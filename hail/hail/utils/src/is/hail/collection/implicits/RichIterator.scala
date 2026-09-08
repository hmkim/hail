package is.hail.collection.implicits

import is.hail.collection.{FlipbookIterator, StagingIterator, StateMachine}

import scala.collection.immutable.ArraySeq
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

import java.io.{InputStreamReader, PrintWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class RichIterator[T](val it: Iterator[T]) extends AnyVal {
  def toStagingIterator: StagingIterator[T] = {
    val bit = it.buffered
    StagingIterator(
      new StateMachine[T] {
        override def value: T = bit.head
        override def isValid = bit.hasNext
        override def advance(): Unit = bit.next(): Unit
      }
    )
  }

  def toFlipbookIterator: FlipbookIterator[T] = toStagingIterator

  def foreachBetween(f: (T) => Unit)(g: => Unit): Unit = {
    if (it.hasNext) {
      f(it.next())
      while (it.hasNext) {
        g
        f(it.next())
      }
    }
  }

  def intersperse[S >: T](sep: S): Iterator[S] = new Iterator[S] {
    var nextIsSep = false
    override def hasNext = it.hasNext

    override def next() = {
      val n = if (nextIsSep) sep else it.next()
      nextIsSep = !nextIsSep
      n
    }
  }

  def intersperse[S >: T](start: S, sep: S, end: S): Iterator[S] = new Iterator[S] {
    var state = 0
    override def hasNext = state != 4

    override def next() = {
      state match {
        case 0 =>
          state = if (it.hasNext) 1 else 3
          start
        case 1 =>
          val n = it.next()
          state = if (it.hasNext) 2 else 3
          n
        case 2 =>
          state = 1
          sep
        case 3 =>
          state = 4
          end
      }
    }
  }

  def pipe(
    pb: ProcessBuilder,
    printHeader: (String => Unit) => Unit,
    printElement: (String => Unit, T) => Unit,
    printFooter: (String => Unit) => Unit,
    timeoutMillis: Long = 0L,
    maxStderrBytes: Int = Int.MaxValue,
  ): (Iterator[String], StringBuilder, Process) = {
    require(timeoutMillis >= 0L, s"timeoutMillis must be non-negative, found $timeoutMillis")
    require(maxStderrBytes > 0, s"maxStderrBytes must be positive, found $maxStderrBytes")

    val command = pb.command().asScala.mkString(" ")

    val proc = pb.start()

    val error = new StringBuilder()
    val stderrReader = new Thread("stderr reader for " + command) {
      override def run(): Unit = {
        val reader = new InputStreamReader(proc.getErrorStream, StandardCharsets.UTF_8)
        val buffer = new Array[Char](8192)
        var captured = 0
        var truncated = false
        var n = reader.read(buffer)
        while (n >= 0) {
          val remaining = maxStderrBytes - captured
          if (remaining > 0) {
            val toAppend = math.min(n, remaining)
            error.synchronized {
              error.append(new String(buffer, 0, toAppend))
              ()
            }
            captured += toAppend
          }
          if (n > remaining) truncated = true
          n = reader.read(buffer)
        }
        if (truncated)
          error.synchronized {
            error.append(s"\n[stderr truncated after $maxStderrBytes characters]\n")
            ()
          }
        reader.close()
      }
    }
    stderrReader.start()

    if (timeoutMillis > 0L) {
      val watchdog = new Thread("timeout watchdog for " + command) {
        override def run(): Unit =
          try {
            Thread.sleep(timeoutMillis)
            if (proc.isAlive) {
              error.synchronized {
                error.append(s"\n[process timed out after $timeoutMillis ms]\n")
                ()
              }
              proc.destroy()
              if (!proc.waitFor(5, TimeUnit.SECONDS))
                proc.destroyForcibly()
            }
          } catch {
            case _: InterruptedException => Thread.currentThread().interrupt()
          }
      }
      watchdog.setDaemon(true)
      watchdog.start()
    }

    // Start a thread to feed the process input from our parent's iterator
    new Thread("stdin writer for " + command) {
      override def run(): Unit = {
        val out = new PrintWriter(proc.getOutputStream)

        printHeader(out.println)
        it.foreach(x => printElement(out.println, x))
        printFooter(out.println)
        out.close()
      }
    }.start()

    // Return an iterator that reads lines from the process's stdout,
    // a bounded StringBuilder that captures standard error that should not be read
    // from or written to until waiting for the process, and the process itself
    (Source.fromInputStream(proc.getInputStream).getLines(), error, proc)
  }

  def trueGroupedIterator(groupSize: Int): Iterator[Iterator[T]] =
    new Iterator[Iterator[T]] {
      var prev: Iterator[T] = null

      override def hasNext: Boolean = it.hasNext

      override def next(): Iterator[T] = {
        if (!hasNext)
          throw new NoSuchElementException("next on empty iterator")

        /* the previous element must must be fully consumed or the next block will start in the
         * wrong place */
        assert(prev == null || !prev.hasNext)
        prev = new Iterator[T] {
          var i = 0

          override def hasNext: Boolean = it.hasNext && i < groupSize

          override def next(): T = {
            if (!hasNext)
              throw new NoSuchElementException("next on empty iterator")
            i += 1
            it.next()
          }
        }

        prev
      }
    }

  def toFastSeq(implicit tct: ClassTag[T]): IndexedSeq[T] = it.to(ArraySeq)

  def headOption: Option[T] = if (it.isEmpty) None else Some(it.next())
}
