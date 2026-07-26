package civictech.testkit

import java.io.File
import java.net.ServerSocket

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
}
