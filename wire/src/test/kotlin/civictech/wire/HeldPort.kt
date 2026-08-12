package civictech.wire

import civictech.cell.wire.Peering
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel

/**
 * A TCP port this test holds from its first line to its last, so a listener can
 * die and come back on the **same** port without the port ever being unbound.
 *
 * ## Why this exists (computenet-dqy.22)
 *
 * Three tests need the shape "the peer's listener process dies and returns at
 * the same address". All three used to express it by stopping the listener and
 * then asking the OS for that exact port number back, absorbing the failures
 * with a 20-attempt retry loop whose own comment admitted it raced the OS. It
 * lost that race in CI on `WsReconnectLoopBoundTest` (PR #37, run 31469561371):
 * `could not re-bind port 37973 after 20 attempts`, `BindException: Address
 * already in use`, in `build-test-fast` — while the same commit passed the
 * serial lane.
 *
 * The retry loop cannot fix it, and the measurements say why:
 *
 * - Both the probe bind and `WsTransport.listen(port)` ask for `SO_REUSEADDR`
 *   whenever `port != 0`, and `SO_REUSEADDR` is exactly the option that admits a
 *   port still in `TIME_WAIT`. So `TIME_WAIT` was already handled, and cannot be
 *   what 20 consecutive attempts hit.
 * - What `SO_REUSEADDR` does *not* admit is a **live** socket holding the port.
 *   Measured: with an ordinary (not even reusing) holder bound to the wildcard
 *   address, the probe loop fails 20/20 attempts across the full 1s. A live
 *   holder is sticky for as long as it lives, so retrying is futile by
 *   construction rather than merely unlucky.
 * - A freed port sits in the OS ephemeral range (the failing 37973 is inside
 *   Linux's default 32768-60999), which is precisely the pool every other
 *   `bind(0)` on the machine draws from. `build-test-fast` runs sibling module
 *   test JVMs concurrently (`org.gradle.parallel=true`); the serial lane, which
 *   passed on the identical commit, does not. Handing a port from that range
 *   back to the OS and then demanding it again is a race against every
 *   concurrent process on the runner.
 *
 * So the fix is not a better retry: it is never to unbind the port. This class
 * holds it with a **guard** socket and hands it to and from a listener with no
 * unbound instant in between:
 *
 * - `SO_REUSEPORT` is what makes a gapless handover possible: two sockets that
 *   both set it may bind the same port, so [serve] binds the listener's channel
 *   *while the guard still holds the port* and only then closes the guard, and
 *   [release] re-binds the guard *while the listener still holds the port* and
 *   only then stops it.
 * - The port stays unstealable throughout. Measured: while a `SO_REUSEPORT`
 *   socket holds the port, an ordinary `bind()` and an `SO_REUSEADDR` `bind()`
 *   from elsewhere both fail — `SO_REUSEPORT` binds need the option on *every*
 *   socket in the group and the same effective user, so no other process's
 *   `bind(0)` can take it away.
 *
 * ## The one behavioural difference, stated plainly
 *
 * A held port cannot answer with `ECONNREFUSED`: a refusal is what the kernel
 * sends when *nothing* is bound, which is the state this class exists to avoid.
 * While no listener is serving, the guard accepts each attempt and resets it
 * immediately (`SO_LINGER 0`), so a dialer's reconnect attempt fails at once
 * with `ECONNRESET` instead of `ECONNREFUSED`.
 *
 * That preserves what the three tests actually assert, because the property
 * under test is about java-websocket's *close* callback, not about which errno
 * arrived: `WebSocketClient` drives `closeConnection` for a failed attempt
 * either way, so `onClose` still fires once per unsuccessful attempt — the
 * trigger of the per-close retry-thread defect that `WsReconnectLoopBoundTest`
 * guards. Verified rather than argued: with `WsConnection`'s `reconnecting`
 * single-flight guard removed, that test still fails against a `HeldPort`
 * endpoint (hundreds of `ws-reconnect-*` threads), so the regression gate is
 * intact.
 *
 * The one thing that difference does break is `WsTransport.connect`'s *initial*
 * reachability probe: it treats a completed TCP connect as "the listener is up",
 * and the guard completes one. Measured: the probe returns immediately against a
 * bare guard, where an unbound port would have given it `ECONNREFUSED` to wait
 * on. So **call [serve] before `WsTransport.connect`** — every test here does,
 * and the alternative is not a hang but a confusing `could not connect to ws://…`
 * ten seconds later. Reconnects are unaffected: those go through `onClose`, which
 * a reset drives exactly as a refusal does.
 *
 * Superseded by this class: T12 finding 4's "probe with a throwaway
 * `SO_REUSEADDR` bind, then retry the real listen". Its diagnosis — that the
 * OS is the adversary here — was right; its remedy assumed the adversary was
 * `TIME_WAIT`, which `SO_REUSEADDR` had already covered.
 */
