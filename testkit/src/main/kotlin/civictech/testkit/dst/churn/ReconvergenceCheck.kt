package civictech.testkit.dst.churn

import civictech.cell.CellRef
import civictech.cell.data.Replicable
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.verify.ReplicaConvergence
import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstWorld
import java.util.WeakHashMap

/**
 * The convergence invariants of the churn mesh, one per observing peer.
 *
 * ## Why one per peer, and why they are `civictech.cell.verify.ReplicaConvergence`
 *
 * [CHA3-12] forbids reimplementing membership filtering: live membership is
 * `LocationRegistry.replicasOf(logicalId)` and the judgement is
 * [ReplicaConvergence.converged], whose departed-stream rule *is* the `[42-REPL-06]` exclusion
 * this task has to check. (`ReplicaConvergence.liveRefs()` is **private**, so "compose with
 * liveRefs" can only mean composing with the two public surfaces that already apply it —
 * [ReplicaConvergence.converged] and [ReplicaConvergence.states]; nothing here re-derives the
 * live set for the judgement.)
 *
 * Every churn peer keeps its **own** [civictech.cell.host.LocationRegistry] (see [MeshPeer]), so
 * "which replicas are live" is a per-peer fold and there is no single privileged answer — peers
 * MAY transiently disagree, and 42's own argument (decided 93 I-3) says gossip idempotence makes
 * that safe. A single convergence over one registry would therefore have quietly chosen one
 * peer's opinion as the truth. One convergence per peer registry instead makes the disagreement
 * itself observable, which is exactly what BS-8 asks for, and each attaches to *every* replica's
 * local delta outlet — an in-process link, no proxy hop, the same shape `ReplicationTest` uses.
 *
 * ## Attachment is opt-in, and that is deliberate
 *
 * Attaching subscribes a fold to every replica's outlet, which is observable work inside the run.
 * A churn mesh built by a suite that is not checking reconvergence must be unaffected by this
 * file existing, so nothing attaches unless the run is wrapped in [observing]. A
 * [ReconvergenceCheck] run over an unobserved world fails loudly rather than judging an empty
 * fold set — the same fail-with-the-remedy shape as [ChurnMesh.observerOf].
 */
object MeshConvergences {

    private val byWorld = WeakHashMap<DstWorld, MutableMap<String, ReplicaConvergence<Any, Any>>>()
    private var arm = 0

    /**
     * Run [block] with churn-mesh replicas observed by a per-peer [ReplicaConvergence].
     *
     * Nesting-safe (a counter, not a flag), so a sweep may wrap a whole seed range.
     */
    fun <T> observing(block: () -> T): T {
        synchronized(this) { arm++ }
        try {
            return block()
        } finally {
            synchronized(this) { arm-- }
        }
    }

    /** The convergences declared on [world], or null when the run was not [observing]. */
    @Synchronized
    fun declaredOn(world: DstWorld): Map<String, ReplicaConvergence<Any, Any>>? = byWorld[world]?.toMap()

    /** The convergence [peer] observes the mesh through, or null when the run was not [observing]. */
    @Synchronized
    fun of(world: DstWorld, peer: String): ReplicaConvergence<Any, Any>? = byWorld[world]?.get(peer)

