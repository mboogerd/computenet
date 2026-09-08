package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.wire.Peering
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.HostRebuild
import civictech.testkit.dst.StableRefs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.Serializable

/**
 * `[KE3-34]` BS-17, OrMap-shaped — **the checkpoint-crossing property**,
 * mirroring `CheckpointReclaimCrashTest` (the OR-set original, unedited,
 * read-only here) for [OrMapCell]'s dot-shaped reclaimer
 * (computenet-9sm.8.3/9sm.8.4).
 *
 * ```
 * reclaim -> checkpoint -> journaled tail -> crash -> restore -> tail replay
 * ```
 *
 * and then asks the restored replica the same question its pre-crash self was
 * asked, so the two answers can be compared rather than each judged alone.
 *
 * ## The two halves of the clause
 *
 * 1. `membership()` after restore + tail replay equals the pre-crash
 *    membership.
 * 2. A replayed delta carrying a PUT-DOT this replica **reclaimed** is
 *    absorbed as already-observed — not folded, not re-emitted as novelty —
 *    **and answered with the repair `dels` entry naming exactly that dot**,
 *    identically to the pre-crash answer. `OrMapCell`'s repair
 *    (`OrMapCell.withRepair`) is the dot-shaped form of `SetCell`'s; see that
 *    cell's class KDoc "THE REPAIR TOMBSTONE" for the measurement that a
 *    silent fence is not safe (30 of 200 sweep seeds diverging, the
 *    element-shaped sibling).
 *
 * **A per-source re-admission FLOOR is not what is being tested here and must
 * not be reintroduced**: `OrMapCell.reclaimed`'s KDoc records the same
 * measurement (31-33 of 200 seeds permanently diverged) that ruled it out on
 * the element-shaped sibling. The fence under test is `ReclaimedDots<K>`, an
 * exact `(key, dot)` set written only from dots `compactBelow` actually
 * discarded.
 *
 * ## Non-vacuity — three facts a "membership equal" assertion cannot supply
 *
 * - **the reclamation happened, before the checkpoint** — `e1`'s `dels`
 *   entry is present and complete right up to `checkpoint()`, gone straight
 *   after, and `OrMapCell.fencesAny("e1")` flips `false -> true` across that
 *   one call;
 * - **the fence was persisted and restored non-empty** — after the crash, on
 *   a cell instance constructed empty, `fencedAmong("e1", e1PutDots)` returns
 *   the whole of `e1PutDots`;
 * - **the replayed frame really carried a reclaimed dot** — the delta
 *   delivered in step 6 is built from `e1PutDots`, captured off the live cell
 *   before the checkpoint, and asserted non-empty and wholly fenced at the
 *   moment of delivery.
 *
 * ## Fixture notes
 *
 * Structurally `CheckpointReclaimCrashTest`'s `Rig`, with `OrMapCell<String,
 * String>` / `MapOps<String, String>` in place of `SetCell<String>`/
 * `SetOps<String>`, and `state()` in place of `readBounded` for measuring tag
 * state without triggering reclamation (`OrMapCell` has no `readBounded` — it
 * is `Stateful`, not `BoundedStateful`, 9sm.8-D8).
 *
 * Keys and values are `String`s deliberately: a journal checkpoint cannot
 * serialise an `Owned`-keyed/valued replica, per the OR-set original's note
 * (computenet-9sm.6.6).
 */
class OrMapCheckpointReclaimCrashTest {

    private interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    /** B and C: volatile, un-journaled, never crashed — the peers that certify A's frontier. */
    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /**
     * A three-peer `Peering.Loopback` triangle whose peer A is journaled *and*
     * crashable — the OrMap-shaped mirror of `CheckpointReclaimCrashTest.Rig`.
     */
    private class Rig(seed: Long) {
        val world = DstWorld(seed)
        val controller = world.controller
        val journal = world.journals.declare("wal") // "the disk": survives the crash

        private val namespace = "or-map-checkpoint-reclaim-crash-$seed"
        private val refsA = HostRebuild.refs(namespace) // instanceId 0
        private val refsB = StableRefs(namespace, instanceId = 1)
        private val refsC = StableRefs(namespace, instanceId = 2)
        val refA: CellRef = refsA.ref("replica")

