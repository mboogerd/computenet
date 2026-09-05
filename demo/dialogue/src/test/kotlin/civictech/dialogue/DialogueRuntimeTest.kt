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
 * ### Measured cost — was ~270 s, is now ~2.5 s (computenet-sh8z)
 *
 * This class used to dominate the repository gate: BS-18 alone **264.2 s**,
 * the class **268.5 s** (macOS/arm64, 2026-09-03, JUnit XML of
 * `--rerun --no-build-cache`, load average 9.4 falling to 6.3). It is now
 * **2.5 s** for BS-18 and **3.1 s** for the class, on the same host minutes
 * later at load average 6.6 — a 104x reduction with every assertion, the
 * `quiescence = 1e-3` threshold, the 2-cycle and the third world (`repeat(2)`)
 * unchanged. Nothing here was weakened; the cost was never where it looked.
 *
 * What it actually was, profiled by counting and timing every
 * `FileJournal.append` this test makes: **73,146 appends totalling 263.6 s** —
 * i.e. essentially the whole test — of which `fsync` was **7.8 s** and the
 * `open`/`write`/`close` around it **253 s**. The journaled propagate rounds
 * were never fsync-bound. They were bound by `FileOutputStream(file, append =
 * true)`, which costs ~3.3 ms on this host against the ~0.03 ms of the fsync
 * that follows it (microbenchmark, 5,000 appends per arm: reopen+fsync
 * 16.7 s, reopen without fsync 15.3 s, one kept handle with fsync 0.11 s, one
 * kept handle without fsync 0.008 s — the fsync is 1% of the reopen).
 * `FileJournal` now keeps one append handle instead of reopening per record,
 * **still fsyncing every append**, so `kill -9` durability — this test's whole
 * subject — is untouched.
 *
 * **The macOS figure does not transfer to ubuntu, and this file should not
 * pretend it does.** The pre-change ubuntu cost was run-variable by 5.8x (45 s
 * to 262 s across three `build-test-fast` jobs of this class: PR #637 run
 * 33718227232 job 100531880203 head `ea00f184` ~45 s; PR #642 run 33726749625
 * job 100557346284 head `75684f14` ~262 s; PR #642 run 33727926137 job
 * 100561063409 head `c090b763f` ~58 s), and on ext4 the `fsync` is a real
 * barrier where on APFS it is nearly free — so the split between "reopen" and
 * "fsync" measured above is a macOS split. Removing 73,146 `open`/`close`
 * pairs can only make ubuntu faster, but by how much is unmeasured here. Read
 * this PR's own `build-test-fast` log before quoting an ubuntu number.
 *
 * The former cost was **ticket-pinned, not an oversight** — computenet-2aw.4.3
 * pins the quiescence threshold, the 2-cycle and the third world, so every
 * lever inside this file was one of its criteria, and computenet-4rof
 * consequently treated the symptom with a per-method
 * `@Timeout(value = 540, unit = TimeUnit.SECONDS)`. computenet-sh8z removed
 * the cost instead, at the journal seam, and with it that override: at the
 * **local** 2.5 s figure above, against the repo-wide 5-minute default
 * (`junit.jupiter.execution.timeout.testable.method.default`,
 * `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:442`), the margin looks like
 * ~118x.
 *
 * **That comparison is the wrong one — computenet-hmcr.** sh8z's fix removed
 * the per-append reopen, not the fsyncs: this test still makes ~72,193
 * `FileDescriptor.sync()` calls (see BS-18 below), so its runtime is
 * essentially linear in per-fsync latency and a macOS/APFS number is not the
 * number a CI timeout should be sized against. On the green attempt of run
 * 33888840373 (job 101080993741, 2026-09-04) BS-18 itself ran ~37.7 s, an
 * ~8x margin against the 300 s default, not ~118x — full derivation on the
 * method below, which carries its own `@Timeout` sized off that figure rather
 * than this paragraph's local one. If a future ubuntu run shows this class
 * back in the tens of seconds, the thing to re-measure is the journal profile
 * above, not the cap.
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
     *
     * computenet-oy26: this is the *only* assertion in BS-18 that can see
     * `AgoraService`'s own replay re-appending to its structure log. Every
     * `assertEquals(emptyList(), report.ops, ...)` beside it reads
     * `GraphApplier`'s `ReconcileReport`, which counts only writes issued by
     * *this call* to `applier.reconcile()` — it cannot see `AgoraService`'s
     * constructor-time replay (`AgoraService.init`, `demo/agora/.../AgoraService.kt`
     * lines 82-108 at this worktree's HEAD), which runs during `World(dir).open()`,
     * strictly before `reconcile()` is ever called. `AgoraService.log()` is
     * guarded by a private `replaying` flag it flips for the duration of that
     * replay specifically so its own `createClaim`/`createEdge`/`remove`
     * calls do not re-append the lines they are replaying — a bug there
     * (dropping or inverting `if (!replaying)`) would grow `graph.jsonl` on
     * every restart while `report.ops` from the subsequent `reconcile()`
     * stayed `emptyList()`, exactly the split this assertion exists to catch.
     *
     * That guard lives in `demo/agora`, which is outside this bead's
     * `metadata.files` claim (`GraphApplier.kt`, `DialogueRuntime.kt`,
     * `DialogueRuntimeTest.kt`, `GraphApplierTest.kt`) — so a mutation
     * demonstrating this assertion's discriminating power (flipping that
     * guard, running BS-18, and watching `graph.jsonl` grow while
     * `report.ops` stays empty) is not reachable from here. This comment is
     * that finding stated plainly, per the bead's own fallback clause,
     * rather than a weaker in-claim mutation manufactured to go red for an
     * unrelated reason.
     */
    private fun structureLines(dir: File): Int {
        val log = File(dir, DialogueRuntime.STRUCTURE_LOG)
        return if (log.exists()) log.readLines().count { it.isNotBlank() } else 0
    }

    private fun tempDir(prefix: String) = kotlin.io.path.createTempDirectory(prefix).toFile()

    // ------------------------------------------------------------------
    // BS-18 / [AGO1-DUR-01] + [AGO1-DUR-02]
    // ------------------------------------------------------------------

    // computenet-hmcr: this comment used to say the repo-wide 300 s default
    // guards this method with "the same ~100x margin as everything else". That
    // was wrong. computenet-sh8z's fix (see class KDoc) removed the per-append
    // reopen, not the fsyncs: this test still makes ~72,193
    // `FileDescriptor.sync()` calls, so its runtime is essentially linear in
    // per-fsync latency, and the ~2.5 s local figure the "~100x" claim was
    // computed from is a fast-local-SSD number, not a CI one.
    //
    // CI figure, re-derived from the raw job log rather than copied from the
    // bead: run 33888840373, job 101080993741 (`build-test-fast`, attempt 2 —
    // the green one), 2026-09-04. In that log, the previous DialogueRuntimeTest
    // method in the same JVM fork ("AGO1-REPLAY-02 - replaying the transcript a
    // second time...") reports PASSED at 15:41:38.8612594Z, and this method's
    // own PASSED line prints at 15:42:16.5681074Z — a gap of 37.706848 s
    // (~37.7 s). Against the 300 s repo-wide default that is an ~8x margin,
    // not ~100x.
    //
    // Sharper evidence than that margin, from the FAILED attempt of the exact
    // same run (job 101075238478, attempt 1 — the attempt computenet-k1by's
    // investigation started from, before the re-run that produced the green
    // attempt above): its `test-results-fast` artifact contains
    // TEST-civictech.dialogue.DialogueRuntimeTest.xml recording this method
    // itself failing with `java.util.concurrent.TimeoutException: ... timed
    // out after 5 minutes`, reported time="341.907". Under the I/O contention
    // that attempt actually hit (`:demo:beadsmirror:test`'s dolt-subprocess
    // phase overlapping this test — see the bead), this exact method did not
    // finish inside the 300 s default at all: a real, observed >=8x
    // degradation that hit BS-18 directly in this exact run, not an inference
    // from correlated tests.
    //
    // Bound: 600 s = ~15.9x the 37.7 s CI baseline (600 / 37.7). It survives
    // the bead's independently-measured 3-6x degradation window from
    // ReadyScheduleTest/ReadyDifferentialTest with room to spare (6x * 37.7 s
    // = 226.2 s, well under 600 s), and it survives the ~8x degradation that
    // actually hit this method above (8x * 37.7 s = 301.6 s, still ~2x under
    // 600 s) — the harder case, since that one is a direct measurement on
    // this method rather than a correlated proxy. It deliberately does not
    // try to survive an unbounded degradation factor: the fix with real
    // leverage is cutting the ~72,193 fsyncs themselves (a kernel
    // durability/group-commit question, CP-C1, filed as its own item) or
    // keeping this test off a lane that shares a disk with
    // :demo:beadsmirror:test's dolt subprocess (a CI-topology change) — both
    // out of scope for this bound, which only buys margin against the
    // sensitivity those would remove.
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
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
            // Not redundant with the assertion above despite both being green on
            // the same mutations we can reach: this one is the only one that can
            // see AgoraService's OWN replay re-appending to graph.jsonl, which
            // `report.ops` structurally cannot — see structureLines()'s KDoc
            // (computenet-oy26).
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
            // Same rationale as the recovery check above (computenet-oy26):
            // `second.ops` cannot see AgoraService's own replay path.
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
