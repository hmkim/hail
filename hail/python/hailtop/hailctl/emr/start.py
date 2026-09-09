import copy
from dataclasses import dataclass
from typing import Optional

from .artifact import HailArtifactManifest, major_minor
from .vep import VEPProfileManifest


@dataclass(frozen=True)
class EMRReleaseConfig:
    release_label: str
    spark_version: str
    scala_version: str
    python_executable: str
    storage_connector: str


EMR_RELEASE_CONFIGS = {
    'emr-spark-8.1.0': EMRReleaseConfig(
        release_label='emr-spark-8.1.0',
        spark_version='4.1.1',
        scala_version='2.13.17',
        python_executable='/usr/bin/python3.12',
        storage_connector='s3a',
    )
}

S3A_FILESYSTEM_CLASS = 'org.apache.hadoop.fs.s3a.S3AFileSystem'
S3A_COMMITTER_FACTORY = 'org.apache.hadoop.fs.s3a.commit.S3ACommitterFactory'
S3A_CORE_SITE_PROPERTIES = {
    'fs.s3.impl': S3A_FILESYSTEM_CLASS,
    'fs.s3n.impl': S3A_FILESYSTEM_CLASS,
    'fs.s3a.impl': S3A_FILESYSTEM_CLASS,
    'mapreduce.outputcommitter.factory.scheme.s3a': S3A_COMMITTER_FACTORY,
    'fs.s3a.committer.magic.enabled': 'true',
    'fs.s3a.committer.name': 'magicv2',
    'fs.s3a.committer.magic.track.commits.in.memory.enabled': 'true',
    'fs.s3a.committer.magic.overwrite.and.commit': 'true',
}
DEFAULT_EMR_RELEASE = 'emr-spark-8.1.0'
HAIL_JAR_PATH = '/usr/lib/hail/hail-all-spark.jar'
EMR_PYSPARK_PYTHON = '/usr/bin/python3.12'
VEP_CONFIG_URI = 'file:///vep_data/vep.json'


def release_config(release_label: str) -> EMRReleaseConfig:
    config = EMR_RELEASE_CONFIGS.get(release_label)
    if config is None:
        supported = ', '.join(sorted(EMR_RELEASE_CONFIGS))
        raise ValueError(f'unsupported EMR release {release_label!r}; supported release(s): {supported}')
    return config


def check_release_spark_compatibility(release_label: str, hail_spark_version: str) -> EMRReleaseConfig:
    config = release_config(release_label)
    if major_minor(config.spark_version) != major_minor(hail_spark_version):
        raise ValueError(
            f'EMR release {release_label!r} ships Spark {config.spark_version}, but this Hail artifact '
            f'was built for Spark {hail_spark_version}; the Spark major and minor versions must agree.'
        )
    return config


def validate_artifact_compatibility(
    artifact: HailArtifactManifest,
    release: EMRReleaseConfig,
    hail_spark_version: str,
) -> None:
    if major_minor(artifact.spark_version) != major_minor(hail_spark_version):
        raise ValueError(
            f'artifact was built for Spark {artifact.spark_version}, but hailctl was built for Spark '
            f'{hail_spark_version}'
        )
    if major_minor(artifact.spark_version) != major_minor(release.spark_version):
        raise ValueError(
            f'artifact was built for Spark {artifact.spark_version}, but EMR release {release.release_label!r} '
            f'ships Spark {release.spark_version}'
        )
    if major_minor(artifact.scala_version) != major_minor(release.scala_version):
        raise ValueError(
            f'artifact was built for Scala {artifact.scala_version}, but EMR release {release.release_label!r} '
            f'ships Scala {release.scala_version}'
        )
    if major_minor(artifact.python_version) != major_minor(EMR_PYSPARK_PYTHON.rsplit('python', 1)[1]):
        raise ValueError(
            f'artifact requires Python {artifact.python_version}, but EMR bootstrap is configured for '
            f'{EMR_PYSPARK_PYTHON}'
        )


