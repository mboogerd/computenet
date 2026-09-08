package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * **The TRIGGER, OrMap-shaped** (computenet-9sm.8.4, decisions 9sm.8-D6/D7;
 * `[KE3-30]`, `[KE3-31]` as amended by computenet-v2ka, dot-shaped for
 * [OrMapCell]).
 *
 * `OrMapCell.compactBelow` (computenet-9sm.8.3) is the fenced, persisted
 * reclaimer for the dot-shaped OR-structure — no production caller of its own
 * until [OrMapCell.snapshot] reads the stability read
 * `Replication.trackDeliveries` installs
 * ([civictech.cell.data.delta.StabilityReclaim]) and reclaims below it before
 * serialising. This file is the dot-shaped mirror of
 * `CheckpointReclaimTest` (the OR-set original, unedited, read-only here) — it
 * pins the wiring, not the reclaimer's own rule, which `OrMapCellCompactBelowTest`
 * already covers (per-dot discard rejected, a silent fence rejected, an
 * unrecorded fence rejected — see that file's non-vacuousness evidence, cited
 * again in this task's bead comment as corroborating).
 *
 * ## Reading tag state without triggering the thing under test
 *
 * `snapshot()` is a production reclamation point here too, so a test that read
 * dot counts through it would compact the cell it is measuring. `OrMapCell` has
 * **no `readBounded`** — it is `Stateful`, not `BoundedStateful` (9sm.8-D8) — so
 * every count below reads through [OrMapCell.state], which copies out under
 * [OrMapCell]'s lock and reclaims nothing (see that method's KDoc). `snapshot()`
 * is called directly in exactly the places that mean to: the post-checkpoint
 * fence assertion, and `the snapshot that reclaims serialises the
 * post-compaction state`, where the point *is* that a snapshot reclaims.
 *
 * The mesh fixture is `CheckpointReclaimTest.Mesh`/`Peer`, copied verbatim in
 * structure with `OrMapCell<String, String>` and `MapOps<String, String>` in
 * place of `SetCell<String>`/`SetOps<String>`.
 */
class OrMapCheckpointReclaimTest {

    private interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    /** Peer A also owns a journal; B and C are volatile, so only A can checkpoint. */
    private class Peer(controller: SimulationController, logicalId: UUID? = null) {
        val registry = LocationRegistry()
        val journal = InMemoryJournal()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            // Per-cell durability (CP-C1): only the DATA replica is journaled.
            // The delivered-watermark companion `Replication` spawns beside it
            // is deliberately left volatile — this test checkpoints the OR-map,
            // not the stability substrate that certifies it.
            journalFor = { ref -> if (logicalId != null && ref.id == logicalId) journal else null },
        )
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    private class Mesh(val controller: SimulationController, val logicalId: UUID) {
        val a = Peer(controller, logicalId)
        val b = Peer(controller)
        val c = Peer(controller)

        val ab: Peering.Loopback
        val bc: Peering.Loopback

        @Suppress("unused")
        val ac: Peering.Loopback

        val ra: OrMapCell<String, String>
        val rb: OrMapCell<String, String>
        val rc: OrMapCell<String, String>

        init {
            ab = Peering.loopback(a.side, b.side)
            bc = Peering.loopback(b.side, c.side)
            ac = Peering.loopback(a.side, c.side)
            ra = OrMapCell<String, String>(CellRef(logicalId, 0)).also { a.replication.replicate(it, a.host) }
            rb = OrMapCell<String, String>(CellRef(logicalId, 1)).also { b.replication.replicate(it, b.host) }
            rc = OrMapCell<String, String>(CellRef(logicalId, 2)).also { c.replication.replicate(it, c.host) }
            controller.runToIdle()
        }

        fun ops(peer: Peer, cell: OrMapCell<String, String>): MapOps<String, String> =
            (HostedCellProxy.create(cell.ref, peer.registry, OrMapInletProxy::class.java) as OrMapInletProxy).inlet.call

        fun checkpointA() = a.host.checkpoint(a.journal)

        fun memberships(): List<Set<String>> = listOf(ra.membership(), rb.membership(), rc.membership())
    }

