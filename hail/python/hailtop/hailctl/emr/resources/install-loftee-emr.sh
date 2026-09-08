#!/bin/bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "usage: $0 RESOURCE_PREFIX SHA256SUMS_SHA256 IMAGE_URI PROCESS_TIMEOUT_SECONDS STDERR_MAX_BYTES" >&2
  exit 2
fi
if [[ ${EUID} -ne 0 ]]; then
  exec sudo "$0" "$@"
fi

PREFIX=${1%/}
MANIFEST_SHA256=$2
IMAGE_URI=$3
PROCESS_TIMEOUT_SECONDS=$4
STDERR_MAX_BYTES=$5
VEP_ROOT=/mnt/vep_data

case "$PREFIX" in s3://*) ;; *) echo "resource prefix must use s3://" >&2; exit 2;; esac
[[ "$MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$IMAGE_URI" == *@sha256:* ]]
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
RESOURCE_SUMS=$WORKDIR/SHA256SUMS.resources
LOFTEE_ARCHIVE_NAME=loftee-v1.0.4_GRCh38.tar.gz
LOFTEE_ARCHIVE_SHA256=
install -d -o root -g root -m 0755 \
  "$VEP_ROOT/loftee" "$VEP_ROOT/loftee_data" "$VEP_ROOT/bin" /usr/local/libexec

stream_verify_sha256() {
  local expected=$1
  python3.12 -c '
import hashlib
import sys

expected = sys.argv[1]
digest = hashlib.sha256()
while chunk := sys.stdin.buffer.read(8 * 1024 * 1024):
    digest.update(chunk)
    sys.stdout.buffer.write(chunk)
actual = digest.hexdigest()
if actual != expected:
    print(f"SHA256 mismatch: expected {expected}, found {actual}", file=sys.stderr)
    raise SystemExit(1)
' "$expected"
}

aws s3 cp "$PREFIX/SHA256SUMS" "$MANIFEST" --only-show-errors
printf '%s  %s\n' "$MANIFEST_SHA256" "$MANIFEST" | sha256sum -c -
: > "$RESOURCE_SUMS"

while read -r checksum filename; do
  [[ "$checksum" =~ ^[0-9a-f]{64}$ ]]
  filename=${filename#\*}
  case "$filename" in
    /*|../*|*/../*|*/..) echo "LoFTEE manifest contains an unsafe path: $filename" >&2; exit 1;;
  esac
  if [[ "$filename" == "$LOFTEE_ARCHIVE_NAME" ]]; then
    LOFTEE_ARCHIVE_SHA256=$checksum
    continue
  fi
  destination=$WORKDIR/$filename
  mkdir -p "$(dirname "$destination")"
  aws s3 cp "$PREFIX/$filename" "$destination" --only-show-errors
  printf '%s  %s\n' "$checksum" "$filename" >> "$RESOURCE_SUMS"
done < "$MANIFEST"
[[ "$LOFTEE_ARCHIVE_SHA256" =~ ^[0-9a-f]{64}$ ]]
( cd "$WORKDIR" && sha256sum -c SHA256SUMS.resources )

# Stream the large plugin archive to its final directory. The verified manifest supplies its digest.
aws s3 cp "$PREFIX/$LOFTEE_ARCHIVE_NAME" - --only-show-errors \
  | stream_verify_sha256 "$LOFTEE_ARCHIVE_SHA256" \
  | tar --no-same-owner --no-same-permissions -xzf - \
      -C "$VEP_ROOT/loftee" --strip-components=1
install -m 0644 "$WORKDIR/gerp_conservation_scores.homo_sapiens.GRCh38.bw" "$VEP_ROOT/loftee_data/"
install -m 0644 "$WORKDIR/human_ancestor.fa.gz" "$VEP_ROOT/loftee_data/"
install -m 0644 "$WORKDIR/human_ancestor.fa.gz.fai" "$VEP_ROOT/loftee_data/"
install -m 0644 "$WORKDIR/human_ancestor.fa.gz.gzi" "$VEP_ROOT/loftee_data/"
gzip -dc "$WORKDIR/loftee.sql.gz" > "$VEP_ROOT/loftee_data/loftee.sql"
install -m 0755 "$WORKDIR/samtools-faidx-shim" "$VEP_ROOT/bin/samtools"
install -m 0644 "$WORKDIR/vep113-GRCh38-loftee-emr.json" "$VEP_ROOT/vep.json"
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
