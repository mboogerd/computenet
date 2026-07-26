package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.replication.Interest
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * FU-1 (spec 20/24 §Partitioned state, 40/42 §Interest-scoped instance sets):
 * a partial-interest scatter-gather pull is narrowed **at the shard, across the
 * wire** — not whole-then-filtered at the requester.
 *
 * PN-5 fans a `StateRequest` to every interest-overlapping shard over real
 * `Peering.loopback` bridges; each shard answers `baselineTo` with its slice.
 * Before FU-1, `StateRequest.scope` was `@Transient`, so a cross-host shard
 * received `scope == null ⇒ Interest.Total` and replied with its *whole* slice —
 * correct (the requester still filters) but over-fetching. FU-1 makes `scope` a
 * wire-carried `@Polymorphic Interest` (the same channel `Assignment.interest`
 * already rides), so the shard narrows to `shardInterest ∩ scope` before the
 * reply crosses back.
 *
 * The [PullInbox] here is a **dumb collector** — it never filters by scope. So
 * every key that appears in a reply was put there by the shard: if a partial-scope
 * reply contains only scope-admitted keys, the *shard* did the narrowing, over the
 * bridge. Controls: (a) a scope-less request (byte-identical to the old
 * `@Transient` wire) over-fetches keys outside the sub-slice; (b) an
 * [Interest.Total] pull is byte-identical to the pre-scope whole-slice reply
 * (the whole board, unchanged).
 */
class PartitionedPullScopeWireTest {

