package civictech.cell.observe

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.proxy.InvocationSink
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * computenet-t6b.3.1.1: [readRouted] answers the same refusal taxonomy
 * [ManagedHost.readState] already does internally, generalized to a caller
 * that only has a [LocationRegistry] plus a [CellRef]. Scaffolding follows
 * `BoundedReadProvenanceTest` (kernel/src/test/kotlin/civictech/cell/host/BoundedReadProvenanceTest.kt).
 */
class RoutedReadTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

    private fun setCell(n: Int): SetCell<String> {
        val cell = SetCell<String>()
        repeat(n) { cell.inlet.call.add("k$it") }
        return cell.also { host.managementInlet.call.spawn(it) }
    }

    // --------------------------------------------------------------- BS-01

    @Test
    fun `a local cell answers identically through the routed entry and through readState directly`() {
        val cell = setCell(100)
        val request = StateRead(limit = 10)

        val direct = host.readState(cell.ref, request)
        controller.runToIdle()
        val directResult = direct.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val routed = readRouted(registry, cell.ref, request)
        controller.runToIdle()
        val routedResult = routed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        routedResult.shouldBeInstanceOf<StateReadResult.Page>()
        directResult.shouldBeInstanceOf<StateReadResult.Page>()
        val routedPage = (routedResult as StateReadResult.Page).page
        val directPage = (directResult as StateReadResult.Page).page

        routedPage.entries shouldBe directPage.entries
        routedPage.frontier shouldBe directPage.frontier
        routedPage.provenance shouldBe directPage.provenance
        routedPage.caveats shouldBe directPage.caveats
        routedPage.exclusivesElided shouldBe directPage.exclusivesElided

        // `next` is deliberately not compared by equality: Cursor's token is
        // "opaque, cell-minted... the kernel never interprets one"
        // (BoundedRead.kt's own KDoc on Cursor), and SetCell's SetWalk token
        // has no equals/hashCode override, so two independently-opened walks
        // over the identical quiescent state are never `==` even though they
        // resume to the identical remainder — a pre-existing property of this
        // cell family's cursor, not something introduced by routing. Both
        // pages are non-terminal (limit 10 of 100 entries), and each cursor
        // is proven equivalent by what it resumes to: both drain the walk to
        // the same 90 remaining entries.
        routedPage.next.shouldNotBeNull()
        directPage.next.shouldNotBeNull()

        val routedRest = readRouted(registry, cell.ref, StateRead(cursor = routedPage.next, limit = 100))
        controller.runToIdle()
        val routedRestPage = (routedRest.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) as StateReadResult.Page).page

        val directRest = host.readState(cell.ref, StateRead(cursor = directPage.next, limit = 100))
        controller.runToIdle()
        val directRestPage = (directRest.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) as StateReadResult.Page).page

        routedRestPage.entries shouldBe directRestPage.entries
        routedRestPage.next shouldBe null
        directRestPage.next shouldBe null
        (routedPage.entries + routedRestPage.entries).size shouldBe 100
    }

    // --------------------------------------------------------------- BS-02

    @Test
    fun `a remote ref answers MIGRATING through the routed entry`() {
        val remoteRef = CellRef(UUID.randomUUID())
        val sink = InvocationSink { }
        registry.publish(remoteRef, sink)

        val result = readRouted(registry, remoteRef, StateRead())
        result.isDone.shouldBeTrue()
        result.isCompletedExceptionally.shouldBeFalse()
        result.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.MIGRATING)
    }

    @Test
    fun `a ref held for a migration flip answers MIGRATING through the routed entry, and never reaches the cell`() {
        val cell = CountingBoundedCell().also { host.managementInlet.call.spawn(it) }
        registry.hold(cell.ref)

        val result = readRouted(registry, cell.ref, StateRead())
        // decided on the caller's thread: no task was ever queued
        result.isDone.shouldBeTrue()
        result.isCompletedExceptionally.shouldBeFalse()
        result.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.MIGRATING)

        controller.step().shouldBeFalse()
        cell.reads.get() shouldBe 0

        // released, it reads normally again — the refusal tracked the flip
        // window, not the cell
        registry.release(cell.ref)
        val released = readRouted(registry, cell.ref, StateRead())
        controller.runToIdle()
        released.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeInstanceOf<StateReadResult.Page>()
        cell.reads.get() shouldBe 1
    }

    /**
     * The case the explicit holds-first pre-check in [readRouted] exists for,
     * and the only one that distinguishes it from plain delegation: a ref held
     * for a flip that the registry no longer places *anywhere*. `ManagedHost.migrate`
     * unpublishes each moving ref before the target host spawns and republishes
     * it, so between those two points `location(ref)` is null while the flip
     * hold is still in force. Without the pre-check this falls to the
     * unplaced arm and answers NOT_HOSTED — "nowhere at all" — where KRD-03
     * requires MIGRATING for a ref held for a migration flip. Delegation
     * cannot cover it: there is no host to delegate to.
     */
    @Test
    fun `a ref held for a flip and no longer placed anywhere answers MIGRATING, not NOT_HOSTED`() {
        val cell = CountingBoundedCell().also { host.managementInlet.call.spawn(it) }
        registry.hold(cell.ref)
        registry.unpublish(cell.ref)
        registry.location(cell.ref) shouldBe null

        val result = readRouted(registry, cell.ref, StateRead())
        result.isDone.shouldBeTrue()
        result.isCompletedExceptionally.shouldBeFalse()
        result.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.MIGRATING)
        cell.reads.get() shouldBe 0
    }

    @Test
    fun `an unknown ref answers NOT_HOSTED through the routed entry, never null, an empty page, or an exception`() {
        val unknown = CellRef(UUID.randomUUID())

        val result = readRouted(registry, unknown, StateRead())
        result.isDone.shouldBeTrue()
        result.isCompletedExceptionally.shouldBeFalse()
        result.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.NOT_HOSTED)
    }

    // --------------------------------------------------------------- fixtures

    private class CountingBoundedCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, BoundedStateful {
        val reads = AtomicInteger()
        override fun readBounded(request: StateRead): StatePage {
            reads.incrementAndGet()
            return StatePage(entries = emptyList())
        }

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
