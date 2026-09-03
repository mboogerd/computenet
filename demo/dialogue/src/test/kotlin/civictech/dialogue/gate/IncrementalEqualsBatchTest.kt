package civictech.dialogue.gate

import civictech.agora.AgoraService
import civictech.agora.BatchReference
import civictech.agora.cell.credenceOf
import civictech.cell.CellRef
import civictech.cell.data.SetOps
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.Utterance
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.apply.GraphApplier
import civictech.dialogue.apply.ReconcileReport
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.mint.claimKey
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BS-10 — **incremental equals batch** (epic computenet-2aw §4 BS-10, §3.2
 * [AGO1-EXTR-03], §3.6 [AGO1-REPLAY-01]; task computenet-2aw.6.1).
 *
 * Over a fixed seed range, a seeded transcript ([TranscriptGenerator]) is
 * replayed *incrementally* into a live pipeline — admissions and retractions
 * interleaved with quiescence-scoped reconciles — and the credences that
 * settle are compared against [BatchReference.solve] over
 * [DialogueBatchReference]'s one-pass fold of the **final live set**. Exactly
 * (1e-9) on even seeds, which are DAG-shaped by construction; within
 * `25 * 1e-3` on odd seeds, which close a cycle, because
 * `AgoraService.createEdge` designates the cycle head at creation time and a
 * head's absorb threshold makes the settled value path-dependent within that
 * threshold (2aw.F6-D5, and the same bound `AgoraExitTest` states).
 *
 * Comparison is by **canonical key and credence, never by ref identity**
 * (2aw.F6-D2, §3.8): the bound key sets are compared to the reference's key
 * sets, and each key's credence is read at `BindingTable.refFor(key)`.
 * Ref-stable identity across pipelines is AGO3/KE1 and is not asserted here.
 *
 * ### The seed range and the divergence policy
 *
 * [SEEDS] is fixed at `0 until 40` and is **not** shrinkable after the fact,
 * and neither is the tolerance widenable (2aw.F6-D3). A seed whose
 * incremental credence exceeds the tolerance is KEPT: the sweep stays red and
 * the seed plus the observed gap is recorded in `doc/demo-findings.md` under
 * the G-19 residual. `TranscriptGenerator.UTTERANCE_COUNT` is the only cost
 * lever the task allows.
 *
 * ### The retraction-blind control
 *
 * A green differential test proves nothing if the reference cannot disagree.
 * So the same sweep solves a **second** reference from `live ∪ (retracted
 * utterances that carried a relation whose endpoints are still minted)` — a
 * fold that is blind to retraction — and asserts it diverged from the
 * incremental credences on at least one seed. That is `AgoraExitTest`'s exact
 * discipline and is this task's half of the feature's "retraction-blind
 * reference implementation that must FAIL BS-06's property".
 */
class IncrementalEqualsBatchTest {

    /** Fixed BEFORE the first green run (2aw.F6-D3). Never shrink this. */
    private val SEEDS = 0L until 40L

    /** `AgoraService`'s default head threshold, restated so the tolerance below is legible. */
    private val q = 1e-3

    private fun toleranceFor(cyclic: Boolean) = if (cyclic) 25 * q else 1e-9

    // ------------------------------------------------------------------
    // The rig — ApplierSemanticsTest's idiom, with a generated cassette
    // ------------------------------------------------------------------

    private class Rig(seed: Long, cassette: CassetteExtractor) {
        val world = SimWorld(seed = seed)
        private val built = DialoguePipeline.build(world.host, cassette)
        val service = AgoraService(world.host, world.registry)
        val bindings = BindingTable(journalDir = null)
        val applier = GraphApplier(world.host, built.refs, service, bindings)
        private val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(world.host, built.refs)

        fun admit(utterance: Utterance) = ops.add(utterance)

        fun retract(utterance: Utterance) = ops.remove(utterance)

        /** Drain, reconcile, drain — the applier reads a settled snapshot only. */
        fun settleAndReconcile(): ReconcileReport {
            world.runToIdle()
            val report = applier.reconcile()
            world.runToIdle()
            return report
        }

        fun credenceOf(ref: CellRef): Double = service.hub.credenceOf(ref) ?: 0.5
    }

    /**
     * Drive [generated]'s program into a fresh rig on [worldSeed], reconciling
     * after roughly every fifth step and always at the end. The reconcile
     * cadence is driven by the TRANSCRIPT seed, not the world seed, so two
     * rigs on different world seeds see the identical op sequence — which is
     * what [AGO1-REPLAY-01]'s two-fresh-pipelines half needs.
     */
    private fun replay(generated: TranscriptGenerator.Generated, transcriptSeed: Long, worldSeed: Long): Rig {
        val rig = Rig(worldSeed, generated.cassette)
        val cadence = Random(transcriptSeed)
        generated.program.forEach { step ->
            when (step) {
                is TranscriptGenerator.Step.Admit -> rig.admit(step.utterance)
                is TranscriptGenerator.Step.Retract -> rig.retract(step.utterance)
            }
            if (cadence.nextInt(5) == 0) rig.settleAndReconcile()
        }
        return rig
    }