    /**
     * Attach [cell] to every observing peer's convergence. Called from [MeshPeer] on each spawn —
     * a rejoin therefore re-attaches, which resets that ref's fold to the initial value and lets
     * the joiner's catch-up stream rebuild it ([CHA3-14]).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun onSpawn(world: DstWorld, peer: MeshPeer, cell: Replicable<*>) {
        val observers = synchronized(this) {
            if (arm == 0) return
            byWorld.getOrPut(world) { declare(world, peer) }.values.toList()
        }
        observers.forEach { it.attach(cell as Replicable<Any>) }
    }

    private fun declare(world: DstWorld, peer: MeshPeer): MutableMap<String, ReplicaConvergence<Any, Any>> {
        val dataId = peer.ref.id
        val initial = initialFold(peer.payload)
        return MeshPeers.all(world).associateTo(linkedMapOf()) { p ->
            p.name to ReplicaConvergence(p.registry, dataId, initial, ::mergeFold)
        }
    }

    private fun initialFold(payload: MeshPayload): Any = when (payload) {
        MeshPayload.SET -> SetDelta<String>()
        MeshPayload.PN_COUNTER -> PnCounterDelta()
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeFold(state: Any, delta: Any): Any = when (state) {
        is SetDelta<*> -> (state as SetDelta<Any>).merge(delta as SetDelta<Any>)
        is PnCounterDelta -> state.merge(delta as PnCounterDelta)
        else -> error("unknown churn-mesh fold state ${state::class.simpleName}")
    }

    /**
     * Project a folded delta stream onto the value the batch reference speaks in.
     *
     * The projection is the mergeable family's own outcome rule — an OR-set element is present
     * iff it holds an add-tag no del-tag covers, a PN counter is increments minus decrements —
     * and nothing else. It is not a second implementation of the cells: the *state* being
     * projected was produced by merging the cells' own emitted deltas.
     */
    @Suppress("UNCHECKED_CAST")
    fun project(state: Any): ReferenceFold = when (state) {
        is SetDelta<*> -> {
            val set = state as SetDelta<Any>
            ReferenceFold.Elements(
                set.adds
                    .filterValues { it.isNotEmpty() }
                    .filter { (element, tags) -> (tags - (set.dels[element] ?: emptySet())).isNotEmpty() }
                    .keys
                    .map { it.toString() }
                    .toSet(),
            )
        }

        is PnCounterDelta -> ReferenceFold.Total(state.incs.values.sum() - state.decs.values.sum())
        else -> error("unknown churn-mesh fold state ${state::class.simpleName}")
    }
}

/**
 * The reconvergence property ([CHA3-10], [CHA3-11], [CHA3-13], [CHA3-14]): at quiescence after
 * an arbitrary churn plan, every replica **still counted as live membership** exposes one fold,
 * and that fold is the batch reference over the operations the surviving replicas accepted.
 *
 * ## What it asserts, in order, and against what
 *
 *  1. **Every observing peer agrees on live membership.** Peers MAY disagree *transiently* (93
 *     I-3); at quiescence the disagreement must be gone, or the mesh has not reconverged at all
 *     and every fold comparison below would be comparing different questions (BS-8).
 *  2. **[CHA3-10]** — for each observing peer, [ReplicaConvergence.converged] holds. That call,
 *     not a set built here, is the judgement: its departed-stream rule already excludes a
 *     replica that left ([CHA3-13]), and this check never rebuilds it. The live refs are named
 *     only to *report* which folds differ and to project the agreed value.
 *  3. **[CHA3-11]** — the agreed fold equals [BatchReference]'s fold over the operations the
 *     surviving replicas accepted. Replica-vs-replica agreement alone is explicitly not enough:
 *     an empty mesh agrees with itself. The reference is recorded at the issuing site
 *     ([AcceptedOp]), so a lost or invented operation is visible.
 *  4. **[CHA3-14]** falls out of 2 and 3 rather than being a fourth rule: a replica that joined
 *     after deltas had already gossiped is judged by exactly the same live-membership test as one
 *     present from the start, so a joiner that had not caught up fails (2) and a joiner missing
 *     operations fails (3).
 *
 * ## The two bounds, and when they are one equality
 *
 * See [BatchReference]: `required` is the live replicas, `permitted` adds every departed one —
 * whether a departing replica's last operations left with it is a race this harness does not
 * control, and the BS-1 sweep measured an orderly eviction losing an element accepted one step
 * earlier. With nothing departed the two sets coincide and this is a plain equality.
 *
 * ## Two boundaries a caller must know, stated here rather than only on the bead
 *
 *  - **An unhealed partition at quiescence is a divergence, and this check will say so.** A
 *    `PARTITION_SUSPEND` peer is parked, not unpublished: it stays in `replicasOf` while its
 *    writes cannot reach anyone. That is a partitioned mesh, not a reconverged one, so a plan
 *    that means to check reconvergence heals before it quiesces.
 *  - **An unclean crash without a rejoin is likewise a divergence.** The kernel has no failure
 *    detector: a crashed peer never unpublishes, so its frozen fold stays inside live membership
 *    and legitimately fails (2). `CRASH_UNCLEAN` belongs in a reconvergence plan together with
 *    the rejoin that answers it.
 *
 * ## Failure messages are fixed strings ([CHA3-40])
 *
 * Every failure below is a [ChurnCheckFailure] whose *identity* is one of the constants on the
 * companion and whose replicas, keys and counts live in [ChurnCheckFailure.detail], reachable as
 * a suppressed throwable. `PlanShrinker`'s default `FailurePredicate` compares the failing
 * check's message, so a replica name or an element count in the identity would silently discard
 * every genuine reduction as "a different failure" (measured on CHA1; the same split
 * `SweepFailure` landed for `computenet-umx.4`). Registering the check ([registered]) is what
 * lets a `DstArtifact` name it, so the failing run replays with the property attached.
 *
 * @param membershipFilter **a control seam, not a knob.** It selects which attached refs the
 *   reported comparison names, and defaults to the departed-stream rule (`replicasOf ∩ attached`)
 *   — the same set [ReplicaConvergence.converged] judges over. The controls task amends this file
 *   to construct a variant judgement (a filter that keeps departed refs, so that disabling the
 *   departed-stream rule can be shown to make an orderly departure read as a divergence) without
 *   touching kernel code. Production callers use [of].
 */
