package civictech.cell.host



import civictech.cell.port.*
import civictech.cell.BlockingCell
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellError
import civictech.cell.CellRef
import civictech.cell.ErrorReporting
import civictech.cell.Stateful
import civictech.cell.SuspendingCell
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import civictech.cell.data.Propagate
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * A Host that manages the lifecycle and connectivity of [Cell]s.
 *
 * Execution is delegated to a [HostScheduler]: threaded ([VirtualThreadScheduler], the default)
 * or deterministic ([civictech.cell.host.SimulationController]).
 */
open class ManagedHost(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    scheduler: HostScheduler? = null,
    private val registry: LocationRegistry? = null,
) : Host {
    override val managementInlet = registerPort("managementInlet", FanInlet.create<HostManagementApi>())
    override val routerInlet = registerPort("routerInlet", FanInlet.create<HostRoutingApi>())

    /** Failed/undeliverable invocations are published here instead of being dropped (G-26). */
    val deadLetterOutlet = registerPort("deadLetterOutlet", FanOutlet.create<Propagate<DeadLetter>>())

    private val scheduler: HostScheduler = scheduler ?: VirtualThreadScheduler("ManagedHost-${ref.id}")

    /** The concurrency color of this host's execution context (spec 32). */
    val color: HostColor get() = scheduler.color

    private val cells = mutableMapOf<CellRef, Cell>()
    private val ctx = object : CellContext {}

    /**
     * Closable intake (spec 33, G-5): while closed, data and router sends fail
     * fast with [IntakeClosedException] — the sender's re-resolution signal.
     * Management stays open (a closed host must remain administrable).
     */
    @Volatile
    private var intakeOpen = true

    internal fun closeIntake() {
        intakeOpen = false
    }

    internal fun openIntake() {
        intakeOpen = true
    }

    private enum class State { RUNNING, DRAINING, DRAINED }

    private var state = State.RUNNING

    /** Snapshots captured by the last drain (spec 33 step 3; starts G-25). */
    private val snapshots = mutableMapOf<CellRef, Serializable>()

    /** Supervision (G-26): per-cell failure policies, spawn-time checkpoints, and suspended-cell parking. */
    private val policies = mutableMapOf<CellRef, SupervisionPolicy>()
    private val checkpoints = mutableMapOf<CellRef, Serializable>()
    private val suspendedCells = mutableMapOf<CellRef, MutableList<HostedPortInvocation>>()

    /** Supervision state is per-host: on despawn/migrate it clears, and parked traffic dead-letters rather than vanishing. */
    private fun clearSupervision(cellRef: CellRef) {
        policies.remove(cellRef)
        checkpoints.remove(cellRef)
        suspendedCells.remove(cellRef)?.forEach {
            deadLetter(null, "cell $cellRef left the host while suspended", it)
        }
    }

    private fun roundTrip(state: Serializable): Serializable {
        val bytes = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(state) } }
            .toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as Serializable
    }

    /**
     * Two-phase drain: phase 1 (management, priority 0) closes the intake at
     * once; phase 2 runs at priority 30 — BELOW data's 20, so every accepted
     * invocation flushes first (no priority inversion) — then deactivates
     * cells and captures snapshots. G-16's ordering remainder: deactivation
     * provably follows the drained queue.
     */
    private fun beginDrain(andThen: () -> Unit = {}) {
        require(state == State.RUNNING) { "drain requires a RUNNING host (was $state)" }
        state = State.DRAINING
        closeIntake()
        enqueue(30) {
            snapshots.clear()
            cells.forEach { (cellRef, cell) ->
                cell.onDeactivate(ctx)
                if (cell is Stateful) snapshots[cellRef] = cell.snapshot()
            }
            state = State.DRAINED
            andThen()
        }
    }

    private fun deadLetter(cause: Throwable?, description: String, invocation: HostedPortInvocation? = null) {
        System.err.println("[ManagedHost ${ref.id}] dead letter: $description" + (cause?.let { " ($it)" } ?: ""))
        deadLetterOutlet.call.propagate(DeadLetter(ref, cause, description, invocation))
    }

    private fun enqueue(priority: Int, action: suspend () -> Any?) {
        scheduler.submit(priority) {
            try {
                action()
            } catch (e: Exception) {
                deadLetter(e, "invocation failed: $e")
            }
        }
    }

    private fun <T> enqueueAwaiting(priority: Int, action: suspend () -> T): T {
        val future = CompletableFuture<T>()
        scheduler.submit(priority) {
            try {
                future.complete(action())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return scheduler.await(future)
    }

    open fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
        if (!intakeOpen) throw IntakeClosedException(ref)
        enqueue(20) {
            val cellRef = hostedInvocation.cellRef
            // a SUSPEND-ed cell's traffic parks per-cell until resume(ref) replays it
            suspendedCells[cellRef]?.let {
                it += hostedInvocation
                return@enqueue null
            }
            val cell = cells[cellRef] ?: return@enqueue deadLetter(
                null, "unknown cell $cellRef", hostedInvocation
            )
            val port = findPort(cell, hostedInvocation.portName) ?: return@enqueue deadLetter(
                null, "unknown port '${hostedInvocation.portName}' on $cellRef", hostedInvocation
            )

            try {
                when (hostedInvocation.type) {
                    HostedPortInvocation.Type.PORT_MANAGEMENT -> {
                        val result = hostedInvocation.invocation.invoke(port)
                        if (result is LinkResult.Rejected) {
                            // proxy-initiated handshakes are fire-and-forget until the
                            // wire layer (M5); rejection is observable here only
                            deadLetter(null, "link rejected: ${result.reason}", hostedInvocation)
                        }
                    }

                    HostedPortInvocation.Type.PORT_API -> {
                        if (port is Use<*>) {
                            // suspend-aware: a 🟣 target's suspend fun may park this task (spec 32)
                            hostedInvocation.invocation.invokeSuspending(port.call)
                        }
                    }
                }
            } catch (e: Exception) {
                // every policy dead-letters — observability is not a policy (G-26)
                deadLetter(e, "invocation failed: $e", hostedInvocation)
                // a declared error outlet additionally receives the failure as data
                (cell as? ErrorReporting)?.errorOutlet?.call?.propagate(CellError(cellRef, e, hostedInvocation))
                when (policies[cellRef] ?: SupervisionPolicy.PROPAGATE) {
                    SupervisionPolicy.PROPAGATE -> {}
                    SupervisionPolicy.RESTART -> {
                        cell.onDeactivate(ctx)
                        cell.onActivate(ctx)
                        checkpoints[cellRef]?.let { (cell as Stateful).restore(roundTrip(it)) }
                    }
                    SupervisionPolicy.SUSPEND -> suspendedCells[cellRef] = mutableListOf()
                }
            }
        }
    }

    /**
     * Returns a managed reference to the API of a hosted cell.
     */
    fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T? {
        if (!cells.containsKey(ref)) return null
        @Suppress("UNCHECKED_CAST")
        // with a registry the proxy re-resolves, surviving relocation (spec 33)
        return (registry?.let { HostedCellProxy.create(ref, it, clazz) }
            ?: HostedCellProxy.create(ref, this, clazz)) as T
    }

    inline fun <reified T : Any> lookup(ref: CellRef): T? = lookup(ref, T::class.java)

    init {
        val internalApi = object : HostManagementApi {
            override fun spawn(cell: Cell): CellRef {
                require(!cells.containsKey(cell.ref)) { "Cell already spawned: ${cell.ref}" }
                // color validation (spec 32, G-3): 🔵/🟣 markers must match the host; unmarked = 🟢 pure
                when (color) {
                    HostColor.BLOCKING -> require(cell !is SuspendingCell) {
                        "SuspendingCell ${cell.ref} cannot spawn on a BLOCKING host"
                    }
                    HostColor.SUSPENDING -> require(cell !is BlockingCell) {
                        "BlockingCell ${cell.ref} cannot spawn on a SUSPENDING host"
                    }
                }
                cells[cell.ref] = cell
                cell.onActivate(ctx)
                // spawn-time checkpoint: what a RESTART supervision restores (G-26)
                if (cell is Stateful) checkpoints[cell.ref] = cell.snapshot()
                // location becomes visible only after activation, so replayed
                // parked invocations find served ports (spec 33 step 7)
                registry?.publish(cell.ref, this@ManagedHost)
                return cell.ref
            }

            override fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T? {
                return this@ManagedHost.lookup(ref, clazz)
            }

            override fun despawn(ref: CellRef) {
                val cell = cells.remove(ref) ?: throw IllegalArgumentException("Cell not found: $ref")
                registry?.unpublish(ref)
                clearSupervision(ref)
                cell.onDeactivate(ctx)
            }

            override fun supervise(ref: CellRef, policy: SupervisionPolicy) {
                require(cells.containsKey(ref)) { "Cell not found: $ref" }
                policies[ref] = policy
            }

            override fun resume(ref: CellRef) {
                val parked = suspendedCells.remove(ref)
                    ?: throw IllegalArgumentException("Cell not suspended: $ref")
                // re-enqueue at data priority: replay order = park order (sequence tiebreaker)
                parked.forEach { this@ManagedHost.enqueueHostedInvocation(it) }
            }

            override fun drainHost() = beginDrain()

            override fun resumeHost() {
                require(state == State.DRAINED) { "resume requires a DRAINED host (was $state)" }
                cells.values.forEach { it.onActivate(ctx) }
                openIntake()
                // republish only after the intake reopens: replay enqueues here (spec 33 step 7)
                cells.keys.forEach { registry?.publish(it, this@ManagedHost) }
                state = State.RUNNING
            }

            override fun migrate(to: Use<HostManagementApi>) = beginDrain {
                val moving = cells.toList()
                cells.clear()
                moving.forEach { (cellRef, cell) ->
                    registry?.unpublish(cellRef)
                    // supervision is per-host and does not migrate (31)
                    clearSupervision(cellRef)
                    // the serialization seam is exercised even in-process (G-25):
                    // restore from a round-tripped snapshot, not the live object
                    snapshots[cellRef]?.let { (cell as Stateful).restore(roundTrip(it)) }
                    // target spawn activates, publishes, and replays parked traffic
                    to.call.spawn(cell)
                }
                snapshots.clear()
            }

            override fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String): LinkResult {
                val fromCell = cells[from] ?: throw IllegalArgumentException("Source cell not found: $from")
                val toCell = cells[to] ?: throw IllegalArgumentException("Target cell not found: $to")

                val outlet = findPort(fromCell, outletName) as? LinkTo<*>
                    ?: throw IllegalArgumentException("Outlet not found or not linkable: $outletName on $from")
                val inlet = findPort(toCell, inletName) as? LinkFrom<*>
                    ?: throw IllegalArgumentException("Inlet not found or not linkable: $inletName on $to")

                @Suppress("UNCHECKED_CAST")
                return (outlet as LinkTo<Any>).linkTo(inlet as LinkFrom<Any>)
            }

            override fun connect(from: CellRef, outletName: String, to: Use<*>) {
                val fromCell = cells[from] ?: throw IllegalArgumentException("Source cell not found: $from")
                val outlet = findPort(fromCell, outletName) as? LinkTo<*>
                    ?: throw IllegalArgumentException("Outlet not found or not linkable: $outletName on $from")

                @Suppress("UNCHECKED_CAST")
                (outlet as LinkTo<Any>).linkTo(to as Use<Any>)
            }
        }

        val internalHostRoutingApi = object : HostRoutingApi {
            override fun route(target: CellRef, inletName: String, invocation: Invocation) {
                val toCell = cells[target] ?: throw IllegalArgumentException("Target cell not found: $target")
                val inlet = findPort(toCell, inletName) as? Use<*>
                    ?: throw IllegalArgumentException("Inlet not found or not usable: $inletName on $target")

                invocation.invoke(inlet.call)
            }
        }

        managementInlet.serve(Proxy.fromClass(HostManagementApi::class.java) { _, method, args ->
            val invocation = Invocation.of(method, args).withTarget(internalApi)
            if (method.name.startsWith("spawn")) {
                enqueueAwaiting(0) { internalApi.spawn(args!![0] as Cell) }
            } else if (method.name.startsWith("lookup")) {
                @Suppress("UNCHECKED_CAST")
                enqueueAwaiting(0) { internalApi.lookup(args!![0] as CellRef, args[1] as Class<Any>) }
            } else if (method.name.startsWith("connect")) {
                // surfaces the LinkResult (management calls may await, spec 31 rule 4)
                enqueueAwaiting(0) { invocation.invoke() }
            } else {
                enqueue(0) { invocation.invoke() }
                null
            }
        })

        routerInlet.serve(Proxy.fromClass(HostRoutingApi::class.java) { _, method, args ->
            if (!intakeOpen) throw IntakeClosedException(ref)
            val invocation = Invocation.of(method, args).withTarget(internalHostRoutingApi)
            enqueue(10) { invocation.invoke() }
            null
        })
    }

    private fun findPort(cell: Cell, name: String): Port? = PortRegistry.of(cell)[name]
}
