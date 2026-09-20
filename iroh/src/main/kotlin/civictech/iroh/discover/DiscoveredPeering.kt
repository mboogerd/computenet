package civictech.iroh.discover

import civictech.cell.DenialReason
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.iroh.HelloGate
import civictech.iroh.IrohNode
import civictech.iroh.IrohTransport
import civictech.iroh.LinkDirection
import civictech.iroh.PeerWatchListener
import civictech.iroh.Verdict
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How many keys this policy retains, how many it dials at once, and when it
 * dials them again (F3-D1, [DSC2-DIAL-03], [DSC2-MDNS-05]).
 *
 * @param maxRetained the bound on [PeerTable] entries a LAN flood can create.
 * @param maxInFlightDials both the dial-pool size and the bound on `Dialling`
 *   entries. They are one number on purpose: a dial *blocks its thread* until
 *   the link is up or the timeout expires (`SidecarClient.dial`), so a bound on
 *   in-flight dials that was not also the thread count would either starve or
 *   queue behind itself.
 * @param refusedDialLimit consecutive unadmitted opens after which a key is
 *   abandoned. Enforced inside the connection, not here (F3-D4).
 * @param schedule the backoff, in milliseconds, before retry [attempt]. The
 *   only source of delay in this package, together with the [DialTimer] that
 *   realises it.
 * @param dialTimeout how long one `openLink` may block before it counts as a
 *   failed dial.
 */
data class DialPolicy(
    val maxRetained: Int = 1024,
    val maxInFlightDials: Int = 4,
    val refusedDialLimit: Int = IrohTransport.REFUSED_DIAL_LIMIT,
    val schedule: (attempt: Int) -> Long = IrohTransport.DEFAULT_RECONNECT_BACKOFF,
    val dialTimeout: Duration = 30.seconds,
) {
    init {
        require(maxInFlightDials > 0) { "maxInFlightDials must be positive, was $maxInFlightDials" }
    }
}

/**
 * Discovery events in, bounded dials out: the policy loop that turns a
 * sidecar's `PEER_DISCOVERED`/`PEER_EXPIRED` stream into peerings on an
 * [IrohNode] (DSC2 feature `computenet-ktn1l`, task `.3`; F3-D1, F3-D3, F3-D4,
 * F3-D8, F3-D9).
 *
 * It owns no transport mechanics and no state machine. [IrohNode] holds the
 * links; [PeerTable] holds what each key is doing and decides every transition;
 * this class owns the **threads, the queue and the timer** that connect the
 * two, and nothing else. That split is the whole design: the table is pure and
 * exhaustively testable, and the concurrency lives here where it can be stated
 * in one place.
 *
 * ## The thread rule, which is the point of this class
 *
 * Three kinds of thread, all daemon, none of them the kernel's scheduler
 * ([DSC2-NEU-04], ktn1l-D17):
 *
 * 1. **The sidecar reader thread** (`iroh-sidecar-reader`, owned by
 *    `SidecarClient`). Every callback this class registers on it — the
 *    [PeerWatchListener] and the [IrohNode.NodeLinkListener] — does exactly
 *    one thing: `queue.offer(...)`. **It never dials, never blocks and never
 *    takes a lock the policy thread holds.** A dial issued from the reader
 *    thread would wait for a `LINK_UP` that only the reader thread can
 *    deliver: an immediate, total deadlock of the endpoint. Every enqueue-only
 *    callback in this file is marked `// ENQUEUE ONLY` for that reason, and
 *    `DiscoveredPeeringTest` pins it.
 * 2. **One policy thread** (`iroh-discover-policy`). It drains the queue and
 *    is the *only* thread that applies a table transition, arms or cancels a
 *    timer, or submits a dial. Single, so the table's transitions are
 *    serialised by construction rather than by argument.
 * 3. **A dial pool** of [DialPolicy.maxInFlightDials] threads
 *    (`iroh-discover-dial-N`). Each runs one blocking `openLink` and posts the
 *    result back onto the queue.
 *
 * The **one** deliberate exception is the [HelloGate] this class installs. A
 * hello must be judged synchronously, on the reader thread, because the
 * verdict decides what is written next on that very link — there is nothing to
 * come back to later. It is a single lock-guarded call into [PeerTable.judge]
 * plus counter increments: no IO, no dial, no wait, and no lock this class
 * holds across anything else. That is why [PeerTable] is pure.
 *
 * ## Time
 *
 * Every "when" is [clock] plus [DialPolicy.schedule]; every "later" is the
 * injected [DialTimer]. There is no `System.currentTimeMillis()` and no
 * `Thread.sleep` in this package, production or test ([DSC2-DIAL-08]).
 *
 * ## Identity
 *
 * This package reads no allowlist and constructs no [PeerId]
 * ([DSC2-ID-01..04]). A discovered stranger is *dialled*, and its hello is
 * refused inside `Session` exactly as any other hello would be; the refusals
 * arrive here only as accounting — an abandoned connection and a
 * [DenialReason] to record. The only `PeerId` this class ever touches is one a
 * `Session` already stamped and handed to it.
 */
