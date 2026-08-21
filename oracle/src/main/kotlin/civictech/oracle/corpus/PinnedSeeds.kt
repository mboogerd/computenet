package civictech.oracle.corpus

import civictech.oracle.bind.CoreOperators
import civictech.oracle.gen.GeneratorConfig

/**
 * The checked-in pinned regression corpus (epic computenet-4ru §2.2, design D8,
 * `[ORA1-REPRO-01..03]`) — every entry in [PinnedSeeds.ALL] is executed by
 * `PinnedSeedsTest` on every `./gradlew :oracle:test` build.
 *
 * ## Append-only, by policy
 *
 * `AGENTS.md`'s "Core invariants to protect" is explicit: *"Preserve deterministic
 * simulation/generative tests. Do not replace a discovered failing seed with a friendlier
 * seed."* This file is the mechanism that invariant protects. An entry here is added when a
 * seed was discovered to fail AND the defect it exposed was fixed; it is never removed,
 * replaced, narrowed, or reordered to make the suite green
 * (`[ORA1-REPRO-02]`, and see `civictech.oracle.run.WavePrefixTest`'s companion object, the
 * pinning convention this file follows). If a fix later needs to change a pinned entry's
 * expected outcome, that is a decision for whoever owns this file next to make explicitly, in
 * a commit that says so — never a silent edit.
 *
 * ## What belongs here, and what does not — the boundary a later discoverer needs
 *
 * **Pin only seeds that are BOTH discovered-to-fail AND fixed.** A seed that still fails —
 * an OPEN finding — does not go here: this corpus asserts `RunOutcome.Success` for every
 * entry, so pinning an unfixed failure would redden every build rather than record it. Unfixed
 * findings are tracked as their own beads and, where the discovering test already carries a
 * named-constant ledger (e.g. `civictech.oracle.run.WavePrefixTest`'s `SEAM_SEEDS`,
 * `HEALED_DIVERGENCE_SEEDS`, `SINGLE_PATH_DIVERGENCE_SEEDS`), stay pinned to *that* population
 * there, not duplicated here.
 *
 * **As of computenet-i3vo (2026-08-21) all three of those `WavePrefixTest` ledgers are EMPTY,
 * and computenet-9fij decided they stay that way — the nine seeds are NOT appended below, on
 * purpose, permanently.** They held `SEAM_SEEDS` (30, 40, 50, 58), `HEALED_DIVERGENCE_SEEDS`
 * (8, 28, 44, 54) and `SINGLE_PATH_DIVERGENCE_SEEDS` (38) — the computenet-eeys residual —
 * until `ScriptGenerator` gained the post-condition that no emitted remove may leave its
 * element live in `Membership`, at which point no generated seed under
 * `WavePrefixTest.generatedSweepConfig()` carries the step-class and all nine left their
 * ledgers at once. computenet-9fij (2026-08-21) is the decision record; this paragraph and the
 * rule below are its outcome, not a pointer to it.
 *
 * **Why not (route (a), append anyway): this is not a fixed defect, and appending would say it
 * is.** computenet-eeys (closed 2026-08-20, PR #365; `concord/corpus/DISPUTES.md`'s
 * `[ORA1-DIFF-09]` entry) settled that these nine's disagreement was the **reference model**
 * reading `[24-SET-03]`'s "observed" as writer-scoped, against the kernel's cell-scoped
 * reading — **no kernel defect is implied by any of them**, and `Membership` still carries that
 * reading today: nothing in this system fixed it. `ScriptGenerator`'s post-condition does not
 * correct `Membership`; it reads `Membership.live` and refuses to *emit* a script the model
 * would get wrong, so the sweep stops exercising the disagreement rather than the disagreement
 * closing. That is categorically unlike computenet-qcm1's 34/36/46, where a generator bug
 * (`emitUnobservedRemove` naming a live element) was the thing found wrong and the thing fixed
 * — the seed still draws the same kind of script, which now runs correctly through code that
 * used to be broken. Pinning the nine would read as "this defect was found and fixed", and the
 * defect these seeds' disagreement pointed at is still open, filed, and unfixed. That reason
 * outweighs the append-only corpus's own asymmetry (an entry cannot be withdrawn) rather than
 * being outweighed by it: the corpus's append-only property makes a wrong entry permanent, and
 * asserting `Success` for a claimed-fixed defect that was not fixed is a wrong entry.
 *
 * **Why not (route (c), pin the hand-built computenet-eeys case instead): it cannot pass.**
 * The three-event case that mechanism is built from — `w0` adds `ab`, `w1` adds `ab`, `w0`
 * removes `ab` (`WavePrefixTest`'s `divergingCase`, also driven by `ShrinkerBs14Test` and
 * `DivergenceControlTest`) — is the demonstration that the model disagrees with the kernel: it
 * replays `RunOutcome.Mismatch`, not `Success`. [PinnedSeed] asserts `Success` for every entry
 * (`PinnedSeedsTest`); there is no [PinnedSeed] this file's shape can express for a case whose
 * entire point is to mismatch. Route (c) collapses into (b): the case that "actually survived"
 * survives as `WavePrefixTest`'s `divergingCase` and `DivergenceControlTest`'s own fixture, not
 * as a member of this corpus.
 *
 * **The population that remains open and un-pinned here is computenet-4ru.18's BS-1 seed 27**,
 * which does not belong here until its defect — not merely its exposure — is fixed.
 *
 * **The moment a discoverer's fix lands, the newly-clean seed's home changes** — but "fix"
 * here means a defect in the system under test (`ScriptGenerator`'s own emission logic, or a
 * kernel defect) was corrected, so the seed still exercises the path that used to fail and now
 * passes for a real reason. It does **not** mean the generator stopped being ABLE to draw the
 * failing script-class at all, with the underlying disagreement the class exposed left standing
 * — that is this file's nine-seed case, and per the paragraphs above it does not qualify. A
 * seed that does qualify leaves whichever open-findings ledger tracked it while it failed (per
 * that ledger's own re-pin convention) and is APPENDED here instead, carrying the seed, its
 * full [GeneratorConfig] (embedded by value — never a reference to a test-source config, since
 * main sources cannot depend on `oracle/src/test`), the bead that fixed the underlying defect,
 * and a one-line reason. Verify the seed actually replays
 * [civictech.oracle.run.RunOutcome.Success] before appending it; an entry that does not replay
 * Success would fail this corpus's own regression test the moment it lands, which is the
 * append-time check that a "fixed" claim is real.
 *
 * ## Initial population — the computenet-qcm1 footprint
 *
 * `computenet-qcm1` fixed `ScriptGenerator.emitUnobservedRemove` (commit `a3176733`), which
 * had been able to draw an unobserved remove naming an element another writer had added and
 * which was still live — the kernel's `SetCell` retracts unconditionally while the model
 * no-ops the remove for lack of an `Observe`, so the disagreement was manufactured by the
 * generator rather than found in the kernel. Three seeds left `WavePrefixTest`'s pinned
 * violation populations entirely as a direct, measured consequence of that fix (re-pin
 * recorded there 2026-08-19, commit `a3176733`), all under `WavePrefixTest`'s own
 * `generatedSweepConfig()` at `scriptLength = 30`:
 *
 * - **34** — left `HEALED_DIVERGENCE_SEEDS` (was `[28, 34, 44, 46, 54]`, became `[8, 28, 44,
 *   54]` at that re-pin, and is empty since computenet-i3vo); no longer violates at all.
 * - **36** — left the `GLITCH_CANDIDATE_SEEDS` bucket (was `[36]`, empty since); filed as the
 *   glitch candidate under computenet-qjtp (closed) and clean once the generator stopped
 *   naming live elements in unobserved removes.
 * - **46** — left `HEALED_DIVERGENCE_SEEDS` alongside 34, for the same reason.
 *
 * All three were re-verified to replay `Success` against this file's own base commit before
 * being pinned below.
 *
 * ## No tagged/OR-map entry — a mechanism limit, not an oversight (computenet-4ru.1.7)
 *
 * Every entry here replays through [civictech.oracle.gen.CaseGenerator.generate], the SAME
 * single-instance generator `PinnedSeedsTest` calls. `civictech.oracle.bind.TaggedOperators`
 * registers `orMap` as its only id, an arity-0 SOURCE, and no operator in the catalog can
 * consume a `TaggedMapDelta` — so `GraphGenerator`'s own `[ORA1-GEN-03]` check ("at least one
 * operator between every source and every terminal") makes a terminal-bearing `orMap` graph
 * unconstructible through this generator, full stop, not merely undiscovered. There is
 * therefore no tagged case this corpus's entry form could ever have pinned a fix against; ORA2's
 * tagged/keyed coverage (`[ORA2-DIFF-01..09]`) runs through
 * `civictech.oracle.run.DifferentialRunner`'s bring-your-own seam instead
 * (`civictech.oracle.tagged.TaggedSweepTest`, `ConvergenceSweepTest`), a different entry point
 * this file's `PinnedSeed` cannot name. `PinnedSeedsTest`'s
 * "computenet-4ru 1 7 the pinned-seed entry form round-trips a tagged config without pinning
 * one" pins this finding as an executable check: a `PinnedSeed` naming `orMap` constructs fine
 * as data and fails LOUDLY at `replay()`'s `[ORA1-REPRO-03]` branch, never silently — the same
 * outcome a stale shape rule would produce for any other entry.
 */
