package is.hail.expr.ir

import is.hail.types.tcoerce
import is.hail.types.virtual._
import is.hail.types.virtual.TIterable.elementType
import is.hail.utils.FastSeq

import scala.collection.mutable

object SegregatedBindingEnv {
  def apply[A, B](env: BindingEnv[A]): SegregatedBindingEnv[A, B] =
    SegregatedBindingEnv(env, env.dropBindings)
}

case class SegregatedBindingEnv[A, B](
  childEnvWithoutBindings: BindingEnv[A],
  newBindings: BindingEnv[B],
) extends GenericBindingEnv[SegregatedBindingEnv[A, B], B] {
  def newBlock(
    eval: Seq[(String, B)] = Seq.empty,
    agg: AggEnv[B] = AggEnv.NoOp,
    scan: AggEnv[B] = AggEnv.NoOp,
    relational: Seq[(String, B)] = Seq.empty,
    dropEval: Boolean = false,
  ): SegregatedBindingEnv[A, B] =
    SegregatedBindingEnv(
      childEnvWithoutBindings.newBlock(agg = agg.empty, scan = scan.empty, dropEval = dropEval),
      newBindings.newBlock(eval, agg, scan, relational, dropEval),
    )

  def unified(implicit ev: BindingEnv[B] =:= BindingEnv[A]): BindingEnv[A] =
    childEnvWithoutBindings.merge(newBindings)

  def mapNewBindings[C](f: (String, B) => C): SegregatedBindingEnv[A, C] = SegregatedBindingEnv(
    childEnvWithoutBindings,
    newBindings.mapValuesWithKey(f),
  )

  override def promoteAgg: SegregatedBindingEnv[A, B] = SegregatedBindingEnv(
    childEnvWithoutBindings.promoteAgg,
    newBindings.promoteAgg,
  )

  override def promoteScan: SegregatedBindingEnv[A, B] = SegregatedBindingEnv(
    childEnvWithoutBindings.promoteScan,
    newBindings.promoteScan,
  )

  override def bindEval(bindings: (String, B)*): SegregatedBindingEnv[A, B] =
    copy(newBindings = newBindings.bindEval(bindings: _*))

  override def noEval: SegregatedBindingEnv[A, B] = SegregatedBindingEnv(
    childEnvWithoutBindings.copy(eval = Env.empty),
    newBindings.copy(eval = Env.empty),
  )

  override def bindAgg(bindings: (String, B)*): SegregatedBindingEnv[A, B] =
    copy(newBindings = newBindings.bindAgg(bindings: _*))

  override def bindScan(bindings: (String, B)*): SegregatedBindingEnv[A, B] =
    copy(newBindings = newBindings.bindScan(bindings: _*))

  override def createAgg: SegregatedBindingEnv[A, B] = SegregatedBindingEnv(
    childEnvWithoutBindings.createAgg,
    newBindings.createAgg,
  )

  override def createScan: SegregatedBindingEnv[A, B] = SegregatedBindingEnv(
    childEnvWithoutBindings.createScan,
    newBindings.createScan,
  )

  override def noAgg: SegregatedBindingEnv[A, B] = SegregatedBindingEnv(
    childEnvWithoutBindings.noAgg,
    newBindings.noAgg,
  )

  override def noScan: SegregatedBindingEnv[A, B] = SegregatedBindingEnv(
    childEnvWithoutBindings.noScan,
    newBindings.noScan,
  )

  override def onlyRelational(keepAggCapabilities: Boolean = false): SegregatedBindingEnv[A, B] =
    SegregatedBindingEnv(
      childEnvWithoutBindings.onlyRelational(keepAggCapabilities),
      newBindings.onlyRelational(keepAggCapabilities),
    )

  override def bindRelational(bindings: (String, B)*): SegregatedBindingEnv[A, B] =
    copy(newBindings = newBindings.bindRelational(bindings: _*))
}

object Binds {
  def apply(x: IR, v: String, i: Int): Boolean =
    Bindings.get(x, i, BindingEnv.empty[Type].createAgg.createScan).eval.contains(v)
}

final case class Bindings(
  eval: IndexedSeq[(String, Type)] = FastSeq.empty,
  agg: AggEnv[Type] = AggEnv.NoOp,
  scan: AggEnv[Type] = AggEnv.NoOp,
  relational: IndexedSeq[(String, Type)] = FastSeq.empty,
  dropEval: Boolean = false,
)

