package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** BS-12's source half: Nexmark assembles existing operators only. */
class NoLocalOperatorTest {

    @Test
    fun `nexmark sources contain no local aggregate join window or multiset implementation`() {
        val repoRoot = Paths.get(
            requireNotNull(System.getProperty("computenet.repo.root")) {
                "bench must provide computenet.repo.root for actual-tree inspection"
            },
        )
        val roots = listOf(
            repoRoot.resolve("bench/src/main/kotlin/civictech/bench/nexmark"),
            repoRoot.resolve("bench/src/jmh/kotlin/civictech/bench/nexmark"),
        ).filter(Files::isDirectory)
        val files = roots.flatMap(::kotlinFiles).sorted()

        assertTrue(files.isNotEmpty(), "no Nexmark Kotlin source files found under $roots")
        val violations = files.flatMap { file ->
            localOperatorViolations(file, Files.readString(file), repoRoot)
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))

        val queryFiles = files.filter {
            it.parent.fileName.toString() == "queries" &&
                it.fileName.toString().matches(Q_QUERY_FILE)
        }
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
    fun `operator inspection is non vacuous for every forbidden local mechanism`() {
        val fixtures = listOf(
            "local aggregator" to "class FakeLocalFold : Aggregator<Int> { override fun add(value: Int) = Unit }",
            "anonymous local aggregator" to "val fold = object : Aggregator<Int, Int, Int> { }",
            "second-supertype local aggregator" to "class FakeFold(val n: Int) : Base(n), Aggregator<Int, Int, Int> { }",
            "local treemap declaration" to "val counts: TreeMap<String, Int>",
            "local treemap construction" to "val counts = TreeMap<String, Int>()",
            "local ledger" to "val localLedger = 1",
            "local multiset" to "val multiset = 1",
            "nested join" to "for (left in lefts) { for (right in rights) { Unit } }",
            "window arithmetic" to "val w = bid.dateTime / WINDOW_MS * WINDOW_MS",
            "envelope bound outside StateGrowth.kt" to "val w = policy.lateness / NexmarkSpec.WINDOW_MS + 2L",
            "wall clock" to "val now = System.currentTimeMillis()",
        )
        fixtures.forEach { (name, source) ->
            assertFalse(
                localOperatorViolations(Paths.get("fixture.kt"), source, Paths.get("."))
                    .isEmpty(),
                "the source inspection must reject a test-local $name",
            )
        }
        assertFalse(
            OPERATOR_IMPORT.containsMatchIn(
                withoutComments("import civictech.cell.data.Windows\n// import civictech.cell.data.op.GroupByCell"),
            ),
            "the operator-import requirement must reject a query file with no live operator import",
        )
    }

    private fun localOperatorViolations(file: Path, source: String, repoRoot: Path): List<String> {
        val code = withoutComments(source)
        val relative = runCatching { repoRoot.relativize(file).toString() }.getOrDefault(file.toString())
        return buildList {
            if (AGGREGATOR_IMPLEMENTATION.containsMatchIn(code)) {
                add("$relative implements a local Aggregator")
            }
            if (LOCAL_TREE_MAP.containsMatchIn(code)) {
                add("$relative contains a locally declared or constructed TreeMap with Int multiplicity")
            }
            if (LOCAL_STATE_IDENTIFIER.containsMatchIn(code.replace(LEDGER_PROBE, ""))) {
                add("$relative contains a local ledger/multiset identifier")
            }
            if (file.fileName.toString() !in REFERENCE_FILES && isNestedLoop(code)) {
                add("$relative contains a hand-rolled nested join loop")
            }
            val windowCode = if (file.fileName.toString() == "StateGrowth.kt") {
                WINDOW_BOUND_EXEMPTION.replace(code, "")
            } else {
                code
            }
            if (file.fileName.toString() !in REFERENCE_FILES &&
                WINDOW_KEY_ARITHMETIC.containsMatchIn(windowCode)
            ) {
                add("$relative derives a window key with local arithmetic")
            }
            val wallClockMatches = WALL_CLOCK.findAll(code).filterNot { match ->
                when (file.fileName.toString()) {
                    "NexmarkRig.kt" -> true
                    "NexmarkRun.kt" -> {
                        val start = code.lastIndexOf('\n', match.range.first - 1) + 1
                        val end = code.indexOf('\n', match.range.last + 1)
                            .takeIf { it >= 0 } ?: code.length
                        val sourceLine = code.substring(start, end)
                        sourceLine.contains("generationStarted") ||
                            sourceLine.contains("generationNanos")
                    }
                    else -> false
                }
            }.toList()
            if (wallClockMatches.isNotEmpty()) {
                add("$relative reads a wall clock or timer")
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
            "\\b(?:class|object)\\b(?:[^{=(\\n]|\\([^)\\n]*\\))*[:,]\\s*" +
                "(?:civictech\\.cell\\.data\\.)?Aggregator\\s*<",
        )
        val LOCAL_TREE_MAP = Regex(
            "(?m)(?:\\b(?:val|var)\\s+\\w+\\s*:\\s*(?:java\\.util\\.)?" +
                "TreeMap<[^>\\n]*,\\s*Int\\s*>|(?:java\\.util\\.)?" +
                "TreeMap<[^>\\n]*,\\s*Int\\s*>\\s*\\()",
        )
        val LOCAL_STATE_IDENTIFIER = Regex("(?i)(?:ledger|multiset|zset|weightedSet)")
        val LEDGER_PROBE = Regex("(?i)\\bledgerProbe\\b")
        val WINDOW_KEY_ARITHMETIC = Regex(
            "\\bfloorDiv\\b|(?:/|%)\\s*(?:(?:\\w+\\.)?WINDOW_MS)\\b",
        )
        val WINDOW_BOUND_EXEMPTION = Regex(
            "policy\\.lateness\\s*/\\s*(?:NexmarkSpec\\.)?WINDOW_MS\\s*\\+\\s*2L",
        )
        val WALL_CLOCK = Regex(
            "System\\.(?:currentTimeMillis|nanoTime)\\s*\\(|" +
                "\\bInstant\\.now\\s*\\(|\\bjava\\.time\\.Clock\\b|" +
                "\\bTimer\\b|\\bScheduledExecutor\\w*\\b|\\bThread\\.sleep\\s*\\(",
        )
        val REFERENCE_FILES = setOf("NexmarkReference.kt")
    }
}