data class PinnedSeed(
    /** The seed this entry pins. Feeds [civictech.oracle.gen.CaseGenerator.generate] directly. */
    val seed: Long,
    /**
     * The full generation config this seed was discovered and fixed under. Embedded by value
     * (never a reference into `oracle/src/test`) so this file has no test-source dependency.
     */
    val config: GeneratorConfig,
    /** The bead that fixed the defect this seed originally exposed. */
    val fixedBy: String,
    /** One line: what was wrong, and why this seed is clean now. */
    val reason: String,
)

/**
 * The checked-in seed list. See this file's header for the append-only rule and the
 * discovered-and-fixed boundary; see `PinnedSeedsTest` for the regression test that runs every
 * entry on every build.
 */
object PinnedSeeds {

    /**
     * The `generatedSweepConfig()` `civictech.oracle.run.WavePrefixTest` discovered the
     * computenet-qcm1 footprint under, embedded by value. Kept `private` and shared only by
     * the entries below — a future entry discovered under a different config embeds its own
     * value rather than reusing this one, since two pinned entries sharing a config is
     * coincidence, not a contract.
     */
    private val QCM1_SWEEP_CONFIG = GeneratorConfig(
        depthRange = 3..5,
        sourceCount = 1,
        vocabulary = listOf(
            CoreOperators.Ids.SET,
            CoreOperators.Ids.KEYED_SET,
            CoreOperators.Ids.FILTER,
            CoreOperators.Ids.FLAT_MAP_SET,
            CoreOperators.Ids.MAP_SET,
            CoreOperators.Ids.COUNT,
            CoreOperators.Ids.UNION,
            CoreOperators.Ids.INTERSECT,
            CoreOperators.Ids.PRESENCE_COUNT,
            CoreOperators.Ids.QUORUM_SET,
        ),
        elementDomainSize = 6,
        scriptLength = 30,
        addRemoveRatio = 0.6,
        unobservedRemoveRatio = 0.25,
        terminalCount = 1,
    )

