package civictech.concord.driver.kernel

import civictech.concord.oracle.Fx.i
import civictech.concord.oracle.Fx.map
import civictech.concord.oracle.Fx.s
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * The refusals of the `dur` evicting-window bindings (computenet-t4od7.8,
 * t4od7-D8). The accepting half — `journal-window`, `waterline` and
 * `journal-count-view` built and wired through named ports, recovered across a
 * crash — is `concord/corpus/15-durability/24-WL-REC-01.yaml`; a corpus
 * scenario cannot exercise a refusal, because one that provoked it would
 * simply error rather than assert anything (same split as [RetransmitBindingTest]).
 */
class DurWindowBindingTest {

    private fun dur(): KernelDriver = KernelDriver(0L)

    @Test fun `a sliding journal-window with lateness is refused naming WindowSlidingCell`() {
        val params = mapOf(
            "window" to map("kind" to s("sliding"), "size" to i(10), "slide" to i(5)),
            "agg" to s("count"),
            "lateness" to i(2),
        )
        assertThrows<UnsupportedCatalogBinding> {
            dur().spawn(KernelDriverDur.DUR_HOST, "jw", "journal-window", params)
        }.message!! shouldContain "WindowSlidingCell"
    }

    @Test fun `a durable waterline without lateness is refused`() {
        assertThrows<UnsupportedCatalogBinding> {
            dur().spawn(KernelDriverDur.DUR_HOST, "wl", "waterline", emptyMap())
        }.message!! shouldContain "lateness"
    }

    @Test fun `a durable link naming an inlet the destination does not register is refused`() {
        val d = dur()
        d.spawn(KernelDriverDur.DUR_HOST, "s", "journal-set-source", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "jv", "journal-count-view", emptyMap())
        assertThrows<UnsupportedCatalogBinding> {
            d.connect("s", "jv", "waterline", null, null)
        }.message!! shouldContain "does not register"
    }
}
