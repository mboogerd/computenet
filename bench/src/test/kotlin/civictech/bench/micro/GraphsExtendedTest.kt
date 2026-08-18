package civictech.bench.micro

import civictech.bench.Drive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fast, untagged correctness tests for the join / grouped / combine micro-fixtures —
 * the extension half of `[BEN1-17]`'s builders and `[BEN1-18]`'s generators.
 *
 * **Untagged on purpose**, for the same reason `GraphsTest` is: `@Tag("bench")` is
 * excluded from the default test task unconditionally, so a tagged test disappears from
 * every default build and all six required checks while still looking green. These
 * execute no benchmark and run in well under a second.
 *
 * ## What makes these tests discriminating rather than decorative
 *
 * The failure this fixture family can hide is a graph that constructs perfectly and
 * never propagates — every cell spawned, every ref resolvable, `applyBatch` and
 * `quiesce` returning cleanly, nothing reaching the collector, nothing thrown. Four
 * things are asserted against that here, and each one would fail on a dead graph:
 *
 *  1. **The [Wiring.UNLINKED] control** (`the unlinked control observes nothing`) pins a
 *     link-less graph at zero arrivals and an empty readout. A broken linked graph lands
 *     on exactly those numbers, so the per-subject tests demanding non-zero arrivals are
 *     the discriminator, not decoration.
 *  2. **Exact membership against [Subject.referenceLive]**, an oracle stated from the
 *     operators' definitions rather than read back off the graph.
 *  3. **Exact per-group aggregates against [Subject.referenceAggregate] /
 *     [Subject.referenceTopK]** — a graph that grouped correctly but aggregated wrongly
 *     passes (2) and fails these.
 *  4. **A partial retraction** (`retracting one of two batches …`), which is the only
 *     assertion here that a *retraction-blind* graph fails: it inserts two batches,
 *     retracts the second, and demands the aggregates return to the first batch's
 *     values. Because the second batch's elements are strictly larger, `maxOf` and
 *     `topK` must actually reshuffle their `TreeMap` support — a graph that dropped
 *     retractions on the floor would still hold the second batch's maxima and fail,
 *     where a retract-to-empty assertion alone would not distinguish it from a graph
 *     that simply cleared.
 */
class GraphsExtendedTest {

    private val batchSize = 100
    private val seed = 20260818L
    private val groups = Subject.DEFAULT_GROUPS

    // -----------------------------------------------------------------------------
    // [BEN1-18] — the keyed and counter payload shapes, before any graph is involved
    // -----------------------------------------------------------------------------

    @Test
    fun `the map view of a batch puts every add and removes every del, netting empty`() {
        val inserts = Deltas.insert(seed, batchSize)
        val retracts = Deltas.retract(inserts)

        val live = LinkedHashMap<Int, Long>()
        inserts.deltas.map(Deltas::asMap).forEach { live.putAll(it.puts); it.removals.forEach(live::remove) }
        assertEquals(batchSize, live.size)
        assertEquals(inserts.elements.associateWith { it.toLong() }, live)

        retracts.deltas.map(Deltas::asMap).forEach { live.putAll(it.puts); it.removals.forEach(live::remove) }
        assertEquals(emptyMap<Int, Long>(), live)

        // Deterministic under the seed, like the batch it derives from.
        assertEquals(
            Deltas.insert(seed, batchSize).deltas.map(Deltas::asMap),
            Deltas.insert(seed, batchSize).deltas.map(Deltas::asMap),
        )
        assertNotEquals(
            Deltas.insert(seed, batchSize).deltas.map(Deltas::asMap),
            Deltas.insert(seed + 1, batchSize).deltas.map(Deltas::asMap),
        )
    }

    @Test
    fun `the counter view of a batch and its retraction sum to zero`() {
        val inserts = Deltas.insert(seed, batchSize)
        assertEquals(batchSize.toLong(), inserts.deltas.sumOf { Deltas.asCounter(it).amount })
        assertEquals(
            0L,
            (inserts.deltas + Deltas.retract(inserts).deltas).sumOf { Deltas.asCounter(it).amount },
        )
    }