    private companion object {
        /** `key -> dels[key]`, read through [OrMapCell.state] so the measurement does not itself reclaim. */
        fun delsOf(cell: OrMapCell<String, String>): Map<String, Set<Timestamp>> = cell.state().dels

        /** Total tombstone DOTS retained — put-dot(s) plus the del-dot(s) an entry names. */
        fun delDotCount(cell: OrMapCell<String, String>): Int = delsOf(cell).values.sumOf { it.size }

        /** Keys carrying a `dels` entry at all. */
        fun tombstonedKeys(cell: OrMapCell<String, String>): Set<String> = delsOf(cell).filterValues { it.isNotEmpty() }.keys

        @Suppress("UNCHECKED_CAST")
        fun snapshotOf(cell: OrMapCell<String, String>): Map<String, Any> = cell.snapshot() as Map<String, Any>

        fun buffer(cell: OrMapCell<String, String>, into: MutableList<Invocation>) {
            cell.outlet.subscribe(Use.fixed(buffering<Propagate<TaggedMapDelta<String, String>>>(into), PortRef.generate()))
        }

        fun trace(step: String, vararg pairs: Pair<String, Any?>) {
            println("[or-map-checkpoint-reclaim] $step: " + pairs.joinToString(" ") { (k, v) -> "$k=$v" })
        }
    }

    /**
     * `[KE3-31]`/`[KE3-32]`, OrMap-shaped — **the del-dot count**, and the
     * clause's second half ("reclamation SHALL run nowhere else") measured
     * rather than argued.
     *
     * **Dot arithmetic, hand-derived from `OrMapCell`'s mint order (9sm.8-D5),
     * not copied from the OR-set sibling** — a re-put/remove mints TWO dots
     * (the retract or removal del-dot plus, for a re-put, the new put-dot),
     * where `SetCell` mints one TAG per remove. A `put` on an absent key mints
     * one dot and no `dels` entry; `remove` on a live key mints one del-dot
     * covering the live put-dot, so each entry ends up with exactly 2 dots
     * (1 put-dot + 1 del-dot):
     *
     * - `put e1..e6` (6 keys, key absent each time): 6 put-dots, no `dels`.
     * - `remove e1..e3`: 3 entries of {put-dot, del-dot} = 6 del-dot-set dots.
     * - `put e7` (key absent): 1 more put-dot, no `dels` change.
     * - `remove e4`: 1 more entry of {put-dot, del-dot} = 2 more dots, total 8.
     * - checkpoint on A: every one of e1..e4's entries is fully delivered
     *   (idle gossip reached B and C before the checkpoint), so all 8 dots —
     *   and the 4 put-dots they cover — are discarded. `membership()` is
     *   `{e5, e6, e7}` and untouched by the discard.
     *
     * This reproduces the bead's stated witness figures (6 -> 8 -> 0 on A, 8 on
     * B/C) exactly; no drift to record.
     */
    @Test
    fun `a checkpoint reclaims delivered tombstones and nothing else does`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        // 1. The fold: six puts, three removes, fully gossiped. Every del-dot
        //    reaches B and C, so A's stable frontier certifies all three removes.
        (1..6).forEach { opA.put("e$it", "v-e$it") }
        (1..3).forEach { opA.remove("e$it") }
        controller.runToIdle()

        val before = delsOf(mesh.ra)
        trace("fold", "memberships" to mesh.memberships(), "delDots" to delDotCount(mesh.ra))
        mesh.memberships() shouldBe listOf(setOf("e4", "e5", "e6"), setOf("e4", "e5", "e6"), setOf("e4", "e5", "e6"))
        tombstonedKeys(mesh.ra) shouldBe setOf("e1", "e2", "e3")
        // Non-vacuity: each entry is {put-dot, del-dot} (9sm.8-D5), so 3 removes = 6 dots.
        delDotCount(mesh.ra) shouldBe 6

        // 2. A FURTHER gossip round with no checkpoint: a local put, a local
        //    remove, and the remote applications they cause at B and C. If
        //    `put`, `remove` or `applyRemote` reclaimed, the three settled
        //    tombstones of step 1 would not survive it.
        opA.put("e7", "v-e7")
        opA.remove("e4")
        controller.runToIdle()
        trace("no-checkpoint", "delDots" to delDotCount(mesh.ra), "tombstoned" to tombstonedKeys(mesh.ra))
        tombstonedKeys(mesh.ra) shouldBe setOf("e1", "e2", "e3", "e4")
        delDotCount(mesh.ra) shouldBe 8
        // and the step-1 entries are the SAME dots, not merely the same count
        listOf("e1", "e2", "e3").forEach { e -> delsOf(mesh.ra)[e] shouldBe before[e] }

        val membershipBefore = mesh.ra.membership()
        val valuesBefore = membershipBefore.associateWith { mesh.ra.value(it) }

        // 3. ONE checkpoint on A.
        mesh.checkpointA()
        trace(
            "after-checkpoint",
            "A-delDots" to delDotCount(mesh.ra),
            "B-delDots" to delDotCount(mesh.rb),
            "C-delDots" to delDotCount(mesh.rc),
        )
        // A: every tombstone was fully delivered, so every one goes, and with it
        // the put-dots it covered (e1..e4's).
        tombstonedKeys(mesh.ra).shouldBeEmpty()
        delDotCount(mesh.ra) shouldBe 0
        mesh.ra.membership() shouldBe setOf("e5", "e6", "e7")
        mesh.ra.membership() shouldBe membershipBefore
        membershipBefore.forEach { k -> mesh.ra.value(k) shouldBe valuesBefore.getValue(k) }

