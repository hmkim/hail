import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_SHA256_RE = re.compile(r'[0-9a-f]{64}')
_AWS_REGION_RE = re.compile(r'[a-z]{2}(-[a-z]+)+-\d+')
_IMAGE_DIGEST_RE = re.compile(r'[^\s@]+@sha256:[0-9a-f]{64}')
_INSTANCE_TYPE_RE = re.compile(r'[a-z][a-z0-9-]*\.[a-z0-9]+')


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
        if self.schema_version != 1:
            raise ValueError(f'unsupported VEP profile schema_version: {self.schema_version}')
        if self.reference_genome != 'GRCh38':
            raise ValueError('the initial EMR VEP profile supports only GRCh38')
        if not self.vep_version or not self.loftee_version:
            raise ValueError('vep_version and loftee_version must not be empty')
        if (
            not isinstance(self.supported_regions, list)
            or not self.supported_regions
            or any(_AWS_REGION_RE.fullmatch(region) is None for region in self.supported_regions)
            or len(set(self.supported_regions)) != len(self.supported_regions)
        ):
            raise ValueError('supported_regions must be a non-empty list of unique AWS regions')
        if _IMAGE_DIGEST_RE.fullmatch(self.image_uri) is None:
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
            if not isinstance(value, int) or value <= 0:
                raise ValueError(f'{name} must be a positive integer')
        if self.storage_size_gb < 100:
            raise ValueError('storage_size_gb must be at least 100 for VEP')
        if _INSTANCE_TYPE_RE.fullmatch(self.recommended_core_instance_type) is None:
            raise ValueError('recommended_core_instance_type is not a valid EC2 instance type')

    def validate_region(self, region: str) -> None:
        if region not in self.supported_regions:
            raise ValueError(
                f'VEP profile does not support region {region!r}; '
                f"supported region(s): {', '.join(self.supported_regions)}"
            )

    def base_bootstrap_args(self, region: str) -> list[str]:
        self.validate_region(region)
        return [
            self.cache_uri,
            self.cache_sha256,
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
