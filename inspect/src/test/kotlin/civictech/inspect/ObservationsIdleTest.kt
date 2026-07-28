package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The idle safety net: an observation nobody reads is released, because P6
 * makes a forgotten subscription *causal* — it keeps the upstream cone's
 * attention raised, not merely some memory allocated. Driven by an injected
 * clock, so nothing here waits five real minutes or asserts on timing.
 */
class ObservationsIdleTest {

    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val now = AtomicLong(1_000_000L)
    private val summaries = mutableListOf<CellRef>()

    private val observations = Observations(
        registry = registry,
        onChange = { ref, _ -> synchronized(summaries) { summaries += ref } },
        clock = now::get,
    )

    private fun set(): SetCell<String> = SetCell<String>().also { host.managementInlet.call.spawn(it) }

    private fun sinkOf(ref: CellRef): CellRef =
        registry.swapSet(ref).mapNotNull { it.to.cell }.single { it != ref }

    @Test
    fun `an observation nobody has read for five minutes is released`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true
        val sink = sinkOf(cell.ref)

        // one tick short of the deadline: still open
        now.addAndGet(Observations.IDLE_RELEASE_MS - 1)
        observations.sweep()
        observations.openRefs shouldBe setOf(cell.ref)

        now.addAndGet(1)
        observations.sweep()

        observations.openRefs shouldBe emptySet()
        awaitUntil("idle sink $sink despawned") { sink !in registry.localRefs() }
        registry.swapSet(sink) shouldBe emptySet()
    }

    @Test
    fun `a state read renews the deadline`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true

        now.addAndGet(Observations.IDLE_RELEASE_MS - 1)
        // the "matching GET state" the ticket names
        observations.touch(cell.ref)
        now.addAndGet(Observations.IDLE_RELEASE_MS - 1)
        observations.sweep()

        observations.openRefs shouldBe setOf(cell.ref)
    }

    @Test
    fun `re-observing renews the deadline too`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true

        now.addAndGet(Observations.IDLE_RELEASE_MS - 1)
        observations.start(cell.ref) shouldBe true
        now.addAndGet(Observations.IDLE_RELEASE_MS - 1)
        observations.sweep()

        observations.openRefs shouldBe setOf(cell.ref)
    }

    @Test
    fun `sweeping releases only the expired observations`() {
        val stale = set()
        val fresh = set()
        observations.start(stale.ref) shouldBe true

        now.addAndGet(Observations.IDLE_RELEASE_MS)
        observations.start(fresh.ref) shouldBe true
        observations.sweep()

        observations.openRefs shouldBe setOf(fresh.ref)
    }

    @Test
    fun `an outlet with no built-in fold is refused, and spawns nothing`() {
        val counter = civictech.cell.data.CounterCell().also { host.managementInlet.call.spawn(it) }

        observations.start(counter.ref) shouldBe false

        observations.openRefs shouldBe emptySet()
        // no orphan sink was left behind by the refused attempt
        registry.localRefs() shouldBe setOf(counter.ref)
    }

    @Test
    fun `outlet selection prefers the conventional name and refuses an ambiguous cell`() {
        Observations.outletName(listOf("outlet")) shouldBe "outlet"
        Observations.outletName(listOf("outlet", "spill")) shouldBe "outlet"
        Observations.outletName(listOf("only")) shouldBe "only"
        Observations.outletName(listOf("left", "right")) shouldBe null
        Observations.outletName(emptyList()) shouldBe null
    }

    @Test
    fun `the built-in fold is chosen from the cell's generated API, never guessed`() {
        // SetDelta and MapDelta producers each have a shipped fold
        (Observations.viewFor(SetCell::class.java) != null) shouldBe true
        (Observations.viewFor(civictech.cell.data.MapCell::class.java) != null) shouldBe true
        (Observations.viewFor(civictech.cell.data.op.GroupByCell::class.java) != null) shouldBe true
        (Observations.viewFor(civictech.cell.data.op.JoinSetCell::class.java) != null) shouldBe true
        // CounterDelta / ListDelta producers, and cells with no outlet at all,
        // have none — and are reported unobservable rather than mis-folded
        Observations.viewFor(civictech.cell.data.CounterCell::class.java) shouldBe null
        Observations.viewFor(civictech.cell.data.ListCell::class.java) shouldBe null
        Observations.viewFor(civictech.cell.observe.ObserveCell::class.java) shouldBe null
    }
}
