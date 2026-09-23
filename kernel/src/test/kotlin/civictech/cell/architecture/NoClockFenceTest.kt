package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * BS-32 (`computenet-t6b.2.5`, `[KBLK-25]`): P1 (`doc/spec/00-foundations/02-design-principles.md`,
 * no wall clock) as a guard test, scoped to exactly the packages this KBLK ticket family
 * touches — `civictech.cell.durability`, `civictech.cell.host`, `civictech.cell.control` — not
 * to all of `kernel/src/main`.
 *
 * That scoping is deliberate, not an oversight: `civictech.cell.wire`
 * (`AnnouncementSigning.kt`, `AnnouncementAdmission.kt`) already carries an injected skew
 * clock (`clock: () -> Long = System::currentTimeMillis`) for an operational check decided
 * elsewhere, outside KBLK's remit. A whole-`kernel/src/main` fence would fail today for a
 * reason this ticket does not own and must not change. A later ticket that wants the fence
 * widened (or an existing package narrowed out of it) does so explicitly, in this file, with
 * its own citation — never by silently editing the package list below to make a build pass.
 *
 * "The natural and wrong instinct" this guards against (per the ticket's own mutation list):
 * injecting `System.nanoTime()`/a `Clock` into [civictech.cell.host.SimulationController] or
 * its [civictech.cell.host.AttentionScheduler] to benchmark or rate-limit the deterministic
 * drive seam. That seam's whole value is that two runs with the same seed retrace identically
 * — a wall clock reading is exactly the kind of non-replayable input [KBLK-25] rules out here.
 */
class NoClockFenceTest {

    private val fencedPackages = listOf("durability", "host", "control")

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
    fun `the fenced packages contain no clock or wall-time source outside comments`() {
        val kernelMain = File(repoRoot(), "kernel/src/main/kotlin")
        val cellRoot = File(kernelMain, "civictech/cell")
        val violations = mutableListOf<String>()

        fencedPackages.forEach { pkg ->
            val pkgRoot = File(cellRoot, pkg)
            if (!pkgRoot.isDirectory) return@forEach
            pkgRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val code = stripComments(file.readText())
                forbidden.forEach { pattern ->
                    if (pattern.containsMatchIn(code)) {
                        violations += "${file.relativeTo(repoRoot())}: matched ${pattern.pattern}"
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "civictech.cell.{durability,host,control} must stay clock-free (P1, [KBLK-25]); found:\n" +
                violations.joinToString("\n"),
        )
    }
}