class DiscoveredPeering private constructor(
    private val node: IrohNode,
    private val policy: DialPolicy,
    private val clock: () -> Long,
    private val timer: DialTimer,
) : AutoCloseable {

    /** The state machine. Pure; every call below is on the policy thread or under the gate. */
    private val table = PeerTable(node.nodeId, maxRetained = policy.maxRetained, clock = clock)

    /** @see DiscoveryCounters — populated here, read by anyone ([DSC2-OBS-01..03]). */
    val counters: DiscoveryCounters = DiscoveryCounters(
        keysRetained = { table.keysRetained },
        malformedEventSource = { node.client.malformedDiscoveryEvents },
    )

    /**
     * One [IrohTransport.IrohConnection] per discovered key, for that key's
     * whole life here (F3-D4).
     *
     * Kept rather than rebuilt per dial because the connection is what carries
     * the *run*: its `unadmittedOpens` and `abandonedAfterRefusals` are
     * consecutive-refusal accounting, and a fresh connection per attempt would
     * reset the run and never reach [DialPolicy.refusedDialLimit].
     */
    private val connections = ConcurrentHashMap<NodeKey, IrohTransport.IrohConnection>()

    private val queue = LinkedBlockingQueue<Command>()

    /** Armed retries, by key. Written and read **only** on the policy thread. */
    private val armed = HashMap<NodeKey, AutoCloseable>()

    private val running = AtomicBoolean(true)

    private val dialThreads = AtomicInteger()

    private val dialPool: ExecutorService =
        Executors.newFixedThreadPool(policy.maxInFlightDials) { runnable ->
            Thread(runnable, "iroh-discover-dial-${dialThreads.incrementAndGet()}").apply { isDaemon = true }
        }

    private val policyThread = Thread({ drain() }, "iroh-discover-policy").apply { isDaemon = true }

    // -------------------------------------------------------------- the API

    /** Every retained key as an observability surface sees it. @see PeerTable.snapshot */
    fun snapshot(): List<PeerView> = table.snapshot()

    /**
     * Stop the policy: no further events are acted on, every armed retry is
     * cancelled, the dial pool is shut down and every connection this policy
     * opened is closed.
     *
     * The [node] is **not** closed — it is the caller's, and the connections
     * here hold `ownsClient = false` precisely so that closing them leaves the
     * endpoint usable.
     */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        queue.offer(Command.Stop)
        policyThread.join(CLOSE_JOIN_MILLIS)
        synchronized(armed) { armed.values.forEach { runCatching { it.close() } }; armed.clear() }
        runCatching { timer.shutdown() }
        dialPool.shutdownNow()
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
    }

    /**
     * The connection this policy holds for [key], while it holds one.
     * Internal: it reaches a connection's refusal accounting
     * ([IrohTransport.IrohConnection.unadmittedOpens]), which is what a test
     * of the abandonment rule has to read.
     */
    internal fun connectionFor(key: NodeKey): IrohTransport.IrohConnection? = connections[key]

    // ------------------------------------------------------------- commands

    /**
     * Everything the policy thread acts on. One type, one queue, one order —
     * so "what happened first" is a fact about the queue rather than a race
     * between the reader thread and a dial thread.
     */
    private sealed interface Command {
        class Discovered(val key: NodeKey, val addresses: List<String>) : Command
        class Expired(val key: NodeKey) : Command
        class LinkUp(val view: IrohNode.LinkView) : Command
        class Admitted(val view: IrohNode.LinkView) : Command
        class LinkDown(val view: IrohNode.LinkView, val outcome: IrohTransport.IrohConnection.LinkOutcome?) : Command

        /** One `openLink` finished. [success] is whether it produced a link, not whether it was admitted. */
        class DialDone(val key: NodeKey, val success: Boolean, val failure: String?) : Command

        /** Close the link this key's tie-break lost, off the reader thread (aas-D7). */
        class CloseLoser(val key: NodeKey, val linkId: Long) : Command

        /** A key the gate superseded: its armed retry is no longer wanted (F3-D6). */
        class CancelRetry(val key: NodeKey) : Command

        /** A timer fired, or something else wants the dial schedule re-examined. */
        data object Due : Command

        /** [close] was called. */
        data object Stop : Command
    }

    /** ENQUEUE ONLY — safe from any thread, including the reader thread. */
    private fun post(command: Command) {
        if (running.get()) queue.offer(command)
    }

    // -------------------------------------------------------- policy thread

    private fun drain() {
        while (running.get()) {
            val command = try {
                queue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (command === Command.Stop) return
            runCatching { apply(command) }.onFailure { failure ->
                System.err.println("[DiscoveredPeering] policy step failed: $failure")
            }
            pump()
        }
    }

    private fun apply(command: Command) = when (command) {
        is Command.Discovered -> onDiscovered(command)
        is Command.Expired -> onExpired(command)
        is Command.LinkUp -> onLinkUp(command)
        is Command.Admitted -> onAdmitted(command)
        is Command.LinkDown -> onLinkDown(command)
        is Command.DialDone -> onDialDone(command)
        is Command.CloseLoser -> closeLink(command.key, command.linkId)
        is Command.CancelRetry -> cancelRetry(command.key)
        Command.Due -> Unit
        Command.Stop -> Unit
    }

    private fun onDiscovered(command: Command.Discovered) {
        counters.eventsReceived.increment()
        when (val observation = table.observe(command.key, command.addresses, clock())) {
            Observation.Self -> {
                // BS-12, [DSC2-DIAL-04]: never dialled, never retained. The
                // table dropped it before an entry existed.
                counters.selfDropped.increment()
            }

            Observation.Dialable -> Unit

            is Observation.Suppressed -> counters.duplicatesSuppressed.increment()

            is Observation.Evicted -> counters.evicted.increment()

            Observation.Rejected ->
                System.err.println(
                    "[DiscoveredPeering] ${command.key.short} not retained: every one of the " +
                        "${policy.maxRetained} entries is live or configured",
                )
        }
    }

    private fun onExpired(command: Command.Expired) {
        // [DSC2-DIAL-06]: an expiry only cancels a retry when the table agrees
        // the key has nothing live on it — a key with a link up is not expired
        // just because the LAN stopped advertising it.
        if (table.expire(command.key)) cancelRetry(command.key)
    }

    private fun onLinkUp(command: Command.LinkUp) {
        val view = command.view
        table.linkUp(NodeKey(view.remoteNodeId), view.direction, view.linkId, sourceOf(view.source))
    }

    private fun onAdmitted(command: Command.Admitted) {
        val view = command.view
        val key = NodeKey(view.remoteNodeId)
        val peer = view.attributedPeer ?: return
        // [DSC2-DIAL-01]/[DSC2-DIAL-07]: an ACCEPTED or CONFIGURED link makes
        // the key peered exactly as a discovered one does, which is what makes
        // a later PEER_DISCOVERED for it Suppressed rather than a second dial.
        table.linkUp(key, view.direction, view.linkId, sourceOf(view.source))
        table.admitted(key, view.linkId, peer)
    }

    private fun onLinkDown(command: Command.LinkDown) {
        val view = command.view
        val key = NodeKey(view.remoteNodeId)
        val outcome = command.outcome
        if (outcome?.quiet == true) {
            // A link this side closed blame-free because it lost the
            // mutual-dial tie-break. Counted here rather than at the gate for
            // an OUTBOUND link, so each closed link moves the counter once;
            // see toVerdict for the INBOUND half.
            counters.tieBreakClosed.increment()
        }
        // One refused hello, counted once ([DSC2-ID-01..04], BS-05a). Charged
        // at the DOWN rather than at the refusal, because an outbound link's
        // refusal is recorded inside its `Session` and reaches this class only
        // as the outcome's `lastDenial` — including refusals the allowlist
        // took, which never reach the gate at all (ktn1l-D12). A quiet close
        // carries no blame and is never counted here. @see toVerdict for the
        // inbound half, which has no connection to report an outcome.
        val reason = if (outcome?.quiet == true) null else outcome?.lastDenial?.reason
        if (reason != null) counters.refused(reason)
        val outcomeOfDown = table.linkDown(key, view.linkId, clock())
        if (outcome?.abandoned == true) {
            table.abandon(key, reason)
            System.err.println(
                "[DiscoveredPeering] ${key.short} abandoned after ${policy.refusedDialLimit} " +
                    "unadmitted opens (last denial: $reason)",
            )
            cancelRetry(key)
            return
        }
        if (outcomeOfDown === DownOutcome.Redial) {
            // [DSC2-DIAL-06]: back in the dialable set, due now. pump() runs
            // right after every command and will pick it up.
            cancelRetry(key)
        }
    }

    private fun onDialDone(command: Command.DialDone) {
        if (command.success) return // The link is up; Admitted or LinkDown says what became of it.
        counters.dialsFailed.increment()
        val dueAt = table.dialFailed(command.key, clock(), policy.schedule) ?: return
        arm(command.key, dueAt - clock())
    }

    /**
     * Submit a dial for every key the table says is due, up to the in-flight
     * bound ([DSC2-DIAL-03]). Runs after **every** command, so a freed slot,
     * an expired backoff and a fresh sighting all reach the same one place.
     */
    private fun pump() {
        if (!running.get()) return
        for (key in table.nextDue(clock(), policy.maxInFlightDials)) {
            val attempt = (table.stateOf(key) as? PeerState.Retained)?.attempt ?: 0
            if (!table.markDialling(key, attempt)) continue
            val connection = connections.computeIfAbsent(key) {
                node.dialDiscovered(
                    peerNodeId = key.bytes,
                    redialTimeout = policy.dialTimeout,
                    refusedDialLimit = policy.refusedDialLimit,
                    // Required non-null, and deliberately empty: passing a
                    // delegate is what stops the connection from re-dialling
                    // on its own loop (`IrohTransport.retire`), while the
                    // outcome itself has ALREADY reached this class through
                    // the node's LinkObserver — `reportUnplanned` calls the
                    // observer first and this second, with the same
                    // LinkOutcome. Acting here too would process every
                    // discovered down twice.
                    onUnplannedDown = { },
                )
            }
            counters.dialsAttempted.increment()
            dialPool.execute {
                val result = runCatching { connection.openLink(policy.dialTimeout) }
                post(Command.DialDone(key, result.isSuccess, result.exceptionOrNull()?.message))
            }
        }
    }

    private fun arm(key: NodeKey, delayMs: Long) {
        cancelRetry(key)
        val handle = timer.schedule(delayMs) { post(Command.Due) } // ENQUEUE ONLY — runs on the timer thread.
        synchronized(armed) { armed[key] = handle }
    }

    private fun cancelRetry(key: NodeKey) {
        synchronized(armed) { armed.remove(key) }?.let { runCatching { it.close() } }
    }

    private fun closeLink(key: NodeKey, linkId: Long) {
        val closed = runCatching { node.client.link(linkId)?.close() }
        if (closed.isFailure) System.err.println("[DiscoveredPeering] closing ${key.short}'s losing link failed: ${closed.exceptionOrNull()}")
    }

    // ------------------------------------------------------------- the gate

    /**
     * [PeerTable.judge]'s answer, as a hello verdict — the one synchronous
     * step this class takes on the reader thread.
     *
     * Only the plain [Judgement.Admit] arm is exercised by task `.3`: the
     * tie-break and supersession arms need two nodes to reach, and task `.4`
     * proves them. They are mapped here, and mapped *completely*, because a
     * `TODO` in a verdict is a link left open.
     */
    private fun toVerdict(judgement: Judgement, direction: LinkDirection, key: NodeKey): Verdict = when (judgement) {
        is Judgement.Admit -> {
            if (judgement.close != null && judgement.closeLinkId != null) {
                // Off the reader thread: closing a link writes a frame.
                post(Command.CloseLoser(judgement.close, judgement.closeLinkId))
            }
            Verdict.Admit
        }

        Judgement.CloseQuietly -> {
            // An INBOUND link has no dialling connection and therefore reports
            // no LinkOutcome, so its tie-break loss is counted here; an
            // OUTBOUND one is counted at its down, where `outcome.quiet` says
            // the same thing. Exactly one of the two fires per closed link.
            if (direction == LinkDirection.INBOUND) counters.tieBreakClosed.increment()
            Verdict.CloseQuietly("tie-break loser for ${key.short} (aas-D7)")
        }

        is Judgement.Supersede -> {
            counters.superseded.increment()
            post(Command.CancelRetry(judgement.oldKey))
            Verdict.Admit
        }

        is Judgement.Refuse -> {
            // Inbound only, for the reason given at onLinkDown: an accepted
            // link has no dialling connection, so its refusal is reported
            // nowhere else. An outbound one is counted at its down, where the
            // outcome carries this very denial.
            if (direction == LinkDirection.INBOUND) counters.refused(judgement.reason)
            Verdict.Refuse(
                judgement.reason,
                judgement.live,
                "a live link for ${key.short} is attributed to another identity ([DSC2-ID-05])",
            )
        }
    }

    /**
     * The link id of the hello being judged.
     *
     * [HelloGate] carries no link id — it is handed the key, the direction and
     * the resolved identity — while [PeerTable.judge] arbitrates *between
     * links* and cannot work without one. The node's registry has it: the link
     * exists before its hello is read (`IrohNode.up` runs first on both
     * directions), so this lookup always finds it. The not-yet-peered link is
     * preferred because the one being judged is by definition not admitted
     * yet, which is what distinguishes it from a live link of the same key and
     * direction. [NO_LINK] is the honest answer when the registry has nothing,
     * and [PeerTable] treats it as an id that matches no link.
     *
     * A link id on [HelloGate.judge] would make this exact; it is task `.1`'s
     * surface and is reported rather than changed here.
     */
    private fun linkIdFor(remoteNodeId: ByteArray, direction: LinkDirection): Long {
        val candidates = node.links(remoteNodeId).filter { it.direction == direction }
        return (candidates.firstOrNull { !it.peered } ?: candidates.firstOrNull())?.linkId ?: NO_LINK
    }

    // ----------------------------------------------------------- start-up

    private fun begin() {
        policyThread.start()
        node.onLinkEvent(object : IrohNode.NodeLinkListener {
            // ENQUEUE ONLY — every one of these runs on the sidecar reader
            // thread (onUp, for an outbound link, on the dial thread).
            override fun onUp(link: IrohNode.LinkView) = post(Command.LinkUp(link))
            override fun onAdmitted(link: IrohNode.LinkView) = post(Command.Admitted(link))
            override fun onDown(link: IrohNode.LinkView, outcome: IrohTransport.IrohConnection.LinkOutcome?) =
                post(Command.LinkDown(link, outcome))
        })
        node.gate = HelloGate { _: KeyId, remoteNodeId: ByteArray, direction: LinkDirection, resolved: PeerId ->
            // The ONE synchronous consult on the reader thread (ktn1l-D17):
            // one lock-guarded O(1) table call and counter increments. No IO,
            // no dial, no wait — anything else here stops the endpoint.
            val key = NodeKey(remoteNodeId)
            toVerdict(table.judge(key, direction, linkIdFor(remoteNodeId, direction), resolved, clock()), direction, key)
        }
        node.client.watchPeers(object : PeerWatchListener {
            // ENQUEUE ONLY — the sidecar reader thread delivers both of these.
            override fun onDiscovered(nodeId: ByteArray, addresses: List<String>) =
                post(Command.Discovered(NodeKey(nodeId), addresses))

            override fun onExpired(nodeId: ByteArray) = post(Command.Expired(NodeKey(nodeId)))
        })
    }

    companion object {
        /** No link of this key. [PeerTable.linkDown] matches no entry on it, by construction. */
        internal const val NO_LINK: Long = -1L

        private const val CLOSE_JOIN_MILLIS: Long = 5_000

        /**
         * Install the policy on [node] and start watching for peers.
         *
         * Blocks until the sidecar answers `WATCH_PEERS` with `WATCHING`; the
         * listener is registered before the request goes out, so no event can
         * be lost in between (`SidecarClient.watchPeers`).
         *
         * The [node] stays the caller's to close. [DiscoveredPeering.close]
         * ends this policy and nothing else.
         *
         * @param clock the only source of time. Injected, not defaulted away:
         *   a test drives it by hand and nothing here reads a wall clock.
         * @param timer how a delay becomes a wake-up. @see DialTimer
         */
        fun start(
            node: IrohNode,
            policy: DialPolicy = DialPolicy(),
            clock: () -> Long = System::currentTimeMillis,
            timer: DialTimer = DialTimer.threaded(),
        ): DiscoveredPeering = DiscoveredPeering(node, policy, clock, timer).also { it.begin() }
    }
}

/** An [IrohNode] link source, as the table names it. The two enums are deliberately separate: one is transport, one is policy. */
private fun sourceOf(source: IrohNode.LinkSource): EntrySource = when (source) {
    IrohNode.LinkSource.ACCEPTED -> EntrySource.ACCEPTED
    IrohNode.LinkSource.DISCOVERED -> EntrySource.DISCOVERED
    IrohNode.LinkSource.CONFIGURED -> EntrySource.CONFIGURED
}
