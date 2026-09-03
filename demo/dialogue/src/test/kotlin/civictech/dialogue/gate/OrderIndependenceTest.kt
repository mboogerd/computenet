package civictech.dialogue.gate

import civictech.agora.AgoraService
import civictech.agora.cell.credenceOf
import civictech.cell.CellRef
import civictech.cell.data.SetOps
import civictech.cell.data.delta.MapDelta
import civictech.cell.link.LinkResult
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.dialogue.ClaimKey
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.RelationKey
import civictech.dialogue.Utterance
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.apply.GraphApplier
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.mint.ClaimProvenanceEntry
import civictech.dialogue.mint.ProvenanceIndex
import civictech.dialogue.mint.RelationProvenanceEntry
import civictech.dialogue.mint.StanceAggregate
import civictech.testkit.SimWorld
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BS-09 — **order independence** (epic computenet-2aw §4 BS-09, §3.2
 * [AGO1-EXTR-03], §3.1 [AGO1-SRC-03], §3.8's G-19 caveat; task
 * computenet-2aw.6.2).
 *
 * The property: given a fixed extraction result per segment, the canonical
 * claim set, canonical relation set, projected stance map and both provenance
 * indices are a function of the **admitted utterance set alone** — not of the
 * order the utterances arrived in, and not of the scheduler interleaving. So
 * the same live set is loaded [SHUFFLES] times per seed, each shuffle in a
 * fresh [SimWorld] on its own scheduler seed, and every run's [Fingerprint] is
 * compared against the first run's.
 *
 * ### Why this is a SET load and not a replay ([AGO1-SRC-03], 2aw.F6-D6)
 *
 * `TranscriptSource.offer` rejects a turn that is not greater than the last
 * admitted one ([AGO1-SRC-04], `OutOfOrderTurnException`), so a shuffled
 * admission order cannot go through the transcript source at all. It goes
 * through `DialoguePipeline.utteranceOps(...).add` directly — which is
 * precisely the set-load relaxation [AGO1-SRC-03] carves out: the pipeline
 * downstream of ingress is a set-valued dataflow and does not care how the set
 * was filled. What this test therefore pins is the *dataflow's* order
 * independence, deliberately with the paced-ingress guard out of the way.
 *
 * The set loaded is the **final live set** of a [TranscriptGenerator] program
 * — the transcript minus everything the program retracts — not the program's
 * admit/retract churn. BS-10 ([IncrementalEqualsBatchTest]) is the test that
 * drives the churn; this one asks whether the endpoint depends on the path.
 *
 * ### What is exactly equal and what is only equal within a bound (2aw.F6-D5)
 *
 * Structure is exact in every case: claim keys, relation keys, the projected
 * stance map (LWW by `(turn, utteranceId)` — **event** order, which no
 * admission order can perturb) and both provenance maps, compared by canonical
 * key and never by cell ref (2aw.F6-D2, §3.8 — ref-stable identity is
 * AGO3/KE1).
 *
 * Credences are bit-identical (`==`) on DAG seeds. On **cyclic** seeds they
 * are only equal within `25 * 1e-3`, and that is an honest reading of BS-09's
 * "identical" rather than a weakening: `AgoraService.createEdge` designates a
 * cycle head as `reaches(target, source)` *at creation time*, and
 * `GraphApplier.reconcile()` creates relations in `relationSink.current()`
 * iteration order — a `MapView` backed by a `LinkedHashMap`, i.e. **arrival**
 * order. Two admission orders of one canonical relation set can therefore
 * designate different heads, and a head's absorb threshold makes the settled
 * fixpoint path-dependent within that threshold.
 *
 * ### The head set is an OBSERVATION, not an assertion (2aw.F6-D5, G-19)
 *
 * Because of the mechanism above, a head-set difference between two orders on
 * a cyclic seed is a true fact about this runtime, not a defect of it. The
 * test therefore **records** such a difference (printed here and written up in
 * `doc/demo-findings.md` under the G-19 residual) instead of failing on it —
 * and, equally, instead of asserting it away by comparing something coarser.
 * What IS asserted about heads is the structural invariant that does hold in
 * every order: every designated head lies on a cycle of the final claim
 * digraph, and every 2-cycle in that digraph has at least one head.
 *
 * Measured on the seeds below — see `doc/demo-findings.md` F-17 for the
 * numbers and their limits.
 */
