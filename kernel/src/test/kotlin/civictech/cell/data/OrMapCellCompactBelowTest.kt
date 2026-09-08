package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.Invocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * Pins `OrMapCell.compactBelow` and its re-admission fence (computenet-9sm.8.3,
 * decisions 9sm.8-D6/D7) — `SetCellCompactBelowTest`'s shape, dot-shaped.
 *
 * The rule: a `dels` **entry** every one of whose dots is at or below a
 * per-source frontier is dropped whole, together with the `puts` dots (and
 * VALUES) it covers; an entry that is only partly covered is left alone in
 * full. Since `remove` and a re-put's retract half mint a **del-dot** into the
 * entry (9sm.8-D5, landed by computenet-9sm.8.1), "every dot ≤ frontier"
 * reaches the dot, which is what makes the rule certify that the REMOVE was
 * delivered and not merely the put it covers (`[KE3-23]`, computenet-v2ka).
 * Membership and per-key VALUE are unchanged by construction, an absent source
 * is bottom (`[KE3-30]`), and nothing is emitted.
 *
 * The discard RECORDS what it discarded, in `ReclaimedDots<K>` keyed by MAP
 * KEY — the re-admission fence, `[24-TAG-04]`'s second clause. A later delta
 * carrying a discarded dot is inadmissible on BOTH lanes however it arrives,
 * including through a `ReBaseline` re-assertion, and a fenced PUT-dot is
 * answered with a minimal repair tombstone rather than dropped silently: a
 * silent fence converts a resurrection into a permanent divergence (measured on
 * the element-shaped sibling at 30 of 200 sweep seeds, `SetCell.applyRemote`).
 * The fence is `snapshot()` state; the key-removed control below is what shows
 * that persisting it is load-bearing.
 *
 * Every expected count is hand-evaluated against the rule and re-derived here
 * rather than copied: see [fixture] for the dot arithmetic.
 */
class OrMapCellCompactBelowTest {

    // -----------------------------------------------------------------
    // fixture
    // -----------------------------------------------------------------

    /**
     * One cell, one key, and the four writes that produce every dot state the
     * rule distinguishes. Counters, derived from `OrMapCell`'s mint order
     * (9sm.8-D5: a re-put mints the retract del-dot FIRST, `n`, and the new
     * value's put-dot SECOND, `n+1`):
     *
     * | write | mints | `puts[k]` | `dels[k]` |
     * |---|---|---|---|
     * | `put k v1` (key absent — one dot) | s:1 | {1→v1} | — |
     * | `put k v2` (key live — retract + put) | s:2 (del), s:3 | {1→v1, 3→v2} | {1, 2} |
     * | `remove k` (covers the live 3, mints its own) | s:4 (del) | unchanged | {1, 2, 3, 4} |
     * | `put k v5` (key dead — one dot) | s:5 | {1→v1, 3→v2, 5→v5} | unchanged |
     *
     * So `value(k) == v5` on the LIVE dot 5, and dots 1 and 3 are the only
     * `puts ∩ dels` — the two a full-cover discard takes with the entry.
     */
    private class Fixture {
        val cell = OrMapCell<String, String>()
        val emitted = mutableListOf<TaggedMapDelta<String, String>>()
        val source: UUID

        init {
            cell.outlet.subscribe(
                Use.fixed(Propagate<TaggedMapDelta<String, String>> { emitted += it }, PortRef.generate())
            )
            cell.inlet.call.put("k", "v1")
            cell.inlet.call.put("k", "v2")
            cell.inlet.call.remove("k")
            cell.inlet.call.put("k", "v5")
            source = emitted[0].puts.getValue("k").keys.single().sourceId
        }

        fun dot(counter: Long) = Timestamp(source, counter)

        fun frontier(counter: Long) = TagFrontier(mapOf(source to counter))

        fun deliver(delta: TaggedMapDelta<String, String>) = deliverTo(cell, delta)
    }

    private companion object {
        val propagateMethod: java.lang.reflect.Method =
            Propagate::class.java.getMethod("propagate", Any::class.java)

        /** `SetCellSincePullBelowFloorTest.deliverRemote`'s idiom: a remote delta onto `deltaInlet`. */
        fun deliverTo(cell: OrMapCell<String, String>, delta: TaggedMapDelta<String, String>) =
            Invocation.of(propagateMethod, arrayOf(delta), null).invoke(cell.deltaInlet.call)

        @Suppress("UNCHECKED_CAST")
        fun snapshotOf(cell: OrMapCell<String, String>): Map<String, Any> = cell.snapshot() as Map<String, Any>

        fun putCounters(cell: OrMapCell<String, String>, key: String): Set<Long> =
            cell.state().puts[key].orEmpty().keys.map { it.counter }.toSet()
    }

