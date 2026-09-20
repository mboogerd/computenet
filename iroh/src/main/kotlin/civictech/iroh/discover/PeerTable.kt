package civictech.iroh.discover

import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import civictech.iroh.LinkDirection
import civictech.iroh.toHex
import java.util.Arrays
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The 32-byte iroh endpoint id (`NodeId`) as a map key, compared by CONTENT.
 *
 * A `ByteArray` is compared by reference in Kotlin, so a raw `NodeId` read off
 * two different sidecar frames names two different map entries even when the
 * bytes are identical — which is the whole defect this class exists to
 * prevent. A `@JvmInline value class` over `ByteArray` would keep that
 * reference equality (the inline class delegates `equals` to the underlying
 * array), so this is a plain class with `contentEquals`/`contentHashCode`.
 *
 * The bytes are **public** endpoint material: an iroh `NodeId` is what a peer
 * broadcasts on the LAN. Nothing here is secret, and nothing here is a
 * `PeerId` — see [PeerTable]'s KDoc on the identity boundary.
 */
class NodeKey(bytes: ByteArray) {
    /** A defensive copy, so a caller reusing its frame buffer cannot mutate a live map key. */
    val bytes: ByteArray = bytes.copyOf()

    /** The full 64-character lowercase hex of [bytes] — the form every observability surface carries. */
    val hex: String = this.bytes.toHex()

    /** The first 8 hex characters, for log lines where the full id is noise. Never used as an identity. */
    val short: String get() = hex.take(8)

    private val hash: Int = this.bytes.contentHashCode()

    override fun equals(other: Any?): Boolean = other is NodeKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = hash

    override fun toString(): String = "NodeKey($short)"
}

/** Where this node learned of a key — the three sources [DSC2-DIAL-01] must hold one peering across. */
enum class EntrySource {
    /** A `PEER_DISCOVERED` event from the sidecar's LAN enumeration. */
    DISCOVERED,

    /** A link this node accepted; the key was never discovered or configured. */
    ACCEPTED,

    /**
     * A peering an application configured explicitly. Never dialled by the
     * discovery policy and never evicted: it has an owner outside this table
     * ([DSC2-DIAL-07]).
     */
    CONFIGURED,
}

/** What a key is doing right now. One entry is in exactly one of these. */
sealed interface PeerState {
    /** Known and dialable. [dueAt] is when the next dial may start; null means "not on the dial schedule". */
    data class Retained(val addresses: List<String>, val lastSeen: Long, val dueAt: Long?, val attempt: Int) : PeerState

    /** A dial is in flight. [attempt] is the 0-based retry index this dial is. */
    data class Dialling(val attempt: Int, val since: Long) : PeerState

    /** A link for this key was admitted and carries [attributedPeer] — the `PeerId` a `Session` stamped. */
    data class Peered(
        val direction: LinkDirection,
        val linkId: Long,
        val attributedPeer: PeerId,
        val since: Long,
    ) : PeerState

    /**
     * Another key ([byKey]) is now peered with the identity this key was
     * attributed to — a rotation or a second device, which discovery cannot
     * tell apart and must not guess (F3-D6, [DSC2-ID-06]). Its pending dial is
     * cancelled and it is never re-dialled; a link it still holds stays up
     * until it drops on its own.
     */
    data class Superseded(val byKey: NodeKey, val since: Long) : PeerState

    /** The caller reported the refused-dial limit for this key (F3-D4). [reason] is the last denial, if one was named. */
    data class Abandoned(val reason: DenialReason?, val since: Long) : PeerState

    /** A `PEER_EXPIRED` arrived with no live link. Retries are cancelled; the entry is evictable and re-dialable on a fresh sighting. */
    data class Expired(val since: Long) : PeerState
}

/** What [PeerTable.observe] did with a sighting. Exactly one of these per call. */
sealed interface Observation {
    /** The key is this node's own. Nothing was retained (F3-D3, [DSC2-DIAL-04]); the caller counts `selfDropped`. */
    data object Self : Observation

    /** The key is now Retained and on the dial schedule — a fresh entry, or an existing Retained/Expired one refreshed. */
    data object Dialable : Observation

    /** The key already has something happening; no dial starts ([DSC2-DIAL-01], [DSC2-DIAL-07]). The caller counts `duplicatesSuppressed`. */
    data class Suppressed(val state: PeerState) : Observation