class OrderIndependenceTest {

    /**
     * Fixed before the first green run (2aw.F6-D3): two even seeds, which
     * [TranscriptGenerator] makes DAG-shaped, and two odd ones, which close a
     * cycle. Never swapped for friendlier ones.
     */
    private val DAG_SEEDS = listOf(2L, 6L)
    private val CYCLIC_SEEDS = listOf(3L, 7L)

    /** How many distinct admission orders each seed's live set is loaded in. */
    private val SHUFFLES = 6

    /** `AgoraService`'s default head threshold, restated so the bound below is legible. */
    private val q = 1e-3

    private fun toleranceFor(cyclic: Boolean) = if (cyclic) 25 * q else 0.0

    // ------------------------------------------------------------------
    // The fingerprint — everything BS-09 says is a function of the set
    // ------------------------------------------------------------------

    /**
     * Everything the property claims is determined by the admitted set. Keyed
     * by **canonical key** throughout (2aw.F6-D2); [credences] is keyed by the
     * key's string form so the map itself is ref-free, and [heads] is a set of
     * relation keys.
     */
    private data class Fingerprint(
        val claims: Set<ClaimKey>,
        val relations: Set<RelationKey>,
        val stances: Map<Pair<String, ClaimKey>, StanceAggregate>,
        val claimProvenance: Map<ClaimKey, Set<String>>,
        val relationProvenance: Map<RelationKey, Set<String>>,
        val heads: Set<RelationKey>,
        val credences: Map<String, Double>,
    )

    // ------------------------------------------------------------------
    // The rig — ApplierSemanticsTest's idiom plus the two provenance sinks
    // ------------------------------------------------------------------

    private class Rig(seed: Long, cassette: CassetteExtractor) {
        val world = SimWorld(seed = seed)
        private val built = DialoguePipeline.build(world.host, cassette)
        val service = AgoraService(world.host, world.registry)
        val bindings = BindingTable(journalDir = null)
        val applier = GraphApplier(world.host, built.refs, service, bindings)
        private val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(world.host, built.refs)

        /** Read-only observations of the F3 provenance folds; they never write to agora. */
        private val claimProvenanceSink =
            observe<ClaimKey, Set<ClaimProvenanceEntry>>("claim-provenance", built.refs.claimProvenance.ref)
        private val relationProvenanceSink =
            observe<RelationKey, Set<RelationProvenanceEntry>>("relation-provenance", built.refs.relationProvenance.ref)

        private fun <K, V> observe(name: String, source: CellRef): ObserveCell<MapDelta<K, V>, Map<K, V>> {
            val cell = ObserveCell(
                View.map<K, V>(),
                CellRef(UUID.nameUUIDFromBytes("order-independence:$name".toByteArray())),
            )
            val management = world.host.managementInlet.call
            management.spawn(cell)
            val result = management.connect(source, "outlet", cell.ref, "inlet")
            check(result !is LinkResult.Rejected) { "$name sink link rejected: $result" }
            return cell
        }

        fun admit(utterance: Utterance) = ops.add(utterance)

        fun claimProvenance(): Map<ClaimKey, Set<String>> =
            claimProvenanceSink.current().mapValues { (_, entries) -> ProvenanceIndex.claimProvenance(entries) }

        fun relationProvenance(): Map<RelationKey, Set<String>> =
            relationProvenanceSink.current().mapValues { (_, entries) -> ProvenanceIndex.relationProvenance(entries) }

        fun credenceOf(ref: CellRef): Double = service.hub.credenceOf(ref) ?: 0.5
    }

