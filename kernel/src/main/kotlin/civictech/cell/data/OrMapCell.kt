package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.EmbeddedMergeClass
import civictech.cell.PendingReBaseline
import civictech.cell.Propagate
import civictech.cell.ReBaselineNotice
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.delta.DeliveredFrontier
import civictech.cell.data.delta.DeliveryTracking
import civictech.cell.data.delta.StabilityReclaim
import civictech.cell.data.delta.TagLaneContinuity
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.Interest
import civictech.cell.link.catchUpOnLinked
import civictech.cell.link.pullServe
import civictech.cell.port.FanInlet
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

/**
 * The tagged map's port surface (spec 20/24 §Tagged maps, 96 §E1.2). The
 * inlet reuses the **existing** [MapOps] `@Contract` verbatim — the tagged map
 * is a new convergence semantics for the same keyed-write vocabulary, not a
 * new vocabulary — so there is no new contract interface and no `gen/`
 * descriptor work. Only the outlet payload differs from [MapApi]: a
 * [TaggedMapDelta] instead of an untagged
 * [civictech.cell.data.delta.MapDelta].
 */
@CellBase
interface OrMapApi<K, V> {
    val inlet: Use<MapOps<K, V>>
    val outlet: Subscribe<Propagate<TaggedMapDelta<K, V>>>
}

/** Below-floor `StateRequest(since)` diagnostics for [OrMapCell.pullServe] (decision 9sm.8-D8). */
private val PULL_FLOOR_LOG: System.Logger = System.getLogger("civictech.cell.data.OrMapCell")

/**
 * An **OR-map**: the keyed structure whose per-key *value* converges under
 * concurrent multi-writer puts and removes (G-23 for keyed structures; spec
 * 20/24 §Tagged maps, 96 §E1.2). Where [MapCell]'s untagged [MapDelta]
 * resolves concurrent same-key puts by arrival order — fine inside one FIFO
 * stream, not replica-stable — this cell mints a **dot** per put and lets the
 * dot algebra decide, so every observer of the same dot set agrees.
 *
 * It is [SetCell]'s observed-remove idiom lifted one level (live/tombstoned
 * *dot per key* rather than live/tombstoned *tag per element*), with
 * [KeyedSetCell]'s atomic retract-then-add lifted with it.
 *
 * **The four laws** ([TaggedMapDelta] carries their merge/read side):
 *
 * - `[24-TMAP-01]` merge is pointwise dot union — commutative, associative,
 *   idempotent.
 * - `[24-TMAP-02]` [membership] is add-wins: a key is present iff it has at
 *   least one live dot.
 * - `[24-TMAP-03]` [value] is the value of the live dot with the greatest
 *   `(counter, sourceId)` order, unless every live dot's value is a
 *   [civictech.cell.MergeablePayload] and there is more than one, in which
 *   case it is their fold in that same order (96 §E1.4). **No wall clock
 *   participates**, here or in the delta.
 * - `[24-TMAP-04]` [MapOps.remove] is reset-remove: it tombstones exactly the
 *   dots it observed live at the key, so a concurrent put's dot — which this
 *   remove never observed — survives the merge as the key's remaining value.
 *
 * **Re-put atomicity.** A [MapOps.put] over an existing key ships the previous
 * dots' tombstones and the new dot in ONE [TaggedMapDelta], so a downstream
 * fold never observes two live values for the key, nor a windowed zero — the
 * [KeyedSetCell] invariant, lifted to dots. A put always mints, even when the
 * value is unchanged: the fresh dot is the evidence that wins a later
 * `[24-TMAP-03]` comparison, so short-circuiting an equal-value re-put (as
 * [KeyedSetCell] does for an identical element) would silently drop a
 * last-writer-wins claim.
 *
 * **Determinism caveat (spec 20/24 §Tagged maps, decided point 5 — normative
 * for adopters).** State convergence alone does not make *value-keyed*
 * derivation deterministic: whether a concurrent remove cancels a concurrent
 * put can depend on the merge schedule for an operator that reads a value
 * rather than mere presence. What keeps derivation deterministic here is that
 * removes are **tag-precise** — a remove carries exactly the dots it observed,
 * never a value-level predicate. An operator deriving from [value] inherits
 * this caveat and must not assume a wall-clock or arrival-order resolution.
 *
 * **Replicated (96 §E1.3, E1-REPL).** The cell is [Replicable]: peer replicas'
 * deltas merge on [deltaInlet] and only *new dot information* re-emits, so
 * gossip echoes terminate on any mesh topology; the re-emission is a fresh
 * **origination** under this replica's own outlet epoch (the C-10 rule, spec
 * 20/22 Rule S4) while the dots themselves travel verbatim (`[24-TAG-01]`:
 * tags are data, never re-minted for received state). A [pullServe] baseline
 * answers `StateRequest` with since-filtered state-as-delta — tombstones
 * included — to the requester alone, and a `ReBaseline` supersession fences
 * the named dot sources as dead lanes (`[24-TAG-02]`), so a superseded
 * source's dots can never resurrect a key. `SetCell` is the element-shaped
 * sibling of every one of those seams; this is the dot-shaped form.
 *
 * Embedded mergeable values fold at [value] and [values] exposes every live
 * dot for application-side resolution (96 §E1.4) — see [TaggedMapDelta.value]
 * for the fold rule.
 *
 * **Admission (`[KE1-04]`).** A value whose merge is *classified*
 * [civictech.cell.EmbeddedMergeClass] `NON_IDEMPOTENT` — `CounterDelta`'s plain
 * addition — is refused at the first encounter, on `put` and on [applyRemote]
 * alike, with a [civictech.cell.NonIdempotentEmbeddedMerge] diagnostic naming
 * the Riak embedded-counter anomaly; no dot is minted and no fold happens. The
 * check is a **first-encounter** one, not a link-time one: `V` is erased at the
 * ports and CP-F2 stamps `MERGE_IDEMPOTENCE` per *cell*, not per type argument,
 * so `[KE1-10]`'s link-time classification is unreachable here — that shortfall
 * and the unclassified-value residual are filed in `concord/corpus/DISPUTES.md`.
 *
 * **The del-dot (`[24-TAG-04]`, decision 9sm.8-D5).** An effective
 * [MapOps.remove] mints a dot of its OWN from this cell's dot counter and ships
 * it inside the `dels` entry beside the put-dots it covers; a [MapOps.put] over
 * a key that has live dots mints that retract del-dot FIRST and its put-dot
 * SECOND, so **a re-put consumes two counters** (`n` for the retract, `n+1` for
 * the new value) and still ships ONE delta. A put over an absent or fully
 * tombstoned key mints one dot, as it always did. A del-dot never enters
 * [puts], so it covers nothing and [membership]/[value] are bit-for-bit what
 * they were.
 *
 * What the del-dot buys is a remove that can be **delivered**. Without it a
 * `dels` entry carried only the put-dots it covered, so a del-dot at or below a
 * stable frontier certified that every open member had delivered the PUT and
 * said nothing about the REMOVE — the reclamation hazard computenet-v2ka
 * measured on the element-shaped sibling (`SetCell.remove`'s KDoc carries the
 * full argument). The dot rides the delivered lane like any other, fed from
 * local mints and from [applyRemote]'s del lane as well as its put lane.
 *
 * **Stability-scoped reclamation (`[KE3-30]`/`[KE3-31]`, decisions 9sm.8-D6/D7).**
 * [compactBelow] discards a whole `dels` entry — the del-dot included — once
 * every dot in it is at or below a causal-stability frontier, and takes the
 * covered `puts` dots (and their values) with it; [snapshot] is its only
 * production caller. Every discarded dot is recorded in the per-instance
 * re-admission fence ([ReclaimedDots] keyed by MAP KEY), which [applyRemote]
 * subtracts on BOTH lanes and answers with a minimal repair tombstone. The
 * fence is persisted under the additive `"reclaimed"` snapshot key; the
 * compaction floor is DERIVED from it and is never a key of its own.
 *
 * Not here: `TaggedMapView`/`UntagCell` adapters (§E1.5).
 */
