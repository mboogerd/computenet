package civictech.dialogue

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.agora.semantics.DfQuad
import civictech.cell.CellRef
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.segmentContentHash
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.StringReader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DialogueRuntime] — the AGO1 composition root (task computenet-2aw.4.3;
 * epic computenet-2aw §3.6 [AGO1-DUR-01]/[AGO1-DUR-02],
 * [AGO1-REPLAY-02]/[AGO1-REPLAY-03], §4 BS-18 and BS-19).
 *
 * What this file pins is the **recovery order** and what survives it. The
 * sibling tasks' [civictech.dialogue.apply.GraphApplierTest] and
 * [civictech.dialogue.apply.ApplierSemanticsTest] cover which op runs against
 * which key and what those writes do to credences; nothing here re-checks
 * that. Here the question is only: after a `kill -9`, does a second process
 * opening the same directory arrive at the same graph, the same bindings and
 * the same admitted-utterance ledger, and does it then apply *nothing*?
 *
 * The extractor is a [CassetteExtractor] over an in-test cassette keyed by
 * segment content hash — [civictech.dialogue.mint.RelationMintTest]'s fixture
 * idiom, and necessary rather than convenient: `RuleExtractor`'s relation
 * endpoints do not canonicalize to claim keys it also mints, so a rule-driven
 * transcript would produce no canonical relations at all and the relation half
 * of every assertion below would be vacuously true.
 */
class DialogueRuntimeTest {

    // ------------------------------------------------------------------
    // Fixture: 30 utterances — 10 claims-with-stances, 10 relations, 10 stances
    // ------------------------------------------------------------------

    /** The canonical text of claim [n] (1-based), as the extractor reports it. */
    private fun claimText(n: Int) = "Proposition $n holds."

    /** Utterance [n]'s own text — one sentence, so it segments 1:1. */
    private fun claimUtteranceText(n: Int) = "Turn $n asserts the ${n}th proposition."

    private fun relationUtteranceText(n: Int) = "Turn $n draws the ${n}th consequence."

    private fun stanceUtteranceText(n: Int) = "Turn $n records the ${n}th opinion."

    private val freshClaimText = "A wholly fresh proposition holds."
    private val freshUtteranceText = "One late turn asserts something new."

    private fun speaker(n: Int) = "speaker${(n - 1) % 4}"

    /** 0.6, 0.7, 0.8, 0.9, 0.6, ... — the acceptance criterion's stance band. */
    private fun stanceValue(n: Int) = 0.6 + 0.1 * ((n - 1) % 4)

    /**
     * The relation program: (source claim, target claim, polarity), one per
     * relation utterance. `1 ATTACK 2` and `2 ATTACK 1` form the 2-cycle BS-18
     * asks for, so agora designates a cycle head and the recovered graph has to
     * reproduce that designation as well as the credences. The remaining eight
     * form a chain, deliberately acyclic: a second cycle multiplies the
     * journaled propagate rounds (and, on a `FileJournal`, one fsync each) for
     * no extra coverage.
     */
    private val relationProgram: List<Triple<Int, Int, Polarity>> = listOf(
        Triple(1, 2, Polarity.ATTACK),
        Triple(2, 1, Polarity.ATTACK),
        Triple(1, 3, Polarity.SUPPORT),
        Triple(3, 4, Polarity.SUPPORT),
        Triple(4, 5, Polarity.ATTACK),
        Triple(5, 6, Polarity.ATTACK),
        Triple(6, 7, Polarity.SUPPORT),
        Triple(7, 8, Polarity.SUPPORT),
        Triple(8, 9, Polarity.ATTACK),
        Triple(9, 10, Polarity.ATTACK),
    )

    private val cassetteEntries: Map<String, List<ExtractedItem>> = buildMap {
        // Turns 1..10 — a standalone claim plus its author's stance on it.
        (1..10).forEach { n ->
            put(
                claimUtteranceText(n),
                listOf(
                    ExtractedClaim(text = claimText(n), speaker = speaker(n), utteranceId = "u$n"),
                    ExtractedStance(
                        claimText = claimText(n),
                        speaker = speaker(n),
                        value = stanceValue(n),
                        utteranceId = "u$n",
                    ),
                ),
            )
        }
        // Turns 11..20 — relations among claims minted above.
        relationProgram.forEachIndexed { index, (source, target, polarity) ->
            val turn = 11 + index
            put(
                relationUtteranceText(turn),
                listOf(
                    ExtractedRelation(
                        sourceText = claimText(source),
                        targetText = claimText(target),
                        polarity = polarity.name,
                        utteranceId = "u$turn",
                    ),
                ),
            )
        }
        // Turns 21..30 — a second speaker's stance on each claim.
        (1..10).forEach { n ->
            val turn = 20 + n
            put(
                stanceUtteranceText(turn),
                listOf(
                    ExtractedStance(
                        claimText = claimText(n),
                        speaker = "observer",
                        value = stanceValue(n + 2),
                        utteranceId = "u$turn",
                    ),
                ),
            )
        }
        // The BS-19 tail: one fresh claim with a single 0.7 stance, admitted
        // only after a reset, so its credence is DF-QuAD's base for {0.7}
        // unless something survived the reset.
        put(
            freshUtteranceText,
            listOf(
                ExtractedClaim(text = freshClaimText, speaker = "latecomer", utteranceId = "u99"),
                ExtractedStance(
                    claimText = freshClaimText,
                    speaker = "latecomer",
                    value = 0.7,
                    utteranceId = "u99",
                ),
            ),
        )
    }.mapKeys { (text, _) -> segmentContentHash(hashSegment(text)) }

