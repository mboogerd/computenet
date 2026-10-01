package civictech.cell.wire

import java.util.concurrent.TimeUnit

/**
 * The reconnect decision every [PeerTransport] binding shares (feature
 * `computenet-gyvli`, decision gyvli-D2): an I/O-free state machine over a
 * handful of flags. Nothing here sleeps, spawns or reads a clock — the
 * binding passes `nowNanos` in and runs its own loop, asking [shouldRedial]
 * before **every** attempt.
 *
 * It hoists what `:wire`'s `WsTransport.WsConnection` and `:iroh`'s
 * `IrohTransport.IrohConnection` each carried a copy of:
 *
 * - the backoff schedule ([DEFAULT_BACKOFF], the 1 s-doubling schedule capped
 *   at 30 s, moved verbatim from `WsTransport.DEFAULT_RECONNECT_BACKOFF`);
 * - the refused-dial bound ([REFUSED_DIAL_LIMIT], [REFUSAL_WINDOW_MS]): a link
 *   that opens and closes without ever producing a surviving peering is a
 *   refusal, not a drop, and [REFUSED_DIAL_LIMIT] consecutive refusals end the
 *   reconnecting ([abandoned]) — `WsConnection.onClose`'s rule
 *   (computenet-4gzr). An open that outlives [refusalWindowMs] clears the run
 *   (`:wire`'s discriminator); so does [onAdmitted] (`:iroh`'s, which has no
 *   window — pass `refusalWindowMs = Long.MAX_VALUE` for that shape);
 * - the two **intent** flags: [deliberateClose] (gyvli-D4 — a close the
 *   binding initiated never re-arms, computenet-8uv6) and [severed]
 *   (`PeerConnection.partition` — held severed until [heal]; a dial that
 *   opens after the sever is refused by [admitDial], computenet-g1aua).
 *
 * Thread-safe: every transition and read holds this object's monitor, so a
 * binding's reader thread and its reconnect loop may both drive it.
 *
 * What it does **not** bound, as on both transports: a dial that never opens
 * at all ([onClosed] with no preceding [onOpened]) costs the peer nothing and
 * retries forever on purpose — a listener that is down is expected back.
 */
