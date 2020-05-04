package provenance.rdd

import provenance.data.{DummyProvenance, Provenance, RoaringBitmapProvenance}
import symbolicprimitives.{SymBase, SymInt}

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.reflect.ClassTag

/** Provenance tracker trait for influence functions, designed to mirror combineByKey but with
  * flexibility in definition. All operations should be assumed to be potential mutators. */
trait InfluenceTracker[T] extends Serializable {
  def init(value: ProvenanceRow[T]): InfluenceTracker[T]
  def mergeValue(value: ProvenanceRow[T]): InfluenceTracker[T]
  def mergeTracker(other: InfluenceTracker[T]): InfluenceTracker[T]
  /** Return the provenance tracked in this tracker. Note that this method may be destructive and
   should only be called once! *  */
  def computeProvenance(): Provenance
}

case class AllInfluenceTracker[T]() extends InfluenceTracker[T] {
  private var prov: Provenance = _
  
  override def init(value: ProvenanceRow[T]): AllInfluenceTracker[T] = {
    prov = value._2
    this
  }
  
  override def mergeValue(value: ProvenanceRow[T]): AllInfluenceTracker[T] = {
    prov.merge(value._2)
    this
  }
  
  override def mergeTracker(other: InfluenceTracker[T]): AllInfluenceTracker[T] = {
    prov.merge(other.computeProvenance())
    this
  }
  
  /** Return the provenance tracked in this tracker. Note that this method may be destructive and
    * should only be called once! *  */
  override def computeProvenance(): Provenance = prov
}
abstract class OrderingInfluenceTracker[T](val ordering: Ordering[T]) extends InfluenceTracker[T] {
  val rowOrdering: Ordering[ProvenanceRow[T]] =
    Ordering.by[ProvenanceRow[T], T](_._1)(ordering)
}

abstract class SingleOrderedTracker[T](ordering: Ordering[T])
  extends OrderingInfluenceTracker[T](ordering) {
  private var maxRow: ProvenanceRow[T] = _
  
  private def maxWith(row: ProvenanceRow[T]): SingleOrderedTracker[T] = {
    maxRow = rowOrdering.max(maxRow, row)
    this
  }
  
  override def init(value: ProvenanceRow[T]): SingleOrderedTracker[T] = {
    maxRow = value
    this
  }
  
  override def mergeValue(value: ProvenanceRow[T]): SingleOrderedTracker[T] = {
    maxWith(value)
  }
  
  override def mergeTracker(other: InfluenceTracker[T]): SingleOrderedTracker[T] = {
    other match {
      case o: SingleOrderedTracker[T] =>
        // This technically doesn't prevent us from comparing min to max, so use cautiously!
        maxWith(o.maxRow)
      case _ =>
        throw new UnsupportedOperationException(s"Cannot compare ${this.getClass.getSimpleName} " +
                                                  "with other InfluenceTracker types")
    }
  }
  
  override def computeProvenance(): Provenance = {
    maxRow._2
  }
}
case class MaxInfluenceTracker[T](override implicit val ordering: Ordering[T])
  extends SingleOrderedTracker[T](ordering)

case class MinInfluenceTracker[T](override implicit val ordering: Ordering[T])
  extends SingleOrderedTracker[T](ordering.reverse)

abstract class OrderedNInfluenceTracker[T](val maxSize: Int)(implicit ordering: Ordering[T])
  extends OrderingInfluenceTracker[T](ordering) {
  
  private val heap = new mutable.PriorityQueue[ProvenanceRow[T]]()(rowOrdering)
  
  /** Add elements to the heap and resize if needed.
    * This currently uses varargs syntax, but performance hasn't been tested.*/
  private def addToHeap(values: ProvenanceRow[T]*): OrderedNInfluenceTracker[T] = {
    heap.enqueue(values: _*)
    while(heap.size > maxSize) heap.dequeue()
    this
  }
  override def init(value: ProvenanceRow[T]): OrderedNInfluenceTracker[T] = addToHeap(value)
  
  override def mergeValue(value: ProvenanceRow[T]): OrderedNInfluenceTracker[T] = addToHeap(value)
  
  override def mergeTracker(other: InfluenceTracker[T]): OrderedNInfluenceTracker[T] = {
    other match {
      case o: OrderedNInfluenceTracker[T] =>
        assert(this.maxSize == o.maxSize)
        addToHeap(o.heap.toSeq: _*)
      case _ =>
        throw new UnsupportedOperationException(s"Cannot compare ${this.getClass.getSimpleName} " +
                                                  "with other InfluenceTracker types")
    }
  }
  
  override def computeProvenance(): Provenance = {
    heap.foldLeft(DummyProvenance.create())(
      {case (unionProv, provRow) => unionProv.merge(provRow._2)})
  }
}

case class BottomNInfluenceTracker[T](override val maxSize: Int)(implicit ordering: Ordering[T])
  extends OrderedNInfluenceTracker[T](maxSize)(ordering)

