package civictech.agora

import civictech.agora.cell.CredenceView
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * computenet-ecso: `CredenceView.credences` is written by `apply()` (the
 * kernel scheduler thread, via `ObserveCell.propagate`) and, in normal use,
 * only ever read back by `ObserveCell` itself on that same thread — but
 * `View.current()`/`apply()` are public, so a future direct caller (bypassing
 * the `ObservationSink`'s own `latest` field) would depend on `credences`
 * being safely published on its own.
 *
 * A timing-based reproduction cannot pin a JMM visibility defect: the bug is
 * "the JVM is *permitted* to show a reader a stale reference", not "it
 * observably does" under any particular scheduler or hardware, so a test
 * that races two threads and asserts on what it happens to observe would
 * pass or fail by luck and prove nothing either way (mutation-check.md's
 * substitute route for a property that cannot be pinned by a discriminating
 * test — see the computenet-ecso task report for the citation and the
 * mutation-check evidence run against this test).
 *
 * What CAN be pinned deterministically is the publication mechanism itself:
 * whether the backing field actually carries the JVM's `volatile` modifier.
 * This is a genuine discriminating check, not a proxy — `@Volatile` on a
 * Kotlin `var` compiles directly to `ACC_VOLATILE` on the backing field, so
 * this test fails if that annotation is ever removed and passes only while
 * it is present, with no dependence on timing, thread scheduling or hardware
 * memory model quirks.
 */
class CredenceViewPublicationTest {

    @Test
    fun `credences field is volatile`() {
        val field = CredenceView::class.java.getDeclaredField("credences")
        assertTrue(
            Modifier.isVolatile(field.modifiers),
            "CredenceView.credences must be @Volatile so a reader on another thread " +
                "is guaranteed to see a fully constructed, up-to-date map " +
                "(computenet-ecso), whether it reads via ObserveCell's own " +
                "@Volatile latest (already safe today) or, in the future, via " +
                "CredenceView.current() directly.",
        )
    }
}