    /** A stand-in peer that emits hand-built deltas, plainly or as a `ReBaseline` (`OrMapConvergenceTest`'s). */
    private class DeltaSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<TaggedMapDelta<String, String>>>())

        fun reBaseline(supersedes: Set<UUID>, delta: TaggedMapDelta<String, String>) =
            outlet.reBaseline(supersedes, supersede = true) { propagate(delta) }
    }

    // -----------------------------------------------------------------
    // the discard rule
    // -----------------------------------------------------------------

    /**
     * **The discard is per ENTRY, not per dot**, and the first arm is the safety
     * property rather than a stale literal. At a frontier of 2 the entry's dots
     * 1 and 2 are covered but 3 and the del-dot 4 are not, so the entry is not
     * certified delivered and NOTHING is discarded — `state()` is structurally
     * equal across the call. A per-dot reclaimer would discard 1 and 2 and
     * return 2; that difference is the datum.
     *
     * The second arm shows the reclaimer is not merely inert: at a frontier that
     * reaches the del-dot the whole entry goes, and the two `puts` dots it
     * covers (with their values) go with it, while the LIVE dot 5 — which no del
     * names, and which is ≤ frontier — is untouched. Membership and value are
     * therefore unchanged by construction.
     */
    @Test
    fun `discards an entry only when every dot including the del-dot is covered, and value is unchanged`() {
        val f = Fixture()
        assertEquals(setOf("k"), f.cell.membership())
        assertEquals("v5", f.cell.value("k"))
        assertEquals(setOf(1L, 2L, 3L, 4L), f.cell.state().dels.getValue("k").map { it.counter }.toSet())
        assertEquals(setOf(1L, 3L, 5L), putCounters(f.cell, "k"))

        // ARM 1 — partial cover: 0, and the state is bit-for-bit what it was.
        val before = f.cell.state()
        assertEquals(0, f.cell.compactBelow(f.frontier(2L)))
        assertEquals(before, f.cell.state())
        assertEquals(setOf("k"), f.cell.membership())
        assertEquals("v5", f.cell.value("k"))

        // ARM 2 — full cover: four del dots plus the two put-dots they cover.
        assertEquals(6, f.cell.compactBelow(f.frontier(10L)))
        assertEquals(setOf(5L), putCounters(f.cell, "k"))
        assertFalse("k" in f.cell.state().dels, "dels should drop the key once its entry empties")
        assertEquals(setOf("k"), f.cell.membership())
        assertEquals("v5", f.cell.value("k"))
        // …and exactly what was discarded is what the fence now holds.
        assertEquals(setOf(f.dot(1L), f.dot(3L)), f.cell.fencedAmong("k", setOf(f.dot(1L), f.dot(3L), f.dot(5L))))
        assertTrue(f.cell.fencesAny("k"))
    }

    /** `[KE3-30]`/`[42-WM-05]`: a dot source with no row in the frontier reads as bottom. */
    @Test
    fun `interlock KE3-30 an absent source is bottom`() {
        val f = Fixture()
        val before = f.cell.state()

        assertEquals(0, f.cell.compactBelow(TagFrontier(emptyMap())))
        assertEquals(before, f.cell.state())

        assertEquals(0, f.cell.compactBelow(TagFrontier(mapOf(UUID.randomUUID() to 100L))))
        assertEquals(before, f.cell.state())
        assertFalse(f.cell.fencesAny("k"), "a declined discard records nothing in the fence")
    }

    /** Compaction is not a write: it emits nothing and spends no counter. */
    @Test
    fun `no emission, and the dot counter is untouched`() {
        val f = Fixture()
        val emissionsBefore = f.emitted.size
        val counterBefore = snapshotOf(f.cell)["counter"]

        assertEquals(6, f.cell.compactBelow(f.frontier(10L)))

        assertEquals(emissionsBefore, f.emitted.size)
        assertEquals(counterBefore, snapshotOf(f.cell)["counter"])
        // the lane continues from where it was: the next mint is 6, not 1
        f.cell.inlet.call.put("other", "v")
        assertEquals(6L, f.emitted.last().puts.getValue("other").keys.single().counter)
    }

    // -----------------------------------------------------------------
    // the re-admission fence
    // -----------------------------------------------------------------

    /**
     * **The put lane, and the repair that keeps the fence from being silent.**
     *
     * Three builds answer this differently, which is what makes it a witness:
     * a build with NO fence folds the replayed dot 1 as novel (`puts[k]` becomes
     * `{1, 5}`) and re-emits a `puts` delta; a build with a SILENT fence emits
     * nothing at all; this build emits EXACTLY ONE delta, with no `puts` and a
     * `dels` entry naming exactly the fenced dot. The presence of that one
     * `dels`-only emission is the datum distinguishing fence-with-repair from
     * fence-without.
     */
    @Test
    fun `a replayed reclaimed put-dot is fenced and answered with exactly one repair tombstone`() {
        val f = Fixture()
        assertEquals(6, f.cell.compactBelow(f.frontier(10L)))
        val emissionsBefore = f.emitted.size

        f.deliver(TaggedMapDelta(puts = mapOf("k" to mapOf(f.dot(1L) to "v1"))))

        assertEquals(setOf(5L), putCounters(f.cell, "k"), "a reclaimed put-dot must not be re-admitted")
        assertEquals("v5", f.cell.value("k"))
        assertEquals(emissionsBefore + 1, f.emitted.size, "the fence is not silent: one repair goes out")
        val repair = f.emitted.last()
        assertTrue(repair.puts.isEmpty(), "a fenced frame re-admits nothing: $repair")
        assertEquals(setOf(f.dot(1L)), repair.dels.getValue("k"))
    }

    /**
     * **The del lane.** A re-delivered `dels` entry that was already reclaimed
     * carries no novelty, so no tombstone is rebuilt and nothing is re-emitted —
     * the discard/re-deliver/re-emit/discard loop cannot start.
     */
    @Test
    fun `a replayed reclaimed del-dot is fenced on the del lane too, and is silent`() {
        val f = Fixture()
        assertEquals(6, f.cell.compactBelow(f.frontier(10L)))
        val emissionsBefore = f.emitted.size
        val stateBefore = f.cell.state()

        f.deliver(TaggedMapDelta(dels = mapOf("k" to setOf(f.dot(3L)))))

        assertEquals(stateBefore, f.cell.state(), "a reclaimed tombstone must not be rebuilt")
        assertEquals(emissionsBefore, f.emitted.size, "a fully fenced frame re-emits nothing")
    }

    /**
     * **The re-baseline fold honours the fence too** (9sm.8-D6). `applyReBaseline`
     * step (b) calls `novelty(delta, fenced = false)`, which lifts only the
     * DEAD-SOURCE fence; a reclaimed dot re-asserted by a `ReBaseline` is still
     * inadmissible, and is repaired exactly as on the ordinary path.
     *
     * The notice also supersedes the source, so its step (a) retracts the live
     * dot 5 as a tombstone — asserted here so the repair is read beside what the
     * re-baseline itself did, not confused with it.
     */
    @Test
    fun `a reclaimed dot re-asserted by a supersede re-baseline stays inert and is repaired`() {
        val f = Fixture()
        assertEquals(6, f.cell.compactBelow(f.frontier(10L)))
        val emissionsBefore = f.emitted.size

        val source = DeltaSource()
        source.outlet.subscribe(f.cell.deltaInlet)
        source.reBaseline(
            supersedes = setOf(f.source),
            delta = TaggedMapDelta(puts = mapOf("k" to mapOf(f.dot(1L) to "v1"))),
        )

        assertEquals(setOf(5L), putCounters(f.cell, "k"), "the reclaimed dot 1 is not re-admitted past the re-baseline")
        assertEquals(emissionsBefore + 1, f.emitted.size)
        val out = f.emitted.last()
        assertTrue(out.puts.isEmpty(), "nothing was admitted on the put lane: $out")
        assertTrue(f.dot(1L) in out.dels.getValue("k"), "the repair names the fenced dot: $out")
        // step (a)'s own work, for contrast: the un-reasserted live dot is tombstoned
        assertTrue(f.dot(5L) in out.dels.getValue("k"))
        assertEquals(emptySet<String>(), f.cell.membership())
    }

    // -----------------------------------------------------------------
    // persistence
    // -----------------------------------------------------------------

    /**
     * **The fence is checkpoint state, and the key-removed arm is the control
     * that proves it.** A restored replica that forgot what it had reclaimed
     * re-admits the next replayed frame exactly as an unfenced one does — which
     * is precisely what the third arm demonstrates, on the same blob with one
     * key deleted.
     */
    @Test
    fun `the reclaimed fence rides snapshot and restore, and dropping the key re-admits`() {
        val f = Fixture()
        assertEquals(6, f.cell.compactBelow(f.frontier(10L)))
        val blob = snapshotOf(f.cell)
        assertNotNull(blob["reclaimed"], "the fence is serialised under its own additive key")

        // WITH the fence: the replay is inert on a fresh cell too.
        val restored = OrMapCell<String, String>()
        restored.restore(HashMap(blob) as Serializable)
        assertEquals(setOf(5L), putCounters(restored, "k"))
        deliverTo(restored, TaggedMapDelta(puts = mapOf("k" to mapOf(f.dot(1L) to "v1"))))
        assertEquals(setOf(5L), putCounters(restored, "k"), "the restored fence still rejects dot 1")

        // WITHOUT it — the control. Same blob, "reclaimed" removed: the replay
        // is folded as novelty and dot 1 comes back, values and all.
        val unfenced = OrMapCell<String, String>()
        unfenced.restore(HashMap(blob).apply { remove("reclaimed") } as Serializable)
        assertEquals(setOf(5L), putCounters(unfenced, "k"))
        deliverTo(unfenced, TaggedMapDelta(puts = mapOf("k" to mapOf(f.dot(1L) to "v1"))))
        assertEquals(setOf(1L, 5L), putCounters(unfenced, "k"), "without the fence the reclaimed dot is re-admitted")
    }

    /**
     * **`snapshot()` is the reclaimer's only production caller** (`[KE3-30]`,
     * 9sm.6-D1/9sm.8-D7): it reads the installed stability read, compacts at
     * that frontier, and serialises — the serialised blob and the cell's own
     * state therefore agree, which is what stops a restore re-admitting.
     *
     * The null-read arm is the safety default: no `Replication`, no read, no
     * reclamation, and `snapshot()` serialises exactly what it always did.
     */
    @Test
    fun `snapshot compacts at the installed stability read, and a null read discards nothing`() {
        // ARM 1 — no read installed: snapshot is inert.
        val untracked = Fixture()
        val before = untracked.cell.state()
        val blobBefore = snapshotOf(untracked.cell)
        assertEquals(before, untracked.cell.state())
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            setOf(1L, 2L, 3L, 4L),
            (blobBefore["dels"] as Map<String, Set<Timestamp>>).getValue("k").map { it.counter }.toSet(),
        )

        // ARM 2 — a read that answers null is likewise not a licence to discard.
        untracked.cell.onStability { null }
        snapshotOf(untracked.cell)
        assertEquals(before, untracked.cell.state())

        // ARM 3 — a read that certifies the whole entry: the discard happens
        // BEFORE serialisation, so the blob has no `k` tombstone either.
        val f = Fixture()
        f.cell.onStability { f.frontier(10L) }
        val blob = snapshotOf(f.cell)
        @Suppress("UNCHECKED_CAST")
        assertFalse("k" in (blob["dels"] as Map<String, Set<Timestamp>>))
        @Suppress("UNCHECKED_CAST")
        assertEquals(
            setOf(5L),
            (blob["puts"] as Map<String, Map<Timestamp, String>>).getValue("k").keys.map { it.counter }.toSet(),
        )
        assertEquals(setOf(5L), putCounters(f.cell, "k"), "the compaction ran on the cell, not on a copy")
        assertNotNull(blob["reclaimed"])
        assertEquals(setOf("k"), f.cell.membership())
        assertEquals("v5", f.cell.value("k"))
    }

    /**
     * A tombstone with no matching put — the remote-tombstone-before-put case —
     * is discarded like any other, and enters the fence like any other.
     */
    @Test
    fun `a tombstone with no matching put is discarded like any other`() {
        val cell = OrMapCell<String, String>()
        val o = UUID.randomUUID()
        deliverTo(cell, TaggedMapDelta(dels = mapOf("y" to setOf(Timestamp(o, 1), Timestamp(o, 2)))))
        assertEquals(emptySet<String>(), cell.membership())

        assertEquals(2, cell.compactBelow(TagFrontier(mapOf(o to 2L))))
        assertEquals(emptySet<String>(), cell.membership())
        assertFalse("y" in cell.state().dels)
        assertTrue(cell.fencesAny("y"))
    }
}