        // B and C did not checkpoint — and did not reclaim. This is the control
        // arm that makes the reduction above attributable to the checkpoint.
        delDotCount(mesh.rb) shouldBe 8
        delDotCount(mesh.rc) shouldBe 8

        // 4. The persisted result of the pass: emptied `dels` beside a grown
        //    fence — the state-level echo of the ordering constraint
        //    `the snapshot that reclaims serialises the post-compaction state`
        //    actually discriminates (see that test's KDoc for why this
        //    assertion alone would pass under an inverted compact/serialise).
        val snap = snapshotOf(mesh.ra)
        @Suppress("UNCHECKED_CAST")
        (snap["dels"] as Map<String, Set<Timestamp>>).keys.shouldBeEmpty()
        snap["reclaimed"] shouldNotBe null
        snap["reclaimed"].toString() shouldNotBe "{}"
    }

    /**
     * `[KE3-31]`'s **ordering constraint**, as a discriminator — the OrMap
     * mirror of `CheckpointReclaimTest`'s test of the same name.
     *
     * The requirement is not merely that a snapshot reclaims, but that the
     * reclamation happens BEFORE the serialisation it accompanies, under one
     * hold of `stateLock`: the persisted dot maps and the persisted
     * `"reclaimed"` fence must come out of one post-compaction state, or a
     * restore re-admits dots the fence says were discarded.
     *
     * The only way to see that is to read the return value of the SAME
     * `snapshot()` call that does the compacting, on a cell that has not been
     * compacted yet — which is why this cannot be folded into the tombstone
     * test above, where the checkpoint has already emptied `dels` and a
     * serialise-then-compact wiring would serialise the same empty maps.
     * Inverting the two statements in `OrMapCell.snapshot()` reddens the
     * `dels`-empty assertion here and nothing else in this file.
     */
    @Test
    fun `the snapshot that reclaims serialises the post-compaction state`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        (1..3).forEach { opA.put("s$it", "v-s$it") }
        (1..2).forEach { opA.remove("s$it") }
        controller.runToIdle()

        // Non-vacuity: there ARE tombstones to reclaim at the moment of the call.
        tombstonedKeys(mesh.ra) shouldBe setOf("s1", "s2")
        delDotCount(mesh.ra) shouldBe 4 // {put-dot, del-dot} x 2

        // ONE snapshot, taken directly. It is the reclamation point and the
        // serialisation point, in that order.
        val snap = snapshotOf(mesh.ra)
        trace("ordering", "dels" to snap["dels"], "reclaimed" to snap["reclaimed"])

        @Suppress("UNCHECKED_CAST")
        val dels = snap["dels"] as Map<String, Set<Timestamp>>
        // The maps this call SERIALISED are the ones its own compaction emptied.
        dels.values.flatten().shouldBeEmpty()
        // ... and the fence it serialised alongside them is the matching one.
        snap["reclaimed"].toString() shouldNotBe "{}"
        // The call really did reclaim (so the emptiness above is compaction,
        // not an empty cell).
        delDotCount(mesh.ra) shouldBe 0
        mesh.ra.membership() shouldBe setOf("s3")
    }

    /**
     * `[KE3-33]` kernel half, **on the checkpoint path**, OrMap-shaped. The
     * seam's membership-invariance is already pinned by
     * `OrMapCellCompactBelowTest`; that is evidence about `compactBelow`, not
     * about the caller this task adds, so it is re-asserted here where the
     * trigger actually fires — with an outlet tap, because a reclamation that
     * emitted would gossip a tombstone-free state to peers that still hold the
     * tombstones.
     */
    @Test
    fun `the checkpoint changes no membership and emits no delta`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        (1..4).forEach { opA.put("k$it", "v-k$it") }
        (1..2).forEach { opA.remove("k$it") }
        controller.runToIdle()

        // Tap AFTER the fold, so the count measures the checkpoint alone.
        val emitted = mutableListOf<Invocation>()
        buffer(mesh.ra, emitted)
        val membershipBefore = mesh.memberships()
        val emittedBefore = emitted.size

        mesh.checkpointA()
        controller.runToIdle() // give any emission the checkpoint DID make a chance to land

        trace("emission", "before" to emittedBefore, "after" to emitted.size, "memberships" to mesh.memberships())
        // Non-vacuity: the checkpoint really did reclaim, so "emitted nothing"
        // is a property of a pass that did work rather than of a no-op.
        delDotCount(mesh.ra) shouldBe 0
        emitted.size shouldBe emittedBefore
        mesh.memberships() shouldBe membershipBefore
        mesh.ra.membership() shouldBe setOf("k3", "k4")
    }

    /**
     * The **null contract** ([civictech.cell.data.delta.StabilityReclaim]): an
     * `OrMapCell` that is not under `Replication` has no stability read
     * installed, so a checkpoint snapshots it exactly as it did before this
     * task existed — zero discards, whatever its `dels` looks like.
     *
     * This is the safety default and not an optimisation: a cell with no
     * replica set has no causal-stability certificate to discard against, and
     * "no certificate" must never read as "discard freely".
     */
    @Test
    fun `an OrMapCell not under Replication reclaims nothing at a checkpoint`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val peer = Peer(controller, logicalId)
        val cell = OrMapCell<String, String>(CellRef(logicalId, 0))
        peer.host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        val ops = (HostedCellProxy.create(cell.ref, peer.registry, OrMapInletProxy::class.java) as OrMapInletProxy)
            .inlet.call
        (1..3).forEach { ops.put("u$it", "v-u$it") }
        ops.remove("u1")
        controller.runToIdle()

        val before = delsOf(cell)
        before.keys shouldBe setOf("u1")
        delDotCount(cell) shouldBe 2 // {put-dot, del-dot}

        peer.host.checkpoint(peer.journal)
        trace("no-read-installed", "delDots" to delDotCount(cell), "dels" to delsOf(cell).keys)

        delsOf(cell) shouldBe before
        delDotCount(cell) shouldBe 2
        cell.membership() shouldBe setOf("u2", "u3")
        // and the snapshot the checkpoint took is still the whole state
        @Suppress("UNCHECKED_CAST")
        (snapshotOf(cell)["dels"] as Map<String, Set<Timestamp>>).keys shouldBe setOf("u1")
    }

    /**
     * `[KE3-30]` — **the frontier is read inside the pass**, and a rise after
     * a checkpoint is not available to that checkpoint. OrMap-shaped mirror of
     * `CheckpointReclaimTest`'s test of the same name.
     *
     * A's companion is held, so the rows carrying B's and C's delivery of the
     * remove never reach A and A's stable frontier reads bottom for A's own
     * source. The first checkpoint therefore reclaims nothing — which is also
     * the `[KE3-30]` interlock on the checkpoint path: an uncertified
     * tombstone survives a checkpoint. Releasing the rows raises the frontier,
     * and the *second* checkpoint reclaims what the first could not.
     *
     * The pair of numbers is the discrimination. A trigger that cached a
     * frontier at install time, or that reclaimed on any condition other than
     * the frontier, cannot produce 2-then-reclaimed on one unchanged cell.
     */
    @Test
    fun `a frontier that rises after a checkpoint is not used by it`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        val companionA = mesh.a.replication.watermarkOf(logicalId)!!
        // Park every delivery ADDRESSED to A's companion: B's and C's row gossip.
        // A's own row still advances (Replication.trackDeliveries calls
        // companion.advance straight from the onDeliver listener).
        mesh.a.registry.hold(companionA.ref)

        opA.put("h1", "v-h1")
        opA.put("h2", "v-h2")
        opA.remove("h1")
        controller.runToIdle()

        val heldStable = mesh.a.replication.stableFrontier(logicalId)
        trace("held", "stable" to heldStable.perSource, "delDots" to delDotCount(mesh.ra))
        // Non-vacuity: there IS a tombstone to reclaim, and the frontier is the
        // only thing declining it.
        delDotCount(mesh.ra) shouldBe 2 // {put-dot, del-dot} for h1
        heldStable.perSource.keys.shouldBeEmpty()

        mesh.checkpointA()
        trace("checkpoint-1", "delDots" to delDotCount(mesh.ra))
        delDotCount(mesh.ra) shouldBe 2
        tombstonedKeys(mesh.ra) shouldBe setOf("h1")

        // The rows land: the frontier rises AFTER checkpoint 1 has run.
        mesh.a.registry.release(companionA.ref)
        controller.runToIdle()
        val landedStable = mesh.a.replication.stableFrontier(logicalId)
        trace("landed", "stable" to landedStable.perSource, "delDots" to delDotCount(mesh.ra))
        landedStable.perSource.values.toList() shouldNotBe emptyList<Long>()
        // Still 2: the rise is not retroactive, and nothing outside a snapshot reclaims.
        delDotCount(mesh.ra) shouldBe 2

        // The SECOND checkpoint reads the risen frontier in its own pass.
        mesh.checkpointA()
        trace("checkpoint-2", "delDots" to delDotCount(mesh.ra), "membership" to mesh.ra.membership())
        delDotCount(mesh.ra) shouldBe 0
        mesh.ra.membership() shouldBe setOf("h2")
    }
}