        private var priorBridgeScheduler: HostScheduler? = null
        private lateinit var sideA: Peering.Side
        private lateinit var replicationA: Replication

        val slotA = world.hosts.declare("peerA") { ctx ->
            // The host seam discards only ctx.scheduler; A's bridge host owns a
            // second one, so this rebuild function discards that itself — as
            // CrashRecoveryTest's does, and for the same reason.
            priorBridgeScheduler?.shutdown()
            val bridgeScheduler = ctx.world.controller.scheduler()
            priorBridgeScheduler = bridgeScheduler
            val host = ManagedHost(
                scheduler = ctx.scheduler,
                registry = ctx.registry,
                // CP-C1: only the DATA replica is journaled. The companion
                // `Replication` spawns beside it is volatile and is rebuilt by
                // `replicate`, not recovered.
                journalFor = { ref -> if (ref.id == refA.id) journal else null },
            )
            sideA = Peering.Side(ctx.registry, ManagedHost(scheduler = bridgeScheduler, registry = ctx.registry))
            replicationA = Replication(ctx.registry)
            host
        }

        val b = Peer(controller)
        val c = Peer(controller)

        private var linksA = listOf(Peering.loopback(sideA, b.side), Peering.loopback(sideA, c.side))

        @Suppress("unused")
        val bc = Peering.loopback(b.side, c.side)

        var ra = OrMapCell<String, String>(refA).also { replicationA.replicate(it, slotA.host) }
            private set
        val rb = OrMapCell<String, String>(refsB.ref("replica")).also { b.replication.replicate(it, b.host) }
        val rc = OrMapCell<String, String>(refsC.ref("replica")).also { c.replication.replicate(it, c.host) }

        init {
            controller.runToIdle()
        }

        private fun proxy(registry: LocationRegistry, cell: OrMapCell<String, String>): MapOps<String, String> =
            (HostedCellProxy.create(cell.ref, registry, OrMapInletProxy::class.java) as OrMapInletProxy).inlet.call

        var opA: MapOps<String, String> = proxy(world.registry, ra)
            private set

        fun checkpointA() = slotA.host.checkpoint(journal)

        fun stableFrontierA() = replicationA.stableFrontier(refA.id)

        fun memberships(): List<Set<String>> = listOf(ra.membership(), rb.membership(), rc.membership())

        /**
         * The crash: A's links go, its scheduler and host are discarded, the
         * replica is re-spawned at the SAME `CellRef`, and only then does the
         * rebuilt host read its surviving journal — checkpoint blob first, tail
         * frames after. See the class KDoc for why the spawn precedes the
         * recovery here rather than riding a `CrashFault`'s hook.
         */
        fun crashAndRecover() {
            linksA.forEach { it.partition() }
            slotA.crash("crash")
            ra = OrMapCell<String, String>(refA).also { replicationA.replicate(it, slotA.host) }
            controller.runToIdle() // spawn is management-band async: graph first…
            slotA.host.recoverFrom(journal) // …then restore + tail replay
            controller.runToIdle()
            opA = proxy(world.registry, ra)
        }

        /** Re-open A's links; B's and C's catch-up re-ships the dots A reclaimed. */
        fun relink() {
            linksA = listOf(Peering.loopback(sideA, b.side), Peering.loopback(sideA, c.side))
            controller.runToIdle()
        }
    }

