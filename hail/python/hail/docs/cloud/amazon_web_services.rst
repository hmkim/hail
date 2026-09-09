===================
Amazon Web Services
===================

``hailctl emr`` (experimental)
------------------------------

``hailctl emr`` provisions Amazon EMR on EC2 clusters configured for Hail. AWS support is
experimental and community-supported; it is not part of Hail's required continuous-integration
matrix.

The supported release is ``emr-spark-8.1.0`` (Spark 4.1.x and Scala 2.13). Hail and the bundled JAR
must be built from the same source revision for Spark 4.1. The command validates this information
from an artifact manifest before creating a cluster.

Artifact manifest
~~~~~~~~~~~~~~~~~

Build and upload an exact Hail wheel and an offline dependency wheelhouse. The local manifest passed
to ``hailctl`` records their S3 locations and SHA256 digests:

.. code-block:: json

    {
      "schema_version": 1,
      "hail_git_revision": "0123456789abcdef0123456789abcdef01234567",
      "hail_pip_version": "0.2.140",
      "spark_version": "4.1.2",
      "scala_version": "2.13.18",
      "python_version": "3.12",
      "wheel_uri": "s3://my-artifacts/hail.whl",
      "wheel_sha256": "<64 lowercase hex characters>",
      "wheelhouse_uri": "s3://my-artifacts/wheelhouse.tar.gz",
      "wheelhouse_sha256": "<64 lowercase hex characters>"
    }

The wheelhouse archive must contain ``requirements.txt`` and every dependency wheel needed by Hail,
excluding PySpark. EMR supplies PySpark. Bootstrap downloads both artifacts through the instance
profile, verifies their checksums, and installs without contacting PyPI.

Starting and using a cluster
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: text

    hailctl emr start CLUSTER_NAME \
        --artifact-manifest ./hail-emr-artifact.json \
        --s3-scratch s3://my-bucket/hail-tmp/ \
        --subnet-id subnet-PRIVATE \
        --service-access-security-group sg-EMR_SERVICE_ENDPOINT \
        --primary-security-group sg-EMR_PRIMARY \
        --core-security-group sg-EMR_CORE

Clusters receive an idle auto-termination policy. The default is one hour and can be changed with
``--idle-timeout``.

Submit a Python job and wait for it to finish:

.. code-block:: text

    hailctl emr submit CLUSTER_ID SCRIPT.py \
        --s3-scratch s3://my-bucket/hail-tmp/ [-- args to your script...]

List or terminate clusters:

.. code-block:: text

    hailctl emr list
    hailctl emr stop CLUSTER_ID

Runtime environment
~~~~~~~~~~~~~~~~~~~

Starting with EMR 7.10, S3A is the default connector for ``s3://``, ``s3n://``, and
``s3a://`` paths. The supported EMR Spark 8.1 release therefore does not use EMRFS. Hail routes
these paths through Spark's Hadoop configuration and sets ``HAIL_CLOUD=aws`` to select its
HadoopFS-only route. Do not carry EMRFS consistent-view or multipart-cleanup properties into this
configuration; many have no S3A equivalent. Bucket-specific EMRFS properties are mapped by EMR only
when the corresponding S3A property is undefined.

``hailctl emr`` explicitly pins all three schemes to ``S3AFileSystem`` and enables the EMR MagicV2
committer with the S3A committer factory, in-memory commit tracking, and overwrite-and-commit
behavior. MagicV2 avoids traditional list-and-rename commit operations and writes files to their
final output location during task commit.

MagicV2 has operational implications. Successful task outputs can remain visible after a failed job,
so clean the destination before retrying the same output path. A killed JVM can leave incomplete
multipart uploads; scratch and output buckets should have an S3 lifecycle rule that aborts incomplete
uploads. MagicV2 also retains a small amount of memory per file until task commit, so workloads that
write unusually many files per executor may require more container memory or fewer concurrent tasks.
S3A directory markers end in ``/`` rather than the legacy EMRFS ``_$folder$`` form.

Hail requires Python 3.12 or later. EMR on EC2 does not preinstall Python 3.12, so the bootstrap uses
Amazon Linux 2023 ``dnf`` packages and points ``spark.pyspark.python`` and ``PYSPARK_PYTHON`` at
``/usr/bin/python3.12``.

Private subnet prerequisites
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Private clusters require network and IAM resources prepared outside ``hailctl``:

* an EMR service VPC endpoint or service-role permission to create and modify it;
* a service access security group with the EMR Spark 8 HTTPS rules;
* an S3 gateway endpoint or another controlled S3 route;
* endpoint policies that permit EMR instance-data buckets and the Hail artifact, log, scratch, and
  dataset buckets;
