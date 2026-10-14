package civictech.bench.macro

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.cost.PointOutcome
import civictech.cell.host.KeyedCells
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SimDeterminismTest {

    @Test
    fun twoAttemptsAtTheSameSeedAgree() {
        SweepRunner(determinismCheck = true).run(coordinate())
            .shouldBeInstanceOf<PointOutcome.Ok>()
    }

    @Test
    fun aPerturbedSecondAttemptIsNonDeterministic() {
        val runner = SweepRunner(
            determinismCheck = true,
            hook = PointHook { context, attempt ->
                if (attempt == 2) {
                    val mesh = context.shouldBeInstanceOf<PointContext.Sim>().mesh
                    mesh.world.edges.deliver("peer0<->peer1", byteArrayOf(1, 2, 3))
                }
            },
        )

        val outcome = runner.run(coordinate()).shouldBeInstanceOf<PointOutcome.NonDeterministic>()

        outcome.second.crossHostFrames shouldBe outcome.first.crossHostFrames + 1
        outcome.second.wireBytes shouldBe outcome.first.wireBytes + 3
    }

    @Test
    fun durableAttemptCountsJournalRecordsAgainstTheUndecoratedJournal(@TempDir journalDir: File) {
        val at = coordinate(durability = true)
        var recordsBeforeInjection = -1L
        val runner = SweepRunner(
            journalDir = journalDir,
            determinismCheck = true,
            hook = PointHook { _, attempt ->
                if (attempt == 1) {
                    recordsBeforeInjection = journalRecords(journalDir, attempt, at.hostCount)
                }
            },
        )

        val outcome = runner.run(at).shouldBeInstanceOf<PointOutcome.Ok>()
        val recordsAfter = journalRecords(journalDir, attempt = 1, hostCount = at.hostCount)
        val observedGrowth = recordsAfter - recordsBeforeInjection
        val observation = if (observedGrowth == 0L) {
            "observed 0 records after injection: this host does not journal plain spawned SetCell adds"
        } else {
            "observed $observedGrowth records after injection on a plain spawned SetCell graph"
        }

        assertEquals(at.cardinality, observedGrowth, observation)
        assertEquals(observedGrowth, outcome.counts.journalRecords, observation)
    }

    private fun journalRecords(root: File, attempt: Int, hostCount: Int): Long =
        (0 until hostCount).sumOf { peer ->
            KeyedCells.hostJournal(File(root, "attempt$attempt/peer$peer"))!!.replay().size.toLong()
        }

    private fun coordinate(durability: Boolean = false): Coordinate = Coordinate(
        shape = "CHAIN",
        shapeSeed = 7,
        cardinality = 20,
        batchSize = 5,
        hostCount = 2,
        fanOut = 1,
        replication = false,
        durability = durability,
        drive = Drive.SIM,
    )
}
