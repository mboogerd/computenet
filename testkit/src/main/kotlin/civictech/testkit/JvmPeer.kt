package civictech.testkit

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Multi-JVM test scaffolding: launch a demo's `main` as a separate OS process on the
 * current test classpath. Canonical form taken from `TwoJvmConvergenceTest` /
 * `CrashRestartConvergenceTest` / `ExchangeScaffoldTest`, which share this exact
 * `launch`/`freePort` shape (only the hardcoded main-class FQN differed per demo —
 * parameterized here as [mainClass]).
 */
object JvmPeer {

    /** An available TCP port on localhost, for handing to a launched peer as a listen arg. */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Launch [mainClass] as a fresh JVM process, inheriting the current classpath. */
    fun launch(mainClass: String, vararg args: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        return ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), mainClass, *args
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
    }

    // destroy()/destroyForcibly() don't block, so a still-dying JVM from one
    // test can compete for CPU with the next test's fresh launches on a
    // CPU-constrained CI runner — waiting here avoids that bleed-over.
    fun destroy(vararg processes: Process) {
        processes.forEach { it.destroy() }
        processes.forEach { it.destroyForcibly() }
        processes.forEach { it.waitFor(10, TimeUnit.SECONDS) }
    }
}
