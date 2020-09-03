package is.hail.sparkextras

import is.hail.annotations._
import is.hail.rvd.{PartitionBoundOrdering, RVD, RVDContext, RVDPartitioner, RVDType}
import is.hail.utils._
import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

object OrderedDependency {
  def generate[T](oldPartitioner: RVDPartitioner, newIntervals: IndexedSeq[Interval], rdd: RDD[T]): OrderedDependency[T] = {
    new OrderedDependency(
      newIntervals.map(oldPartitioner.queryInterval).toArray,
      rdd)
  }
}

class OrderedDependency[T](
  depArray: Array[Range],
  rdd: RDD[T]
) extends NarrowDependency[T](rdd) {

  override def getParents(partitionId: Int): Seq[Int] = depArray(partitionId)
}

object RepartitionedOrderedRDD2 {
  def apply(prev: RVD, newRangeBounds: IndexedSeq[Interval]): ContextRDD[Long] =
    ContextRDD(new RepartitionedOrderedRDD2(prev, newRangeBounds))
}

/**
  * Repartition 'prev' to comply with 'newRangeBounds', using narrow dependencies.
  * Assumes new key type is a prefix of old key type, so no reordering is
  * needed.
  */
class RepartitionedOrderedRDD2 private (@transient val prev: RVD, @transient val newRangeBounds: IndexedSeq[Interval])
  extends RDD[ContextRDD.ElementType[Long]](prev.crdd.sparkContext, Nil) { // Nil since we implement getDependencies

  val prevCRDD: ContextRDD[Long] = prev.boundary.crdd
  val typ: RVDType = prev.typ
  val kOrd: ExtendedOrdering = PartitionBoundOrdering(typ.kType.virtualType)


  def getPartitions: Array[Partition] = {
    require(newRangeBounds.forall{i => typ.kType.virtualType.relaxedTypeCheck(i.start) && typ.kType.virtualType.relaxedTypeCheck(i.end)})
    Array.tabulate[Partition](newRangeBounds.length) { i =>
      RepartitionedOrderedRDD2Partition(
        i,
        dependency.getParents(i).toArray.map(prevCRDD.partitions),
        newRangeBounds(i))
    }
  }

  override def compute(partition: Partition, context: TaskContext): Iterator[RVDContext => Iterator[Long]] = {
    val ordPartition = partition.asInstanceOf[RepartitionedOrderedRDD2Partition]
    val pord = kOrd.intervalEndpointOrdering
    val range = ordPartition.range
    val ur = new UnsafeRow(typ.rowType)
    val key = new SelectFieldsRow(ur, typ.kFieldIdx)

    Iterator.single { (ctx: RVDContext) =>
      ordPartition.parents.iterator
        .flatMap { parentPartition =>
          prevCRDD.iterator(parentPartition, context).flatMap(_(ctx))
        }.dropWhile { ptr =>
          ur.set(ctx.r, ptr)
          pord.lt(key, range.left)
        }.takeWhile { ptr =>
          ur.set(ctx.r, ptr)
          pord.lteq(key, range.right)
        }
    }
  }

  val dependency: OrderedDependency[_] = OrderedDependency.generate(
    prev.partitioner,
    newRangeBounds,
    prevCRDD.rdd)

  override def getDependencies: Seq[Dependency[_]] = FastSeq(dependency)
}

case class RepartitionedOrderedRDD2Partition(
    index: Int,
    parents: Array[Partition],
    range: Interval
) extends Partition