    /**
     * Load [live] in the given order into a fresh world, quiesce, reconcile
     * **once**, quiesce again, and fingerprint what settled.
     *
     * One reconcile is the point: the whole canonical set arrives at the
     * applier's sinks in one go, so the relation creation order inside
     * `reconcile()` — and therefore head designation — is exactly the arrival
     * order this shuffle produced.
     */
    private fun loadAndFingerprint(live: List<Utterance>, cassette: CassetteExtractor, worldSeed: Long): Fingerprint {
        val rig = Rig(worldSeed, cassette)
        live.forEach { rig.admit(it) }
        rig.world.runToIdle()
        val report = rig.applier.reconcile()
        rig.world.runToIdle()

        assertEquals(
            emptyList(),
            report.failures,
            "world seed $worldSeed: a rejected write would leave a key unbound and make the comparison vacuous",
        )

        val claims = rig.bindings.boundClaims()
        val relations = rig.bindings.boundRelations()
        val heads = relations.filter { rig.service.nodeInfo(BindingTable.refFor(it))?.head == true }.toSet()
        val credences = (
            claims.map { it.value to rig.credenceOf(BindingTable.refFor(it)) } +
                relations.map { it.value to rig.credenceOf(BindingTable.refFor(it)) }
            ).toMap()

        return Fingerprint(
            claims = claims,
            relations = relations,
            stances = rig.applier.observedStances(),
            claimProvenance = rig.claimProvenance(),
            relationProvenance = rig.relationProvenance(),
            heads = heads,
            credences = credences,
        )
    }

    // ------------------------------------------------------------------
    // BS-09
    // ------------------------------------------------------------------

    @Test
    fun `BS-09 AGO1-EXTR-03 - the same admitted set in N shuffled orders settles to the same canonical graph`() {
        val headDifferences = mutableListOf<String>()
        var worstCyclicGap = 0.0
        var worstDagGap = 0.0

        (DAG_SEEDS + CYCLIC_SEEDS).forEach { seed ->
            val cyclic = seed in CYCLIC_SEEDS
            val tolerance = toleranceFor(cyclic)
            val generated = TranscriptGenerator.generate(seed, cyclic)
            if (cyclic) {
                assertTrue(
                    generated.closedACycle,
                    "seed $seed was chosen as a cyclic seed but closed no cycle — the cyclic half would be vacuous",
                )
            }

            val live = generated.live
            // The final live set legitimately contains an ORPHANED relation:
            // TranscriptGenerator retracts the reserved claim's introduction at
            // the end of every program while the anchor relation onto it
            // survives, so one relation utterance mints no canonical relation.
            // That is by design (it keeps the pending rule load-bearing) and is
            // not a defect for this test to assert away.
            assertTrue(live.any { it.id == generated.anchorRelationId }, "seed $seed: the orphaned anchor must be live")

            val fingerprints = (0 until SHUFFLES).map { i ->
                val order = live.shuffled(Random(seed * 1000 + i))
                loadAndFingerprint(order, generated.cassette, worldSeed = seed * 1000 + i)
            }

            val first = fingerprints.first()
            assertTrue(first.claims.isNotEmpty(), "seed $seed: the load must have produced claims to compare")
            assertTrue(first.relations.isNotEmpty(), "seed $seed: the load must have produced relations to compare")

            // ----------------------------------------------------------
            // (a) structure: exact, in every order, DAG or cyclic
            // ----------------------------------------------------------
            fingerprints.drop(1).forEachIndexed { index, other ->
                val order = index + 1
                assertEquals(first.claims, other.claims, "seed $seed order $order: canonical claim keys")
                assertEquals(first.relations, other.relations, "seed $seed order $order: canonical relation keys")
                assertEquals(first.stances, other.stances, "seed $seed order $order: projected stances (LWW by event order)")
                assertEquals(
                    first.claimProvenance,
                    other.claimProvenance,
                    "seed $seed order $order: claim provenance [AGO1-PROV-01]",
                )
                assertEquals(
                    first.relationProvenance,
                    other.relationProvenance,
                    "seed $seed order $order: relation provenance [AGO1-PROV-01]",
                )
            }

            // ----------------------------------------------------------
            // (b) credences: bit-identical on DAG seeds, within the head
            //     threshold on cyclic ones (2aw.F6-D5)
            // ----------------------------------------------------------
            fingerprints.drop(1).forEachIndexed { index, other ->
                val order = index + 1
                assertEquals(first.credences.keys, other.credences.keys, "seed $seed order $order: credence key set")
                first.credences.forEach { (key, expected) ->
                    val actual = other.credences.getValue(key)
                    val gap = abs(actual - expected)
                    if (cyclic) worstCyclicGap = maxOf(worstCyclicGap, gap) else worstDagGap = maxOf(worstDagGap, gap)
                    if (cyclic) {
                        assertTrue(
                            gap <= tolerance,
                            "seed $seed order $order key $key: $actual vs $expected beyond the cyclic bound $tolerance " +
                                "(2aw.F6-D5)",
                        )
                    } else {
                        assertTrue(
                            actual == expected,
                            "seed $seed order $order key $key: DAG credences must be bit-identical ($actual vs $expected)",
                        )
                    }
                }
            }

            // ----------------------------------------------------------
            // (c) heads: the structural invariant is asserted; a DIFFERENCE
            //     between orders is recorded, never failed (2aw.F6-D5, G-19)
            // ----------------------------------------------------------
            val digraph = claimDigraph(generated, live)
            fingerprints.forEachIndexed { order, fingerprint ->
                fingerprint.heads.forEach { head ->
                    val (source, target) = digraph.getValue(head)
                    assertTrue(
                        reaches(digraph.values.toList(), from = target, to = source),
                        "seed $seed order $order: head $head does not lie on a cycle of the final claim digraph — " +
                            "createEdge designates a head only when reaches(target, source) held at creation",
                    )
                }
                twoCycles(digraph).forEach { (a, b) ->
                    assertTrue(
                        a in fingerprint.heads || b in fingerprint.heads,
                        "seed $seed order $order: a 2-cycle with no designated head",
                    )
                }
                if (cyclic && twoCycles(digraph).isNotEmpty()) {
                    assertTrue(fingerprint.heads.isNotEmpty(), "seed $seed order $order: a cyclic graph with no head")
                }
            }

            val headSets = fingerprints.map { it.heads }
            headSets.drop(1).forEachIndexed { index, other ->
                if (other != headSets.first()) {
                    headDifferences += "seed $seed (cyclic=$cyclic): order 0 heads ${headSets.first()} vs " +
                        "order ${index + 1} heads $other"
                }
            }
        }

        // The observation, not an assertion. doc/demo-findings.md F-17 carries
        // whichever of the two outcomes this run produced, and why the negative
        // one is weaker than it looks.
        println(
            if (headDifferences.isEmpty()) {
                "BS-09 head designation: IDENTICAL across all $SHUFFLES orders on every seed " +
                    "(${DAG_SEEDS + CYCLIC_SEEDS}) — see doc/demo-findings.md F-17 for why this is a weak negative"
            } else {
                "BS-09 head designation DIFFERED between orders (2aw.F6-D5, G-19):\n" +
                    headDifferences.joinToString("\n")
            },
        )
        println(
            "BS-09 worst cross-order credence gap: DAG seeds $worstDagGap (must be 0.0), " +
                "cyclic seeds $worstCyclicGap (tol ${toleranceFor(true)})",
        )
    }