object Bindings {
  val empty: Bindings = Bindings(FastSeq.empty, AggEnv.NoOp, AggEnv.NoOp, FastSeq.empty, false)

  // Create a `Bindings` which cannot see anything bound in the enclosing context.
  private def inFreshScope(
    eval: IndexedSeq[(String, Type)] = FastSeq.empty,
    agg: Option[IndexedSeq[(String, Type)]] = None,
    scan: Option[IndexedSeq[(String, Type)]] = None,
    relational: IndexedSeq[(String, Type)] = FastSeq.empty,
  ): Bindings = Bindings(
    eval,
    agg.map(AggEnv.Create(_)).getOrElse(AggEnv.Drop),
    scan.map(AggEnv.Create(_)).getOrElse(AggEnv.Drop),
    relational,
    dropEval = true,
  )

  /** Returns the environment of the `i`th child of `ir` given the environment of the parent node
    * `ir`.
    */
  def get[E <: GenericBindingEnv[E, Type]](ir: BaseIR, i: Int, baseEnv: E): E = {
    val res = ir match {
      case ir: MatrixIR => childEnvMatrix(ir, i, baseEnv)
      case ir: TableIR => childEnvTable(ir, i, baseEnv)
      case ir: BlockMatrixIR => childEnvBlockMatrix(ir, i, baseEnv)
      case ir: IR => childEnvValue(ir, i, baseEnv)
    }
    val newRes = get2(ir, i, baseEnv)
    assert(res == newRes, s"\nnew = $newRes\n old = $res\n node = ${ir.getClass}")
    res
  }

  private def get2[E <: GenericBindingEnv[E, Type]](ir: BaseIR, i: Int, baseEnv: E): E = {
    val bindings = ir match {
      case ir: MatrixIR => childEnvMatrix2(ir, i)
      case ir: TableIR => childEnvTable2(ir, i)
      case ir: BlockMatrixIR => childEnvBlockMatrix2(ir, i)
      case ir: IR => childEnvValue2(ir, i)
    }
    baseEnv.extend(bindings)
  }

  /** Like [[Bindings.get]], but keeps separate any new bindings introduced by `ir`. Always
    * satisfies the identity
    * {{{
    * Bindings.segregated(ir, i, baseEnv).unified == Bindings(ir, i, baseEnv)
    * }}}
    */
  def segregated[A](ir: BaseIR, i: Int, baseEnv: BindingEnv[A]): SegregatedBindingEnv[A, Type] =
    get(ir, i, SegregatedBindingEnv(baseEnv))

  private def childEnvMatrix2(ir: MatrixIR, i: Int): Bindings = {
    ir match {
      case MatrixMapRows(child, _) if i == 1 =>
        Bindings.inFreshScope(
          eval = child.typ.rowBindings :+ "n_cols" -> TInt32,
          agg = Some(child.typ.entryBindings),
          scan = Some(child.typ.rowBindings),
        )
      case MatrixFilterRows(child, _) if i == 1 =>
        Bindings.inFreshScope(child.typ.rowBindings)
      case MatrixMapCols(child, _, _) if i == 1 =>
        Bindings.inFreshScope(
          eval = child.typ.colBindings :+ "n_rows" -> TInt64,
          agg = Some(child.typ.entryBindings),
          scan = Some(child.typ.colBindings),
        )
      case MatrixFilterCols(child, _) if i == 1 =>
        Bindings.inFreshScope(child.typ.colBindings)
      case MatrixMapEntries(child, _) if i == 1 =>
        Bindings.inFreshScope(child.typ.entryBindings)
      case MatrixFilterEntries(child, _) if i == 1 =>
        Bindings.inFreshScope(child.typ.entryBindings)
      case MatrixMapGlobals(child, _) if i == 1 =>
        Bindings.inFreshScope(child.typ.globalBindings)
      case MatrixAggregateColsByKey(child, _, _) =>
        if (i == 1) {
          Bindings.inFreshScope(
            eval = child.typ.rowBindings,
            agg = Some(child.typ.entryBindings),
          )
        } else if (i == 2) {
          Bindings.inFreshScope(
            eval = child.typ.globalBindings,
            agg = Some(child.typ.colBindings),
          )
        } else Bindings.inFreshScope()
      case MatrixAggregateRowsByKey(child, _, _) =>
        if (i == 1)
          Bindings.inFreshScope(
            eval = child.typ.colBindings,
            agg = Some(child.typ.entryBindings),
          )
        else if (i == 2)
          Bindings.inFreshScope(
            eval = child.typ.globalBindings,
            agg = Some(child.typ.rowBindings),
          )
        else Bindings.inFreshScope()
      case RelationalLetMatrixTable(name, value, _) if i == 1 =>
        Bindings.inFreshScope(relational = FastSeq(name -> value.typ))
      case _ =>
        Bindings.inFreshScope()
    }
  }

