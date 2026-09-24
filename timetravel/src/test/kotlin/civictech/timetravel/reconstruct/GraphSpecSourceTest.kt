package civictech.timetravel.reconstruct

import civictech.cell.CellRef
import civictech.cell.durability.DurabilityClass
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.SpawnStep
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.OutletWaveState
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * computenet-6tm33.3 (feature computenet-6tm33 D2, 6tm33-D9): [GraphSpecSource] hands back
 * every instance it spawns, [DiscardingJournal] keeps the kernel's KFX-12 epoch gate honest
 * for a journal-less reconstruction host, and [DurableGraphFixture] is the recorded-run input
 * every reconstruction test in this family builds from.
 */
class GraphSpecSourceTest {

    private fun freshHost(
        seed: Long,
        registry: LocationRegistry = LocationRegistry(),
        journalFor: ((CellRef) -> civictech.cell.durability.Journal?)? = null,
    ): Pair<SimulationController, ManagedHost> {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journalFor = journalFor)
        return controller to host
    }

    @Test
    fun `build hands back every spawned instance, matching the registry`() {
        val recording = DurableGraphFixture.record(seed = 101, sourceCount = 2, script = listOf(0 to "a"))
        val registry = LocationRegistry()
        val (controller, host) = freshHost(101, registry, journalFor = { DiscardingJournal })

        val build = GraphSpecSource(recording.spec).build(host)
        controller.runToIdle()

        val builtRefs = build.cells.map { it.ref }.toSet()
        builtRefs shouldBe registry.localRefs()
        builtRefs shouldBe recording.refs.all
        build.cells.forEach { cell -> registry.describe(cell.ref) shouldBe cell.javaClass }
    }

    @Test
    fun `build does not mutate the given GraphSpec`() {
        val recording = DurableGraphFixture.record(seed = 102, sourceCount = 2, script = listOf(0 to "a"))
        val stepsBefore = recording.spec.steps
        val loweredShapeBefore = recording.spec.lowered().map { shapeOf(it) }
        val (controller, host) = freshHost(102, journalFor = { DiscardingJournal })

        GraphSpecSource(recording.spec).build(host)
        controller.runToIdle()

        recording.spec.steps shouldBe stepsBefore
        recording.spec.lowered().map { shapeOf(it) } shouldBe loweredShapeBefore
    }

    /** A [civictech.cell.graph.GraphStep]'s replay-relevant data, factory excluded (it is code, not data). */
    private fun shapeOf(step: civictech.cell.graph.GraphStep): Any = when (step) {
        is SpawnStep -> Triple(step.handle, step.identity, step.parent)
        is ConnectStep -> step
        else -> step
    }

    @Test
    fun `journaled defaults to null and otherwise is the given predicate`() {
        val recording = DurableGraphFixture.record(seed = 103, sourceCount = 1, script = listOf(0 to "a"))
        val (defaultController, defaultHost) = freshHost(103, journalFor = { DiscardingJournal })
        val defaultBuild = GraphSpecSource(recording.spec).build(defaultHost)
        defaultController.runToIdle()
        defaultBuild.journaled.shouldBeNull()

        val predicate: (CellRef) -> Boolean = { it == recording.refs.union }
        val (predController, predHost) = freshHost(104, journalFor = { DiscardingJournal })
        val predBuild = GraphSpecSource(recording.spec, predicate).build(predHost)
        predController.runToIdle()
        predBuild.journaled.shouldNotBeNull()
        predBuild.journaled!!(recording.refs.union) shouldBe true
        predBuild.journaled!!(recording.refs.sources.first()) shouldBe false
    }

    @Test
    fun `DiscardingJournal retains nothing`() {
        DiscardingJournal.append(byteArrayOf(1, 2))
        DiscardingJournal.reset(listOf(byteArrayOf(3)))
        DiscardingJournal.replay().shouldBeEmpty()
        DiscardingJournal.durability shouldBe DurabilityClass.IN_MEMORY
    }

    /** [PortRegistry]-derived outlet lookup shared by the D9 gate below. */
    private fun sourceIdOf(cell: civictech.cell.Cell): java.util.UUID =
        (PortRegistry.of(cell)["outlet"] as FanOutlet<*>).waveState().sourceId

    @Test
    fun `6tm33-D9 a DiscardingJournal selector installs the ref-derived epoch a live run journaled`() {
        val recording = DurableGraphFixture.record(seed = 201, sourceCount = 2, script = listOf(0 to "a", 1 to "b"))

        val (durableController, durableHost) = freshHost(201, journalFor = { DiscardingJournal })
        val durableBuild = GraphSpecSource(recording.spec).build(durableHost)
        durableController.runToIdle()
        val durableByRef = durableBuild.cells.associateBy { it.ref }

        recording.refs.sources.forEachIndexed { i, ref ->
            val actual = sourceIdOf(durableByRef.getValue(ref))
            val expected = OutletWaveState.durable(PortRef.of(ref, "outlet")).sourceId
            actual shouldBe expected
            actual shouldBe recording.epochs[i]
        }

        // non-vacuity control: no journalFor at all -> KFX-12's gate never fires,
        // every outlet mints a fresh random epoch instead of the ref-derived one.
        val (volatileController, volatileHost) = freshHost(202, journalFor = null)
        val volatileBuild = GraphSpecSource(recording.spec).build(volatileHost)
        volatileController.runToIdle()
        val volatileByRef = volatileBuild.cells.associateBy { it.ref }

        val anyDiffers = recording.refs.sources.any { ref ->
            sourceIdOf(volatileByRef.getValue(ref)) != OutletWaveState.durable(PortRef.of(ref, "outlet")).sourceId
        }
        anyDiffers shouldBe true
    }

    @Test
    fun `DurableGraphFixture record shape - increasing indices, full snapshots, source frames unwaved`() {
        val recording = DurableGraphFixture.record(seed = 11, sourceCount = 1, script = listOf(0 to "a", 0 to "b", 0 to "c"))

        recording.steps.size shouldBe 3
        recording.steps.forEachIndexed { k, step ->
            (step.proxyIndex <= step.lastIndex) shouldBe true
            if (k < recording.steps.size - 1) {
                (step.lastIndex < recording.steps[k + 1].proxyIndex) shouldBe true
            }
        }
        recording.steps.last().liveSnapshots.keys shouldBe recording.refs.all

        // computenet-6tm33.3: the design text this clause was drafted from claims the journal
        // holds frames "for the union" too, with a non-null (waved) context. Falsified: a local
        // `outlet.linkTo(inlet)` connection (`LinkAdmission.connect`, kernel/.../LinkAdmission.kt)
        // wires the union's inlet as a direct in-process subscriber of the source's outlet — the
        // delivery never re-enters `ManagedHost.enqueueHostedInvocation` (the sole appender,
        // `journalSelector(hostedInvocation.cellRef)?.append(...)` at ManagedHost.kt:796/834), so
        // nothing is ever journaled for a cell that never receives its own hosted invocation.
        // Verified by construction: DurableGraphFixture.record's ManagedHost is given a
        // whole-host `journal` (a selector returning it for EVERY cellRef, union and view
        // included), yet a 3-add run against `sourceCount = 1` produces exactly 3 journal
        // records total, all attributed to the source — printed and inspected directly against
        // this fixture before writing this assertion. Pinning the observed shape instead: only
        // the source is journaled (proxy calls carry no MessageContext, so its frames are
        // unwaved); the union and view are never journaled at all, by construction, however many
        // sources there are — reconstruction's `recoverFrom` re-derives their state by replaying
        // the source frame back through the live graph, not by replaying an independent record
        // of their own.
        val records = JournalReader.open(JournalSource.InMemory(recording.journal, "j")).records.toList()
        val frames = records.filterIsInstance<FrameRecord>()
        frames.shouldNotBeEmpty()
        val sourceRef = recording.refs.sources.single()
        val sourceFrames = frames.filter { it.cellRef == sourceRef }
        val unionFrames = frames.filter { it.cellRef == recording.refs.union }
        val viewFrames = frames.filter { it.cellRef == recording.refs.view }
        sourceFrames.shouldNotBeEmpty()
        sourceFrames.forEach { it.context.shouldBeNull() }
        unionFrames.shouldBeEmpty()
        viewFrames.shouldBeEmpty()
        frames.size shouldBe sourceFrames.size
    }

    @Test
    fun `recoverLive matches the recording's own live snapshot and leaves the journal untouched (copy rule)`() {
        val recording = DurableGraphFixture.record(seed = 77, sourceCount = 1, script = listOf(0 to "a", 0 to "b", 0 to "c"))
        val sizeBefore = recording.journal.replay().size

        val recovered = DurableGraphFixture.recoverLive(recording)

        recording.journal.replay().size shouldBe sizeBefore
        val sourceRef = recording.refs.sources.single()
        recovered[sourceRef] shouldBe recording.steps.last().liveSnapshots.getValue(sourceRef)
    }
}
