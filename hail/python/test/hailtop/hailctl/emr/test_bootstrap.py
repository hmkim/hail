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
    assert 'docker logout' in base
    assert 'rm -f /root/.docker/config.json' in base


def test_vep_bootstraps_inject_process_controls_and_have_valid_shell_syntax():
    for name in ['install-vep-emr.sh', 'install-loftee-emr.sh']:
        script = resources.files('hailtop.hailctl.emr').joinpath(f'resources/{name}')
        content = script.read_text()
        assert "config['process_timeout_seconds'] = int(timeout)" in content
        assert "config['stderr_max_bytes'] = int(stderr_max)" in content
        subprocess.run(['bash', '-n', str(script)], check=True)