    // -----------------------------------------------------------------------------
    // The family split — no declared subject escapes a propagation loop
    // -----------------------------------------------------------------------------

    @Test
    fun `every declared subject is covered by exactly one of the two family loops`() {
        assertEquals(Subject.all().toSet(), (Subject.setShaped() + Subject.extended()).toSet())
        assertEquals(Subject.all().size, Subject.setShaped().size + Subject.extended().size)
        assertTrue(Subject.extended().isNotEmpty())
    }

    // -----------------------------------------------------------------------------
    // [BEN1-17] — every extended subject propagates, under SIM
    // -----------------------------------------------------------------------------

    @Test
    fun `every extended subject propagates inserts to the collector under SIM`() {
        Subject.extended().forEach { subject ->
            Graphs.build(subject, Drive.SIM, groups = groups).use { graph ->
                assertEquals(subject.sources, graph.sourceCount, "$subject source arity")

                val inserts = Deltas.insert(seed, batchSize)
                graph.applyAndQuiesce(inserts)

                assertTrue(graph.arrivals > 0, "$subject: nothing reached the collector")
                assertEquals(
                    subject.referenceLive(inserts.elements, groups),
                    graph.live,
                    "$subject: observed membership after inserts",
                )
            }
        }
    }

    @Test
    fun `every extended subject returns to empty after the covering retraction`() {
        // No exclusions: every declared extended subject must retract to empty. The one
        // operator that could not — MergeableGroupByCell, which ignores `dels` on its
        // local inlet — is not a subject at all, and the named omission on `Subject`
        // says why. An exclusion list here would be the place that quietly grew.
        Subject.extended().forEach { subject ->
            Graphs.build(subject, Drive.SIM, groups = groups).use { graph ->
                val inserts = Deltas.insert(seed, batchSize)
                graph.applyAndQuiesce(inserts)
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

    // -----------------------------------------------------------------------------
    // [BEN1-17] — the aggregate values themselves, not just the live group count
    // -----------------------------------------------------------------------------

    @Test
    fun `scalar group-by aggregates match the aggregator definitions after inserts`() {
        listOf(
            Subject.GROUP_BY_COUNT,
            Subject.GROUP_BY_SUM,
            Subject.GROUP_BY_MIN,
            Subject.GROUP_BY_MAX,
        ).forEach { subject ->
            Graphs.build(subject, Drive.SIM, groups = groups).use { graph ->
                val inserts = Deltas.insert(seed, batchSize)
                graph.applyAndQuiesce(inserts)

                assertEquals(
                    subject.referenceAggregate(inserts.elements, groups),
                    (graph.collector as LongMapCollectorCell).values(),
                    "$subject: per-group aggregate after inserts",
                )
            }
        }
    }

    @Test
    fun `topK keeps the k largest per group, descending`() {
        Graphs.build(Subject.GROUP_BY_TOP_K, Drive.SIM, groups = groups).use { graph ->
            val inserts = Deltas.insert(seed, batchSize)
            graph.applyAndQuiesce(inserts)

            assertEquals(
                Subject.GROUP_BY_TOP_K.referenceTopK(inserts.elements, groups),
                (graph.collector as TopKCollectorCell).values(),
                "topK ranking after inserts",
            )
        }
    }

    // -----------------------------------------------------------------------------
    // The assertion a retraction-blind graph fails: the TreeMap support reshuffles
    // -----------------------------------------------------------------------------

    @Test
    fun `retracting one of two batches reshuffles the TreeMap-backed aggregates`() {
        listOf(
            Subject.GROUP_BY_COUNT,
            Subject.GROUP_BY_SUM,
            Subject.GROUP_BY_MIN,
            Subject.GROUP_BY_MAX,
        ).forEach { subject ->
            Graphs.build(subject, Drive.SIM, groups = groups).use { graph ->
                // One stream, so the two batches carry disjoint, strictly increasing
                // elements: every element of `second` outranks every element of `first`.
                val stream = DeltaStream(seed)
                val first = stream.insert(batchSize)
                val second = stream.insert(batchSize)

                graph.applyAndQuiesce(first)
                graph.applyAndQuiesce(second)
                val both = first.elements + second.elements
                assertEquals(
                    subject.referenceAggregate(both, groups),
                    (graph.collector as LongMapCollectorCell).values(),
                    "$subject: aggregate over both batches",
                )

                graph.applyAndQuiesce(Deltas.retract(second))

                // Every group survives (the first batch still holds it), so this is the
                // *reshuffle* path rather than group death: `Support.retract` decrements
                // the TreeMap multiset and `Extremum.value` re-reads the surviving end.
                assertEquals(
                    subject.referenceAggregate(first.elements, groups),
                    (graph.collector as LongMapCollectorCell).values(),
                    "$subject: aggregate after retracting the second batch",
                )
                assertEquals(groups, graph.live, "$subject: no group may die here")
            }
        }
    }

    @Test
    fun `topK re-admits an evicted duplicate when the leaders retract`() {
        Graphs.build(Subject.GROUP_BY_TOP_K, Drive.SIM, groups = groups).use { graph ->
            val stream = DeltaStream(seed)
            val first = stream.insert(batchSize)
            val second = stream.insert(batchSize)

            graph.applyAndQuiesce(first)
            graph.applyAndQuiesce(second)
            graph.applyAndQuiesce(Deltas.retract(second))

            // The whole point of keeping the full support: the values the second batch
            // pushed out of the top-k have to come back.
            assertEquals(
                Subject.GROUP_BY_TOP_K.referenceTopK(first.elements, groups),
                (graph.collector as TopKCollectorCell).values(),
                "topK after the leading batch retracts",
            )
        }
    }

    // -----------------------------------------------------------------------------
    // Group cardinality is a dial, not a hidden constant
    // -----------------------------------------------------------------------------

    @Test
    fun `the groups parameter controls accumulator state size`() {
        listOf(1, 4, 32).forEach { g ->
            Graphs.build(Subject.GROUP_BY_MIN, Drive.SIM, groups = g).use { graph ->
                assertEquals(g, graph.groups)
                val inserts = Deltas.insert(seed, batchSize)
                graph.applyAndQuiesce(inserts)

                assertEquals(g, graph.live, "groups=$g: live group count")
                assertEquals(
                    Subject.GROUP_BY_MIN.referenceAggregate(inserts.elements, g),
                    (graph.collector as LongMapCollectorCell).values(),
                    "groups=$g: per-group minima",
                )
            }
        }
    }

    // -----------------------------------------------------------------------------
    // The same graph under the live scheduler, settled by the drain fence
    // -----------------------------------------------------------------------------

    @Test
    fun `a grouped and a join subject propagate under REAL drive`() {
        listOf(Subject.GROUP_BY_MAX, Subject.JOIN_SET).forEach { subject ->
            Graphs.build(subject, Drive.REAL, groups = groups).use { graph ->
                val inserts = Deltas.insert(seed, batchSize)
                graph.applyAndQuiesce(inserts)

                assertTrue(graph.arrivals > 0, "$subject/REAL: nothing reached the collector")
                assertEquals(subject.referenceLive(inserts.elements, groups), graph.live)

                graph.applyAndQuiesce(Deltas.retract(inserts))
                assertEquals(0, graph.live, "$subject/REAL: membership after retracts")
            }
        }
    }

    // -----------------------------------------------------------------------------
    // The control that makes all of the above non-vacuous
    // -----------------------------------------------------------------------------

    @Test
    fun `the unlinked control constructs, applies and quiesces while observing nothing`() {
        Subject.extended().forEach { subject ->
            Graphs.build(subject, Drive.SIM, Wiring.UNLINKED, groups).use { graph ->
                graph.applyAndQuiesce(Deltas.insert(seed, batchSize))

                assertEquals(0L, graph.arrivals, "$subject unlinked: a delta arrived without a link")
                assertEquals(0, graph.live, "$subject unlinked: membership without a link")
            }
        }
    }
}
