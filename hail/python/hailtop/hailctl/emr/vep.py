import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

import botocore.session
from botocore.exceptions import UnknownRegionError

_SHA256_RE = re.compile(r'[0-9a-f]{64}')
_IMAGE_DIGEST_RE = re.compile(r'[^\s@]+@sha256:[0-9a-f]{64}')
_INSTANCE_TYPE_RE = re.compile(r'[a-z][a-z0-9-]*\.[a-z0-9]+')
_PRIVATE_ECR_RE = re.compile(r'^\d{12}\.dkr\.ecr\.(?P<region>[a-z0-9-]+)\.amazonaws\.com(?:\.cn)?/')


def _validate_emr_region(region: object) -> str:
    if not isinstance(region, str) or not region:
        raise ValueError(f'AWS EMR region must be a non-empty string, found {region!r}')
    session = botocore.session.get_session()
    resolver = session.get_component('endpoint_resolver')
    try:
        partition = resolver.get_partition_for_region(region)
    except UnknownRegionError as exc:
        raise ValueError(f'unknown AWS region {region!r} in botocore endpoint metadata') from exc
    if region not in session.get_available_regions('emr', partition_name=partition):
        raise ValueError(f'AWS region {region!r} does not provide an EMR endpoint in partition {partition!r}')
    return region


def _s3_bucket(uri: str) -> str:
    return urlparse(uri).netloc


