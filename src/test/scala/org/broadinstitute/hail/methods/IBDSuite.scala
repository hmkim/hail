package org.broadinstitute.hail.methods

import org.apache.spark.sql.Row
import org.broadinstitute.hail.SparkSuite
import org.broadinstitute.hail.check.Gen
import org.broadinstitute.hail.check.Prop._
import org.broadinstitute.hail.check.Properties
import org.broadinstitute.hail.driver._
import org.broadinstitute.hail.expr.{TDouble, TInt, TString}
import org.broadinstitute.hail.io.vcf.LoadVCF
import org.broadinstitute.hail.utils.AbsoluteFuzzyComparable._
import org.broadinstitute.hail.utils.{AbsoluteFuzzyComparable, TextTableConfiguration, TextTableReader}
import org.broadinstitute.hail.variant._
import org.broadinstitute.hail.utils._
import org.testng.annotations.Test

import scala.language._
import scala.sys.process._

class IBDSuite extends SparkSuite {

  def toI(a: Any): Int =
    a.asInstanceOf[Int]

  def toD(a: Any): Double =
    a.asInstanceOf[Double]

  def toS(a: Any): String =
    a.asInstanceOf[String]

  implicit object ibdAbsoluteFuzzyComparable extends AbsoluteFuzzyComparable[IBDInfo] {
    def absoluteEq(tolerance: Double, x: IBDInfo, y: IBDInfo) = {
      def feq(x: Double, y: Double) = AbsoluteFuzzyComparable.absoluteEq(tolerance, x, y)
      def NaNorFeq(x: Double, y: Double) =
        x.isNaN && y.isNaN || feq(x, y)

      NaNorFeq(x.Z0, y.Z0) && NaNorFeq(x.Z1, y.Z1) && NaNorFeq(x.Z2, y.Z2) && NaNorFeq(x.PI_HAT, y.PI_HAT)
    }
  }

  implicit object eibdAbsoluteFuzzyComparable extends AbsoluteFuzzyComparable[ExtendedIBDInfo] {
    def absoluteEq(tolerance: Double, x: ExtendedIBDInfo, y: ExtendedIBDInfo) = {
      def feq(x: Double, y: Double) = AbsoluteFuzzyComparable.absoluteEq(tolerance, x, y)
      AbsoluteFuzzyComparable.absoluteEq(tolerance, x.ibd, y.ibd) &&
        feq(x.ibs0, y.ibs0) && feq(x.ibs1, y.ibs1) && feq(x.ibs2, y.ibs2)
    }
  }

  private def runPlinkIBD(vds: VariantDataset,
    min: Option[Double] = None,
    max: Option[Double] = None): Map[(String, String), ExtendedIBDInfo] = {

    val tmpdir = tmpDir.createTempFile(prefix = "plinkIBD")
    val vcfOutputFile = tmpdir + ".vcf"

    var s = State(sc, sqlContext).copy(vds = vds)
    s = ExportVCF.run(s, Array("-o", vcfOutputFile))

    val thresholdString = min.map(x => s"--min $x").getOrElse("") + " " +
      max.map(x => s"--max $x").getOrElse("")

    s"plink --double-id --allow-extra-chr --vcf ${ uriPath(vcfOutputFile) } --genome full --out ${ uriPath(tmpdir) } " + thresholdString !

    val genomeFormat = TextTableConfiguration(
      types = Map(("IID1", TString), ("IID2", TString), ("Z0", TDouble), ("Z1", TDouble), ("Z2", TDouble),
        ("PI_HAT", TDouble), ("IBS0", TInt), ("IBS1", TInt), ("IBS2", TInt)),
      separator = " +")
    val (_, rdd) = TextTableReader.read(sc)(Array(tmpdir + ".genome"), genomeFormat)

    rdd.collect()
      .map(_.value)
      .map { ann =>
        // _, fid1, iid1, fid2, iid2, rt, ez, z0, z1, z2, pihat, phe, dst, ppc, ratio, ibs0, ibs1, ibs2, homhom, hethet
        val row = ann.asInstanceOf[Row]
        val iid1 = toS(row(2))
        val iid2 = toS(row(4))
        val z0 = toD(row(7))
        val z1 = toD(row(8))
        val z2 = toD(row(9))
        val pihat = toD(row(10))
        val ibs0 = toI(row(15))
        val ibs1 = toI(row(16))
        val ibs2 = toI(row(17))
        ((iid1, iid2), ExtendedIBDInfo(IBDInfo(z0, z1, z2, pihat), ibs0, ibs1, ibs2))
      }
      // if min or max is enabled, we remove NaNs, plink does not
      .filter { case (_, eibd) => (min.isEmpty && max.isEmpty) || !eibd.hasNaNs }
      .toMap
  }

  object Spec extends Properties("IBD") {
    val plinkSafeBiallelicVDS = VariantSampleMatrix.gen(sc, VSMSubgen.plinkSafeBiallelic)
      .resize(1000)
      .map(vds => vds.filterVariants { case (v, va, gs) => v.isAutosomalOrPseudoAutosomal })
      .filter(vds => vds.nVariants > 2 && vds.nSamples >= 2)

    property("hail generates same result as plink 1.9") =
      forAll(plinkSafeBiallelicVDS) { vds =>
        val us = IBD(vds).collect().toMap

        val plink = runPlinkIBD(vds)

        mapSameElements(us, plink,
          (x: ExtendedIBDInfo, y: ExtendedIBDInfo) => AbsoluteFuzzyComparable.absoluteEq(tolerance, x, y))
      }

    property("hail generates same result as plink 1.9 with min and/or max") =
      forAll(plinkSafeBiallelicVDS,
        Gen.option(Gen.choose(0.0, 1.0), 0.8),
        Gen.option(Gen.choose(0.0, 1.0), 0.8)) { (vds, maybeMin, maybeMax) =>
        // ensure min <= max
        val validMax = maybeMax.map(max => maybeMin match {
          case None => max
          case Some(min) if max < min => (max / min) * (1.0 - min) + min
          case Some(min) => max
        })

        val us = IBD(vds, min = maybeMin, max = validMax).collect().toMap

        val plink = runPlinkIBD(vds, maybeMin, validMax)

        mapSameElements(us, plink,
          (x: ExtendedIBDInfo, y: ExtendedIBDInfo) => AbsoluteFuzzyComparable.absoluteEq(tolerance, x, y))
      }
  }

  // plink rounds to the nearest ten-thousandth
  val tolerance = 5e-5

  @Test def testIBDPlink() {
    Spec.check()
  }

  @Test def ibdPlinkSameOnRealVCF() {
    val vds = LoadVCF(sc, "src/test/resources/sample.vcf")

    val us = IBD(vds).collect().toMap

    val plink = runPlinkIBD(vds)

    assert(mapSameElements(us, plink,
      (x: ExtendedIBDInfo, y: ExtendedIBDInfo) => AbsoluteFuzzyComparable.absoluteEq(tolerance, x, y)))
  }

  @Test def ibdLookupTable() {
    def referenceImplementation(i: Int, j: Int): Int = {
      if (i == 3 || j == 3)
        0
      else
        2 - Math.abs(i - j)
    }

    for (i <- 0 to 3; j <- 0 to 3) {
      val index = (i << 2) | j

      assert((IBD.ibsLookupTable(index) & 3.toByte) == referenceImplementation(i, j))
    }
  }

}
