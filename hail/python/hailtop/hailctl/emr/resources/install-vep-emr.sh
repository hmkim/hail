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
VEP_ROOT=/mnt/vep_data
CONFIG_PATH=${VEP_ROOT}/vep113-GRCh38-base.json
REGISTRY=${IMAGE_URI%%/*}

case "$CACHE_URI" in s3://*) ;; *) echo "cache URI must use s3://" >&2; exit 2;; esac
case "$CONFIG_URI" in s3://*) ;; *) echo "config URI must use s3://" >&2; exit 2;; esac
[[ "$CACHE_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$CONFIG_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$IMAGE_URI" == *@sha256:* ]]
[[ "$PROCESS_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]
[[ "$STDERR_MAX_BYTES" =~ ^[1-9][0-9]*$ ]]

if ! mountpoint -q /mnt; then
  echo "/mnt must be the profile-sized VEP volume" >&2
  exit 1
fi
if [[ -e /vep_data || -L /vep_data || -e "$VEP_ROOT" || -L "$VEP_ROOT" ]]; then
  echo "VEP data paths must not exist before the base bootstrap" >&2
  exit 1
fi
install -d -o root -g root -m 0755 "$VEP_ROOT"
ln -s "$VEP_ROOT" /vep_data
WORKDIR=$(mktemp -d /mnt/vep-emr-bootstrap.XXXXXX)
trap 'rm -rf -- "$WORKDIR"' EXIT
CONFIG_STAGED=${WORKDIR}/vep113-GRCh38-base.json

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

dnf install -y docker
systemctl enable --now docker

# Cache data is not executable Hail code. Stream it directly to the final profile-sized volume.
# The pipeline still fails the bootstrap before cluster readiness if the profile digest does not match.
aws s3 cp "$CACHE_URI" - --only-show-errors \
  | stream_verify_sha256 "$CACHE_SHA256" \
  | tar --no-same-owner --no-same-permissions -xzf - -C "$VEP_ROOT"

# The config controls the executable argument vector, so verify it before installing it.
aws s3 cp "$CONFIG_URI" "$CONFIG_STAGED" --only-show-errors
printf '%s  %s\n' "$CONFIG_SHA256" "$CONFIG_STAGED" | sha256sum -c -
install -m 0644 "$CONFIG_STAGED" "$CONFIG_PATH"
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
chmod -R a+rX "$VEP_ROOT"

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
