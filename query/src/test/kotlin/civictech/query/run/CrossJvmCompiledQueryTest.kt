package civictech.query.run

import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.WriterId
import civictech.query.QueryCompiler
import civictech.query.diag.CompileResult
import civictech.query.lower.PlanFixtures
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import civictech.testkit.JvmPeer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The two cross-JVM claims of feature computenet-cab.7 (cab.7-D14), each forking one child JVM
 * running [CrossJvmMain] through [JvmPeer.launch] on this JVM's classpath:
 *
 * - **BS-15** (`[QRY1-PLAN-01]`, `[QRY1-LOWER-05]`): one query text and one [civictech.query.schema.Catalog],
 *   compiled here and in the child, yield `==` [civictech.query.plan.LogicalPlan]s and `==`
 *   [civictech.cell.graph.GraphSpec]s (LOWER-05's definition: equal step list, handles and
 *   factories by `equals`) whose `ObjectOutputStream` bytes — each produced by its own JVM — are
 *   identical. In-JVM determinism is `LoweringDeterminismTest`; this is the across-process half.
 * - **`[QRY1-API-06]` cross-process half**: a [CompiledQuery] serialized here is deserialized,
 *   applied to a fresh `SimWorld` host, driven and folded in the child, **without
 *   recompilation** — the child's `apply` mode calls no `QueryCompiler`, `Planner` or `Lowering`
 *   API (see [CrossJvmMain]'s KDoc and import list) — and its answers equal this JVM's answers
 *   for the same script. BS-12's in-process half is `CompiledQueryApplyTest`.
 *
 * Every child is awaited with a bounded `waitFor`, must exit `0` and print its
 * `cross-jvm done <mode>` line (proof it really ran), and every failure quotes its output
 * ([JvmPeer.Peer.report]); it is destroyed in a `finally`.
 *
 * **Limit.** Both JVMs are launched from the same `java.home` and classpath, so this witnesses
 * independence from process-local state (identity hashes, class-init order, per-JVM counters),
 * not from a different JDK build or kernel version.
 */
class CrossJvmCompiledQueryTest {

    @TempDir
    lateinit var dir: Path

    /**
     * Three roots over two relations: a join with a projection (`j` drops the join column `Y`),
     * an antijoin (`a`), and a grouped count over the same join (`cnt`).
     */
    private val source = """
        j(X, Z) :- r(X, Y), s(Y, Z).
        a(X, Y) :- r(X, Y), not s(X, Y).
        @count cnt(X, Z) :- r(X, Y), s(Y, Z).
    """.trimIndent()

    /**
     * `PlanFixtures`' INT catalog with every attribute declared the row key: `QueryCompiler`
     * refuses a COUNT over a non-key-preserving input ([QRY1-SEM-02]), and the grouped count is
     * one of the three roots this fixture must carry.
     */
    private val catalog = PlanFixtures.catalog("r" to 2, "s" to 2).let { base ->
        Catalog(base.relations.mapValues { (_, schema) -> schema.copy(rowKey = schema.attributes.map { it.name }.toSet()) })
    }

    private fun row(vararg values: Int): Row = Row(values.toList())

    private val writer = WriterId("w")
    private fun add(vararg values: Int): ScriptEvent = ScriptEvent.Add(writer, row(*values))
    private fun remove(vararg values: Int): ScriptEvent = ScriptEvent.Remove(writer, row(*values))

    /**
     * Adds and removes on both relations. `r.remove(2, 20)` retracts a row that had joined with
     * `s(20, 200)`; `s.add(3, 30)` makes `a` exclude `r(3, 30)`; the LAST event,
     * `s.remove(30, 300)`, retracts the join partner of `r(3, 30)` — dropping it changes both
     * `j` and `cnt`, which is what the recorded mutation check relies on.
     */
    private val script: Map<String, List<ScriptEvent>> = linkedMapOf(
        "r" to listOf(add(1, 10), add(2, 20), add(3, 30), remove(2, 20)),
        "s" to listOf(add(10, 100), add(20, 200), add(30, 300), add(3, 30), remove(30, 300)),
    )

    private fun compileHere(): CompiledQuery = when (val result = QueryCompiler.compile(source, catalog)) {
        is CompileResult.Compiled -> result.query
        is CompileResult.Rejected -> throw AssertionFailedError("fixture query did not compile: ${result.rejections}")
    }

    /** Launches [CrossJvmMain] with [args], waits for it to exit 0 and print its done line. */
    private fun runChild(mode: String, vararg args: String): JvmPeer.Peer {
        val peer = JvmPeer.launch(CrossJvmMain::class.java.name, mode, *args)
        try {
            if (!peer.process.waitFor(CHILD_TIMEOUT_S, TimeUnit.SECONDS)) {
                throw AssertionFailedError("child `$mode` did not exit within ${CHILD_TIMEOUT_S}s\n\n${peer.report()}")
            }
            val exit = peer.process.exitValue()
            if (exit != 0) throw AssertionFailedError("child `$mode` exited with $exit, expected 0\n\n${peer.report()}")
            // The output reader thread may lag the exit by a moment; wait for the done line.
            JvmPeer.await("child printed `${CrossJvmMain.DONE_PREFIX}$mode`", listOf(peer), timeoutMs = 10_000) {
                peer.output().lines().any { it.trim() == CrossJvmMain.DONE_PREFIX + mode }
            }
        } catch (t: Throwable) {
            JvmPeer.destroy(peer)
            throw t
        }
        return peer
    }

    private fun fail(message: String, peer: JvmPeer.Peer): Nothing =
        throw AssertionFailedError("$message\n\n${peer.report()}")

    @Test
    fun `BS-15 - one query compiled in two JVMs yields equal plans and equal specs with identical bytes`() {
        val parent = compileHere()

        val catalogFile = dir.resolve("catalog.ser").toFile().also { CrossJvmMain.write(it, catalog) }
        val sourceFile = dir.resolve("source.txt").toFile().also { it.writeText(source) }
        val outFile = dir.resolve("compiled.ser").toFile()

        val peer = runChild("compile", catalogFile.path, sourceFile.path, outFile.path)
        try {
            val child = CrossJvmMain.read(outFile) as? CompiledQuery
                ?: fail("child wrote no CompiledQuery to $outFile", peer)
            val childSpecBytes = File(outFile.path + ".spec").readBytes()

            if (child.plan != parent.plan) {
                fail("BS-15 [QRY1-PLAN-01]: plans differ across JVMs\nparent=${parent.plan}\nchild=${child.plan}", peer)
            }
            if (child.spec != parent.spec) {
                fail("BS-15 [QRY1-LOWER-05]: specs differ across JVMs\nparent=${parent.spec.lowered()}\nchild=${child.spec.lowered()}", peer)
            }
            val parentSpecBytes = CrossJvmMain.bytes(parent.spec)
            if (!childSpecBytes.contentEquals(parentSpecBytes)) {
                val at = childSpecBytes.indices.firstOrNull { it >= parentSpecBytes.size || childSpecBytes[it] != parentSpecBytes[it] }
                fail(
                    "BS-15 [QRY1-LOWER-05]: spec serializations differ across JVMs " +
                        "(child ${childSpecBytes.size} bytes, parent ${parentSpecBytes.size} bytes, first difference at ${at ?: parentSpecBytes.size})",
                    peer,
                )
            }
            // The child compiled a real three-root query, not an empty one.
            if (child.outputShapes.keys != setOf("j", "a", "cnt")) fail("unexpected roots ${child.outputShapes}", peer)
        } finally {
            JvmPeer.destroy(peer)
        }
    }

    @Test
    fun `QRY1-API-06 - a CompiledQuery serialized here applies and answers in a child JVM without recompilation`() {
        val compiled = compileHere()
        val seed = 11L

        val jobFile = dir.resolve("job.ser").toFile().also { CrossJvmMain.write(it, CrossJvmJob(compiled, script)) }
        val outFile = dir.resolve("answers.ser").toFile()

        val expected = CrossJvmMain.applyAndDrive(compiled, script, seed)

        val peer = runChild("apply", jobFile.path, outFile.path, seed.toString())
        try {
            @Suppress("UNCHECKED_CAST")
            val actual = CrossJvmMain.read(outFile) as? Map<String, Any?>
                ?: fail("child wrote no answer map to $outFile", peer)

            if (actual.keys != expected.keys) {
                fail("[QRY1-API-06]: output names differ: child=${actual.keys} parent=${expected.keys}", peer)
            }
            for ((root, answer) in expected) {
                if (actual[root] != answer) {
                    fail("[QRY1-API-06]: output '$root' diverges across JVMs: child=${actual[root]} parent=$answer", peer)
                }
            }
            // A script whose every answer is empty would prove nothing about the child.
            val nonEmpty = expected.filterValues { value ->
                when (value) {
                    is Collection<*> -> value.isNotEmpty()
                    is Map<*, *> -> value.isNotEmpty()
                    is Long -> value != 0L
                    else -> value != null
                }
            }
            if (nonEmpty.isEmpty()) fail("[QRY1-API-06]: every answer is empty: $expected", peer)
            // Pin the fixture's answers so a silently degenerate compile cannot pass as agreement.
            if (expected["j"] != setOf(row(1, 100))) fail("fixture answer j=${expected["j"]}", peer)
            if (expected["a"] != setOf(row(1, 10))) fail("fixture answer a=${expected["a"]}", peer)
        } finally {
            JvmPeer.destroy(peer)
        }
    }

    private companion object {
        /** The loaded-CI budget for a cold child JVM that compiles or applies and exits. */
        const val CHILD_TIMEOUT_S = 120L
    }
}