    private companion object {
        /**
         * `key -> dels[key]`, read through [OrMapCell.state] — which mints
         * nothing, emits nothing and, unlike `snapshot()` (now a production
         * reclamation point), reclaims nothing. Measuring dot state through
         * `snapshot()` would compact the cell being measured.
         */
        fun delsOf(cell: OrMapCell<String, String>): Map<String, Set<Timestamp>> = cell.state().dels

        fun putsOf(cell: OrMapCell<String, String>): Map<String, Map<Timestamp, String>> = cell.state().puts

        fun delDotCount(cell: OrMapCell<String, String>): Int = delsOf(cell).values.sumOf { it.size }

        fun tombstonedKeys(cell: OrMapCell<String, String>): Set<String> = delsOf(cell).filterValues { it.isNotEmpty() }.keys

        fun buffer(cell: OrMapCell<String, String>, into: MutableList<Invocation>) {
            cell.outlet.subscribe(Use.fixed(buffering<Propagate<TaggedMapDelta<String, String>>>(into), PortRef.generate()))
        }

        /** The `TaggedMapDelta`s an outlet tap captured, in order. */
        @Suppress("UNCHECKED_CAST")
        fun emitted(tap: List<Invocation>): List<TaggedMapDelta<String, String>> =
            tap.filter { it.methodName == "propagate" }.map { it.args[0] as TaggedMapDelta<String, String> }

        @Suppress("UNCHECKED_CAST")
        fun snapshotOf(cell: OrMapCell<String, String>): Map<String, Any> = cell.snapshot() as Map<String, Any>

        fun trace(step: String, vararg pairs: Pair<String, Any?>) {
            println("[or-map-checkpoint-reclaim-crash] $step: " + pairs.joinToString(" ") { (k, v) -> "$k=$v" })
        }
    }

