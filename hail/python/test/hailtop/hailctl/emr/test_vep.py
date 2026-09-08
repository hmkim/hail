import json

import pytest

from hailtop.hailctl.emr.vep import VEPProfileManifest, load_vep_profile


def profile_dict(**overrides):
    values = {
        'schema_version': 1,
        'reference_genome': 'GRCh38',
        'vep_version': '113.4',
        'loftee_version': '1.0.4_GRCh38',
        'supported_regions': ['us-east-1'],
        'image_uri': 'public.ecr.aws/example/vep@sha256:' + 'a' * 64,
        'cache_uri': 's3://example/vep/cache.tar.gz',
        'cache_sha256': 'b' * 64,
        'cache_size_bytes': 25_000_000_000,
        'base_config_uri': 's3://example/vep/base-config.json',
        'base_config_sha256': 'c' * 64,
        'loftee_resource_prefix_uri': 's3://example/vep/loftee/',
        'loftee_manifest_sha256': 'd' * 64,
        'loftee_size_bytes': 14_000_000_000,
        'storage_size_gb': 200,
        'recommended_core_instance_type': 'r5.2xlarge',
        'executor_cores': 1,
        'process_timeout_seconds': 600,
        'stderr_max_bytes': 1_048_576,
    }
    values.update(overrides)
    return values


def test_profile_parses_and_builds_bootstrap_args(tmp_path):
    path = tmp_path / 'vep.json'
    path.write_text(json.dumps(profile_dict()))
    profile = load_vep_profile(str(path))
    assert profile.reference_genome == 'GRCh38'
    base_args = profile.base_bootstrap_args('us-east-1')
    assert base_args[-3:] == ['us-east-1', '600', '1048576']
    assert profile.loftee_bootstrap_args()[-2:] == ['600', '1048576']
    assert profile.loftee_bootstrap_args()[0] == 's3://example/vep/loftee'


@pytest.mark.parametrize(
    ('overrides', 'message'),
    [
        ({'schema_version': 2}, 'schema_version'),
        ({'reference_genome': 'GRCh37'}, 'only GRCh38'),
        ({'supported_regions': []}, 'supported_regions'),
        ({'supported_regions': ['us-east-1', 'us-east-1']}, 'supported_regions'),
        ({'image_uri': 'example/vep:latest'}, 'immutable sha256'),
        ({'cache_uri': 'https://example/cache'}, 's3://'),
        ({'cache_sha256': 'bad'}, 'SHA256'),
        ({'storage_size_gb': 99}, 'at least 100'),
        ({'executor_cores': 0}, 'positive integer'),
        ({'process_timeout_seconds': 0}, 'positive integer'),
        ({'stderr_max_bytes': 0}, 'positive integer'),
        ({'recommended_core_instance_type': 'invalid'}, 'EC2 instance type'),
    ],
)
def test_profile_rejects_invalid_values(overrides, message):
    with pytest.raises(ValueError, match=message):
        VEPProfileManifest.from_dict(profile_dict(**overrides))


def test_profile_rejects_missing_and_unknown_fields():
    missing = profile_dict()
    del missing['cache_uri']
    with pytest.raises(ValueError, match='missing field'):
        VEPProfileManifest.from_dict(missing)
    with pytest.raises(ValueError, match='unknown field'):
        VEPProfileManifest.from_dict(profile_dict(extra='value'))


def test_profile_rejects_unsupported_region():
    profile = VEPProfileManifest.from_dict(profile_dict())
    with pytest.raises(ValueError, match='does not support region'):
        profile.validate_region('ap-northeast-2')