    private fun hashSegment(text: String) =
        Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text)

    /** A fresh extractor per world — each world builds its own [DialoguePipeline]. */
    private fun cassette(): CassetteExtractor {
        val json = Json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
            cassetteEntries,
        )
        return CassetteExtractor.load(StringReader(json))
    }

    private fun utterance(turn: Int, text: String) = Utterance(
        id = "u$turn",
        turn = turn,
        speaker = if (turn <= 10) speaker(turn) else if (turn <= 20) speaker(turn - 10) else "observer",
        tsMillis = 1_000L * turn,
        text = text,
    )

    /** The 30-utterance transcript, in turn order. */
    private val transcript: List<Utterance> =
        (1..10).map { utterance(it, claimUtteranceText(it)) } +
            (11..20).map { utterance(it, relationUtteranceText(it)) } +
            (21..30).map { utterance(it, stanceUtteranceText(it)) }

    /** Turn 99: the post-reset admission. Not part of [transcript]. */
    private val freshUtterance = Utterance(
        id = "u99",
        turn = 99,
        speaker = "latecomer",
        tsMillis = 99_000L,
        text = freshUtteranceText,
    )

    private val freshKey = claimKey(freshClaimText)

    // ------------------------------------------------------------------
    // World harness
    // ------------------------------------------------------------------

    private val quiescence = 1e-3

    /**
     * One deterministic process over [dir] — construct, recover, drain,
     * complete. Abandoning a world is exactly dropping the reference: no
     * shutdown, no checkpoint, nothing flushed that was not already fsync'd,
     * which is what makes the next world's construction a `kill -9` recovery
     * (`AgoraService`'s `DurabilityTest` idiom).
     */
    private inner class World(dir: File?, seed: Long = 11L) {
        val controller = SimulationController(seed)
        val runtime = DialogueRuntime(
            extractor = cassette(),
            transcript = transcript,
            journalDir = dir,
            scheduler = controller.scheduler(),
            quiescence = quiescence,
        )

        /** Budgeted drain — `SimWorld.runToIdle`'s form, since this host is not a `SimWorld`. */
        fun drain(budget: Int = 2_000_000): Int {
            var steps = 0
            while (controller.step()) {
                check(++steps < budget) { "no quiescence within $budget steps" }
            }
            return steps
        }

        /** Steps 7–8: replay the WAL, drain it, then seed the driver's ledger. */
        fun open(): World {
            runtime.recover()
            drain()
            runtime.completeRecovery()
            return this
        }
    }

    /** Everything BS-18 requires world 2 to reproduce. */
    private data class Snapshot(
        val graph: Map<CellRef, Pair<AgoraService.Kind, Double>>,
        val heads: Set<CellRef>,
        val claims: Set<ClaimKey>,
        val relations: Set<RelationKey>,
        val admitted: List<String>,
    )

    private fun DialogueRuntime.snapshot() = Snapshot(
        graph = service.graph().associate { it.ref to (it.info.kind to it.credence) },
        heads = service.graph().filter { it.info.head }.map { it.ref }.toSet(),
        claims = bindings.boundClaims(),
        relations = bindings.boundRelations(),
        admitted = source.admitted.map { it.id },
    )

    private fun assertRecovered(before: Snapshot, after: Snapshot, label: String) {
        assertEquals(before.graph.keys, after.graph.keys, "$label: recovered ref set differs [AGO1-DUR-01]")
        before.graph.forEach { (ref, node) ->
            val (kind, credence) = node
            val recovered = after.graph.getValue(ref)
            assertEquals(kind, recovered.first, "$label: node $ref changed kind")
            assertTrue(
                abs(credence - recovered.second) <= 25 * quiescence,
                "$label: node $ref credence $credence before vs ${recovered.second} recovered",
            )
        }
        assertEquals(before.heads, after.heads, "$label: cycle-head designation differs")
        assertEquals(before.claims, after.claims, "$label: bound claim keys differ [AGO1-DUR-01]")
        assertEquals(before.relations, after.relations, "$label: bound relation keys differ [AGO1-DUR-01]")
        assertEquals(before.admitted, after.admitted, "$label: admitted-utterance ledger differs [AGO1-DUR-01]")
    }

    /**
     * Lines in agora's structure log — `0` when the file does not exist, so a
     * runtime that never opened one is caught by the *recovery* assertions
     * (world 2's ref set comes back empty) rather than by a
     * `FileNotFoundException` thrown out of this helper before they run.
     */
    private fun structureLines(dir: File): Int {
        val log = File(dir, DialogueRuntime.STRUCTURE_LOG)
        return if (log.exists()) log.readLines().count { it.isNotBlank() } else 0
    }

    private fun tempDir(prefix: String) = kotlin.io.path.createTempDirectory(prefix).toFile()

    // ------------------------------------------------------------------
    // BS-18 / [AGO1-DUR-01] + [AGO1-DUR-02]
    // ------------------------------------------------------------------

    @Test
    fun `BS-18 AGO1-DUR-01 - a world reopened on the same journal dir after a crash rebuilds the same graph, bindings and admitted ledger, and reconciles to nothing`() {
        val dir = tempDir("dialogue-runtime-durability")

        // World 1: replay the whole transcript, reconcile, then vanish without
        // a shutdown. Nothing below is flushed on the way out — every durable
        // record was fsync'd as it was made.
        val before: Snapshot
        val structureLinesBefore: Int
        run {
            val world = World(dir).open()
            world.runtime.source.replay(from = 1)
            world.drain()
            val report = world.runtime.reconcile()
            world.drain()
            assertEquals(30, world.runtime.source.admitted.size, "fixture: all 30 utterances admitted")
            assertEquals(10, world.runtime.bindings.boundClaims().size, "fixture: 10 canonical claims")
            assertEquals(
                relationProgram.size,
                world.runtime.bindings.boundRelations().size,
                "fixture: every relation resolved — a vacuous relation half would hide the whole relation leg",
            )
            assertTrue(report.failures.isEmpty(), "fixture: world 1 applied cleanly, failures=${report.failures}")
            before = world.runtime.snapshot()
            structureLinesBefore = structureLines(dir)
        }

        // Worlds 2 and 3: recovery must be stable across REPEATED restarts —
        // an append on rebuild, or a checkpoint racing the staged replay, shows
        // up as second-restart drift (DurabilityTest's repeat(2) idiom).
        repeat(2) { phase ->
            val label = "restart ${phase + 2}"
            val world = World(dir).open()

            assertRecovered(before, world.runtime.snapshot(), label)

            // [AGO1-DUR-02]: a recovered world has nothing left to apply.
            val report = world.runtime.reconcile()
            world.drain()
            assertEquals(emptyList(), report.ops, "$label: recovered world re-applied structure ops [AGO1-DUR-02]")
            assertEquals(
                structureLinesBefore,
                structureLines(dir),
                "$label: graph.jsonl grew on recovery [AGO1-DUR-02]",
            )

            // BS-18's last clause: re-driving the same transcript through the
            // recovered driver admits nothing and applies nothing.
            world.runtime.source.replay(from = 1)
            world.drain()
            val second = world.runtime.reconcile()
            world.drain()
            assertEquals(
                before.admitted,
                world.runtime.source.admitted.map { it.id },
                "$label: re-replay admitted something [AGO1-REPLAY-02]",
            )
            assertEquals(emptyList(), second.ops, "$label: re-replay produced structure ops [AGO1-DUR-02]")
            assertEquals(
                structureLinesBefore,
                structureLines(dir),
                "$label: graph.jsonl grew on re-replay [AGO1-DUR-02]",
            )
            assertRecovered(before, world.runtime.snapshot(), "$label after re-replay")
        }
    }

    // ------------------------------------------------------------------
    // [AGO1-REPLAY-02] — idempotent replay inside one world
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-REPLAY-02 - replaying the transcript a second time into the pipeline that consumed it admits nothing and applies nothing`() {
        val world = World(dir = null)
        world.runtime.source.replay(from = 1)
        world.drain()
        world.runtime.reconcile()
        world.drain()

        val admitted = world.runtime.source.admitted.map { it.id }
        val refs = world.runtime.service.graph().map { it.ref }.toSet()
        assertEquals(30, admitted.size, "fixture: the first replay admitted the whole transcript")
        assertTrue(refs.isNotEmpty(), "fixture: the first reconcile built a graph")

        world.runtime.source.replay(from = 1)
        world.drain()
        val report = world.runtime.reconcile()
        world.drain()

        assertEquals(admitted, world.runtime.source.admitted.map { it.id }, "[AGO1-REPLAY-02] admitted set grew")
        assertEquals(emptyList(), report.ops, "[AGO1-REPLAY-02] second replay produced structure ops")
        assertEquals(refs, world.runtime.service.graph().map { it.ref }.toSet(), "[AGO1-REPLAY-02] graph changed")
    }

    // ------------------------------------------------------------------
    // BS-19 / [AGO1-REPLAY-03] — reset
    // ------------------------------------------------------------------

    @Test
    fun `BS-19 AGO1-REPLAY-03 - reset empties the graph, the bindings and the ledger, and a fresh claim carries no residual influence`() {
        assertResetIsClean(dir = null, label = "ephemeral")
    }

    @Test
    fun `BS-19 AGO1-REPLAY-03 - reset is equally clean on a journalled runtime`() {
        assertResetIsClean(dir = tempDir("dialogue-runtime-reset"), label = "journalled")
    }

    private fun assertResetIsClean(dir: File?, label: String) {
        val world = World(dir).open()
        world.runtime.source.replay(from = 1)
        world.drain()
        world.runtime.reconcile()
        world.drain()
        assertTrue(world.runtime.service.graph().isNotEmpty(), "$label fixture: the graph was built")

        world.runtime.reset()
        world.drain()
        world.runtime.reconcile()
        world.drain()

        assertEquals(emptyList(), world.runtime.service.graph(), "$label: graph not empty after reset [AGO1-REPLAY-03]")
        assertEquals(emptySet(), world.runtime.bindings.boundClaims(), "$label: claim bindings survived reset")
        assertEquals(emptySet(), world.runtime.bindings.boundRelations(), "$label: relation bindings survived reset")
        assertEquals(emptyList(), world.runtime.source.admitted, "$label: admitted ledger survived reset")

        // One fresh claim-with-stance: its credence is DF-QuAD's base for the
        // single 0.7 stance if and only if nothing of the old graph influences
        // it — a residual attacker or support would move it off the base.
        world.runtime.source.offer(freshUtterance)
        world.drain()
        world.runtime.reconcile()
        world.drain()

        val node = world.runtime.service.graph().single()
        assertEquals(BindingTable.refFor(freshKey), node.ref, "$label: the fresh claim is under its deterministic ref")
        assertEquals(
            DfQuad.base(listOf(0.7)),
            node.credence,
            1e-9,
            "$label: the post-reset claim carries residual influence [AGO1-REPLAY-03]",
        )
    }

    // ------------------------------------------------------------------
    // Ephemeral mode touches no disk
    // ------------------------------------------------------------------

    @Test
    fun `with no journal dir the runtime writes no files and recover is a no-op`() {
        val dir = tempDir("dialogue-runtime-ephemeral")
        val world = World(dir = null)

        // recover()/completeRecovery() are no-ops here: source exists from
        // construction, and calling them neither replaces it nor throws.
        world.runtime.recover()
        world.drain()
        world.runtime.completeRecovery()
        val source = world.runtime.source

        world.runtime.source.replay(from = 1)
        world.drain()
        world.runtime.reconcile()
        world.drain()

        assertTrue(world.runtime.service.graph().isNotEmpty(), "fixture: the ephemeral world still builds a graph")
        assertEquals(source, world.runtime.source, "completeRecovery replaced the live source")
        assertEquals(
            emptyList(),
            dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toList(),
            "an ephemeral runtime wrote to disk",
        )
    }

    // ------------------------------------------------------------------
    // The live quiescence fence
    // ------------------------------------------------------------------

    @Test
    fun `afterQuiescence fences a reconcile on the production VirtualThreadScheduler`() {
        // The production scheduler, named explicitly — this is the one test
        // that leaves the deterministic controller behind, and the fence is
        // what stands in for runToIdle there.
        val scheduler = VirtualThreadScheduler("dialogue-runtime-live-fence")
        val runtime = DialogueRuntime(
            extractor = cassette(),
            transcript = transcript,
            journalDir = null,
            scheduler = scheduler,
            quiescence = quiescence,
        )

        (1..5).forEach { runtime.source.offer(transcript[it - 1]) }

        runtime.afterQuiescence { runtime.reconcile() }

        assertEquals(
            (1..5).map { claimKey(claimText(it)) }.toSet(),
            runtime.bindings.boundClaims(),
            "the fenced reconcile did not see all five admitted claims",
        )
    }
}
