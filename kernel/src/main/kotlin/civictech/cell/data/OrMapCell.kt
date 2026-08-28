package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.PendingReBaseline
import civictech.cell.Propagate
import civictech.cell.ReBaselineNotice
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
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
 * for the fold rule. Not here: the admission check refusing non-idempotent
 * embedded values (separate follow-on work), `TaggedMapView`/`UntagCell`
 * adapters (§E1.5), and delivered-watermark tracking
 * ([civictech.cell.data.delta.DeliveryTracking], E3.3).
 */
class OrMapCell<K, V>(ref: CellRef = CellRef(UUID.randomUUID())) :
    OrMapCellBase<K, V>(ref), Stateful, Replicable<TaggedMapDelta<K, V>> {

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
    // ponytail: dot sets grow monotonically; compaction is future work (G-25),
    // exactly as for SetCell's tag sets.
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
     * when the key is absent. Delegated the same way as [value].
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
            // reset-remove's local half: everything this writer currently sees
            // live at the key dies in the SAME delta that carries the fresh dot
            // (KeyedSetCell's atomic retract+add, lifted to dots). The fold
            // happens under `stateLock`; the propagation after it, never under.
            val (dot, observed) = synchronized(stateLock) {
                val seen = LinkedHashSet(liveDots(key).keys)
                val minted = Timestamp(dotSource, ++dotCounter)
                puts.getOrPut(key) { LinkedHashMap() }[minted] = value
                if (seen.isNotEmpty()) dels.getOrPut(key) { LinkedHashSet() } += seen
                minted to seen
            }
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
            val observed = synchronized(stateLock) {
                val seen = LinkedHashSet(liveDots(key).keys)
                // effective-only (21): removing a key with no live dot is a no-op
                if (seen.isEmpty()) return
                dels.getOrPut(key) { LinkedHashSet() } += seen
                seen
            }
            outlet.call.propagate(TaggedMapDelta(dels = mapOf(key to observed)))
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
     * from the very sources it supersedes.
     */
    private fun novelty(delta: TaggedMapDelta<K, V>, fenced: Boolean = true): TaggedMapDelta<K, V>? = synchronized(stateLock) {
        val freshPuts = LinkedHashMap<K, Map<Timestamp, V>>()
        delta.puts.forEach { (key, dots) ->
            val known = puts[key]
            val fresh = dots.filterKeys { dot ->
                (!fenced || dot.sourceId !in deadSources) && known?.containsKey(dot) != true
            }
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
            val fresh = dots.filterTo(LinkedHashSet()) { known?.contains(it) != true }
            if (fresh.isNotEmpty()) freshDels[key] = fresh
        }
        if (freshPuts.isEmpty() && freshDels.isEmpty()) return null
        TaggedMapDelta(freshPuts, freshDels)
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
        // read before originating: `originate` clears the current context, so
        // the notice must be taken off the arriving wave first.
        val notice = CurrentContext.get()?.reBaseline
        // one atomic fold: novelty and its absorption must not straddle another
        // writer, and no outbound call happens under the monitor.
        val effective = synchronized(stateLock) {
            if (notice != null) applyReBaseline(delta, notice)
            else novelty(delta)?.also { absorb(it) }
        }
        if (effective == null) return // echo terminates here
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
     */
    private fun applyReBaseline(
        delta: TaggedMapDelta<K, V>,
        notice: ReBaselineNotice,
    ): TaggedMapDelta<K, V>? = synchronized(stateLock) {
        if (!notice.supersede) return novelty(delta)?.also { absorb(it) }

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
        // (b) union-merge the re-asserted/fresh state, past the fence
        val novel = novelty(delta, fenced = false)?.also { absorb(it) }
        // (c) fence the superseded sources
        deadSources += notice.supersedes

        if (novel == null && retracted.isEmpty()) return null
        val delsOut = LinkedHashMap<K, Set<Timestamp>>()
        novel?.dels?.forEach { (key, dots) -> delsOut[key] = dots }
        retracted.forEach { (key, dots) ->
            delsOut.merge(key, dots) { a, b -> LinkedHashSet(a).also { it += b } }
        }
        TaggedMapDelta(novel?.puts ?: emptyMap(), delsOut)
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

    /** Only the put-dots a [since] frontier has not observed; a copy, never an alias, when [since] is null. */
    private fun putsSince(since: TagFrontier?): Map<K, Map<Timestamp, V>> = synchronized(stateLock) {
        puts.mapValues { (_, dots) ->
            if (since == null) LinkedHashMap(dots)
            else dots.filterKeys { (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }
    }

    /** Only the tombstoned dots a [since] frontier has not observed; a copy when [since] is null. */
    private fun delsSince(since: TagFrontier?): Map<K, Set<Timestamp>> = synchronized(stateLock) {
        dels.mapValues { (_, dots) ->
            if (since == null) LinkedHashSet(dots)
            else dots.filterTo(LinkedHashSet()) { (since.perSource[it.sourceId] ?: -1L) < it.counter }
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
        outlet.pullServe { request ->
            // the three halves of a reply are one snapshot: taken together
            // under the monitor, shipped after it is released.
            val reply = synchronized(stateLock) {
                val putsOut = scopedTo(putsSince(request.since), request.scope)
                val delsOut = scopedTo(delsSince(request.since), request.scope)
                if (putsOut.isEmpty() && delsOut.isEmpty()) null
                else Triple(putsOut, delsOut, currentFrontier(request.scope))
            } ?: return@pullServe
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
    override fun snapshot(): Serializable = synchronized(stateLock) {
        HashMap(
            mapOf(
                "puts" to HashMap(puts.mapValues { LinkedHashMap(it.value) }),
                "dels" to HashMap(dels.mapValues { LinkedHashSet(it.value) }),
                "counter" to dotCounter,
                "dead" to LinkedHashSet(deadSources),
            )
        )
    }

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
        Unit
    }

    companion object {
        fun <K, V> create(): OrMapApi<K, V> = OrMapCell()
    }
}
