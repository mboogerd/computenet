package civictech.query.run

import civictech.cell.Propagate
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.graph.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.oracle.model.ScriptEvent
import civictech.query.QueryCompiler
import civictech.query.diag.CompileResult
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlin.system.exitProcess

/**
 * One cross-process job for [CrossJvmMain]'s `apply` mode: an already-compiled query and the
 * events to drive it with, keyed by EDB relation name. Each event is a [ScriptEvent.Add] or
 * [ScriptEvent.Remove] whose element is a [Row]; events of one relation are driven in list order,
 * relations in the map's order.
 */
data class CrossJvmJob(
    val compiled: CompiledQuery,
    val script: Map<String, List<ScriptEvent>>,
) : Serializable

/**
 * The child-JVM entry point of `CrossJvmCompiledQueryTest` (cab.7-D14), launched by
 * `civictech.testkit.JvmPeer.launch("civictech.query.run.CrossJvmMain", …)` on the test JVM's own
 * classpath. Two modes:
 *
 * - `compile <catalog.ser> <source.txt> <out.ser>`: reads a serialized [Catalog] and the query
 *   text, runs [QueryCompiler.compile], and writes the [CompiledQuery] to `out.ser` — and the
 *   `ObjectOutputStream` bytes of its spec alone to `out.ser.spec`, so the parent compares bytes
 *   **this** JVM produced rather than its own re-serialization of what it read back (BS-15,
 *   `[QRY1-LOWER-05]`). A `Rejected` result prints its rejections and exits `2`.
 * - `apply <job.ser> <out.ser> <seed>`: reads a [CrossJvmJob] and hands it to [applyAndDrive],
 *   writing the folded answers to `out.ser` (`[QRY1-API-06]`'s cross-process half).
 *
 * **The `apply` path invokes no compiler, planner or lowering API.** [applyAndDrive] and the
 * `apply` branch touch only [CompiledQuery.applyTo], [AppliedQuery]'s accessors, the kernel host
 * and kernel views; the only compiler reference in this file is [QueryCompiler] in the `compile`
 * branch. That is what "apply in another process without recompilation" means here, and it is
 * checkable from this file's import list: no `civictech.query.plan`, `civictech.query.lower` or
 * `civictech.query.parse` import.
 *
 * Every successful mode prints exactly one line `cross-jvm done <mode>` and exits `0`; any
 * throwable prints its stack trace and exits `1`.
 */
object CrossJvmMain {

    const val DONE_PREFIX = "cross-jvm done "

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            when (args.firstOrNull()) {
                "compile" -> compile(args)
                "apply" -> apply(args)
                else -> error("usage: compile <catalog.ser> <source.txt> <out.ser> | apply <job.ser> <out.ser> <seed>; got ${args.toList()}")
            }
        } catch (t: Throwable) {
            t.printStackTrace(System.out)
            System.out.flush()
            1
        }
        System.out.flush()
        exitProcess(code)
    }

    private fun compile(args: Array<String>): Int {
        require(args.size == 4) { "compile <catalog.ser> <source.txt> <out.ser>; got ${args.toList()}" }
        val catalog = read(File(args[1])) as Catalog
        val source = File(args[2]).readText()
        return when (val result = QueryCompiler.compile(source, catalog)) {
            is CompileResult.Rejected -> {
                println("cross-jvm compile rejected: ${result.rejections}")
                2
            }
            is CompileResult.Compiled -> {
                write(File(args[3]), result.query)
                File(args[3] + ".spec").writeBytes(bytes(result.query.spec))
                println(DONE_PREFIX + "compile")
                0
            }
        }
    }

    private fun apply(args: Array<String>): Int {
        require(args.size == 4) { "apply <job.ser> <out.ser> <seed>; got ${args.toList()}" }
        val job = read(File(args[1])) as CrossJvmJob
        val answers = applyAndDrive(job.compiled, job.script, args[3].toLong())
        write(File(args[2]), LinkedHashMap(answers))
        println(DONE_PREFIX + "apply")
        return 0
    }

    /**
     * Applies [compiled] to a fresh `SimWorld(seed)` host, subscribes one kernel view per output
     * chosen by its [OutputShape] (the `QueryCase.buildGraph` choice of fold), writes every event
     * of [script] through the host's proxy for that source, runs to idle, and returns the folded
     * answers: `Set<Row>` for [OutputShape.SET_OF_ROWS], `Map<Any?, Any?>` for
     * [OutputShape.MAP_BY_GROUP], `Long` for [OutputShape.COUNTER] (the summed `CounterDelta`
     * amounts — `CountView` folds a `MapDelta`, not the `CounterDelta` a scalar `CountCell`
     * emits, so it cannot be subscribed there). The parent test runs this same function
     * in-process, so the two JVMs differ only in where the [CompiledQuery] came from.
     */
    fun applyAndDrive(compiled: CompiledQuery, script: Map<String, List<ScriptEvent>>, seed: Long): Map<String, Any?> {
        val world = SimWorld(seed)
        val host = world.host
        val applied = compiled.applyTo(host.managementInlet)

        val readers: Map<String, () -> Any?> = compiled.outputShapes.entries.associate { (root, shape) ->
            root to when (shape) {
                OutputShape.SET_OF_ROWS -> {
                    val view = SetView<Row>()
                    host.lookup(applied.setOutput(root))!!.outlet
                        .subscribe(Use.fixed(Propagate { delta -> view.apply(delta) }, PortRef.generate()))
                    val read: () -> Any? = { view.current() }
                    read
                }
                OutputShape.MAP_BY_GROUP -> {
                    val view = MapView<Any?, Any?>()
                    host.lookup(applied.mapOutput(root))!!.outlet
                        .subscribe(Use.fixed(Propagate { delta -> view.apply(delta) }, PortRef.generate()))
                    val read: () -> Any? = { view.current() }
                    read
                }
                OutputShape.COUNTER -> {
                    // Same fold as oracle's ScalarTerminalFold (QueryCase's COUNTER choice). Unexercised by
                    // CrossJvmCompiledQueryTest: its fixture has no scalar COUNT root.
                    var total = 0L
                    host.lookup(applied.counterOutput(root))!!.outlet
                        .subscribe(Use.fixed(Propagate { delta -> total += delta.amount }, PortRef.generate()))
                    val read: () -> Any? = { total }
                    read
                }
            }
        }

        for ((relation, events) in script) {
            val ops = host.lookup(applied.sources.getValue(relation))!!.inlet.call
            for (event in events) {
                when (event) {
                    is ScriptEvent.Add -> ops.add(event.element as Row)
                    is ScriptEvent.Remove -> ops.remove(event.element as Row)
                    else -> error("CrossJvmJob scripts carry only Add/Remove of Rows; got $event for '$relation'")
                }
            }
        }
        world.runToIdle()

        return readers.mapValuesTo(LinkedHashMap()) { (_, read) -> read() }
    }

    fun bytes(value: Any): ByteArray = java.io.ByteArrayOutputStream().also { bos ->
        ObjectOutputStream(bos).use { it.writeObject(value) }
    }.toByteArray()

    fun write(file: File, value: Any) = file.writeBytes(bytes(value))

    fun read(file: File): Any? = ObjectInputStream(file.inputStream()).use { it.readObject() }
}
