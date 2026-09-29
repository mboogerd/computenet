package civictech.economy

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `kwhw6-D12`: no wall clock is a test, not a grep. `economy/src/main` must stay free of any
 * wall-clock read — the same regex list `civictech.cell.architecture.NoClockFenceTest` fences
 * `kernel/src/main`'s `durability`/`host`/`control` packages with, applied here to the whole
 * of `:economy`'s main source set (this module is small enough that no narrower scope is
 * needed). A `System.err.println` call (`StartupRecord.emit`, F2 T4) is not a clock read and
 * does not match any of these patterns.
 */
class NoWallClockTest {

    private val forbidden = listOf(
        Regex("""\bSystem\s*\.\s*nanoTime\b"""),
        Regex("""\bSystem\s*\.\s*currentTimeMillis\b"""),
        Regex("""\bjava\.time\.Clock\b"""),
        Regex("""\bClock\s*\.\s*system"""),
        Regex("""\bInstant\s*\.\s*now\b"""),
        Regex("""\bTimeSource\b"""),
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /** Strips `//` line comments and `/* ... */`/KDoc block comments, leaving code only. */
    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < source.length) {
                        sb.append(source[i + 1]); i += 2; continue
                    }
                    if (c == '"') inString = false
                    i++
                }
                c == '"' -> {
                    inString = true; sb.append(c); i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    sb.append(c); i++
                }
            }
        }
        return sb.toString()
    }

    @Test
    fun `economy src main contains no clock or wall-time source outside comments`() {
        val economyMain = File(repoRoot(), "economy/src/main/kotlin")
        val violations = mutableListOf<String>()
        var scanned = 0

        economyMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            scanned++
            val code = stripComments(file.readText())
            forbidden.forEach { pattern ->
                if (pattern.containsMatchIn(code)) {
                    violations += "${file.relativeTo(repoRoot())}: matched ${pattern.pattern}"
                }
            }
        }

        // Non-vacuity control: without it, a walk over an empty or missing directory would
        // make the assertion below pass vacuously.
        assertTrue(scanned > 0, "scanned zero .kt files under $economyMain")

        assertTrue(
            violations.isEmpty(),
            "economy/src/main must stay clock-free (kwhw6-D12); found:\n" + violations.joinToString("\n"),
        )
    }
}