class HeldPort : AutoCloseable {

    private val lock = Any()

    /** Non-null exactly while no listener owns the port. */
    private var guard: Guard? = Guard.bind(0)

    /** The held port: stable for this object's whole lifetime. */
    val port: Int = guard!!.port

    /**
     * Bind [side]'s listener onto the held port and start serving.
     *
     * The listener's channel is bound before the guard lets go, so the port is
     * never unbound. A connect that lands on the guard during that overlap is
     * reset and the dialer retries, which is the ordinary reconnect path.
     */
    fun serve(side: Peering.Side): WsTransport.WsListener = synchronized(lock) {
        val held = checkNotNull(guard) { "the held port is already serving a listener" }
        val channel = reusePortChannel()
        try {
            channel.bind(InetSocketAddress(port), BACKLOG) // the guard still holds it: SO_REUSEPORT
        } catch (e: IOException) {
            channel.close()
            throw IllegalStateException("could not bind the held port $port alongside its guard", e)
        }
        val listener = try {
            WsTransport.listen(channel, side)
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
        held.close() // only now: the listener is already bound and accepting
        guard = null
        listener
    }

    /**
     * Stop [listener] while keeping the port bound — the peer's "process death".
     *
     * The guard takes the port back before the listener gives it up, so there is
     * no instant in which another process could bind it.
     */
    fun release(listener: WsTransport.WsListener, timeoutMs: Int = 1000) = synchronized(lock) {
        check(guard == null) { "no listener holds the port" }
        guard = Guard.bind(port) // succeeds while the listener still holds it
        listener.stop(timeoutMs)
    }

    override fun close() = synchronized(lock) {
        guard?.close()
        guard = null
    }

    /**
     * The socket that holds the port when no listener does, refusing as visibly
     * as a held port can: accept, reset (`SO_LINGER 0`), forget.
     */
    private class Guard(private val channel: ServerSocketChannel) : AutoCloseable {

        val port: Int = (channel.localAddress as InetSocketAddress).port

        private val thread = Thread {
            while (channel.isOpen) {
                val accepted = try {
                    channel.accept() ?: continue
                } catch (_: ClosedChannelException) {
                    break
                } catch (_: IOException) {
                    break
                }
                try {
                    // RST rather than FIN: a dialer must see this attempt fail
                    // now, not sit in a half-open handshake with a dead peer
                    accepted.setOption(StandardSocketOptions.SO_LINGER, 0)
                } catch (_: IOException) {
                    // best effort — the close below still fails the attempt
                } finally {
                    runCatching { accepted.close() }
                }
            }
        }.apply { isDaemon = true; name = "held-port-guard-$port" }

        override fun close() {
            runCatching { channel.close() } // unblocks the accept loop
            thread.join(1_000)
        }

        companion object {
            fun bind(port: Int): Guard {
                val channel = reusePortChannel()
                try {
                    channel.bind(InetSocketAddress(port), BACKLOG)
                } catch (e: IOException) {
                    channel.close()
                    throw IllegalStateException("could not hold port $port", e)
                }
                return Guard(channel).also { it.thread.start() }
            }
        }
    }

    private companion object {
        /**
         * Small but not 1: the guard must complete a dialer's handshake to reset
         * it, and a zero-backoff reconnect loop can offer several at once.
         */
        const val BACKLOG = 16

        fun reusePortChannel(): ServerSocketChannel {
            val channel = ServerSocketChannel.open()
            check(channel.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
                "HeldPort needs SO_REUSEPORT to hand a port over without unbinding it; " +
                    "this JDK/OS does not offer it (Linux and macOS both do)"
            }
            // before bind, as the option requires
            channel.setOption(StandardSocketOptions.SO_REUSEPORT, true)
            return channel
        }
    }
}