    // ------------------------------------------------------------------
    // BS-10
    // ------------------------------------------------------------------

    @Test
    fun `BS-10 AGO1-EXTR-03 - incrementally replayed transcripts equal the batch reference on every seed`() {
        var blindDivergenceSeen = false
        var blindDivergentSeeds = 0
        var blindControlsRun = 0

        forEachSeed(SEEDS) { seed ->
            val cyclic = seed % 2 == 1L
            val tolerance = toleranceFor(cyclic)
            val generated = TranscriptGenerator.generate(seed, cyclic)

            val rig = replay(generated, transcriptSeed = seed, worldSeed = seed)
            val report = rig.settleAndReconcile()

            // A rejected write would leave a key unbound and make every
            // credence comparison below vacuous for it.
            assertEquals(emptyList(), report.failures, "seed $seed: the final reconcile must have no failures")

            val live = generated.live
            val reference = DialogueBatchReference.fold(generated.cassette, live)
            val batch = BatchReference.solve(reference.nodes)

            // (a) the canonical key sets, by key — never by ref (2aw.F6-D2).
            assertEquals(
                reference.claimKeys,
                rig.bindings.boundClaims(),
                "seed $seed: bound claim keys must equal the reference's minted claim keys",
            )
            assertEquals(
                reference.relationKeys,
                rig.bindings.boundRelations(),
                "seed $seed: bound relation keys must equal the reference's resolvable relation keys",
            )

            // (b) BS-10's own assertion: every bound node's credence.
            val refs = rig.bindings.boundClaims().map { BindingTable.refFor(it) } +
                rig.bindings.boundRelations().map { BindingTable.refFor(it) }
            refs.forEach { ref ->
                val incremental = rig.credenceOf(ref)
                val expected = batch.getValue(ref)
                assertTrue(
                    abs(incremental - expected) <= tolerance,
                    "seed $seed (cyclic=$cyclic) node $ref: incremental $incremental vs batch $expected (tol $tolerance)",
                )
            }

            // (c) the retraction-blind control: re-admit every retracted
            //     RELATION utterance. A relation utterance mints no claim
            //     key, so the blind fold's claim set is identical to the live
            //     one and its own both-endpoints-minted filter drops any
            //     relation whose endpoints did not survive — which is exactly
            //     "the retracted relation-bearing utterances whose endpoints
            //     still exist", computed by the rule rather than by hand.
            val blindLive = live + generated.retracted
                .filter { generated.kindOf(it) == TranscriptGenerator.Kind.RELATION }
                .sortedBy { it.turn }
            if (blindLive.size > live.size) {
                val blind = DialogueBatchReference.fold(generated.cassette, blindLive)
                if (blind.relationKeys != reference.relationKeys) {
                    blindControlsRun++
                    val blindBatch = BatchReference.solve(blind.nodes)
                    val diverged = refs.any { ref ->
                        val expected = blindBatch[ref] ?: return@any false
                        abs(rig.credenceOf(ref) - expected) > tolerance
                    }
                    if (diverged) {
                        blindDivergenceSeen = true
                        blindDivergentSeeds++
                    }
                }
            }
        }

        assertTrue(
            blindDivergenceSeen,
            "retraction-blind reference never diverged — the gate has no teeth " +
                "(controls run on $blindControlsRun seeds, divergent on $blindDivergentSeeds)",
        )
        println(
            "BS-10 retraction-blind control: ran on $blindControlsRun of ${SEEDS.count()} seeds, " +
                "diverged on $blindDivergentSeeds",
        )
    }

