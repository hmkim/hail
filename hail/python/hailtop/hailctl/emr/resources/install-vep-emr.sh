#!/bin/bash
set -euo pipefail

if [[ $# -ne 9 ]]; then
  echo "usage: $0 CACHE_URI CACHE_SHA256 CACHE_SIZE_BYTES CONFIG_URI CONFIG_SHA256 IMAGE_URI AWS_REGION PROCESS_TIMEOUT_SECONDS STDERR_MAX_BYTES" >&2
  exit 2
fi

if [[ ${EUID} -ne 0 ]]; then
  exec sudo "$0" "$@"
fi

CACHE_URI=$1
CACHE_SHA256=$2
CACHE_SIZE_BYTES=$3
CONFIG_URI=$4
CONFIG_SHA256=$5
IMAGE_URI=$6
REGION=$7
PROCESS_TIMEOUT_SECONDS=$8
STDERR_MAX_BYTES=$9
VEP_ROOT=/mnt/vep_data
CONFIG_PATH=${VEP_ROOT}/vep113-GRCh38-base.json
REGISTRY=${IMAGE_URI%%/*}

case "$CACHE_URI" in s3://*) ;; *) echo "cache URI must use s3://" >&2; exit 2;; esac
case "$CONFIG_URI" in s3://*) ;; *) echo "config URI must use s3://" >&2; exit 2;; esac
[[ "$CACHE_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$CACHE_SIZE_BYTES" =~ ^[1-9][0-9]*$ ]]
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

is_private_ecr_registry() {
  [[ "$REGISTRY" == *.dkr.ecr.*.amazonaws.com || "$REGISTRY" == *.dkr.ecr.*.amazonaws.com.cn ]]
}

cleanup_registry_credentials() {
  if is_private_ecr_registry && command -v docker >/dev/null 2>&1; then
    docker logout "$REGISTRY" >/dev/null 2>&1 || true
  fi
  rm -f /root/.docker/config.json
}

cleanup() {
  local status=$?
  trap - EXIT
  cleanup_registry_credentials
  rm -rf -- "$WORKDIR"
  exit "$status"
}
trap cleanup EXIT
CACHE_ARCHIVE=${WORKDIR}/vep-cache.tar.gz
CONFIG_STAGED=${WORKDIR}/vep113-GRCh38-base.json

dnf install -y docker
systemctl enable --now docker

# Cache archives are staged on the profile-sized volume and verified before extraction.
aws s3 cp "$CACHE_URI" "$CACHE_ARCHIVE" --only-show-errors
ACTUAL_CACHE_SIZE=$(stat -c '%s' "$CACHE_ARCHIVE")
if [[ "$ACTUAL_CACHE_SIZE" != "$CACHE_SIZE_BYTES" ]]; then
  echo "VEP cache size mismatch: expected $CACHE_SIZE_BYTES bytes, found $ACTUAL_CACHE_SIZE" >&2
  exit 1
fi
printf '%s  %s\n' "$CACHE_SHA256" "$CACHE_ARCHIVE" | sha256sum -c -
python3.12 - "$CACHE_ARCHIVE" <<'PY'
import pathlib
import sys
import tarfile

path = sys.argv[1]
with tarfile.open(path, 'r:gz') as archive:
    for member in archive:
        member_path = pathlib.PurePosixPath(member.name)
        if (
            member_path.is_absolute()
            or '..' in member_path.parts
            or not member_path.parts
            or member_path.parts[0] != 'homo_sapiens'
            or member.issym()
            or member.islnk()
            or member.isdev()
        ):
            raise SystemExit(f'unsafe VEP cache archive member: {member.name!r}')
PY
tar --no-same-owner --no-same-permissions -xzf "$CACHE_ARCHIVE" -C "$VEP_ROOT"
rm -f "$CACHE_ARCHIVE"

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

if is_private_ecr_registry; then
  aws ecr get-login-password --region "$REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
fi
docker pull "$IMAGE_URI"
cleanup_registry_credentials

mkdir -p /usr/local/libexec
cat > /usr/local/libexec/vep-emr-root <<EOF
#!/bin/bash
set -euo pipefail
if [[ \$# -ne 12 \
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
  || "\${11:-}" != "--output_file" \
  || "\${12:-}" != "STDOUT" ]]; then
  echo "arguments do not match the installed GRCh38 VEP profile" >&2
  exit 2
fi
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
