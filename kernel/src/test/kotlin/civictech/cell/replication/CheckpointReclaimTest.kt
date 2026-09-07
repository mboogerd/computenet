package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StateRead
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.data.delta.SetDelta
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * **The TRIGGER** (computenet-9sm.6.1, decisions 9sm.6-D1/D3; `[KE3-30]`,
 * `[KE3-31]`, `[KE3-32]`, `[KE3-33]`'s kernel half).
 *
 * `SetCell.compactBelow` has been the fenced, persisted reclaimer since
 * computenet-v2ka and computenet-pay7, with **no production caller at all** —
 * every call site on `main` was a test. This file pins the caller that closes
 * that: `SetCell.snapshot()` reads the stability read
 * `Replication.trackDeliveries` installs
 * ([civictech.cell.data.delta.StabilityReclaim]) and reclaims below it before
 * serialising, so `HostDurability.checkpoint` — which snapshots every
 * `Stateful` cell teed to its journal — is where a replica's tombstones
 * actually go away.
 *
 * ## What is asserted here, and what is deliberately not
 *
 * The reclaimer's own rule (all-or-nothing per `dels` entry, the del-dot
 * included; the fence's shape and its both-lane subtraction) is pinned by
 * `SetCellCompactBelowTest` and `CompactionTriggerPinTest` and is **not**
 * restated. What is new, and only measurable here, is the wiring:
 *
 * - a checkpoint reclaims, and *only* a checkpoint does — gossip, `add`,
 *   `remove` and `applyRemote` reclaim nothing (`[KE3-31]`/`[KE3-32]`);
 * - a cell with no stability read installed — anything not under
 *   `Replication` — snapshots exactly as it always did (the null contract,
 *   which is the safety default and the reason a fixture cannot silently
 *   acquire a reclaimer);
 * - `membership()` is identical across a checkpoint and the checkpoint emits
 *   no delta (`[KE3-33]`, kernel half, re-asserted **on the checkpoint path**
 *   rather than on the seam);
 * - the frontier is read **inside the pass** (`[KE3-30]`): a rise that happens
 *   after a checkpoint is not available to it, and the next checkpoint is what
 *   picks up what the first could not.
 *
 * ## Reading tag state without triggering the thing under test
 *
 * `snapshot()` is now a *production reclamation point*, so a test that read
 * tag counts through it would compact the cell it was measuring. Every count
 * below therefore goes through [tagState], which walks
 * `SetCell.readBounded` — a read that mints nothing, emits nothing and
 * reclaims nothing. `snapshot()` is called directly in exactly two places,
 * both of which mean to: the post-checkpoint fence assertion, and `the
 * snapshot that reclaims serialises the post-compaction state`, where the
 * point *is* that a snapshot reclaims — and, there, that it serialises what
 * its own compaction left behind.
 *
 * The mesh fixture is the three-peer `Peering.Loopback` triangle of
 * `CompactionTriggerPinTest` (itself copied from `StableFrontierMeshTest`,
 * whose `Peer`/`Mesh` are file-private — copying is the honest option that
 * file's own note records), with one addition this task needs: peer A's host
 * carries a per-cell journal, so `host.checkpoint(journal)` reaches the data
 * replica and nothing else.
 */
class CheckpointReclaimTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
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
            // is deliberately left volatile — this test checkpoints the OR-set,
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

        val ra: SetCell<String>
        val rb: SetCell<String>
        val rc: SetCell<String>

        init {
            ab = Peering.loopback(a.side, b.side)
            bc = Peering.loopback(b.side, c.side)
            ac = Peering.loopback(a.side, c.side)
            ra = SetCell<String>(CellRef(logicalId, 0)).also { a.replication.replicate(it, a.host) }
            rb = SetCell<String>(CellRef(logicalId, 1)).also { b.replication.replicate(it, b.host) }
            rc = SetCell<String>(CellRef(logicalId, 2)).also { c.replication.replicate(it, c.host) }
            controller.runToIdle()
        }

        fun ops(peer: Peer, cell: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(cell.ref, peer.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        fun checkpointA() = a.host.checkpoint(a.journal)

        fun memberships(): List<Set<String>> = listOf(ra.membership(), rb.membership(), rc.membership())
    }

    private companion object {
        /**
         * `element -> (addTags, delTags)`, read through `readBounded` so the
         * measurement does not itself reclaim. See the class KDoc.
         */
        @Suppress("UNCHECKED_CAST")
        fun tagState(cell: SetCell<String>): Map<String, Pair<Set<Timestamp>, Set<Timestamp>>> {
            val out = HashMap<String, Pair<Set<Timestamp>, Set<Timestamp>>>()
            var request = StateRead(limit = 64)
            while (true) {
                val page = cell.readBounded(request)
                page.entries.forEach { entry ->
                    val e = entry as SetCell.SetStateEntry<String>
                    out[e.element] = e.addTags to e.delTags
                }
                val next = page.next ?: break
                request = StateRead(cursor = next, limit = 64)
            }
            return out
        }

        /** Total tombstone TAGS retained (del-dots plus the add-tags they name). */
        fun delTagCount(cell: SetCell<String>): Int = tagState(cell).values.sumOf { it.second.size }

        /** Elements carrying a tombstone entry at all. */
        fun tombstonedElements(cell: SetCell<String>): Set<String> =
            tagState(cell).filterValues { it.second.isNotEmpty() }.keys

        fun addTagCount(cell: SetCell<String>): Int = tagState(cell).values.sumOf { it.first.size }

        @Suppress("UNCHECKED_CAST")
        fun snapshotOf(cell: SetCell<String>): Map<String, Any> = cell.snapshot() as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        fun buffer(cell: SetCell<String>, into: MutableList<Invocation>) {
            cell.outlet.subscribe(Use.fixed(buffering<Propagate<SetDelta<String>>>(into), PortRef.generate()))
        }

        fun trace(step: String, vararg pairs: Pair<String, Any?>) {
            println("[checkpoint-reclaim] $step: " + pairs.joinToString(" ") { (k, v) -> "$k=$v" })
        }
    }

    /**
     * `[KE3-31]`/`[KE3-32]` — **the tombstone count**, and the clause's second
     * half ("reclamation SHALL run nowhere else") measured rather than argued.
     *
     * A heavy fold gossiped until the stable frontier covers every del-dot
     * keeps every tombstone while nothing checkpoints, *including across a
     * further round of adds, removes and remote applications*. One
     * `host.checkpoint(journal)` on A then reduces A's tombstones to nothing —
     * and B's and C's, which did not checkpoint, are untouched, so the
     * reclamation is attributable to the checkpoint and not to the gossip that
     * accompanied it.
     *
     * The final assertion is the `[KE3-31]` ordering constraint in its
     * observable form: the snapshot that reclaims serialises the emptied maps
     * and the grown `"reclaimed"` fence **from the same state**, which is what
     * makes a checkpoint restorable without re-admitting.
     */
    @Test
    fun `a checkpoint reclaims delivered tombstones and nothing else does`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        // 1. The fold: six adds, three removes, fully gossiped. Every del-dot
        //    reaches B and C, so A's stable frontier certifies all three removes.
        (1..6).forEach { opA.add("e$it") }
        (1..3).forEach { opA.remove("e$it") }
        controller.runToIdle()

        val before = tagState(mesh.ra)
        trace("fold", "memberships" to mesh.memberships(), "delTags" to delTagCount(mesh.ra))
        mesh.memberships() shouldBe listOf(setOf("e4", "e5", "e6"), setOf("e4", "e5", "e6"), setOf("e4", "e5", "e6"))
        tombstonedElements(mesh.ra) shouldBe setOf("e1", "e2", "e3")
        // Non-vacuity: each entry is {add-tag, del-dot} (computenet-v2ka), so 3 removes = 6 tags.
        delTagCount(mesh.ra) shouldBe 6

        // 2. A FURTHER gossip round with no checkpoint: a local add, a local
        //    remove, and the remote applications they cause at B and C. If
        //    `add`, `remove` or `applyRemote` reclaimed, the three settled
        //    tombstones of step 1 would not survive it.
        opA.add("e7")
        opA.remove("e4")
        controller.runToIdle()
        trace("no-checkpoint", "delTags" to delTagCount(mesh.ra), "tombstoned" to tombstonedElements(mesh.ra))
        tombstonedElements(mesh.ra) shouldBe setOf("e1", "e2", "e3", "e4")
        delTagCount(mesh.ra) shouldBe 8
        // and the step-1 entries are the SAME tags, not merely the same count
        listOf("e1", "e2", "e3").forEach { e -> tagState(mesh.ra)[e] shouldBe before[e] }

        val membershipBefore = mesh.ra.membership()
        val addTagsBefore = addTagCount(mesh.ra)

        // 3. ONE checkpoint on A.
        mesh.checkpointA()
        trace(
            "after-checkpoint",
            "A-delTags" to delTagCount(mesh.ra),
            "B-delTags" to delTagCount(mesh.rb),
            "C-delTags" to delTagCount(mesh.rc),
            "A-addTags" to addTagCount(mesh.ra),
        )
        // A: every tombstone was fully delivered, so every one goes, and with it
        // the add-tags it covered (`[KE3-32]`, decision 9sm.6-D3).
        tombstonedElements(mesh.ra).shouldBeEmpty()
        delTagCount(mesh.ra) shouldBe 0
        addTagCount(mesh.ra) shouldBe addTagsBefore - 4 // e1..e4's add-tags went with their dels
        mesh.ra.membership() shouldBe membershipBefore

        // B and C did not checkpoint — and did not reclaim. This is the control
        // arm that makes the reduction above attributable to the checkpoint.
        delTagCount(mesh.rb) shouldBe 8
        delTagCount(mesh.rc) shouldBe 8

        // 4. The persisted result of the pass: emptied maps beside a grown
        //    fence. NOTE (review, computenet-9sm.6.1) — this is a *state*
        //    assertion and NOT a discriminator for `[KE3-31]`'s ordering: A has
        //    already been reclaimed by the checkpoint above, so a wiring that
        //    serialised before compacting would serialise the same empty maps
        //    here and pass. Measured: with only the tests this file had before
        //    the ordering discriminator was added, inverting the two statements
        //    in `SetCell.snapshot()` left every one of them green. That
        //    discriminator is `the snapshot that reclaims serialises the
        //    post-compaction state` below, which snapshots a cell that has NOT
        //    yet been compacted; under the same inversion it is now the one and
        //    only test in this file that reddens (its `dels` assertion).
        val snap = snapshotOf(mesh.ra)
        @Suppress("UNCHECKED_CAST")
        (snap["dels"] as Map<String, Set<Timestamp>>).keys.shouldBeEmpty()
        snap["reclaimed"] shouldNotBe null
        snap["reclaimed"].toString() shouldNotBe "{}"
    }

    /**
     * `[KE3-31]`'s **ordering constraint**, as a discriminator (added at
     * review, computenet-9sm.6.1).
     *
     * The requirement is not merely that a snapshot reclaims, but that the
     * reclamation happens BEFORE the serialisation it accompanies, under one
     * hold of `stateLock`: the persisted tag maps and the persisted
     * `"reclaimed"` fence must come out of one post-compaction state, or a
     * restore re-admits tags the fence says were discarded.
     *
     * The only way to see that is to read the return value of the SAME
     * `snapshot()` call that does the compacting, on a cell that has not been
     * compacted yet — which is why this cannot be folded into the tombstone
     * test above, where the checkpoint has already emptied the maps and a
     * serialise-then-compact wiring would serialise the same empty maps.
     * Inverting the two statements in `SetCell.snapshot()` reddens the
     * `dels`-empty assertion here and nothing else in this file.
     */
    @Test
    fun `the snapshot that reclaims serialises the post-compaction state`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        (1..3).forEach { opA.add("s$it") }
        (1..2).forEach { opA.remove("s$it") }
        controller.runToIdle()

        // Non-vacuity: there ARE tombstones to reclaim at the moment of the call.
        tombstonedElements(mesh.ra) shouldBe setOf("s1", "s2")
        delTagCount(mesh.ra) shouldBe 4

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
        delTagCount(mesh.ra) shouldBe 0
        mesh.ra.membership() shouldBe setOf("s3")
    }

    /**
     * `[KE3-33]` kernel half, **on the checkpoint path**. The seam's
     * membership-invariance is already pinned by `SetCellCompactBelowTest`;
     * that is evidence about `compactBelow`, not about the caller this task
     * adds, so it is re-asserted here where the trigger actually fires — with
     * an outlet tap, because a reclamation that emitted would gossip a
     * *tombstone-free* state to peers that still hold the tombstones.
     */
    @Test
    fun `the checkpoint changes no membership and emits no delta`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        (1..4).forEach { opA.add("k$it") }
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
        delTagCount(mesh.ra) shouldBe 0
        emitted.size shouldBe emittedBefore
        mesh.memberships() shouldBe membershipBefore
        mesh.ra.membership() shouldBe setOf("k3", "k4")
    }

    /**
     * The **null contract** ([civictech.cell.data.delta.StabilityReclaim]): a
     * `SetCell` that is not under `Replication` has no stability read
     * installed, so a checkpoint snapshots it exactly as it did before this
     * task existed — zero discards, whatever its tombstones look like.
     *
     * This is the safety default and not an optimisation: a cell with no
     * replica set has no causal-stability certificate to discard against, and
     * "no certificate" must never read as "discard freely".
     */
    @Test
    fun `a SetCell not under Replication reclaims nothing at a checkpoint`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val peer = Peer(controller, logicalId)
        val cell = SetCell<String>(CellRef(logicalId, 0))
        peer.host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        val ops = (HostedCellProxy.create(cell.ref, peer.registry, SetInletProxy::class.java) as SetInletProxy)
            .inlet.call
        (1..3).forEach { ops.add("u$it") }
        ops.remove("u1")
        controller.runToIdle()

        val before = tagState(cell)
        before.keys shouldBe setOf("u1", "u2", "u3")
        delTagCount(cell) shouldBe 2 // {add-tag, del-dot}

        peer.host.checkpoint(peer.journal)
        trace("no-read-installed", "delTags" to delTagCount(cell), "state" to tagState(cell).keys)

        tagState(cell) shouldBe before
        delTagCount(cell) shouldBe 2
        cell.membership() shouldBe setOf("u2", "u3")
        // and the snapshot the checkpoint took is still the whole state
        @Suppress("UNCHECKED_CAST")
        (snapshotOf(cell)["dels"] as Map<String, Set<Timestamp>>).keys shouldBe setOf("u1")
    }

    /**
     * `[KE3-30]` — **the frontier is read inside the pass**, and a rise after
     * a checkpoint is not available to that checkpoint.
     *
     * A's companion is held, so the rows carrying B's and C's delivery of the
     * remove never reach A and A's stable frontier reads bottom for A's own
     * source. The first checkpoint therefore reclaims nothing — which is also
     * the `[KE3-30]` interlock on the checkpoint path: an uncertified tombstone
     * survives a checkpoint. Releasing the rows raises the frontier, and the
     * *second* checkpoint reclaims what the first could not.
     *
     * The pair of numbers is the discrimination. A trigger that cached a
     * frontier at install time, or that reclaimed on any condition other than
     * the frontier, cannot produce 0-then-reclaimed on one unchanged cell.
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

        opA.add("h1")
        opA.add("h2")
        opA.remove("h1")
        controller.runToIdle()

        val heldStable = mesh.a.replication.stableFrontier(logicalId)
        trace("held", "stable" to heldStable.perSource, "delTags" to delTagCount(mesh.ra))
        // Non-vacuity: there IS a tombstone to reclaim, and the frontier is the
        // only thing declining it.
        delTagCount(mesh.ra) shouldBe 2
        heldStable.perSource.keys.shouldBeEmpty()

        mesh.checkpointA()
        trace("checkpoint-1", "delTags" to delTagCount(mesh.ra))
        delTagCount(mesh.ra) shouldBe 2
        tombstonedElements(mesh.ra) shouldBe setOf("h1")

        // The rows land: the frontier rises AFTER checkpoint 1 has run.
        mesh.a.registry.release(companionA.ref)
        controller.runToIdle()
        val landedStable = mesh.a.replication.stableFrontier(logicalId)
        trace("landed", "stable" to landedStable.perSource, "delTags" to delTagCount(mesh.ra))
        landedStable.perSource.values.toList() shouldNotBe emptyList<Long>()
        // Still 2: the rise is not retroactive, and nothing outside a snapshot reclaims.
        delTagCount(mesh.ra) shouldBe 2

        // The SECOND checkpoint reads the risen frontier in its own pass.
        mesh.checkpointA()
        trace("checkpoint-2", "delTags" to delTagCount(mesh.ra), "membership" to mesh.ra.membership())
        delTagCount(mesh.ra) shouldBe 0
        mesh.ra.membership() shouldBe setOf("h2")
    }
}
