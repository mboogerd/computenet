package civictech.bench.graphalytics

import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import java.nio.file.Path

/**
 * The load-mode vocabulary used by the LCC benchmark.
 *
 * Durability is selected by constructing the [Journal] supplied to [GraRun]; it is
 * deliberately not a host flag, environment variable, or system property.
 */
object LoadModes {
    const val VOLATILE = "volatile"
    const val WAL_BOUND = "wal-bound"
    const val BATCHED_8 = "batched-8"
    const val BATCHED_INGRESS_64 = "batched-ingress-64"

    val names: List<String> = listOf(VOLATILE, WAL_BOUND, BATCHED_8, BATCHED_INGRESS_64)

    /** Resolve a CLI/JMH mode name to its journal instance and ingress batch size. */
    fun resolve(name: String, dir: Path): Pair<Journal?, Int> = when (name) {
        VOLATILE -> null to 1
        WAL_BOUND -> FileJournal(dir.resolve("wal-bound.journal").toFile()) to 1
        BATCHED_8 -> BatchedFileJournal(
            dir.resolve("batched-8.journal").toFile(),
            syncEvery = 8,
        ) to 1
        BATCHED_INGRESS_64 -> null to 64
        else -> throw IllegalArgumentException(
            "Unknown Graphalytics load mode '$name'; expected one of ${names.joinToString()}",
        )
    }
}