case class TopNInfluenceTracker[T](override val maxSize: Int)(implicit ordering: Ordering[T])
  extends OrderedNInfluenceTracker[T](maxSize)(ordering.reverse)

class UnionInfluenceTracker[T](val trackers: InfluenceTracker[T]*) extends InfluenceTracker[T] {
  override def init(value: ProvenanceRow[T]): UnionInfluenceTracker[T] ={
    trackers.foreach(_.init(value))
    this
  }
  
  override def mergeValue(value: ProvenanceRow[T]): UnionInfluenceTracker[T] = {
    trackers.foreach(_.mergeValue(value))
    this
  }
  
  override def mergeTracker(other: InfluenceTracker[T]): UnionInfluenceTracker[T] = {
    other match {
      case o: UnionInfluenceTracker[T] =>
        // Assumption: same set of tracker types
        trackers.zip(o.trackers).foreach {case (a, b) => a.mergeTracker(b)}
        this
      case _ =>
        throw new UnsupportedOperationException(s"Cannot compare ${this.getClass.getSimpleName} " +
                                                  "with other InfluenceTracker types")
    }
  }
  
  /** Return the provenance tracked in this tracker. Note that this method may be destructive and
    * should only be called once! *  */
  override def computeProvenance(): Provenance = {
    trackers.foldLeft(DummyProvenance.create())(
      {case (unionProv, tracker) => unionProv.merge(tracker.computeProvenance())}
    )
  }
}

/** Retains provenance for only the values that pass (true) the filterFn */
case class FilterInfluenceTracker[T](filterFn: T => Boolean) extends InfluenceTracker[T] {
  // For unknown reasons, using a ListBuffer can result in loss of entire data rows (not just
  // provenance, but the rdd count itself.
  //private val values = ListBuffer[Provenance]()
  private val values = ArrayBuffer[Provenance]()

  private def addIfFiltered(value: ProvenanceRow[T]): FilterInfluenceTracker[T] = {
    if(filterFn(value._1)) values += value._2
    this
  }
  // Technically should 'reset', but lazy implementation for now.
  override def init(value: ProvenanceRow[T]): FilterInfluenceTracker[T] = addIfFiltered(value)
  
  override def mergeValue(value: ProvenanceRow[T]): FilterInfluenceTracker[T] = addIfFiltered(value)
  
  override def mergeTracker(other: InfluenceTracker[T]): FilterInfluenceTracker[T] = {
    other match {
      case o: FilterInfluenceTracker[T] =>
        // primitive implementation, doesn't do any deduplication on the fly.
        this.values ++= o.values
        this
      case _ =>
        throw new UnsupportedOperationException(s"Cannot compare ${this.getClass.getSimpleName} " +
                                                  "with other InfluenceTracker types")
    }
  }
  
  /** Return the provenance tracked in this tracker. Note that this method may be destructive and
    * should only be called once! *  */
  override def computeProvenance(): Provenance =
    values.foldLeft(DummyProvenance.create())(_.merge(_))
}

object InfluenceTrackerExample{
  def main(args: Array[String]): Unit = {
    val first = MaxInfluenceTracker[Int]
    first.init((1, DummyProvenance.create()))
    first.mergeValue(20, RoaringBitmapProvenance.create(3))
  
    val second = MaxInfluenceTracker[Int]
  
    second.init((100, RoaringBitmapProvenance.create(100, 20)))
  
    println(first.mergeTracker(second).computeProvenance())
    
    val heap = new TopNInfluenceTracker[Int](3)
    heap.init((1, RoaringBitmapProvenance.create(100)))
    heap.mergeValue((2, RoaringBitmapProvenance.create(200)))
    heap.mergeValue((3, RoaringBitmapProvenance.create(300)))
    heap.mergeValue((4, RoaringBitmapProvenance.create(400)))
    
    println(heap.computeProvenance())
    val heap2 = new TopNInfluenceTracker[Int](3)
    heap2.init((5, RoaringBitmapProvenance.create(500)))
    println(heap.mergeTracker(heap2).computeProvenance())
    
    val ct: ClassTag[SymInt] = scala.reflect.classTag[SymInt]
    println(classOf[SymBase].isAssignableFrom(ct.runtimeClass))
    
    val filterTracker = FilterInfluenceTracker[Int](_ > 5)
    filterTracker.init((1, RoaringBitmapProvenance.create(1)))
    filterTracker.mergeValue((6, RoaringBitmapProvenance.create(600)))
    val filterTracker2 = FilterInfluenceTracker[Int](_ > 5)
    filterTracker.init((7, RoaringBitmapProvenance.create(700)))
    filterTracker.mergeValue((3, RoaringBitmapProvenance.create(3)))
    filterTracker.mergeTracker(filterTracker2)
    println(filterTracker.computeProvenance())
  }
}