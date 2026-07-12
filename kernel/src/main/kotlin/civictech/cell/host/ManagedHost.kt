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
import civictech.cell.attention.AttentionBand
import civictech.cell.attention.AttentionSupport
import civictech.cell.attention.NonSuspendable
import civictech.cell.attention.SuspensionNotice
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.durability.Journal
import civictech.cell.wire.WireCodec
import civictech.cell.data.Magnitude
import civictech.cell.data.Propagate
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*
import java.util.concurrent.CompletableFuture

private const val RECORD_FRAME: Byte = 1
private const val RECORD_CHECKPOINT: Byte = 2

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
    /** Attention → resources mapping (spec 34, M6.3); null = pre-M6 FIFO scheduling. */
    private val attention: AttentionPolicy? = null,
    /**
     * Max cells in this host's subtree — itself plus child hosts, recursively
     * (G-28, M8.1). A spawn anywhere in the subtree counts against every
     * ancestor's quota. Null = unlimited. Hosts count as cells of their
     * parent; this is a sandbox budget, not a resource model.
     */
    private val quota: Int? = null,
    /**
     * Write-ahead journal (spec 24 durability, G-25, M10.1): every accepted
     * data invocation is appended as a wire frame before staging — a journal
     * is a bridge to disk. Null = volatile host (default, pre-M10 behavior).
     * Recovery: rebuild the graph (spawn the same cells), then [recoverFrom].
     */
    private val journal: Journal? = null,
    /** Opt-in data intake bound; management invocations remain exempt. */
    private val intakeBound: IntakeBound? = null,
) : Host {

    /** Parent/child host relations (G-28): recorded when a host spawns a host. */
    internal var parentHost: ManagedHost? = null
        private set
    private val childHosts = mutableListOf<ManagedHost>()

    internal fun subtreeCellCount(): Int = cells.size + childHosts.sumOf { it.subtreeCellCount() }
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
    private var intakeState = IntakeState.OPEN

    private val lowWaterListeners = mutableListOf<() -> Unit>()

    val currentIntakeState: IntakeState get() = intakeState

    internal fun onIntakeAvailable(listener: () -> Unit) {
        val runNow = synchronized(dataLock) {
            if (intakeState == IntakeState.SATURATED) {
                lowWaterListeners += listener
                false
            } else true
        }
        if (runNow) listener()
    }

    internal fun closeIntake() {
        intakeState = IntakeState.CLOSED
    }

    internal fun openIntake() {
        intakeState = IntakeState.OPEN
    }

    private enum class State { RUNNING, DRAINING, DRAINED }

    private var state = State.RUNNING

    /** Snapshots captured by the last drain (spec 33 step 3; starts G-25). */
    private val snapshots = mutableMapOf<CellRef, Serializable>()

    /** Supervision (G-26): per-cell failure policies, spawn-time checkpoints, and suspended-cell parking. */
    private val policies = mutableMapOf<CellRef, SupervisionPolicy>()
    private val checkpoints = mutableMapOf<CellRef, Serializable>()
    private val suspendedCells = mutableMapOf<CellRef, MutableList<HostedPortInvocation>>()

    /**
     * Data plane (spec 34, M6.3): messages stage in per-cell FIFO queues; each
     * staged message submits one dispatcher task at data priority, and each
     * dispatch picks the next cell by attention band. Per-cell FIFO (a superset
     * of per-link FIFO, spec 31 rule 3) holds because band selection happens
     * BETWEEN cells, never within one — and the one-task-per-message shape
     * keeps drain's phase 2 (priority 30) behind every accepted message.
     */
    private val dataLock = Any()
    private val dataQueues = LinkedHashMap<CellRef, ArrayDeque<Pair<Long, HostedPortInvocation>>>()
    private var dataSequence = 0L
    private var dispatchStep = 0L
    private var strideCount = 0
    private val lastAttended = mutableMapOf<CellRef, Long>()

    /** Attention-parked traffic (spec 34 decision 2): parked, never dropped. */
    private val attentionParked = mutableMapOf<CellRef, MutableList<HostedPortInvocation>>()

    /**
     * Magnitude-band boost (spec 34, M17): per cell, the band its largest
     * staged [Magnitude] payload maps to under the policy's `magnitudeBands`.
     * Lifetime is the pending queue — cleared when it drains (or parks), so a
     * despawned cell's entry drains out through ordinary dead-letter dispatch.
     */
    private val magnitudeBoost = mutableMapOf<CellRef, AttentionBand>()

    /** Supervision state is per-host: on despawn/migrate it clears, and parked traffic dead-letters rather than vanishing. */
    private fun clearSupervision(cellRef: CellRef) {
        policies.remove(cellRef)
        checkpoints.remove(cellRef)
        suspendedCells.remove(cellRef)?.forEach {
            deadLetter(null, "cell $cellRef left the host while suspended", it)
        }
        synchronized(dataLock) { attentionParked.remove(cellRef) }?.forEach {
            deadLetter(null, "cell $cellRef left the host while attention-parked", it)
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
            // attention-parked traffic is accepted work: flush it before
            // deactivation, same guarantee as the ordinary queue (spec 33/34)
            val parked = synchronized(dataLock) {
                attentionParked.values.flatten().also { attentionParked.clear() }
            }
            parked.forEach { deliver(it) }
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

    /** Suppresses journaling while [recoverFrom] replays — replay must not re-journal itself. */
    @Volatile
    private var recovering = false

    open fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
        val isManagement = hostedInvocation.type == HostedPortInvocation.Type.PORT_MANAGEMENT
        if (!isManagement && intakeState == IntakeState.CLOSED) throw IntakeClosedException(ref)
        if (!isManagement) {
            synchronized(dataLock) {
                if (intakeState == IntakeState.SATURATED) {
                    if (intakeBound?.policy == SaturationPolicy.Coalesce && coalesce(hostedInvocation)) {
                        // Coalescing is acceptance, not loss: retain every original
                        // in the WAL so recovery may replay the equivalent sequence.
                        if (!recovering) journal?.append(journalFrame(hostedInvocation))
                        return
                    }
                    throw IntakeSaturatedException(ref)
                }
            }
        }
        // write-ahead (M10.1): the intake is the single funnel, so journal
        // order = acceptance order = per-cell FIFO on replay
        if (!recovering) journal?.append(journalFrame(hostedInvocation))
        // stage at SEND time (not dispatch time) so a backlog can form and band
        // selection has something to choose between; one dispatcher task per
        // message keeps message count <= task count (a task may find nothing)
        synchronized(dataLock) {
            stage(hostedInvocation)
            intakeBound?.let { bound ->
                if (!isManagement && dataQueuedCount() >= bound.highWater) {
                    intakeState = IntakeState.SATURATED
                }
            }
        }
        enqueue(20) { dispatchOne() }
    }

    private fun dataQueuedCount(): Int = dataQueues.values.sumOf { queue ->
        queue.count { (_, invocation) -> invocation.type != HostedPortInvocation.Type.PORT_MANAGEMENT }
    }

    /** Caller holds dataLock. Same source+wave slot preserves wave identity and source FIFO. */
    private fun coalesce(incoming: HostedPortInvocation): Boolean {
        val payload = incoming.invocation.args.singleOrNull() as? MergeablePayload ?: return false
        val context = incoming.invocation.context ?: return false
        val queue = dataQueues[incoming.cellRef] ?: return false
        val index = queue.indexOfLast { (_, old) ->
            old.portName == incoming.portName &&
                old.invocation.methodId == incoming.invocation.methodId &&
                old.invocation.context?.timestamp == context.timestamp &&
                old.invocation.context?.sourcePort == context.sourcePort &&
                old.invocation.args.singleOrNull() is MergeablePayload
        }
        if (index < 0) return false
        val entries = queue.toMutableList()
        val (sequence, old) = entries[index]
        val merged = (old.invocation.args.single() as MergeablePayload).mergeWith(payload)
        entries[index] = sequence to old.copy(invocation = old.invocation.copy(args = listOf(merged)))
        queue.clear()
        queue.addAll(entries)
        return true
    }

    /**
     * Replay this host's [journal] (M10.1): checkpoint records restore
     * `Stateful` state directly; invocation frames re-enter through the
     * ordinary intake (decode = the same path a network frame takes — a
     * journal is a bridge to disk). Call after the graph is rebuilt (cells
     * spawned) and before new traffic; replays are not re-journaled.
     */
    fun recoverFrom(journal: Journal) {
        recovering = true
        try {
            journal.replay().forEach { record ->
                when (record[0]) {
                    RECORD_FRAME -> enqueueHostedInvocation(
                        WireCodec.decode(record.copyOfRange(1, record.size))
                    )

                    RECORD_CHECKPOINT -> restoreCheckpoint(record.copyOfRange(1, record.size))
                    else -> error("unknown journal record type ${record[0]}")
                }
            }
        } finally {
            recovering = false
        }
    }

    private fun journalFrame(hostedInvocation: HostedPortInvocation): ByteArray =
        byteArrayOf(RECORD_FRAME) + WireCodec.encode(hostedInvocation)

    /**
     * Checkpoint (M10.2): capture every `Stateful` cell's snapshot as one
     * record and compact the journal down to it — replay after a checkpoint
     * is restore + tail. Runs on the management band so it can't interleave
     * with a dispatching cell.
     */
    fun checkpoint(journal: Journal) {
        enqueueAwaiting(0) {
            val state = HashMap<CellRef, Serializable>()
            cells.forEach { (cellRef, cell) -> if (cell is Stateful) state[cellRef] = cell.snapshot() }
            val blob = ByteArrayOutputStream()
                .also { ObjectOutputStream(it).use { out -> out.writeObject(state) } }
                .toByteArray()
            journal.reset(listOf(byteArrayOf(RECORD_CHECKPOINT) + blob))
        }
    }

    private fun restoreCheckpoint(blob: ByteArray) {
        @Suppress("UNCHECKED_CAST")
        val state = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as Map<CellRef, Serializable>
        state.forEach { (cellRef, snapshot) ->
            (cells[cellRef] as? Stateful)?.restore(snapshot)
                ?: deadLetter(null, "checkpoint state for $cellRef but no Stateful cell — graph rebuilt differently?")
        }
    }

    /** Callers hold [dataLock]: append to the target cell's FIFO (or its attention park). */
    private fun stage(hostedInvocation: HostedPortInvocation) {
        val cellRef = hostedInvocation.cellRef
        attentionParked[cellRef]?.let {
            it += hostedInvocation // parked cells accumulate in arrival order
            return // no boost: magnitude is urgency, interest owns park/resume
        }
        dataQueues.getOrPut(cellRef) { ArrayDeque() }.addLast(++dataSequence to hostedInvocation)
        attention?.magnitudeBands?.let { bands ->
            hostedInvocation.invocation.args.filterIsInstance<Magnitude>()
                .maxOfOrNull { it.size() }
                ?.let { magnitudeBoost.merge(cellRef, bands(it), ::maxOf) }
        }
    }

    private fun bandOf(cellRef: CellRef): AttentionBand = when {
        attention == null -> AttentionBand.NORMAL
        else -> cells[cellRef]?.let { AttentionSupport.of(it).band } ?: AttentionBand.NORMAL
    }

    /**
     * Run (at most) one staged message: highest band first, oldest head as
     * tiebreaker; the stride floor (spec 34 decision 2) bounds how long
     * lower-band work can be passed over; a cell at NONE past the policy
     * window parks instead of running (park, never drop).
     */
    private suspend fun dispatchOne() {
        var toPark: Set<CellRef>? = null
        var listeners: List<() -> Unit> = emptyList()
        val next: HostedPortInvocation? = synchronized(dataLock) {
            if (dataQueues.isEmpty()) return
            dispatchStep++
            // effective band = interest ⊔ staged urgency (spec 34, M17): the
            // boost is a data-region sub-priority — it can never lift a task
            // above the router/management bands, and the stride floor below
            // bounds what it can starve
            val bands = dataQueues.keys.associateWith {
                maxOf(bandOf(it), magnitudeBoost[it] ?: AttentionBand.NONE)
            }
            bands.forEach { (cellRef, band) ->
                if (band > AttentionBand.NONE) lastAttended[cellRef] = dispatchStep
            }
            val maxBand = bands.values.max()
            val lower = bands.filterValues { it < maxBand }.keys
            val stride = attention?.stride ?: Int.MAX_VALUE
            val pickFrom: Collection<CellRef> = if (strideCount >= stride && lower.isNotEmpty()) {
                strideCount = 0
                lower
            } else {
                if (lower.isNotEmpty()) strideCount++ else strideCount = 0
                bands.filterValues { it == maxBand }.keys
            }
            val pick = pickFrom.minByOrNull { dataQueues.getValue(it).first().first }!!
            val suspendAfter = attention?.suspendAfter
            val region = if (suspendAfter != null && bands.getValue(pick) == AttentionBand.NONE &&
                dispatchStep - (lastAttended[pick] ?: 0L) > suspendAfter
            ) suspensionRegionOf(pick) else null
            if (region != null) {
                toPark = region
                null
            } else {
                // runnable — or its region vetoed suspension (spec 34 decision 3):
                // a vetoed cell keeps running, it does not strand its queue
                val queue = dataQueues.getValue(pick)
                val head = queue.removeFirst().second
                if (queue.isEmpty()) {
                    dataQueues.remove(pick)
                    magnitudeBoost.remove(pick) // boost lifetime = the pending queue
                }
                intakeBound?.let { bound ->
                    if (intakeState == IntakeState.SATURATED && dataQueuedCount() <= bound.lowWater) {
                        intakeState = IntakeState.OPEN
                        listeners = lowWaterListeners.toList()
                        lowWaterListeners.clear()
                    }
                }
                head
            }
        }
        listeners.forEach { it() }
        toPark?.forEach { parkForAttention(it) }
        next?.let { deliver(it) }
    }

    /**
     * Session delta 3 (spec 34 decision 3): the unit of attention suspension
     * is the **glitch-free region** — the local downstream `GlitchFreeCell`
     * join(s) plus their transitive local upstream contributors, bounded by
     * further glitch-free cells (the frontier, spec 22). Parking one diamond
     * branch would stall waves at the join; parking the whole region cannot.
     * Returns null (veto) if any member is [NonSuspendable] or still attended.
     * A cell with no local downstream join is its own region (per-cell parking,
     * as before). Cross-host region members are invisible here by design —
     * remote branches remain the WAIT/DEGRADE fallback's job (GlitchFreeCell).
     */
    private fun suspensionRegionOf(cellRef: CellRef): Set<CellRef>? {
        val joins = mutableSetOf<CellRef>()
        bfs(cellRef, downstream = true) { ref, cell ->
            if (cell is GlitchFreeCell<*>) {
                joins += ref
                false // the join bounds the walk; regions don't chain through it
            } else true
        }
        if (joins.isEmpty()) return setOf(cellRef)
        val region = mutableSetOf<CellRef>()
        joins.forEach { join ->
            region += join
            bfs(join, downstream = false) { ref, cell ->
                if (cell is GlitchFreeCell<*>) false // another region's join: frontier
                else {
                    region += ref
                    true
                }
            }
        }
        val vetoed = region.any { ref ->
            val cell = cells[ref] ?: return@any false
            cell is NonSuspendable || AttentionSupport.of(cell).band > AttentionBand.NONE
        }
        return if (vetoed) null else region
    }

    /**
     * Local link-graph walk from [start] (exclusive). [visit] returns whether
     * to walk past the visited cell; only cells on this host are reachable.
     * Neighbors resolve by link **port object identity** (the same rule
     * AttentionSupport uses) — PortRefs don't reliably carry their cell.
     */
    private fun bfs(start: CellRef, downstream: Boolean, visit: (CellRef, Cell) -> Boolean) {
        val portOwner = HashMap<Port, CellRef>()
        cells.forEach { (ref, cell) ->
            val ports = PortRegistry.of(cell)
            ports.names().forEach { name -> ports[name]?.let { portOwner[it] = ref } }
        }
        val seen = mutableSetOf(start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            val current = cells[frontier.removeFirst()] ?: continue
            val ports = PortRegistry.of(current)
            ports.names().forEach { name ->
                val port = ports[name] as? Linked ?: return@forEach
                port.linking.links.forEach { link ->
                    val outbound = link.fromPort === port
                    if (outbound != downstream) return@forEach
                    val neighborPort = (if (outbound) link.toPort else link.fromPort) ?: return@forEach
                    val neighbor = portOwner[neighborPort] // absent: remote — fallback territory
                        ?.takeIf { it != current.ref && seen.add(it) } ?: return@forEach
                    val cell = cells[neighbor] ?: return@forEach
                    if (visit(neighbor, cell)) frontier.addLast(neighbor)
                }
            }
        }
    }

    private fun parkForAttention(cellRef: CellRef) {
        synchronized(dataLock) {
            val queue = dataQueues.remove(cellRef) ?: ArrayDeque()
            magnitudeBoost.remove(cellRef) // re-staged on unpark replay
            attentionParked[cellRef] = queue.map { it.second }.toMutableList()
        }
        cells[cellRef]?.let { notifyDownstream(it, SuspensionNotice.Suspended) }
    }

    private fun unparkForAttention(cellRef: CellRef) {
        val parked = synchronized(dataLock) {
            attentionParked.remove(cellRef)?.also { lastAttended[cellRef] = dispatchStep }
        } ?: return
        cells[cellRef]?.let { notifyDownstream(it, SuspensionNotice.Resumed) }
        parked.forEach { enqueueHostedInvocation(it) }
    }

    /** spec 34 decision 3: suspended/resumed notices travel downstream, with data. */
    private fun notifyDownstream(cell: Cell, notice: SuspensionNotice) {
        val ports = PortRegistry.of(cell)
        ports.names().forEach { name ->
            val port = ports[name] as? Linked ?: return@forEach
            port.linking.links.forEach { link ->
                if (link.fromPort === port) Protocols.sendDownstream(link, Protocols.Suspension, notice)
            }
        }
    }

    private suspend fun deliver(hostedInvocation: HostedPortInvocation) {
        val cellRef = hostedInvocation.cellRef
        // a SUSPEND-ed cell's traffic parks per-cell until resume(ref) replays it
        suspendedCells[cellRef]?.let {
            it += hostedInvocation
            return
        }
        val cell = cells[cellRef] ?: return deadLetter(
            null, "unknown cell $cellRef", hostedInvocation
        )
        val port = findPort(cell, hostedInvocation.portName) ?: return deadLetter(
            null, "unknown port '${hostedInvocation.portName}' on $cellRef", hostedInvocation
        )

        try {
            when (hostedInvocation.type) {
                HostedPortInvocation.Type.PORT_MANAGEMENT -> {
                    // the transport identity of the delivery is ambient for the
                    // handshake running inside (G-29 phase 1, M8.2)
                    val result = CurrentPeer.with(hostedInvocation.peer) {
                        hostedInvocation.invocation.invoke(port)
                    }
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

    /**
     * Returns a managed reference to the API of a hosted cell.
     */
    fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T? {
        if (!cells.containsKey(ref)) {
            // remote-backed proxy when the ref lives across the wire (M5.4, spec 41):
            // sends route through the bridge, parking/replaying like any registry send.
            // Local refs on *other* hosts still answer null — that host's lookup owns them.
            @Suppress("UNCHECKED_CAST")
            return registry?.takeIf { it.location(ref) is LocationRegistry.Remote }
                ?.let { HostedCellProxy.create(ref, it, clazz) } as T?
        }
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
                // quota walks every ancestor (G-28): a sandboxed subtree cannot
                // grow past any enclosing budget
                var scope: ManagedHost? = this@ManagedHost
                while (scope != null) {
                    scope.quota?.let { limit ->
                        check(scope!!.subtreeCellCount() < limit) {
                            "quota exceeded: host ${scope!!.ref} allows $limit cells in its subtree (G-28)"
                        }
                    }
                    scope = scope.parentHost
                }
                // color validation (spec 32, G-3): 🔵/🟣 markers must match the host; unmarked = 🟢 pure
                when (color) {
                    HostColor.BLOCKING -> require(cell !is SuspendingCell) {
                        "SuspendingCell ${cell.ref} cannot spawn on a BLOCKING host"
                    }
                    HostColor.SUSPENDING -> require(cell !is BlockingCell) {
                        "BlockingCell ${cell.ref} cannot spawn on a SUSPENDING host"
                    }
                }
                if (cell is ManagedHost) {
                    // hosts hosting hosts (31): record the relation for quota
                    // walking and shutdown cascade
                    childHosts += cell
                    cell.parentHost = this@ManagedHost
                }
                cells[cell.ref] = cell
                cell.onActivate(ctx)
                // spawn-time checkpoint: what a RESTART supervision restores (G-26)
                if (cell is Stateful) checkpoints[cell.ref] = cell.snapshot()
                if (attention != null) {
                    // renewed interest resumes a parked cell (spec 34): the listener
                    // may fire on any thread, so hop through the management band
                    AttentionSupport.of(cell).onBandChange { band ->
                        if (band > AttentionBand.NONE) enqueue(0) { unparkForAttention(cell.ref) }
                    }
                    // scheduling-step clock for time-aware aggregators (decay);
                    // racy read is fine — attention is advisory metadata (34)
                    AttentionSupport.of(cell).ticks = { dispatchStep }
                }
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

            override fun drainHost() {
                // shutdown cascade (G-28, M8.1): children drain first — a child
                // must not outlive (or keep accepting after) its parent
                childHosts.forEach { it.managementInlet.call.drainHost() }
                beginDrain()
            }

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
                val result = (outlet as LinkTo<Any>).linkTo(inlet as LinkFrom<Any>)
                if (result is LinkResult.Connected) {
                    val edge = TopologyLink(
                        result.link.id,
                        result.link.from.copy(cell = from),
                        result.link.to.copy(cell = to),
                    )
                    registry?.link(edge)
                    result.link.onUnlink { registry?.unlink(it.id) }
                }
                return result
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
            if (intakeState == IntakeState.CLOSED) throw IntakeClosedException(ref)
            val invocation = Invocation.of(method, args).withTarget(internalHostRoutingApi)
            enqueue(10) { invocation.invoke() }
            null
        })
    }

    private fun findPort(cell: Cell, name: String): Port? = PortRegistry.of(cell)[name]
}