    /**
     * `[KE3-34]`'s checkpoint-crossing property, end to end, on one
     * deterministic schedule — OrMap-shaped.
     *
     * Step 7 — the control — is what makes step 6 a *clause* rather than a
     * plausible-looking assertion: the same replay is delivered to A **before**
     * the crash and after it, and the two emitted answers are compared to each
     * other. A restored replica that had forgotten its fence would fold the
     * put-dot and re-emit it as novelty (a `puts` frame, not a `dels` one); a
     * restored replica that fenced *silently* would emit nothing at all. Only
     * the landed behaviour produces the identical minimal tombstone twice.
     */
    @Test
    fun `checkpoint-driven reclamation survives crash, restore and tail replay`() {
        val rig = Rig(seed = 1L)

        // ------------------------------------------------------------------
        // 1. The fold: puts and one remove, gossiped until A's stable frontier
        //    covers the whole of e1's `dels` entry — the put-dot and the
        //    del-dot 9sm.8-D5's `remove` mints alongside it.
        // ------------------------------------------------------------------
        (1..4).forEach { rig.opA.put("e$it", "v-e$it") }
        rig.opA.remove("e1")
        rig.controller.runToIdle()

        val e1PutDots = putsOf(rig.ra).getValue("e1")
        trace("fold", "memberships" to rig.memberships(), "e1PutDots" to e1PutDots)
        rig.memberships() shouldBe List(3) { setOf("e2", "e3", "e4") }
        // NON-VACUITY (a) — there IS a complete tombstone to reclaim at the
        // moment of the checkpoint, and nothing has been reclaimed yet.
        tombstonedKeys(rig.ra) shouldBe setOf("e1")
        delDotCount(rig.ra) shouldBe 2 // {put-dot, del-dot}
        e1PutDots.keys.shouldNotBeEmpty()
        rig.ra.fencesAny("e1") shouldBe false
        rig.ra.fencedAmong("e1", e1PutDots.keys).shouldBeEmpty()
        rig.stableFrontierA().perSource.keys.shouldNotBeEmpty()

        // ------------------------------------------------------------------
        // 2. THE CHECKPOINT. `HostDurability.checkpoint` snapshots the journaled
        //    replica, `OrMapCell.snapshot()` compacts at the stable frontier, and
        //    the fence it writes is serialised in the same call.
        // ------------------------------------------------------------------
        rig.checkpointA()
        trace("checkpoint", "delDots" to delDotCount(rig.ra), "fenced" to rig.ra.fencedAmong("e1", e1PutDots.keys))
        // NON-VACUITY (a), other side — the reclamation actually happened, and
        // happened HERE: `fencesAny` flips across this one call.
        tombstonedKeys(rig.ra).shouldBeEmpty()
        delDotCount(rig.ra) shouldBe 0
        rig.ra.fencesAny("e1") shouldBe true
        rig.ra.fencedAmong("e1", e1PutDots.keys) shouldBe e1PutDots.keys
        // …and the reclamation moved no membership (`[KE3-33]`, restated here
        // only because everything below compares against this membership).
        rig.ra.membership() shouldBe setOf("e2", "e3", "e4")

        // ------------------------------------------------------------------
        // 3. The tail: ten more ops on A, journaled AFTER the checkpoint
        //    truncated the WAL down to its blob. These are what replay.
        // ------------------------------------------------------------------
        (5..10).forEach { rig.opA.put("t$it", "v-t$it") }
        rig.opA.remove("t5")
        rig.opA.remove("t6")
        rig.opA.put("t11", "v-t11")
        rig.opA.put("t12", "v-t12")
        rig.controller.runToIdle()

        val pre = rig.ra.membership()
        trace("tail", "pre" to pre, "delDots" to delDotCount(rig.ra))
        pre shouldBe setOf("e2", "e3", "e4", "t7", "t8", "t9", "t10", "t11", "t12")
        val preValues = pre.associateWith { rig.ra.value(it) }

        // ------------------------------------------------------------------
        // 4. THE CONTROL ANSWER, taken pre-crash. A delta carrying e1's
        //    reclaimed put-dot, delivered straight to `deltaInlet` — the wire's
        //    own path, and deliberately NOT through the host intake, so this
        //    probe is not itself journaled into the tail that replays below.
        // ------------------------------------------------------------------
        val replay = TaggedMapDelta(puts = mapOf("e1" to e1PutDots))
        val preTap = mutableListOf<Invocation>()
        buffer(rig.ra, preTap)
        rig.ra.deltaInlet.call.propagate(replay)
        rig.controller.runToIdle()

        val preAnswer = emitted(preTap)
        trace("pre-crash answer", "emitted" to preAnswer, "membership" to rig.ra.membership())
        // Fenced, not folded: e1 stays absent and the membership is untouched…
        rig.ra.membership() shouldBe pre
        // …and the fence is not silent — the repair names exactly the fenced
        // dot(s) and nothing else, with an empty `puts` half (no novelty
        // re-emitted).
        preAnswer shouldBe listOf(TaggedMapDelta(dels = mapOf("e1" to e1PutDots.keys)))

        // ------------------------------------------------------------------
        // 5. CRASH -> RESTORE -> TAIL REPLAY.
        // ------------------------------------------------------------------
        rig.crashAndRecover()
        trace(
            "recovered",
            "membership" to rig.ra.membership(),
            "fenced" to rig.ra.fencedAmong("e1", e1PutDots.keys),
            "delDots" to delDotCount(rig.ra),
        )

        // THE CLAUSE, first half: membership survives the crossing, values too.
        rig.ra.membership() shouldBe pre
        pre.forEach { k -> rig.ra.value(k) shouldBe preValues.getValue(k) }
        // NON-VACUITY (b) — the fence was persisted in the checkpoint blob and
        // restored NON-EMPTY onto a cell instance that was constructed empty.
        // Without this the assertions below would hold of an unfenced replica
        // that had simply never been offered the dot.
        rig.ra.fencesAny("e1") shouldBe true
        rig.ra.fencedAmong("e1", e1PutDots.keys) shouldBe e1PutDots.keys

        // The links come back: B and C still hold e1's whole `dels` entry (they
        // never checkpointed), so their catch-up is itself a replay of the
        // reclaimed dots over a second path. It must not resurrect e1 either.
        rig.relink()
        trace("relinked", "memberships" to rig.memberships())
        rig.ra.membership() shouldBe pre
        rig.memberships().toSet().size shouldBe 1

        // ------------------------------------------------------------------
        // 6/7. THE SAME QUESTION, ASKED AGAIN. The restored A is offered the
        //      identical delta, measured the identical way.
        // ------------------------------------------------------------------
        // NON-VACUITY (c) — the frame really does carry a dot THIS replica
        // reclaimed, checked at the moment of delivery rather than assumed from
        // step 2 (the cell being asked is a different object now).
        replay.puts.getValue("e1").keys.shouldNotBeEmpty()
        rig.ra.fencedAmong("e1", replay.puts.getValue("e1").keys) shouldBe replay.puts.getValue("e1").keys

        val postTap = mutableListOf<Invocation>()
        buffer(rig.ra, postTap)
        rig.ra.deltaInlet.call.propagate(replay)
        rig.controller.runToIdle()

        val postAnswer = emitted(postTap)
        trace("post-crash answer", "emitted" to postAnswer, "membership" to rig.ra.membership())
        // Not folded…
        rig.ra.membership() shouldBe pre
        rig.ra.membership().contains("e1") shouldBe false
        // …not re-emitted as novelty…
        postAnswer.single().puts.keys.shouldBeEmpty()
        // …and answered with the repair tombstone EXACTLY as it is pre-crash.
        // This equality is the clause: the fence alone is not the fix.
        postAnswer shouldBe preAnswer
        postAnswer shouldBe listOf(TaggedMapDelta(dels = mapOf("e1" to e1PutDots.keys)))
    }