def hail_configurations(
    off_heap_memory_per_core_mb: Optional[int], vep_profile: Optional[VEPProfileManifest] = None
) -> list[dict]:
    spark_defaults = {
        'spark.jars': f'local://{HAIL_JAR_PATH}',
        'spark.driver.extraClassPath': HAIL_JAR_PATH,
        'spark.executor.extraClassPath': HAIL_JAR_PATH,
        'spark.serializer': 'org.apache.spark.serializer.KryoSerializer',
        'spark.kryo.registrator': 'is.hail.kryo.HailKryoRegistrator',
        'spark.task.maxFailures': '20',
        'spark.driver.extraJavaOptions': '-Xss4M',
        'spark.executor.extraJavaOptions': '-Xss4M',
        'spark.executorEnv.PYTHONHASHSEED': '0',
        'spark.yarn.appMasterEnv.PYTHONHASHSEED': '0',
        'spark.executorEnv.HAIL_CLOUD': 'aws',
        'spark.yarn.appMasterEnv.HAIL_CLOUD': 'aws',
        'spark.pyspark.python': EMR_PYSPARK_PYTHON,
    }
    spark_env_export = {
        'HAIL_CLOUD': 'aws',
        'PYSPARK_PYTHON': EMR_PYSPARK_PYTHON,
        'SPARK_DIST_CLASSPATH': '$(hadoop classpath)',
    }
    if off_heap_memory_per_core_mb is not None:
        spark_defaults['spark.executorEnv.HAIL_WORKER_OFF_HEAP_MEMORY_PER_CORE_MB'] = str(off_heap_memory_per_core_mb)
    if vep_profile is not None:
        spark_defaults['spark.executorEnv.VEP_CONFIG_URI'] = VEP_CONFIG_URI
        spark_defaults['spark.yarn.appMasterEnv.VEP_CONFIG_URI'] = VEP_CONFIG_URI
        spark_defaults['spark.executor.cores'] = str(vep_profile.executor_cores)
        spark_env_export['VEP_CONFIG_URI'] = VEP_CONFIG_URI
    return [
        {'Classification': 'core-site', 'Properties': copy.deepcopy(S3A_CORE_SITE_PROPERTIES)},
        {'Classification': 'spark-defaults', 'Properties': spark_defaults},
        {'Classification': 'spark', 'Properties': {'maximizeResourceAllocation': 'true'}},
        {
            'Classification': 'spark-env',
            'Configurations': [{'Classification': 'export', 'Properties': spark_env_export}],
            'Properties': {},
        },
    ]


def validate_s3a_configurations(configurations: object) -> None:
    if not isinstance(configurations, list):
        raise ValueError('The final RunJobFlow Configurations value must be a list.')
    core_site_properties: dict = {}
    for configuration in configurations:
        if isinstance(configuration, dict) and configuration.get('Classification') == 'core-site':
            properties = configuration.get('Properties')
            if isinstance(properties, dict):
                core_site_properties.update(properties)
    for name, expected in S3A_CORE_SITE_PROPERTIES.items():
        actual = core_site_properties.get(name)
        if actual != expected:
            raise ValueError(f'The final core-site configuration must set {name}={expected}.')