    /** The requester: a dumb collector of `baselineTo` pull replies — it does NOT filter by scope. */
    private class PullInbox(
        shardRefs: List<CellRef>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        private val outletToShard = shardRefs.associateBy { PortRef.of(it, "outlet") }
        private val collected = mutableListOf<PullReply<String>>()

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    val ctx = CurrentContext.get()
                    val frontier = ctx?.baseline ?: return // live wave (no baseline) — not a pull leg
                    val shard = outletToShard[ctx.sourcePort] ?: return
                    collected += PullReply(shard, value, frontier)
                }
            })
        }

        fun drain(): List<PullReply<String>> = collected.toList().also { collected.clear() }
    }

    private interface InboxRoute {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    /** A router bridged to [shardCount] shards, each an interest-scoped hosted instance on its own host+registry. */
    private class Mesh(seed: Long, val shardCount: Int, val totalSlots: Int) {
        val controller = SimulationController(seed)
        val routerRegistry = LocationRegistry()
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val logicalId: UUID = UUID.randomUUID()
        val router = PartitionedShardSet<String>(totalSlots, { it.first().toString() }, routerRegistry)
        private val shardRefs = (0 until shardCount).map { CellRef(logicalId, it.toLong()) }
        private val inbox = PullInbox(shardRefs)

        init {
            val shardCells = List(shardCount) { i ->
                val reg = LocationRegistry()
                val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val host = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val interest = Interest.Slots.forShard(i, shardCount, totalSlots)
                val shard = ShardCell<String>(shardRefs[i], { it.first().toString() }, interest)
                host.managementInlet.call.spawn(shard)
                Peering.loopback(routerSide, Peering.Side(reg, bridgeHost))
                Triple(shard, interest, reg)
            }
            routerBridgeHost.managementInlet.call.spawn(inbox)
            controller.runToIdle()
            shardCells.forEach { (shard, interest, reg) ->
                router.addShard(shard, interest)
                val replyProxy = (HostedCellProxy.create(inbox.ref, reg, InboxRoute::class.java) as InboxRoute).inlet.call
                shard.outlet.subscribe(Use.fixed(replyProxy, inbox.inlet.ref))
            }
            controller.runToIdle()
        }

        fun quiesce() = controller.runToIdle()

        /**
         * Fire a scatter-gather pull and return the replies once they have crossed
         * the bridge. `pull` only *sends* the requests; the `baselineTo` legs arrive
         * asynchronously over each shard's reverse bridge, so we drain before reading
         * — proof each reply (and its narrowing) crossed the wire, not read in-process.
         */
        fun pull(scope: Interest, sinceOf: (CellRef) -> TagFrontier?): List<PullReply<String>> {
            router.pull(inbox.inlet.ref, scope, sinceOf)
            controller.runToIdle()
            return inbox.drain()
        }
    }

    /** A single-writer OR-set input over one shared source: counters interleave across shards by construction. */
    private class Input {
        private val src = UUID.randomUUID()
        private var ctr = 0L
        private val live = mutableMapOf<String, MutableSet<Timestamp>>()

        fun add(e: String): SetDelta<String> {
            val t = Timestamp(src, ++ctr)
            live.getOrPut(e) { mutableSetOf() } += t
            return SetDelta(adds = mapOf(e to setOf(t)))
        }

        fun remove(e: String): SetDelta<String>? {
            val observed = live[e]?.toSet()?.takeIf { it.isNotEmpty() } ?: return null
            live[e]!!.clear()
            return SetDelta(dels = mapOf(e to observed))
        }

        fun liveSet(): Set<String> = live.filterValues { it.isNotEmpty() }.keys.toMutableSet()
    }

    // domain first chars hash (single-char String.hashCode == char code) to distinct slots over 12:
    // a->1(sh1) b->2(sh2) c->3(sh0) d->4(sh1) e->5(sh2) f->6(sh0) g->7(sh1) h->8(sh2)
    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    // a partial scope that intersects only PART of each 3-shard slice: {1,2,3} picks
    // one populated slot per shard (sh1's a, sh2's b, sh0's c) and excludes the rest
    // (sh1's d,g / sh2's e,h / sh0's f) — a genuine sub-slice per shard.
    private val partialScope = Interest.Slots(setOf(1, 2, 3), 12)

    private fun keyOf(e: String): String = e.first().toString()
    private fun union(replies: List<PullReply<String>>): Set<String> =
        replies.flatMapTo(mutableSetOf()) { it.delta.adds.keys }

    private fun seedBoard(mesh: Mesh, input: Input, rnd: Random) {
        repeat(50) {
            val e = domain[rnd.nextInt(domain.size)]
            val delta = if (rnd.nextInt(10) < 7 || e !in input.liveSet()) input.add(e) else input.remove(e)
            if (delta != null) mesh.router.route(delta)
            repeat(rnd.nextInt(3)) { mesh.controller.step() }
        }
        mesh.quiesce()
    }

    // ---- the payoff: partial scope is narrowed AT THE SHARD, across the wire ----

    @Test
    fun `partial-scope pull is narrowed at the shard across the wire, control transient over-fetches, 100 seeds`() {
        var overFetched = 0 // control (a): a scope-less (transient) wire request carries keys outside the sub-slice
        for (seed in 0L until 100L) {
            val mesh = Mesh(seed, shardCount = 3, totalSlots = 12)
            val input = Input()
            seedBoard(mesh, input, Random(seed))

            // FU-1: fan a partial scope. The inbox never filters, so every collected
            // key was narrowed by the shard before its reply crossed the bridge.
            val narrowed = mesh.pull(partialScope) { null }

            // hard invariant, every seed: the cross-host reply admits ONLY sub-slice keys.
            union(narrowed).all { partialScope.admits(keyOf(it)) }.shouldBeTrue()
            // and it equals exactly the board keys the requester's interest admits.
            union(narrowed) shouldBe input.liveSet().filterTo(mutableSetOf()) { partialScope.admits(keyOf(it)) }

            // control (a): the pre-FU-1 wire — scope dropped (@Transient) ⇒ shard sees
            // Total ⇒ whole slice. Byte-identical to sending a scope-less StateRequest,
            // which is exactly what an Interest.Total fan sends. Overlapping shards then
            // carry keys OUTSIDE the requested sub-slice.
            val transient = mesh.pull(Interest.Total) { null }
            val leakedBeyondScope = union(transient).filterTo(mutableSetOf()) { !partialScope.admits(keyOf(it)) }
            if (leakedBeyondScope.isNotEmpty()) {
                overFetched++
                // those leaked keys are precisely what narrowing dropped
                (leakedBeyondScope - union(narrowed)) shouldBe leakedBeyondScope
            }
        }
        // if this fails, no seed ever put an out-of-scope key on an overlapping shard —
        // the over-fetch the wire-carry fixes would be untested.
        (overFetched > 0).shouldBeTrue()
    }

    // ---- control (b): Total scope is byte-identical to the whole-slice reply -----

    @Test
    fun `control b - Total scope pull is byte-identical to the whole-slice reply and diverges from the narrowed slice, 100 seeds`() {
        var divergedFromNarrowed = 0
        for (seed in 0L until 100L) {
            val mesh = Mesh(seed, shardCount = 3, totalSlots = 12)
            val input = Input()
            seedBoard(mesh, input, Random(seed))

            // Total ⇒ verbatim: the assembled union is the whole board, unchanged from
            // the pre-scope reply (contentsSince/currentFrontier short-circuit Total).
            val total = mesh.pull(Interest.Total) { null }
            union(total) shouldBe input.liveSet()

            // and Total is a strict superset of the partial-scope slice whenever the board
            // holds any out-of-scope key — the narrowing genuinely removes keys.
            val narrowed = mesh.pull(partialScope) { null }
            union(narrowed).all { partialScope.admits(keyOf(it)) }.shouldBeTrue()
            if (union(narrowed) != union(total)) divergedFromNarrowed++
            union(narrowed).all { it in union(total) }.shouldBeTrue()
        }
        (divergedFromNarrowed > 0).shouldBeTrue()
    }
}
