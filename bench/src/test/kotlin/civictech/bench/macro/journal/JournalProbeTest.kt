package civictech.bench.macro.journal

import civictech.bench.Drive
import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.macro.CountingJournal
import civictech.bench.macro.PointBudget
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.SimulationController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Fast semantic pins for the journal probe and REAL rig (`[BEN2-37]`,
 * BS-14/BS-15, 9f7x5-D6..D9/D16).
 */
class JournalProbeTest {

    @TempDir
    lateinit var temporaryRoot: File

    @Test
    fun `SIM probe at C=100 b=10 pairs the decorator count with the file, recovers the sink fold and shrinks the file on checkpoint`() {
        val at = JournalPlan.coordinate(100, 10)

        val measured = JournalProbe.run(at, temporaryRoot.resolve("sim"))
        val outcome = measured.outcome.shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.subject shouldBe CostSubject.JOURNAL
        outcome.at shouldBe at
        measured.appended shouldBe measured.records
        (measured.records!! >= outcome.counts.journalRecords) shouldBe true
        outcome.counts.journalRecords shouldBe 100L
        measured.bytesPerRecord!! shouldBeGreaterThan 0.0
        measured.secondAttemptFileBytes shouldBe measured.fileBytes
        measured.retainedBytes!! shouldBeGreaterThan 0L
        measured.checkpointBytes!! shouldBeLessThan measured.fileBytes!!
        measured.recordsAfterCheckpoint!! shouldBeLessThan measured.records!!
    }

    @Test
    fun `a recovery that restores nothing is Failed, not Ok`() {
        val at = JournalPlan.coordinate(100, 10)

        val measured = JournalProbe.run(
            at = at,
            journalDir = temporaryRoot.resolve("empty-recovery"),
            recoveryJournal = { InMemoryJournal() },
        )
        val outcome = measured.outcome.shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.subject shouldBe CostSubject.JOURNAL
        outcome.cause shouldContain "recovery restored no journal frames"
        measured.stage shouldBe "recover"
        measured.replayCompleted shouldBe true
        measured.recoverCompleted shouldBe false
    }

    @Test
    fun `a recovery that replays only part of the journal is Failed with a fold mismatch`() {
        val at = JournalPlan.coordinate(100, 10)

        val outcome = JournalProbe.run(
            at = at,
            journalDir = temporaryRoot.resolve("partial-recovery"),
            determinismCheck = false,
            recoveryJournal = { file ->
                val records = FileJournal(file).replay()
                InMemoryJournal().apply { records.take(records.size / 2).forEach(::append) }
            },
        ).outcome.shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.subject shouldBe CostSubject.JOURNAL
        outcome.cause shouldContain "recovery fold mismatch: 1 of 1 sinks differ"
    }

    @Test
    fun `recovery OutOfMemoryError is Unschedulable with its stage and heap limit`() {
        val at = JournalPlan.coordinate(100, 10)

        val outcome = JournalProbe.run(
            at = at,
            journalDir = temporaryRoot.resolve("oom-recovery"),
            determinismCheck = false,
            recoveryJournal = { throw OutOfMemoryError("simulated") },
        ).outcome.shouldBeInstanceOf<PointOutcome.Unschedulable>()

        outcome.subject shouldBe CostSubject.JOURNAL
        outcome.reason shouldBe "recover: java.lang.OutOfMemoryError: simulated"
        outcome.observedLimit shouldContain "maxHeap="
    }

    @Test
    fun `a non-Ok SweepRunner outcome is re-subjected to JOURNAL with no figures`() {
        val at = JournalPlan.coordinate(100, 10)

        val measured = JournalProbe.run(
            at = at,
            journalDir = temporaryRoot.resolve("non-convergent"),
            budget = PointBudget(simSteps = 0),
            determinismCheck = false,
        )

        measured.outcome shouldBe PointOutcome.NonConvergent(
            at = at,
            subject = CostSubject.JOURNAL,
            budget = "simSteps=0",
        )
        measured.fileBytes shouldBe null
        measured.records shouldBe null
        measured.retainedBytes shouldBe null
        measured.checkpointBytes shouldBe null
    }

    @Test
    fun `REAL rig drives C=100 through a FileJournal, recovers the fold on a fresh host and checkpoints`() {
        val at = JournalPlan.coordinate(100, 10)
        val file = temporaryRoot.resolve("real/host.journal")
        val counting = CountingJournal(FileJournal(file))

        JournalRealRig(at, counting).use { rig ->
            rig.applyGraph()
            rig.drive() shouldBeGreaterThan 0L
            counting.appends.get() shouldBe FileJournal(file).replay().size.toLong()

            val originalFold = rig.sinkFold()
            originalFold.keys shouldBe rig.generated.sinks.toSet()
            // A vacuous (empty) fold would make the recovery equality below prove nothing.
            originalFold.values.sumOf { it.values.sum() } shouldBeGreaterThan 0
            rig.recoverInto(file).use { recovered ->
                recovered.sinkFold() shouldBe originalFold
            }

            val beforeCheckpoint = file.length()
            rig.checkpoint(counting)
            file.length() shouldBeLessThan beforeCheckpoint
        }
    }