* a service role and EC2 instance profile with least-privilege S3, EC2, EMR, and optional KMS
  permissions.

This initial release requires a private subnet with controlled NAT egress because the bootstrap installs
Python 3.12 and native system packages from Amazon Linux repositories. Hail Python dependencies are
installed from the checksum-verified S3 wheelhouse rather than PyPI. A no-NAT deployment requires a
custom image or private package-repository design and is not currently supported.

Advanced cluster options
~~~~~~~~~~~~~~~~~~~~~~~~

``--run-job-flow-json`` deep-merges a JSON object into the final ``RunJobFlow`` request. Nested
objects are merged and lists are replaced. ``InstanceFleets`` replaces default ``InstanceGroups``.
The final request must retain Spark, the content-addressed Hail bootstrap, the required S3A and
MagicV2 ``core-site`` properties, an S3 log URI, a supported release, and a valid auto-termination
policy.

``--off-heap-memory-per-core-mb`` caps Hail's native off-heap allocation per task core. It does not
reserve YARN container memory or automatically change ``spark.executor.memoryOverhead``.

Variant Effect Predictor (VEP)
------------------------------

VEP and LoFTEE can be installed when creating a GRCh38 cluster:

.. code-block:: text

    hailctl emr start NAME ... \
        --vep GRCh38 \
        --vep-manifest ./vep-grch38-profile.json

The profile can instead be configured once:

.. code-block:: text

    hailctl config set emr/vep_grch38_manifest ./vep-grch38-profile.json
    hailctl emr start NAME ... --vep GRCh38

After the cluster is ready, Hail discovers the node-local configuration automatically, matching the
Dataproc experience:

.. code-block:: python

    annotated = hl.vep(dataset)

The initial EMR implementation supports only GRCh38 and requires an immutable profile supplied by the
operator. The profile pins the VEP and LoFTEE versions, supported AWS regions, container image digest,
S3 cache/config/reference locations and SHA256 checksums, storage size, instance recommendation,
executor concurrency, process timeout, and stderr limit. Supported regions are validated against
botocore's endpoint metadata for the EMR service. Before a real launch, hailctl compares the private
ECR registry and S3 bucket locations with the cluster region and warns when cross-region transfer
charges may apply; this warning does not reject an intentionally cross-region profile. Hail does not
yet publish a default AWS profile, so ``--vep-manifest`` or ``emr/vep_grch38_manifest`` is required.

VEP clusters default to the profile's memory-optimized core instance type, one VEP process per
executor, and 200 GiB gp3 storage on primary and core nodes. Explicit ``--core-instance-type`` values
are retained. The base bootstrap establishes ``/vep_data`` as a link to ``/mnt/vep_data`` before
installing any resources, so the cache and LoFTEE data use the profile-sized volume from the start
and no later copy/delete migration is required. The VEP cache archive is staged temporarily on the
profile-sized volume, checked against both its expected byte count and SHA256 digest, and validated for
safe member paths and types before extraction. Large non-archive LoFTEE references such as GERP, the
ancestor FASTA, and the compressed SQL database stream from S3 through a SHA256-verifying pass-through
process to their final paths. Their verified compressed byte counts, together with the staged plugin,
configuration, and shim sizes, must equal the profile's expected LoFTEE resource size. A mismatch fails
the bootstrap before the cluster becomes ready or VEP is invoked. The LoFTEE plugin archive, VEP
configuration, and executable shim are staged and verified before extraction or installation.

The Hail wheel, bundled JAR, bootstrap scripts, VEP configuration, cache archive, LoFTEE plugin, and
container image are governed by the exact Hail artifact and profile contracts. Cache and LoFTEE data
are installed on every node at cluster creation; an existing cluster cannot be retrofitted and must be
recreated with ``--vep``.

The bootstrap runs a container pinned by digest behind a root-owned wrapper with fixed read-only
mounts. The EMR driver user (``hadoop``) and YARN executor user (``yarn``) may invoke only that wrapper;
they are not added to the Docker group. Normal Hail execution obtains the wrapper argument vector from
the checksum-verified, root-installed VEP configuration and streams variant records over standard
input; variant data is not evaluated as shell or Docker arguments. The wrapper fixes the image,
executable, and host paths and validates the complete VEP argument vector against the installed GRCh38
profile. Only the JSON-versus-CSQ output flag may vary. Arguments that do not exactly match the base or
LoFTEE profile contract are rejected before Docker starts.

This strict contract intentionally rejects custom VEP commands on a cluster created with ``--vep``.
Operators who need a different command or plugin set must publish and review a separate profile rather
than reuse the privileged wrapper. Registry credentials are removed after image installation. Advanced
request overlays must retain both checksum-verified VEP bootstraps and the automatic
``VEP_CONFIG_URI`` settings.
