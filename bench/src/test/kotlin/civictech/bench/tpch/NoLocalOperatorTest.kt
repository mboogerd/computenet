package civictech.bench.tpch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** BS-9's source half: the TPC-H bridge assembles existing operators only. */
class NoLocalOperatorTest {

    @Test
    fun `tpch sources contain no local aggregate join or multiset implementation`() {
        val repoRoot = Paths.get(
            requireNotNull(System.getProperty("computenet.repo.root")) {
                "bench must provide computenet.repo.root for actual-tree inspection"
            },
        )
        val roots = listOf(
            repoRoot.resolve("bench/src/main/kotlin/civictech/bench/tpch"),
            repoRoot.resolve("bench/src/jmh/kotlin/civictech/bench/tpch"),
        ).filter(Files::isDirectory)
        val files = roots.flatMap(::kotlinFiles).sorted()

        assertTrue(files.isNotEmpty(), "no TPC-H Kotlin source files found under $roots")
        val violations = files.flatMap { file ->
            localOperatorViolations(file, Files.readString(file), repoRoot)
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))

        val queryFiles = files.filter { it.parent.fileName.toString() == "queries" && it.fileName.toString().matches(Q_QUERY_FILE) }
        assertTrue(queryFiles.isNotEmpty(), "no Q*.kt query files found under the queries package")
        queryFiles.forEach { file ->
            val code = withoutComments(Files.readString(file))
            assertTrue(
                OPERATOR_IMPORT.containsMatchIn(code),
                "${repoRoot.relativize(file)} imports no existing relational operator",
            )
        }
    }

    @Test
    fun `operator inspection is non vacuous for a local aggregator`() {
        val fixture = """
            class FakeLocalFold : Aggregator<Int> {
                override fun add(value: Int) = Unit
            }
        """.trimIndent()

        assertFalse(
            localOperatorViolations(Paths.get("fixture.kt"), fixture, Paths.get(".")).isEmpty(),
            "the source inspection must reject a test-local : Aggregator< implementation",
        )
    }

    private fun localOperatorViolations(file: Path, source: String, repoRoot: Path): List<String> {
        val code = withoutComments(source)
        val relative = runCatching { repoRoot.relativize(file).toString() }.getOrDefault(file.toString())
        return buildList {
            if (AGGREGATOR_IMPLEMENTATION.containsMatchIn(code)) {
                add("$relative implements a local Aggregator")
            }
            if (TREE_MAP_MULTIPLICITY.containsMatchIn(code)) {
                add("$relative contains a TreeMap with Int multiplicity")
            }
            if (LOCAL_STATE_IDENTIFIER.containsMatchIn(code.replace("ledgerProbe", ""))) {
                add("$relative contains a local ledger/multiset identifier")
            }
            if (file.fileName.toString() !in REFERENCE_FILES && isNestedLoop(code)) {
                add("$relative contains a hand-rolled nested join loop")
            }
        }
    }

    private fun kotlinFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
            .toList()
    }

    /** Remove comments while retaining line structure for useful failure locations. */
    private fun withoutComments(source: String): String = source
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("//[^\\r\\n]*"), "")

    /** Detect a loop nested in another braced loop, including compact one-line loops. */
    private fun isNestedLoop(source: String): Boolean {
        val loop = Regex("\\bfor\\s*\\(")
        var depth = 0
        val activeLoopBodies = mutableListOf<Int>()
        var pendingLoop = false
        source.lines().forEach { line ->
            val loopCount = loop.findAll(line).count()
            if (loopCount > 0 && (activeLoopBodies.isNotEmpty() || loopCount > 1)) return true
            if (pendingLoop && '{' in line) {
                activeLoopBodies += depth + 1
                pendingLoop = false
            }
            if (loopCount == 1) {
                if ('{' in line) activeLoopBodies += depth + 1 else pendingLoop = true
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            activeLoopBodies.removeAll { it > depth }
        }
        return false
    }

    private companion object {
        val Q_QUERY_FILE = Regex("Q\\d+\\.kt")
        val OPERATOR_IMPORT = Regex(
            "(?m)^\\s*import\\s+civictech\\.cell\\.data\\.op\\." +
                "(?:JoinSetCell|SemiJoinCell|FilterCell|GroupByCell|FlatMapSetCell)\\b",
        )
        val AGGREGATOR_IMPLEMENTATION = Regex(
            "(?m)^\\s*(?:class|object)\\s+\\w+[^\\n{]*:\\s*" +
                "(?:civictech\\.cell\\.data\\.)?Aggregator\\s*<",
        )
        // Q16 passes the kernel countDistinct accumulator's support type as a
        // GroupByCell type argument. Only a locally declared TreeMap is prohibited;
        // the operator's already-owned support state is not a bench-side multiset.
        val TREE_MAP_MULTIPLICITY = Regex(
            "(?m)^\\s*(?:(?:private|internal|public)\\s+)?(?:val|var)\\s+\\w+[^\\n=]*=" +
                "\\s*(?:java\\.util\\.)?TreeMap<[^>\\n]*,\\s*Int\\s*>",
        )
        val LOCAL_STATE_IDENTIFIER = Regex("(?i)(?:ledger|multiset|zset|weightedSet)")
        val REFERENCE_FILES = setOf("TpchReference.kt", "LogicalTables.kt")
    }
}
