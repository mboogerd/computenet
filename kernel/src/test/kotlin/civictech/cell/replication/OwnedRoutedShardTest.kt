package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.port.Reconciliation
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import civictech.nature.Ownership
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PN-18 (spec 23 §SPSC corollary, plan §3b). The SPSC rule — an `Owned`/`Leased`
 * payload has exactly one downstream consumer — extends verbatim to an instance
 * set: an exclusive-carrying routed port may join a set only under a **disjoint**
 * assignment (each payload reaches exactly one covering instance — a legal
 * move-by-serialize). Total/overlapping interest would fan one exclusive to N
 * instances and is [Reconciliation.Refuse] on the *existing* `OWNERSHIP` axis at
 * instance-set formation — no new vocabulary.
 *
 * The proof is operational: `Owned` payloads routed over an [InstanceSet]'s
 * interest assignment — including across a repartition flip — are each consumed
 * exactly once, none lost (a payload with no covering instance dead-letters as a
 * [Frozen], the G-46 contract on the parked window). The controls make the
 * refusal load-bearing: overlap admitted double-consumes; the refusal is scoped
 * precisely to the exclusive bit.
 */
class OwnedRoutedShardTest {

    private data class Item(val key: Int, val v: String)

    /** One hosted instance: consumes its routed `Owned` exactly once via [Owned.take]. */
    private class Instance(val ref: CellRef) {
        val consumed = mutableListOf<Item>()
        fun consume(o: Owned<Item>) { consumed += o.take() }
    }

    private val EXCLUSIVE = NatureVector.of(Ownership.EXCLUSIVE)
    private val total = 6

    /** slotOf mirrors the router's key→slot map (here the key already IS its slot). */
    private fun slot(key: Int) = Math.floorMod(key, total)

    /**
     * The router: deliver an `Owned` [item] to every instance whose *assigned*
     * interest admits its key — the one [Interest.admits] the real router uses.
     * Disjoint ⇒ exactly one covering instance (one [Owned.take]); a key no
     * instance covers dead-letters as a [Frozen] (G-46: exclusive is never
     * dropped). Handing the same [Owned] to >1 instance is the SPSC violation the
     * second [Owned.take] surfaces loudly.
     */
    private fun route(
        set: InstanceSet,
        instances: Map<CellRef, Instance>,
        item: Item,
        deadLetter: MutableList<Frozen<Item>>,
    ) {
        val owned = Owned(item)
        val covering = instances.keys.filter { set.interestOf(it).admits(slot(item.key)) }
        if (covering.isEmpty()) deadLetter += owned.freeze()
        else covering.forEach { instances.getValue(it).consume(owned) }
    }

    private fun disjointSet(refs: List<CellRef>, slotsPer: List<Set<Int>>): InstanceSet {
        val set = InstanceSet(CellRef(UUID.randomUUID()))
        refs.forEachIndexed { i, r -> set.assign(r, Interest.Slots(slotsPer[i], total), epoch = 0L) }
        return set
    }

    @Test
    fun `disjoint assignment — every Owned consumed exactly once, none lost, across a repartition`() {
        val refs = List(3) { CellRef(UUID.randomUUID(), it.toLong()) }
        val instances = refs.associateWith { Instance(it) }
        // s0={0,1}, s1={2,3}, s2={4,5}: a full disjoint cover of the 6 slots.
        val set = disjointSet(refs, listOf(setOf(0, 1), setOf(2, 3), setOf(4, 5)))

        // formation: an exclusive-carrying port joins a disjoint set (control b — green).
        set.admitExclusive(EXCLUSIVE) shouldBe Reconciliation.Direct

        val deadLetter = mutableListOf<Frozen<Item>>()
        val phase1 = (0 until 12).map { Item(it, "a$it") }
        phase1.forEach { route(set, instances, it, deadLetter) }

        // repartition flip: rotate ownership at a new epoch — still disjoint.
        set.assign(refs[0], Interest.Slots(setOf(4, 5), total), epoch = 1L)
        set.assign(refs[1], Interest.Slots(setOf(0, 1), total), epoch = 1L)
        set.assign(refs[2], Interest.Slots(setOf(2, 3), total), epoch = 1L)
        set.admitExclusive(EXCLUSIVE) shouldBe Reconciliation.Direct

        val phase2 = (12 until 24).map { Item(it, "b$it") }
        phase2.forEach { route(set, instances, it, deadLetter) }

        // exactly-once: every payload consumed once, none lost, none duplicated.
        val all = phase1 + phase2
        val consumed = instances.values.flatMap { it.consumed }
        consumed.size shouldBe all.size
        consumed.toSet() shouldBe all.toSet()
        deadLetter.shouldBeEmpty()
    }

    @Test
    fun `parked-window gap — an uncovered exclusive dead-letters, never lost`() {
        val refs = List(2) { CellRef(UUID.randomUUID(), it.toLong()) }
        val instances = refs.associateWith { Instance(it) }
        // slots {4,5} are owned by nobody — the transient gap a flip window opens.
        val set = disjointSet(refs, listOf(setOf(0, 1), setOf(2, 3)))
        set.admitExclusive(EXCLUSIVE) shouldBe Reconciliation.Direct

        val deadLetter = mutableListOf<Frozen<Item>>()
        val items = (0 until 6).map { Item(it, "x$it") }
        items.forEach { route(set, instances, it, deadLetter) }

        val consumed = instances.values.flatMap { it.consumed }
        // none lost: consumed ∪ dead-lettered == everything sent.
        (consumed.size + deadLetter.size) shouldBe items.size
        deadLetter.size shouldBe 2 // exactly the slot-4 and slot-5 items
    }

    @Test
    fun `control a — overlapping assignment is refused on OWNERSHIP, and double-consumes if bypassed`() {
        val refs = List(3) { CellRef(UUID.randomUUID(), it.toLong()) }
        val instances = refs.associateWith { Instance(it) }
        val set = InstanceSet(CellRef(UUID.randomUUID()))
        // all-Total ⇒ every instance covers every key: a broadcast, not disjoint.
        refs.forEach { set.assign(it, Interest.Total, epoch = 0L) }

        // formation refuses — typed, naming the existing OWNERSHIP axis.
        val refusal = set.admitExclusive(EXCLUSIVE)
        refusal.shouldBeRefuseOn(NatureAxis.OWNERSHIP)

        // bypass the refusal and route anyway: one Owned fans to N instances, and
        // the second take() surfaces the double-consume the refusal exists to stop.
        shouldThrow<IllegalStateException> {
            route(set, instances, Item(0, "boom"), mutableListOf())
        }
    }

    @Test
    fun `control b — the refusal is scoped precisely to the exclusive bit`() {
        val refs = List(2) { CellRef(UUID.randomUUID(), it.toLong()) }
        val set = InstanceSet(CellRef(UUID.randomUUID()))
        refs.forEach { set.assign(it, Interest.Total, epoch = 0L) } // overlapping

        // a SHARED (fan-out-safe, default) port is admitted even under overlap —
        // the refusal fires ONLY for an exclusive-carrying port, nothing else.
        set.admitExclusive(NatureVector.DEFAULT) shouldBe Reconciliation.Direct
    }

    private fun Reconciliation.shouldBeRefuseOn(axis: NatureAxis) {
        check(this is Reconciliation.Refuse) { "expected a Refuse, got $this" }
        this.mismatch.axis shouldBe axis
    }
}
