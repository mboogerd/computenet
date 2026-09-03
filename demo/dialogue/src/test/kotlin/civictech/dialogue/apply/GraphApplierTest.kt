package civictech.dialogue.apply

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.agora.semantics.DfQuad
import civictech.cell.data.SetOps
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.dialogue.ClaimKey
import civictech.dialogue.DialogueRuntime
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
import civictech.dialogue.mint.ClaimAggregate
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import civictech.testkit.SimWorld
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GraphApplier] — pipeline stage 8, the sole writer into the agora graph
 * (epic computenet-2aw §3.4 [AGO1-APPLY-01]..[AGO1-APPLY-07], §4 BS-01, task
 * computenet-2aw.4.2).
 *
 * The extractor is a [CassetteExtractor] over an in-test cassette keyed by
 * segment content hash, the same fixture idiom
 * [civictech.dialogue.mint.RelationMintTest] uses: `RuleExtractor`'s relation
 * endpoints never canonicalize to a claim key it mints, so nothing would ever
 * resolve into a canonical relation and the applier would have no edges to
 * apply.
 */
class GraphApplierTest {

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private val catsPurr = "Cats purr."
    private val dogsBark = "Dogs bark."
    private val birdsSing = "Birds sing."
    private val fishSwim = "Fish swim."

    /** u3: "Dogs bark." --SUPPORT--> "Cats purr.". */
    private val supportText = "Therefore they are related."

    /** u6: "Fish swim." --ATTACK--> "Birds sing." — a second, independent relation. */
    private val secondRelationText = "And that follows too."

    /** u7: "Dogs bark." --ATTACK--> "Cats purr.", plus bob's stance on "Cats purr.". */
    private val attackText = "No it does not."

