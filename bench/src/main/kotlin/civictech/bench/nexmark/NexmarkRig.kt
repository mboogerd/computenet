package civictech.bench.nexmark

import civictech.cell.durability.Journal
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.awaitDrained

/** Test seams for host construction and phase-order observation. */
class NexmarkRigHooks(
    val hostFactory: (HostScheduler, Journal?, Int) -> ManagedHost = { scheduler, journal, dispatchBatch ->
        ManagedHost(scheduler = scheduler, journal = journal, dispatchBatch = dispatchBatch)
    },
    val phaseQuiesced: (String) -> Unit = {},
)

/** A two-drive host and quiescence clock for the Nexmark stream protocol. */
class NexmarkRig private constructor(
    val drive: Drive,
    val host: ManagedHost,
    private val scheduler: HostScheduler,
    private val simulation: SimulationController?,
    private val countingScheduler: PhaseCountingScheduler?,
    val schedulerLabel: String,
    private val hooks: NexmarkRigHooks,
) : AutoCloseable {

    private var phase: PhaseStart? = null

    fun beginPhase(): PhaseStart {
        check(phase == null) { "a Nexmark phase is already open" }
        return PhaseStart(
            startedNanos = System.nanoTime(),
            startedSteps = countingScheduler?.executedSteps ?: 0L,
        ).also { phase = it }
    }

    fun completePhase(label: String): Figure {
        require(label.isNotBlank()) { "phase label must not be blank" }
        val started = checkNotNull(phase) { "completePhase called without beginPhase" }
        phase = null
        return when (drive) {
            Drive.SIM -> {
                val consumedBeforeDrain = countingScheduler!!.executedSteps - started.startedSteps
                val drained = simulation!!.runToIdle().toLong()
                hooks.phaseQuiesced(label)
                Figure.Work(
                    steps = consumedBeforeDrain + drained,
                    unit = WorkUnit.SCHEDULER_TASKS,
                )
            }

            Drive.REAL -> {
                scheduler.awaitDrained("Nexmark $label phase")
                hooks.phaseQuiesced(label)
                Figure.Duration(System.nanoTime() - started.startedNanos)
            }
        }
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
            seed: Long,
            journal: Journal? = null,
            dispatchBatch: Int = 1,
            hooks: NexmarkRigHooks = NexmarkRigHooks(),
        ): NexmarkRig {
            require(dispatchBatch >= 1) { "dispatchBatch must be >= 1, was $dispatchBatch" }
            if (journal != null) ensureNexmarkWireSerializers()
            val schedulers = when (drive) {
                Drive.SIM -> {
                    val simulation = SimulationController(seed)
                    val counting = PhaseCountingScheduler(simulation.scheduler())
                    RigSchedulers(counting, simulation, counting, SIM_SCHEDULER)
                }

                Drive.REAL -> {
                    val scheduler = VirtualThreadScheduler("bench-nexmark")
                    RigSchedulers(scheduler, null, null, "VirtualThreadScheduler")
                }
            }
            val host = hooks.hostFactory(schedulers.scheduler, journal, dispatchBatch)
            return NexmarkRig(
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
