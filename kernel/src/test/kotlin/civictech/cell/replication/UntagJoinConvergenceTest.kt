package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.MapCell
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.UntagCell
import civictech.cell.data.view.MapView
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * 96 §E1.5 (adapter half) — the **E1.5 acceptance property**: BS-12 of feature
 * computenet-j2x.3, with the `[KE1-27]` divergence control that gives it teeth.
 *
 * `OrMapCell` converges and `CombineLatestCell` does not (G-23: "delta merges
 * are arrival-order biased; not replica-stable"), so the question this file
 * answers is whether [UntagCell] is a sound bridge between them — whether an
 * `OrMapCell → UntagCell → CombineLatestCell` chain, driven from a gossiping
 * mesh, is replica-stable *without* changing the join.
 *
 * What is proven here:
 *
 * - **BS-12** (`[KE1-24]`, `[KE1-33]`): two peers gossip one replicated
 *   OR-map across a bridge that partitions and heals; each peer runs its own
 *   `UntagCell → CombineLatestCell` against a **locally-identical** second
 *   input. At idle both peers' combine outputs are equal to each other and to
 *   an **independent batch recompute** — the gossiped deltas merged by the dot
 *   algebra, read as `{k → value(k)}`, and pushed through the same `combine`
 *   function outside any cell. Never the cell's own output read twice.
 * - **The `[KE1-27]` control**: the *same* schedule driven through raw
 *   [MapCell] + [MapView] — the untagged pair the join family speaks natively —
 *   diverges. The control is asserted to **trip**; a passing control would be a
 *   broken test, so this file would rather fail than soften it into "may
 *   differ".
 * - **BS-13's regression stance** (`[KE1-38]`, `[KE1-39]`) is *not* re-proved
 *   here. It is proved by
 *   [civictech.cell.replication.OrMapConvergenceTest]'s diamond-dedup and
 *   re-origination tests and the `42-REPL-01` corpus scenario staying green
 *   **unmodified** — nothing in this file touches either.
 *
 * ## Why the control is a fair comparison and not a strawman
 *
 * Both paths receive the same op schedule ([schedule]), a pure function of the
 * seed. The only difference is *how the writes reach a peer*:
 *
 * - Under the OR-map, gossip carries dots, and the mesh's delivery order is
 *   whatever the seeded [SimulationController] produces — the peers converge
 *   because the dot algebra is order-blind, not because the orders agree.
 * - Under raw [MapCell] the peer has no dots, so the only thing it can do with
 *   a write is apply it on arrival. [arrivalOrder] models exactly what the
 *   bridge does to that arrival order: a peer sees **its own** writes at once,
 *   while the other peer's writes made during the partition window reach it
 *   only at the heal. That is a real mesh delivery order, not a shuffle — the
 *   two peers hold the same op multiset and differ only where the partition
 *   put them.
 *
 * So the control isolates the one variable G-23 names: order sensitivity. This
 * is the same discipline as `OrMapConvergenceTest`'s
 * `control - the same gossip traffic applied untagged, in arrival order,
 * diverges on at least one seed`, moved from the fold to a real
 * `MapCell → CombineLatestCell → MapView` chain.
 *
 * ## Schedule note (recorded because the number is load-bearing)
 *
 * [HEAL] is late (op 36 of 40) deliberately. Divergence under the raw path
 * survives only for keys whose *last* write falls inside the partition window:
 * every key rewritten in the common post-heal tail converges again by
 * last-writer-wins, so a short tail is what keeps the control's teeth. With the
 * heal at op 30 the tail is 11 writes over 3 keys and the control would be
 * expected to trip on only a couple of seeds in fifty — too near zero to rely
 * on. This is the "construct a schedule that forces the interleaving" branch of
 * the feature's honesty clause, applied to the *schedule*; no seed was
 * exchanged for a friendlier one, and the assertion is unconditional.
 */
class UntagJoinConvergenceTest {

    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    // =====================================================================
    // the schedule — one pure function of the seed, driven down both paths
    // =====================================================================

    /**
     * One write in the session.
     *
     * @param writer which peer issues it — the peer whose replica it lands on
     *   first, and (in the control) the peer that does not have to wait for the
     *   heal to see it.
     * @param partitioned whether it falls inside the partition window.
     * @param partitions / [heals] whether the bridge changes state before it.
     */
    private data class Op(
        val writer: Int,
        val key: String,
        val put: Boolean,
        val value: String,
        val steps: Int,
        val partitioned: Boolean,
        val partitions: Boolean = false,
        val heals: Boolean = false,
    )

    /**
     * The seeded write schedule. Deliberately plants the two hard cases inside
     * the partition window, where the writers cannot have observed each other:
     * a same-key put on **both** replicas (one dot-order winner must emerge
     * everywhere) and a put racing a remove (add-wins, reset-remove).
     */
    private fun schedule(seed: Long): List<Op> {
        val rnd = Random(seed)
        val ops = ArrayList<Op>()
        for (index in 1..OPS) {
            when (index) {
                CONCURRENT -> (0 until PEERS).forEach { w ->
                    ops += Op(w, "milk", true, "concurrent-$w", 0, partitioned = true)
                }

                RACED -> {
                    ops += Op(0, "eggs", true, "raced-put", 0, partitioned = true)
                    ops += Op(1, "eggs", false, "", 0, partitioned = true)
                }
            }
            val who = rnd.nextInt(PEERS)
            val key = KEYS[rnd.nextInt(KEYS.size)]
            val put = rnd.nextInt(10) < 7
            ops += Op(
                writer = who,
                key = key,
                put = put,
                value = "w$who-op$index",
                steps = rnd.nextInt(4),
                partitioned = index in PARTITION until HEAL,
                partitions = index == PARTITION,
                heals = index == HEAL,
            )
        }
        return ops
    }

    /**
     * The order in which peer [peer] *observes* the schedule when the bridge is
     * down for the partition window: its own writes land as issued; the other
     * peer's window writes are held by the broken bridge and all arrive at the
     * heal. The untagged path has nothing but this order to go on.
     */
    private fun arrivalOrder(ops: List<Op>, peer: Int): List<Op> {
        val out = ArrayList<Op>()
        val held = ArrayList<Op>()
        ops.forEach { op ->
            if (op.heals) {
                out += held
                held.clear()
            }
            if (op.partitioned && op.writer != peer) held += op else out += op
        }
        out += held
        return out
    }

    // =====================================================================
    // the join under test — one combine function, used by both cells and the
    // independent batch recompute
    // =====================================================================

    /**
     * Outer and total: a key held by only one side still produces output, so a
     * key the OR-map removed remains visible as `null@price` rather than
     * vanishing — which keeps removals observable in the compared subject
     * instead of collapsing them into absence.
     */
    private fun combineOf(tier: String?, price: String?): String = "$tier@$price"

    /**
     * The locally-identical second input, seeded the same on every peer. Holds
     * one key (`butter`) the OR-map never writes, so the outer branch is
     * exercised on every seed.
     */
    private val prices = mapOf(
        "milk" to "1.00",
        "eggs" to "2.00",
        "bread" to "3.00",
        "butter" to "4.00",
    )

    /**
     * The **independent** batch recompute (BS-12's second subject): merge the
     * gossiped deltas by the dot algebra, read `{k → value(k)}` off the merge,
     * and apply `combine` outside any cell. Nothing here reads a
     * [UntagCell] or a [CombineLatestCell] — comparing the chain to this is a
     * comparison against a second implementation, not against itself.
     */
    private fun batchRecompute(deltas: List<TaggedMapDelta<String, String>>): Map<String, String> {
        val merged = deltas.fold(TaggedMapDelta<String, String>()) { acc, d -> acc.merge(d) }
        val left = merged.membership().associateWith { merged.value(it) }
        return (left.keys + prices.keys).associateWith { combineOf(left[it], prices[it]) }
    }

    // =====================================================================
    // the two-peer session: OrMapCell → UntagCell → CombineLatestCell
    // =====================================================================

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
        val deadLetters = mutableListOf<DeadLetter>()

        init {
            listOf(host, bridgeHost).forEach { h ->
                h.deadLetterOutlet.subscribe(
                    Use.fixed(Propagate<DeadLetter> { deadLetters += it }, PortRef.generate())
                )
            }
        }
    }

    private data class Session(
        /** Each peer's `CombineLatestCell` output, folded by its own [MapView]. */
        val joined: List<Map<String, String>>,
        /** Each peer's replica's own `(membership, value)` view — the chain's input. */
        val replicaViews: List<Map<String, String?>>,
        val gossip: List<TaggedMapDelta<String, String>>,
        val deadLetters: List<DeadLetter>,
    )

    /** Record a replica's broadcast emissions — exactly what the mesh gossips. */
    private fun record(cell: OrMapCell<String, String>): MutableList<TaggedMapDelta<String, String>> {
        val out = mutableListOf<TaggedMapDelta<String, String>>()
        cell.outlet.subscribe(
            Use.fixed(Propagate<TaggedMapDelta<String, String>> { out += it }, PortRef.generate())
        )
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun runSession(seed: Long, heal: Boolean): Session {
        val controller = SimulationController(seed)
        val peers = List(PEERS) { Peer(controller) }
        val bridge = Peering.loopback(peers[0].side, peers[1].side)

        // derived from the seed, not random: the dot source is derived from the
        // ref, so every (counter, sourceId) tie-break is reproducible per seed
        val logicalId = UUID(seed, SALT)
        val replicas = peers.mapIndexed { i, peer ->
            OrMapCell<String, String>(CellRef(logicalId, i.toLong()))
                .also { peer.replication.replicate(it, peer.host) }
        }
        controller.runToIdle()
        val gossip = replicas.map { record(it) }

        // per peer: the chain under test. The UntagCell is LINKED to the
        // replica's outlet (not merely subscribed), so it is a first-class
        // consumer and receives the targeted catch-up/baseline deliveries a
        // plain subscriber never sees.
        val views = replicas.map { replica ->
            val untag = UntagCell<String, String>()
            replica.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)

            val combine = CombineLatestCell<String, String, String, String> { _, tier, price ->
                combineOf(tier, price)
            }
            untag.outlet.linkTo(combine.left as LinkFrom<Propagate<MapDelta<String, String>>>)

            val view = MapView<String, String>()
            combine.outlet.subscribe(
                Use.fixed(Propagate<MapDelta<String, String>> { view.apply(it) }, PortRef.generate())
            )
            // the locally-identical second input — same content on every peer
            combine.right.call.propagate(MapDelta(prices, emptySet()))
            view
        }

        val ops = replicas.mapIndexed { i, replica ->
            (HostedCellProxy.create(replica.ref, peers[i].registry, OrMapInletProxy::class.java)
                    as OrMapInletProxy).inlet.call
        }

        schedule(seed).forEach { op ->
            if (op.partitions) bridge.partition()
            if (op.heals && heal) bridge.heal()
            val o = ops[op.writer]
            if (op.put) o.put(op.key, op.value) else o.remove(op.key)
            repeat(op.steps) { controller.step() }
        }
        controller.runToIdle()

        return Session(
            joined = views.map { it.current() },
            replicaViews = replicas.map { r -> r.membership().associateWith { r.value(it) } },
            gossip = gossip.flatten(),
            deadLetters = peers.flatMap { it.deadLetters },
        )
    }

    // =====================================================================
    // BS-12 — the acceptance property ([KE1-24], [KE1-33])
    // =====================================================================

    @Test
    fun `BS-12 two peers joining an untagged OR-map agree with each other and with a batch recompute`() {
        forEachSeed(0L until SEEDS) { seed ->
            val session = runSession(seed, heal = true)
            withClue("seed $seed") {
                // the chain's input converged ([KE1-33]) — asserted separately
                // so a failure names which half broke
                withClue("replicas") { session.replicaViews.toSet().size shouldBe 1 }
                // …and both peers' joins agree ([KE1-24])
                withClue("joins") { session.joined.toSet().size shouldBe 1 }
                // …on the right thing: an independent fold of the gossiped
                // dots, combined outside any cell
                withClue("batch recompute") { session.joined[0] shouldBe batchRecompute(session.gossip) }
                session.deadLetters.shouldBeEmpty()

                // non-triviality: the chain really ran end to end. `butter` is
                // right-only (the outer branch), and the OR-map keys are
                // genuinely present, so this is not two empty maps agreeing.
                session.joined[0]["butter"] shouldBe "null@4.00"
                withClue("or-map keys reached the join") {
                    session.joined[0].keys.containsAll(prices.keys).shouldBeTrue()
                    session.gossip.isNotEmpty().shouldBeTrue()
                }
            }
        }
    }

    // =====================================================================
    // [KE1-27] — the divergence control. It MUST trip.
    // =====================================================================

    /**
     * One untagged peer: the same schedule, in this peer's arrival order,
     * through the raw pair the join family speaks natively —
     * `MapCell → CombineLatestCell → MapView`. No dots, so the only thing
     * deciding a key's value is which write arrived last.
     */
    @Suppress("UNCHECKED_CAST")
    private fun untaggedPeer(ops: List<Op>): Map<String, String> {
        val map = MapCell<String, String>()
        val combine = CombineLatestCell<String, String, String, String> { _, tier, price ->
            combineOf(tier, price)
        }
        map.outlet.linkTo(combine.left as LinkFrom<Propagate<MapDelta<String, String>>>)

        val view = MapView<String, String>()
        combine.outlet.subscribe(
            Use.fixed(Propagate<MapDelta<String, String>> { view.apply(it) }, PortRef.generate())
        )
        combine.right.call.propagate(MapDelta(prices, emptySet()))

        ops.forEach { if (it.put) map.inlet.call.put(it.key, it.value) else map.inlet.call.remove(it.key) }
        return view.current()
    }

    @Test
    fun `control - the same schedule through raw MapCell and MapView diverges on at least one seed`() {
        val diverged = mutableListOf<Long>()
        for (seed in 0L until SEEDS) {
            val ops = schedule(seed)
            val orders = (0 until PEERS).map { arrivalOrder(ops, it) }

            // the control compares two genuinely different orderings of ONE op
            // multiset — not two different sets of writes, and not the same
            // list twice (either would make a passing control meaningless)
            withClue("seed $seed") {
                orders[0].toSet() shouldBe orders[1].toSet()
                (orders[0] != orders[1]).shouldBeTrue()
            }

            if (orders.map { untaggedPeer(it) }.toSet().size > 1) diverged += seed
        }
        println(
            "[KE1-27] raw MapCell/MapView control diverged on ${diverged.size} of $SEEDS seed(s): " +
                "${diverged.take(10)}"
        )
        // The control must TRIP. If this ever goes green the harness has lost
        // its teeth and the BS-12 property above is asserting nothing — widen
        // the sweep or shorten the post-heal tail (see the class KDoc), never
        // weaken this into "may differ".
        diverged.isNotEmpty().shouldBeTrue()
    }

    private companion object {
        const val PEERS = 2
        const val OPS = 40
        const val PARTITION = 15
        const val CONCURRENT = 20
        const val RACED = 22

        /**
         * Late by construction — the post-heal tail is the window in which the
         * untagged path re-converges by last-writer-wins. See the class KDoc.
         */
        const val HEAL = 36
        const val SEEDS = 50L

        val KEYS = listOf("milk", "eggs", "bread")

        /** A fixed logical-id salt, so a session's refs are a pure function of its seed. */
        const val SALT = 0x1F5L
    }
}
