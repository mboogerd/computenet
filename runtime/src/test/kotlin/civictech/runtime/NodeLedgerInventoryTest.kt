package civictech.runtime

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Guards the bootstrap boundary: runtime hosts use the node ledger, demos do not build one. */
class NodeLedgerInventoryTest {

    @Test
    fun `every runtime ManagedHost construction names the node budget`() {
        val root = repoRoot().toPath()
        val sourceRoot = root.resolve("runtime/src/main")
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .forEach { file ->
                    val source = stripComments(Files.readString(file))
                    managedHostCalls(source).forEach { call ->
                        assertTrue(
                            Regex("""\bbudget\s*=""").containsMatchIn(call.snippet),
                            "${root.relativize(file)} ManagedHost construction omits budget =: ${call.snippet}",
                        )
                    }
                }
        }
    }

    @Test
    fun `demo main sources do not construct a budget ledger`() {
        val root = repoRoot().toPath()
        val demoRoot = root.resolve("demo")
        val budgetType = Regex("""\b(TokenBucketLedger|BudgetLedger)\b""")
        Files.list(demoRoot).use { modules ->
            modules.filter { Files.isDirectory(it) }
                .forEach { module ->
                    val sourceRoot = module.resolve("src/main")
                    if (!Files.isDirectory(sourceRoot)) return@forEach
                    Files.walk(sourceRoot).use { paths ->
                        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                            .forEach { file ->
                                val source = stripComments(Files.readString(file))
                                assertTrue(
                                    !budgetType.containsMatchIn(source),
                                    "${root.relativize(file)} names a runtime budget type",
                                )
                            }
                    }
                }
        }
    }

    private data class ManagedHostCall(val snippet: String)

    private fun managedHostCalls(source: String): List<ManagedHostCall> {
        val calls = mutableListOf<ManagedHostCall>()
        val constructor = Regex("""\bManagedHost\s*\(""")
        constructor.findAll(source).forEach { match ->
            val lineStart = source.lastIndexOf('\n', match.range.first - 1) + 1
            val line = source.substring(lineStart, source.indexOf('\n', match.range.first).takeIf { it >= 0 } ?: source.length)
            val trimmedLine = line.trimStart()
            if (trimmedLine.startsWith("import ") || Regex("""\bclass\b.*:\s*ManagedHost\s*\(""").containsMatchIn(line)) {
                return@forEach
            }

            val open = source.indexOf('(', match.range.first)
            val close = matchingParen(source, open)
            calls += ManagedHostCall(source.substring(match.range.first, close + 1))
        }
        return calls
    }

    private fun matchingParen(source: String, open: Int): Int {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("unclosed ManagedHost construction at source offset $open")
    }

    private fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not find settings.gradle.kts from ${System.getProperty("user.dir")}")
        }
        return dir
    }
}
