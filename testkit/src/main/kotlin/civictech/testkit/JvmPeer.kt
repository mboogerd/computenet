package civictech.testkit

import org.opentest4j.AssertionFailedError
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Multi-JVM test scaffolding: launch a demo's `main` as a separate OS process on the
 * current test classpath, and learn the ports it **actually bound** from the process
 * itself. Canonical form taken from `TwoJvmConvergenceTest` /
 * `CrashRestartConvergenceTest` / `ExchangeScaffoldTest`, which share this exact
 * `launch` shape (only the hardcoded main-class FQN differed per demo —
 * parameterized here as [mainClass]).
 *
 * ## Why there is no `freePort()` any more (computenet-dqy.25)
 *
 * This object used to offer
 *
 * ```kotlin
 * fun freePort(): Int = ServerSocket(0).use { it.localPort }
 * ```
 *
 * and every multi-JVM demo test called it three to five times to pick the ports it
 * would then pass to a *freshly launched child JVM*. That asks the OS for an
 * ephemeral port, **closes it**, and binds it later somewhere else. Between the
 * close and that bind the port is unowned and sits in the OS ephemeral range that
 * every other `bind(0)` on the machine draws from, so any concurrent test JVM,
 * Gradle worker or unrelated process can take it first. Observed on PR #41 (run
 * 31473496092, `build-test-serial`): the launched `:demo:shopping` peer died with
 * `java.net.BindException: Address already in use` and the test then failed as
 * `timed out awaiting: both peers serving HTTP`, one line below the real cause.
 *
 * Neither a retry nor a longer timeout can fix that: `SO_REUSEADDR` already covers
 * `TIME_WAIT`, so a bind that keeps failing has a **live** competing holder, which
 * is sticky rather than unlucky (measured in computenet-dqy.22, which fixes the
 * in-process form of the same defect with a guard socket). And macOS never re-issues
 * a just-freed ephemeral port while Linux does, which is why the pattern looks
 * harmless on a dev box and reddens at random on Linux CI.
 *
 * So the guess is gone rather than narrowed. The child is launched with `0` for
 * every port it listens on — it performs the `bind(0)` **itself**, and there is no
 * instant between choosing and owning the port — and it prints each bound port in
 * one machine-readable line that [Peer.port] reads back:
 *
 * ```
 * computenet-port http 51234
 * ```
 *
 * The emitting side is `civictech.demo.shell.announcePort`; [PORT_LINE_PREFIX] is
 * this side of that two-process contract. A cross-process handover of a *held*
 * socket (an inherited file descriptor, or an `SO_REUSEPORT` overlap with a parent
 * guard) would also close the window, but it would make the child's listen path
 * differ from production for no gain: the child can talk to us before it needs its
 * ports, so it can simply be the one to choose them.
 *
 * ## Child output is buffered, not inherited
 *
 * The previous [launch] redirected the child to `Redirect.INHERIT`, and three demo
 * tests hand-rolled their own launcher purely to redirect to a per-process log file
 * instead — because Gradle's console renders a failed test's *exception*, never its
 * stdout, so inherited child output is invisible exactly when it matters. Every
 * launched peer's merged stdout/stderr is therefore buffered here, and folded into
 * the failure message of anything this object can fail: [Peer.port] and [await].
 * That is what makes a child that cannot bind fail the test **with the bind error**
 * instead of with a downstream timeout.
 */
object JvmPeer {

    /** The one line a launched peer prints per bound port: `computenet-port <name> <port>`. */
    const val PORT_LINE_PREFIX: String = "computenet-port "

    /**
     * Default bound for [Peer.port]: a cold JVM start plus graph construction on a
     * loaded CI runner, matching the 45s budgets the multi-JVM suites already use.
     */
    const val LAUNCH_TIMEOUT_MS: Long = 45_000