    private val cassetteEntries: Map<String, List<ExtractedItem>> = mapOf(
        // BS-01's utterance: ONE utterance yielding a claim AND a stance on it.
        catsPurr to listOf(
            ExtractedClaim(text = catsPurr, speaker = "alice", utteranceId = "u1"),
            ExtractedStance(claimText = catsPurr, speaker = "alice", value = 0.9, utteranceId = "u1"),
        ),
        dogsBark to listOf(ExtractedClaim(text = dogsBark, speaker = "bob", utteranceId = "u2")),
        supportText to listOf(
            ExtractedRelation(sourceText = dogsBark, targetText = catsPurr, polarity = "SUPPORT", utteranceId = "u3"),
        ),
        birdsSing to listOf(ExtractedClaim(text = birdsSing, speaker = "carol", utteranceId = "u4")),
        fishSwim to listOf(ExtractedClaim(text = fishSwim, speaker = "dave", utteranceId = "u5")),
        secondRelationText to listOf(
            ExtractedRelation(sourceText = fishSwim, targetText = birdsSing, polarity = "ATTACK", utteranceId = "u6"),
        ),
        attackText to listOf(
            ExtractedRelation(sourceText = dogsBark, targetText = catsPurr, polarity = "ATTACK", utteranceId = "u7"),
            ExtractedStance(claimText = catsPurr, speaker = "bob", value = 0.2, utteranceId = "u7"),
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
    private val u3 = utterance("u3", 3, "bob", supportText)
    private val u4 = utterance("u4", 4, "carol", birdsSing)
    private val u5 = utterance("u5", 5, "dave", fishSwim)
    private val u6 = utterance("u6", 6, "dave", secondRelationText)
    private val u7 = utterance("u7", 7, "bob", attackText)

    private val catsKey = claimKey(catsPurr)
    private val dogsKey = claimKey(dogsBark)
    private val birdsKey = claimKey(birdsSing)
    private val fishKey = claimKey(fishSwim)

    private val supportKey = RelationMint.relationKey(dogsKey, catsKey, Polarity.SUPPORT)
    private val secondKey = RelationMint.relationKey(fishKey, birdsKey, Polarity.ATTACK)
    private val attackKey = RelationMint.relationKey(dogsKey, catsKey, Polarity.ATTACK)

    private inner class Rig {
        val world = SimWorld(seed = 1L)
        private val built = DialoguePipeline.build(world.host, cassette(), namespace = "applier-test")
        val refs: DialoguePipeline.Refs = built.refs
        val service = AgoraService(world.host, world.registry)
        val bindings = BindingTable(journalDir = null)
        val applier = GraphApplier(world.host, refs, service, bindings)
        val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(world.host, refs)

        fun admit(vararg utterances: Utterance) {
            utterances.forEach { ops.add(it) }
            world.runToIdle()
        }

        fun retract(utterance: Utterance) {
            ops.remove(utterance)
            world.runToIdle()
        }

        /**
         * Reconcile, then drain so credence updates settle before assertions.
         *
         * [assertApply07] is deliberately NOT called here: an invariant helper
         * that runs before a test's own assertions catches every mutation
         * first and hides whether the criterion's own assertion discriminates
         * at all. Each test invokes it explicitly, last.
         */
        fun reconcile(): ReconcileReport {
            val report = applier.reconcile()
            world.runToIdle()
            return report
        }

        /**
         * [AGO1-APPLY-07], asserted after every reconcile: every canonical
         * item is bound or carries a failure record — there is no third
         * state.
         */
        fun assertApply07() {
            val claims = applier.observedClaims().keys
            val failedClaims = claims.filter { applier.accounting.failed(ApplyKind.CLAIM, it.value) }.toSet()
            assertEquals(
                claims,
                applier.boundClaims() + failedClaims,
                "[AGO1-APPLY-07] every canonical claim key is bound or has a failure record",
            )
            val relations = applier.observedRelations().keys
            val failedRelations =
                relations.filter { applier.accounting.failed(ApplyKind.RELATION, it.value) }.toSet()
            assertEquals(
                relations,
                applier.boundRelations() + failedRelations,
                "[AGO1-APPLY-07] every canonical relation key is bound or has a failure record",
            )
        }
    }

    // ------------------------------------------------------------------
    // BS-01 — [AGO1-APPLY-01]
    // ------------------------------------------------------------------

    @Test
    fun `BS-01 AGO1-APPLY-01 - one utterance yielding a claim and a stance produces exactly one claim node at its deterministic ref with the projected base credence`() {
        val rig = Rig()
        rig.admit(u1)

        assertTrue(rig.service.graph().isEmpty(), "nothing may reach agora before the explicit reconcile")

        val report = rig.reconcile()

        val graph = rig.service.graph()
        assertEquals(1, graph.size, "exactly one agora node [AGO1-APPLY-01]")
        val node = graph.single()
        assertEquals(AgoraService.Kind.CLAIM, node.info.kind)
        assertEquals(
            BindingTable.refFor(catsKey),
            node.ref,
            "the claim must live under the binding table's deterministic ref, not a random one",
        )
        assertEquals(catsPurr, node.info.text)
        assertEquals(
            DfQuad.base(listOf(0.9)),
            node.credence,
            1e-9,
            "BS-01: credence is DF-QuAD's base for the one projected stance",
        )
        assertEquals(1, report.structureOps)
        assertEquals(1, report.stanceWrites, "alice's stance must be applied")
        assertTrue(rig.bindings.isBound(catsKey))

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // [AGO1-APPLY-02] — idempotence
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-APPLY-02 - a second reconcile over an unchanged canonical set issues no structure op and creates nothing`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        val first = rig.reconcile()
        assertEquals(3, first.structureOps, "precondition: two claims and one relation created")

        val refsBefore = rig.service.graph().map { it.ref }
        val cumulativeBefore = rig.applier.accounting.structureOps

        val second = rig.reconcile()

        assertEquals(
            emptyList(),
            second.ops,
            "[AGO1-APPLY-02] a reconcile over an unchanged canonical set must issue ZERO structure ops",
        )
        assertEquals(0, second.structureOps)
        // ops alone does NOT pin idempotence: re-issuing createClaim under a
        // ref agora already holds is REJECTED ("Cell already spawned"), which
        // leaves ops empty and records a failure instead. Measured — removing
        // both idempotence guards leaves every ops-based assertion in this
        // test green.
        assertEquals(
            emptyList(),
            second.failures,
            "[AGO1-APPLY-02] a create the applier should never have issued shows up as a REJECTION, not as an op",
        )
        assertEquals(emptyList(), rig.applier.accounting.failures, "no write may have been attempted at all")
        assertEquals(0, second.stanceWrites, "an unchanged stance must not be re-issued")
        assertEquals(refsBefore, rig.service.graph().map { it.ref }, "the agora graph must be untouched")
        assertEquals(
            cumulativeBefore,
            rig.applier.accounting.structureOps,
            "the cumulative structure-op counter must not advance",
        )

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // [AGO1-APPLY-04] — pull at quiescence, never push mid-wave
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-APPLY-04 - nothing reaches agora at any step of the wave, only at the explicit reconcile`() {
        val rig = Rig()

        rig.ops.add(u1)
        rig.ops.add(u2)
        rig.ops.add(u3)

        var steps = 0
        while (rig.world.controller.step()) {
            assertTrue(
                rig.service.graph().isEmpty(),
                "[AGO1-APPLY-04] the applier must not write to agora mid-wave (step $steps)",
            )
            check(++steps < 200_000) { "no quiescence within 200000 steps" }
        }
        assertTrue(rig.service.graph().isEmpty(), "still nothing at quiescence: reconcile has not been called")
        assertTrue(steps > 0, "precondition: the admission actually produced work")

        val report = rig.reconcile()

        assertEquals(3, report.structureOps, "only the explicit reconcile writes")
        assertNotNull(rig.service.nodeInfo(BindingTable.refFor(catsKey)))
        assertNotNull(rig.service.nodeInfo(BindingTable.refFor(supportKey)))

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // [AGO1-APPLY-03] — the relation is created THROUGH AgoraService
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-APPLY-03 - a canonical relation becomes an EDGE node with the right polarity, endpoints and deterministic ref`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        rig.reconcile()

        val edgeRef = BindingTable.refFor(supportKey)
        assertEquals(edgeRef, rig.bindings.refOf(supportKey))
        val info = assertNotNull(rig.service.nodeInfo(edgeRef), "the relation must exist as an agora node")
        assertEquals(AgoraService.Kind.EDGE, info.kind)
        assertEquals(Polarity.SUPPORT, info.polarity)
        assertEquals(BindingTable.refFor(dogsKey), info.source)
        assertEquals(BindingTable.refFor(catsKey), info.target)

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // Fixed op order — relation removals, claim removals, claim creates,
    // relation creates
    // ------------------------------------------------------------------

    @Test
    fun `reconcile emits removals before creates and relations around claims, whatever order the canonical sets changed in`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        rig.reconcile()

        // One batch whose input order is nothing like the required emission
        // order: the retraction that produces the two REMOVALS is applied
        // FIRST, and the admissions that produce the three CREATES arrive
        // after it — yet a create must never precede a removal, and within
        // each phase the relation/claim order is fixed and opposite.
        rig.retract(u1)
        rig.admit(u4, u5, u6)

        val report = rig.reconcile()

        assertEquals(
            listOf(
                ApplyOp.OpKind.REMOVE_RELATION,
                ApplyOp.OpKind.REMOVE_CLAIM,
                ApplyOp.OpKind.CREATE_CLAIM,
                ApplyOp.OpKind.CREATE_CLAIM,
                ApplyOp.OpKind.CREATE_RELATION,
            ),
            report.ops.map { it.kind },
            "fixed order: relation removals, claim removals, claim creates, relation creates",
        )
        assertEquals(
            supportKey.value,
            report.ops.first().key,
            "the removed relation is the one whose endpoint claim died",
        )
        assertEquals(catsKey.value, report.ops[1].key)
        assertEquals(secondKey.value, report.ops.last().key)
        assertEquals(
            emptyList(),
            report.failures,
            "removing the edge before its endpoint claim means no write is ever rejected",
        )

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // [AGO1-APPLY-06] — per-key failure accounting and isolation
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-APPLY-06 - a rejected relation and stance are recorded against their keys, left unbound, and do not stop the other keys applying`() {
        val rig = Rig()
        rig.admit(u1, u2)
        rig.reconcile()
        assertTrue(rig.bindings.isBound(catsKey) && rig.bindings.isBound(dogsKey), "precondition: both claims bound")

        // The test is deliberately a SECOND writer: it removes "Cats purr."
        // behind the applier's back, so the applier's binding table and agora
        // diverge. The applier must not heal that; it must report it.
        rig.service.remove(BindingTable.refFor(catsKey))
        rig.world.runToIdle()

        // u7 yields ATTACK "Dogs bark." -> "Cats purr." and bob's stance on
        // "Cats purr." — both writes target the node that is now gone.
        rig.admit(u7)

        val report = rig.reconcile()

        val relationFailures = rig.applier.accounting.failures(ApplyKind.RELATION)
        assertEquals(1, relationFailures.size, "[AGO1-APPLY-06] exactly one RELATION failure")
        assertEquals(attackKey.value, relationFailures.single().key)
        val stanceFailures = rig.applier.accounting.failures(ApplyKind.STANCE)
        assertEquals(1, stanceFailures.size, "[AGO1-APPLY-06] exactly one STANCE failure")
        assertEquals("bob@${catsKey.value}", stanceFailures.single().key)

        assertTrue(!rig.bindings.isBound(attackKey), "a rejected relation must NOT be marked bound")
        assertEquals(0, report.stanceWrites, "the rejected stance must not count as a write")

        // Isolation: the untouched key kept working.
        assertTrue(rig.bindings.isBound(dogsKey), "\"Dogs bark.\" must stay bound")
        assertNotNull(
            rig.service.nodeInfo(BindingTable.refFor(dogsKey)),
            "\"Dogs bark.\" must still be present in the agora graph",
        )
        assertEquals(0, report.structureOps, "no structure op succeeded this reconcile")

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // Retraction plumbing — the edge goes before the claim
    // ------------------------------------------------------------------

    @Test
    fun `retracting the utterance behind a claim removes its relation first, then the claim, unbinding both`() {
        val rig = Rig()
        rig.admit(u1, u2, u3)
        rig.reconcile()

        rig.retract(u1)
        val report = rig.reconcile()

        assertEquals(
            listOf(ApplyOp.OpKind.REMOVE_RELATION, ApplyOp.OpKind.REMOVE_CLAIM),
            report.ops.map { it.kind },
            "the edge must be removed BEFORE its endpoint claim — otherwise agora's cascade takes it " +
                "and the applier issues only one op",
        )
        assertEquals(emptyList(), report.failures)
        assertNull(rig.service.nodeInfo(BindingTable.refFor(supportKey)))
        assertNull(rig.service.nodeInfo(BindingTable.refFor(catsKey)))
        assertTrue(!rig.bindings.isBound(catsKey) && !rig.bindings.isBound(supportKey), "both keys unbound")
        assertNotNull(rig.service.nodeInfo(BindingTable.refFor(dogsKey)), "the surviving claim is untouched")

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // Crash-window check 1 — adopt-if-present
    // ------------------------------------------------------------------

    @Test
    fun `a node already present under a key's deterministic ref is adopted, not re-created`() {
        val rig = Rig()
        rig.admit(u1)

        // The crash window: createClaim returned, the process died before
        // bind was recorded. On restart the node exists and the table does
        // not know about it.
        rig.service.createClaim(catsPurr, BindingTable.refFor(catsKey))
        rig.world.runToIdle()

        val report = rig.reconcile()

        assertEquals(
            emptyList(),
            report.ops,
            "an already-present node must be ADOPTED: no create may be issued for it",
        )
        assertEquals(0, rig.applier.accounting.structureOps)
        assertTrue(rig.bindings.isBound(catsKey), "adoption must record the binding")
        assertEquals(BindingTable.refFor(catsKey), rig.bindings.refOf(catsKey))
        assertEquals(1, rig.service.graph().size)

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // Crash-window check 2 — absent-is-removed
    // ------------------------------------------------------------------

    @Test
    fun `a removal whose node is already gone unbinds the key without calling remove`() {
        val rig = Rig()
        rig.admit(u1)
        rig.reconcile()
        assertTrue(rig.bindings.isBound(catsKey))

        // The mirror crash window: remove returned, the process died before
        // unbind was recorded.
        rig.service.remove(BindingTable.refFor(catsKey))
        rig.world.runToIdle()

        rig.retract(u1)
        val report = rig.reconcile()

        assertTrue(
            !rig.bindings.isBound(catsKey),
            "an absent key must be UNBOUND even though no remove was issued",
        )
        assertEquals(
            emptyList(),
            report.ops,
            "no structure op may be issued for a node agora no longer has",
        )
        assertEquals(
            emptyList(),
            report.failures,
            "an already-absent node is not a failure — calling remove on it would be",
        )

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // The applier never subscribes: no write path reacts to the delta stream
    // ------------------------------------------------------------------

    @Test
    fun `the applier registers no listener that writes to agora`() {
        val source = java.io.File("src/main/kotlin/civictech/dialogue/apply/GraphApplier.kt")
        assertTrue(source.exists(), "fixture precondition: GraphApplier.kt is readable from the module dir")
        val code = source.readLines().filterNot { it.trimStart().startsWith("*") }
        assertEquals(
            emptyList(),
            code.filter { it.contains("onChange") },
            "[AGO1-APPLY-04] / computenet-23bf: the write path must not subscribe to the delta stream",
        )
    }

    // ------------------------------------------------------------------
    // Unbound-key sanity: the relation-create guard, kept honest
    // ------------------------------------------------------------------

    @Test
    fun `a relation key whose canonical set is empty binds nothing`() {
        val rig = Rig()
        rig.admit(u3) // the relation alone: neither endpoint is minted
        val report = rig.reconcile()

        assertEquals(emptyList(), report.ops)
        assertEquals(emptySet<ClaimKey>(), rig.bindings.boundClaims())
        assertEquals(emptySet<RelationKey>(), rig.bindings.boundRelations())
        assertTrue(rig.service.graph().isEmpty())

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // [AGO1-APPLY-07] relation half — unbound endpoint at reconcile time
    // (computenet-8fze, residual from computenet-2aw.4.2 F4 T2)
    // ------------------------------------------------------------------

    /**
     * Reaches `reconcile()`'s relation-create `source == null || target ==
     * null` branch: a canonical relation whose endpoint claim key is minted
     * (so the relation itself is canonical, per F3's "PENDING until both
     * endpoint keys are minted") but NOT bound in the [BindingTable] at
     * reconcile time, because that claim's own create attempt fails in the
     * SAME reconcile call.
     *
     * The failure is forced by spawning a conflicting cell directly on the
     * host under `catsKey`'s deterministic ref, bypassing [AgoraService] so
     * its own `nodeInfo` (the applier's adopt-if-present check) does not see
     * it: the applier still attempts a real `createClaim`, which collides
     * with the pre-spawned cell and is rejected. Claim creates run before
     * relation creates in the fixed op order, so by the time the relation
     * leg runs, `bindings.refOf(catsKey)` is null — not because the endpoint
     * was never attempted, but because its own attempt just failed.
     *
     * Per the bead's note, an ops-only assertion is vacuous here (a rejected
     * write also leaves `ops` empty), so this asserts on
     * [ApplyAccounting] and on the agora graph state, never on the op list
     * alone. [Rig.assertApply07] is invoked last, per the bead's other note,
     * so it cannot pre-empt this test's own discriminating assertions.
     */
    @Test
    fun `AGO1-APPLY-07 relation half - a canonical relation whose endpoint claim fails to bind in the same reconcile records a RELATION ApplyFailure`() {
        val rig = Rig()
        rig.admit(u1, u2, u3) // catsKey, dogsKey both minted; supportKey canonical (both endpoints minted)

        // Force catsKey's own claim create to fail this reconcile: a cell
        // already occupies its deterministic ref, spawned OUTSIDE
        // AgoraService so `nodeInfo` — the adopt-if-present guard — does not
        // see it and the applier's real `createClaim` collides for real.
        val conflict = ObserveCell(View.map<ClaimKey, ClaimAggregate>(), BindingTable.refFor(catsKey))
        rig.world.host.managementInlet.call.spawn(conflict)

        val report = rig.reconcile()

        // Precondition: the endpoint claim really did fail to bind, not
        // succeed some other way.
        assertTrue(!rig.bindings.isBound(catsKey), "precondition: catsKey failed to bind this reconcile")
        val claimFailures = rig.applier.accounting.failures(ApplyKind.CLAIM)
        assertEquals(1, claimFailures.size, "precondition: exactly one CLAIM failure (catsKey)")
        assertEquals(catsKey.value, claimFailures.single().key)

        // The relation's own key must never be marked bound, and the write
        // must never have been attempted (assert on accounting/graph state,
        // NOT on `report.ops` alone: a rejected write also leaves ops empty).
        assertTrue(!rig.bindings.isBound(supportKey), "the relation must NOT be marked bound")
        val relationFailures = rig.applier.accounting.failures(ApplyKind.RELATION)
        assertEquals(
            1,
            relationFailures.size,
            "[AGO1-APPLY-07] the unbound-endpoint relation must be recorded, not silently skipped",
        )
        assertEquals(supportKey.value, relationFailures.single().key)
        assertTrue(
            relationFailures.single().reason.contains(catsKey.value),
            "the failure names the endpoint that is not bound: ${relationFailures.single().reason}",
        )
        assertNull(
            rig.service.nodeInfo(BindingTable.refFor(supportKey)),
            "no EDGE node may exist in the agora graph for an unbound-endpoint relation",
        )
        assertEquals(
            1,
            report.structureOps,
            "only dogsKey's unrelated claim create succeeds this reconcile; catsKey's create and the relation both fail",
        )

        rig.assertApply07()
    }

    // ------------------------------------------------------------------
    // computenet-oy26 — the sink ref seam
    // ------------------------------------------------------------------

    /**
     * [GraphApplier]'s three observation sinks must spawn at exactly
     * [DialogueRuntime.sinkRef], not at an independently re-literalized copy
     * of `dialogue:sink:$name` (computenet-oy26). `DialogueRuntime` uses that
     * same prefix, via `SINK_PREFIX`, to decide which refs are volatile
     * ([DialogueRuntime.isDurable]); a second literal that happened to agree
     * today could silently drift the moment `SINK_PREFIX` changed, making
     * these sinks durable and routing `MapDelta` payloads over a
     * non-`@Serializable` vocabulary through the journal.
     *
     * This does not merely assert two literals are `equals()` — it proves
     * [GraphApplier]'s actual `management.spawn` call targets exactly
     * [DialogueRuntime.sinkRef]`("claims")`: a cell is planted at that ref
     * *before* [GraphApplier] is constructed, so if the applier's internal
     * sink spawn disagreed by even one character it would spawn at a
     * *different*, unoccupied ref and this collision would never fire.
     */
    @Test
    fun `claims sink spawns at exactly DialogueRuntime's own sinkRef, not a re-literalized copy`() {
        val world = SimWorld(seed = 1L)
        val built = DialoguePipeline.build(world.host, cassette(), namespace = "applier-test")
        val service = AgoraService(world.host, world.registry)
        val bindings = BindingTable(journalDir = null)

        val conflict = ObserveCell(View.map<ClaimKey, ClaimAggregate>(), DialogueRuntime.sinkRef("claims"))
        world.host.managementInlet.call.spawn(conflict)

        val failure = assertFailsWith<IllegalArgumentException> {
            GraphApplier(world.host, built.refs, service, bindings)
        }
        assertTrue(
            failure.message?.contains(DialogueRuntime.sinkRef("claims").toString()) == true,
            "GraphApplier's claims sink must collide with a cell planted at " +
                "DialogueRuntime.sinkRef(\"claims\") — got: ${failure.message}",
        )
    }
}
