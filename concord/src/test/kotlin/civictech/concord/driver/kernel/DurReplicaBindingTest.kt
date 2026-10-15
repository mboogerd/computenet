package civictech.concord.driver.kernel

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.SimulationController
import civictech.concord.driver.DeadLetter
import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.map
import civictech.concord.oracle.Fx.s
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.Test

/** Durable-host replication binding and crash/recovery composition (DUR-REPL-01). */
class DurReplicaBindingTest {

    private val budget = 5_000_000

    private fun replica(logical: String, interest: Value? = null): Map<String, Value> = buildMap {
        put("replica-of", s(logical))
        interest?.let { put("interest", it) }
    }

    @Test
    fun `dur replica refuses a catalog type outside the admitted set family`() {
        val refused = assertThrows<UnsupportedCatalogBinding> {
            KernelDriver(0L).spawn(
                KernelDriverDur.DUR_HOST,
                "bad",
                "effect-sink",
                replica("shared"),
            )
        }

        refused.message!! shouldContain "set-source"
        refused.message!! shouldContain "journal-set-source"
    }

    @Test
    fun `dur replica-of on a journal controller is refused, not ignored`() {
        val refused = assertThrows<UnsupportedCatalogBinding> {
            KernelDriver(0L).spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", replica("shared"))
        }

        refused.message!! shouldContain "journal-set-source"
    }

    @Test
    fun `dur replica admits only total interest`() {
        assertDoesNotThrow {
            KernelDriver(0L).spawn(
                KernelDriverDur.DUR_HOST,
                "total",
                "set-source",
                replica("shared-total", map("total" to Value.BoolVal(true))),
            )
        }

        val refused = assertThrows<UnsupportedCatalogBinding> {
            KernelDriver(0L).spawn(
                KernelDriverDur.DUR_HOST,
                "partial",
                "set-source",
                replica("shared-partial", map("empty" to Value.BoolVal(true))),
            )
        }
        refused.message!! shouldContain "Interest.Total"
    }

    @Test
    fun `journaled dur replica recovers and converges with its peer`() {
        val d = KernelDriver(0L).apply {
            createHost("h2")
            spawn(
                KernelDriverDur.DUR_HOST,
                "r1",
                "journal-set-source",
                replica("shared", map("total" to Value.BoolVal(true))),
            )
            spawn("h2", "r2", "set-source", replica("shared"))
            spawn(KernelDriverDur.DUR_HOST, "journal-only", "journal-set-source", emptyMap())
            spawn(KernelDriverDur.DUR_HOST, "journal-only-view", "set-view", emptyMap())
            spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap())
            connect("journal-only", "journal-only-view")
        }

        d.apply("r1", "add", s("x1"))
        d.apply("r2", "add", s("x2"))
        d.apply("journal-only", "add", s("from-journal"))
        d.quiesce(budget)
        d.readView("r1") shouldBe list(s("x1"), s("x2"))
        d.readView("r2") shouldBe list(s("x1"), s("x2"))

        d.despawn("ctl")
        d.quiesce(budget)
        d.apply("r2", "add", s("x3"))
        d.apply("r1", "add", s("x4"))
        d.quiesce(budget)

        val expected = list(s("x1"), s("x2"), s("x3"), s("x4"))
        d.readView("r2") shouldBe expected
        d.readView("r1") shouldBe expected
        // The replica can recover x1/x2 through either idempotent route. This
        // non-replicated arm makes the journal route independently observable.
        d.readView("journal-only-view") shouldBe list(s("from-journal"))
        d.deadLetters() shouldBe emptyList()
    }

    @Test
    fun `crash retires the discarded replica before publishing its replacement`() {
        val controller = SimulationController(0L)
        val registry = LocationRegistry()
        val replicaRef = CellRef(UUID.randomUUID(), 0L)
        val deadLetters = mutableListOf<DeadLetter>()
        val d = KernelDriverDur(
            scheduler = { controller.scheduler() },
            registryOf = { registry },
            drainController = { controller.runToIdle() },
            replicaRefOf = { replicaRef },
            onDeadLetter = deadLetters::add,
        )
        d.spawn("r1", "journal-set-source", replica("shared"))
        d.spawn("ctl", "journal", emptyMap())
        val discardedHost = requireNotNull(registry.locate(replicaRef))

        d.despawn("ctl")

        // Address the host object that the crash discarded, bypassing the registry
        // that now routes this stable ref to its replacement. It must reject the
        // delivery: accepting it would prove the departed replica is still live.
        @Suppress("UNCHECKED_CAST")
        val staleReplica = HostedCellProxy.create(replicaRef, discardedHost, SetApi::class.java) as SetApi<Any?>
        staleReplica.inlet.call.add("ghost")
        controller.runToIdle()

        deadLetters.map { it.reason } shouldBe listOf("unknown cell $replicaRef")
    }
}
