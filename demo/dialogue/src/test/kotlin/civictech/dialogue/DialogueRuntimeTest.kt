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
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit
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
 *
 * ### Measured cost — this class dominates the repository gate
 *
 * The BS-18 test alone is **~270 s** and the whole class **~274 s**
 * (macOS/arm64, 2026-09-03, reviewer-measured from the JUnit XML; the
 * implementer measured 283 s / 287 s on the same machine under different
 * load). Three worlds, each converging the 2-cycle at `quiescence = 1e-3`
 * against a real on-disk journal that fsyncs per journaled propagate round —
 * the cost `AgoraService`'s own `DurabilityTest` avoids by switching to an
 * in-memory journal, which BS-18 cannot do because its whole subject is what
 * survives a `kill -9`. (Re-measured 2026-09-03 by computenet-4rof's review on
 * the same host at load average ~4.5: BS-18 276.3 s, class 280.8 s, from the
 * JUnit XML of `--rerun --no-build-cache`. The figures above hold.)
 *
 * That is a **deliberate, ticket-pinned cost, not an oversight**:
 * computenet-2aw.4.3's acceptance criteria pin the quiescence threshold, the
 * 2-cycle and the third world (`repeat(2)`), so every available lever for
 * making it cheaper is one of those criteria. Anyone shortening the
 * repository gate should change the bead's criteria rather than quietly
 * weakening an assertion here.
 *
 * ### …and on ubuntu the cost is run-variable by ~5.8x — 45 s to 262 s
 *
 * The macOS figure above is not automatically the gate's cost — but neither is
 * any single ubuntu reading. Two `build-test-fast` runs of this class, both on
 * `ubuntu-latest`, differ by 5.8x, read off each job's own log:
 *
 * - PR #637 (run 33718227232, job 100531880203, head `ea00f184`): the five
 *   cheap tests finish at `05:21:05.394Z`, BS-18 `PASSED` at `05:21:50.221Z`
 *   — **~45 s**.
 * - PR #642, the change described below (run 33726749625, job 100557346284,
 *   head `75684f14`): cheap tests finish at `07:13:42.522Z`, BS-18 `PASSED` at
 *   `07:18:04.418Z` — **~262 s**, i.e. ~38 s under the 5-minute default this
 *   method used to run against.
 *
 * So the reading this item carried for most of its life — "~51 s on ubuntu,
 * ~6x margin, the thin margin is purely a local/macOS problem" — does not
 * survive its own PR's CI run. The thin margin is a property of the *slow*
 * tail on both platforms; ubuntu just reaches it less often. Do not treat a
 * single green ubuntu timing here as a margin.
 *
 * What both runs do agree on is that this class is **not on the critical
 * path**: #637's lane totalled 8m00s and #642's ~9m30s, each scheduling other
 * modules' tests alongside and after this one and each finishing minutes after
 * BS-18 returned. There is a margin problem here; there is no CI-*cost*
 * problem.
 *
 * ### …but the local margin against the global timeout was the real bug
 * (computenet-4rof)
 *
 * The repo sets a global JUnit per-method timeout of 5 minutes
 * (`junit.jupiter.execution.timeout.testable.method.default`,
 * `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:442`). Against that cap,
 * BS-18's ~270 s idle cost is not merely "expensive" — it is a **~30 s
 * (~10%) margin**, and two different implementers hit the cap outright with
 * a sibling agent sharing the machine, even though neither of their diffs
 * touched anything this test depends on. It shows up first as a
 * local/agent-experience defect (a red suite unrelated to the diff under
 * test), and — on the evidence of the 262 s ubuntu run recorded above — the
 * gating lane was riding the same thin margin, so this was never purely a
 * macOS problem. computenet-4rof resolved it with a per-method
 * `@Timeout(value = 540, unit = TimeUnit.SECONDS)` on BS-18 alone, rather
 * than moving the class to a tag-excluded lane: exclusion would still need a
 * dedicated CI lane to keep exercising [AGO1-DUR-01] at all, solves a *cost*
 * problem this class does not have (it is not on the critical path either
 * run), and does nothing for a developer who runs this class directly — which
 * is exactly when the thin margin bites. See computenet-4rof for the full
 * comparison of options.
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

    // computenet-4rof: the repo's global per-method timeout
    // (`junit.jupiter.execution.timeout.testable.method.default = 5m`,
    // buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:442) leaves this method a
    // ~30 s margin (~10%) against its own idle macOS/arm64 cost (~270 s,
    // measured 2026-09-03) — thin enough that two different implementers hit
    // the 5-minute cap outright while an unrelated sibling agent shared the
    // machine (load 4.5-6.8), even though nothing in their diffs touched this
    // test; and PR #642's own `build-test-fast` run measured 262 s on
    // ubuntu-latest, ~38 s under the same cap, so the gating lane was riding
    // the margin too. `@Timeout` below raises the cap for this one method to
    // 540 s — ~2x the slowest run observed on either platform (276 s macOS,
    // 262 s ubuntu), chosen so a wedged BS-18 is still reported in about twice
    // its honest runtime rather than never, and deliberately scoped to this
    // method so the 5-minute default keeps guarding every other test in the
    // repo against a real hang. See the class doc comment above for why the underlying cost
    // is not itself a lever here.
    @Timeout(value = 540, unit = TimeUnit.SECONDS)
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
