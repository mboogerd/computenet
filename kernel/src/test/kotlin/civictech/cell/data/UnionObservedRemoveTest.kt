package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.ObservedRemoveOps
import civictech.cell.data.op.UnionSetApi
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.view.SetView
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.RoutedPropagate
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * D-UNION — union-scoped observed remove
 * ([civictech.cell.data.op.ObservedRemoveOps.removeObserved]).
 *
 * The defect this closes: `demo/shopping` applies removal to the caller's own
 * per-user writer, so `alice add e; bob add e; alice remove e` leaves `e`
 * present — and a remove of an item you never added is a silent no-op. That is
 * correct *per-writer* OR-set semantics (and stays so — see
 * `UnionSetCellTest.element stays live while another source's tag survives`,
 * which this suite must not weaken); what was missing is the *list-level*
 * intent: remove the element as it exists in the merged view.
 *
 * The hazard the primitive has to survive is catch-up resurrection: the del is
 * minted at the union, while the add-tags it covers live on in the originating
 * writers, which re-assert their full tag state on every late-join catch-up and
 * every anti-entropy replay — on a different stream than the one that carried
 * the del. Hence retained tombstones at the merge point
 * (`TagState(retainTombstones = true)`), covered below and at the journal-replay
 * level.
 */
class UnionObservedRemoveTest {

    @Suppress("UNCHECKED_CAST")
    private fun deltas(buffer: List<Invocation>) = buffer.map { it.args[0] as SetDelta<String> }

    /** Live membership of everything an outlet has emitted, folded the way any consumer would. */
    private fun membership(buffer: List<Invocation>): Set<String> =
        SetView<String>().apply { deltas(buffer).forEach { apply(it) } }.current()

    private fun tap(union: UnionSetCell<String>): MutableList<Invocation> {
        val buffer = mutableListOf<Invocation>()
        union.outlet.subscribe(Use.fixed(buffering<Propagate<SetDelta<String>>>(buffer), PortRef.generate()))
        return buffer
    }

    /**
     * The handshake link — the `LinkFrom` overload, not the ad-hoc `Use` one:
     * only this path fires the post-install `onLinked` catch-up (G-22), which
     * is precisely the stream this ticket's resurrection hazard rides
     * (`LateJoinCatchUpTest` is the exemplar).
     */
    private fun link(
        outlet: FanOutlet<Propagate<SetDelta<String>>>,
        inlet: FanInlet<Propagate<SetDelta<String>>>,
    ) {
        val result = outlet.linkTo(inlet as LinkFrom<Propagate<SetDelta<String>>>)
        assertTrue(result is LinkResult.Connected, "link refused: $result")
    }

