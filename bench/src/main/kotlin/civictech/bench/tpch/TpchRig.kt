package civictech.bench.tpch

import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.awaitDrained
import java.nio.file.Path

/** The TPC-H rig uses the repository-wide measurement regimes. */
typealias Drive = civictech.bench.Drive

/** Test seam for host construction and per-phase quiescence observation. */
class TpchRigHooks(
    val hostFactory: (HostScheduler, Journal?, Int) -> ManagedHost = { scheduler, journal, dispatchBatch ->
        ManagedHost(scheduler = scheduler, journal = journal, dispatchBatch = dispatchBatch)
    },
    val phaseQuiesced: (String) -> Unit = {},
)

/**
 * A two-drive host and phase clock for the refresh harness.
 *
 * SIM reports controller work, while REAL reports elapsed time after the scheduler's
 * positive quiescence fence. The phase start is captured before a caller submits its
 * load or refresh messages, so every returned figure belongs to exactly one phase.
 */
class TpchRig private constructor(
    val drive: Drive,
    val host: ManagedHost,
    private val scheduler: HostScheduler,
    private val simulation: SimulationController?,
    private val countingScheduler: PhaseCountingScheduler?,
    val schedulerLabel: String,
    private val hooks: TpchRigHooks,
) : AutoCloseable {

    private var phase: PhaseStart? = null

    fun beginPhase(): PhaseStart {
        check(phase == null) { "a TPC-H phase is already open" }
        return PhaseStart(
            startedNanos = System.nanoTime(),
            startedSteps = countingScheduler?.executedSteps ?: 0L,
        ).also { phase = it }
    }

    fun completePhase(label: String): Figure {
        require(label.isNotBlank()) { "phase label must not be blank" }
        val started = checkNotNull(phase) { "completePhase called without beginPhase" }
        phase = null
        val figure = when (drive) {
            Drive.SIM -> {
                val drained = simulation!!.runToIdle().toLong()
                val submitted = countingScheduler!!.executedSteps - started.startedSteps
                hooks.phaseQuiesced(label)
                Figure.Work(submitted + drained)
            }

            Drive.REAL -> {
                scheduler.awaitDrained("TPC-H $label phase")
                hooks.phaseQuiesced(label)
                Figure.Duration(System.nanoTime() - started.startedNanos)
            }
        }
        return figure
    }

    override fun close() {
        scheduler.shutdown()
        phase = null
    }

    class PhaseStart internal constructor(
        internal val startedNanos: Long,
        internal val startedSteps: Long,
    )

    private data class RigSchedulers(
        val scheduler: HostScheduler,
        val simulation: SimulationController?,
        val counting: PhaseCountingScheduler?,
        val label: String,
    )

    private class PhaseCountingScheduler(
        private val delegate: HostScheduler,
    ) : HostScheduler by delegate {
        var executedSteps: Long = 0L
            private set

        override fun submit(priority: Int, action: suspend () -> Unit) {
            delegate.submit(priority) {
                executedSteps++
                action()
            }
        }
    }

    companion object {
        fun open(
            drive: Drive,
            seed: Long? = null,
            journal: Journal? = null,
            dispatchBatch: Int = 1,
            hooks: TpchRigHooks = TpchRigHooks(),
        ): TpchRig {
            require(dispatchBatch >= 1) { "dispatchBatch must be >= 1, was $dispatchBatch" }
            if (journal != null) ensureTpchWireSerializers()
            val schedulers = when (drive) {
                Drive.SIM -> {
                    val simulation = SimulationController(seed)
                    val counting = PhaseCountingScheduler(simulation.scheduler())
                    RigSchedulers(counting, simulation, counting, "SimulationController")
                }

                Drive.REAL -> {
                    val scheduler = VirtualThreadScheduler("bench-tpch")
                    RigSchedulers(scheduler, null, null, "VirtualThreadScheduler")
                }
            }
            val host = hooks.hostFactory(schedulers.scheduler, journal, dispatchBatch)
            return TpchRig(
                drive = drive,
                host = host,
                scheduler = schedulers.scheduler,
                simulation = schedulers.simulation,
                countingScheduler = schedulers.counting,
                schedulerLabel = schedulers.label,
                hooks = hooks,
            )
        }
    }
}

/** Load-mode names and the same journal/ingress mapping used by the LCC rig. */
object LoadModes {
    const val VOLATILE = "volatile"
    const val WAL_BOUND = "wal-bound"
    const val BATCHED_8 = "batched-8"
    const val BATCHED_INGRESS_64 = "batched-ingress-64"

    val names: List<String> = listOf(VOLATILE, WAL_BOUND, BATCHED_8, BATCHED_INGRESS_64)

    fun resolve(name: String, dir: Path): Pair<Journal?, Int> = when (name) {
        VOLATILE -> null to 1
        WAL_BOUND -> FileJournal(dir.resolve("wal-bound.journal").toFile()) to 1
        BATCHED_8 -> BatchedFileJournal(
            dir.resolve("batched-8.journal").toFile(),
            syncEvery = 8,
        ) to 1
        BATCHED_INGRESS_64 -> null to 64
        else -> throw IllegalArgumentException(
            "Unknown TPC-H load mode '$name'; expected one of ${names.joinToString()}",
        )
    }
}

/** Convert the selected durability and ingress settings to the record vocabulary. */
fun loadModeLabel(journal: Journal?, dispatchBatch: Int): LoadModeLabel {
    require(dispatchBatch >= 1) { "dispatchBatch must be >= 1, was $dispatchBatch" }
    val durability = when (journal?.durability) {
        null -> "volatile"
        DurabilityClass.SYNCHRONOUS -> "WAL-bound"
        DurabilityClass.IN_MEMORY -> "in-memory"
        DurabilityClass.BATCHED -> {
            require(journal is BatchedFileJournal) {
                "BATCHED durability requires BatchedFileJournal so syncEvery can be recorded"
            }
            "batched(syncEvery=${journal.syncEvery})"
        }
    }
    val ingress = if (dispatchBatch == 1) "per-message" else "batched($dispatchBatch)"
    return LoadModeLabel(durability, ingress)
}