    // ------------------------------------------------------------------
    // [AGO1-REPLAY-01] — the two-fresh-pipelines half (§3.8 limit: keys and
    // credences, never refs)
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-REPLAY-01 - the same program in two fresh pipelines on different world seeds agrees by key and credence`() {
        listOf(2L to false, 3L to true).forEach { (seed, cyclic) ->
            val generated = TranscriptGenerator.generate(seed, cyclic)
            val first = replay(generated, transcriptSeed = seed, worldSeed = 1_000L + seed)
            first.settleAndReconcile()
            val second = replay(generated, transcriptSeed = seed, worldSeed = 9_000L + seed)
            second.settleAndReconcile()

            assertEquals(first.bindings.boundClaims(), second.bindings.boundClaims(), "seed $seed: claim keys")
            assertEquals(first.bindings.boundRelations(), second.bindings.boundRelations(), "seed $seed: relation keys")

            val refs = first.bindings.boundClaims().map { BindingTable.refFor(it) } +
                first.bindings.boundRelations().map { BindingTable.refFor(it) }
            assertTrue(refs.isNotEmpty(), "seed $seed: the replay must have produced nodes to compare")
            refs.forEach { ref ->
                val a = first.credenceOf(ref)
                val b = second.credenceOf(ref)
                if (cyclic) {
                    assertTrue(
                        abs(a - b) <= toleranceFor(true),
                        "seed $seed node $ref: $a vs $b beyond the cyclic bound (2aw.F6-D5)",
                    )
                } else {
                    assertTrue(
                        a == b,
                        "seed $seed node $ref: DAG credences must be bit-identical across world seeds ($a vs $b)",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The generator's own contract — the sweep above is only as good as this
    // ------------------------------------------------------------------

    @Test
    fun `TranscriptGenerator is deterministic and produces the content BS-10 needs`() {
        var cyclicSeedsThatClosedACycle = 0
        SEEDS.forEach { seed ->
            val cyclic = seed % 2 == 1L
            val a = TranscriptGenerator.generate(seed, cyclic)
            val b = TranscriptGenerator.generate(seed, cyclic)
            assertEquals(a.transcript, b.transcript, "seed $seed: generation must be a pure function of the seed")
            assertEquals(a.program, b.program, "seed $seed: the drive program must be deterministic too")

            assertEquals(
                a.transcript.size,
                a.transcript.map { it.text }.toSet().size,
                "seed $seed: utterance texts must be unique — a cassette entry is keyed by content hash",
            )
            assertEquals(
                a.transcript.indices.map { it + 1 },
                a.transcript.map { it.turn },
                "seed $seed: turns ascend in generation order",
            )

            val relations = a.transcript.filter { a.kindOf(it) == TranscriptGenerator.Kind.RELATION }
            assertTrue(relations.isNotEmpty(), "seed $seed: a transcript with no relation cannot exercise BS-10")
            assertTrue(
                a.retracted.isNotEmpty(),
                "seed $seed: the program must retract something",
            )
            assertTrue(
                a.retracted.any { a.kindOf(it) == TranscriptGenerator.Kind.RELATION },
                "seed $seed: at least one retraction must remove a relation-bearing utterance",
            )
            // Sole contributor: re-admitting the retracted relation utterances
            // must actually change the relation key set, or the control has
            // nothing to diverge on.
            val live = a.live
            val baseline = DialogueBatchReference.fold(a.cassette, live)
            val blind = DialogueBatchReference.fold(
                a.cassette,
                live + a.retracted.filter { a.kindOf(it) == TranscriptGenerator.Kind.RELATION },
            )
            assertTrue(
                blind.relationKeys.size > baseline.relationKeys.size,
                "seed $seed: some retracted relation utterance must be the SOLE contributor of its relation key",
            )

            // The pending rule must be load-bearing on every seed: the
            // reserved claim's introduction is retracted, so its key is not
            // minted, while the anchor relation that points at it is still
            // live. Without this a `DialogueBatchReference` with the
            // both-endpoints-minted filter deleted would still be green.
            assertTrue(
                live.any { it.id == a.anchorRelationId },
                "seed $seed: the anchor relation utterance must survive to the end",
            )
            assertTrue(
                a.retracted.any { it.id == a.pristineIntroId },
                "seed $seed: the reserved claim's introduction must be retracted",
            )
            assertTrue(
                claimKey(TranscriptGenerator.claimText(TranscriptGenerator.PRISTINE_CLAIM)) !in baseline.claimKeys,
                "seed $seed: the reserved claim's key must be un-minted, orphaning the anchor relation",
            )

            // A cycle-closing relation is emitted ONLY when cyclic=true. The
            // converse is not guaranteed per seed — whether a random relation
            // happens to close a cycle is itself random — so the "cyclic
            // seeds really do go cyclic" half is asserted over the range,
            // below, rather than pretended per seed.
            if (!cyclic) {
                assertTrue(!a.closedACycle, "seed $seed: an even (DAG) seed must never close a cycle")
            } else if (a.closedACycle) {
                cyclicSeedsThatClosedACycle++
            }
        }
        assertTrue(
            cyclicSeedsThatClosedACycle > 0,
            "no odd seed in $SEEDS closed a cycle — the cyclic half of the sweep would be vacuous",
        )
        println("TranscriptGenerator: $cyclicSeedsThatClosedACycle odd seeds closed a cycle")
    }
}