class ReconvergenceCheck internal constructor(
    private val payload: MeshPayload,
    private val membershipFilter: (replicasOf: Set<CellRef>, attached: Set<CellRef>) -> Set<CellRef>,
) : DstCheck {

    override fun verify(world: DstWorld) {
        val peers = MeshPeers.all(world)
        check(peers.isNotEmpty()) { "no churn peers were declared on this world — ChurnMesh.spec(...) declares them" }
        val convergences = MeshConvergences.declaredOn(world)
            ?: throw IllegalStateException(
                "no reconvergence observation was declared on this world — " +
                    "MeshConvergences.observing { ... } around the run is what declares it",
            )
        val dataId = peers.first().ref.id
        val observers = peers.filter { it.member }
        if (observers.isEmpty()) return

        assertOneMembership(observers, dataId)

        val reference = BatchReference.of(world, payload)
        val required = requiredPeers(peers)
        val permitted = required + peers.filter { it.lastDeparture != null }.map { it.name }
        val requiredFold = reference.foldOf(required)
        val permittedFold = reference.foldOf(permitted)

        observers.forEach { observer ->
            val convergence = convergences[observer.name] ?: return@forEach
            val states = convergence.states()
            val judged = membershipFilter(observer.registry.replicasOf(dataId), states.keys)
            val folds = judged.mapNotNull { ref -> states[ref]?.let { ref to MeshConvergences.project(it) } }
            val values = folds.map { it.second }.toSet()

            if (values.size > 1 || !convergence.converged()) {
                throw ChurnCheckFailure(DIVERGED, detail = divergence(observer, folds, states.keys - judged))
            }
            val actual = values.singleOrNull() ?: return@forEach

            requiredFold.shortfallIn(actual)?.let { missing ->
                throw ChurnCheckFailure(
                    LOST,
                    detail = "observer=${observer.name} missing $missing; observed ${actual.render()}; " +
                        "reference over surviving replicas $required is ${requiredFold.render()}",
                )
            }
            actual.shortfallIn(permittedFold)?.let { extra ->
                throw ChurnCheckFailure(
                    INVENTED,
                    detail = "observer=${observer.name} holds $extra beyond every accepted operation; " +
                        "observed ${actual.render()}; reference over ${permitted.sorted()} is " +
                        permittedFold.render(),
                )
            }
        }
    }

    /**
     * Which peers' accepted operations the survivors still owe: those still counted as members.
     *
     * A departed replica's last operations are in the *permitted* arm instead, orderly departure
     * included — see [BatchReference] for the measurement behind that, and for which test asserts
     * the stronger handoff claim where the harness controls the race.
     */
    private fun requiredPeers(peers: List<MeshPeer>): Set<String> =
        peers.filter { it.member }.map { it.name }.toSet()

    private fun assertOneMembership(observers: List<MeshPeer>, dataId: java.util.UUID) {
        val byObserver = observers.associate { it.name to it.registry.replicasOf(dataId) }
        if (byObserver.values.toSet().size > 1) {
            throw ChurnCheckFailure(
                MEMBERSHIP,
                detail = byObserver.entries.sortedBy { it.key }
                    .joinToString("; ") { (name, refs) -> "$name sees ${refs.map { it.instanceId }.sorted()}" },
            )
        }
    }

    private fun divergence(
        observer: MeshPeer,
        folds: List<Pair<CellRef, ReferenceFold>>,
        departed: Set<CellRef>,
    ): String {
        val grouped = folds.groupBy({ it.second }, { it.first })
        val pairwise = grouped.keys.toList().let { values ->
            if (values.size < 2) {
                "converged() rejected the folds while this check saw ${values.size} distinct value(s) — " +
                    "the live set and the judged set disagree"
            } else {
                val (a, b) = values[0] to values[1]
                "replicas ${grouped.getValue(a).map { it.instanceId }.sorted()} hold ${a.render()}, " +
                    "replicas ${grouped.getValue(b).map { it.instanceId }.sorted()} hold ${b.render()}; " +
                    "differing: ${a.shortfallIn(b) ?: "-"} / ${b.shortfallIn(a) ?: "-"}"
            }
        }
        return "observer=${observer.name}; $pairwise; " +
            "excluded departed folds: ${departed.map { it.instanceId }.sorted()}"
    }

    companion object {

        /** [CHA3-10]: two live replicas at quiescence expose different folds. */
        const val DIVERGED = "churn reconvergence violated: live replicas expose different folds"

        /** [CHA3-11], lower bound: an operation a surviving replica accepted is not in the fold. */
        const val LOST =
            "churn reconvergence violated: the converged fold is missing operations surviving replicas accepted"

        /** [CHA3-11], upper bound: the fold holds something no replica ever accepted. */
        const val INVENTED = "churn reconvergence violated: the converged fold holds unaccepted operations"

        /** Peers still disagree about who is live once the run has quiesced (BS-8). */
        const val MEMBERSHIP = "churn reconvergence violated: peers disagree on live membership at quiescence"

        /** The departed-stream rule as a filter: exactly the set [ReplicaConvergence.converged] judges. */
        val DEPARTED_STREAM_RULE: (Set<CellRef>, Set<CellRef>) -> Set<CellRef> =
            { replicasOf, attached -> replicasOf intersect attached }

        /** The check as a suite uses it. */
        fun of(payload: MeshPayload = MeshPayload.SET): ReconvergenceCheck =
            ReconvergenceCheck(payload, DEPARTED_STREAM_RULE)

        /** The stable [CheckRegistry] id a `DstArtifact` records for [payload]'s reconvergence check. */
        fun idFor(payload: MeshPayload): String = "churn-reconvergence-${payload.name.lowercase()}"

        /**
         * [of], registered under [idFor] so a failing run's artifact carries the property
         * ([CHA3-40]). Re-registers, because a suite may arm the same check in several tests.
         */
        fun registered(payload: MeshPayload = MeshPayload.SET): DstCheck {
            val id = idFor(payload)
            CheckRegistry.unregister(id)
            return CheckRegistry.register(id, of(payload))
        }
    }
}
