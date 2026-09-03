package civictech.dialogue.apply

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.CellRef
import civictech.cell.data.SetOps
import civictech.cell.data.delta.MapDelta
import civictech.cell.link.LinkResult
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.RelationKey
import civictech.dialogue.Segment
import civictech.dialogue.Utterance
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.segmentContentHash
import civictech.agora.cell.credenceOf
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.RelationProvenanceEntry
import civictech.dialogue.mint.claimKey
import civictech.testkit.SimWorld
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.StringReader
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **argumentation semantics** of [GraphApplier]'s writes — epic
 * computenet-2aw §4 scenarios BS-03 and BS-06, requirements
 * [AGO1-APPLY-03] (relations go through `AgoraService.createEdge`, so DF-QuAD
 * influence and cycle-head designation run unchanged), [AGO1-APPLY-05] and
 * [AGO1-REL-02]'s applier half (a retracted relation's influence leaves its
 * target). Task computenet-2aw.4.4.
 *
 * The sibling task's [GraphApplierTest] pins the *plumbing* — which node
 * exists under which ref, which op in which order, which failure against
 * which key. This file pins what those writes do to the **credences** agora
 * derives from them, which is the whole reason [AGO1-APPLY-03] insists the
 * applier go through `AgoraService` instead of spawning cells itself.
 *
 * ### Why the reference solver is copied rather than imported
 *
 * Every credence literal below is justified by [BatchReference.solve] over
 * the same topology, never by re-deriving `DfQuad.combine` inline: a literal
 * recomputed from the production formula proves only that the formula equals
 * itself. `civictech.agora.BatchReference` lives in `:demo:agora`'s **test**
 * source set, which is not on `:demo:dialogue`'s test classpath (see
 * `demo/dialogue/build.gradle.kts`: `implementation(project(":demo:agora"))`
 * puts only agora's *main* output there), and `:demo:agora` is read-only for
 * this task. So the solver is reproduced verbatim below, keeping agora's
 * `ClaimCell.REF_ORDER` fold order, and remains independent of the
 * incremental propagation path it checks.
 */
class ApplierSemanticsTest {

    // ------------------------------------------------------------------
    // The batch reference (verbatim copy — see the class doc)
    // ------------------------------------------------------------------

    /**
     * Gauss-Seidel fixpoint over the final graph, using the same semantics
     * functions and the same ref-sorted fold order as the cells. Copied from
     * `demo/agora/src/test/kotlin/civictech/agora/TestSupport.kt`; keep in
     * step with it if agora's solver changes.
     */
    private object BatchReference {
        data class NodeSpec(
            val stances: Map<String, Double> = emptyMap(),
            val polarity: Polarity? = null, // non-null for edges
            val source: CellRef? = null,
            val target: CellRef? = null,
        )

        fun solve(
            topology: Map<CellRef, NodeSpec>,
            semantics: GradualSemantics = DfQuad,
            tol: Double = 1e-13,
            maxSweeps: Int = 100_000,
        ): Map<CellRef, Double> {
            val order = topology.keys.sortedWith(civictech.agora.cell.ClaimCell.REF_ORDER)
            val cred = order.associateWith { 0.5 }.toMutableMap()
            val incoming = order.associateWith { n ->
                topology.entries
                    .filter { it.value.target == n }
                    .sortedWith(compareBy(civictech.agora.cell.ClaimCell.REF_ORDER) { it.key })
            }
            repeat(maxSweeps) {
                var maxDelta = 0.0
                order.forEach { n ->
                    val energies = incoming.getValue(n).map { (ref, spec) ->
                        val e = cred.getValue(ref) * cred.getValue(spec.source!!)
                        if (spec.polarity == Polarity.SUPPORT) e else -e
                    }
                    val attacks = energies.filter { it < 0 }.map { -it }
                    val supports = energies.filter { it > 0 }
                    val next =
                        semantics.combine(semantics.base(topology.getValue(n).stances.values), attacks, supports)
                    maxDelta = maxOf(maxDelta, kotlin.math.abs(next - cred.getValue(n)))
                    cred[n] = next
                }
                if (maxDelta < tol) return cred
            }
            error("batch reference did not converge within $maxSweeps sweeps")
        }
    }

    // ------------------------------------------------------------------
    // Fixture — epic §4's BS-03 cassette
    // ------------------------------------------------------------------

    private val catsPurr = "Cats purr."
    private val dogsBark = "Dogs bark."

