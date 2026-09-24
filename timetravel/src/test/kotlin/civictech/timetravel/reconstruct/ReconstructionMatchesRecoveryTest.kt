package civictech.timetravel.reconstruct

import civictech.cell.CellRef
import civictech.cell.port.FanOutlet
import civictech.cell.port.OutletWaveState
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/**
 * TTD1 F4 (`computenet-6tm33.5`) BS-3 — **the epic's anchor property** (computenet-ocv §6): the same
 * journal, recovered LIVE by `ManagedHost.recoverFrom` on a fresh journaled host
 * ([DurableGraphFixture.recoverLive]) and reconstructed OFFLINE at its final position
 * ([Reconstructor.stateAt]), yields an equal `Stateful.snapshot()` for every cell (`[TTD1-20]`, D5);
 * plus the 6tm33-D9 epoch-parity pin.
 *
 * Seeds are the fixed list `1L..20L`. A failing seed is reported by name and never replaced
 * (AGENTS.md "Preserve deterministic simulation/generative tests"); every seed runs and all
 * failures are reported together.
 */
class ReconstructionMatchesRecoveryTest {

    private val seeds: List<Long> = (1L..20L).toList()

    private val universe = listOf("u0", "u1", "u2", "u3", "u4")

    private fun openTimeline(recording: DurableGraphFixture.Recording): Pair<JournalReading, RunTimeline> {
        val reading = JournalReader.open(JournalSource.InMemory(recording.journal, "j"))
        return reading to RunTimeline.of(reading).values.single()
    }

    /** Runs [body] for every seed, then fails once naming every seed that failed and why. */
    private fun forEverySeed(body: (Long) -> Unit) {
        val failures = seeds.mapNotNull { seed ->
            try {
                withClue("seed=$seed") { body(seed) }
                null
            } catch (e: AssertionError) {
                "seed=$seed: ${e.message}"
            }
        }
        withClue("failing seeds (of ${seeds.first()}..${seeds.last()}):\n${failures.joinToString("\n")}") {
            failures.shouldBeEmpty()
        }
    }

    /**
     * Property 1 — the crash shape (mirrors kernel `CrashRecoveryTest`'s "accepted but in flight"
     * burst): a seeded script of 6..12 adds over a five-element universe, each drained, then two
     * more adds through the same proxy with NO drain between or after them. The recording host is
     * then abandoned; only its journal is used. Every cell (source, union, view) reconstructs
     * offline to exactly the live-recovered snapshot, faithfully.
     */
    @Test
    fun `single source crash burst - offline reconstruction equals live recovery for every cell, seeds 1 to 20`() {
        forEverySeed { seed ->
            val random = Random(seed)
            val script = List(random.nextInt(6, 13)) { 0 to universe[random.nextInt(universe.size)] }
            val recording = DurableGraphFixture.record(seed = seed, sourceCount = 1, script = script)

            // The burst: accepted (journaled write-ahead, at the intake) but never dispatched.
            val before = recording.journal.replay().size
            recording.proxies[0].add(universe[random.nextInt(universe.size)])
            recording.proxies[0].add(universe[random.nextInt(universe.size)])
            withClue("the burst is journaled at accept, before any drain") {
                recording.journal.replay().size shouldBe before + 2
            }

            val (reading, timeline) = openTimeline(recording)
            val offline = Reconstructor(reading, timeline, GraphSpecSource(recording.spec))
                .stateAt(Position.Index(timeline.size - 1))
            val live = DurableGraphFixture.recoverLive(recording)

            offline.run shouldBe Fidelity.Faithful
            offline.details.shouldBeEmpty()
            offline.cells.keys shouldBe recording.refs.all
            live.keys shouldBe recording.refs.all
            for (ref in recording.refs.all) {
                withClue("cell $ref") {
                    val cell = offline.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
                    cell.snapshot shouldBe live.getValue(ref)
                    cell.fidelity shouldBe Fidelity.Faithful
                }
            }
        }
    }

    /**
     * Property 2 — epoch parity (6tm33-D9, as amended on computenet-6tm33.5): two sources and a
     * seeded interleaved script, recorded WITHOUT a checkpoint (so no outlet-wave record restores
     * the epochs — only the reconstruction host's own selector can). Every cell's offline snapshot
     * equals live recovery's (BS-3), and the OFFLINE host's rebuilt source outlets carry the live
     * run's journaled epochs.
     *
     * Why the epoch assertion and not snapshots alone: the fixture journals only the sources'
     * contextless proxy frames — local links never reach the journal — and `SetCell` tags derive
     * from the cell ref, not the outlet epoch, so snapshot equality holds with or without the D9
     * selector (measured on computenet-6tm33.3's review). The outlet epoch is where a missing
     * selector is observable.
     */
    @Test
    fun `two sources - offline equals live for every cell and the offline source outlets carry the live epochs, seeds 1 to 20`() {
        forEverySeed { seed ->
            val random = Random(seed)
            val script = List(random.nextInt(6, 13)) { random.nextInt(2) to universe[random.nextInt(universe.size)] }
            val recording = DurableGraphFixture.record(seed = seed, sourceCount = 2, script = script)
            val sources = recording.refs.sources

            // The live run's epochs are the ref-derived ones the design assumes.
            sources.forEachIndexed { i, source ->
                OutletWaveState.durable(PortRef.of(source, "outlet")).sourceId shouldBe recording.epochs[i]
            }
            recording.epochs[0] shouldNotBe recording.epochs[1]

            val (reading, timeline) = openTimeline(recording)
            val reconstructor = EpochCapturingReconstructor(reading, timeline, GraphSpecSource(recording.spec), sources)
            val offline = reconstructor.stateAt(Position.Index(timeline.size - 1))
            val live = DurableGraphFixture.recoverLive(recording)

            withClue("offline reconstruction host's source outlet epochs (6tm33-D9)") {
                reconstructor.offlineEpochs shouldBe recording.epochs
            }
            offline.run shouldBe Fidelity.Faithful
            offline.cells.keys shouldBe recording.refs.all
            for (ref in recording.refs.all) {
                withClue("cell $ref") {
                    val cell = offline.cells.getValue(ref).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
                    cell.snapshot shouldBe live.getValue(ref)
                }
            }
        }
    }

    /**
     * Reads each source outlet's emission epoch off the offline reconstruction host after replay,
     * through the `protected` [observe] step (6tm33-D12) — no change to [Reconstructor].
     */
    private class EpochCapturingReconstructor(
        reading: JournalReading,
        timeline: RunTimeline,
        graph: GraphSource,
        private val sources: List<CellRef>,
    ) : Reconstructor(reading, timeline, graph) {
        var offlineEpochs: List<UUID>? = null
            private set

        override fun observe(
            session: Session,
            requested: Position,
            n: Int,
            anchor: Int,
            recovery: RecoveryIncomplete?,
        ): Reconstruction {
            val byRef = session.build.cells.associateBy { it.ref }
            offlineEpochs = sources.map { source ->
                (PortRegistry.of(byRef.getValue(source))["outlet"] as FanOutlet<*>).waveState().sourceId
            }
            return super.observe(session, requested, n, anchor, recovery)
        }
    }
}