    /** Launch [mainClass] as a fresh JVM process, inheriting the current classpath. */
    fun launch(mainClass: String, vararg args: String): Peer {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), mainClass, *args)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        return Peer(described = (listOf(mainClass) + args).joinToString(" "), process = process)
    }

    /**
     * Await [condition], naming [what] on timeout **and** quoting every peer's
     * output — a peer that died is the diagnosis, and it is only readable here.
     */
    fun await(
        what: String,
        peers: Iterable<Peer>,
        timeoutMs: Long = LAUNCH_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        try {
            awaitUntil(what, timeoutMs, condition)
        } catch (e: AssertionError) {
            throw AssertionFailedError("${e.message}\n\n${peers.joinToString("\n\n") { it.report() }}", e)
        }
    }

    // destroy()/destroyForcibly() don't block, so a still-dying JVM from one
    // test can compete for CPU with the next test's fresh launches on a
    // CPU-constrained CI runner — waiting here avoids that bleed-over.
    fun destroy(peers: Iterable<Peer>) {
        peers.forEach { it.process.destroy() }
        peers.forEach { it.process.destroyForcibly() }
        peers.forEach { it.process.waitFor(10, TimeUnit.SECONDS) }
        peers.forEach { live.remove(it) }
    }

    fun destroy(vararg peers: Peer) = destroy(peers.asList())

    /**
     * A launched peer process: its buffered output, and the ports it announced.
     *
     * Ports are read from the child rather than chosen for it (see [JvmPeer]), so
     * every accessor here can fail, and every failure quotes the child's own output.
     */
    class Peer internal constructor(private val described: String, val process: Process) {

        private val lock = Object()
        private val lines = ArrayDeque<String>()
        private val ports = HashMap<String, Int>()

        /** Set once the child's output stream reaches EOF: no further port can appear. */
        private var ended = false

        init {
            live += this
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(lock) {
                            lines.addLast(line)
                            if (lines.size > MAX_BUFFERED_LINES) lines.removeFirst()
                            parsePort(line)?.let { (name, port) -> ports[name] = port }
                            lock.notifyAll()
                        }
                    }
                } catch (_: Exception) {
                    // a killed peer's stream closes mid-read; `ended` below is the signal
                } finally {
                    synchronized(lock) { ended = true; lock.notifyAll() }
                }
            }.apply { isDaemon = true; name = "jvm-peer-output" }.start()
        }

        /**
         * The port this peer bound and announced under [name] — `http`, `ws` or
         * `inspect` for the demos here.
         *
         * Fails with the peer's own output if it stopped without announcing, which is
         * where a `BindException` shows up: a peer that cannot bind fails the test
         * naming that, not a later "timed out awaiting …" that has to be traced back
         * to a child process's log.
         *
         * A failure here also stops *this* peer before it throws (see [failWith]) —
         * the callers read their ports before arming the `finally` that destroys
         * them, so a peer whose handshake failed would otherwise outlive the test
         * that launched it. Peers that already announced successfully are not
         * covered by that and remain the caller's `finally` to destroy; the shutdown
         * hook on [JvmPeer] is the backstop for both.
         */
        fun port(name: String, timeoutMs: Long = LAUNCH_TIMEOUT_MS): Int = synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (ports[name] == null) {
                if (ended) failWith(
                    "peer `$described` produced no more output without announcing its \"$name\" port " +
                        "(${aliveDescription()}) — its own output below is the diagnosis; a BindException " +
                        "there means the peer lost the port it was told to bind\n\n${report()}"
                )
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) failWith(
                    "timed out after ${timeoutMs}ms awaiting peer `$described` to announce its \"$name\" " +
                        "port (announced so far: ${ports.keys.sorted()})\n\n${report()}"
                )
                lock.wait(minOf(remaining, POLL_MS))
            }
            ports.getValue(name)
        }

        /**
         * Throw [message], having first stopped the peer it describes.
         *
         * [message] is composed by the caller, so the whole diagnosis — the peer's
         * buffered output and its live-or-exited status — is captured before
         * anything is killed. What the kill buys is that a *hung* peer (started,
         * announced nothing more, still running) does not keep running alongside
         * the rest of the suite: a module's test classes share one Gradle test JVM,
         * so an abandoned demo process would compete with every later test for CPU
         * and the shutdown hook below only reaps at JVM exit.
         */
        private fun failWith(message: String): Nothing {
            val error = AssertionFailedError(message)
            process.destroyForcibly()
            throw error
        }

        /** kill -9, for the tests whose subject is a peer dying mid-session. */
        fun kill() {
            process.destroyForcibly()
        }

        /** Everything the peer has written so far (stdout and stderr, interleaved as it wrote them). */
        fun output(): String = synchronized(lock) { lines.joinToString("\n") }

        /** The peer's output under a header naming it — what a failure message quotes. */
        fun report(): String =
            "---- peer `$described` (${aliveDescription()}) ----\n" +
                output().ifEmpty { "<no output>" }

        /**
         * `waitFor`, not `isAlive`: a peer whose output stream has reached EOF is a
         * peer that is exiting, but the exit status is not readable the instant the
         * pipe closes — `isAlive` was observed still true there, which reported a
         * dead peer as "still running". This is only ever called while composing a
         * failure, so a bounded pause to get the status right costs nothing.
         */
        private fun aliveDescription(): String =
            if (process.waitFor(1, TimeUnit.SECONDS)) "exited with status ${process.exitValue()}"
            else "still running, pid ${process.pid()}"

        private fun parsePort(line: String): Pair<String, Int>? {
            if (!line.startsWith(PORT_LINE_PREFIX)) return null
            val (name, port) = line.removePrefix(PORT_LINE_PREFIX).trim().split(" ", limit = 2)
                .takeIf { it.size == 2 } ?: return null
            return port.toIntOrNull()?.let { name to it }
        }
    }

    private const val MAX_BUFFERED_LINES = 1_000
    private const val POLL_MS = 200L

    /**
     * Every peer launched in this JVM, so a test that fails *between* two launches
     * cannot strand a child process on the runner past this JVM: the old shape put
     * both launches before its `try`, and reading a port from the child is a step
     * that can now legitimately fail.
     *
     * Note the "past this JVM" — the hook below only runs at exit, so it is the
     * backstop and not the answer. The answer for the peer that actually failed is
     * [Peer.failWith], which kills it there and then; a *healthy* peer launched
     * before its sibling's handshake failed is neither, and does run on until the
     * test JVM ends.
     */
    private val live: MutableSet<Peer> = Collections.newSetFromMap(ConcurrentHashMap())

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread { live.toList().forEach { runCatching { it.process.destroyForcibly() } } }
        )
    }
}
