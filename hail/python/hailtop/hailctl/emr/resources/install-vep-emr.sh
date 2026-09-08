#!/bin/bash
set -euo pipefail

if [[ $# -ne 8 ]]; then
  echo "usage: $0 CACHE_URI CACHE_SHA256 CONFIG_URI CONFIG_SHA256 IMAGE_URI AWS_REGION PROCESS_TIMEOUT_SECONDS STDERR_MAX_BYTES" >&2
  exit 2
fi

if [[ ${EUID} -ne 0 ]]; then
  exec sudo "$0" "$@"
fi

CACHE_URI=$1
CACHE_SHA256=$2
CONFIG_URI=$3
CONFIG_SHA256=$4
IMAGE_URI=$5
REGION=$6
PROCESS_TIMEOUT_SECONDS=$7
STDERR_MAX_BYTES=$8
CACHE_ARCHIVE=/tmp/vep-cache.tar.gz
CONFIG_PATH=/vep_data/vep113-GRCh38-base.json
REGISTRY=${IMAGE_URI%%/*}

case "$CACHE_URI" in s3://*) ;; *) echo "cache URI must use s3://" >&2; exit 2;; esac
case "$CONFIG_URI" in s3://*) ;; *) echo "config URI must use s3://" >&2; exit 2;; esac
[[ "$CACHE_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$CONFIG_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$IMAGE_URI" == *@sha256:* ]]
[[ "$PROCESS_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]
[[ "$STDERR_MAX_BYTES" =~ ^[1-9][0-9]*$ ]]

dnf install -y docker
systemctl enable --now docker

mkdir -p /vep_data
aws s3 cp "$CACHE_URI" "$CACHE_ARCHIVE" --only-show-errors
printf '%s  %s\n' "$CACHE_SHA256" "$CACHE_ARCHIVE" | sha256sum -c -
tar -xzf "$CACHE_ARCHIVE" -C /vep_data
rm -f "$CACHE_ARCHIVE"

aws s3 cp "$CONFIG_URI" "$CONFIG_PATH" --only-show-errors
printf '%s  %s\n' "$CONFIG_SHA256" "$CONFIG_PATH" | sha256sum -c -
python3.12 - "$CONFIG_PATH" "$PROCESS_TIMEOUT_SECONDS" "$STDERR_MAX_BYTES" <<'PY'
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

if [[ "$REGISTRY" == *.dkr.ecr.*.amazonaws.com ]]; then
  aws ecr get-login-password --region "$REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
fi
docker pull "$IMAGE_URI"
if [[ "$REGISTRY" == *.dkr.ecr.*.amazonaws.com ]]; then
  docker logout "$REGISTRY" >/dev/null || true
fi
rm -f /root/.docker/config.json

mkdir -p /usr/local/libexec
cat > /usr/local/libexec/vep-emr-root <<EOF
#!/bin/bash
set -euo pipefail
exec docker run --rm -i \\
  -v /vep_data:/opt/vep/.vep:ro \\
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

printf '%s\n' \
  '##fileformat=VCFv4.2' \
  '##contig=<ID=chr22,length=50818468>' \
  $'#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO' \
  $'chr22\t29121087\t.\tC\tT\t.\tPASS\t.' \
  | /usr/local/bin/vep-emr \
      --format vcf --json --cache --offline --assembly GRCh38 --minimal \
      --allele_number --no_stats --output_file STDOUT \
  | grep -q '"most_severe_consequence":"intron_variant"'

echo "VEP 113 GRCh38 base bootstrap complete"