@dataclass(frozen=True)
class VEPProfileManifest:
    schema_version: int
    reference_genome: str
    vep_version: str
    loftee_version: str
    supported_regions: list[str]
    image_uri: str
    cache_uri: str
    cache_sha256: str
    cache_size_bytes: int
    base_config_uri: str
    base_config_sha256: str
    loftee_resource_prefix_uri: str
    loftee_manifest_sha256: str
    loftee_size_bytes: int
    storage_size_gb: int
    recommended_core_instance_type: str
    executor_cores: int
    process_timeout_seconds: int
    stderr_max_bytes: int

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> 'VEPProfileManifest':
        required = {field.name for field in cls.__dataclass_fields__.values()}
        missing = required - data.keys()
        unknown = data.keys() - required
        if missing:
            raise ValueError(f"VEP profile is missing field(s): {', '.join(sorted(missing))}")
        if unknown:
            raise ValueError(f"VEP profile has unknown field(s): {', '.join(sorted(unknown))}")
        profile = cls(**data)
        profile.validate()
        return profile

    def validate(self) -> None:
        if type(self.schema_version) is not int or self.schema_version != 1:
            raise ValueError(f'unsupported VEP profile schema_version: {self.schema_version}')
        if self.reference_genome != 'GRCh38':
            raise ValueError('the initial EMR VEP profile supports only GRCh38')
        if not isinstance(self.vep_version, str) or not self.vep_version:
            raise ValueError('vep_version must be a non-empty string')
        if not isinstance(self.loftee_version, str) or not self.loftee_version:
            raise ValueError('loftee_version must be a non-empty string')
        if not isinstance(self.supported_regions, list) or not self.supported_regions:
            raise ValueError('supported_regions must be a non-empty list of unique AWS EMR regions')
        if len(set(map(str, self.supported_regions))) != len(self.supported_regions):
            raise ValueError('supported_regions must be a non-empty list of unique AWS EMR regions')
        for region in self.supported_regions:
            _validate_emr_region(region)
        if not isinstance(self.image_uri, str) or _IMAGE_DIGEST_RE.fullmatch(self.image_uri) is None:
            raise ValueError('image_uri must be pinned by an immutable sha256 digest')
        for name, uri in (
            ('cache_uri', self.cache_uri),
            ('base_config_uri', self.base_config_uri),
            ('loftee_resource_prefix_uri', self.loftee_resource_prefix_uri),
        ):
            if not isinstance(uri, str) or not uri.startswith('s3://'):
                raise ValueError(f'{name} must be an s3:// URI')
        for name, digest in (
            ('cache_sha256', self.cache_sha256),
            ('base_config_sha256', self.base_config_sha256),
            ('loftee_manifest_sha256', self.loftee_manifest_sha256),
        ):
            if not isinstance(digest, str) or _SHA256_RE.fullmatch(digest) is None:
                raise ValueError(f'{name} must be a lowercase 64-character SHA256 digest')
        for name, value in (
            ('cache_size_bytes', self.cache_size_bytes),
            ('loftee_size_bytes', self.loftee_size_bytes),
            ('storage_size_gb', self.storage_size_gb),
            ('executor_cores', self.executor_cores),
            ('process_timeout_seconds', self.process_timeout_seconds),
            ('stderr_max_bytes', self.stderr_max_bytes),
        ):
            if type(value) is not int or value <= 0:
                raise ValueError(f'{name} must be a positive integer')
        if self.storage_size_gb < 100:
            raise ValueError('storage_size_gb must be at least 100 for VEP')
        if (
            not isinstance(self.recommended_core_instance_type, str)
            or _INSTANCE_TYPE_RE.fullmatch(self.recommended_core_instance_type) is None
        ):
            raise ValueError('recommended_core_instance_type is not a valid EC2 instance type')

    def validate_region(self, region: str) -> None:
        _validate_emr_region(region)
        if region not in self.supported_regions:
            raise ValueError(
                f'VEP profile does not support region {region!r}; '
                f"supported region(s): {', '.join(self.supported_regions)}"
            )

    def region_warnings(self, region: str, bucket_region: Callable[[str], str]) -> list[str]:
        self.validate_region(region)
        messages = []
        image_match = _PRIVATE_ECR_RE.match(self.image_uri)
        if image_match is not None and image_match.group('region') != region:
            image_region = image_match.group('region')
            messages.append(
                f'VEP image registry is in {image_region}, not {region}; the pull may fail and '
                'cross-region transfer charges may apply'
            )

        buckets: dict[str, list[str]] = {}
        for name, uri in (
            ('cache_uri', self.cache_uri),
            ('base_config_uri', self.base_config_uri),
            ('loftee_resource_prefix_uri', self.loftee_resource_prefix_uri),
        ):
            buckets.setdefault(_s3_bucket(uri), []).append(name)

        for bucket, names in buckets.items():
            try:
                actual_region = bucket_region(bucket)
            except Exception as exc:  # best-effort warning must not block a valid launch
                messages.append(f'could not verify the region of S3 bucket {bucket!r}: {exc}')
                continue
            if actual_region != region:
                messages.append(
                    f'S3 bucket {bucket!r} used by {", ".join(names)} is in {actual_region}, not {region}; '
                    'cross-region transfer charges may apply'
                )
        return messages

    def base_bootstrap_args(self, region: str) -> list[str]:
        self.validate_region(region)
        return [
            self.cache_uri,
            self.cache_sha256,
            str(self.cache_size_bytes),
            self.base_config_uri,
            self.base_config_sha256,
            self.image_uri,
            region,
            str(self.process_timeout_seconds),
            str(self.stderr_max_bytes),
        ]

    def loftee_bootstrap_args(self) -> list[str]:
        return [
            self.loftee_resource_prefix_uri.rstrip('/'),
            self.loftee_manifest_sha256,
            self.image_uri,
            str(self.loftee_size_bytes),
            str(self.process_timeout_seconds),
            str(self.stderr_max_bytes),
        ]


def load_vep_profile(path: str) -> VEPProfileManifest:
    profile_path = Path(path).expanduser()
    try:
        data = json.loads(profile_path.read_text())
    except OSError as exc:
        raise ValueError(f'could not read VEP profile {path!r}: {exc}') from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f'VEP profile {path!r} is not valid JSON: {exc}') from exc
    if not isinstance(data, dict):
        raise ValueError('VEP profile must contain a JSON object')
    return VEPProfileManifest.from_dict(data)
