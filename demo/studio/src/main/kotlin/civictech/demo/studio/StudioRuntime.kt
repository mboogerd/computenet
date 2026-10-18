package civictech.demo.studio

import civictech.cell.CellRef
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.Link
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/** Graph and node state have one lock; kernel callbacks only enqueue onto intake. */
class StudioRuntime(val data: Path) : AutoCloseable {
    private val lock = Any()
    private val scheduler = VirtualThreadScheduler("studio-host")
    private val host = ManagedHost(registry = LocationRegistry(), scheduler = scheduler)
    private val manage = host.managementInlet.call
    private val intake = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("studio-input").factory())
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val timers = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("studio-git").factory())
    private val processes = StudioProcesses()
    private val nodes = linkedMapOf<String, Entry>()
    private val edges = linkedMapOf<String, StudioEdge>()
    private val links = mutableMapOf<Pair<String, String>, Link>()
    private val listeners = mutableSetOf<(String) -> Unit>()
    private var closed = false

    private class Entry(var node: StudioNode, var state: NodeState, val ref: CellRef) {
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
        val graphFile = data.resolve("graph.json")
        if (Files.exists(graphFile)) {
            val graph = json.decodeFromString<StudioGraph>(Files.readString(graphFile))
            // Restore ALL held inputs and output caches before any link catch-up can run.
            graph.nodes.forEach { n ->
                val file = nodeDir(n.id).resolve("state.json")
                val restored = if (Files.exists(file)) json.decodeFromString<NodeState>(Files.readString(file)) else NodeState(n.id)
                val state = if (restored.status in setOf("running", "queued", "generating")) restored.copy(status = "stale", error = "previous run interrupted") else restored
                nodes[n.id] = spawn(n, state)
            }
            graph.edges.forEach { e -> addEdgeLocked(e, persist = false) }
        }
        nodes.values.filter { it.node.kind == "git" }.forEach { schedulePoll(it, 0) }
    } }

    private fun spawn(node: StudioNode, state: NodeState): Entry {
        val cell = StudioCell(node.id, state.output?.toString()) { input ->
            try { intake.execute { synchronized(lock) { if (!closed) accept(node.id, input) } } }
            catch (_: RejectedExecutionException) { /* shutdown */ }
        }
        return Entry(node, state, manage.spawn(cell))
    }

    private fun nodeDir(id: String): Path = data.resolve("nodes").resolve(id)
    private fun entry(id: String) = nodes[id] ?: throw StudioException(404, "node not found")
    fun graph(): StudioGraph = synchronized(lock) { snapshot() }
    fun state(id: String): NodeState = synchronized(lock) { entry(id).state }
    /** Drain accepted kernel deliveries, then the non-blocking callback queue (test barrier). */
    internal fun settleInputs() {
        val barrier = CompletableFuture<Unit>()
        scheduler.submit(Int.MAX_VALUE) { barrier.complete(Unit) }
        barrier.get(5, TimeUnit.SECONDS)
        intake.submit {}.get(5, TimeUnit.SECONDS)
    }
    private fun snapshot() = StudioGraph(nodes.values.map { it.node }, edges.values.toList(), nodes.mapValues { it.value.state })
    private fun graphFrame(): String = buildJsonObject {
        put("type", "graph"); put("nodes", json.encodeToJsonElement(nodes.values.map { it.node })); put("edges", json.encodeToJsonElement(edges.values.toList()))
    }.toString()
    private fun snapshotFrame(): String = JsonObject(json.encodeToJsonElement(snapshot()).jsonObject + ("type" to JsonPrimitive("snapshot"))).toString()

    // Callbacks must enqueue only. Registration and the initial frame share the state lock,
    // so a broadcast cannot overtake its subscriber's snapshot.
    internal fun listen(listener: (String) -> Unit): AutoCloseable = synchronized(lock) {
        listener(snapshotFrame()); listeners += listener
        AutoCloseable { synchronized(lock) { listeners -= listener } }
    }
    private fun broadcast(frame: String) { listeners.forEach { it(frame) } }
    private fun changed(e: Entry) {
        atomicWrite(nodeDir(e.node.id).resolve("state.json"), json.encodeToString(e.state))
        broadcast(obj("type" to JsonPrimitive("state"), "node" to json.encodeToJsonElement(e.state)).toString())
    }
    private fun structural() {
        atomicWrite(data.resolve("graph.json"), json.encodeToString(StudioGraph(nodes.values.map { it.node }, edges.values.toList())))
        broadcast(graphFrame())
    }

    fun addNode(body: JsonObject): StudioNode = synchronized(lock) {
        val kind = body.string("kind")
        val config = nodeConfig(kind, body["config"]?.jsonObject ?: JsonObject(emptyMap()))
        val id = "n" + UUID.randomUUID().toString().replace("-", "")
        val node = StudioNode(id, kind, body.string("title", kind),
            (body["x"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, (body["y"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            config, inputPorts(kind, config))
        val e = spawn(node, NodeState(id))
        nodes[id] = e
        structural(); changed(e)
        if (kind == "git") schedulePoll(e, 0)
        node
    }

    fun patchNode(id: String, body: JsonObject): StudioNode = synchronized(lock) {
        val e = entry(id)
        val patch = body["config"]?.jsonObject
        val old = e.node
        val config = nodeConfig(old.kind, JsonObject(old.config + patch.orEmpty()))
        val ports = inputPorts(old.kind, config)
        e.node = old.copy(title = body.string("title", old.title),
            x = (body["x"] as? JsonPrimitive)?.doubleOrNull ?: old.x,
            y = (body["y"] as? JsonPrimitive)?.doubleOrNull ?: old.y, config = config, ports = ports)
        edges.values.filter { it.to == id && it.port !in ports }.map { it.id }.forEach(::removeEdgeLocked)
        e.pending.keys.retainAll(ports.toSet())
        e.state = e.state.copy(inputs = e.state.inputs.filterKeys { it in ports })
        if (patch != null) e.version++
        structural(); changed(e)
        if (patch != null && old.kind == "git") schedulePoll(e, 0)
        e.node
    }

    fun deleteNode(id: String) = synchronized(lock) {
        val e = entry(id)
        e.poll?.cancel(false)
        e.task?.cancel(true)
        e.gitTask?.cancel(true)
        edges.values.filter { it.from == id || it.to == id }.map { it.id }.forEach(::removeEdgeLocked)
        nodes.remove(id)
        manage.despawn(e.ref)
        structural()
    }

    fun addEdge(from: String, to: String, port: String): StudioEdge = synchronized(lock) {
        val e = StudioEdge("e" + UUID.randomUUID().toString().replace("-", ""), from, to, port)
        addEdgeLocked(e, true); e
    }
    private fun addEdgeLocked(edge: StudioEdge, persist: Boolean) {
        val from = entry(edge.from)
        val to = entry(edge.to)
        if (edge.port !in to.node.ports) conflict("input port does not exist")
        if (edges.values.any { it.to == edge.to && it.port == edge.port }) conflict("input port already connected")
        val pair = edge.from to edge.to
        edges[edge.id] = edge // Make the mapping visible before catch-up is enqueued.
        if (pair !in links) {
            when (val result = manage.connect(from.ref, "outlet", to.ref, "inlet", LinkOptions(staged = true))) {
                is LinkResult.Connected -> links[pair] = result.link
                is LinkResult.Rejected -> {
                    edges.remove(edge.id)
                    val titles = nodes.values.associate { it.ref.toString() to JsonPrimitive(it.node.title).toString() }
                    val reason = Regex("""CellRef\(id=[0-9a-fA-F-]+, instanceId=\d+\)""").replace(result.reason) {
                        titles[it.value] ?: it.value
                    }
                    conflict(reason)
                }
                LinkResult.Deferred -> { edges.remove(edge.id); conflict("unexpected deferred local link") }
            }
        } else {
            // A second canvas port shares the admitted link. Replay through that link;
            // all previously connected ports ignore their equal held value.
            from.state.output?.let { emit(from, it) }
        }
        if (persist) structural()
    }
    fun deleteEdge(id: String) = synchronized(lock) {
        if (id !in edges) throw StudioException(404, "edge not found")
        removeEdgeLocked(id); structural()
    }
    private fun removeEdgeLocked(id: String) {
        val edge = edges.remove(id) ?: return
        if (edges.values.none { it.from == edge.from && it.to == edge.to }) links.remove(edge.from to edge.to)?.unlink()
        // Inputs are held values, independent of current connectivity. Keeping them
        // makes an equal re-link catch-up a no-op. PATCH prunes deleted ports explicitly.
    }

    private fun emit(e: Entry, value: JsonObject) {
        host.lookup(TypedRef<StudioCellApi>(e.ref))!!.inlet.call.propagate(StudioMsg.Emit(value.toString()))
    }
    private fun publish(e: Entry, value: JsonObject) {
        e.state = e.state.copy(output = value, error = null)
        changed(e); emit(e, value)
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
            "git" -> poll(id, true)
            else -> conflict("fire requires a text or git node")
        }
    }

    private fun fired(value: JsonObject) = JsonObject(value + ("fired" to JsonPrimitive(Instant.now().toString())))

    private fun approved(e: Entry) = e.state.code?.let { sha256(it) == e.state.approvedHash } == true

    private fun accept(id: String, input: StudioMsg.Input) {
        val e = nodes[id] ?: return
        val value = json.parseToJsonElement(input.change).jsonObject
        var any = false
        edges.values.filter { it.from == input.fromNodeId && it.to == id }.forEach { edge ->
            if (e.state.inputs[edge.port] != value) {
                val pending = e.pending[edge.port]
                e.pending[edge.port] = if (pending == null) value else JsonObject(value + ("old" to (pending["old"] ?: JsonNull)))
                e.state = e.state.copy(inputs = e.state.inputs + (edge.port to value))
                any = true
            }
        }
        if (!any) return
        if (e.generating) e.state = e.state.copy(status = "generating")
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
        if (e.node.kind !in setOf("exec", "program")) conflict("run requires an exec or program node")
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
        e.state = e.state.copy(approvedHash = null)
        launch(e, generating = true)
    }
    fun approve(id: String, hash: String) = synchronized(lock) {
        val e = entry(id)
        if (e.node.kind != "program" || e.state.code == null || sha256(e.state.code!!) != hash || e.state.codeHash != hash)
            conflict("hash does not match current code")
        e.state = e.state.copy(approvedHash = hash, error = null); changed(e)
        if (e.node.config.bool("auto", true) && ready(e) && !e.busy) launch(e)
    }
    /** Test seam is the same installation path used by real code generation. */
    internal fun installCode(id: String, answer: String) = synchronized(lock) {
        val e = entry(id)
        check(e.node.kind == "program")
        check(!e.busy) { "node is busy" }
        installCodeLocked(e, answer)
    }
    private fun installCodeLocked(e: Entry, answer: String) {
        val code = extractPython(answer)
        atomicWrite(nodeDir(e.node.id).resolve("program/cell.py"), code)
        e.state = e.state.copy(code = code, codeHash = sha256(code), approvedHash = null, status = "stale", error = null)
        changed(e)
    }

    private fun launch(e: Entry, generating: Boolean = false) {
        if (!generating && e.node.kind == "program" && (e.state.code == null || sha256(e.state.code!!) != e.state.approvedHash)) {
            e.state = e.state.copy(status = "failed", error = "code not approved"); changed(e); return
        }
        val inputs = e.state.inputs + e.pending
        e.pending.clear(); e.manualPending = false; e.busy = true; e.generating = generating
        val node = e.node
        val code = e.state.code
        val runId = "${Instant.now().toEpochMilli()}-${UUID.randomUUID()}"
        e.state = e.state.copy(status = if (generating) "generating" else "running", error = null, runCount = e.state.runCount + 1)
        changed(e)
        e.task = workers.submit { execute(e, node, inputs, code, runId, generating) }
    }

    private fun execute(e: Entry, node: StudioNode, inputs: Map<String, JsonObject>, code: String?, runId: String, generating: Boolean) {
        val started = Instant.now()
        val dir = nodeDir(node.id).resolve("runs").resolve(runId)
        var argv = emptyList<String>()
        var cwd = nodeDir(node.id)
        var result = ProcessResult(-1, "", "", "run did not start")
        var output = ""
        var cost: Double? = null
        try {
            val config = node.config
            val preset = if (generating) config.string("generator", "claude") else if (node.kind == "program") "python" else config.string("preset")
            cwd = if (node.kind == "program") nodeDir(node.id).resolve(if (generating) "." else "program") else {
                val path = config.string("cwd").takeIf { it.isNotBlank() }
                    ?: inputs.values.firstNotNullOfOrNull { it.string("repo").takeIf(String::isNotBlank) }
                path?.let(Path::of) ?: nodeDir(node.id)
            }
            val prompt = if (generating) generationPrompt(node, inputs) else substitute(config.string("prompt"), inputs)
            argv = when (preset) {
                "custom" -> tokenize(config.string("command")).map { substitute(it, inputs) }
                "claude" -> claudeArgv(config, prompt, noTools = generating)
                "codex" -> codexArgv(config, prompt, dir.resolve("last.txt"), noTools = generating)
                "python" -> {
                    // Generation is excluded while busy. Install the approved bytes before
                    // starting the prescribed program-directory command.
                    atomicWrite(cwd.resolve("cell.py"), checkNotNull(code))
                    listOf("python3", "cell.py")
                }
                else -> error("invalid preset")
            }
            result = processes.run(argv, cwd, dir, config.long("timeoutSec", 120), if (preset == "python") json.encodeToString(inputs) else null)
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
        } catch (ex: Exception) { result = result.copy(error = ex.message ?: ex.javaClass.simpleName) }
        val detail = RunDetail(runId, started.toString(), java.time.Duration.between(started, Instant.now()).toMillis(),
            result.exitCode, cost, inputs, argv, cwd.toAbsolutePath().toString(), result.stdout, result.stderr, result.error)
        // Run output I/O is deliberately outside the studio lock.
        try {
            Files.createDirectories(dir)
            if (!Files.exists(dir.resolve("stdout.txt"))) Files.writeString(dir.resolve("stdout.txt"), result.stdout)
            if (!Files.exists(dir.resolve("stderr.txt"))) Files.writeString(dir.resolve("stderr.txt"), result.stderr)
            atomicWrite(dir.resolve("run.json"), json.encodeToString(detail))
        } catch (ex: Exception) { result = result.copy(error = "could not save run: ${ex.message}") }
        synchronized(lock) {
            if (closed || nodes[node.id] !== e) return
            e.busy = false; e.generating = false
            e.state = e.state.copy(lastRun = detail.summary(), status = if (result.error == null) "idle" else "failed", error = result.error)
            if (result.error == null) {
                if (generating) installCodeLocked(e, output)
                else if (e.state.output?.get("new")?.jsonPrimitive?.contentOrNull != output)
                    publish(e, change(e.state.output?.get("new")?.jsonPrimitive?.contentOrNull, output))
                else changed(e)
            } else changed(e)
            if (!generating && ready(e) && (e.manualPending || (e.pending.isNotEmpty() && e.node.config.bool("auto", true)))) launch(e)
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
    private fun poll(id: String, fire: Boolean) = synchronized(lock) {
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
            try { sha = gitRef(config) } catch (ex: Exception) { error = ex.message ?: "git poll failed" }
            synchronized(lock) {
                if (closed || nodes[id] !== e) return@synchronized
                e.pollBusy = false
                if (version == e.version) {
                    if (error != null) { e.state = e.state.copy(error = error); changed(e) }
                    else {
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
            links.values.forEach { it.unlink() }; links.clear()
            nodes.values.forEach { manage.despawn(it.ref) }
            listeners.clear()
        }
        timers.shutdownNow(); intake.shutdownNow()
        processes.close(); workers.shutdownNow()
        workers.awaitTermination(10, TimeUnit.SECONDS)
        scheduler.shutdown()
    }
}