    @Test
    fun `REAL rig accepts an InMemoryJournal and no journal`() {
        val at = JournalPlan.coordinate(100, 10)
        val memory = InMemoryJournal()

        JournalRealRig(at, memory).use { rig ->
            rig.applyGraph()
            rig.drive() shouldBeGreaterThan 0L
        }
        memory.replay().size shouldBe 100

        JournalRealRig(at, null).use { rig ->
            rig.applyGraph()
            rig.drive() shouldBeGreaterThan 0L
        }
    }

    @Test
    fun `probe refuses a non-durable, multi-host or REAL coordinate`() {
        val at = JournalPlan.coordinate(100, 10)

        shouldThrow<IllegalArgumentException> {
            JournalProbe.run(at.copy(durability = false), temporaryRoot.resolve("non-durable"))
        }.message shouldContain "durability=true"
        shouldThrow<IllegalArgumentException> {
            JournalProbe.run(at.copy(hostCount = 2), temporaryRoot.resolve("multi-host"))
        }.message shouldContain "hostCount=1"
        shouldThrow<IllegalArgumentException> {
            JournalProbe.run(at.copy(drive = Drive.REAL), temporaryRoot.resolve("real-drive"))
        }.message shouldContain "SIM drive"
    }

    @Test
    fun `journal plan is cardinality-major and records the fixed verdict rule before the numbers`() {
        val points = JournalPlan.points()

        points.size shouldBe 12
        points.map { it.cardinality to it.batchSize } shouldBe JournalPlan.CARDINALITIES.flatMap { cardinality ->
            JournalPlan.BATCH_SIZES.map { batchSize -> cardinality to batchSize }
        }
        points.all { it.hostCount == 1 && it.durability && it.drive == Drive.SIM } shouldBe true
        JournalPlan.omissions().size shouldBe 3
        JournalPlan.describe() shouldContain "D13 FIRES iff"
        JournalPlan.describe() shouldContain "D13 RETIRES iff"
        JournalPlan.describe() shouldContain "D13 is INCONCLUSIVE otherwise"
    }

    @Test
    fun `journal plan combines nine in-process points with three observed fork outcomes`() {
        JournalPlan.executedPoints().size shouldBe 12
        JournalPlan.inProcessPoints().size shouldBe 9
        JournalPlan.inProcessPoints().none { it.cardinality == 1_000_000L } shouldBe true
        JournalPlan.forkedObservations().map { it.batchSize } shouldBe JournalPlan.BATCH_SIZES
        JournalPlan.forkedObservations().all {
            it.kind == "Ok" && it.stage == "complete" && it.replayCompleted && it.recoverCompleted
        } shouldBe true
        JournalPlan.describe() shouldContain "Declared SIM combinations: 4 × 3 = 12"
        JournalPlan.describe() shouldContain "Executed SIM combinations: 4 × 3 = 12"
        JournalPlan.describe() shouldContain "3 C=1e6 points in bounded -Xmx8g forks"
    }

    @Test
    fun `ordinary probe defers C=1e6 to the recorded fork evidence before a mesh is built`() {
        val journalDir = temporaryRoot.resolve("in-process-deferral")

        val measured = JournalProbe.run(
            at = JournalPlan.coordinate(1_000_000, 1),
            journalDir = journalDir,
            determinismCheck = false,
        )

        measured.declaredOmission shouldBe true
        measured.stage shouldBe "omission"
        measured.outcome.shouldBeInstanceOf<PointOutcome.Unschedulable>().reason shouldContain "computenet-9f7x5.5"
        journalDir.exists() shouldBe false
    }

    @Test
    fun `forked probe bypasses the declared omission and reports its own drive stage`() {
        val journalDir = temporaryRoot.resolve("forked-stage")
        val stages = mutableListOf<String>()

        val measured = JournalProbe.runForked(
            at = JournalPlan.coordinate(1_000_000, 1),
            journalDir = journalDir,
            budget = PointBudget(simSteps = 0),
            determinismCheck = false,
            stageObserver = stages::add,
        )

        measured.declaredOmission shouldBe false
        measured.stage shouldBe "drive"
        measured.outcome.shouldBeInstanceOf<PointOutcome.NonConvergent>()
        stages shouldBe listOf("setup", "drive")
        journalDir.exists() shouldBe true
    }

    @Test
    fun `recovery drains use the point budget rather than the simulation default`() {
        val at = JournalPlan.coordinate(100, 10)
        val recoveryBudget = SimulationController.DEFAULT_BUDGET + 7
        val observedBudgets = mutableListOf<Int>()

        val measured = JournalProbe.run(
            at = at,
            journalDir = temporaryRoot.resolve("recovery-budget"),
            budget = PointBudget(simSteps = recoveryBudget),
            determinismCheck = false,
            recoveryJournal = ::FileJournal,
            recoveryDrain = { mesh, simSteps ->
                observedBudgets += simSteps
                mesh.runToIdle(simSteps)
            },
        )

        measured.outcome.shouldBeInstanceOf<PointOutcome.Ok>()
        observedBudgets shouldBe listOf(recoveryBudget, recoveryBudget)
    }
}