  private def childEnvMatrix[E <: GenericBindingEnv[E, Type]](ir: MatrixIR, i: Int, _baseEnv: E)
    : E = {
    val baseEnv = _baseEnv.onlyRelational()
    ir match {
      case MatrixMapRows(child, _) if i == 1 =>
        baseEnv
          .createAgg.createScan
          .bindEval(child.typ.rowBindings: _*)
          .bindEval("n_cols" -> TInt32)
          .bindAgg(child.typ.entryBindings: _*)
          .bindScan(child.typ.rowBindings: _*)
      case MatrixFilterRows(child, _) if i == 1 =>
        baseEnv.bindEval(child.typ.rowBindings: _*)
      case MatrixMapCols(child, _, _) if i == 1 =>
        baseEnv
          .createAgg.createScan
          .bindEval(child.typ.colBindings: _*)
          .bindEval("n_rows" -> TInt64)
          .bindAgg(child.typ.entryBindings: _*)
          .bindScan(child.typ.colBindings: _*)
      case MatrixFilterCols(child, _) if i == 1 =>
        baseEnv.bindEval(child.typ.colBindings: _*)
      case MatrixMapEntries(child, _) if i == 1 =>
        baseEnv.bindEval(child.typ.entryBindings: _*)
      case MatrixFilterEntries(child, _) if i == 1 =>
        baseEnv.bindEval(child.typ.entryBindings: _*)
      case MatrixMapGlobals(child, _) if i == 1 =>
        baseEnv.bindEval(child.typ.globalBindings: _*)
      case MatrixAggregateColsByKey(child, _, _) =>
        if (i == 1)
          baseEnv
            .bindEval(child.typ.rowBindings: _*)
            .createAgg.bindAgg(child.typ.entryBindings: _*)
        else if (i == 2)
          baseEnv
            .bindEval(child.typ.globalBindings: _*)
            .createAgg.bindAgg(child.typ.colBindings: _*)
        else baseEnv
      case MatrixAggregateRowsByKey(child, _, _) =>
        if (i == 1)
          baseEnv
            .bindEval(child.typ.colBindings: _*)
            .createAgg.bindAgg(child.typ.entryBindings: _*)
        else if (i == 2)
          baseEnv
            .bindEval(child.typ.globalBindings: _*)
            .createAgg.bindAgg(child.typ.rowBindings: _*)
        else baseEnv
      case RelationalLetMatrixTable(name, value, _) if i == 1 =>
        baseEnv.bindRelational(name -> value.typ)
      case _ =>
        baseEnv
    }
  }

  private def childEnvTable2(ir: TableIR, i: Int): Bindings = {
    ir match {
      case TableFilter(child, _) if i == 1 =>
        Bindings.inFreshScope(child.typ.rowBindings)
      case TableGen(contexts, globals, cname, gname, _, _, _) if i == 2 =>
        Bindings.inFreshScope(FastSeq(
          cname -> elementType(contexts.typ),
          gname -> globals.typ,
        ))
      case TableMapGlobals(child, _) if i == 1 =>
        Bindings.inFreshScope(child.typ.globalBindings)
      case TableMapRows(child, _) if i == 1 =>
        Bindings.inFreshScope(
          eval = child.typ.rowBindings,
          scan = Some(child.typ.rowBindings),
        )
      case TableAggregateByKey(child, _) if i == 1 =>
        Bindings.inFreshScope(
          eval = child.typ.globalBindings,
          agg = Some(child.typ.rowBindings),
        )
      case TableKeyByAndAggregate(child, _, _, _, _) =>
        if (i == 1)
          Bindings.inFreshScope(
            eval = child.typ.globalBindings,
            agg = Some(child.typ.rowBindings),
          )
        else if (i == 2)
          Bindings.inFreshScope(child.typ.rowBindings)
        else Bindings.inFreshScope()
      case TableMapPartitions(child, g, p, _, _, _) if i == 1 =>
        Bindings.inFreshScope(FastSeq(
          g -> child.typ.globalType,
          p -> TStream(child.typ.rowType),
        ))
      case RelationalLetTable(name, value, _) if i == 1 =>
        Bindings.inFreshScope(relational = FastSeq(name -> value.typ))
      case _ =>
        Bindings.inFreshScope()
    }
  }