    /**
     * The **fail-safe direction computenet-pay7 deliberately chose**, asserted
     * as design rather than reported as a defect — OrMap-shaped.
     *
     * `"reclaimed"` is an additive snapshot key: a checkpoint blob written
     * before the fence existed does not carry it, and `OrMapCell.restore` reads
     * an absent key as an EMPTY fence. The consequence is visible and is the
     * point — a replayed put-dot is then FOLDED, which is a recoverable
     * resurrection window (the pre-reclaimer behaviour, which the mesh's
     * ordinary anti-entropy can still repair). The alternative — inventing a
     * fence, or attributing un-keyed dots to a guessed key — produces
     * divergence-by-invented-fence, which is exactly the permanent failure
     * `ReclaimedDots`'s `(key, dot)` key exists to prevent (measured on the
     * element-shaped sibling at 4 of 200 STABLE seeds when the key was the tag
     * alone).
     *
     * Both arms are restored into a bare `OrMapCell` outside `Replication`, so
     * neither can reclaim on its own and the only difference between them is
     * the presence of the key.
     */
    @Test
    fun `a checkpoint blob without the reclaimed key restores an empty fence and re-admits`() {
        val rig = Rig(seed = 2L)

        rig.opA.put("f1", "v-f1")
        rig.opA.put("f2", "v-f2")
        rig.opA.remove("f1")
        rig.controller.runToIdle()
        val f1PutDots = putsOf(rig.ra).getValue("f1")
        f1PutDots.keys.shouldNotBeEmpty()

        rig.checkpointA()
        // Non-vacuity: the blob under test is one a real reclamation produced.
        rig.ra.fencedAmong("f1", f1PutDots.keys) shouldBe f1PutDots.keys

        val blob = snapshotOf(rig.ra)
        blob["reclaimed"] shouldNotBe null
        blob["reclaimed"].toString() shouldNotBe "{}"

        val stripped = HashMap(blob).also { it.remove("reclaimed") }
        val replay = TaggedMapDelta(puts = mapOf("f1" to f1PutDots))

        // Arm 1 — the key present: the restored fence rejects the replay, and
        // answers it with the repair tombstone.
        val kept = OrMapCell<String, String>(CellRef(rig.refA.id, 9))
        kept.restore(HashMap(blob) as Serializable)
        val keptTap = mutableListOf<Invocation>()
        buffer(kept, keptTap)
        kept.deltaInlet.call.propagate(replay)
        trace("fence kept", "membership" to kept.membership(), "emitted" to emitted(keptTap))
        kept.fencedAmong("f1", f1PutDots.keys) shouldBe f1PutDots.keys
        kept.membership() shouldBe setOf("f2")
        emitted(keptTap) shouldBe listOf(TaggedMapDelta(dels = mapOf("f1" to f1PutDots.keys)))

        // Arm 2 — the key absent: an EMPTY fence, and the replay is folded.
        // Deliberate, and recoverable; see the KDoc.
        val stripe = OrMapCell<String, String>(CellRef(rig.refA.id, 10))
        stripe.restore(stripped as Serializable)
        val stripeTap = mutableListOf<Invocation>()
        buffer(stripe, stripeTap)
        stripe.deltaInlet.call.propagate(replay)
        trace("fence dropped", "membership" to stripe.membership(), "emitted" to emitted(stripeTap))
        stripe.fencesAny("f1") shouldBe false
        stripe.fencedAmong("f1", f1PutDots.keys).shouldBeEmpty()
        stripe.membership() shouldBe setOf("f1", "f2") // re-admitted: the window
        stripe.value("f1") shouldBe "v-f1"
        emitted(stripeTap) shouldBe listOf(TaggedMapDelta(puts = mapOf("f1" to f1PutDots)))
    }
}
