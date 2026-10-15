package civictech.concord.driver.kernel

import civictech.concord.oracle.Fx.i
import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.map
import civictech.concord.oracle.Fx.s
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/** Named-journal placement and recovery at the Concord durable-driver seam. */
class DurJournalBindingTest {

    private val budget = 5_000_000

    private fun named(name: String): Map<String, Value> = mapOf("journal" to s(name))

    @Test
    fun `a controller rebuilds the host but recovers only its named journal`() {
        val d = KernelDriver(0L).also { driver ->
            driver.spawn(KernelDriverDur.DUR_HOST, "a", "journal-set-source", named("a"))
            driver.spawn(KernelDriverDur.DUR_HOST, "av", "set-view", emptyMap())
            driver.spawn(KernelDriverDur.DUR_HOST, "b", "journal-set-source", named("b"))
            driver.spawn(KernelDriverDur.DUR_HOST, "bv", "set-view", emptyMap())
            driver.spawn(KernelDriverDur.DUR_HOST, "ctl-a", "journal", named("a"))
            driver.connect("a", "av")
            driver.connect("b", "bv")
        }

        d.apply("a", "add", s("a1"))
        d.apply("a", "add", s("a2"))
        d.apply("b", "add", s("b1"))
        d.quiesce(budget)
        d.readView("av") shouldBe list(s("a1"), s("a2"))
        d.readView("bv") shouldBe list(s("b1"))

        d.despawn("ctl-a")
        d.quiesce(budget)

        d.readView("av") shouldBe list(s("a1"), s("a2"))
        d.readView("bv") shouldBe list()
    }

    @Test
    fun `unnamed journaled cells and controllers use default`() {
        val d = KernelDriver(0L).also { driver ->
            driver.spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", emptyMap())
            driver.spawn(KernelDriverDur.DUR_HOST, "view", "set-view", emptyMap())
            driver.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap())
            driver.connect("source", "view")
        }
        d.apply("source", "add", s("kept"))
        d.quiesce(budget)

        d.despawn("ctl")
        d.quiesce(budget)

        d.readView("view") shouldBe list(s("kept"))
    }

    @Test
    fun `partition and count-view bind as volatile or named-journal cells`() {
        val d = KernelDriver(0L).also { driver ->
            driver.spawn(KernelDriverDur.DUR_HOST, "durable-source", "set-source", named("parts"))
            driver.spawn(KernelDriverDur.DUR_HOST, "durable-partition", "partition", named("parts"))
            driver.spawn(KernelDriverDur.DUR_HOST, "durable-view", "count-view", named("parts"))
            driver.spawn(KernelDriverDur.DUR_HOST, "volatile-source", "set-source", emptyMap())
            driver.spawn(KernelDriverDur.DUR_HOST, "volatile-partition", "partition", emptyMap())
            driver.spawn(KernelDriverDur.DUR_HOST, "volatile-view", "count-view", emptyMap())
            driver.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", named("parts"))
            driver.connect("durable-source", "durable-partition")
            driver.connect("durable-partition", "durable-view")
            driver.connect("volatile-source", "volatile-partition")
            driver.connect("volatile-partition", "volatile-view")
        }
        d.apply("durable-source", "add", s("a"))
        d.apply("durable-source", "add", s("b"))
        d.apply("volatile-source", "add", s("v"))
        d.quiesce(budget)
        d.readView("durable-view") shouldBe map("a" to i(1), "b" to i(1))
        d.readView("volatile-view") shouldBe map("v" to i(1))

        d.despawn("ctl")
        d.quiesce(budget)

        d.readView("durable-view") shouldBe map("a" to i(1), "b" to i(1))
        d.readView("volatile-view") shouldBe map()
    }

    @Test
    fun `an empty journal name is refused`() {
        assertThrows<UnsupportedCatalogBinding> {
            KernelDriver(0L).spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", named(""))
        }.message!! shouldContain "non-empty string"
    }

    @Test
    fun `journal on a cell outside the durable host is refused`() {
        assertThrows<UnsupportedCatalogBinding> {
            KernelDriver(0L).spawn("h1", "source", "set-source", named("a"))
        }.message!! shouldContain "host: dur"
    }

    @Test
    fun `snapshot of a journaled partition is refused as not Stateful`() {
        val d = KernelDriver(0L)
        d.spawn(KernelDriverDur.DUR_HOST, "partition", "partition", named("parts"))

        assertThrows<UnsupportedCatalogBinding> { d.snapshot("partition") }
            .message!! shouldContain "not Stateful"
    }
}