  private def childEnvTable[E <: GenericBindingEnv[E, Type]](ir: TableIR, i: Int, _baseEnv: E)
    : E = {
    val baseEnv = _baseEnv.onlyRelational()
    ir match {
      case TableFilter(child, _) if i == 1 =>
        baseEnv.bindEval(child.typ.rowBindings: _*)
      case TableGen(contexts, globals, cname, gname, _, _, _) if i == 2 =>
        baseEnv.bindEval(
          cname -> elementType(contexts.typ),
          gname -> globals.typ,
        )
      case TableMapGlobals(child, _) if i == 1 =>
        baseEnv.bindEval(child.typ.globalBindings: _*)
      case TableMapRows(child, _) if i == 1 =>
        baseEnv
          .bindEval(child.typ.rowBindings: _*)
          .createScan.bindScan(child.typ.rowBindings: _*)
      case TableAggregateByKey(child, _) if i == 1 =>
        baseEnv
          .bindEval(child.typ.globalBindings: _*)
          .createAgg.bindAgg(child.typ.rowBindings: _*)
      case TableKeyByAndAggregate(child, _, _, _, _) =>
        if (i == 1)
          baseEnv
            .bindEval(child.typ.globalBindings: _*)
            .createAgg.bindAgg(child.typ.rowBindings: _*)
        else if (i == 2)
          baseEnv.bindEval(child.typ.rowBindings: _*)
        else baseEnv
      case TableMapPartitions(child, g, p, _, _, _) if i == 1 =>
        baseEnv.bindEval(
          g -> child.typ.globalType,
          p -> TStream(child.typ.rowType),
        )
      case RelationalLetTable(name, value, _) if i == 1 =>
        baseEnv.bindRelational(name -> value.typ)
      case _ =>
        baseEnv
    }
  }

  private def childEnvBlockMatrix2(ir: BlockMatrixIR, i: Int): Bindings = {
    ir match {
      case BlockMatrixMap(_, eltName, _, _) if i == 1 =>
        Bindings.inFreshScope(FastSeq(eltName -> TFloat64))
      case BlockMatrixMap2(_, _, lName, rName, _, _) if i == 2 =>
        Bindings.inFreshScope(FastSeq(lName -> TFloat64, rName -> TFloat64))
      case RelationalLetBlockMatrix(name, value, _) if i == 1 =>
        Bindings.inFreshScope(relational = FastSeq(name -> value.typ))
      case _ =>
        Bindings.inFreshScope()
    }
  }

  private def childEnvBlockMatrix[E <: GenericBindingEnv[E, Type]](
    ir: BlockMatrixIR,
    i: Int,
    _baseEnv: E,
  ): E = {
    val baseEnv = _baseEnv.onlyRelational()
    ir match {
      case BlockMatrixMap(_, eltName, _, _) if i == 1 =>
        baseEnv.bindEval(eltName -> TFloat64)
      case BlockMatrixMap2(_, _, lName, rName, _, _) if i == 2 =>
        baseEnv.bindEval(lName -> TFloat64, rName -> TFloat64)
      case RelationalLetBlockMatrix(name, value, _) if i == 1 =>
        baseEnv.bindRelational(name -> value.typ)
      case _ =>
        baseEnv
    }
  }