    /** u3: "Dogs bark." --ATTACK--> "Cats purr.". */
    private val attackText = "No, that is wrong."

    /** u4: "Cats purr." --SUPPORT--> "Dogs bark.", closing a 2-cycle with u3's edge. */
    private val supportText = "But it also backs this."

    private val cassetteEntries: Map<String, List<ExtractedItem>> = mapOf(
        catsPurr to listOf(
            ExtractedClaim(text = catsPurr, speaker = "alice", utteranceId = "u1"),
            ExtractedStance(claimText = catsPurr, speaker = "alice", value = 0.9, utteranceId = "u1"),
        ),
        dogsBark to listOf(ExtractedClaim(text = dogsBark, speaker = "bob", utteranceId = "u2")),
        attackText to listOf(
            ExtractedRelation(sourceText = dogsBark, targetText = catsPurr, polarity = "ATTACK", utteranceId = "u3"),
        ),
        supportText to listOf(
            ExtractedRelation(sourceText = catsPurr, targetText = dogsBark, polarity = "SUPPORT", utteranceId = "u4"),
        ),
    ).mapKeys { (text, _) -> segmentContentHash(hashSegment(text)) }

    private fun hashSegment(text: String) =
        Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text)

    private fun cassette(): CassetteExtractor {
        val json = Json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
            cassetteEntries,
        )
        return CassetteExtractor.load(StringReader(json))
    }

    private fun utterance(id: String, turn: Int, speaker: String, text: String) =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    private val u1 = utterance("u1", 1, "alice", catsPurr)
    private val u2 = utterance("u2", 2, "bob", dogsBark)
    private val u3 = utterance("u3", 3, "bob", attackText)
    private val u4 = utterance("u4", 4, "alice", supportText)

    private val catsKey = claimKey(catsPurr)
    private val dogsKey = claimKey(dogsBark)

    private val attackKey: RelationKey = RelationMint.relationKey(dogsKey, catsKey, Polarity.ATTACK)
    private val supportKey: RelationKey = RelationMint.relationKey(catsKey, dogsKey, Polarity.SUPPORT)

    private val refA get() = BindingTable.refFor(catsKey)
    private val refB get() = BindingTable.refFor(dogsKey)
    private val refAttack get() = BindingTable.refFor(attackKey)
    private val refSupport get() = BindingTable.refFor(supportKey)

    private inner class Rig {
        val world = SimWorld(seed = 1L)
        private val built = DialoguePipeline.build(world.host, cassette(), namespace = "semantics-test")
        val refs: DialoguePipeline.Refs = built.refs
        val service = AgoraService(world.host, world.registry)
        val bindings = BindingTable(journalDir = null)
        val applier = GraphApplier(world.host, refs, service, bindings)
        val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(world.host, refs)

        /**
         * A test-owned observation of the F3 relation-provenance fold, so
         * BS-06's provenance half can be asserted here rather than split
         * across two tests. Read-only: it never writes to agora.
         */
        private val provenanceSink: ObserveCell<
            MapDelta<RelationKey, Set<RelationProvenanceEntry>>,
            Map<RelationKey, Set<RelationProvenanceEntry>>,
            > = run {
            val cell = ObserveCell(
                View.map<RelationKey, Set<RelationProvenanceEntry>>(),
                CellRef(UUID.nameUUIDFromBytes("semantics-test:provenance".toByteArray())),
            )
            val management = world.host.managementInlet.call
            management.spawn(cell)
            val result = management.connect(refs.relationProvenance.ref, "outlet", cell.ref, "inlet")
            check(result !is LinkResult.Rejected) { "provenance sink link rejected: $result" }
            cell
        }

        fun provenance(): Map<RelationKey, Set<RelationProvenanceEntry>> = provenanceSink.current()

        fun admit(vararg utterances: Utterance) {
            utterances.forEach { ops.add(it) }
            world.runToIdle()
        }

        fun retract(utterance: Utterance) {
            ops.remove(utterance)
            world.runToIdle()
        }

        /** Reconcile, then drain so the credence recomputation settles. */
        fun reconcile(): ReconcileReport {
            val report = applier.reconcile()
            world.runToIdle()
            return report
        }

        fun credenceOf(ref: CellRef): Double =
            assertNotNull(service.hub.credenceOf(ref), "no credence for $ref")
    }

    // ------------------------------------------------------------------
    // BS-03 — [AGO1-APPLY-03]: an applied ATTACK drops its target
    // ------------------------------------------------------------------

    /**
     * The structural half. Kept apart from the credence half below on
     * purpose: an assertion that fires before the criterion's own assertion
     * hides whether that assertion discriminates at all, so each half is
     * mutation-checkable on its own.
     */
    @Test
    fun `BS-03 AGO1-APPLY-03 - an applied ATTACK becomes an EDGE node with the canonical polarity and endpoints`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        val report = rig.reconcile()

        // This is what createEdge produces; a directly-spawned EdgeCell would
        // not be registered as a node at all.
        val info = assertNotNull(rig.service.nodeInfo(refAttack), "[AGO1-APPLY-03] the relation must be an agora node")
        assertEquals(AgoraService.Kind.EDGE, info.kind)
        assertEquals(Polarity.ATTACK, info.polarity)
        assertEquals(refB, info.source, "source is the canonical relation's source claim")
        assertEquals(refA, info.target, "target is the canonical relation's target claim")
        assertEquals(emptyList(), report.failures, "the edge write must not have been rejected")
    }

    /**
     * The credence half — BS-03's actual claim. Contains **only** credence
     * assertions, so a mutation that reddens it reddens the criterion itself
     * rather than a structural assertion standing in front of it.
     */
    @Test
    fun `BS-03 AGO1-APPLY-03 - the applied ATTACK drops its target to the batch reference value`() {
        val rig = Rig()
        rig.admit(u1, u2)
        rig.reconcile()

        // Pre-relation: A is exactly its projected base, B is the neutral base.
        val before = BatchReference.solve(
            mapOf(
                refA to BatchReference.NodeSpec(stances = mapOf("alice" to 0.9)),
                refB to BatchReference.NodeSpec(),
            ),
        )
        assertEquals(0.9, before.getValue(refA), 1e-12, "reference: A's pre-relation credence")
        assertEquals(0.9, rig.credenceOf(refA), 1e-9, "A carries alice's stance before any relation is applied")

        rig.admit(u3)
        rig.reconcile()

        // BS-03's credence claim, pinned from the reference solver over this
        // exact topology (a fresh edge has no stance, so it sits at 0.5 and
        // carries energy 0.5 * cred(B) = 0.25 into A).
        val after = BatchReference.solve(
            mapOf(
                refA to BatchReference.NodeSpec(stances = mapOf("alice" to 0.9)),
                refB to BatchReference.NodeSpec(),
                refAttack to BatchReference.NodeSpec(polarity = Polarity.ATTACK, source = refB, target = refA),
            ),
        )
        val expectedA = after.getValue(refA)
        assertTrue(
            expectedA < 0.9,
            "reference sanity: an ATTACK must lower the target (solver gave $expectedA)",
        )
        val actualA = rig.credenceOf(refA)
        assertTrue(actualA < 0.9, "BS-03: the applied ATTACK must strictly lower A's credence (was $actualA)")
        assertEquals(
            expectedA,
            actualA,
            1e-9,
            "BS-03 [AGO1-APPLY-03]: A must equal the batch reference for the applied topology",
        )
        // The solver's value, recorded so a future reader can see what the
        // literal in the epic (0.675) actually resolves to: 0.9 - 0.9*(0.5*0.5).
        assertEquals(0.675, expectedA, 1e-9, "the reference value the epic's BS-03 predicts")

        assertEquals(
            after.getValue(refB),
            rig.credenceOf(refB),
            1e-9,
            "B, the attack's SOURCE, is unaffected by its own outgoing edge",
        )
    }

    // ------------------------------------------------------------------
    // Cycle-head designation still runs (the reason [AGO1-APPLY-03] insists
    // on createEdge)
    // ------------------------------------------------------------------

    @Test
    fun `a second relation closing a 2-cycle is designated a cycle head and the host still quiesces`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        rig.reconcile()
        assertNotNull(rig.service.nodeInfo(refAttack))

        rig.admit(u4)
        val report = rig.reconcile()
        assertEquals(emptyList(), report.failures)

        val first = assertNotNull(rig.service.nodeInfo(refAttack))
        val second = assertNotNull(rig.service.nodeInfo(refSupport), "the cycle-closing relation must exist")
        assertEquals(Polarity.SUPPORT, second.polarity)

        assertEquals(
            false,
            first.head,
            "the edge created FIRST closed no cycle: reaches(A -> B) was false when it was created",
        )
        assertEquals(
            true,
            second.head,
            "the edge created SECOND closes the 2-cycle and must be designated the head — this is what " +
                "createEdge does and what spawning an EdgeCell directly would lose [AGO1-APPLY-03]",
        )
        assertEquals(
            1,
            rig.service.graph().count { it.info.kind == AgoraService.Kind.EDGE && it.info.head },
            "exactly one of the two edges is a head",
        )

        // Quiescence within the step budget is asserted by runToIdle's own
        // budget inside reconcile(); reaching here at all is the assertion.
        assertEquals(2, rig.service.graph().count { it.info.kind == AgoraService.Kind.EDGE })
    }

    // ------------------------------------------------------------------
    // BS-06 — [AGO1-APPLY-05] / [AGO1-REL-02] applier half
    // ------------------------------------------------------------------

    @Test
    fun `BS-06 AGO1-REL-02 - retracting the relation's utterance removes the edge and returns the target to its pre-relation credence`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        rig.reconcile()

        val attacked = rig.credenceOf(refA)
        assertTrue(attacked < 0.9, "precondition: the ATTACK is actually influencing A (was $attacked)")
        assertTrue(rig.provenance().containsKey(attackKey), "precondition: the relation has provenance")

        rig.retract(u3)
        val report = rig.reconcile()

        // The criterion's own assertion goes FIRST: a structural assertion in
        // front of it would catch every mutation and leave this one unproven.
        // The influence left the target: A is back at exactly its pre-relation
        // value, which is the reference for the topology with the edge gone.
        val expected = BatchReference.solve(
            mapOf(
                refA to BatchReference.NodeSpec(stances = mapOf("alice" to 0.9)),
                refB to BatchReference.NodeSpec(),
            ),
        )
        assertEquals(
            expected.getValue(refA),
            rig.credenceOf(refA),
            1e-9,
            "[AGO1-REL-02] the retracted relation's influence must leave the target entirely",
        )
        assertEquals(0.9, rig.credenceOf(refA), 1e-9, "A is back to alice's stance alone")

        assertNull(rig.service.nodeInfo(refAttack), "BS-06: the edge node must be gone")
        assertTrue(!rig.bindings.isBound(attackKey), "BS-06: the relation key must be unbound")
        assertEquals(emptyList(), report.failures, "removing a live edge must not be rejected")

        // Both claims survive the relation's retraction.
        assertNotNull(rig.service.nodeInfo(refA), "A survives")
        assertNotNull(rig.service.nodeInfo(refB), "B survives")
        assertTrue(rig.bindings.isBound(catsKey) && rig.bindings.isBound(dogsKey), "both claims stay bound")

        // The F3 half, asserted here so BS-06 is one scenario in one test.
        assertTrue(
            !rig.provenance().containsKey(attackKey),
            "BS-06: the relation's provenance entry must be gone",
        )
    }

    // ------------------------------------------------------------------
    // [AGO1-APPLY-05] claim half — the applier removes the edge itself, so
    // agora's cascade has nothing to do and no write is rejected
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-APPLY-05 - retracting the target claim's utterance removes claim and edge with no rejected write, leaving the other claim neutral`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        rig.reconcile()

        rig.retract(u1)
        val report = rig.reconcile()

        assertNull(rig.service.nodeInfo(refA), "the retracted claim's node must be gone")
        assertNull(rig.service.nodeInfo(refAttack), "the relation that pointed at it must be gone too")
        assertTrue(
            !rig.bindings.isBound(catsKey) && !rig.bindings.isBound(attackKey),
            "both keys must be unbound",
        )
        assertEquals(
            emptyList(),
            report.failures,
            "[AGO1-APPLY-05] the applier removes the edge BEFORE the claim, so agora's dangling-edge " +
                "cascade never takes an edge the applier then fails to remove",
        )
        assertEquals(emptyList(), rig.applier.accounting.failures, "no failure at all across the run")

        assertNotNull(rig.service.nodeInfo(refB), "the surviving claim is untouched")
        val expected = BatchReference.solve(mapOf(refB to BatchReference.NodeSpec()))
        assertEquals(
            expected.getValue(refB),
            rig.credenceOf(refB),
            1e-9,
            "B has no stance and no incoming influence: DF-QuAD's neutral base",
        )
        assertEquals(0.5, rig.credenceOf(refB), 1e-9, "the reference value for a bare claim")
        assertEquals(1, rig.service.graph().size, "only B is left")
    }
}
