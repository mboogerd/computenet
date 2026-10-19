package civictech.demo.studio

import civictech.cell.CellRef
import civictech.cell.graph.*
import civictech.cell.durability.FileJournal
import civictech.cell.Propagate
import civictech.cell.Cell
import civictech.cell.link.LinkRole
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.membrane.TrafficLightApi
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/** Graph and node state have one lock; kernel callbacks only enqueue onto intake. */
class StudioRuntime internal constructor(val data: Path, private val sandbox: PythonSandbox,
                                         private val judge: StudioJudge = TypeSafeStudioJudge()) : AutoCloseable {
    constructor(data: Path) : this(data, PythonSandbox())
    private val lock = Any()
    private val scheduler = VirtualThreadScheduler("studio-host")
    private val journal = FileJournal(data.resolve("studio.journal").toFile())
    private val host = ManagedHost(registry = LocationRegistry(), scheduler = scheduler, journal = journal)
    // Shared topology/data journal also permits graph-owned promotion bookkeeping in part B.
    internal val context = ApplyContext(host, journals = mapOf("studio" to journal), topology = journal)
    private val coldRegistry = LocationRegistry()
    private val coldHost = ManagedHost(registry = coldRegistry, scheduler = scheduler)
    private val intake = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("studio-input").factory())
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val timers = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("studio-git").factory())
    private val processes = StudioProcesses()
    private val nodes = linkedMapOf<String, Entry>()
    private val edges = linkedMapOf<String, StudioEdge>()
    private val links = mutableSetOf<Pair<String, String>>()
    private val services = mutableMapOf<CellRef, CellRef>()
    private val profile = data.resolve("python.sb")
    internal val activeProcesses get() = processes.active.map { it.pid() }.toSet()
    private var changes = 0
    private var recovering = true
    internal var afterPromotion: () -> Unit = {} // crash-image boundary: committed swap, before application publish
    internal var recoveredInputs = 0
        private set
    private val listeners = mutableSetOf<(String) -> Unit>()
    private var closed = false

    /** Intake's working cache; changed() commits it to the authoritative cell before use. */
    private class Entry(var node: StudioNode, var state: NodeState, val ref: CellRef, val handle: String = node.id) {
        var liveCode: String? = null
        var shadow: Entry? = null
        var observer: StudioObserver? = null
        var fence = ProcessFence()
        var job: JobRecord? = null
        val acknowledge = mutableListOf<String>()
        val pending = linkedMapOf<String, JsonObject>()
        var busy = false
        var generating = false
        var task: Future<*>? = null
        var gitTask: Future<*>? = null
        var manualPending = false
        var version = 0L
        var pollBusy = false
        var firePending = false
        var poll: ScheduledFuture<*>? = null
    }

    init { synchronized(lock) {
        Files.createDirectories(data)
        atomicWrite(profile, PythonSandbox.PROFILE)
        // Nothing can launch work, poll git, or accept HTTP writes before this fence.
        context.recover(journal).awaitApplied()
        val metadataFile = data.resolve("graph.json")
        val metadata = if (Files.isRegularFile(metadataFile))
            json.decodeFromString<CanvasMetadata>(Files.readString(metadataFile)) else CanvasMetadata()
        // Unpromoted candidates are disposable; retain their code on the incumbent.
        context.live().spawns.values.filter { it.shadow }.forEach {
            GraphSpec(listOf(DespawnStep(it.handle))).apply(context)
        }
        context.handles.forEach { (handle, ref) ->
            if (!handle.endsWith("~gate")) {
                val checkpoint = checkpointOf(ref)
                val id = checkpoint.definition.id
                val node = checkpoint.definition.node(metadata.nodes[id])
                val e = Entry(node, checkpoint.state.copy(preview = null), ref, handle)
                e.liveCode = checkpoint.liveCode
                e.job = checkpoint.job
                e.pending.putAll(checkpoint.pending)
                nodes[id] = e
            }
        }
        val livePairs = context.live().links.values.map { it.from to it.to }.toSet()
        metadata.edges.filter { edge ->
            nodes[edge.from]?.ref to nodes[edge.to]?.let(::inputRef) in livePairs && edge.port in nodes[edge.to]!!.node.ports
        }.forEach { edges[it.id] = it; links += it.from to it.to }
        nodes.values.forEach { e ->
            if (e.job?.phase == "started" || e.state.status in setOf("running", "queued", "generating")) {
                e.job = e.job?.copy(phase = "interrupted")
                e.state = e.state.copy(status = "stale", error = "previous run interrupted")
                changed(e)
            }
            drain(e) // retain delivered inputs, but recovery never schedules external effects
            if (e.pending.isNotEmpty()) { e.pending.clear(); changed(e) }
        }
        recovering = false
        nodes.values.forEach(::attach)
        nodes.values.filter { it.node.kind in setOf("git", "beads") }.forEach { schedulePoll(it, 0) }
    } }

    private fun checkpointOf(ref: CellRef): StudioCheckpoint =
        json.decodeFromString(host.snapshotOf(ref).get(5, TimeUnit.SECONDS) as String)

    private fun gateHandle(e: Entry) = "${e.node.id}~gate"
    private fun inputHandle(e: Entry) = if (e.node.kind == "program") gateHandle(e) else e.handle
    private fun inputPort(e: Entry) = if (e.node.kind == "program") "dataInlet" else "inlet"
    private fun inputRef(e: Entry) = context.handles.getValue(inputHandle(e))
    private fun current(e: Entry) = nodes[e.node.id].let { it === e || it?.shadow === e }

    private fun api(e: Entry) = host.lookup(TypedRef<StudioCellApi>(e.ref))!!

    /** Runtime-local Observe links; services are rebuilt after journal recovery. */
    private fun attach(e: Entry) {
        val collector = StudioObserver {
            try { intake.execute { synchronized(lock) { if (!closed && current(e)) drain(e) } } }
            catch (_: RejectedExecutionException) { /* shutdown */ }
        }
        host.managementInlet.call.spawn(collector)
        check(host.managementInlet.call.connect(e.ref, "notifications", collector.ref, "inlet",
            LinkOptions(role = LinkRole.Observe, staged = true)) is LinkResult.Connected)
        services[e.ref] = collector.ref
    }

    private fun drain(e: Entry, allowRuns: Boolean = !recovering) {
        checkpointOf(e.ref).inbox.forEach { (key, input) ->
            if (recovering) recoveredInputs++
            e.acknowledge += key
            accept(e, input, allowRuns)
        }
    }

    private fun spawn(node: StudioNode): Entry {
        GraphSpec(listOf(SpawnStep(node.id, StudioFactory(json.encodeToString(node.definition())), journalId = "studio"))).apply(context)
        val e = Entry(node, NodeState(node.id), context.handles.getValue(node.id))
        if (node.kind == "program") {
            GraphSpec(listOf(SpawnStep(gateHandle(e), StudioGateFactory),
                ConnectStep(gateHandle(e), "dataOutlet", e.handle, "inlet", LinkOptions(staged = true)))).apply(context)
            gate(e).controlInlet.call.setGreen()
        }
        attach(e)
        return e
    }

    private fun nodeDir(id: String): Path = data.resolve("nodes").resolve(id)
    private fun entry(id: String) = nodes[id] ?: throw StudioException(404, "node not found")
    fun graph(): StudioGraph = synchronized(lock) { ensurePreviews(); snapshot() }
    private fun ensurePreviews() {
        if (!recovering && !closed) nodes.values.filter {
            it.node.kind == "program" && it.state.codeHash != null &&
                it.state.codeHash != it.state.approvedHash && it.shadow == null
        }.forEach(::ensureShadow)
    }
    fun state(id: String): NodeState = synchronized(lock) { entry(id).state }
    /** Drain accepted kernel deliveries, then the non-blocking callback queue (test barrier). */
    internal fun settleInputs() {
        host.quiescence().await(5_000)
        intake.submit {}.get(5, TimeUnit.SECONDS)
    }
    internal fun settlePoll(id: String) {
        val task = synchronized(lock) { entry(id).gitTask }
        task?.get(65, TimeUnit.SECONDS)
    }
    private fun snapshot() = StudioGraph(nodes.values.map { it.node }, edges.values.toList(), nodes.mapValues { it.value.state })
    private fun graphFrame(): String = buildJsonObject {
        put("type", "graph"); put("nodes", json.encodeToJsonElement(nodes.values.map { it.node })); put("edges", json.encodeToJsonElement(edges.values.toList()))
    }.toString()
    private fun snapshotFrame(): String = JsonObject(json.encodeToJsonElement(snapshot()).jsonObject + ("type" to JsonPrimitive("snapshot"))).toString()

    // Callbacks must enqueue only. Registration and the initial frame share the state lock,
    // so a broadcast cannot overtake its subscriber's snapshot.
    internal fun listen(listener: (String) -> Unit): AutoCloseable = synchronized(lock) {
        ensurePreviews()
        listener(snapshotFrame()); listeners += listener
        AutoCloseable { synchronized(lock) { listeners -= listener } }
    }
    private fun broadcast(frame: String) { listeners.forEach { it(frame) } }
    private fun changed(e: Entry, emit: Boolean = false) {
        val checkpoint = StudioCheckpoint(e.node.definition(), e.state, e.job, e.pending.toMap(), liveCode = e.liveCode)
        api(e).commit.call.propagate(json.encodeToString(StudioCommit(checkpoint, e.acknowledge.toList(), if (emit) UUID.randomUUID().toString() else null)))
        host.quiescence().await(5_000)
        // Read back the authoritative cell state before publishing or launching a process.
        check(checkpointOf(e.ref).let { it.state == e.state && it.job == e.job }) { "state commit did not apply" }
        e.acknowledge.clear()
        stateChange()
        val owner = nodes[e.node.id]
        if (owner?.shadow === e) {
            owner.state = owner.state.copy(preview = PreviewState(checkNotNull(e.state.codeHash),
                if (e.busy) "running" else if (e.state.status == "failed") "failed"
                else if (e.state.output != owner.state.preview?.output) "running" else "idle",
                owner.state.preview?.output, e.state.error))
            changed(owner)
        } else broadcast(obj("type" to JsonPrimitive("state"), "node" to json.encodeToJsonElement(e.state)).toString())
    }
    private fun stateChange() {
        if (++changes >= 100) checkpointLocked()
    }
    private fun checkpointLocked() {
        // The kernel refuses compaction with no selected snapshots/frontiers. An empty
        // graph still recovers correctly from its topology tail; leave that WAL intact.
        if (context.handles.isEmpty()) { changes = 0; return }
        host.quiescence().await(5_000)
        host.checkpoint(journal)
        changes = 0
    }
    internal fun checkpoint() = synchronized(lock) { checkpointLocked() }
    private fun structural() {
        atomicWrite(data.resolve("graph.json"), json.encodeToString(CanvasMetadata(
            nodes.mapValues { (_, e) -> CanvasNode(e.node.x, e.node.y, e.node.title) }, edges.values.toList())))
        stateChange()
        broadcast(graphFrame())
    }

    fun addNode(body: JsonObject): StudioNode = synchronized(lock) {
        val kind = body.string("kind")
        val config = nodeConfig(kind, body["config"]?.jsonObject ?: JsonObject(emptyMap()))
        val id = "n" + UUID.randomUUID().toString().replace("-", "")
        val node = StudioNode(id, kind, body.string("title", kind),
            (body["x"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, (body["y"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            config, inputPorts(kind, config))
        val e = spawn(node)
        nodes[id] = e
        structural(); changed(e)
        if (kind in setOf("git", "beads")) schedulePoll(e, 0)
        node
    }

    fun patchNode(id: String, body: JsonObject): StudioNode = synchronized(lock) {
        val e = entry(id)
        val patch = body["config"]?.jsonObject
        val old = e.node
        val config = nodeConfig(old.kind, beadsPatchConfig(old, patch))
        val ports = inputPorts(old.kind, config)
        e.node = old.copy(title = body.string("title", old.title),
            x = (body["x"] as? JsonPrimitive)?.doubleOrNull ?: old.x,
            y = (body["y"] as? JsonPrimitive)?.doubleOrNull ?: old.y, config = config, ports = ports)
        edges.values.filter { it.to == id && it.port !in ports }.map { it.id }.forEach(::removeEdgeLocked)
        e.pending.keys.retainAll(ports.toSet())
        e.state = e.state.copy(inputs = e.state.inputs.filterKeys { it in ports })
        if (patch != null) {
            e.version++
            if (e.shadow != null) discardShadow(e)
        }
        structural(); changed(e)
        if (patch != null && old.kind in setOf("git", "beads")) schedulePoll(e, 0)
        e.node
    }

    fun deleteNode(id: String) = synchronized(lock) {
        val e = entry(id)
        e.poll?.cancel(false)
        fence(e)
        discardShadow(e)
        e.gitTask?.cancel(true)
        edges.values.filter { it.from == id || it.to == id }.map { it.id }.forEach(::removeEdgeLocked)
        detach(e)
        GraphSpec(listOf(DespawnStep(e.handle)) +
            if (e.node.kind == "program") listOf(DespawnStep(gateHandle(e))) else emptyList()).apply(context)
        nodes.remove(id)
        links.removeIf { it.first == id || it.second == id }
        structural()
    }

    fun addEdge(from: String, to: String, port: String): StudioEdge = synchronized(lock) {
        val e = StudioEdge("e" + UUID.randomUUID().toString().replace("-", ""), from, to, port)
        addEdgeLocked(e); e
    }
    private fun addEdgeLocked(edge: StudioEdge) {
        val from = entry(edge.from)
        val to = entry(edge.to)
        if (edge.port !in to.node.ports) conflict("input port does not exist")
        if (edges.values.any { it.to == edge.to && it.port == edge.port }) conflict("input port already connected")
        val pair = edge.from to edge.to
        edges[edge.id] = edge // Make the mapping visible before catch-up is enqueued.
        if (pair !in links) {
            // apply() writes ahead of admission. Cold kernel precheck keeps refusals out
            // of the journal; all live edits remain serialized under the studio lock.
            val proposed = GraphSpec(nodes.values.map { e -> SpawnStep(e.node.id,
                StudioFactory(json.encodeToString(e.node.definition())), identity = IdentityBinding.Exact(e.ref)) } +
                (links + setOf(pair)).map { (a, b) -> ConnectStep(a, "outlet", b, "inlet", LinkOptions(staged = true)) })
            val refusal = proposed.precheck(live = HostLiveView(coldHost, coldRegistry)).steps
                .mapNotNull { it.result as? StepCheck.Refused }.firstOrNull()
            if (refusal != null) {
                edges.remove(edge.id)
                val titles = nodes.values.associate { it.ref.toString() to JsonPrimitive(it.node.title).toString() }
                val reason = Regex("""CellRef\(id=[0-9a-fA-F-]+, instanceId=\d+\)""").replace(refusal.reason) {
                    titles[it.value] ?: it.value
                }
                conflict(reason)
            }
            GraphSpec(listOf(ConnectStep(from.handle, "outlet", inputHandle(to), inputPort(to), LinkOptions(staged = true)))).apply(context)
            links += pair
        } else {
            // A second canvas port shares the admitted link. Previously connected
            // ports ignore their equal held value.
            if (from.state.output != null) changed(from, emit = true)
        }
        structural()
    }
    fun deleteEdge(id: String) = synchronized(lock) {
        if (id !in edges) throw StudioException(404, "edge not found")
        removeEdgeLocked(id); structural()
    }
    private fun removeEdgeLocked(id: String) {
        val edge = edges.remove(id) ?: return
        if (edges.values.none { it.from == edge.from && it.to == edge.to }) {
            GraphSpec(listOf(UnlinkStep(entry(edge.from).handle, "outlet", inputHandle(entry(edge.to)), inputPort(entry(edge.to))))).apply(context)
            links.remove(edge.from to edge.to)
        }
        // Inputs are held values, independent of current connectivity. Keeping them
        // makes an equal re-link catch-up a no-op. PATCH prunes deleted ports explicitly.
    }

    private fun publish(e: Entry, value: JsonObject) {
        e.state = e.state.copy(output = value, error = null)
        changed(e, emit = true)
    }
    fun submit(id: String, text: String) = synchronized(lock) {
        val e = entry(id)
        if (e.node.kind != "text") conflict("submit requires a text node")
        if (e.state.output != null && e.state.text == text) return@synchronized
        val value = change(e.state.output?.get("new")?.jsonPrimitive?.contentOrNull, text)
        e.state = e.state.copy(text = text)
        publish(e, value)
    }
    fun fire(id: String) = synchronized(lock) {
        val e = entry(id)
        when (e.node.kind) {
            "text" -> publish(e, fired(e.state.output ?: change(null, e.state.text)))
            "git", "beads" -> poll(id, true)
            else -> conflict("fire requires a text, git or beads node")
        }
    }

    private fun fired(value: JsonObject) = JsonObject(value + ("fired" to JsonPrimitive(Instant.now().toString())))

    private fun approved(e: Entry) = e.liveCode?.let { sha256(it) == e.state.approvedHash } == true

    private fun accept(e: Entry, input: StudioMsg, allowRuns: Boolean) {
        val id = e.node.id
        val value = json.parseToJsonElement(input.change).jsonObject
        var any = false
        edges.values.filter { it.from == input.fromNodeId && it.to == id }.forEach { edge ->
            if (e.state.inputs[edge.port] != value) {
                val pending = e.pending[edge.port]
                e.pending[edge.port] = if (pending == null) value else composeBeadsOrChange(pending, value)
                e.state = e.state.copy(inputs = e.state.inputs + (edge.port to value))
                any = true
            }
        }
        if (!any) { changed(e); return }
        // A recovered unpromoted implementation is recreated lazily on new input.
        if (allowRuns && nodes[id] === e && e.node.kind == "program" &&
            e.state.codeHash != null && e.state.codeHash != e.state.approvedHash && e.shadow == null) ensureShadow(e)
        if (allowRuns && nodes[id]?.shadow === e) {
            if (ready(e) && !e.busy) launch(e) else changed(e)
            return
        }
        if (!allowRuns) e.state = e.state.copy(status = "stale")
        else if (e.generating) e.state = e.state.copy(status = "generating")
        else if (!e.node.config.bool("auto", true)) e.state = e.state.copy(status = "stale")
        else if (e.node.kind == "program" && !approved(e))
            e.state = e.state.copy(status = "stale", error = "code not approved — generate and approve")
        else if (ready(e)) {
            if (e.busy) e.state = e.state.copy(status = if (e.generating) "generating" else "queued")
            else { launch(e); return }
        }
        changed(e)
    }
    private fun ready(e: Entry) = e.node.ports.all { it in e.state.inputs }

    fun run(id: String) = synchronized(lock) {
        val e = entry(id)
        if (e.node.kind !in setOf("exec", "program", "judge")) conflict("run requires an exec, program or judge node")
        if (!ready(e)) conflict("an input is missing")
        if (e.generating) conflict("node is generating code")
        if (e.busy) {
            e.manualPending = true
            e.state = e.state.copy(status = "queued"); changed(e)
        } else launch(e)
    }
    fun generate(id: String) = synchronized(lock) {
        val e = entry(id)
        if (e.node.kind != "program") conflict("generate requires a program node")
        if (e.busy) conflict("node is busy")
        launch(e, generating = true)
    }
    fun approve(id: String, hash: String) = synchronized(lock) {
        val e = entry(id)
        if (e.node.kind != "program" || e.state.code == null || sha256(e.state.code!!) != hash || e.state.codeHash != hash)
            conflict("hash does not match current candidate")
        if (hash == e.state.approvedHash && e.shadow == null) return@synchronized
        val candidate = ensureShadow(e)
        if (candidate.state.codeHash != hash) conflict("hash does not match shadow candidate")
        // No detached completion may publish into a retired instance. Do not rely
        // on kernel quiescence to stop a worker outside a hosted handler (E5).
        fence(e); fence(candidate)
        detach(e)
        try {
            context.promote(context.handles.getValue(gateHandle(e)), e.ref, candidate.ref, "outlet",
                links.filter { it.first == id }.map { inputRef(entry(it.second)) to inputPort(entry(it.second)) })
        } catch (ex: Exception) {
            // PRECHECK/COMMIT refusal retains the incumbent; restore its intake service.
            attach(e)
            throw ex
        }
        candidate.observer?.let { host.managementInlet.call.despawn(it.ref) }
        candidate.observer = null
        host.quiescence().await(5_000)
        afterPromotion()
        val promoted = checkpointOf(candidate.ref)
        candidate.state = promoted.state
        candidate.liveCode = promoted.liveCode
        candidate.job = promoted.job
        candidate.pending.clear()
        candidate.node = e.node
        e.shadow = null
        nodes[id] = candidate
        changed(candidate)
        // A delivery can have reached the incumbent's durable inbox while its
        // runtime callback is still queued. Transfer and fold it before the one
        // explicit publish, including when the candidate was recreated on approve.
        drain(candidate, allowRuns = false)
        // Relinking does not publish the selected computation. Recompute explicitly
        // once, even for manual nodes; production output equality still dedupes.
        if (ready(candidate)) launch(candidate)
    }

    /** Test seam is the same installation path used by real code generation. */
    internal fun installCode(id: String, answer: String) = synchronized(lock) {
        val e = entry(id)
        check(e.node.kind == "program")
        check(!e.generating) { "node is generating" }
        installCodeLocked(e, answer)
    }
    private fun installCodeLocked(e: Entry, answer: String) {
        val code = extractPython(answer)
        discardShadow(e)
        e.state = e.state.copy(code = code, codeHash = sha256(code),
            status = if (approved(e)) e.state.status else "stale", error = null)
        changed(e)
        if (e.state.codeHash != e.state.approvedHash) ensureShadow(e)
    }

    @Suppress("UNCHECKED_CAST")
    private fun gate(e: Entry) = host.lookup(TypedRef<TrafficLightApi<Propagate<String>>>(
        context.handles.getValue(gateHandle(e))))!!

    private fun detach(e: Entry) { services.remove(e.ref)?.let { host.managementInlet.call.despawn(it) } }
    private fun fence(e: Entry) {
        e.fence.cancel(); e.task?.cancel(true)
        e.busy = false; e.generating = false; e.manualPending = false
        if (e.job?.phase == "started") {
            e.job = e.job!!.copy(phase = "interrupted")
            e.state = e.state.copy(status = "stale", error = "run fenced for replacement")
            changed(e)
        }
    }
    private fun discardShadow(e: Entry) {
        val shadow = e.shadow ?: return
        fence(shadow); detach(shadow)
        shadow.observer?.let { host.managementInlet.call.despawn(it.ref) }
        GraphSpec(listOf(DespawnStep(shadow.handle))).apply(context)
        e.shadow = null
        e.state = e.state.copy(preview = null)
    }

    /** Pure collector: only an Observe-role link receives candidate results. */
    private class StudioObserver(override val ref: CellRef = CellRef(UUID.randomUUID()), receive: (String) -> Unit) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        init { inlet.serve(Propagate(receive)) }
    }
    private fun ensureShadow(e: Entry): Entry {
        e.shadow?.let { return it }
        val handle = "${e.node.id}~candidate~${UUID.randomUUID()}"
        GraphSpec(listOf(SpawnStep(handle, StudioFactory(json.encodeToString(e.node.definition())),
            identity = IdentityBinding.NewInstanceOf(e.ref.id), journalId = "studio", shadow = true))).apply(context)
        val candidate = Entry(e.node, NodeState(e.node.id, inputs = e.state.inputs,
            code = e.state.code, codeHash = e.state.codeHash), context.handles.getValue(handle), handle)
        candidate.liveCode = candidate.state.code // sandbox makes this non-effectful computation safe
        e.shadow = candidate
        val collector = StudioObserver { value ->
            try { intake.execute { synchronized(lock) {
                if (!closed && e.shadow === candidate) {
                    val msg = json.decodeFromString<StudioMsg>(value)
                    if (candidate.state.output?.toString() != msg.change) return@synchronized
                    e.state = e.state.copy(preview = PreviewState(checkNotNull(candidate.state.codeHash),
                        if (candidate.busy) "running" else if (candidate.state.status == "failed") "failed" else "idle",
                        json.parseToJsonElement(msg.change).jsonObject, candidate.state.error))
                    changed(e)
                }
            } } } catch (_: RejectedExecutionException) { /* shutdown */ }
        }
        candidate.observer = collector
        host.managementInlet.call.spawn(collector)
        check(host.managementInlet.call.connect(candidate.ref, "outlet", collector.ref, "inlet",
            LinkOptions(role = LinkRole.Observe, staged = true)) is LinkResult.Connected)
        attach(candidate)
        changed(candidate)
        GraphSpec(listOf(ConnectStep(gateHandle(e), "dataOutlet", handle, "inlet", LinkOptions(staged = true)))).apply(context)
        if (ready(candidate)) launch(candidate)
        return candidate
    }

    private fun launch(e: Entry, generating: Boolean = false) {
        if (!generating && e.node.kind == "program" && nodes[e.node.id]?.shadow !== e && !approved(e)) {
            e.state = e.state.copy(status = "failed", error = "code not approved"); changed(e); return
        }
        val inputs = e.state.inputs + e.pending
        e.pending.clear(); e.manualPending = false; e.busy = true; e.generating = generating
        val node = e.node
        val code = e.liveCode
        e.fence = ProcessFence()
        val fence = e.fence
        val runId = "${Instant.now().toEpochMilli()}-${UUID.randomUUID()}"
        e.state = e.state.copy(status = if (generating) "generating" else "running", error = null, runCount = e.state.runCount + 1)
        e.job = JobRecord(runId, sha256(json.encodeToString(inputs.toSortedMap().toMap())), "started")
        changed(e) // synchronous durable started record BEFORE submitting the worker
        val previewRun = nodes[node.id]?.shadow === e
        e.task = workers.submit { execute(e, node, inputs, code, runId, generating, fence, previewRun) }
    }

    private fun execute(e: Entry, node: StudioNode, inputs: Map<String, JsonObject>, code: String?, runId: String, generating: Boolean, fence: ProcessFence, previewRun: Boolean) {
        val started = Instant.now()
        val dir = nodeDir(node.id).resolve(if (previewRun) "previews" else "runs").resolve(runId)
        var argv = emptyList<String>()
        var cwd = nodeDir(node.id)
        var result = ProcessResult(-1, "", "", "run did not start")
        var output = ""
        var cost: Double? = null
        var judgment: StudioJudgment? = null
        var outcome: String? = null
        try {
            if (node.kind == "judge") {
                val response = judge.judge(node.config, inputs)
                judgment = response.judgment
                cost = response.costUsd
                outcome = if (node.config.string("mode") == "gate")
                    if (judgePasses(node.config, response.judgment)) "passed" else "gated" else "judged"
                result = ProcessResult(0, json.encodeToString(response.judgment), "", null)
            } else {
            val config = node.config
            val preset = if (generating) config.string("generator", "claude") else if (node.kind == "program") "python" else config.string("preset")
            cwd = if (beadsPreset(preset)) beadsWorkspace(config) else if (node.kind == "program") { if (generating) nodeDir(node.id) else dir.resolve("work") } else {
                val path = config.string("cwd").takeIf { it.isNotBlank() }
                    ?: inputs.values.firstNotNullOfOrNull { it.string("repo").takeIf(String::isNotBlank) }
                path?.let(Path::of) ?: nodeDir(node.id)
            }
            val prompt = if (generating) generationPrompt(node, inputs) else substitute(config.string("prompt"), inputs)
            argv = when (preset) {
                "custom" -> tokenize(config.string("command")).map { substitute(it, inputs) }
                "claude" -> claudeArgv(config, prompt, noTools = generating)
                "codex" -> codexArgv(config, prompt, dir.resolve("last.txt"), noTools = generating)
                "bd-comment", "bd-create" -> beadsWriteArgv(config, inputs)
                "python" -> {
                    // Each execution owns a fresh scratch directory and immutable code bytes.
                    // The same sandbox applies before and after promotion.
                    atomicWrite(cwd.resolve("cell.py"), checkNotNull(code))
                    sandbox.command(cwd, profile)
                }
                else -> error("invalid preset")
            }
            result = processes.run(argv, cwd, dir, config.long("timeoutSec", 120),
                if (preset == "python") json.encodeToString(inputs) else null,
                if (preset == "python") mapOf("TMPDIR" to cwd.toRealPath().toString()) else if (beadsPreset(preset)) beadsEnvironment(config) else emptyMap(), fence)
            if (result.error == null) when (preset) {
                "claude" -> {
                    val envelope = json.parseToJsonElement(result.stdout).jsonObject
                    check(!envelope.bool("is_error")) { envelope.string("result", "claude reported an error") }
                    output = (envelope["result"] as? JsonPrimitive)?.content ?: error("claude response has no result")
                    cost = (envelope["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull
                }
                "codex" -> output = Files.readString(dir.resolve("last.txt"))
                else -> output = result.stdout
            }
            output = output.removeSuffix("\n")
            }
        } catch (ex: Exception) { result = result.copy(error = ex.message ?: ex.javaClass.simpleName) }
        val detail = RunDetail(runId, started.toString(), java.time.Duration.between(started, Instant.now()).toMillis(),
            result.exitCode, cost, inputs, argv, cwd.toAbsolutePath().toString(), result.stdout, result.stderr, result.error,
            if (node.kind == "judge") outcome ?: "failed" else null, judgment)
        // Run output I/O is deliberately outside the studio lock.
        try {
            Files.createDirectories(dir)
            if (!Files.exists(dir.resolve("stdout.txt"))) Files.writeString(dir.resolve("stdout.txt"), result.stdout)
            if (!Files.exists(dir.resolve("stderr.txt"))) Files.writeString(dir.resolve("stderr.txt"), result.stderr)
            atomicWrite(dir.resolve("run.json"), json.encodeToString(detail))
        } catch (ex: Exception) { result = result.copy(error = "could not save run: ${ex.message}") }
        synchronized(lock) {
            if (closed || !current(e) || e.job?.runId != runId || e.job?.phase != "started") return
            e.busy = false; e.generating = false
            e.job = e.job!!.copy(phase = "completed", result = detail)
            e.state = e.state.copy(lastRun = detail.summary(), status = if (result.error != null) "failed" else if (outcome == "gated") "gated" else "idle", error = result.error)
            if (result.error == null) {
                if (judgment != null) {
                    val prior = e.state.judgment
                    e.state = e.state.copy(judgment = StudioJudgeState(judgment,
                        (prior?.passed ?: 0) + if (outcome == "passed") 1 else 0,
                        (prior?.gated ?: 0) + if (outcome == "gated") 1 else 0))
                    val value = judgedChange(node.config, inputs, prior?.last?.answer, judgment)
                    if (value != null && e.state.output?.get("new") != value["new"]) publish(e, value)
                    else changed(e)
                }
                else if (generating) installCodeLocked(e, output)
                else if (e.state.output?.get("new")?.jsonPrimitive?.contentOrNull != output)
                    publish(e, change(e.state.output?.get("new")?.jsonPrimitive?.contentOrNull, output))
                else changed(e)
            } else changed(e)
            if (!generating && ready(e) && (e.manualPending || (e.pending.isNotEmpty() && (nodes[node.id]?.shadow === e || e.node.config.bool("auto", true))))) launch(e)
            else if (e.pending.isNotEmpty() && !e.node.config.bool("auto", true)) {
                e.state = e.state.copy(status = "stale"); changed(e)
            }
        }
    }

    private fun generationPrompt(node: StudioNode, inputs: Map<String, JsonObject>): String = buildString {
        appendLine(node.config.string("prompt"))
        appendLine("Input ports: ${node.ports.joinToString()}")
        appendLine("Write a Python 3 program using only the standard library. Read exactly ONE JSON object from stdin.")
        appendLine("Its keys are input port names, and each value is a change object {\"old\": string or null, \"new\": string, ...extra}.")
        appendLine("Print output text to stdout. Exit non-zero on failure. Return the source in one ```python fenced block.")
        appendLine("Current inputs (examples, possibly truncated):")
        inputs.forEach { (port, value) -> appendLine("$port: ${value.toString().take(2000)}") }
    }

    private fun schedulePoll(e: Entry, delay: Long) {
        e.poll?.cancel(false)
        e.poll = timers.schedule({ poll(e.node.id, false) }, delay, TimeUnit.SECONDS)
    }
    internal fun poll(id: String, fire: Boolean = false) = synchronized(lock) {
        val e = nodes[id] ?: return@synchronized
        if (closed) return@synchronized
        e.firePending = e.firePending || fire
        if (e.pollBusy) return@synchronized
        e.pollBusy = true; e.poll?.cancel(false)
        val version = e.version
        val config = e.node.config
        e.gitTask = workers.submit {
            var sha: String? = null
            var error: String? = null
            try { sha = if (e.node.kind == "beads") processes.readBeads(config, data) else gitRef(config) } catch (ex: Exception) { error = ex.message ?: "source poll failed" }
            synchronized(lock) {
                if (closed || nodes[id] !== e) return@synchronized
                e.pollBusy = false
                if (version == e.version) {
                    if (error != null) { e.state = e.state.copy(error = error); changed(e) }
                    else if (e.node.kind == "beads") {
                        val watch = beadsWatch(config)
                        val previous = e.state.beads?.takeIf { it.watch == watch }?.set
                        e.state = e.state.copy(beads = BeadsState(watch, sha!!), error = null,
                            output = if (previous == null) null else e.state.output)
                        if (previous != null && previous != sha) {
                            val value = beadsChange(previous, sha!!, config.string("workspace"))
                            publish(e, if (e.firePending) fired(value) else value)
                        } else if (e.firePending) publish(e, fired(e.state.output ?: beadsChange(null, sha!!, config.string("workspace"))))
                        else changed(e)
                    } else {
                        val previous = e.state.ref
                        e.state = e.state.copy(ref = sha, error = null)
                        if (previous != null && previous != sha) {
                            val value = change(previous, sha!!, config.string("repo"))
                            publish(e, if (e.firePending) fired(value) else value)
                        }
                        else if (e.firePending) publish(e, fired(e.state.output ?: change(null, sha!!, config.string("repo"))))
                        else changed(e)
                    }
                    e.firePending = false
                }
                schedulePoll(e, if (version != e.version) 0 else e.node.config.long("intervalSec", 60))
            }
        }
    }

    private fun gitRef(config: JsonObject): String {
        val repo = config.string("repo")
        check(repo.isNotBlank()) { "repo is empty" }
        fun git(vararg args: String): String {
            // Poll logs are separate from user runs; git never holds the state lock.
            val dir = Files.createTempDirectory(data, ".git-poll-")
            try {
                val r = processes.run(listOf("git", "-C", repo) + args, data, dir, 60)
                check(r.error == null) { r.error ?: "git failed" }
                return r.stdout.trim()
            } finally { dir.toFile().deleteRecursively() }
        }
        if (config.bool("fetch", true) && "origin" in git("remote").lines()) git("fetch", "--quiet", "origin")
        val branch = config.string("branch").takeIf { it.isNotBlank() }
            ?: runCatching { git("symbolic-ref", "--short", "refs/remotes/origin/HEAD") }.getOrNull()
            ?: if (runCatching { git("rev-parse", "--verify", "refs/heads/main^{commit}") }.isSuccess) "main" else "master"
        return git("rev-parse", "--verify", "--end-of-options", "$branch^{commit}")
    }

    fun runs(id: String): List<RunSummary> {
        synchronized(lock) { entry(id) }
        val dir = nodeDir(id).resolve("runs")
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { paths -> paths.map { it.resolve("run.json") }.filter { Files.isRegularFile(it) }
            .map { json.decodeFromString<RunDetail>(Files.readString(it)).summary() }.toList() }
            .sortedByDescending { it.startedAt }.take(20)
    }
    fun runDetail(id: String, runId: String): RunDetail {
        synchronized(lock) { entry(id) }
        if (!Regex("[A-Za-z0-9_-]+").matches(runId)) bad("invalid run id")
        val file = nodeDir(id).resolve("runs").resolve(runId).resolve("run.json")
        if (!Files.isRegularFile(file)) throw StudioException(404, "run not found")
        return json.decodeFromString(Files.readString(file))
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            nodes.values.forEach { it.poll?.cancel(false) }
            // Teardown is not a graph edit. Checkpoint the live topology, including any
            // started job, before stopping the host; recovery fences interrupted jobs.
            nodes.values.forEach { e ->
                detach(e)
                e.shadow?.let { detach(it) }
            }
            services.clear()
            checkpointLocked()
            listeners.clear()
        }
        timers.shutdownNow(); intake.shutdownNow()
        processes.close(); workers.shutdownNow()
        workers.awaitTermination(10, TimeUnit.SECONDS)
        scheduler.shutdown()
    }
}