class ReconnectPolicy(
    private val backoff: (attempt: Int) -> Long = DEFAULT_BACKOFF,
    val refusedDialLimit: Int = REFUSED_DIAL_LIMIT,
    val refusalWindowMs: Long = REFUSAL_WINDOW_MS,
) {
    init {
        require(refusedDialLimit >= 1) { "refusedDialLimit must be >= 1 (was $refusedDialLimit)" }
        require(refusalWindowMs >= 0) { "refusalWindowMs must be >= 0 (was $refusalWindowMs)" }
    }

    /** Saturating: `Long.MAX_VALUE` ms disables the window rather than overflowing it. */
    private val refusalWindowNanos: Long = TimeUnit.MILLISECONDS.toNanos(refusalWindowMs)

    /** When the current link opened, or null while none is open (a close for a dial that never opened). */
    private var openedAtNanos: Long? = null

    private var unadmitted = 0
    private var abandonedFlag = false
    private var severedFlag = false
    private var deliberateCloseFlag = false

    /** Consecutive opens that produced no surviving peering; see [REFUSED_DIAL_LIMIT]. */
    val unadmittedOpens: Int get() = synchronized(this) { unadmitted }

    /** True once [refusedDialLimit] consecutive unadmitted opens ended the reconnecting; cleared by [heal]. */
    val abandoned: Boolean get() = synchronized(this) { abandonedFlag }

    /** True between [sever] and [heal]. */
    val severed: Boolean get() = synchronized(this) { severedFlag }

    /** True once [closeDeliberately] ran; permanent — [heal] does not undo an application's decision to stop. */
    val deliberateClose: Boolean get() = synchronized(this) { deliberateCloseFlag }

    /** May the binding attempt a (re-)dial now? Asked before every attempt. */
    @Synchronized
    fun shouldRedial(): Boolean =
        !deliberateCloseFlag && !severedFlag && !abandonedFlag && unadmitted < refusedDialLimit

    /** The delay before re-dial attempt [attempt] (0-based within one loop). */
    fun nextDelayMs(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0 (was $attempt)" }
        return backoff(attempt)
    }

    /**
     * A link opened. Charged **before** the hello goes out, because the open
     * is the cost: it has already committed the peer to one accept and one
     * hello parse whatever happens next (computenet-4gzr).
     */
    @Synchronized
    fun onOpened(nowNanos: Long) {
        openedAtNanos = nowNanos
        unadmitted++
    }

    /**
     * A dial just opened: charge it ([onOpened]) and return true if the
     * binding still wants it, or return false **without charging** when
     * [shouldRedial] no longer holds — the link opened after a [sever], a
     * [closeDeliberately] or an abandonment, and the binding must close it
     * quietly rather than let it carry (the structural fix for
     * computenet-g1aua: the sever raced an in-flight dial).
     */
    @Synchronized
    fun admitDial(nowNanos: Long): Boolean {
        if (!shouldRedial()) return false
        onOpened(nowNanos)
        return true
    }

    /** The peer admitted this side's hello: the run of refusals is over. */
    @Synchronized
    fun onAdmitted() {
        unadmitted = 0
    }

    /**
     * The link closed. Applies `WsConnection.onClose`'s rule: an open that
     * outlived [refusalWindowMs] was not a refusal and clears the run; a close
     * with no open (a dial that never connected) neither counts nor clears;
     * and a run at [refusedDialLimit] sets [abandoned].
     */
    @Synchronized
    fun onClosed(nowNanos: Long) {
        val opened = openedAtNanos
        openedAtNanos = null
        if (opened != null && nowNanos - opened >= refusalWindowNanos) unadmitted = 0
        if (unadmitted >= refusedDialLimit) abandonedFlag = true
    }

    /** The binding is closing this link on the application's behalf: arm nothing, ever again. */
    @Synchronized
    fun closeDeliberately() {
        deliberateCloseFlag = true
    }

    /** `PeerConnection.partition`: hold the link down until [heal]. */
    @Synchronized
    fun sever() {
        severedFlag = true
    }

    /**
     * An operator's decision to try again: clears [severed], [abandoned] and
     * the unadmitted run (a maxed-out run is as terminal as [abandoned], so
     * both must go — `WsConnection.heal`). Leaves [deliberateClose] set: a
     * heal resumes a peering the policy gave up on, not one the application
     * closed.
     */
    @Synchronized
    fun heal() {
        severedFlag = false
        abandonedFlag = false
        unadmitted = 0
    }

    companion object {
        /**
         * 1 s, doubling, capped at 30 s — the schedule `WsTransport` and
         * `IrohTransport` each declared as `DEFAULT_RECONNECT_BACKOFF`. The
         * shift itself is capped so a long-lived failing connection cannot
         * overflow it.
         */
        val DEFAULT_BACKOFF: (attempt: Int) -> Long = { attempt ->
            (1_000L shl attempt.coerceAtMost(20)).coerceAtMost(30_000L)
        }

        /**
         * How many consecutive opens may end without a surviving peering
         * before the reconnecting stops (computenet-4gzr). A cost ceiling on
         * what one refused peer may charge a listener, not a semantic
         * threshold; room for the handful of unadmitted opens a transient
         * fault can produce.
         */
        const val REFUSED_DIAL_LIMIT: Int = 5

        /**
         * How long an opened link must last to clear the refused-dial run
         * (`:wire`'s discriminator). Three orders of magnitude above a
         * refusal's round trip; only ever subtracted at close, never slept on.
         */
        const val REFUSAL_WINDOW_MS: Long = 2_000
    }
}
