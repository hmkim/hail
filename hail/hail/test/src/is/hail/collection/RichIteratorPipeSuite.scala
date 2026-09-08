package is.hail.collection

import is.hail.collection.implicits.toRichIterator

import org.junit.jupiter.api.Assertions.{assertFalse, assertNotEquals, assertTrue}
import org.junit.jupiter.api.Test

class RichIteratorPipeSuite {
  @Test
  def testPipeTimeoutUnblocksStdoutReader(): Unit = {
    val started = System.nanoTime()
    val (stdout, stderr, process) =
      Iterator("input").pipe(
        new ProcessBuilder("bash", "-c", "cat >/dev/null; exec sleep 30"),
        _ => (),
        (write, value) => write(value),
        _ => (),
        timeoutMillis = 200L,
      )

    assertFalse(stdout.hasNext)
    assertNotEquals(0, process.waitFor())
    val elapsedSeconds = (System.nanoTime() - started).toDouble / 1_000_000_000d
    assertTrue(elapsedSeconds < 5d, s"timed process took $elapsedSeconds seconds")
    assertTrue(stderr.synchronized(stderr.toString()).contains("process timed out after 200 ms"))
  }

  @Test
  def testPipeBoundsAndDrainsStderr(): Unit = {
    val limit = 128
    val (stdout, stderr, process) =
      Iterator("input").pipe(
        new ProcessBuilder(
          "bash",
          "-c",
          "cat >/dev/null; head -c 4096 /dev/zero | tr '\\0' x >&2",
        ),
        _ => (),
        (write, value) => write(value),
        _ => (),
        maxStderrBytes = limit,
      )

    assertFalse(stdout.hasNext)
    assertTrue(process.waitFor() == 0)
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!stderr.synchronized(stderr.toString()).contains("stderr truncated") && System.nanoTime() < deadline)
      Thread.sleep(10L)
    val captured = stderr.synchronized(stderr.toString())
    assertTrue(captured.startsWith("x" * limit))
    assertTrue(captured.contains(s"stderr truncated after $limit characters"))
    assertTrue(captured.length < limit + 100)
  }
}