  private def childEnvValue2(ir: IR, i: Int): Bindings =
    ir match {
      case Block(bindings, _) =>
        val eval = mutable.ArrayBuilder.make[(String, Type)]
        val agg = mutable.ArrayBuilder.make[(String, Type)]
        val scan = mutable.ArrayBuilder.make[(String, Type)]
        for (k <- 0 until i) bindings(k) match {
          case Binding(name, value, Scope.EVAL) =>
            eval += name -> value.typ
          case Binding(name, value, Scope.AGG) =>
            agg += name -> value.typ
          case Binding(name, value, Scope.SCAN) =>
            scan += name -> value.typ
        }
        if (i < bindings.length) bindings(i).scope match {
          case Scope.EVAL =>
            Bindings(
              eval.result(),
              AggEnv.bindOrNoOp(agg.result()),
              AggEnv.bindOrNoOp(scan.result()),
            )
          case Scope.AGG => Bindings(agg.result(), AggEnv.Promote, AggEnv.bindOrNoOp(scan.result()))
          case Scope.SCAN =>
            Bindings(scan.result(), AggEnv.bindOrNoOp(agg.result()), AggEnv.Promote)
        }
        else
          Bindings(eval.result(), AggEnv.bindOrNoOp(agg.result()), AggEnv.bindOrNoOp(scan.result()))
      case TailLoop(name, args, resultType, _) if i == args.length =>
        Bindings(
          args.map { case (name, ir) => name -> ir.typ } :+
            name -> TTuple(TTuple(args.map(_._2.typ): _*), resultType)
        )
      case StreamMap(a, name, _) if i == 1 =>
        Bindings(FastSeq(name -> elementType(a.typ)))
      case StreamZip(as, names, _, _, _) if i == as.length =>
        Bindings(names.zip(as.map(a => elementType(a.typ))))
      case StreamZipJoin(as, key, curKey, curVals, _) if i == as.length =>
        val eltType = tcoerce[TStruct](elementType(as.head.typ))
        Bindings(FastSeq(
          curKey -> eltType.typeAfterSelectNames(key),
          curVals -> TArray(eltType),
        ))
      case StreamZipJoinProducers(contexts, ctxName, makeProducer, key, curKey, curVals, _) =>
        if (i == 1) {
          val contextType = elementType(contexts.typ)
          Bindings(FastSeq(ctxName -> contextType))
        } else if (i == 2) {
          val eltType = tcoerce[TStruct](elementType(makeProducer.typ))
          Bindings(FastSeq(
            curKey -> eltType.typeAfterSelectNames(key),
            curVals -> TArray(eltType),
          ))
        } else Bindings.empty
      case StreamLeftIntervalJoin(left, right, _, _, lEltName, rEltName, _) if i == 2 =>
        Bindings(FastSeq(
          lEltName -> elementType(left.typ),
          rEltName -> TArray(elementType(right.typ)),
        ))
      case StreamFor(a, name, _) if i == 1 =>
        Bindings(FastSeq(name -> elementType(a.typ)))
      case StreamFlatMap(a, name, _) if i == 1 =>
        Bindings(FastSeq(name -> elementType(a.typ)))
      case StreamFilter(a, name, _) if i == 1 =>
        Bindings(FastSeq(name -> elementType(a.typ)))
      case StreamTakeWhile(a, name, _) if i == 1 =>
        Bindings(FastSeq(name -> elementType(a.typ)))
      case StreamDropWhile(a, name, _) if i == 1 =>
        Bindings(FastSeq(name -> elementType(a.typ)))
      case StreamFold(a, zero, accumName, valueName, _) if i == 2 =>
        Bindings(FastSeq(accumName -> zero.typ, valueName -> elementType(a.typ)))
      case StreamFold2(a, accum, valueName, _, _) =>
        if (i <= accum.length)
          Bindings.empty
        else if (i < 2 * accum.length + 1)
          Bindings(
            (valueName -> elementType(a.typ)) +:
              accum.map { case (name, value) => (name, value.typ) }
          )
        else
          Bindings(accum.map { case (name, value) => (name, value.typ) })
      case StreamBufferedAggregate(stream, _, _, _, name, _, _) if i > 0 =>
        Bindings(FastSeq(name -> elementType(stream.typ)))
      case RunAggScan(a, name, _, _, _, _) if i == 2 || i == 3 =>
        Bindings(FastSeq(name -> elementType(a.typ)))
      case StreamScan(a, zero, accumName, valueName, _) if i == 2 =>
        Bindings(FastSeq(
          accumName -> zero.typ,
          valueName -> elementType(a.typ),
        ))
      case StreamAggScan(a, name, _) if i == 1 =>
        val eltType = elementType(a.typ)
        Bindings(
          eval = FastSeq(name -> eltType),
          scan = AggEnv.Create(FastSeq(name -> eltType)),
        )
      case StreamJoinRightDistinct(ll, rr, _, _, l, r, _, _) if i == 2 =>
        Bindings(FastSeq(
          l -> elementType(ll.typ),
          r -> elementType(rr.typ),
        ))
      case ArraySort(a, left, right, _) if i == 1 =>
        Bindings(FastSeq(
          left -> elementType(a.typ),
          right -> elementType(a.typ),
        ))
      case ArrayMaximalIndependentSet(a, Some((left, right, _))) if i == 1 =>
        val typ = tcoerce[TBaseStruct](elementType(a.typ)).types.head
        val tupleType = TTuple(typ)
        Bindings(FastSeq(left -> tupleType, right -> tupleType), dropEval = true)
      case AggArrayPerElement(a, elementName, indexName, _, _, isScan) =>
        if (i == 0)
          Bindings(
            agg = if (isScan) AggEnv.NoOp else AggEnv.Promote,
            scan = if (!isScan) AggEnv.NoOp else AggEnv.Promote,
          )
        else if (i == 1) {
          Bindings(
            eval = FastSeq(indexName -> TInt32),
            agg = if (isScan) AggEnv.NoOp
            else AggEnv.Bind(FastSeq(
              elementName -> elementType(a.typ),
              indexName -> TInt32,
            )),
            scan = if (!isScan) AggEnv.NoOp
            else AggEnv.Bind(FastSeq(
              elementName -> elementType(a.typ),
              indexName -> TInt32,
            )),
          )
        } else Bindings.empty
      case AggFold(zero, _, _, accumName, otherAccumName, isScan) =>
        if (i == 0)
          Bindings(
            agg = if (isScan) AggEnv.NoOp else AggEnv.Drop,
            scan = if (!isScan) AggEnv.NoOp else AggEnv.Drop,
          )
        else if (i == 1)
          Bindings(
            eval = FastSeq(accumName -> zero.typ),
            agg = if (isScan) AggEnv.NoOp else AggEnv.Promote,
            scan = if (!isScan) AggEnv.NoOp else AggEnv.Promote,
          )
        else
          Bindings(
            eval = FastSeq(accumName -> zero.typ, otherAccumName -> zero.typ),
            agg = if (isScan) AggEnv.NoOp else AggEnv.Drop,
            scan = if (!isScan) AggEnv.NoOp else AggEnv.Drop,
            dropEval = true,
          )
      case NDArrayMap(nd, name, _) if i == 1 =>
        Bindings(FastSeq(name -> tcoerce[TNDArray](nd.typ).elementType))
      case NDArrayMap2(l, r, lName, rName, _, _) if i == 2 =>
        Bindings(FastSeq(
          lName -> tcoerce[TNDArray](l.typ).elementType,
          rName -> tcoerce[TNDArray](r.typ).elementType,
        ))
      case CollectDistributedArray(contexts, globals, cname, gname, _, _, _, _) if i == 2 =>
        Bindings(
          eval = FastSeq(
            cname -> elementType(contexts.typ),
            gname -> globals.typ,
          ),
          agg = AggEnv.Drop,
          scan = AggEnv.Drop,
          dropEval = true,
        )
      case TableAggregate(child, _) =>
        if (i == 1)
          Bindings(
            eval = child.typ.globalBindings,
            agg = AggEnv.Create(child.typ.rowBindings),
            scan = AggEnv.Drop,
            dropEval = true,
          )
        else Bindings(agg = AggEnv.Drop, scan = AggEnv.Drop, dropEval = true)
      case MatrixAggregate(child, _) =>
        if (i == 1)
          Bindings(
            eval = child.typ.globalBindings,
            agg = AggEnv.Create(child.typ.entryBindings),
            scan = AggEnv.Drop,
            dropEval = true,
          )
        else Bindings(agg = AggEnv.Drop, scan = AggEnv.Drop, dropEval = true)
      case ApplyAggOp(init, _, _) =>
        if (i < init.length) Bindings(agg = AggEnv.Drop)
        else Bindings(agg = AggEnv.Promote)
      case ApplyScanOp(init, _, _) =>
        if (i < init.length) Bindings(scan = AggEnv.Drop)
        else Bindings(scan = AggEnv.Promote)
      case AggFilter(_, _, isScan) if i == 0 =>
        Bindings(
          agg = if (isScan) AggEnv.NoOp else AggEnv.Promote,
          scan = if (!isScan) AggEnv.NoOp else AggEnv.Promote,
        )
      case AggGroupBy(_, _, isScan) if i == 0 =>
        Bindings(
          agg = if (isScan) AggEnv.NoOp else AggEnv.Promote,
          scan = if (!isScan) AggEnv.NoOp else AggEnv.Promote,
        )
      case AggExplode(a, name, _, isScan) =>
        if (i == 0)
          Bindings(
            agg = if (isScan) AggEnv.NoOp else AggEnv.Promote,
            scan = if (!isScan) AggEnv.NoOp else AggEnv.Promote,
          )
        else
          Bindings(
            agg = if (isScan) AggEnv.NoOp else AggEnv.Bind(FastSeq(name -> elementType(a.typ))),
            scan = if (!isScan) AggEnv.NoOp else AggEnv.Bind(FastSeq(name -> elementType(a.typ))),
          )
      case StreamAgg(a, name, _) if i == 1 =>
        Bindings(agg = AggEnv.Create(FastSeq(name -> elementType(a.typ))))
      case RelationalLet(name, value, _) =>
        if (i == 1)
          Bindings(
            agg = AggEnv.Drop,
            scan = AggEnv.Drop,
            relational = FastSeq(name -> value.typ),
          )
        else
          Bindings(
            agg = AggEnv.Drop,
            scan = AggEnv.Drop,
            dropEval = true,
          )
      case _: LiftMeOut =>
        Bindings(
          agg = AggEnv.Drop,
          scan = AggEnv.Drop,
          dropEval = true,
        )
      case _ =>
        if (UsesAggEnv(ir, i))
          Bindings(agg = AggEnv.Promote)
        else if (UsesScanEnv(ir, i))
          Bindings(scan = AggEnv.Promote)
        else Bindings.empty
    }