class OrMapCell<K, V>(ref: CellRef = CellRef(UUID.randomUUID())) :
    OrMapCellBase<K, V>(ref),
    Stateful,
    Replicable<TaggedMapDelta<K, V>>,
    DeliveryTracking,
    StabilityReclaim,
    TagLaneContinuity {

    /**
     * Replica gossip intake (spec 40/42 §Design as implemented, 96 §E1.3):
     * another replica's effective deltas merge here, and only *new* dot
     * information re-emits (effective-only, 21), so an echo dies at the first
     * replica that already holds every dot it carries.
     */
    override val deltaInlet =
        registerPort("deltaInlet", FanInlet.create<Propagate<TaggedMapDelta<K, V>>>())

    // One causal namespace for the whole map (decided point 1) — dots are NOT
    // partitioned per key, because a per-key context re-admits stale values on
    // key re-creation. `puts` holds every dot ever minted here with the value
    // that put wrote; `dels` holds the dots a remove observed live and covered.
    // A key's live dots are `puts[key]` minus `dels[key]`.
    //
    // The dot sets are RECLAIMED at causal stability (`[KE3-30]`/`[KE3-31]`,
    // 9sm.8-D7): [compactBelow] discards a whole `dels` entry once every dot in
    // it — the del-dot included — is at or below the installed stability read's
    // frontier, and the `puts` dots that entry covers go with it. What that
    // buys is an EXCHANGE, not a bound: the discarded dots become contiguous
    // counter runs in [reclaimed] (a reduction, not a bound — see
    // [ReclaimedDots]'s KDoc). Bounding the retained state outright needs epoch
    // hygiene (G-42), which stays research-gated.
    private val puts = mutableMapOf<K, MutableMap<Timestamp, V>>()
    private val dels = mutableMapOf<K, MutableSet<Timestamp>>()

    /**
     * Guards **every** access to [puts], [dels], [deadSources] and
     * [dotCounter] — the read accessors as much as the writers.
     *
     * The cell's writer runs on whichever thread delivers to [inlet] or
     * [deltaInlet], while [membership], [value], [state] and [snapshot] are
     * *host*-facing reads a caller makes from its own thread. Unguarded, those
     * accessors iterate the shared [LinkedHashMap]s and escape a
     * [java.util.ConcurrentModificationException] into the caller: observed on
     * CI out of [membership] on an `awaitUntil` thread while the beads
     * mirror's poller wrote (computenet-yk5r).
     *
     * **The monitor is never held across an outbound call.** `put`/`remove`
     * mutate under it and propagate after releasing it; [applyRemote] folds
     * under it and originates after. So no foreign code ever runs while this
     * cell holds the monitor, and no cross-cell lock cycle can form.
     *
     * **What it costs.** Reads serialize against the single writer: a host
     * polling [membership] or [state] over a large map delays the next write
     * by that scan (both are O(dots) and already copy). Nothing downstream is
     * blocked, per the paragraph above.
     *
     * **Why not the cheaper options.** Copying the maps on read without a
     * guard does not help — the copy is itself an iteration and throws the
     * same CME. Concurrent maps (a weakly-consistent iteration, no lock) would
     * replace [LinkedHashMap]'s insertion-order iteration with hash order,
     * changing what [membership] and [state] observably return, and would
     * still let [state] tear a `puts` read against a `dels` read.
     */
    private val stateLock = Any()

    // Dots are minted locally, not taken from the wave's MessageContext:
    // observed-remove correctness needs a dot unique per put *instance*, and a
    // wave timestamp repeats across every cell the wave touches (22).
    // Replay-stable identity (M10.1, the SetCell/MintedTags pattern): the
    // source is DERIVED from the ref, so a recovered instance replaying its
    // journal re-mints the exact dots the network already observed — a random
    // source would resurrect removed keys, because a pre-crash remove cannot
    // cover a re-minted dot.
    private val dotSource: UUID =
        UUID.nameUUIDFromBytes("or-map-tags:${ref.id}:${ref.instanceId}".toByteArray())
    private var dotCounter = 0L

    /**
     * Fenced dot sources (spec 20/24 §Tag continuity, `[24-TAG-02]`, 93 I-22
     * R5c): every source a processed `ReBaseline` superseded. A put-dot stamped
     * by a dead source is refused from then on — that is a stale pre-restart
     * delta arriving late over a longer mesh path, and admitting it would
     * resurrect a key the re-baseline retracted.
     *
     * The dot-shaped sibling of
     * [civictech.cell.data.delta.TagState]'s `deadSources`, deliberately kept
     * *here* rather than extracted: `TagState` is a live-tags-only ledger whose
     * retraction is a deletion, while this cell — like [SetCell] — keeps both
     * halves of the OR structure, so its retraction is a *tombstone*. The two
     * fences share the rule, not the fold.
     *
     * ponytail: unbounded, exactly as `TagState`'s is — epoch-hygiene
     * reclamation stays research-gated (G-42).
     */
    private val deadSources = mutableSetOf<UUID>()

    // Per-origin delivered frontier (spec 40/42 §Delivered watermarks, E3.3(a);
    // decision 9sm.8-D1): every dot this cell mints or absorbs — put-dots AND
    // del-dots, on BOTH lanes — folds into a max-contiguous prefix per ORIGIN
    // source (the dot's minting source, still visible in the fold). Listeners —
    // the replica's WatermarkCell companion, installed by
    // `Replication.trackDeliveries` — advance on each raised prefix, so the
    // merged lattice answers "which origin waves has the replica set delivered",
    // not "how many did each replica re-emit" (the CP-B2 outlet tap's key
    // space, which is a different one).
    //
    // Deliberately its OWN [DeliveredFrontier] instance rather than a helper
    // shared with [SetCell]: the two cells' monitors are independent and a
    // shared mutable helper would couple them (9sm.8-D1).
    private val delivered = DeliveredFrontier()
    private val deliveryListeners = mutableListOf<(UUID, Long) -> Unit>()

    /**
     * The **re-admission fence** (`[24-TAG-04]` clause 2, decision 9sm.8-D6;
     * the dot-shaped form of `SetCell`'s, computenet-pay7): every `(key, dot)`
     * pair [compactBelow] has discarded from this replica, kept as a causal
     * context. Written only by [compactBelow]; read only by [novelty] (and by
     * the [fencesAny]/[fencedAmong] diagnostics).
     *
     * Keyed by MAP KEY, exactly as the element key is load-bearing in
     * [ReclaimedDots]'s own KDoc: a rejoining incarnation re-mints counters for
     * a DIFFERENT key, so a fence indexed by the dot alone would reject a live
     * dot of another key.
     *
     * **Not a per-source floor**, which is the design constraint this cell
     * inherits rather than chooses: computenet-v2ka MEASURED all three
     * per-source-floor variants on the element-shaped sibling and each fenced
     * *live* tags into permanent membership divergence, 31-33 of 200 seeds
     * against a no-reclaimer control of 2-4 (`doc/kernel-lane-findings.md`
     * `## KE3-GC-DEL-DOT`). Only a discarded dot ever enters [ReclaimedDots], so
     * a live dot cannot be fenced at all. The compaction floor
     * ([ReclaimedDots.floorFor]) is DERIVED from this fence and carries no
     * snapshot key of its own.
     */
    private val reclaimed = ReclaimedDots<K>()

    /**
     * The stability read [snapshot] reclaims below ([StabilityReclaim],
     * `[KE3-30]`, decisions 9sm.6-D1/9sm.8-D7) — installed by
     * `Replication.trackDeliveries` beside the [onDeliver] listener, `null` for
     * any cell that is not under `Replication`.
     *
     * **Null is the safety default, not a missing feature**: no read, no
     * reclamation, and [snapshot] then serialises exactly what it always did.
     * Guarded by [stateLock] like every other field here, but never *invoked*
     * under it — the read walks another cell's state (see [stateLock]'s "never
     * held across an outbound call").
     */
    private var stabilityRead: (() -> TagFrontier?)? = null

    /**
     * Install the stability read [snapshot] reclaims below. Assignment, not
     * accumulation: `Replication.trackDeliveries` runs again on a rehome, and a
     * second install of an equivalent read must not stack.
     */
    override fun onStability(read: () -> TagFrontier?) = synchronized(stateLock) {
        stabilityRead = read
        Unit
    }

    override fun onDeliver(listener: (source: UUID, thru: Long) -> Unit) = synchronized(stateLock) {
        deliveryListeners += listener
        Unit
    }

    /**
     * Fold [dots] into the delivered frontier and return each raised per-origin
     * prefix. Call under [stateLock]; hand the result to [notifyDelivered]
     * *after* releasing it.
     */
    private fun foldDelivered(dots: Iterable<Timestamp>): Map<UUID, Long> {
        if (deliveryListeners.isEmpty()) return emptyMap()
        val advanced = HashMap<UUID, Long>()
        for (dot in dots) delivered.deliver(dot.sourceId, dot.counter)?.let { advanced[dot.sourceId] = it }
        return advanced
    }

    /**
     * Notify listeners of each raised per-origin prefix. **Never called under
     * [stateLock]**: a listener is another cell's call (see [stateLock]).
     */
    private fun notifyDelivered(advanced: Map<UUID, Long>) {
        if (advanced.isEmpty()) return
        val listeners = synchronized(stateLock) { deliveryListeners.toList() }
        for ((source, thru) in advanced) listeners.forEach { it(source, thru) }
    }

    // ------------------------------------------------------------------ 9sm.8-D9
    // TAG-LANE CONTINUITY across a reincarnation of this ref, the dot-shaped form of
    // `SetCell`'s (computenet-uju5, `doc/kernel-lane-findings.md` `## KE3-23-ROWCONTENT`).
    // [dotSource] is ref-derived and [dotCounter] restarts at 0 on any construction that
    // does not [restore], so a replica that despawns and returns on the same CellRef
    // re-mints counters its previous incarnation already spent — for DIFFERENT keys. A
    // peer's delivered row for this source already stands at the pre-departure high-water
    // and cannot move for those dots ([DeliveredFrontier.deliver] returns null at or below
    // the prefix), so the row would certify a del-dot the peer never applied.
    //
    // `Replication` is what knows a returning ref is a return; it remembers the departing
    // instance's high-water and installs it here at `replicate`. Raising the counter is the
    // whole fix: mints stay per-source monotone and strictly above every prefix any peer
    // holds.

    override fun tagLaneHighWater(): Long = synchronized(stateLock) { dotCounter }

    override fun continueTagLaneAbove(counter: Long) = synchronized(stateLock) {
        // never lowers: a restored checkpoint already carries its own counter, and a
        // re-installation must be idempotent.
        if (counter > dotCounter) dotCounter = counter
        Unit
    }

    /** The dots at [key] no tombstone covers. */
    private fun liveDots(key: K): Map<Timestamp, V> = synchronized(stateLock) {
        val dots = puts[key] ?: return emptyMap()
        // a copy, never the live map: the uncovered branch's result is iterated
        // by callers (`value`'s dot-order scan) outside this monitor.
        val covered = dels[key] ?: return LinkedHashMap(dots)
        dots.filterKeys { it !in covered }
    }

    /** `[24-TMAP-02]` add-wins presence: keys with at least one live dot. */
    fun membership(): Set<K> = synchronized(stateLock) {
        puts.keys.filterTo(LinkedHashSet()) { liveDots(it).isNotEmpty() }
    }

    /**
     * `[24-TMAP-03]`/`[KE1-01..03,06,07]` the key's exposed value — delegated
     * to [TaggedMapDelta.value] over a one-key delta view of this cell's live
     * dots, so the fold/pick logic has exactly one implementation
     * ([KE1-08], j2x.1-D4) and this cell can never disagree with the delta
     * type it emits. `null` when the key is absent.
     */
    fun value(key: K): V? {
        val dots = liveDots(key)
        if (dots.isEmpty()) return null
        return TaggedMapDelta(puts = mapOf(key to dots)).value(key)
    }

    /**
     * `[KE1-06]`/`[KE1-07]` every live dot's value at [key] — the empty set
     * when the key is absent. Unlike [value] this is *not* delegated to
     * [TaggedMapDelta]: there is no fold or pick to single-source here, only
     * the same `liveDots(key).values.toSet()` the delta's [TaggedMapDelta.values]
     * computes. `OrMapEmbeddedValueTest` pins the two against each other across
     * every dot state, so the duplication cannot drift unobserved.
     */
    fun values(key: K): Set<V> = liveDots(key).values.toSet()

    /**
     * This cell's whole dot state as one delta-from-empty, tombstones
     * included — the catch-up emission (G-22, `[24-CATCHUP-01]`) and the
     * read-view any consumer can fold. Copies out; never aliases the fold's
     * mutable maps.
     */
    fun state(): TaggedMapDelta<K, V> = synchronized(stateLock) {
        TaggedMapDelta(
            puts = puts.mapValues { LinkedHashMap(it.value) },
            dels = dels.mapValues { LinkedHashSet(it.value) },
        )
    }

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): MapOps<K, V> = object : MapOps<K, V> {
        override fun put(key: K, value: V) {
            // `[KE1-04]` admission: a classified non-idempotent embedded value
            // is refused here — before any dot is minted, so the refusal leaves
            // no state and performs no fold.
            EmbeddedMergeClass.requireEmbeddable(value, "put")
            // reset-remove's local half: everything this writer currently sees
            // live at the key dies in the SAME delta that carries the fresh dot
            // (KeyedSetCell's atomic retract+add, lifted to dots). The fold and
            // the delivered-frontier advance happen under `stateLock`; the
            // listener notification and the propagation after it, never under.
            //
            // THE RETRACT DEL-DOT (`[24-TAG-04]`, 9sm.8-D5): when the key HAS
            // live dots this put's retract half is an effective remove, so it
            // mints its own dot — FIRST, so the retract's counter is `n` and the
            // new value's is `n+1` and a re-put therefore consumes two. The
            // del-dot goes into `dels` beside the dots it covers and never into
            // `puts`, so `liveDots` is untouched by it.
            val (dot, observed, advanced) = synchronized(stateLock) {
                val seen = LinkedHashSet(liveDots(key).keys)
                val delDot = if (seen.isEmpty()) null else Timestamp(dotSource, ++dotCounter)
                val minted = Timestamp(dotSource, ++dotCounter)
                puts.getOrPut(key) { LinkedHashMap() }[minted] = value
                val entry = if (delDot == null) seen else LinkedHashSet(seen).also { it += delDot }
                if (entry.isNotEmpty()) dels.getOrPut(key) { LinkedHashSet() } += entry
                // a local mint is trivially contiguous
                Triple(minted, entry, foldDelivered(listOfNotNull(delDot) + minted))
            }
            notifyDelivered(advanced)
            outlet.call.propagate(
                TaggedMapDelta(
                    puts = mapOf(key to mapOf(dot to value)),
                    dels = if (observed.isEmpty()) emptyMap() else mapOf(key to observed),
                )
            )
        }

        override fun remove(key: K) {
            // `[24-TMAP-04]` reset-remove, tag-precise: tombstone exactly the
            // dots observed live here and now. A concurrent put's dot is not in
            // this set and therefore survives the merge.
            //
            // THE DEL-DOT (`[24-TAG-04]`, 9sm.8-D5): the remove also mints a dot
            // of its own, from the same counter space the put-dots come from,
            // and ships it inside the entry. It enters `dels` only — it covers
            // no put and `membership()`/`value()` are unchanged by it — and it
            // is what makes `dot <= stableFrontier` certify that every open
            // member delivered this REMOVE rather than merely the put it covers
            // (see the class KDoc, and `SetCell.remove` for the measurement).
            val (entry, advanced) = synchronized(stateLock) {
                val seen = LinkedHashSet(liveDots(key).keys)
                // effective-only (21): removing a key with no live dot is a
                // no-op, and mints no dot — there is no remove to deliver.
                if (seen.isEmpty()) return
                val delDot = Timestamp(dotSource, ++dotCounter)
                seen += delDot
                dels.getOrPut(key) { LinkedHashSet() } += seen
                seen to foldDelivered(listOf(delDot)) // a local mint is trivially contiguous
            }
            notifyDelivered(advanced)
            outlet.call.propagate(TaggedMapDelta(dels = mapOf(key to entry)))
        }
    }

    // ---------------------------------------------------------------------
    // replication (96 §E1.3): gossip merge, re-origination, pull baseline,
    // dead-source fencing. The dot-shaped form of SetCell's element-shaped
    // seams — read that cell beside this one; the shapes differ where a dot
    // carries a value and a tag does not.
    // ---------------------------------------------------------------------

    /**
     * The *new dot information* in [delta] — what this fold has never held —
     * or `null` when it carries none. This is the echo terminator: a delta
     * that has already been absorbed (an echo returning around the mesh, a
     * duplicate arriving over a second path of a diamond, an anti-entropy
     * catch-up replay) reduces to nothing and re-emits nothing.
     *
     * `SetCell.applyRemote`'s `tags - (adds[e] ?: emptySet())` is a set
     * difference; the put half here is the *map*-shaped analogue — a
     * `filterKeys` over `Map<Timestamp, V>` that keeps each new dot's value —
     * because a put-dot is not merely present, it names a value. The del half
     * is the set difference verbatim, `dels` being `Set<Timestamp>` on both
     * cells.
     *
     * [fenced] `false` is the `ReBaseline` re-assertion path only (see
     * [applyReBaseline] step (b)): a re-baseline legitimately re-asserts dots
     * from the very sources it supersedes. It lifts **only the dead-source
     * fence**; the re-admission fence ([reclaimed]) still applies there, because
     * a reclaimed dot is one this replica already observed and discarded and no
     * re-assertion makes it new information again (9sm.8-D6).
     *
     * **THE RE-ADMISSION FENCE, on BOTH lanes** (`[24-TAG-04]` clause 2,
     * 9sm.8-D6). Novelty here is `dots − puts[key]` (resp. `dels[key]`), and a
     * dot [compactBelow] discarded is absent from those maps again — which is
     * exactly why a duplicated or reordered frame re-delivering it reads as NEW
     * information and resurrects the key. `− reclaimed` is the receiver-side
     * memory that closes it. Fencing only the put lane would let a re-delivered
     * `dels` entry rebuild a tombstone the reclaimer then discards again on its
     * next pass, and each rebuild re-emits; fencing both makes a replayed frame
     * carry no novelty at all, so the echo dies here as any other duplicate does.
     *
     * The rejected PUT-dots are reported separately, in [Novelty.fencedPuts],
     * because a silent fence is not safe: see [applyRemote]'s repair tombstone.
     *
     * Nothing is lost from the delivered frontier by not folding a fenced dot: a
     * dot is only ever reclaimed when its whole `dels` entry was ≤ the frontier
     * compaction was driven from, so it was folded before it was discarded, and
     * [DeliveredFrontier] is monotone.
     */
    private fun novelty(delta: TaggedMapDelta<K, V>, fenced: Boolean = true): Novelty<K, V> = synchronized(stateLock) {
        val freshPuts = LinkedHashMap<K, Map<Timestamp, V>>()
        val fencedPuts = LinkedHashMap<K, Set<Timestamp>>()
        delta.puts.forEach { (key, dots) ->
            val known = puts[key]
            val unknown = dots.filterKeys { dot ->
                (!fenced || dot.sourceId !in deadSources) && known?.containsKey(dot) != true
            }
            val rejected = unknown.keys.filterTo(LinkedHashSet()) { reclaimed.holds(key, it) }
            if (rejected.isNotEmpty()) fencedPuts[key] = rejected
            val fresh = if (rejected.isEmpty()) unknown else unknown.filterKeys { it !in rejected }
            if (fresh.isNotEmpty()) freshPuts[key] = fresh
        }
        // Tombstones are never fenced by source: a del entry is stamped by the
        // source that minted the *put* it covers, not by the remover, so a
        // source filter here would discard the very tombstones that keep a dead
        // source's own dots dead. A tombstone can only reduce liveness, so
        // admitting one is always safe — the same asymmetry `TagState` has
        // (`apply` fences the add pass; `foldDels` folds unconditionally).
        val freshDels = LinkedHashMap<K, Set<Timestamp>>()
        delta.dels.forEach { (key, dots) ->
            val known = dels[key]
            val fresh = dots.filterTo(LinkedHashSet()) { known?.contains(it) != true && !reclaimed.holds(key, it) }
            if (fresh.isNotEmpty()) freshDels[key] = fresh
        }
        Novelty(
            novel = if (freshPuts.isEmpty() && freshDels.isEmpty()) null else TaggedMapDelta(freshPuts, freshDels),
            fencedPuts = fencedPuts,
        )
    }

    /**
     * **THE REPAIR TOMBSTONE** (`[24-TAG-04]` clause 2, 9sm.8-D6; the
     * element-shaped original's argument is in `SetCell.applyRemote`, grep
     * anchor `A SILENT FENCE IS NOT SAFE`).
     *
     * A fenced put-dot is answered with a minimal `dels` entry naming exactly
     * that dot: no value, and **no dot is minted**, because no new remove
     * happened and nothing new needs certifying. The fence is itself the
     * evidence that the dot was covered by a remove this replica saw certified
     * delivered, so the covering entry can be reconstructed from the dot alone.
     *
     * Why it is not optional: a fenced sender is a replica that still holds the
     * put-dot LIVE with no tombstone for it. Dropping its frame on the floor
     * leaves the key live there and absent here for ever — the resurrection is
     * converted into a permanent DIVERGENCE rather than removed. MEASURED on the
     * element-shaped sibling: fencing without the repair took the sweep's STABLE
     * membership divergence from 3 of 200 to 30 of 200, the same order as the
     * per-source floor.
     *
     * It cannot loop: the repair goes out only for a dot that was novel against
     * `puts` here, and a peer that has folded it answers with a `dels` frame
     * whose every dot this replica fences, yielding no novelty at all.
     */
    private fun withRepair(
        novel: TaggedMapDelta<K, V>?,
        fencedPuts: Map<K, Set<Timestamp>>,
    ): TaggedMapDelta<K, V>? {
        if (fencedPuts.isEmpty()) return novel
        val delsOut = LinkedHashMap<K, Set<Timestamp>>()
        novel?.dels?.forEach { (key, dots) -> delsOut[key] = dots }
        fencedPuts.forEach { (key, dots) ->
            delsOut.merge(key, dots) { a, b -> LinkedHashSet(a).also { it += b } }
        }
        return TaggedMapDelta(novel?.puts ?: emptyMap(), delsOut)
    }

    /**
     * Fold already-computed [novel] dots into the live maps.
     *
     * **Copy-on-insert, never alias.** Every dot is *inserted into* this cell's
     * own mutable map; a remote delta's map is never adopted wholesale
     * ([TaggedMapDelta.merge]'s empty-side fast path returns its operand by
     * reference, and a remote delta may be retained by its sender or by another
     * consumer, so an adopted map would be mutated under them by this cell's
     * next local put). The same discipline [state] observes on the way out.
     */
    private fun absorb(novel: TaggedMapDelta<K, V>) = synchronized(stateLock) {
        novel.puts.forEach { (key, dots) -> puts.getOrPut(key) { LinkedHashMap() }.putAll(dots) }
        novel.dels.forEach { (key, dots) -> dels.getOrPut(key) { LinkedHashSet() } += dots }
    }

    /**
     * Merge a peer replica's delta and re-emit exactly the novelty (spec 40/42;
     * 96 §E1.3).
     *
     * The re-emission is an [civictech.cell.port.FanOutlet.originate] — a fresh
     * wave under *this* replica's outlet epoch (the C-10 rule / 93 I-14 Rule
     * S4), not a forwarding of the sender's wave — while the dots inside it are
     * byte-identical to the ones that arrived (`[24-TAG-01]`: relayed state
     * preserves its tags). Convergence rides the dots; the waves stay local.
     *
     * **[PendingReBaseline] is cleared around the re-emission**, and that is
     * load-bearing rather than defensive. [civictech.cell.port.FanOutlet.originate]
     * clears [CurrentContext] only; the fresh context the emission then mints
     * reads `PendingReBaseline.get()` (`FanOutlet`'s `call`), which is still set
     * whenever the *sender's* `reBaseline { … }` frame is on this thread's stack
     * — i.e. on every synchronous outlet-to-`deltaInlet` hop. Without this the
     * notice would ride the re-emission after all, which is exactly the
     * translation [applyReBaseline] documents this cell as NOT making.
     */
    private fun applyRemote(delta: TaggedMapDelta<K, V>) {
        // `[KE1-04]` admission, remote half — the same refusal the local `put`
        // raises, applied before novelty/absorb so a refused delta leaves no
        // dot behind and is never folded. It is raised, not swallowed: the
        // delta is refused loudly rather than dropped, so nothing this cell
        // declines can go missing without the diagnostic.
        delta.puts.values.forEach { dots ->
            dots.values.forEach { EmbeddedMergeClass.requireEmbeddable(it, "applyRemote") }
        }
        // read before originating: `originate` clears the current context, so
        // the notice must be taken off the arriving wave first.
        val notice = CurrentContext.get()?.reBaseline
        // one atomic fold: novelty and its absorption must not straddle another
        // writer, and no outbound call happens under the monitor — neither the
        // listener notification nor the re-emission.
        val (folded, advanced) = synchronized(stateLock) {
            val outcome =
                if (notice != null) applyReBaseline(delta, notice)
                else novelty(delta).let { n ->
                    val absorbed = n.novel?.also { absorb(it) }
                    Folded(absorbed, withRepair(absorbed, n.fencedPuts))
                }
            // BOTH LANES feed the delivered frontier (9sm.8-D1): every put-dot
            // of the ABSORBED delta AND every dot of its `dels` — the del lane
            // is what makes a del-dot certify the REMOVE at the receiving
            // replica, and dropping it would leave every non-origin peer's row
            // short by exactly the removes it absorbed. The repair tombstone is
            // deliberately NOT folded: its dots were reclaimed here, so they
            // were folded before they were discarded (see [novelty]).
            outcome to (
                outcome.absorbed?.let { d ->
                    foldDelivered(d.puts.values.flatMap { it.keys } + d.dels.values.flatten())
                } ?: emptyMap()
                )
        }
        notifyDelivered(advanced)
        val effective = folded.emitted ?: return // echo terminates here
        PendingReBaseline.with(null) { outlet.originate { propagate(effective) } }
    }

    /**
     * The convergent-consumer half of a RESTART re-baseline, dot-shaped (spec
     * 20/24 §Tag continuity `[24-TAG-02]`, 93 I-22 R5; the element-shaped
     * original is [civictech.cell.data.delta.TagState.applyReBaseline]):
     *
     * - **(a) retract** every live dot from a superseded source that this
     *   baseline does not re-assert. `TagState` drops such a tag from its live
     *   ledger; this cell keeps both halves of the OR structure, so the drop is
     *   a **tombstone** — recorded in [dels], and therefore durable against the
     *   re-arrival of the same dot over any other path.
     * - **(b) merge** the re-asserted and fresh dots by ordinary dot union,
     *   *not* through the dead-source fence — the re-assertion legitimately
     *   carries dots from the very sources (c) is about to fence.
     * - **(c) fence** the superseded sources: every later ordinary delta
     *   stamped by one of them is refused by [novelty].
     *
     * `supersede = false` (pull-merge) retracts nothing and fences nothing —
     * forward idempotent merge only, exactly as `TagState` treats it.
     *
     * **The notice is not forwarded** (enforced in [applyRemote], which clears
     * [PendingReBaseline] around the re-emission — see its doc for why
     * `originate` alone does not). The re-emission is an ordinary originated
     * delta whose retraction is expressed as tombstones, which is the safer
     * translation: this replica re-emits only *novelty*, so a peer applying
     * `supersede = true` against that partial state would drop every
     * un-reasserted dot of the superseded source — including dots this replica
     * had no reason to mention. Tombstones converge without needing the mode.
     *
     * **The residual that choice leaves, stated rather than papered over.** The
     * fence is therefore *replica-local*: it binds only the replicas that
     * actually processed a notice. A dot of a superseded source held by a peer
     * that never saw one stays live there and can never reach a fenced replica
     * ([novelty] refuses it), so those two replicas do not re-converge on that
     * key. Forwarding the mode would close that hole and open the over-retraction
     * one above — the element-shaped family makes the opposite trade
     * ([civictech.cell.data.delta.TagState] via `UnionSetCell`, whose reactive
     * `outlet.call.propagate` forwards the notice transparently) and carries the
     * over-retraction instead. Neither hole is reachable through the shipped
     * wiring today: nothing emits a `TaggedMapDelta` re-baseline, and a replica
     * RESTART deliberately keeps its ref-derived [dotSource] rather than
     * superseding it, so no mesh source is ever fenced. Closing it properly needs
     * the notice to reach every replica as data (a fenced-source lattice on the
     * gossip mesh), which is 96 §E1 follow-on work, not this seam.
     *
     * **Revisit trigger (computenet-u7fi, KE3 decision 2026-09-06: residual
     * accepted PROVISIONALLY).** WHEN any change makes `OrMapCell` emit a
     * `TaggedMapDelta` re-baseline (`ReBaselineEmitting` entering this class's
     * supertype list) or supersedes a replica's ref-derived [dotSource], THEN
     * reopen `computenet-u7fi` BEFORE that change merges: the unreachability
     * above rests on exactly those two facts. The fenced-source lattice is
     * filed in `concord/corpus/DISPUTES.md` §42-WM-R14 and
     * `doc/spec/40-distribution/42-replication.md` §Open interactions, not
     * here.
     */
    private fun applyReBaseline(
        delta: TaggedMapDelta<K, V>,
        notice: ReBaselineNotice,
    ): Folded<K, V> = synchronized(stateLock) {
        if (!notice.supersede) {
            val n = novelty(delta)
            val absorbed = n.novel?.also { absorb(it) }
            return Folded(absorbed, withRepair(absorbed, n.fencedPuts))
        }

        // (a) retract — tombstone, don't delete
        val retracted = LinkedHashMap<K, Set<Timestamp>>()
        puts.keys.toList().forEach { key ->
            val reasserted = delta.puts[key]?.keys ?: emptySet<Timestamp>()
            val doomed = liveDots(key).keys.filterTo(LinkedHashSet()) {
                it.sourceId in notice.supersedes && it !in reasserted
            }
            if (doomed.isNotEmpty()) {
                dels.getOrPut(key) { LinkedHashSet() } += doomed
                retracted[key] = doomed
            }
        }
        // (b) union-merge the re-asserted/fresh state, past the DEAD-SOURCE
        // fence only — the re-admission fence still applies (9sm.8-D6), so a
        // reclaimed dot re-asserted by a re-baseline stays inert and is repaired
        // exactly as it is on the ordinary path.
        val n = novelty(delta, fenced = false)
        val novel = n.novel?.also { absorb(it) }
        // (c) fence the superseded sources
        deadSources += notice.supersedes

        if (novel == null && retracted.isEmpty() && n.fencedPuts.isEmpty()) return Folded(null, null)
        val delsOut = LinkedHashMap<K, Set<Timestamp>>()
        novel?.dels?.forEach { (key, dots) -> delsOut[key] = dots }
        retracted.forEach { (key, dots) ->
            delsOut.merge(key, dots) { a, b -> LinkedHashSet(a).also { it += b } }
        }
        // the retraction is absorbed state (it moved live dots into `dels`), so
        // it feeds the delivered lane exactly as it did before the fence landed;
        // only the repair is held back from that fold.
        val absorbed = TaggedMapDelta(novel?.puts ?: emptyMap(), delsOut)
        Folded(absorbed, withRepair(absorbed, n.fencedPuts))
    }

    /**
     * [applyRemote]'s two outputs, which are NOT the same delta once the
     * re-admission fence is in play (9sm.8-D6): [absorbed] is what this fold
     * actually took into [puts]/[dels] and is what feeds the delivered lane;
     * [emitted] is that plus the repair tombstone for every fenced put-dot, and
     * is what goes out on the wire. `null` on either means "nothing".
     */
    private class Folded<K, V>(
        val absorbed: TaggedMapDelta<K, V>?,
        val emitted: TaggedMapDelta<K, V>?,
    )

    /**
     * [novelty]'s two outputs: the new dot information ([novel], `null` when
     * there is none) and the put-dots the re-admission fence rejected
     * ([fencedPuts]) — which are not novelty, but are what [withRepair] answers.
     */
    private class Novelty<K, V>(
        val novel: TaggedMapDelta<K, V>?,
        val fencedPuts: Map<K, Set<Timestamp>>,
    )

    /**
     * Discard `dels` **entries** whose EVERY dot is at or below [frontier], per
     * source, and nothing else (decision 9sm.8-D7, `[KE3-31]` as amended by
     * computenet-v2ka; the dot-shaped form of `SetCell.compactBelow`).
     *
     * For each key `k`, the whole of `dels[k]` is discarded — and with it
     * `puts[k] ∩ dels[k]`, the covered put-dots AND THEIR VALUES, which is the
     * memory this reclaimer actually returns — **iff every dot in `dels[k]` is ≤
     * [frontier]**; otherwise the entry is left untouched in full. A key whose
     * map became empty is dropped from that map. A LIVE put-dot (present in
     * `puts`, absent from `dels`) is never in `dels[k]` and is never touched,
     * even when it is ≤ frontier — so [membership] and [value] are unchanged by
     * construction. A tombstone with no matching put is discarded like any other.
     *
     * **Every-dot, not per-dot, and that is the whole safety argument**
     * (computenet-v2ka). Since [MapOps.remove] and a re-put's retract half mint
     * a **del-dot** into the entry (9sm.8-D5), the entry's dot set contains not
     * only the put-dots the remove covered but a dot standing for the REMOVE
     * itself. Requiring *every* dot ≤ [frontier] therefore requires the del-dot
     * ≤ [frontier], and — because the delivered frontier is a max-CONTIGUOUS
     * per-source prefix fed from both lanes ([applyRemote]) — that means every
     * open member has delivered the remove, not merely the put. A per-dot
     * discard drops a tombstone as soon as the PUT under it is everywhere, which
     * is exactly the state a straggler holding the put and missing the remove
     * resurrects from.
     *
     * The `[KE3-30]` interlock / `[42-WM-05]` absent-row-is-bottom: a dot source
     * with no entry in [frontier] reads as bottom, so nothing of that source is
     * ever discarded.
     *
     * **What it DOES record: the re-admission fence.** Every dot discarded here
     * is recorded in [reclaimed], and [novelty] subtracts that set on BOTH
     * lanes. This function is the fence's ONLY writer — exactly what is
     * discarded is what is remembered, which is why a live put-dot can never
     * enter it.
     *
     * Nothing is emitted, and [dotCounter], [delivered] and [deliveryListeners]
     * are untouched. Runs entirely under [stateLock] and makes no outbound call.
     *
     * `internal`: reachable from `:kernel` tests only — a harness seam, not a
     * public capability.
     *
     * @return the total number of dots discarded (from `dels` plus the matching
     *   dots also removed from `puts`).
     */
    internal fun compactBelow(frontier: TagFrontier): Int = synchronized(stateLock) {
        var discarded = 0
        val emptiedDels = mutableListOf<K>()
        for ((key, delDots) in dels) {
            // EVERY dot, or none: an entry one of whose dots — the del-dot
            // included — is above the frontier is not certified delivered, and
            // discarding any part of it is what resurrects the key.
            val allCovered = delDots.isNotEmpty() && delDots.all { dot ->
                (frontier.perSource[dot.sourceId] ?: Long.MIN_VALUE) >= dot.counter
            }
            if (!allCovered) continue
            val covered = LinkedHashSet(delDots)
            covered.forEach { reclaimed.record(key, it) }
            delDots -= covered
            discarded += covered.size
            puts[key]?.let { putDots ->
                val putCovered = putDots.keys.filterTo(LinkedHashSet()) { it in covered }
                if (putCovered.isNotEmpty()) {
                    putDots.keys.removeAll(putCovered)
                    discarded += putCovered.size
                    if (putDots.isEmpty()) puts.remove(key)
                }
            }
            if (delDots.isEmpty()) emptiedDels += key
        }
        emptiedDels.forEach { dels.remove(it) }
        discarded
    }

    /**
     * Whether this replica's fence holds any dot at all for [key] — the
     * existence half of [fencedAmong], for a harness that wants to know *when*
     * a key entered the fence and has no dot to ask about (the del-dot it is
     * looking for is precisely the one [compactBelow] has just discarded).
     *
     * Read-only, additive, and consulted by no protocol path.
     */
    internal fun fencesAny(key: K): Boolean = synchronized(stateLock) { reclaimed.anyFor(key) }

    /**
     * Which of [dots] this replica has reclaimed **for [key]** — the direct read
     * of the fence's own state. A non-empty result on a replica MISSING the key
     * those dots make live elsewhere is the fence being the cause of that
     * divergence; an empty result on every such replica exonerates it, by
     * measurement rather than by inference about the workload.
     */
    internal fun fencedAmong(key: K, dots: Set<Timestamp>): Set<Timestamp> = synchronized(stateLock) {
        dots.filterTo(LinkedHashSet()) { reclaimed.holds(key, it) }
    }

    /**
     * Highest dot counter observed per dot source over `puts ∪ dels`,
     * restricted to the keys [scope] admits (spec 20/21 §Pull, 93 I-24) — the
     * currency a baseline reply reports. Tombstoned dots count, exactly as
     * `SetCell.currentFrontier` folds `adds ∪ dels`: a pull that skipped them
     * would let an incremental requester's `since` step past a tombstone it
     * never received. `null`/[Interest.Total] scope iterates every key.
     */
    private fun currentFrontier(scope: Interest? = null): TagFrontier = synchronized(stateLock) {
        val admit: (K) -> Boolean =
            if (scope == null || scope is Interest.Total) { _ -> true } else { key -> scope.admits(key) }
        val frontier = mutableMapOf<UUID, Long>()
        fun fold(dot: Timestamp) = frontier.merge(dot.sourceId, dot.counter, ::maxOf)
        puts.forEach { (key, dots) -> if (admit(key)) dots.keys.forEach(::fold) }
        dels.forEach { (key, dots) -> if (admit(key)) dots.forEach(::fold) }
        TagFrontier(frontier)
    }

    /**
     * Restrict a reply map to the keys [scope] admits (PN-3c) — the per-key
     * interest filter a partial-interest pull applies. The same map, unchanged,
     * for `null`/[Interest.Total] scope, so a scope-absent reply is verbatim.
     */
    private fun <T> scopedTo(source: Map<K, T>, scope: Interest?): Map<K, T> =
        if (scope == null || scope is Interest.Total) source else source.filterKeys { scope.admits(it) }

    /**
     * Is [since] below this replica's compaction floor for any source (`[KE3-35]`/`[KE3-36]`,
     * decision 9sm.8-D8, mirrored from `SetCell.belowFloor`)? Returns the first offending
     * `(source, since[source], floor[source])`, or `null` when the request can be answered
     * incrementally.
     *
     * The floor is derived from the re-admission fence — see [ReclaimedDots.floors]: `floor[s]`
     * is the highest counter this replica has ever DISCARDED for `s` (there is no persisted
     * per-source floor of its own — a per-source floor was MEASURED UNSAFE, `## KE3-GC-DEL-DOT`),
     * so a `since[s]` below it names dots this replica no longer remembers and a since-filtered
     * answer would silently omit them.
     *
     * "Below" is strict (`since[s] < floor[s]`), and a source ABSENT from [since] reads as `-1`
     * — [putsSince]/[delsSince]'s own default — so it is below floor iff the fence holds any run
     * for it. `since == null` is never below floor: it is already the full-state branch.
     *
     * Call under [stateLock]; the decision and the reply it governs are one snapshot (9sm.8-D8,
     * mirroring 9sm.7-D1).
     */
    private fun belowFloor(since: TagFrontier?): Triple<UUID, Long, Long>? = synchronized(stateLock) {
        if (since == null) return@synchronized null
        for ((source, floor) in reclaimed.floors()) {
            val asked = since.perSource[source] ?: -1L
            if (asked < floor) return@synchronized Triple(source, asked, floor)
        }
        null
    }

    /** Only the put-dots a [since] frontier has not observed; a copy, never an alias, when [since] is null. */
    private fun putsSince(since: TagFrontier?): Map<K, Map<Timestamp, V>> = synchronized(stateLock) {
        puts.mapValues { (_, dots) ->
            if (since == null) LinkedHashMap(dots)
            else dots.filterKeys { (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * Only the tombstoned dots a [since] frontier has not observed; a copy when [since] is null.
     *
     * **ENTRY-WHOLE** (`[24-TAG-04]`'s last sentence, decision 9sm.8-D8, mirrored from
     * `SetCell.sinceFilter`'s `wholeEntry` mode): a `dels[key]` entry is one indivisible fact —
     * the del-dot plus the put-dots it covers. Filtered per dot, a since-pull could ship the
     * del-dot alone (its counter is the highest in the entry, so it is the dot most likely to be
     * novel) while withholding the covers it certifies, letting the requester advance its
     * delivered frontier past the del-dot without having applied the remove it stands for. So for
     * a non-null [since] the filter decides per ENTRY: ship all of it if ANY dot in it is novel,
     * else none of it.
     */
    private fun delsSince(since: TagFrontier?): Map<K, Set<Timestamp>> = synchronized(stateLock) {
        dels.mapValues { (_, dots) ->
            if (since == null) LinkedHashSet(dots)
            else {
                val novel: (Timestamp) -> Boolean = { (since.perSource[it.sourceId] ?: -1L) < it.counter }
                if (dots.any(novel)) LinkedHashSet(dots) else emptySet()
            }
        }.filterValues { it.isNotEmpty() }
    }

    init {
        deltaInlet.serve(object : Propagate<TaggedMapDelta<K, V>> {
            override fun propagate(value: TaggedMapDelta<K, V>) = applyRemote(value)
        })
        // late-join catch-up (G-22) — and replica initial sync / anti-entropy
        // (M7.4): full dot state as one delta-from-empty, tombstones included,
        // to just the new subscriber — idempotent merge ([24-TMAP-01]) makes
        // replays harmless, and shipping the tombstones is what stops a late
        // joiner resurrecting a removed key.
        outlet.catchUpOnLinked {
            synchronized(stateLock) { if (puts.isEmpty() && dels.isEmpty()) null else state() }
        }
        // on-demand pull (spec 20/21 §Pull, decided in 93 I-16/I-24): a
        // single-wave state-as-delta reply, since-filtered, stamped as a
        // catch-up baseline (MessageContext.baseline) and delivered only to the
        // requester — never broadcast, never admitted to wave completeness.
        //
        // BELOW-FLOOR FALLBACK (`[KE3-35]`/`[KE3-36]`, decision 9sm.8-D8, mirrored from
        // `SetCell`'s pull-serve). A `since` that names, for ANY source, a counter below this
        // replica's compaction floor cannot be answered incrementally: the floor is the highest
        // counter this replica has DISCARDED for that source (derived from the re-admission
        // fence — see [ReclaimedDots.floors]; exact, and carrying no snapshot key of its own),
        // so the dots between `since` and the floor are gone and a since-filtered reply would
        // silently omit them. The honest answer is FULL state, which the requester's idempotent
        // merge absorbs — never a partial delta, never a per-source mix of full and partial, and
        // never a silently widened request. `[24-TAG-04]`'s compaction paragraph: "a
        // `StateRequest(since)` that asks for state below the compaction floor is answered with
        // full state."
        //
        // The fallback IS the existing `since = null` branch, verbatim (mirroring 9sm.7-D2): the
        // same `putsSince`/`delsSince`/`scopedTo` calls and the same `baselineTo` stamp, so there
        // is no new reply type, no `StateRequest` field and no wire change. There is no
        // `readBounded` half to mirror here — `OrMapCell` is `Stateful`, not `BoundedStateful`
        // (9sm.8-D8). The decision is decided under [stateLock] in the same hold that builds the
        // reply, and logged after the monitor is released, because logging is a foreign call.
        //
        // Independently of the floor, [delsSince] ships a `dels[key]` entry whole or not at all
        // (`[24-TAG-04]`'s last sentence) — see its KDoc.
        outlet.pullServe { request ->
            // the three halves of a reply are one snapshot: taken together
            // under the monitor, shipped after it is released.
            val outcome = synchronized(stateLock) {
                val below = belowFloor(request.since)
                val effectiveSince = if (below == null) request.since else null
                val putsOut = scopedTo(putsSince(effectiveSince), request.scope)
                val delsOut = scopedTo(delsSince(effectiveSince), request.scope)
                if (putsOut.isEmpty() && delsOut.isEmpty()) null
                else below to Triple(putsOut, delsOut, currentFrontier(request.scope))
            } ?: return@pullServe
            val reply = outcome.second
            outcome.first?.let { (source, asked, floor) ->
                // 9sm.8-D8 (mirroring 9sm.7-D5): the JDK's own logger — the log line is a
                // diagnostic, never the test oracle; the reply tap is.
                PULL_FLOOR_LOG.log(
                    System.Logger.Level.DEBUG,
                    "OrMapCell ${ref.id}: StateRequest(since) below compaction floor for source " +
                        "$source (since=$asked < floor=$floor) — answering with full state",
                )
            }
            baselineTo(request.replyTo, reply.third) {
                propagate(TaggedMapDelta(reply.first, reply.second))
            }
        }
    }

    // snapshot/restore (G-25 seam): keys and values must be Serializable. The
    // dot counter is state too (M10.2) — a checkpoint-restored instance must
    // not re-mint a spent dot, or a post-restore put could collide with a dot
    // the network still remembers (and a tombstone for the old one would then
    // cover the new value).
    //
    // The dead-source fence rides along (additive "dead" key, absent in an
    // E1-CORE-era snapshot and read defensively): `Replication.rebind` carries a
    // replica's state across a promotion swap through exactly this seam, and a
    // candidate that woke without the fence would re-admit a superseded
    // source's straggler dots the incumbent had already refused.
    /**
     * **THE RECLAIMER'S ONLY PRODUCTION CALLER** (`[KE3-30]`/`[KE3-31]`,
     * decisions 9sm.6-D1/9sm.8-D7).
     *
     * Reads the installed stability read ([onStability]) and, if one is
     * installed and answers, runs [compactBelow] at that frontier **before**
     * serialising — both under one hold of [stateLock].
     *
     * There is **no `compact: Boolean` parameter**, so reclamation rides every
     * caller of `Stateful.snapshot()` — `HostDurability.checkpoint`,
     * `ManagedHost.snapshotOf`, migration, promotion state transfer, the concord
     * driver's raw `snapshot` verb. `[KE3-30]` makes the FRONTIER the sole
     * authority for a discard ("no other condition SHALL authorise a discard"),
     * and the identity of the caller is exactly such an other condition; and
     * every `snapshot()` is a moment at which the persisted dot maps must agree
     * with the persisted `"reclaimed"` fence, which compacting first and
     * serialising second under one monitor hold is what guarantees. The admitted
     * consequence: an observer read can reclaim — still gated on causal
     * stability, so it discards nothing an ordinary checkpoint could not have
     * discarded a moment later.
     *
     * **Why the frontier is read OUTSIDE the lock.** The read is a foreign call
     * — it walks the delivered-watermark companion — and [stateLock]'s KDoc
     * forbids holding the monitor across one. That makes it a conservative
     * under-read and never a hazard: per-source stability is monotone, so a
     * frontier read a few instructions early can only be *lower* than the truth
     * at discard time, i.e. it can only decline a discard the next pass will
     * make. [compactBelow] re-enters the same monitor this function already
     * holds (`synchronized` is reentrant), so compaction and serialisation
     * observe one state with no window between them.
     *
     * 9sm.6-D4's R14 interlock is NOT vacuous here, unlike on `SetCell`: this
     * cell has [deadSources], a source-shaped fence that is a DIFFERENT
     * mechanism from [reclaimed] despite the shared name. They do not interact —
     * [deadSources] gates admission by source, [reclaimed] by `(key, dot)` — and
     * neither reads the other.
     */
    override fun snapshot(): Serializable {
        // Outside the monitor, deliberately: see "Why the frontier is read
        // OUTSIDE the lock" above.
        val read = synchronized(stateLock) { stabilityRead }
        val frontier = read?.invoke()
        return synchronized(stateLock) {
            // `[KE3-30]`: the frontier is the only authority, and a null read
            // (no `Replication`, or nothing certifiable) discards nothing.
            if (frontier != null) compactBelow(frontier)
            snapshotLocked()
        }
    }

    /** The serialisation half of [snapshot]. Call under [stateLock]. */
    private fun snapshotLocked(): Serializable =
        HashMap(
            mapOf(
                "puts" to HashMap(puts.mapValues { LinkedHashMap(it.value) }),
                "dels" to HashMap(dels.mapValues { LinkedHashSet(it.value) }),
                "counter" to dotCounter,
                "dead" to LinkedHashSet(deadSources),
                // The re-admission fence is state too (9sm.8-D6): a
                // checkpoint-restored replica that forgot what it had reclaimed
                // would re-admit the next replayed frame exactly as an unfenced
                // one does. Additive — [restore] treats an absent key as an
                // empty fence, so a pre-fence checkpoint still loads.
                //
                // Adding a key here is not local on the OR-SET
                // (`SetCell.snapshotLocked`'s "ADDING A KEY HERE IS NOT LOCAL":
                // `civictech.inspect.ValueEncoder.orSetMembership` recognises an
                // OR-set by its key set). It IS local here: that encoder
                // requires `{adds, dels, counter}` and an OR-map snapshot
                // `{puts, …}` already falls through to raw, so `"reclaimed"`
                // conscripts no `:inspect` file (verified ValueEncoder.kt
                // :63-75, :315-317).
                "reclaimed" to reclaimed.save(),
            )
        )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) = synchronized(stateLock) {
        val maps = state as Map<String, Any>
        puts.clear()
        dels.clear()
        deadSources.clear()
        (maps.getValue("puts") as Map<K, Map<Timestamp, V>>)
            .forEach { (key, dots) -> puts[key] = LinkedHashMap(dots) }
        (maps.getValue("dels") as Map<K, Set<Timestamp>>)
            .forEach { (key, dots) -> dels[key] = LinkedHashSet(dots) }
        dotCounter = maps["counter"] as? Long ?: 0L
        (maps["dead"] as? Set<UUID>)?.let { deadSources += it }
        reclaimed.restore(maps["reclaimed"]) // absent on a pre-fence checkpoint: an empty fence
        Unit
    }

    companion object {
        fun <K, V> create(): OrMapApi<K, V> = OrMapCell()
    }
}
