package civictech.bench.macro

import civictech.bench.macro.mesh.MeshRefs
import civictech.bench.macro.mesh.SetOpsProxy
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.durability.Journal
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.replication.WriteAuthority
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One production-scheduler world for a single-host REAL benchmark point.
 *
 * Placement hints are deliberately ignored: a REAL point has already been
 * constrained to one host, while the same generated graph may still carry the
 * placement used by its SIM coordinate.
 */
class RealWorld(journal: Journal?) : AutoCloseable {
    val registry = LocationRegistry()
    val scheduler = InstrumentedScheduler(VirtualThreadScheduler("bench-real"))
    val host = ManagedHost(scheduler = scheduler, registry = registry, journal = journal)

    private val capturedDeadLetters = CopyOnWriteArrayList<DeadLetter>()
    private val refs = linkedMapOf<String, CellRef>()
    private var applied = false

    /** A fresh snapshot of the live dead-letter capture. */
    val deadLetters: List<DeadLetter> get() = capturedDeadLetters.toList()

    init {
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        capturedDeadLetters += value
                    }
                },
                PortRef.generate(),
            ),
        )
    }

    /** Apply the primitive spawn/connect steps of [spec] to this one host. */
    fun apply(spec: GraphSpec): Map<String, CellRef> {
        check(!applied) { "RealWorld.apply may only be called once" }
        applied = true

        for (step in spec.lowered()) {
            when (step) {
                is SpawnStep -> spawn(step)
                is ConnectStep -> connect(step)
                else -> throw IllegalArgumentException("unsupported graph step ${step.javaClass.simpleName}")
            }
        }
        return refs.toMap()
    }

    /** Routed write surface for a source created by [apply]. */
    fun sourceInlet(handle: String): SetOps<Long> {
        val ref = refs[handle] ?: throw IllegalArgumentException("unknown applied handle '$handle'")
        return (HostedCellProxy.create(ref, registry, SetOpsProxy::class.java) as SetOpsProxy)
            .inlet.call
    }

    fun awaitIdle(millis: Long): Boolean = scheduler.awaitIdle(millis)

    override fun close() {
        scheduler.shutdown()
    }

    private fun spawn(step: SpawnStep) {
        validateSpawn(step)
        require(step.handle !in refs) { "duplicate spawn handle '${step.handle}'" }
        val expected = MeshRefs.ref(step.handle, 0, 1)
        val cell = step.factory.create(expected)
        require(cell.ref == expected) {
            "spawn '${step.handle}' factory returned ref ${cell.ref}, expected $expected"
        }
        refs[step.handle] = host.managementInlet.call.spawn(cell)
    }

    private fun connect(step: ConnectStep) {
        val from = refs[step.from]
            ?: throw IllegalArgumentException("connect names unknown source handle '${step.from}'")
        val to = refs[step.to]
            ?: throw IllegalArgumentException("connect names unknown target handle '${step.to}'")
        val result = host.managementInlet.call.connect(
            from,
            step.outlet,
            to,
            step.inlet,
            step.options,
        )
        check(result !is LinkResult.Rejected) {
            "link ${step.from}.${step.outlet} → ${step.to}.${step.inlet} rejected: " +
                (result as LinkResult.Rejected).reason
        }
    }

    private fun validateSpawn(step: SpawnStep) {
        when {
            step.family != null -> refuseSpawn(step, "declares family")
            step.inputs.isNotEmpty() -> refuseSpawn(step, "declares inputs")
            step.authority != WriteAuthority.Open -> refuseSpawn(step, "declares non-Open authority")
            step.replicated -> refuseSpawn(step, "declares replicated=true; replication is a mesh switch")
            step.journalId != null -> refuseSpawn(step, "declares journalId")
            step.shadow -> refuseSpawn(step, "declares shadow=true")
        }
    }

    private fun refuseSpawn(step: SpawnStep, reason: String): Nothing =
        throw IllegalArgumentException("spawn '${step.handle}' $reason")
}