  private def childEnvValue[E <: GenericBindingEnv[E, Type]](ir: IR, i: Int, baseEnv: E): E =
    ir match {
      case Block(bindings, _) =>
        var env = baseEnv
        for (k <- 0 until i) bindings(k) match {
          case Binding(name, value, scope) =>
            env = env.bindInScope(name, value.typ, scope)
        }
        if (i < bindings.length) bindings(i).scope match {
          case Scope.EVAL => env
          case Scope.AGG => env.promoteAgg
          case Scope.SCAN => env.promoteScan
        }
        else env
      case TailLoop(name, args, resultType, _) if i == args.length =>
        baseEnv
          .bindEval(args.map { case (name, ir) => name -> ir.typ }: _*)
          .bindEval(name -> TTuple(TTuple(args.map(_._2.typ): _*), resultType))
      case StreamMap(a, name, _) if i == 1 =>
        baseEnv.bindEval(name -> elementType(a.typ))
      case StreamZip(as, names, _, _, _) if i == as.length =>
        baseEnv.bindEval(names.zip(as.map(a => elementType(a.typ))): _*)
      case StreamZipJoin(as, key, curKey, curVals, _) if i == as.length =>
        val eltType = tcoerce[TStruct](elementType(as.head.typ))
        baseEnv.bindEval(
          curKey -> eltType.typeAfterSelectNames(key),
          curVals -> TArray(eltType),
        )
      case StreamZipJoinProducers(contexts, ctxName, makeProducer, key, curKey, curVals, _) =>
        if (i == 1) {
          val contextType = elementType(contexts.typ)
          baseEnv.bindEval(ctxName -> contextType)
        } else if (i == 2) {
          val eltType = tcoerce[TStruct](elementType(makeProducer.typ))
          baseEnv.bindEval(
            curKey -> eltType.typeAfterSelectNames(key),
            curVals -> TArray(eltType),
          )
        } else baseEnv
      case StreamLeftIntervalJoin(left, right, _, _, lEltName, rEltName, _) if i == 2 =>
        baseEnv.bindEval(
          lEltName -> elementType(left.typ),
          rEltName -> TArray(elementType(right.typ)),
        )
      case StreamFor(a, name, _) if i == 1 =>
        baseEnv.bindEval(name -> elementType(a.typ))
      case StreamFlatMap(a, name, _) if i == 1 =>
        baseEnv.bindEval(name -> elementType(a.typ))
      case StreamFilter(a, name, _) if i == 1 =>
        baseEnv.bindEval(name -> elementType(a.typ))
      case StreamTakeWhile(a, name, _) if i == 1 =>
        baseEnv.bindEval(name -> elementType(a.typ))
      case StreamDropWhile(a, name, _) if i == 1 =>
        baseEnv.bindEval(name -> elementType(a.typ))
      case StreamFold(a, zero, accumName, valueName, _) if i == 2 =>
        baseEnv.bindEval(accumName -> zero.typ, valueName -> elementType(a.typ))
      case StreamFold2(a, accum, valueName, _, _) =>
        if (i <= accum.length)
          baseEnv
        else if (i < 2 * accum.length + 1)
          baseEnv
            .bindEval(valueName -> elementType(a.typ))
            .bindEval(accum.map { case (name, value) => (name, value.typ) }: _*)
        else
          baseEnv.bindEval(accum.map { case (name, value) => (name, value.typ) }: _*)
      case StreamBufferedAggregate(stream, _, _, _, name, _, _) if i > 0 =>
        baseEnv.bindEval(name -> elementType(stream.typ))
      case RunAggScan(a, name, _, _, _, _) if i == 2 || i == 3 =>
        baseEnv.bindEval(name -> elementType(a.typ))
      case StreamScan(a, zero, accumName, valueName, _) if i == 2 =>
        baseEnv.bindEval(
          accumName -> zero.typ,
          valueName -> elementType(a.typ),
        )
      case StreamAggScan(a, name, _) if i == 1 =>
        val eltType = elementType(a.typ)
        baseEnv
          .bindEval(name -> eltType)
          .createScan.bindScan(name -> eltType)
      case StreamJoinRightDistinct(ll, rr, _, _, l, r, _, _) if i == 2 =>
        baseEnv.bindEval(
          l -> elementType(ll.typ),
          r -> elementType(rr.typ),
        )
      case ArraySort(a, left, right, _) if i == 1 =>
        baseEnv.bindEval(
          left -> elementType(a.typ),
          right -> elementType(a.typ),
        )
      case ArrayMaximalIndependentSet(a, Some((left, right, _))) if i == 1 =>
        val typ = tcoerce[TBaseStruct](elementType(a.typ)).types.head
        val tupleType = TTuple(typ)
        baseEnv.noEval.bindEval(left -> tupleType, right -> tupleType)
      case AggArrayPerElement(a, elementName, indexName, _, _, isScan) =>
        if (i == 0) baseEnv.promoteAggOrScan(isScan)
        else if (i == 1)
          baseEnv
            .bindEval(indexName -> TInt32)
            .bindAggOrScan(
              isScan,
              elementName -> elementType(a.typ),
              indexName -> TInt32,
            )
        else baseEnv
      case AggFold(zero, _, _, accumName, otherAccumName, isScan) =>
        if (i == 0) baseEnv.noAggOrScan(isScan)
        else if (i == 1) baseEnv.promoteAggOrScan(isScan).bindEval(accumName -> zero.typ)
        else baseEnv.noEval.noAggOrScan(isScan)
          .bindEval(accumName -> zero.typ, otherAccumName -> zero.typ)
      case NDArrayMap(nd, name, _) if i == 1 =>
        baseEnv.bindEval(name -> tcoerce[TNDArray](nd.typ).elementType)
      case NDArrayMap2(l, r, lName, rName, _, _) if i == 2 =>
        baseEnv.bindEval(
          lName -> tcoerce[TNDArray](l.typ).elementType,
          rName -> tcoerce[TNDArray](r.typ).elementType,
        )
      case CollectDistributedArray(contexts, globals, cname, gname, _, _, _, _) if i == 2 =>
        baseEnv.onlyRelational().bindEval(
          cname -> elementType(contexts.typ),
          gname -> globals.typ,
        )
      case TableAggregate(child, _) =>
        if (i == 1)
          baseEnv.onlyRelational()
            .bindEval(child.typ.globalBindings: _*)
            .createAgg.bindAgg(child.typ.rowBindings: _*)
        else baseEnv.onlyRelational()
      case MatrixAggregate(child, _) =>
        if (i == 1)
          baseEnv.onlyRelational()
            .bindEval(child.typ.globalBindings: _*)
            .createAgg.bindAgg(child.typ.entryBindings: _*)
        else baseEnv.onlyRelational()
      case ApplyAggOp(init, _, _) =>
        if (i < init.length) baseEnv.noAgg
        else baseEnv.promoteAgg
      case ApplyScanOp(init, _, _) =>
        if (i < init.length) baseEnv.noScan
        else baseEnv.promoteScan
      case AggFilter(_, _, isScan) if i == 0 =>
        baseEnv.promoteAggOrScan(isScan)
      case AggGroupBy(_, _, isScan) if i == 0 =>
        baseEnv.promoteAggOrScan(isScan)
      case AggExplode(a, name, _, isScan) =>
        if (i == 0) baseEnv.promoteAggOrScan(isScan)
        else baseEnv.bindAggOrScan(isScan, name -> elementType(a.typ))
      case StreamAgg(a, name, _) if i == 1 =>
        baseEnv.createAgg
          .bindAgg(name -> elementType(a.typ))
      case RelationalLet(name, value, _) =>
        if (i == 1)
          baseEnv.noAgg.noScan.bindRelational(name -> value.typ)
        else
          baseEnv.onlyRelational()
      case _: LiftMeOut =>
        baseEnv.onlyRelational()
      case _ =>
        if (UsesAggEnv(ir, i)) baseEnv.promoteAgg
        else if (UsesScanEnv(ir, i)) baseEnv.promoteScan
        else baseEnv
    }
}
