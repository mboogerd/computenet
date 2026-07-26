package civictech.testkit

import org.opentest4j.AssertionFailedError

/**
 * Poll [condition] until it is true or [timeoutMs] elapses, sleeping 100ms between
 * checks. Fails loudly (naming [what] was being awaited) rather than hanging —
 * canonical form taken from `TwoJvmConvergenceTest.await` / `CrashRestartConvergenceTest.await`
 * / `ExchangeScaffoldTest.await`, the three copies that share this exact shape.
 */
fun awaitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
        Thread.sleep(100)
    }
}
