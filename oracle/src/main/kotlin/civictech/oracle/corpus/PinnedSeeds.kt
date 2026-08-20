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
 * `RAW_VIEW_FLICKER_SEEDS`, `CHAIN_ARTIFACT_SEEDS`), stay pinned to *that* population there,
 * not duplicated here. As of this file's creation the open, NOT-pinned-here populations are:
 * `WavePrefixTest`'s `SEAM_SEEDS` (30, 40, 50, 58), `RAW_VIEW_FLICKER_SEEDS` (8, 28, 44, 54)
 * and `CHAIN_ARTIFACT_SEEDS` (38) — all residual under computenet-eeys — and
 * computenet-4ru.18's BS-1 seed 27. None of those belong here until the defect they name is
 * fixed.
 *
 * **The moment a discoverer's fix lands, the newly-clean seed's home changes**: it leaves
 * whichever open-findings ledger tracked it while it failed (per that ledger's own re-pin
 * convention) and is APPENDED here instead, carrying the seed, its full [GeneratorConfig]
 * (embedded by value — never a reference to a test-source config, since main sources cannot
 * depend on `oracle/src/test`), the bead that fixed the underlying defect, and a one-line
 * reason. Verify the seed actually replays [civictech.oracle.run.RunOutcome.Success] before
 * appending it; an entry that does not replay Success would fail this corpus's own regression
 * test the moment it lands, which is the append-time check that a "fixed" claim is real.
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
 * - **34** — left `RAW_VIEW_FLICKER_SEEDS` (was `[28, 34, 44, 46, 54]`, is now `[8, 28, 44,
 *   54]`); no longer violates at all.
 * - **36** — left the `GLITCH_CANDIDATE_SEEDS` bucket (was `[36]`, is now `[]`); filed as the
 *   glitch candidate under computenet-qjtp (closed) and clean once the generator stopped
 *   naming live elements in unobserved removes.
 * - **46** — left `RAW_VIEW_FLICKER_SEEDS` alongside 34, for the same reason.
 *
 * All three were re-verified to replay `Success` against this file's own base commit before
 * being pinned below.
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
            reason = "Left WavePrefixTest's RAW_VIEW_FLICKER_SEEDS entirely once " +
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
            reason = "Left WavePrefixTest's RAW_VIEW_FLICKER_SEEDS entirely, the same " +
                "emitUnobservedRemove fix as seed 34.",
        ),
    )
}