    /**
     * The key was retained (like [Dialable]) and making room for it dropped
     * [victim] — the oldest-`lastSeen` entry that was not peered, dialling,
     * linked or configured ([DSC2-MDNS-05]). The caller counts `evicted`.
     */
    data class Evicted(val victim: NodeKey) : Observation

    /** The table was full of entries none of which may be evicted. The key was NOT retained and the table did not grow. */
    data object Rejected : Observation
}

/** What [PeerTable.linkDown] decided about re-dialling the key whose link dropped. */
sealed interface DownOutcome {
    /** The key returned to Retained with `dueAt = now`; it is dialable again ([DSC2-DIAL-06]). */
    data object Redial : DownOutcome

    /** The key stays where it is: superseded, abandoned, expired, configured, or still holding another live link. */
    data object NoRedial : DownOutcome
}

/**
 * What [PeerTable.judge] decided about a hello. The table is already mutated
 * for the outcome returned (ktn1l-D18); the caller applies only the side
 * effects — closing links, moving counters, recording a denial.
 */
sealed interface Judgement {
    /**
     * Admit this link. [close]/[closeLinkId] name a link that must be closed
     * quietly because this link won the tie-break against it; both are null
     * when nothing has to be closed.
     */
    data class Admit(val close: NodeKey?, val closeLinkId: Long?) : Judgement

    /** This link's direction is the tie-break loser and the other direction is up. Close it with no denial and no blame (aas-D7). The caller counts `tieBreakClosed`. */
    data object CloseQuietly : Judgement

    /** Admit this link and treat [oldKey] as [PeerState.Superseded] by this one (F3-D6, [DSC2-ID-06]). The caller counts `superseded`. */
    data class Supersede(val oldKey: NodeKey) : Judgement

    /**
     * Refuse this link: a LIVE link for the same key is attributed to [live],
     * and this hello resolved the same key to a different identity
     * ([DSC2-ID-05]). The table is unchanged — the live link stays, and the
     * caller records the denial.
     */
    data class Refuse(val reason: DenialReason, val live: PeerId) : Judgement
}

/**
 * One retained key, as an observability surface sees it ([DSC2-OBS-01..03]).
 *
 * Everything here is public: the endpoint id hex a peer broadcasts on the LAN,
 * the addresses it broadcast with it, the state name, the source, a timestamp,
 * the last denial reason, and the NAME of the `PeerId` a `Session` stamped.
 * There is no key material, statement byte or credential in this view because
 * there is none in the table to leak — [PeerTable] holds none (see its KDoc),
 * so this is a statement about the table's contents, not a filter over them.
 */
data class PeerView(
    val keyHex: String,
    val addresses: List<String>,
    val state: String,
    val source: String,
    val lastSeen: Long,
    val lastDenial: DenialReason?,
    val attributedPeer: String?,
)

/**
 * The per-key state machine the discovery dial policy runs on — PURE: no IO,
 * no threads, no sidecar, no timers (ktn1l-D18). Every method is O(1) or O(n)
 * over retained entries and returns what changed, so a caller never has to
 * re-derive it by reading the table back.
 *
 * That purity is what makes it exhaustively testable, and it is deliberate:
 * this table is consulted SYNCHRONOUSLY on the sidecar reader thread by the
 * hello gate and from the policy thread, so a method that blocked, dialled or
 * slept would stall the reader. The threads, the executor and the timer live
 * in `DiscoveredPeering` (feature task 3), around this.
 *
 * ## The identity boundary (F3-D3, [DSC2-ID-02], [DSC2-ID-03])
 *
 * Entries are keyed by the KEY identifier — the 32-byte iroh `NodeId`,
 * compared by content ([NodeKey]). This table **constructs no [PeerId]**: the
 * only `PeerId` it ever holds is one a `Session` already stamped on a live
 * link and handed in through [admitted]. It holds no allowlist and no name
 * mapping beyond that; `Side.allow` is applied inside `Session`, at the hello,
 * and nowhere near here. A `PeerId(` in this package would be the second
 * key-to-identity derivation DSC4 forbids, and
 * `kernel/src/test/resources/architecture/peerid-constructions.txt` fails the
 * build on one.
 *
 * ## Thread safety
 *
 * All state is behind one [ReentrantLock]. Every public method takes it; none
 * calls out to a caller-supplied lambda except [dialFailed]'s `schedule` and
 * the `clock`, both of which are required to be pure and fast.
 *
 * @param ownNodeId this node's own endpoint id. A sighting of it is dropped by
 *   [observe] as [Observation.Self] and never retained.
 * @param maxRetained the bound on retained entries ([DSC2-MDNS-05]). See
 *   [observe] for exactly what it bounds — and [linkUp] for the one documented
 *   way past it.
 * @param clock the only source of time in this class; there is no
 *   `System.currentTimeMillis()` here ([DSC2-DIAL-08]).
 */
