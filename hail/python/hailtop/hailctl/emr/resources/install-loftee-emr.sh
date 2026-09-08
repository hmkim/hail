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
WORKDIR=/mnt1/loftee-emr-bootstrap
MANIFEST=$WORKDIR/SHA256SUMS

case "$PREFIX" in s3://*) ;; *) echo "resource prefix must use s3://" >&2; exit 2;; esac
[[ "$MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$IMAGE_URI" == *@sha256:* ]]
[[ "$PROCESS_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]
[[ "$STDERR_MAX_BYTES" =~ ^[1-9][0-9]*$ ]]

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR" /mnt/vep_data
if [[ -d /vep_data && ! -L /vep_data ]]; then
  cp -a /vep_data/. /mnt/vep_data/
  rm -rf /vep_data
fi
ln -sfn /mnt/vep_data /vep_data
mkdir -p /vep_data/loftee /vep_data/loftee_data /vep_data/bin /usr/local/libexec
aws s3 cp "$PREFIX/SHA256SUMS" "$MANIFEST" --only-show-errors
printf '%s  %s\n' "$MANIFEST_SHA256" "$MANIFEST" | sha256sum -c -

while read -r checksum filename; do
  aws s3 cp "$PREFIX/$filename" "$WORKDIR/$filename" --only-show-errors
done < "$MANIFEST"
( cd "$WORKDIR" && sha256sum -c SHA256SUMS )

rm -rf /vep_data/loftee
mkdir -p /vep_data/loftee
tar -xzf "$WORKDIR/loftee-v1.0.4_GRCh38.tar.gz" -C /vep_data/loftee --strip-components=1
install -m 0644 "$WORKDIR/gerp_conservation_scores.homo_sapiens.GRCh38.bw" /vep_data/loftee_data/
install -m 0644 "$WORKDIR/human_ancestor.fa.gz" /vep_data/loftee_data/
install -m 0644 "$WORKDIR/human_ancestor.fa.gz.fai" /vep_data/loftee_data/
install -m 0644 "$WORKDIR/human_ancestor.fa.gz.gzi" /vep_data/loftee_data/
gzip -dc "$WORKDIR/loftee.sql.gz" > /vep_data/loftee_data/loftee.sql
install -m 0755 "$WORKDIR/samtools-faidx-shim" /vep_data/bin/samtools
install -m 0644 "$WORKDIR/vep113-GRCh38-loftee-emr.json" /vep_data/vep.json
python3.12 - /vep_data/vep.json "$PROCESS_TIMEOUT_SECONDS" "$STDERR_MAX_BYTES" <<'PY'
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
chmod -R a+rX /vep_data

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