    /**
     * Append-only. See this file's header KDoc before adding an entry: pin only a seed that is
     * both discovered-to-fail and fixed, verified to replay `Success` first.
     */
    val ALL: List<PinnedSeed> = listOf(
        PinnedSeed(
            seed = 34L,
            config = QCM1_SWEEP_CONFIG,
            fixedBy = "computenet-qcm1",
            reason = "Left WavePrefixTest's HEALED_DIVERGENCE_SEEDS entirely once " +
                "ScriptGenerator.emitUnobservedRemove stopped naming a live element another " +
                "writer added; the violation was the generator's manufactured divergence, not " +
                "a kernel defect.",
        ),
        PinnedSeed(
            seed = 36L,
            config = QCM1_SWEEP_CONFIG,
            fixedBy = "computenet-qcm1",
            reason = "Left WavePrefixTest's GLITCH_CANDIDATE_SEEDS (filed as computenet-qjtp, " +
                "closed) once emitUnobservedRemove stopped naming live elements; the sole " +
                "glitch candidate the sweep found was an artifact of that generator bug.",
        ),
        PinnedSeed(
            seed = 46L,
            config = QCM1_SWEEP_CONFIG,
            fixedBy = "computenet-qcm1",
            reason = "Left WavePrefixTest's HEALED_DIVERGENCE_SEEDS entirely, the same " +
                "emitUnobservedRemove fix as seed 34.",
        ),
    )
}