class PeerTable(
    ownNodeId: ByteArray,
    private val maxRetained: Int = 1024,
    private val clock: () -> Long,
) {
    init {
        require(maxRetained > 0) { "maxRetained must be positive, was $maxRetained" }
    }

    /** This node's own key. Kept as a [NodeKey] so the self check is one content comparison. */
    val ownKey: NodeKey = NodeKey(ownNodeId)

    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<NodeKey, Entry>()

    private class Entry(
        val key: NodeKey,
        var state: PeerState,
        var source: EntrySource,
        var addresses: List<String>,
        var lastSeen: Long,
        var lastDenial: DenialReason? = null,
        var attributedPeer: PeerId? = null,
    ) {
        /** Links the caller has reported UP for this key, admitted or not, by direction. At most one per direction. */
        val upLinks: MutableMap<LinkDirection, Long> = LinkedHashMap()
    }

    /** How many keys are retained right now — the `keysRetained` gauge of [DiscoveryCounters] ([DSC2-OBS-01]). */
    val keysRetained: Int get() = lock.withLock { entries.size }

    /**
     * Records a sighting of [key] at [addresses].
     *
     * The bound is enforced HERE, on the discovery path, because that is the
     * path an attacker or a noisy LAN can drive ([DSC2-MDNS-05]): a key that
     * would push the table past [maxRetained] evicts the oldest-`lastSeen`
     * entry that is not peered, dialling, holding a live link or configured,
     * and is [Observation.Rejected] outright when no such entry exists.
     *
     * A [Observation.Suppressed] sighting deliberately does NOT refresh
     * `lastSeen`: refreshing it would let a repeatedly-sighted superseded,
     * abandoned or expired entry outlive fresher dialable ones in the eviction
     * order, which inverts what the bound is for.
     */
    fun observe(key: NodeKey, addresses: List<String>, now: Long): Observation = lock.withLock {
        if (key == ownKey) return Observation.Self

        val existing = entries[key]
        if (existing != null) {
            if (existing.source == EntrySource.CONFIGURED) return Observation.Suppressed(existing.state)
            return when (val state = existing.state) {
                is PeerState.Retained -> {
                    existing.addresses = addresses
                    existing.lastSeen = now
                    existing.state = state.copy(addresses = addresses, lastSeen = now)
                    Observation.Dialable
                }

                is PeerState.Expired -> {
                    existing.addresses = addresses
                    existing.lastSeen = now
                    existing.state = PeerState.Retained(addresses, lastSeen = now, dueAt = now, attempt = 0)
                    Observation.Dialable
                }

                else -> Observation.Suppressed(state)
            }
        }

        var evicted: NodeKey? = null
        if (entries.size >= maxRetained) {
            val victim = evictionVictim() ?: return Observation.Rejected
            entries.remove(victim)
            evicted = victim
        }
        entries[key] = Entry(
            key = key,
            state = PeerState.Retained(addresses, lastSeen = now, dueAt = now, attempt = 0),
            source = EntrySource.DISCOVERED,
            addresses = addresses,
            lastSeen = now,
        )
        return if (evicted != null) Observation.Evicted(evicted) else Observation.Dialable
    }

    /** The oldest-`lastSeen` evictable entry, or null when every entry is protected. Caller holds the lock. */
    private fun evictionVictim(): NodeKey? =
        entries.values
            .filter { it.source != EntrySource.CONFIGURED }
            .filter { it.state !is PeerState.Peered && it.state !is PeerState.Dialling }
            .filter { it.upLinks.isEmpty() }
            .minByOrNull { it.lastSeen }
            ?.key

    /**
     * A `PEER_EXPIRED` for [key]: the LAN stopped advertising it.
     *
     * @return true when the key moved to [PeerState.Expired] and the caller
     *   must cancel its pending retries ([DSC2-DIAL-06]). False — and nothing
     *   changes — for a key with a live link, for a configured key (its
     *   reconnects are not this policy's), and for one already superseded,
     *   abandoned or expired.
     */
    fun expire(key: NodeKey): Boolean = lock.withLock {
        val entry = entries[key] ?: return false
        if (entry.source == EntrySource.CONFIGURED) return false
        if (entry.upLinks.isNotEmpty() || entry.state is PeerState.Peered) return false
        return when (entry.state) {
            is PeerState.Retained, is PeerState.Dialling -> {
                entry.state = PeerState.Expired(since = clock())
                true
            }

            else -> false
        }
    }

    /**
     * The keys whose next dial may start now: Retained, not configured, with a
     * `dueAt` at or before [now], oldest `dueAt` first, at most
     * `maxInFlight - <currently dialling>` of them ([DSC2-DIAL-03]).
     *
     * Reports only; it starts nothing and moves nothing. The caller marks each
     * key it actually dials with [markDialling].
     */
    fun nextDue(now: Long, maxInFlight: Int): List<NodeKey> = lock.withLock {
        val dialling = entries.values.count { it.state is PeerState.Dialling }
        val budget = (maxInFlight - dialling).coerceAtLeast(0)
        if (budget == 0) return emptyList()
        return entries.values
            .filter { it.source != EntrySource.CONFIGURED }
            .mapNotNull { entry ->
                val state = entry.state as? PeerState.Retained ?: return@mapNotNull null
                val dueAt = state.dueAt ?: return@mapNotNull null
                if (dueAt <= now) entry to dueAt else null
            }
            .sortedBy { it.second }
            .take(budget)
            .map { it.first.key }
    }

    /**
     * Marks [key] as dialling at retry index [attempt].
     *
     * @return true when a Retained entry moved to [PeerState.Dialling]; false
     *   for a key that is unknown or no longer dialable (the caller raced an
     *   accepted link or an expiry).
     */
    fun markDialling(key: NodeKey, attempt: Int): Boolean = lock.withLock {
        val entry = entries[key] ?: return false
        if (entry.state !is PeerState.Retained) return false
        entry.state = PeerState.Dialling(attempt = attempt, since = clock())
        return true
    }

    /**
     * The dial for [key] failed: back to Retained at `dueAt = now +
     * schedule(attempt)` with the attempt index advanced ([DSC2-DIAL-03]).
     * [schedule] is the injected backoff and is never consulted for anything
     * else here.
     *
     * @return the new `dueAt`, or null when the key was not dialling.
     */
    fun dialFailed(key: NodeKey, now: Long, schedule: (Int) -> Long): Long? = lock.withLock {
        val entry = entries[key] ?: return null
        val dialling = entry.state as? PeerState.Dialling ?: return null
        val dueAt = now + schedule(dialling.attempt)
        entry.state = PeerState.Retained(
            addresses = entry.addresses,
            lastSeen = entry.lastSeen,
            dueAt = dueAt,
            attempt = dialling.attempt + 1,
        )
        return dueAt
    }

    /**
     * Records that a link for [key] is UP but not yet admitted. Creates the
     * entry when the key is unknown — an accepted inbound link from a key this
     * node never discovered.
     *
     * **This path may exceed [maxRetained]**, and only when every existing
     * entry is protected from eviction: a link that is physically up must be
     * represented, or the tie-break and the one-peering invariant lose the very
     * fact they arbitrate over. The bound exists to stop a LAN flood of
     * SIGHTINGS ([DSC2-MDNS-05]); live links are bounded by the transport that
     * accepted them, not by this table.
     *
     * [judge] creates an entry for an unknown key on the same reasoning, and
     * does not attempt eviction at all — so the two link-backed paths, not this
     * one alone, are where the table can pass its bound.
     */
    fun linkUp(key: NodeKey, direction: LinkDirection, linkId: Long, source: EntrySource) = lock.withLock {
        val entry = entries[key] ?: run {
            if (entries.size >= maxRetained) evictionVictim()?.let { entries.remove(it) }
            val fresh = Entry(
                key = key,
                state = PeerState.Retained(addresses = emptyList(), lastSeen = clock(), dueAt = null, attempt = 0),
                source = source,
                addresses = emptyList(),
                lastSeen = clock(),
            )
            entries[key] = fresh
            fresh
        }
        entry.upLinks[direction] = linkId
    }

    /**
     * A `Session` admitted [linkId] for [key] and stamped [peer] on it: the
     * entry becomes [PeerState.Peered]. [peer] is carried, never derived — see
     * this class's identity-boundary KDoc.
     */
    fun admitted(key: NodeKey, linkId: Long, peer: PeerId): Boolean = lock.withLock {
        val entry = entries[key] ?: return false
        val direction = entry.upLinks.entries.firstOrNull { it.value == linkId }?.key ?: return false
        entry.attributedPeer = peer
        entry.state = PeerState.Peered(direction = direction, linkId = linkId, attributedPeer = peer, since = clock())
        return true
    }

    /**
     * The link [linkId] for [key] went down.
     *
     * A discovered key whose only link dropped returns to Retained with
     * `dueAt = now` — straight back into the dialable set ([DSC2-DIAL-06]).
     * Everything else answers [DownOutcome.NoRedial] and stays put: a
     * superseded key (F3-D6), an abandoned or expired one, a configured one
     * (its reconnects belong to whoever configured it), and a key that still
     * holds another live link — which is what a quiet tie-break close looks
     * like from here, and must not provoke a re-dial of a peer this node is
     * still linked to.
     */
    fun linkDown(key: NodeKey, linkId: Long, now: Long): DownOutcome = lock.withLock {
        val entry = entries[key] ?: return DownOutcome.NoRedial
        entry.upLinks.entries.removeIf { it.value == linkId }
        if (entry.upLinks.isNotEmpty()) return DownOutcome.NoRedial
        if (entry.source == EntrySource.CONFIGURED) return DownOutcome.NoRedial
        return when (entry.state) {
            is PeerState.Superseded, is PeerState.Abandoned, is PeerState.Expired -> DownOutcome.NoRedial

            else -> {
                entry.attributedPeer = null
                entry.state = PeerState.Retained(
                    addresses = entry.addresses,
                    lastSeen = entry.lastSeen,
                    dueAt = now,
                    attempt = 0,
                )
                DownOutcome.Redial
            }
        }
    }

    /**
     * Gives up on [key] — the caller hit the refused-dial limit (F3-D4).
     * [reason] is the last denial attributed to it, if one was named; it is
     * recorded as [PeerView.lastDenial].
     *
     * @return false, changing nothing, for a key this table does not hold —
     *   an unknown key has no entry to record a denial on.
     */
    fun abandon(key: NodeKey, reason: DenialReason?): Boolean = lock.withLock {
        val entry = entries[key] ?: return false
        entry.lastDenial = reason
        entry.state = PeerState.Abandoned(reason = reason, since = clock())
        return true
    }

    /**
     * Judges a hello that arrived on [linkId] for [key], in [direction],
     * resolving to [resolved] (F3-D5, F3-D6, F3-D7).
     *
     * Precedence, and it is a total order, not a preference (ktn1l-D18):
     *
     * 1. **[Judgement.Refuse] (IDENTITY_MISMATCH)** — a live link for THIS key
     *    is attributed to a different identity. A key whose identity changed
     *    under a live link is refused before any tie-break gets to reason about
     *    its directions, because the tie-break's premise (these two links are
     *    the same peer) is exactly what has failed ([DSC2-ID-05]).
     * 2. **[Judgement.CloseQuietly]** — this direction is [loserDirection] and
     *    the other direction for this key is up (aas-D7).
     * 3. **[Judgement.Admit] naming the other link** — the mirror of 2: this
     *    direction wins, so the other one is closed.
     * 4. **[Judgement.Supersede]** — no live link for this key, but another
     *    key's peered entry carries the same identity (F3-D6).
     * 5. **[Judgement.Admit]** with nothing to close.
     *
     * The table is MUTATED for the outcome returned: cases 3, 4 and 5 leave
     * [key] [PeerState.Peered] (and case 4 leaves the old key
     * [PeerState.Superseded], its dial cancelled). Cases 1 and 2 change
     * nothing at all — the live link stays as it was, and it is the caller
     * that closes a link and records a denial.
     */
    fun judge(
        key: NodeKey,
        direction: LinkDirection,
        linkId: Long,
        resolved: PeerId,
        now: Long,
    ): Judgement = lock.withLock {
        val entry = entries[key] ?: run {
            val fresh = Entry(
                key = key,
                state = PeerState.Retained(addresses = emptyList(), lastSeen = now, dueAt = null, attempt = 0),
                source = EntrySource.ACCEPTED,
                addresses = emptyList(),
                lastSeen = now,
            )
            entries[key] = fresh
            fresh
        }
        entry.upLinks[direction] = linkId

        // 1. Identity mismatch on this key, against a link that is still live.
        val peered = entry.state as? PeerState.Peered
        if (peered != null && peered.linkId != linkId && peered.attributedPeer != resolved) {
            entry.upLinks.entries.removeIf { it.value == linkId }
            return Judgement.Refuse(DenialReason.IDENTITY_MISMATCH, live = peered.attributedPeer)
        }

        // 2/3. Both directions of this key are up: exactly one survives.
        val other = entry.upLinks.entries.firstOrNull { it.key != direction && it.value != linkId }
        if (other != null) {
            if (direction == loserDirection(ownKey.bytes, key.bytes)) {
                entry.upLinks.entries.removeIf { it.value == linkId }
                return Judgement.CloseQuietly
            }
            entry.attributedPeer = resolved
            entry.state = PeerState.Peered(direction, linkId, resolved, since = now)
            return Judgement.Admit(close = key, closeLinkId = other.value)
        }

        // 4. The identity is already peered under a DIFFERENT key: a rotation or a second device.
        val old = entries.values.firstOrNull { candidate ->
            candidate.key != key && (candidate.state as? PeerState.Peered)?.attributedPeer == resolved
        }
        entry.attributedPeer = resolved
        entry.state = PeerState.Peered(direction, linkId, resolved, since = now)
        if (old != null) {
            old.state = PeerState.Superseded(byKey = key, since = now)
            return Judgement.Supersede(old.key)
        }

        // 5. Nothing else holds this key or this identity.
        return Judgement.Admit(close = null, closeLinkId = null)
    }

    /** Every retained key, as an observability surface sees it. A copy; the caller may hold it. */
    fun snapshot(): List<PeerView> = lock.withLock {
        entries.values.map { entry ->
            PeerView(
                keyHex = entry.key.hex,
                addresses = entry.addresses.toList(),
                state = describe(entry.state),
                source = entry.source.name,
                lastSeen = entry.lastSeen,
                lastDenial = entry.lastDenial,
                attributedPeer = entry.attributedPeer?.name,
            )
        }
    }

    /** The state for [key], or null when the key is not retained. Diagnostics and tests. */
    fun stateOf(key: NodeKey): PeerState? = lock.withLock { entries[key]?.state }

    private fun describe(state: PeerState): String = when (state) {
        is PeerState.Retained -> "Retained"
        is PeerState.Dialling -> "Dialling(attempt=${state.attempt})"
        is PeerState.Peered -> "Peered(${state.direction})"
        is PeerState.Superseded -> "Superseded(by=${state.byKey.short})"
        is PeerState.Abandoned -> "Abandoned(${state.reason})"
        is PeerState.Expired -> "Expired"
    }

    companion object {
        /**
         * The direction this node must NOT keep for [peer] when both
         * directions of one key are up (aas-D7, ktn1l-D16).
         *
         * One invariant, evaluated identically on both sides, which is the
         * only reason two nodes converge on the SAME physical link: the
         * smaller endpoint id keeps its OUTBOUND link, so A < B keeps A→B and
         * both sides close the B→A one.
         *
         * The comparison is UNSIGNED. `java.util.Arrays.compareUnsigned` is
         * exactly a lexicographic compare of the bytes as unsigned, which is
         * what an endpoint id is; Kotlin's `Byte` compare is signed and would
         * order `0x80…` BEFORE `0x7f…`, so the two sides of a peering whose
         * ids straddle 0x80 would each conclude they were the smaller one and
         * both close their outbound link, leaving no peering at all. That case
         * is the discriminating test row, not a hypothetical.
         *
         * @throws IllegalArgumentException when the ids are equal. Equal ids
         *   mean a node judging a peering with itself, which [observe] drops as
         *   [Observation.Self] long before this is reached; there is no answer
         *   to return and silently picking one would hide the bug.
         */
        fun loserDirection(own: ByteArray, peer: ByteArray): LinkDirection {
            val cmp = Arrays.compareUnsigned(own, peer)
            require(cmp != 0) { "loserDirection is undefined for a peer with this node's own id (${own.toHex()})" }
            return if (cmp < 0) LinkDirection.INBOUND else LinkDirection.OUTBOUND
        }
    }
}
