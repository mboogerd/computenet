package civictech.bench.graphalytics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * BS-8's source half: inspect the actual graphalytics tree, not a fixture copied into
 * the test. The run-protocol sibling's phase-drive assertion is the behavioural half;
 * this test catches a loop driver before it can be hidden by a passing small graph.
 */
class NoLoopDriverTest {

    @Test
    fun `graphalytics sources contain no feedback ports loop driver or KE5 identifiers`() {
        val repoRoot = Paths.get(
            requireNotNull(System.getProperty("computenet.repo.root")) {
                "bench must provide computenet.repo.root for actual-tree inspection"
            },
        )
        val roots = listOf(
            repoRoot.resolve("bench/src/main/kotlin/civictech/bench/graphalytics"),
            repoRoot.resolve("bench/src/jmh/kotlin/civictech/bench/graphalytics"),
        ).filter(Files::isDirectory)
        val files = roots.flatMap(::kotlinFiles).sorted()

        assertTrue(files.isNotEmpty(), "no graphalytics Kotlin source files found under $roots")

        files.forEach { file ->
            val source = Files.readString(file)
            assertFalse(
                FEEDBACK_IMPORT.containsMatchIn(source),
                "${repoRoot.relativize(file)} imports a feedback-port or cycle-head type",
            )

            val code = withoutComments(source)
            val driver = driverCallInsideLoop(code)
            assertTrue(
                driver == null,
                "${repoRoot.relativize(file)} drives the runtime inside a loop: $driver",
            )
            assertFalse(
                KE5_IDENTIFIER.containsMatchIn(code),
                "${repoRoot.relativize(file)} mentions a loop/fixpoint identifier outside comments",
            )
        }
    }

    private fun kotlinFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
            .toList()
    }

    /** Remove comments for the source-inspection assertions while preserving code lines. */
    private fun withoutComments(source: String): String = source
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("//[^\\r\\n]*"), "")

    /**
     * Find a drive call whose nearest enclosing brace block is a for/while/do/repeat body.
     * The actual sources use braced loops; the same-line check also catches a compact
     * `for (...) runToIdle()` mutation.
     */
    private fun driverCallInsideLoop(source: String): String? {
        val lines = source.lines()
        var depth = 0
        var pendingLoop = false
        val loopBodyDepths = mutableListOf<Int>()

        lines.forEachIndexed { index, line ->
            val loop = LOOP.find(line)
            val hasOpenBrace = '{' in line
            if (pendingLoop && hasOpenBrace) {
                loopBodyDepths += depth + 1
                pendingLoop = false
            }
            if (loop != null) {
                if (hasOpenBrace) {
                    loopBodyDepths += depth + 1
                } else {
                    pendingLoop = true
                }
            }

            val call = DRIVER.find(line)
            val isInsideLoop = loopBodyDepths.any { depth >= it }
            val isCompactLoopCall = loop != null && call != null
            if (call != null && (isInsideLoop || isCompactLoopCall || pendingLoop && index > 0)) {
                return "${call.value.trim()} on source line ${index + 1}"
            }

            depth += line.count { it == '{' } - line.count { it == '}' }
            loopBodyDepths.removeAll { it > depth }
        }
        return null
    }

    private companion object {
        val FEEDBACK_IMPORT = Regex(
            "(?m)^\\s*import\\s+civictech\\.cell\\.port\\.(?:CycleHead|FeedbackInlet|PropagateFeedbackInlet|FeedbackPort)\\b",
        )
        val KE5_IDENTIFIER = Regex("\\b(?:fixpoint|iterationCut|maxLaps)\\b")
        val LOOP = Regex("\\b(?:for|while|do|repeat)\\b")
        val DRIVER = Regex("\\b(?:runToIdle|awaitDrained|awaitIdle|step)\\s*\\(")
    }
}