    // ------------------------------------------------------------------
    // The claim digraph of the final live set, derived from the independent
    // reference fold rather than from the pipeline under test
    // ------------------------------------------------------------------

    /** relation key -> (source claim ref, target claim ref) for the final live set. */
    private fun claimDigraph(
        generated: TranscriptGenerator.Generated,
        live: List<Utterance>,
    ): Map<RelationKey, Pair<CellRef, CellRef>> {
        val folded = DialogueBatchReference.fold(generated.cassette, live)
        return folded.relationKeys.associateWith { key ->
            val spec = folded.nodes.getValue(BindingTable.refFor(key))
            spec.source!! to spec.target!!
        }
    }

    /** The unordered pairs of relation keys that form a 2-cycle between the same two claims. */
    private fun twoCycles(digraph: Map<RelationKey, Pair<CellRef, CellRef>>): List<Pair<RelationKey, RelationKey>> {
        val entries = digraph.entries.toList()
        val pairs = mutableListOf<Pair<RelationKey, RelationKey>>()
        entries.forEachIndexed { i, a ->
            entries.drop(i + 1).forEach { b ->
                if (a.value.first == b.value.second && a.value.second == b.value.first) pairs += a.key to b.key
            }
        }
        return pairs
    }

    /** Whether [to] is reachable from [from] over the claim-level edges. */
    private fun reaches(edges: List<Pair<CellRef, CellRef>>, from: CellRef, to: CellRef): Boolean {
        if (from == to) return true
        val seen = mutableSetOf(from)
        val stack = ArrayDeque(listOf(from))
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            edges.filter { it.first == n }.forEach { (_, next) ->
                if (next == to) return true
                if (seen.add(next)) stack.add(next)
            }
        }
        return false
    }
}