def deep_merge(base: dict, overlay: dict) -> dict:
    result = copy.deepcopy(base)
    for key, value in overlay.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def validate_vep_configurations(configurations: object, profile: VEPProfileManifest) -> None:
    if not isinstance(configurations, list):
        raise ValueError('The final RunJobFlow Configurations value must be a list.')
    spark_defaults: dict = {}
    spark_env_export: dict = {}
    for configuration in configurations:
        if not isinstance(configuration, dict):
            continue
        if configuration.get('Classification') == 'spark-defaults':
            properties = configuration.get('Properties')
            if isinstance(properties, dict):
                spark_defaults.update(properties)
        if configuration.get('Classification') == 'spark-env':
            nested = configuration.get('Configurations')
            if isinstance(nested, list):
                for child in nested:
                    if isinstance(child, dict) and child.get('Classification') == 'export':
                        properties = child.get('Properties')
                        if isinstance(properties, dict):
                            spark_env_export.update(properties)
    required = {
        'spark.executorEnv.VEP_CONFIG_URI': VEP_CONFIG_URI,
        'spark.yarn.appMasterEnv.VEP_CONFIG_URI': VEP_CONFIG_URI,
        'spark.executor.cores': str(profile.executor_cores),
    }
    for name, expected in required.items():
        if spark_defaults.get(name) != expected:
            raise ValueError(f'The final spark-defaults configuration must set {name}={expected}.')
    if spark_env_export.get('VEP_CONFIG_URI') != VEP_CONFIG_URI:
        raise ValueError(f'The final spark-env export must set VEP_CONFIG_URI={VEP_CONFIG_URI}.')


def validate_vep_instances(instances: object, profile: VEPProfileManifest) -> None:
    if not isinstance(instances, dict):
        raise ValueError('The final RunJobFlow Instances value must be a JSON object.')
    if 'InstanceFleets' in instances:
        raise ValueError('VEP initially supports InstanceGroups only, not InstanceFleets.')
    groups = instances.get('InstanceGroups')
    if not isinstance(groups, list):
        raise ValueError('The final VEP request must contain InstanceGroups.')
    roles = set()
    for group in groups:
        if not isinstance(group, dict):
            raise ValueError('Every VEP instance group must be a JSON object.')
        role = group.get('InstanceRole')
        if role not in {'MASTER', 'CORE', 'TASK'}:
            raise ValueError(f'Unsupported VEP instance group role: {role!r}.')
        roles.add(role)
        ebs = group.get('EbsConfiguration')
        configs = ebs.get('EbsBlockDeviceConfigs') if isinstance(ebs, dict) else None
        if not isinstance(configs, list):
            raise ValueError(f'VEP instance group {role} must configure EBS storage.')
        total_size_gb = 0
        for config in configs:
            if not isinstance(config, dict):
                continue
            volume = config.get('VolumeSpecification')
            count = config.get('VolumesPerInstance', 1)
            if isinstance(volume, dict) and isinstance(volume.get('SizeInGB'), int) and isinstance(count, int):
                total_size_gb += volume['SizeInGB'] * count
        if total_size_gb < profile.storage_size_gb:
            raise ValueError(
                f'VEP instance group {role} must have at least {profile.storage_size_gb} GiB of EBS storage.'
            )
    if not {'MASTER', 'CORE'}.issubset(roles):
        raise ValueError('The final VEP request must contain primary and core instance groups.')


def merge_run_job_flow_overlay(base: dict, overlay: dict) -> dict:
    result = deep_merge(base, overlay)
    instances_overlay = overlay.get('Instances')
    if (
        isinstance(instances_overlay, dict)
        and 'InstanceFleets' in instances_overlay
        and 'InstanceGroups' not in instances_overlay
    ):
        instances = result.get('Instances')
        if isinstance(instances, dict):
            instances.pop('InstanceGroups', None)
    return result


