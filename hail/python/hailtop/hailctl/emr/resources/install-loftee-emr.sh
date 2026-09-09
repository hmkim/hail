#!/bin/bash
set -euo pipefail

if [[ $# -ne 6 ]]; then
  echo "usage: $0 RESOURCE_PREFIX SHA256SUMS_SHA256 IMAGE_URI LOFTEE_SIZE_BYTES PROCESS_TIMEOUT_SECONDS STDERR_MAX_BYTES" >&2
  exit 2
fi
if [[ ${EUID} -ne 0 ]]; then
  exec sudo "$0" "$@"
fi

PREFIX=${1%/}
MANIFEST_SHA256=$2
IMAGE_URI=$3
LOFTEE_SIZE_BYTES=$4
PROCESS_TIMEOUT_SECONDS=$5
STDERR_MAX_BYTES=$6
VEP_ROOT=/mnt/vep_data

case "$PREFIX" in s3://*) ;; *) echo "resource prefix must use s3://" >&2; exit 2;; esac
[[ "$MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$IMAGE_URI" == *@sha256:* ]]
[[ "$LOFTEE_SIZE_BYTES" =~ ^[1-9][0-9]*$ ]]
[[ "$PROCESS_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]
[[ "$STDERR_MAX_BYTES" =~ ^[1-9][0-9]*$ ]]

if ! mountpoint -q /mnt; then
  echo "/mnt must be the profile-sized VEP volume" >&2
  exit 1
fi
if [[ ! -L /vep_data || "$(readlink -f /vep_data)" != "$VEP_ROOT" || ! -d "$VEP_ROOT" ]]; then
  echo "base VEP bootstrap did not establish /vep_data -> $VEP_ROOT" >&2
  exit 1
fi
if [[ -e "$VEP_ROOT/loftee" || -L "$VEP_ROOT/loftee" ]]; then
  echo "LoFTEE destination unexpectedly exists before installation" >&2
  exit 1
fi
WORKDIR=$(mktemp -d /mnt/loftee-emr-bootstrap.XXXXXX)
trap 'rm -rf -- "$WORKDIR"' EXIT
MANIFEST=$WORKDIR/SHA256SUMS
LOFTEE_ARCHIVE_NAME=loftee-v1.0.4_GRCh38.tar.gz
VEP_CONFIG_NAME=vep113-GRCh38-loftee-emr.json
SAMTOOLS_SHIM_NAME=samtools-faidx-shim
GERP_NAME=gerp_conservation_scores.homo_sapiens.GRCh38.bw
ANCESTOR_NAME=human_ancestor.fa.gz
ANCESTOR_FAI_NAME=human_ancestor.fa.gz.fai
ANCESTOR_GZI_NAME=human_ancestor.fa.gz.gzi
LOFTEE_SQL_NAME=loftee.sql.gz
SIZE_LOG=$WORKDIR/resource-sizes
: > "$SIZE_LOG"
declare -A RESOURCE_SHA256
install -d -o root -g root -m 0755 \
  "$VEP_ROOT/loftee" "$VEP_ROOT/loftee_data" "$VEP_ROOT/bin" /usr/local/libexec

stream_verify_sha256() {
  local expected=$1
  local size_log=$2
  python3.12 -c '
import hashlib
import sys

expected, size_log = sys.argv[1:]
digest = hashlib.sha256()
size = 0
while chunk := sys.stdin.buffer.read(8 * 1024 * 1024):
    digest.update(chunk)
    size += len(chunk)
    sys.stdout.buffer.write(chunk)
actual = digest.hexdigest()
if actual != expected:
    print(f"SHA256 mismatch: expected {expected}, found {actual}", file=sys.stderr)
    raise SystemExit(1)
with open(size_log, "a") as f:
    print(size, file=f)
' "$expected" "$size_log"
}

aws s3 cp "$PREFIX/SHA256SUMS" "$MANIFEST" --only-show-errors
printf '%s  %s\n' "$MANIFEST_SHA256" "$MANIFEST" | sha256sum -c -

while read -r checksum filename; do
  [[ "$checksum" =~ ^[0-9a-f]{64}$ ]]
  filename=${filename#\*}
  case "$filename" in
    "$LOFTEE_ARCHIVE_NAME"|"$VEP_CONFIG_NAME"|"$SAMTOOLS_SHIM_NAME"|\
    "$GERP_NAME"|"$ANCESTOR_NAME"|"$ANCESTOR_FAI_NAME"|"$ANCESTOR_GZI_NAME"|"$LOFTEE_SQL_NAME") ;;
    *) echo "LoFTEE manifest contains an unexpected path: $filename" >&2; exit 1;;
  esac
  RESOURCE_SHA256["$filename"]=$checksum
done < "$MANIFEST"

for filename in \
  "$LOFTEE_ARCHIVE_NAME" "$VEP_CONFIG_NAME" "$SAMTOOLS_SHIM_NAME" \
  "$GERP_NAME" "$ANCESTOR_NAME" "$ANCESTOR_FAI_NAME" "$ANCESTOR_GZI_NAME" "$LOFTEE_SQL_NAME"
do
  [[ "${RESOURCE_SHA256[$filename]:-}" =~ ^[0-9a-f]{64}$ ]]
done

# These resources contain executable code or its command configuration. Stage and verify them first.
for filename in "$LOFTEE_ARCHIVE_NAME" "$VEP_CONFIG_NAME" "$SAMTOOLS_SHIM_NAME"; do
  aws s3 cp "$PREFIX/$filename" "$WORKDIR/$filename" --only-show-errors
  printf '%s  %s\n' "${RESOURCE_SHA256[$filename]}" "$WORKDIR/$filename" | sha256sum -c -
  stat -c '%s' "$WORKDIR/$filename" >> "$SIZE_LOG"
done
if tar -tzf "$WORKDIR/$LOFTEE_ARCHIVE_NAME" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "LoFTEE plugin archive contains an unsafe path" >&2
  exit 1
fi
tar --no-same-owner --no-same-permissions -xzf "$WORKDIR/$LOFTEE_ARCHIVE_NAME" \
  -C "$VEP_ROOT/loftee" --strip-components=1
install -m 0755 "$WORKDIR/$SAMTOOLS_SHIM_NAME" "$VEP_ROOT/bin/samtools"
install -m 0644 "$WORKDIR/$VEP_CONFIG_NAME" "$VEP_ROOT/vep.json"

# Large reference datasets stream directly to their final paths while retaining provenance checks.
aws s3 cp "$PREFIX/$GERP_NAME" - --only-show-errors \
  | stream_verify_sha256 "${RESOURCE_SHA256[$GERP_NAME]}" "$SIZE_LOG" \
  > "$VEP_ROOT/loftee_data/$GERP_NAME"
aws s3 cp "$PREFIX/$ANCESTOR_NAME" - --only-show-errors \
  | stream_verify_sha256 "${RESOURCE_SHA256[$ANCESTOR_NAME]}" "$SIZE_LOG" \
  > "$VEP_ROOT/loftee_data/$ANCESTOR_NAME"
aws s3 cp "$PREFIX/$ANCESTOR_FAI_NAME" - --only-show-errors \
  | stream_verify_sha256 "${RESOURCE_SHA256[$ANCESTOR_FAI_NAME]}" "$SIZE_LOG" \
  > "$VEP_ROOT/loftee_data/$ANCESTOR_FAI_NAME"
aws s3 cp "$PREFIX/$ANCESTOR_GZI_NAME" - --only-show-errors \
  | stream_verify_sha256 "${RESOURCE_SHA256[$ANCESTOR_GZI_NAME]}" "$SIZE_LOG" \
  > "$VEP_ROOT/loftee_data/$ANCESTOR_GZI_NAME"
aws s3 cp "$PREFIX/$LOFTEE_SQL_NAME" - --only-show-errors \
  | stream_verify_sha256 "${RESOURCE_SHA256[$LOFTEE_SQL_NAME]}" "$SIZE_LOG" \
  | gzip -dc > "$VEP_ROOT/loftee_data/loftee.sql"
ACTUAL_LOFTEE_SIZE=$(awk '{ total += $1 } END { print total + 0 }' "$SIZE_LOG")
if [[ "$ACTUAL_LOFTEE_SIZE" != "$LOFTEE_SIZE_BYTES" ]]; then
  echo "LoFTEE resource size mismatch: expected $LOFTEE_SIZE_BYTES bytes, found $ACTUAL_LOFTEE_SIZE" >&2
  exit 1
fi
chmod 0644 "$VEP_ROOT/loftee_data/$GERP_NAME" \
  "$VEP_ROOT/loftee_data/$ANCESTOR_NAME" \
  "$VEP_ROOT/loftee_data/$ANCESTOR_FAI_NAME" \
  "$VEP_ROOT/loftee_data/$ANCESTOR_GZI_NAME" \
  "$VEP_ROOT/loftee_data/loftee.sql"
python3.12 - "$VEP_ROOT/vep.json" "$PROCESS_TIMEOUT_SECONDS" "$STDERR_MAX_BYTES" <<'PY'
import json
import sys

path, timeout, stderr_max = sys.argv[1:]
with open(path) as f:
    config = json.load(f)
config['process_timeout_seconds'] = int(timeout)
config['stderr_max_bytes'] = int(stderr_max)
with open(path, 'w') as f:
    json.dump(config, f, sort_keys=True)
    f.write('\n')
PY
chmod -R a+rX "$VEP_ROOT"

cat > /usr/local/libexec/vep-emr-root <<EOF
#!/bin/bash
set -euo pipefail
if [[ \$# -ne 16 \
  || "\${1:-}" != "--format" \
  || "\${2:-}" != "vcf" \
  || ( "\${3:-}" != "--json" && "\${3:-}" != "--vcf" ) \
  || "\${4:-}" != "--cache" \
  || "\${5:-}" != "--offline" \
  || "\${6:-}" != "--assembly" \
  || "\${7:-}" != "GRCh38" \
  || "\${8:-}" != "--minimal" \
  || "\${9:-}" != "--allele_number" \
  || "\${10:-}" != "--no_stats" \
  || "\${11:-}" != "--dir_plugins" \
  || "\${12:-}" != "/opt/vep/loftee" \
  || "\${13:-}" != "--plugin" \
  || "\${14:-}" != "LoF,loftee_path:/opt/vep/loftee,gerp_bigwig:/opt/vep/loftee_data/gerp_conservation_scores.homo_sapiens.GRCh38.bw,human_ancestor_fa:/opt/vep/loftee_data/human_ancestor.fa.gz,conservation_file:/opt/vep/loftee_data/loftee.sql" \
  || "\${15:-}" != "--output_file" \
  || "\${16:-}" != "STDOUT" ]]; then
  echo "arguments do not match the installed GRCh38 LoFTEE profile" >&2
  exit 2
fi
exec docker run --rm -i \\
  -v /vep_data/homo_sapiens:/opt/vep/.vep/homo_sapiens:ro \\
  -v /vep_data/loftee:/opt/vep/loftee:ro \\
  -v /vep_data/loftee_data:/opt/vep/loftee_data:ro \\
  -v /vep_data/bin/samtools:/usr/local/bin/samtools:ro \\
  "$IMAGE_URI" \\
  /opt/vep/src/ensembl-vep/vep "\$@"
EOF
chmod 0755 /usr/local/libexec/vep-emr-root

cat > /usr/local/bin/vep-emr <<'EOF'
#!/bin/bash
set -euo pipefail
exec sudo -n /usr/local/libexec/vep-emr-root "$@"
EOF
chmod 0755 /usr/local/bin/vep-emr
cat > /etc/sudoers.d/hail-vep <<'EOF'
hadoop ALL=(root) NOPASSWD: /usr/local/libexec/vep-emr-root *
yarn ALL=(root) NOPASSWD: /usr/local/libexec/vep-emr-root *
EOF
chmod 0440 /etc/sudoers.d/hail-vep
visudo -cf /etc/sudoers.d/hail-vep

run_smoke() {
  local user=$1
  printf '%s\n' \
    '##fileformat=VCFv4.2' \
    '##contig=<ID=chr22,length=50818468>' \
    $'#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO' \
    $'chr22\t28695729\tCHEK2_stop_gained\tC\tA\t.\tPASS\t.' \
    | sudo -u "$user" /usr/local/bin/vep-emr \
        --format vcf --json --cache --offline --assembly GRCh38 --minimal \
        --allele_number --no_stats --dir_plugins /opt/vep/loftee \
        --plugin 'LoF,loftee_path:/opt/vep/loftee,gerp_bigwig:/opt/vep/loftee_data/gerp_conservation_scores.homo_sapiens.GRCh38.bw,human_ancestor_fa:/opt/vep/loftee_data/human_ancestor.fa.gz,conservation_file:/opt/vep/loftee_data/loftee.sql' \
        --output_file STDOUT \
    | grep -q '"lof":"HC"'
}
run_smoke hadoop
run_smoke yarn

rm -rf "$WORKDIR"
test ! -e /root/.docker/config.json

echo "LoFTEE v1.0.4 GRCh38 bootstrap complete"