    /**
     * The exact state-as-delta [writer] unicasts to a late-joining subscriber
     * (`SetCell.catchUpOnLinked`): captured by linking it to a throwaway union
     * that starts empty, so that union's emissions *are* the catch-up.
     */
    private fun catchUpOf(writer: SetCell<String>): SetDelta<String> {
        val probe = UnionSetCell<String>()
        val buffer = tap(probe)
        link(writer.outlet, probe.inlet)
        return deltas(buffer).fold(SetDelta()) { acc, d -> acc.merge(d) }
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `one observed remove retracts every writer's add of the element`() {
        val alice = SetCell<String>()
        val bob = SetCell<String>()
        val union = UnionSetCell<String>()
        link(alice.outlet, union.inlet)
        link(bob.outlet, union.inlet)
        val out = tap(union)

        alice.inlet.call.add("coffee")
        bob.inlet.call.add("coffee")
        assertEquals(setOf("coffee"), membership(out))

        // ONE remove, by one participant, over the merged view
        union.removeInlet.call.removeObserved("coffee")

        assertEquals(emptySet<String>(), membership(out), "a single union-scoped remove must retract both adds")
        // and the emission is a real del delta covering BOTH writers' tags
        val emitted = deltas(out).fold(SetDelta<String>()) { acc, d -> acc.merge(d) }
        assertEquals(emitted.adds.getValue("coffee"), emitted.dels.getValue("coffee"))
        assertEquals(2, emitted.dels.getValue("coffee").size)
    }

    @Test
    fun `removing an element the merged view has not observed is a no-op`() {
        val union = UnionSetCell<String>()
        val out = tap(union)

        union.removeInlet.call.removeObserved("never-added")

        assertEquals(0, out.size, "effective-only (21, [24-SET-01]): no delta for an unobserved element")
    }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `add-wins - a tag unobserved at remove time survives the observed remove`() {
        val alice = SetCell<String>()
        val bob = SetCell<String>()
        val union = UnionSetCell<String>()
        link(alice.outlet, union.inlet)
        val out = tap(union)

        alice.inlet.call.add("coffee")
        // bob's add is CONCURRENT: his writer is not yet joined to this merged
        // view, so its tag is not among the ones the remove observes
        bob.inlet.call.add("coffee")

        union.removeInlet.call.removeObserved("coffee")
        assertEquals(emptySet<String>(), membership(out))

        // bob's stream now merges (late join / a peer connecting): his tag was
        // never observed by the remove, so the element re-enters — add-wins as a
        // consequence of tag-set union, spec 24 [24-SET-03]. This is the
        // documented distributed boundary, not a lost remove.
        link(bob.outlet, union.inlet)
        assertEquals(setOf("coffee"), membership(out), "an unobserved concurrent add must survive (add-wins)")
    }

    // ------------------------------------------- no catch-up resurrection

    @Test
    fun `a writer's catch-up re-assertion does not resurrect an observed-removed element`() {
        val alice = SetCell<String>()
        val union = UnionSetCell<String>()
        link(alice.outlet, union.inlet)
        val out = tap(union)

        alice.inlet.call.add("coffee")
        union.removeInlet.call.removeObserved("coffee")
        assertEquals(emptySet<String>(), membership(out))

        // alice's writer never learned of the union-level del: its own state
        // still carries the add-tag with no tombstone. This is exactly what a
        // late-join catch-up and a peer re-announce anti-entropy replay
        // re-deliver — on a different stream than the one that carried the del.
        val reassertion = catchUpOf(alice)
        assertTrue(reassertion.adds.getValue("coffee").isNotEmpty(), "the writer really does re-assert its add-tag")
        assertTrue(reassertion.dels.isEmpty(), "and it carries no tombstone of its own")

        val before = out.size
        union.inlet.call.propagate(reassertion)
        union.inlet.call.propagate(reassertion) // idempotent under repeat replay

        assertEquals(emptySet<String>(), membership(out), "the removed element must stay absent")
        assertEquals(before, out.size, "and a fully-tombstoned re-assertion must emit nothing")
    }

    @Test
    fun `the union's own catch-up carries its tombstones, so a late-joining peer converges`() {
        val alice = SetCell<String>()
        val union = UnionSetCell<String>()
        link(alice.outlet, union.inlet)
        alice.inlet.call.add("coffee")
        alice.inlet.call.add("tea")
        union.removeInlet.call.removeObserved("coffee")

        // a peer union joining now (the Peering re-announce chain in
        // demo/shopping) gets live tags AND the retained dels
        val peer = UnionSetCell<String>()
        val peerOut = tap(peer)
        link(union.outlet, peer.inlet)
        assertEquals(setOf("tea"), membership(peerOut))

        // ... and the peer's own copy of alice's writer cannot resurrect it
        peer.inlet.call.propagate(catchUpOf(alice))
        assertEquals(setOf("tea"), membership(peerOut), "the peer learned the del from the catch-up")
    }

    @Test
    fun `a checkpoint-restored union keeps its tombstones`() {
        val alice = SetCell<String>()
        val union = UnionSetCell<String>()
        link(alice.outlet, union.inlet)
        alice.inlet.call.add("coffee")
        union.removeInlet.call.removeObserved("coffee")

        val restored = UnionSetCell<String>()
        restored.restore(union.snapshot())
        val out = tap(restored)

        restored.inlet.call.propagate(catchUpOf(alice))
        assertEquals(emptySet<String>(), membership(out), "a restored merge point must not forget its tombstones")
    }

    @Test
    fun `a pre-D-UNION single-map snapshot still restores`() {
        val source = UnionSetCell<String>()
        val alice = SetCell<String>()
        link(alice.outlet, source.inlet)
        alice.inlet.call.add("coffee")

        // no tombstones anywhere: the snapshot is still the legacy live-only
        // map, so an existing consumer of the shape reads it unchanged
        val snapshot = source.snapshot()
        assertTrue(snapshot is java.util.HashMap<*, *>, "a tombstone-free snapshot keeps the pre-D-UNION shape")

        // ... and restoring it yields a merge point whose catch-up is the state
        val restored = UnionSetCell<String>()
        restored.restore(snapshot)
        val peer = UnionSetCell<String>()
        val peerOut = tap(peer)
        link(restored.outlet, peer.inlet)
        assertEquals(setOf("coffee"), membership(peerOut))
    }

    // ------------------------------------------------------------------ (c)

    /**
     * The demo/shopping durability shape (M10.4), at kernel level: per-user
     * writer `SetCell`s with deterministic refs (hence replay-stable tag
     * sources) routing their deltas into a shared union, every routed
     * invocation write-ahead journaled. After a crash the graph is rebuilt at
     * the same refs and the journal replayed through the ordinary intake — the
     * observed remove replays with it, and the element it removed must stay
     * removed even though both writers re-mint the very add-tags it covered.
     */
    private class Session(controller: SimulationController, journal: Journal) {
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            // per-cell durability (CP-C1): only the writers and the union are
            // journaled, so the test's own observation tap stays volatile
            journalFor = { ref -> if (ref in durableRefs) journal else null },
        )
        val union = UnionSetCell<String>(UNION_REF)
        val alice = SetCell<String>(ALICE_REF)
        val bob = SetCell<String>(BOB_REF)

        init {
            host.managementInlet.call.spawn(union)
            listOf(alice, bob).forEach { writer ->
                host.managementInlet.call.spawn(writer)
                writer.outlet.streamTo(RoutedPropagate(UNION_REF, "inlet", registry::deliver))
            }
        }

        fun writer(ref: CellRef): SetOps<String> =
            host.lookup(TypedRef<SetApi<String>>(ref))!!.inlet.call

        fun eraser(): ObservedRemoveOps<String> =
            host.lookup(TypedRef<UnionSetApi<String>>(UNION_REF))!!.removeInlet.call

        companion object {
            private fun refOf(name: String) = CellRef(UUID.nameUUIDFromBytes("d-union-test:$name".toByteArray()))
            val UNION_REF = refOf("union")
            val ALICE_REF = refOf("alice")
            val BOB_REF = refOf("bob")
            val durableRefs = setOf(UNION_REF, ALICE_REF, BOB_REF)
        }
    }

    @Test
    fun `journal replay reproduces post-remove membership`() {
        val controller = SimulationController(7L)
        val journal = InMemoryJournal() // "the disk": the only thing that survives

        val first = Session(controller, journal)
        val liveOut = first.tapUnion()
        controller.runToIdle()

        first.writer(Session.ALICE_REF).add("coffee")
        first.writer(Session.BOB_REF).add("coffee")
        first.writer(Session.ALICE_REF).add("tea")
        controller.runToIdle()
        assertEquals(setOf("coffee", "tea"), membership(liveOut))

        first.eraser().removeObserved("coffee")
        controller.runToIdle()
        assertEquals(setOf("tea"), membership(liveOut))

        // CRASH: host, registry, queues, links all discarded; the graph is
        // rebuilt at the same refs, then the journal tail replays into it
        val recovered = Session(controller, journal)
        val replayOut = recovered.tapUnion()
        controller.runToIdle()
        recovered.host.recoverFrom(journal)
        controller.runToIdle()

        assertEquals(
            setOf("tea"), membership(replayOut),
            "replay re-mints the same add-tags (ref-derived, M10.1); the replayed observed remove must still cover them",
        )
    }

    private fun Session.tapUnion(): MutableList<Invocation> = tap(union)
}
