package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StateRead
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
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
 * `[KE3-34]` BS-17 — **the checkpoint-crossing property**, which is the half of
 * that clause nothing on `main` exercises (computenet-9sm.6.2).
 *
 * The fence's shape and its persistence landed with computenet-pay7
 * (`SetCell.snapshot()` serialises `ReclaimedDots` under the additive
 * `"reclaimed"` key; `restore()` reads an absent key as an EMPTY fence), and
 * the checkpoint *trigger* landed with computenet-9sm.6.1
 * (`SetCell.snapshot()` is `compactBelow`'s only production caller, reading the
 * stability read `Replication.trackDeliveries` installs). Neither task crossed
 * a crash. This file drives the whole sequence:
 *
 * ```
 * reclaim -> checkpoint -> journaled tail -> crash -> restore -> tail replay
 * ```
 *
 * and then asks the restored replica the same question its pre-crash self was
 * asked, so the two answers can be compared rather than each judged alone.
 *
 * ## The two halves of the clause, and why the second is the easy one to lose
 *
 * 1. `membership()` after restore + tail replay equals the pre-crash
 *    membership.
 * 2. A replayed delta carrying a tag this replica **reclaimed** is absorbed as
 *    already-observed — not folded, not re-emitted as novelty — **and answered
 *    with the repair `dels` entry naming exactly that tag**, identically to the
 *    pre-crash answer.
 *
 * The fence alone is not the fix, and the measurement that says so is recorded
 * on `SetCell.applyRemote`: fence-*without*-the-repair-emission drove
 * `GcSafetySweepTest`'s STABLE resurrections to 0 and took membership
 * divergence to **30 of 200 seeds** (control 3), the same order as the rejected
 * per-source floor; with the repair it is **0 resurrecting / 5-8 diverging**
 * against a 1-4 control. So "the replayed tag is inert" is only half of what is
 * asserted below — the other half is that the minimal `dels` entry is still
 * emitted after restore, and is the SAME entry as before the crash.
 *
 * **A per-source re-admission FLOOR is not what is being tested here and must
 * not be reintroduced**: it was built in three variants and measured unsafe
 * (31-33 of 200 seeds permanently diverged). The fence under test is
 * `ReclaimedDots`, an exact `(element, tag)` dot set written only from tags
 * `compactBelow` actually discarded.
 *
 * ## Non-vacuity — three facts a "membership equal" assertion cannot supply
 *
 * A crash test that restored an empty fence and replayed a tag nothing had ever
 * reclaimed would assert `membership() == pre`, pass, and prove nothing. Each
 * arm is therefore pinned by its own assertion, named at the point it is made:
 *
 * - **the reclamation happened, before the checkpoint** — `e1`'s `dels` entry
 *   is present and complete right up to `checkpoint()`, gone straight after,
 *   and `SetCell.fencesAny("e1")` flips `false -> true` across that one call;
 * - **the fence was persisted and restored non-empty** — after the crash, on a
 *   cell instance constructed empty, `fencedAmong("e1", addTagsE1)` returns the
 *   whole of `addTagsE1`;
 * - **the replayed frame really carried a reclaimed tag** — the delta delivered
 *   in step 6 is built from `addTagsE1`, captured off the live cell before the
 *   checkpoint, and asserted non-empty and wholly fenced at the moment of
 *   delivery.
 *
 * ## Fixture notes
 *
 * Peer A is declared through [DstWorld]'s host seam so the crash is
 * [civictech.testkit.dst.HostSlot.crash]'s own algorithm — discard the
 * in-flight scheduler, rebuild at the **same `CellRef`** from
 * [HostRebuild]/[StableRefs] — exactly as `CrashRecoveryTest` does. B and C
 * stay outside the seam with their own `LocationRegistry`s, for the same reason
 * that test gives: `DstWorld` models one shared registry, and three independent
 * peers are not that.
 *
 * **`recoverFrom` is called by this test rather than by a `CrashFault`, and the
 * ordering is the reason.** `CrashFault.midDrain(journal = …)` recovers inside
 * its own step hook, immediately after the rebuild function returns — i.e.
 * before the graph's cells are re-spawned. `HostDurability.recoverFrom`'s own
 * contract is "call after the graph is rebuilt (cells spawned)": its
 * `restoreCheckpoint` looks the cell up in `cellsView()` and *dead-letters* the
 * blob when it is absent. `CrashRecoveryTest` can live with that because its
 * assertions ride the replayed frames; this test's whole subject is the
 * checkpoint blob, so the sequence here is `slot.crash()` -> re-spawn ->
 * `runToIdle()` -> `recoverFrom(journal)`, which is the same shape with the
 * spawn placed where the restore can see it.
 *
 * A's links are partitioned across the crash so the freshly constructed,
 * still-empty replica cannot be filled by B's and C's catch-up before its own
 * journal has spoken; they are re-opened afterwards, and the catch-up that
 * follows is itself a natural replay of `e1`'s reclaimed tags over a second
 * path.
 *
 * Elements are `String`s deliberately: a journal checkpoint cannot serialise an
 * `Owned`-element replica (`ObjectOutputStream` over a snapshot map whose keys
 * are `Owned`), reported by computenet-9sm.6.6.
 *
 * Non-goal, per the bead: computenet-684h (a repair entry carries no del-dot)
 * is not fixed here.
 */
class CheckpointReclaimCrashTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
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
     * crashable. The mesh shape is `CompactionTriggerPinTest`'s (itself copied
     * from `StableFrontierMeshTest`, whose `Peer`/`Mesh` are file-private —
     * copying is the honest option that file's own note records); A's half is
     * `CrashRecoveryTest`'s host-slot declaration, with `journalFor` narrowed to
     * the data replica (CP-C1) so the delivered-watermark companion stays
     * volatile and the checkpoint blob holds the OR-set and nothing else.
     */
    private class Rig(seed: Long) {
        val world = DstWorld(seed)
        val controller = world.controller
        val journal = world.journals.declare("wal") // "the disk": survives the crash

        private val namespace = "checkpoint-reclaim-crash-$seed"
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

        var ra = SetCell<String>(refA).also { replicationA.replicate(it, slotA.host) }
            private set
        val rb = SetCell<String>(refsB.ref("replica")).also { b.replication.replicate(it, b.host) }
        val rc = SetCell<String>(refsC.ref("replica")).also { c.replication.replicate(it, c.host) }

        init {
            controller.runToIdle()
        }

        private fun proxy(registry: LocationRegistry, cell: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        var opA: SetOps<String> = proxy(world.registry, ra)
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
            ra = SetCell<String>(refA).also { replicationA.replicate(it, slotA.host) }
            controller.runToIdle() // spawn is management-band async: graph first…
            slotA.host.recoverFrom(journal) // …then restore + tail replay
            controller.runToIdle()
            opA = proxy(world.registry, ra)
        }

        /** Re-open A's links; B's and C's catch-up re-ships the tags A reclaimed. */
        fun relink() {
            linksA = listOf(Peering.loopback(sideA, b.side), Peering.loopback(sideA, c.side))
            controller.runToIdle()
        }
    }

    private companion object {
        /**
         * `element -> (addTags, delTags)`, read through `readBounded`, which
         * mints nothing, emits nothing and — unlike `snapshot()`, which is now a
         * production reclamation point — reclaims nothing. Measuring tag state
         * through `snapshot()` would compact the cell being measured.
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

        fun delTagCount(cell: SetCell<String>): Int = tagState(cell).values.sumOf { it.second.size }

        fun tombstonedElements(cell: SetCell<String>): Set<String> =
            tagState(cell).filterValues { it.second.isNotEmpty() }.keys

        @Suppress("UNCHECKED_CAST")
        fun buffer(cell: SetCell<String>, into: MutableList<Invocation>) {
            cell.outlet.subscribe(Use.fixed(buffering<Propagate<SetDelta<String>>>(into), PortRef.generate()))
        }

        /** The `SetDelta`s an outlet tap captured, in order. */
        @Suppress("UNCHECKED_CAST")
        fun emitted(tap: List<Invocation>): List<SetDelta<String>> =
            tap.filter { it.methodName == "propagate" }.map { it.args[0] as SetDelta<String> }

        @Suppress("UNCHECKED_CAST")
        fun snapshotOf(cell: SetCell<String>): Map<String, Any> = cell.snapshot() as Map<String, Any>

        fun trace(step: String, vararg pairs: Pair<String, Any?>) {
            println("[checkpoint-reclaim-crash] $step: " + pairs.joinToString(" ") { (k, v) -> "$k=$v" })
        }
    }

    /**
     * `[KE3-34]`'s checkpoint-crossing property, end to end, on one
     * deterministic schedule.
     *
     * Step 7 — the control — is what makes step 6 a *clause* rather than a
     * plausible-looking assertion: the same replay is delivered to A **before**
     * the crash and after it, and the two emitted answers are compared to each
     * other. A restored replica that had forgotten its fence would fold the
     * add-tag and re-emit it as novelty (an `adds` frame, not a `dels` one); a
     * restored replica that fenced *silently* would emit nothing at all. Only
     * the landed behaviour produces the identical minimal tombstone twice.
     */
    @Test
    fun `checkpoint-driven reclamation survives crash, restore and tail replay`() {
        val rig = Rig(seed = 1L)

        // ------------------------------------------------------------------
        // 1. The fold: adds and one remove, gossiped until A's stable frontier
        //    covers the whole of e1's `dels` entry — the add-tag and the
        //    del-dot computenet-v2ka's `remove` mints alongside it.
        // ------------------------------------------------------------------
        (1..4).forEach { rig.opA.add("e$it") }
        rig.opA.remove("e1")
        rig.controller.runToIdle()

        val addTagsE1 = tagState(rig.ra).getValue("e1").first
        trace("fold", "memberships" to rig.memberships(), "addTagsE1" to addTagsE1)
        rig.memberships() shouldBe List(3) { setOf("e2", "e3", "e4") }
        // NON-VACUITY (a) — there IS a complete tombstone to reclaim at the
        // moment of the checkpoint, and nothing has been reclaimed yet.
        tombstonedElements(rig.ra) shouldBe setOf("e1")
        delTagCount(rig.ra) shouldBe 2 // {add-tag, del-dot}
        addTagsE1.shouldNotBeEmpty()
        rig.ra.fencesAny("e1") shouldBe false
        rig.ra.fencedAmong("e1", addTagsE1).shouldBeEmpty()
        rig.stableFrontierA().perSource.keys.shouldNotBeEmpty()

        // ------------------------------------------------------------------
        // 2. THE CHECKPOINT. `HostDurability.checkpoint` snapshots the journaled
        //    replica, `SetCell.snapshot()` compacts at the stable frontier, and
        //    the fence it writes is serialised in the same call.
        // ------------------------------------------------------------------
        rig.checkpointA()
        trace("checkpoint", "delTags" to delTagCount(rig.ra), "fenced" to rig.ra.fencedAmong("e1", addTagsE1))
        // NON-VACUITY (a), other side — the reclamation actually happened, and
        // happened HERE: `fencesAny` flips across this one call.
        tombstonedElements(rig.ra).shouldBeEmpty()
        delTagCount(rig.ra) shouldBe 0
        rig.ra.fencesAny("e1") shouldBe true
        rig.ra.fencedAmong("e1", addTagsE1) shouldBe addTagsE1
        // …and the reclamation moved no membership (`[KE3-33]`, restated here
        // only because everything below compares against this membership).
        rig.ra.membership() shouldBe setOf("e2", "e3", "e4")

        // ------------------------------------------------------------------
        // 3. The tail: ten more ops on A, journaled AFTER the checkpoint
        //    truncated the WAL down to its blob. These are what replay.
        // ------------------------------------------------------------------
        (5..10).forEach { rig.opA.add("t$it") }
        rig.opA.remove("t5")
        rig.opA.remove("t6")
        rig.opA.add("t11")
        rig.opA.add("t12")
        rig.controller.runToIdle()

        val pre = rig.ra.membership()
        trace("tail", "pre" to pre, "delTags" to delTagCount(rig.ra))
        pre shouldBe setOf("e2", "e3", "e4", "t7", "t8", "t9", "t10", "t11", "t12")

        // ------------------------------------------------------------------
        // 4. THE CONTROL ANSWER, taken pre-crash. A delta carrying e1's
        //    reclaimed add-tag, delivered straight to `deltaInlet` — the wire's
        //    own path (`BoundedStateReadTest` merges peer deltas the same way),
        //    and deliberately NOT through the host intake, so this probe is not
        //    itself journaled into the tail that replays below.
        // ------------------------------------------------------------------
        val replay = SetDelta(adds = mapOf("e1" to addTagsE1))
        val preTap = mutableListOf<Invocation>()
        buffer(rig.ra, preTap)
        rig.ra.deltaInlet.call.propagate(replay)
        rig.controller.runToIdle()

        val preAnswer = emitted(preTap)
        trace("pre-crash answer", "emitted" to preAnswer, "membership" to rig.ra.membership())
        // Fenced, not folded: e1 stays absent and the membership is untouched…
        rig.ra.membership() shouldBe pre
        // …and the fence is not silent — the repair names exactly the fenced tag
        // and nothing else, with an empty `adds` half (no novelty re-emitted).
        preAnswer shouldBe listOf(SetDelta(dels = mapOf("e1" to addTagsE1)))

        // ------------------------------------------------------------------
        // 5. CRASH -> RESTORE -> TAIL REPLAY.
        // ------------------------------------------------------------------
        rig.crashAndRecover()
        trace(
            "recovered",
            "membership" to rig.ra.membership(),
            "fenced" to rig.ra.fencedAmong("e1", addTagsE1),
            "delTags" to delTagCount(rig.ra),
        )

        // THE CLAUSE, first half: membership survives the crossing.
        rig.ra.membership() shouldBe pre
        // NON-VACUITY (b) — the fence was persisted in the checkpoint blob and
        // restored NON-EMPTY onto a cell instance that was constructed empty.
        // Without this the assertions below would hold of an unfenced replica
        // that had simply never been offered the tag.
        rig.ra.fencesAny("e1") shouldBe true
        rig.ra.fencedAmong("e1", addTagsE1) shouldBe addTagsE1

        // The links come back: B and C still hold e1's whole `dels` entry (they
        // never checkpointed), so their catch-up is itself a replay of the
        // reclaimed tags over a second path. It must not resurrect e1 either.
        rig.relink()
        trace("relinked", "memberships" to rig.memberships())
        rig.ra.membership() shouldBe pre
        rig.memberships().toSet().size shouldBe 1

        // ------------------------------------------------------------------
        // 6/7. THE SAME QUESTION, ASKED AGAIN. The restored A is offered the
        //      identical delta, measured the identical way.
        // ------------------------------------------------------------------
        // NON-VACUITY (c) — the frame really does carry a tag THIS replica
        // reclaimed, checked at the moment of delivery rather than assumed from
        // step 2 (the cell being asked is a different object now).
        replay.adds.getValue("e1").shouldNotBeEmpty()
        rig.ra.fencedAmong("e1", replay.adds.getValue("e1")) shouldBe replay.adds.getValue("e1")

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
        postAnswer.single().adds.keys.shouldBeEmpty()
        // …and answered with the repair tombstone EXACTLY as it is pre-crash.
        // This equality is the clause: the fence alone is not the fix.
        postAnswer shouldBe preAnswer
        postAnswer shouldBe listOf(SetDelta(dels = mapOf("e1" to addTagsE1)))
    }

    /**
     * The **fail-safe direction computenet-pay7 deliberately chose**, asserted
     * as design rather than reported as a defect.
     *
     * `"reclaimed"` is an additive snapshot key: a checkpoint blob written
     * before the fence existed does not carry it, and `SetCell.restore` reads
     * an absent key as an EMPTY fence. The consequence is visible and is the
     * point — a replayed add-tag is then FOLDED, which is a recoverable
     * resurrection window (the pre-computenet-pay7 behaviour, which the mesh's
     * ordinary anti-entropy can still repair). The alternative — inventing a
     * fence, or attributing un-keyed runs to a guessed element — produces
     * divergence-by-invented-fence, which is exactly the permanent failure
     * `ReclaimedDots`'s `(element, tag)` key exists to prevent (measured on 4 of
     * 200 STABLE seeds when the key was the tag alone).
     *
     * Both arms are restored into a bare `SetCell` outside `Replication`, so
     * neither can reclaim on its own and the only difference between them is
     * the presence of the key.
     */
    @Test
    fun `a checkpoint blob without the reclaimed key restores an empty fence and re-admits`() {
        val rig = Rig(seed = 2L)

        rig.opA.add("f1")
        rig.opA.add("f2")
        rig.opA.remove("f1")
        rig.controller.runToIdle()
        val addTagsF1 = tagState(rig.ra).getValue("f1").first
        addTagsF1.shouldNotBeEmpty()

        rig.checkpointA()
        // Non-vacuity: the blob under test is one a real reclamation produced.
        rig.ra.fencedAmong("f1", addTagsF1) shouldBe addTagsF1

        val blob = snapshotOf(rig.ra)
        blob["reclaimed"] shouldNotBe null
        blob["reclaimed"].toString() shouldNotBe "{}"

        val stripped = HashMap(blob).also { it.remove("reclaimed") }
        val replay = SetDelta(adds = mapOf("f1" to addTagsF1))

        // Arm 1 — the key present: the restored fence rejects the replay, and
        // answers it with the repair tombstone.
        val kept = SetCell<String>(CellRef(rig.refA.id, 9))
        kept.restore(HashMap(blob) as Serializable)
        val keptTap = mutableListOf<Invocation>()
        buffer(kept, keptTap)
        kept.deltaInlet.call.propagate(replay)
        trace("fence kept", "membership" to kept.membership(), "emitted" to emitted(keptTap))
        kept.fencedAmong("f1", addTagsF1) shouldBe addTagsF1
        kept.membership() shouldBe setOf("f2")
        emitted(keptTap) shouldBe listOf(SetDelta(dels = mapOf("f1" to addTagsF1)))

        // Arm 2 — the key absent: an EMPTY fence, and the replay is folded.
        // Deliberate, and recoverable; see the KDoc.
        val stripe = SetCell<String>(CellRef(rig.refA.id, 10))
        stripe.restore(stripped as Serializable)
        val stripeTap = mutableListOf<Invocation>()
        buffer(stripe, stripeTap)
        stripe.deltaInlet.call.propagate(replay)
        trace("fence dropped", "membership" to stripe.membership(), "emitted" to emitted(stripeTap))
        stripe.fencesAny("f1") shouldBe false
        stripe.fencedAmong("f1", addTagsF1).shouldBeEmpty()
        stripe.membership() shouldBe setOf("f1", "f2") // re-admitted: the window
        emitted(stripeTap) shouldBe listOf(SetDelta(adds = mapOf("f1" to addTagsF1)))
    }
}
