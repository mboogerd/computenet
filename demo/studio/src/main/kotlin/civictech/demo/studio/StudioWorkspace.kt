package civictech.demo.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant

@Serializable
data class Workflow(val id: String, val name: String, val createdAt: String,
                    val nodeCount: Int, val status: String, val error: String? = null)

@Serializable
private data class WorkflowRecord(val id: String, val name: String, val createdAt: String)

/** Owns independent runtime lifetimes. Requests and removal share this lock. */
class StudioWorkspace(val data: Path) : AutoCloseable {
    private class Entry(val record: WorkflowRecord, val runtime: StudioRuntime?, val error: String?) {
        val disconnect = mutableSetOf<() -> Unit>()
        fun view() = Workflow(record.id, record.name, record.createdAt,
            runtime?.context?.live()?.spawns?.values?.count { !it.shadow && !it.handle.endsWith("~gate") } ?: 0,
            if (runtime == null) "failed" else "ok", error)
    }
    private val entries = linkedMapOf<String, Entry>()
    private val registry = data.resolve("workflows.json")
    private val workflows = data.resolve("workflows")
    private var closed = false

    init {
        Files.createDirectories(workflows)
        val records = if (Files.exists(registry)) json.decodeFromString<List<WorkflowRecord>>(Files.readString(registry)) else emptyList()
        require(records.map { it.id }.distinct().size == records.size) { "duplicate workflow id in registry" }
        require(records.all { it.id == "default" || Regex("[a-z0-9]{8}").matches(it.id) }) { "invalid workflow id in registry" }
        val migrated = migrate(records)
        migrated.forEach { record -> entries[record.id] = load(record) }
    }

    private fun load(record: WorkflowRecord): Entry = try {
        Entry(record, StudioRuntime(workflows.resolve(record.id)), null)
    } catch (e: Exception) {
        Entry(record, null, e.message ?: e.javaClass.simpleName)
    }

    /** Moving each legacy item first also resumes a partially completed migration. */
    private fun migrate(records: List<WorkflowRecord>): List<WorkflowRecord> {
        val legacy = listOf("studio.journal", "graph.json", "nodes", "python.sb")
        val destination = workflows.resolve("default")
        val markers = legacy.take(3)
        val hasRoot = markers.any { Files.exists(data.resolve(it)) }
        val pending = records.none { it.id == "default" } && markers.any { Files.exists(destination.resolve(it)) }
        if (!hasRoot && !pending) return records
        Files.createDirectories(destination)
        legacy.forEach { name ->
            val source = data.resolve(name)
            if (Files.exists(source)) Files.move(source, destination.resolve(name))
        }
        if (records.any { it.id == "default" }) return records
        val updated = records + WorkflowRecord("default", "Default", Instant.now().toString())
        save(updated)
        return updated
    }

    private fun save(records: List<WorkflowRecord>) = atomicWrite(registry, json.encodeToString(records))
    private fun entry(id: String): Entry {
        check(!closed) { "workspace closed" }
        return entries[id] ?: throw StudioException(404, "workflow not found")
    }

    @Synchronized fun list(): List<Workflow> = entries.values.map { it.view() }

    @Synchronized fun create(suppliedName: String): Workflow {
        check(!closed) { "workspace closed" }
        val name = suppliedName.trim()
        if (name.length !in 1..80) bad("name must be 1–80 characters")
        if (entries.values.any { it.record.name.equals(name, ignoreCase = true) }) conflict("workflow name already exists")
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        var id: String
        do { id = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
        while (Files.exists(workflows.resolve(id)))
        val record = WorkflowRecord(id, name, Instant.now().toString())
        val runtime = StudioRuntime(workflows.resolve(id))
        try { save(entries.values.map { it.record } + record) }
        catch (e: Exception) { runtime.close(); throw e }
        return Entry(record, runtime, null).also { entries[id] = it }.view()
    }

    @Synchronized fun <T> withRuntime(id: String, action: (StudioRuntime) -> T): T {
        val e = entry(id)
        return action(e.runtime ?: conflict(e.error ?: "workflow failed to load"))
    }

    /** SSE registers before releasing the request lock, so removal cannot miss it. */
    @Synchronized internal fun onRemoval(id: String, disconnect: () -> Unit): AutoCloseable {
        val e = entry(id)
        e.disconnect += disconnect
        return AutoCloseable { synchronized(this) { e.disconnect -= disconnect } }
    }

    @Synchronized fun remove(id: String) {
        val e = entry(id)
        e.disconnect.toList().forEach { it() }
        e.disconnect.clear()
        val source = workflows.resolve(id)
        val trash = data.resolve("trash")
        Files.createDirectories(trash)
        val destination = trash.resolve("$id-${Instant.now()}")
        e.runtime?.close()
        try { Files.move(source, destination) }
        catch (ex: Exception) { entries[id] = load(e.record); throw ex }
        try { save(entries.values.filter { it !== e }.map { it.record }) }
        catch (ex: Exception) {
            Files.move(destination, source)
            entries[id] = load(e.record)
            throw ex
        }
        entries.remove(id)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        entries.values.forEach { e ->
            e.disconnect.toList().forEach { it() }
            e.disconnect.clear()
        }
        // Close every runtime even if checkpointing one of them fails.
        var failure: Exception? = null
        entries.values.forEach { e -> try { e.runtime?.close() } catch (ex: Exception) {
            if (failure == null) failure = ex else failure!!.addSuppressed(ex)
        } }
        failure?.let { throw it }
    }
}