def build_run_job_flow_kwargs(
    *,
    cluster_name: str,
    release_label: str,
    master_instance_type: str,
    core_instance_type: str,
    core_instance_count: int,
    ec2_key_name: Optional[str],
    subnet_id: Optional[str],
    service_access_security_group: Optional[str],
    primary_security_group: Optional[str],
    core_security_group: Optional[str],
    log_uri: Optional[str],
    bootstrap_s3_uri: str,
    artifact: HailArtifactManifest,
    off_heap_memory_per_core_mb: Optional[int],
    use_default_roles: bool,
    service_role: Optional[str],
    instance_profile: Optional[str],
    idle_timeout: int,
    vep_profile: Optional[VEPProfileManifest] = None,
    vep_bootstrap_actions: Optional[list[dict]] = None,
) -> dict:
    kwargs: dict = {
        'Name': cluster_name,
        'ReleaseLabel': release_label,
        'Applications': [{'Name': 'Spark'}],
        'Configurations': hail_configurations(off_heap_memory_per_core_mb, vep_profile),
        'BootstrapActions': [
            {
                'Name': 'install-hail',
                'ScriptBootstrapAction': {'Path': bootstrap_s3_uri, 'Args': artifact.bootstrap_args()},
            }
        ],
        'Instances': {
            'InstanceGroups': [
                {
                    'Name': 'Primary',
                    'InstanceRole': 'MASTER',
                    'InstanceType': master_instance_type,
                    'InstanceCount': 1,
                },
                {
                    'Name': 'Core',
                    'InstanceRole': 'CORE',
                    'InstanceType': core_instance_type,
                    'InstanceCount': core_instance_count,
                },
            ],
            'KeepJobFlowAliveWhenNoSteps': True,
            'TerminationProtected': False,
        },
        'AutoTerminationPolicy': {'IdleTimeout': idle_timeout},
        'VisibleToAllUsers': True,
        'Tags': [
            {'Key': 'for-use-with-amazon-emr-managed-policies', 'Value': 'true'},
            {'Key': 'hailctl', 'Value': 'emr'},
            {'Key': 'hail-version', 'Value': artifact.hail_pip_version},
            {'Key': 'hail-revision', 'Value': artifact.hail_git_revision},
            {'Key': 'hail-spark-version', 'Value': artifact.spark_version},
        ],
    }
    if vep_profile is not None:
        if not isinstance(vep_bootstrap_actions, list) or len(vep_bootstrap_actions) != 2:
            raise ValueError('VEP requires exactly two checksum-verified bootstrap actions.')
        kwargs['BootstrapActions'].extend(copy.deepcopy(vep_bootstrap_actions))
        ebs_configuration = {
            'EbsBlockDeviceConfigs': [
                {
                    'VolumeSpecification': {
                        'VolumeType': 'gp3',
                        'SizeInGB': vep_profile.storage_size_gb,
                    },
                    'VolumesPerInstance': 1,
                }
            ],
            'EbsOptimized': True,
        }
        for instance_group in kwargs['Instances']['InstanceGroups']:
            instance_group['EbsConfiguration'] = copy.deepcopy(ebs_configuration)
        kwargs['Tags'].extend([
            {'Key': 'hail-vep-reference-genome', 'Value': vep_profile.reference_genome},
            {'Key': 'hail-vep-version', 'Value': vep_profile.vep_version},
            {'Key': 'hail-loftee-version', 'Value': vep_profile.loftee_version},
        ])
    elif vep_bootstrap_actions is not None:
        raise ValueError('VEP bootstrap actions require a VEP profile.')

    if log_uri is not None:
        kwargs['LogUri'] = log_uri
    if ec2_key_name is not None:
        kwargs['Instances']['Ec2KeyName'] = ec2_key_name
    if subnet_id is not None:
        kwargs['Instances']['Ec2SubnetId'] = subnet_id
    if service_access_security_group is not None:
        kwargs['Instances']['ServiceAccessSecurityGroup'] = service_access_security_group
    if primary_security_group is not None:
        kwargs['Instances']['EmrManagedMasterSecurityGroup'] = primary_security_group
    if core_security_group is not None:
        kwargs['Instances']['EmrManagedSlaveSecurityGroup'] = core_security_group

    if use_default_roles:
        kwargs['ServiceRole'] = 'EMR_DefaultRole'
        kwargs['JobFlowRole'] = 'EMR_EC2_DefaultRole'
    else:
        if service_role is None or instance_profile is None:
            raise ValueError('Either pass --use-default-roles, or provide both --service-role and --instance-profile.')
        kwargs['ServiceRole'] = service_role
        kwargs['JobFlowRole'] = instance_profile

    return kwargs
