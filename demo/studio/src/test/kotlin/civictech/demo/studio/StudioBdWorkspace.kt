package civictech.demo.studio

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Minimal beadsmirror recipe: real embedded Dolt, sandbox init, no remote or repo discovery. */
internal class StudioBdWorkspace(parent: Path) {
    val root: Path = Files.createDirectories(parent.resolve("studio"))
    init {
        try { run("--version") } catch (e: IOException) { assumeTrue(false, "bd is not on PATH; scratch Beads integration tests require bd (CI installs it): ${e.message}") }
        run("init")
    }
    fun run(vararg args: String): String {
        val stdout = Files.createTempFile(root.parent, "bd-out-", ".txt")
        val stderr = Files.createTempFile(root.parent, "bd-err-", ".txt")
        try {
            val builder = ProcessBuilder(listOf("bd", "--sandbox") + args).directory(root.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
            builder.environment().keys.removeIf { it.startsWith("BEADS_") || it.startsWith("DOLT_") }
            builder.environment().putAll(mapOf("BEADS_DIR" to root.resolve(".beads").toString(),
                "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_SYSTEM" to "/dev/null"))
            val process = builder.start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { destroyTree(process); error("scratch bd ${args.toList()} timed out") }
            check(process.exitValue() == 0) { "scratch bd ${args.toList()}: ${Files.readString(stderr)}\n${Files.readString(stdout)}" }
            return Files.readString(stdout)
        } finally { Files.deleteIfExists(stdout); Files.deleteIfExists(stderr) }
    }
    fun create(title: String = "scratch issue"): String = json.parseToJsonElement(
        run("create", "--title=$title", "--description=scratch only", "--type=task", "--json")).jsonObject.string("id")
}
