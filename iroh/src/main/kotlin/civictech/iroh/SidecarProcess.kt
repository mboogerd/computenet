package civictech.iroh

import java.io.BufferedReader
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A running sidecar child process and its handshake.
 *
 * `PROTOCOL.md` §1: the sidecar binds an ephemeral TCP port on `127.0.0.1` and
 * writes exactly **one** line to stdout —
 * `{"port":54321,"nodeId":"<64 lowercase hex>"}` — and nothing else ever goes to
 * stdout. This class spawns the binary, reads that one line, and hands out a
 * [SidecarClient] on the port it names.
 *
 * The handshake line is parsed by hand rather than through a serialization
 * framework: it is one line of fixed shape whose two fields are already
 * validated more strictly here (a decimal port in range, exactly 32 bytes of
 * lowercase hex) than a schema would validate them.
 */
class SidecarProcess private constructor(
    private val process: Process,
    /** The loopback TCP port the sidecar's host socket is bound to. */
    val port: Int,
    /** This sidecar's endpoint id: an ed25519 public key, 32 bytes. */
    val nodeId: ByteArray,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    @Volatile
    private var client: SidecarClient? = null

    /** This sidecar's endpoint id as the 64 lowercase hex characters §1 uses. */
    val nodeIdHex: String get() = nodeId.toHex()

    /** True while the child process is alive. */
    val isAlive: Boolean get() = process.isAlive

    /**
     * Connect the host socket. The sidecar serves one host connection at a time
     * (`PROTOCOL.md` §1), so this is called once per process in practice; the
     * most recent client is the one [close] shuts down.
     */
    fun connect(timeout: Duration = 30.seconds): SidecarClient =
        SidecarClient.connect(port, timeout).also { client = it }

    /**
     * Ask the sidecar to shut down, then make sure it is gone.
     *
     * `SHUTDOWN` on the host socket is the graceful path; the process is
     * destroyed if it has not exited within [grace], and forcibly destroyed if
     * it has not exited within [grace] again.
     */
    fun close(grace: Duration) {
        if (!closed.compareAndSet(false, true)) return
        client?.let { c ->
            runCatching { c.shutdown() }
            runCatching { c.close() }
        }
        if (!process.waitFor(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun close() = close(5.seconds)

    companion object {
        private val stderrPumps = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "iroh-sidecar-stderr").apply { isDaemon = true }
        }

        private val HANDSHAKE_PORT = Regex(""""port"\s*:\s*(\d+)""")
        private val HANDSHAKE_NODE_ID = Regex(""""nodeId"\s*:\s*"([0-9a-f]{64})"""")

        /**
         * The pure decision behind [spawn]'s `iroh.relay.url` steering: append
         * `--relay-url <relayUrl>` to [args] when [relayUrl] is non-null and
         * [args] contains neither `--offline` nor `--relay-url` already;
         * otherwise return [args] unchanged. Factored out of [spawn] so the
         * decision is unit-testable without spawning a process — see
         * `SidecarProcessArgsTest`.
         */
        internal fun effectiveArgs(args: List<String>, relayUrl: String?): List<String> {
            if (relayUrl == null) return args
            if (args.contains("--offline") || args.contains("--relay-url")) return args
            return args + listOf("--relay-url", relayUrl)
        }

        /**
         * Spawn [binary] and read its handshake line.
         *
         * @param stderrSink each stderr line of the child, for a test or a host
         *   that wants the diagnostics; the default discards them.
         * @param args extra command-line arguments for the child, passed through
         *   verbatim — the binary's own contract (`iroh/sidecar/src/main.rs`):
         *   `--offline`, `--secret-key <64 hex>`, `--bind-addr <ip:port>`,
         *   `--socket-port <port>`, `--relay-url <url>` (refused together with
         *   `--offline`). Empty by default, which is a fresh key on an
         *   ephemeral UDP port: an endpoint whose id changes every run. A caller
         *   that must bring the SAME endpoint back after its process died — the
         *   far side of a reconnect test — pins both with `--secret-key` and
         *   `--bind-addr`, so the NodeId a dialler was given and the addresses it
         *   was taught still name this endpoint after the restart.
         *
         *   When the JVM system property `iroh.relay.url` is set, and [args]
         *   contains neither `--offline` nor `--relay-url`, `--relay-url
         *   <property value>` is appended so every spawned sidecar can be
         *   steered onto one relay from a single JVM property — the choke
         *   point [effectiveArgs] this class narrows every JVM call site
         *   through. Explicit caller args always win: an [args] list that
         *   already names `--offline` or `--relay-url` is passed through
         *   unchanged. With the property unset, [args] is passed through
         *   byte-identical to before this parameter existed.
         * @throws SidecarException when the binary does not exist, exits before
         *   the handshake, or writes a line that is not `PROTOCOL.md` §1's.
         */
        fun spawn(
            binary: Path,
            handshakeTimeout: Duration = 60.seconds,
            stderrSink: (String) -> Unit = {},
            args: List<String> = emptyList(),
        ): SidecarProcess {
            val file: File = binary.toFile()
            if (!file.isFile) throw SidecarException("sidecar binary $binary does not exist")

            val effectiveArgs = effectiveArgs(args, System.getProperty("iroh.relay.url"))
            val process = ProcessBuilder(listOf(file.absolutePath) + effectiveArgs)
                .redirectErrorStream(false)
                .start()

            stderrPumps.execute {
                runCatching {
                    process.errorStream.bufferedReader().useLines { lines -> lines.forEach(stderrSink) }
                }
            }

            val line = readHandshakeLine(process, handshakeTimeout)
                ?: run {
                    process.destroyForcibly()
                    throw SidecarException("sidecar $binary produced no handshake line within $handshakeTimeout")
                }

            val port = HANDSHAKE_PORT.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val nodeIdHex = HANDSHAKE_NODE_ID.find(line)?.groupValues?.get(1)
            if (port == null || port !in 1..65535 || nodeIdHex == null) {
                process.destroyForcibly()
                throw SidecarException("sidecar $binary wrote a handshake line this host cannot read: $line")
            }
            val nodeId = nodeIdHex.hexToBytesOrNull()
                ?: run {
                    process.destroyForcibly()
                    throw SidecarException("sidecar $binary wrote a nodeId that is not 32 bytes of hex: $nodeIdHex")
                }
            return SidecarProcess(process, port, nodeId)
        }

        /**
         * Read the single stdout line, bounded. `BufferedReader.readLine` cannot
         * be interrupted, so the read runs on a daemon thread and the caller
         * waits on its result; a sidecar that never speaks leaves that thread
         * blocked on a stream the caller then destroys.
         */
        private fun readHandshakeLine(process: Process, timeout: Duration): String? {
            val reader: BufferedReader = process.inputStream.bufferedReader()
            val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
            val thread = Thread({
                runCatching { reader.readLine() }.getOrNull()?.let { result.offer(it) }
            }, "iroh-sidecar-handshake")
            thread.isDaemon = true
            thread.start()
            return result.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }
}
