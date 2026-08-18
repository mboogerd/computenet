package civictech.bench.micro

import civictech.bench.Drive
import civictech.cell.Timestamp
import civictech.cell.data.view.SetView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fast, untagged correctness tests for the micro-benchmark fixtures
 * (`[BEN1-17]` builders, `[BEN1-18]` generators).
 *
 * **Untagged on purpose.** `@Tag("bench")` is excluded from the default test task and
 * selected only under `-PbenchOnly=true`; tagging these would remove them from every
 * default build and every required check while leaving them looking green. They execute
 * no benchmark, sleep nowhere, and run in well under a second.
 *
 * ## What these tests are actually for
 *
 * A measurement fixture that silently measures the wrong thing is worse than one that
 * fails loudly, and the specific way *this* fixture could go quietly wrong is a graph
 * that constructs perfectly and never propagates: every cell spawned, every ref
 * resolvable, `applyBatch` and `quiesce` returning cleanly, and not one delta reaching
 * the collector. Nothing throws. A suite that asserted "it builds" would be green.
 *
 * So every propagation assertion here is paired against the [Wiring.UNLINKED] control —
 * the same subjects, the same drive, the same batches, links omitted. `the unlinked
 * control observes nothing` pins that graph at zero arrivals and zero membership; the
 * per-subject tests demand non-zero arrivals *and* an exact membership that matches
 * [Subject.referenceLive], an oracle written from the operators' definitions rather than
 * from anything the graph computes. The pair is what makes "it propagates" a checked
 * claim instead of an assumption: if links stopped being established, the per-subject
 * tests would land on exactly the numbers the control asserts.
 */
class GraphsTest {

    /** Order-100 batches, as the item specifies: 100 adds, then the 100 removes covering them. */
    private val batchSize = 100
    private val seed = 20260818L

    // ---------------------------------------------------------------------------
    // [BEN1-18] — the generators, before any graph is involved
    // ---------------------------------------------------------------------------

    @Test
    fun `insert batches are deterministic under a seed and disjoint within a stream`() {
        assertEquals(Deltas.insert(seed, batchSize), Deltas.insert(seed, batchSize))
        assertNotEquals(Deltas.insert(seed, batchSize).deltas, Deltas.insert(seed + 1, batchSize).deltas)

        val stream = DeltaStream(seed)
        val first = stream.insert(batchSize)
        val second = stream.insert(batchSize)
        assertEquals(batchSize, first.elements.size)
        assertEquals(emptySet<Int>(), first.elements intersect second.elements)
        val tags = { b: DeltaBatch -> b.deltas.flatMap { d -> d.adds.values.flatten() }.toSet() }
        assertEquals(batchSize, tags(first).size)
        assertEquals(emptySet<Timestamp>(), tags(first) intersect tags(second))
    }

    @Test
    fun `a retract batch covers exactly the add-tags of the inserts it retracts`() {
        val inserts = Deltas.insert(seed, batchSize)
        val retracts = Deltas.retract(inserts)

        assertEquals(Direction.RETRACT, retracts.direction)
        assertEquals(inserts.size, retracts.size)
        inserts.deltas.zip(retracts.deltas).forEach { (insert, retract) ->
            assertEquals(insert.adds, retract.dels)
            assertEquals(emptyMap<Int, Set<Timestamp>>(), retract.adds)
        }

        // The tag algebra directly, with no cell in the way: adds then the covering dels
        // leave nothing live.
        val view = SetView<Int>()
        inserts.deltas.forEach { view.apply(it) }
        assertEquals(batchSize, view.size)
        retracts.deltas.forEach { view.apply(it) }
        assertEquals(0, view.size)
    }

    @Test
    fun `retracting a retraction is refused rather than silently producing a no-op batch`() {
        val retracts = Deltas.retract(Deltas.insert(seed, 4))
        assertThrows(IllegalArgumentException::class.java) { Deltas.retract(retracts) }
    }

    // ---------------------------------------------------------------------------
    // [BEN1-17] — every subject propagates, under SIM
    // ---------------------------------------------------------------------------

    @Test
    fun `every set-shaped subject propagates inserts and retracts to the collector under SIM`() {
        Subject.setShaped().forEach { subject ->
            Graphs.build(subject, Drive.SIM).use { graph ->
                assertEquals(subject.sources, graph.sourceCount, "$subject source arity")

                val inserts = Deltas.insert(seed, batchSize)
                graph.applyAndQuiesce(inserts)

                assertTrue(graph.arrivals > 0, "$subject: nothing reached the collector")
                assertEquals(
                    subject.referenceLive(inserts.elements),
                    graph.live,
                    "$subject: observed membership after inserts",
                )

                val arrivalsAfterInserts = graph.arrivals
                graph.applyAndQuiesce(Deltas.retract(inserts))

                assertTrue(
                    graph.arrivals > arrivalsAfterInserts,
                    "$subject: the retract batch produced no downstream deltas at all",
                )
                assertEquals(0, graph.live, "$subject: observed membership after retracts")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // [BEN1-17] — the same graph under the live scheduler, settled by the drain fence
    // ---------------------------------------------------------------------------

    @Test
    fun `UnionSetCell propagates under REAL drive, settled by the awaitDrained fence`() {
        Graphs.build(Subject.UNION, Drive.REAL).use { graph ->
            val inserts = Deltas.insert(seed, batchSize)
            graph.applyAndQuiesce(inserts)

            assertTrue(graph.arrivals > 0, "UNION/REAL: nothing reached the collector")
            assertEquals(Subject.UNION.referenceLive(inserts.elements), graph.live)

            graph.applyAndQuiesce(Deltas.retract(inserts))
            assertEquals(0, graph.live)
        }
    }

    // ---------------------------------------------------------------------------
    // The control that makes all of the above non-vacuous
    // ---------------------------------------------------------------------------

    @Test
    fun `the unlinked control constructs, applies and quiesces while observing nothing`() {
        Subject.setShaped().forEach { subject ->
            Graphs.build(subject, Drive.SIM, Wiring.UNLINKED).use { graph ->
                // The failure mode being pinned: none of this throws. An unlinked graph
                // is indistinguishable from a working one except at the collector.
                graph.applyAndQuiesce(Deltas.insert(seed, batchSize))

                assertEquals(0L, graph.arrivals, "$subject unlinked: a delta arrived without a link")
                assertEquals(0, graph.live, "$subject unlinked: membership without a link")
            }
        }
    }
}
