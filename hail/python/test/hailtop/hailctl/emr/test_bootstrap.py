import subprocess
from importlib import resources


def test_bootstrap_rejects_invalid_wheel_filename_before_installing():
    script = resources.files('hailtop.hailctl.emr').joinpath('resources/install-hail-emr.sh')
    result = subprocess.run(
        ['bash', str(script), 's3://bucket/hail.whl', 'a' * 64, 's3://bucket/wheelhouse.tar.gz', 'b' * 64],
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode != 0
    assert 'does not end in a valid Hail wheel filename' in result.stderr


def test_vep_bootstraps_use_only_scoped_docker_wrapper():
    base = resources.files('hailtop.hailctl.emr').joinpath('resources/install-vep-emr.sh').read_text()
    loftee = resources.files('hailtop.hailctl.emr').joinpath('resources/install-loftee-emr.sh').read_text()
    combined = base + loftee

    assert 'usermod -aG docker' not in combined
    assert 'chmod 666' not in combined
    assert 'chmod 777' not in combined
    assert 'hadoop ALL=(root) NOPASSWD: /usr/local/libexec/vep-emr-root *' in combined
    assert 'yarn ALL=(root) NOPASSWD: /usr/local/libexec/vep-emr-root *' in combined
    assert 'exec sudo -n /usr/local/libexec/vep-emr-root "$@"' in combined
    assert ':ro' in base
    assert ':ro' in loftee
    assert 'cleanup_registry_credentials' in base
    assert 'trap cleanup EXIT' in base
    assert 'docker logout' in base
    assert 'rm -f /root/.docker/config.json' in base


def test_vep_bootstraps_inject_process_controls_and_have_valid_shell_syntax():
    contents = {}
    for name in ['install-vep-emr.sh', 'install-loftee-emr.sh']:
        script = resources.files('hailtop.hailctl.emr').joinpath(f'resources/{name}')
        content = script.read_text()
        contents[name] = content
        assert "config['process_timeout_seconds'] = int(timeout)" in content
        assert "config['stderr_max_bytes'] = int(stderr_max)" in content
        assert 'VEP_ROOT=/mnt/vep_data' in content
        assert 'mountpoint -q /mnt' in content
        assert 'tar --no-same-owner --no-same-permissions' in content
        subprocess.run(['bash', '-n', str(script)], check=True)

    base = contents['install-vep-emr.sh']
    loftee = contents['install-loftee-emr.sh']
    assert 'mktemp -d /mnt/vep-emr-bootstrap.XXXXXX' in base
    assert 'CACHE_ARCHIVE=${WORKDIR}/vep-cache.tar.gz' in base
    assert 'aws s3 cp "$CACHE_URI" "$CACHE_ARCHIVE" --only-show-errors' in base
    assert 'ACTUAL_CACHE_SIZE=$(stat -c' in base
    assert 'printf \'%s  %s\\n\' "$CACHE_SHA256" "$CACHE_ARCHIVE" | sha256sum -c -' in base
    assert "member_path.parts[0] != 'homo_sapiens'" in base
    assert 'member.issym()' in base
    assert 'tar --no-same-owner --no-same-permissions -xzf "$CACHE_ARCHIVE"' in base
    assert 'stream_verify_sha256' not in base
    assert base.index('sha256sum -c -') < base.index(
        'tar --no-same-owner --no-same-permissions -xzf "$CACHE_ARCHIVE"'
    )
    assert 'arguments do not match the installed GRCh38 VEP profile' in base
    assert 'mktemp -d /mnt/loftee-emr-bootstrap.XXXXXX' in loftee
    assert 'WORKDIR=/mnt1/' not in loftee
    assert 'LOFTEE_ARCHIVE_NAME=loftee-v1.0.4_GRCh38.tar.gz' in loftee
    assert '"$LOFTEE_ARCHIVE_NAME" "$VEP_CONFIG_NAME" "$SAMTOOLS_SHIM_NAME"' in loftee
    assert 'sha256sum -c -' in loftee
    assert 'aws s3 cp "$PREFIX/$GERP_NAME" - --only-show-errors' in loftee
    assert '| stream_verify_sha256 "${RESOURCE_SHA256[$GERP_NAME]}" "$SIZE_LOG"' in loftee
    assert 'ACTUAL_LOFTEE_SIZE=$(awk' in loftee
    assert 'LoFTEE resource size mismatch' in loftee
    assert 'arguments do not match the installed GRCh38 LoFTEE profile' in loftee
    assert 'aws s3 cp "$PREFIX/$ANCESTOR_NAME" - --only-show-errors' in loftee
    assert 'cp -a /vep_data' not in loftee
    assert 'rm -rf /vep_data' not in loftee
    assert 'ln -sfn /mnt/vep_data /vep_data' not in loftee
    assert 'readlink -f /vep_data' in loftee
