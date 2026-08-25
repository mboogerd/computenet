package civictech.oracle.run

import civictech.oracle.bind.OptionalFamilies
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.GeneratorConfig
import civictech.testkit.forEachSeed

/**
 * A **seed sweep** over the generated-case path: generate `(seed, config)` with
 * [CaseGenerator], execute it with [DifferentialRunner.run], and report what the whole range
 * concluded — never what its first failing seed concluded.
 *
 * ## Every seed runs; the report is a density `[ORA1-DIFF-03]`
 *
 * The loop is `civictech.testkit.forEachSeed`, not a hand-rolled `for` with an assertion in
 * it. That is the whole point: `forEachSeed` runs **every** seed regardless of earlier
 * failures and rethrows a [civictech.testkit.dst.SweepFailure] (computenet-e0to) whose
 * `detail` carries the density summary — `"failed on N of M seeds; first: seed=K — ..."` —
 * with the first failure as cause. A fail-fast loop would report "seed 27 disagrees" where the
 * honest reading is "4 of 200 disagree", and those two are different findings: the first looks
 * like a pinned counterexample, the second like a systematic seam. This object does not
 * reimplement that behavior and must not: `forEachSeed` IS the required density form.
 *
 * Because `forEachSeed`'s summary quotes only the **first** failure's message, [run] also
 * prints each failing seed as it happens (see [describe]). Without that, enumerating the other
 * failing seeds costs a second sweep with an ad-hoc collector — which is exactly what a prior
 * session on this task had to do.
 *
 * ## Progress `[ORA1-PERF-03]`
 *
 * [run] calls [onProgress] once per seed, **before** that seed's case is generated or
 * executed, with the seed and its 1-based index in the sweep. The default implementation
 * prints to stdout, which is all the requirement needs: a sweep that hangs must be
 * attributable to a specific case, and the last line printed names it. A caller that wants the
 * progress stream as data (a test, a future reporter) passes its own lambda.
 *
 * ## Budget `[ORA1-PERF-01]` / `[ORA1-PERF-02]`
 *
 * The default range is `0 until `[DEFAULT_SEED_COUNT] — **200 seeds** — sized from a
 * measurement, not from a guess. Measured on this repo's BS-1 configuration
 * (`OracleSweepTest.baselineConfig`: depth 1..4, 4 sources, the six set-algebra ids, 200
 * script ops, 2 terminals, 1 writer) on an Apple-silicon laptop, 2026-08-19:
 *
 * - **~3 ms per seed** wall clock, generation and execution together: 679 ms for the 200-seed
 *   range when BS-1 runs alone (3.4 ms/seed, coldest case), 545 ms when it runs after the rest
 *   of `OracleSweepTest` (2.7 ms/seed, warmed JIT), 817 ms at `-Poracle.seeds=400`
 *   (2.0 ms/seed).
 * - So the default range costs **well under a second**, a small fraction of
 *   `./gradlew :oracle:test`, and is comfortably inside the repo's normal per-module test
 *   budget — as is a 10x widening, should a local run want one.
 *
 * Treat the per-seed figure as the order of magnitude it is, and do not quote it as a
 * cross-machine constant: it was measured in one JVM on one machine (another machine measured
 * ~14 ms/seed at this same config on 2026-08-18, a 4x spread), and it is dominated by script
 * length and topology depth, so a config change invalidates it. `OracleSweepTest`'s BS-1 test
 * prints the figure it actually observed on every run, so the numbers above are checkable
 * rather than folklore.
 *
 * `-Poracle.seeds=N` widens (or narrows) the sweep with no source change:
 * `oracle/build.gradle.kts` forwards it to the test JVM as the [SEED_COUNT_PROPERTY] system
 * property and [defaultSeedCount] reads it. `N` is a seed **count**, so `-Poracle.seeds=1000`
 * means seeds `0..999`; the range always starts at 0 so a widened sweep is a superset of the
 * default one and a failing seed keeps its identity.
 *
 * ## Known taxonomy edge a sweep failure must be read against
 *
 * If a case exhausts its step budget **mid-script**, [DifferentialRunner]'s shared core stops
 * driving and, when nothing is left to step at that instant, compares the *partial* run
 * against the *full*-script model — reporting [RunOutcome.Mismatch] rather than
 * [RunOutcome.NonQuiescence]. So a `Mismatch` in a sweep is only kernel evidence when the run
 * was not budget-starved. The fix belongs in [DifferentialRunner] (a sibling task owns that
 * file), not here; what this file owns is saying so next to the density number, so nobody
 * reads a budget-starved seed as a kernel disagreement. At BS-1 size the default budget
 * ([DifferentialRunner.DEFAULT_STEP_BUDGET], 200 000 steps) is not remotely approached, so the
 * edge is not reachable from the default sweep — but a caller who lowers [run]'s `stepBudget`
 * moves into it.
 *
 * ## What `ORA1-…` and `ORA2 §…` markers ARE — and what they are not (computenet-4ru.22)
 *
 * They are **acceptance clauses of the beads items that built this harness**, not EARS
 * requirement ids. ORA1's are written in epic `computenet-4ru` §4; ORA2's in feature
 * `computenet-4ru.1` §4. Neither family has, or was ever meant to have, normative text under
 * `doc/spec/`, and no `concord/corpus` scenario `covers:` one — deliberately. They constrain
 * this **test harness** (what the reference model may import, how a sweep reports its density,
 * which controls must redden), not the ComputeNet runtime that `doc/spec` specifies and the
 * corpus checks. A concord scenario cannot express *"`civictech.oracle.model` imports no
 * `civictech.cell.data.op` type"*: that is [civictech.oracle.model.ModelImportBoundaryTest]'s
 * job, and it lives here because the obligation is this module's, not the kernel's.
 *
 * That was **checked, not assumed** (2026-08-25, base `3d190aaff`): `git grep 'ORA1-' doc/` and
 * `git grep 'ORA2-' doc/` are both empty, and `concord/corpus`'s only mention of either family
 * is `DISPUTES.md`, which is the honesty ledger rather than a scenario. So there is **no
 * asymmetry** between ORA1 and ORA2 — neither has a `doc/spec` home. A reviewer on
 * `computenet-9892` who went looking for `ORA2 §MODEL-12` in `doc/spec` was looking for
 * something nobody ever wrote, and returned NOT VERIFIED for it (computenet-4ru.22).
 *
 * The ORA2 family is therefore written `ORA2 §MODEL-12`, in this repo's
 * `<document> §<section>` idiom (`96 §E1.5`, `epic computenet-4ru §2.3`) — deliberately **not**
 * a square-bracketed `ORA2-MODEL-12`, whose shape is this repo's mark of an EARS requirement id in
 * `doc/spec` (`[24-TMAP-03]`, `[42-REPL-04]`). To check one, read the bead section it names;
 * do not go looking for spec text. [civictech.oracle.MarkerFormTest] stops the bracketed form
 * from coming back, and `concord/corpus/DISPUTES.md` records the same decision where a reader
 * arriving from the requirement side lands.
 *
 * `ORA1-…` still carries the old bracketed shape, and that difference means **nothing about its
 * status**: it is the same kind of marker, left alone only because renaming its 448 citations
 * reaches outside this module (`:kernel` tests, `kernel/build.gradle.kts`,
 * `oracle/build.gradle.kts`, `.claude/skills/work/SKILL.md`) and outside computenet-4ru.22's
 * file claim. computenet-gmld tracks giving ORA1 the same form.
 *
 * ## What a green sweep MEANS: the reference model is DEFENDED, not PROVEN `[ORA1-HONEST-01]`
 *
 * This object is the module's entry point (epic computenet-4ru §2.3), so the load-bearing
 * caveat belongs here rather than buried beside one model file.
 *
 * A green sweep says: **on every seed in the range, the kernel and the reference model agreed.**
 * It does **not** say the reference model is correct. Nothing in this module proves that, and
 * no test in it could — the reference (`civictech.oracle.model`) is a second implementation of
 * the same semantics, read off the same specification, and a second implementation can be
 * wrong in its own way or wrong in the *same* way as the first. Agreement is evidence, not
 * proof, and "the oracle says so" is never by itself a statement about the kernel.
 *
 * That is not a theoretical caveat here: **computenet-eeys is a measured instance of the
 * reference model being the wrong side of a disagreement** (`civictech.oracle.model.Membership`
 * scopes observed-remove coverage to the removing *writer*, while `[24-SET-03]`'s observer is
 * the *cell* — see [WavePrefixOracle]'s verdict KDoc and the `[ORA1-DIFF-09]` entry in
 * `concord/corpus/DISPUTES.md`). The reference was reviewed, tested and green on the sweep, and
 * still had it wrong.
 *
 * What the reference's correctness actually rests on is **four defenses**. Each is a landed,
 * named, falsifiable test; none is a proof; and they are listed here with their weaknesses so
 * a reader can price a green sweep rather than trust it:
 *
 * 1. **Independence** — `[ORA1-MODEL-10]`, pinned by
 *    [civictech.oracle.model.ModelImportBoundaryTest]. `civictech.oracle.model` may name value,
 *    key and delta types but no `civictech.cell.data.op.*` type and no concrete data-cell
 *    class, enforced by a source-text import scan over the package. This rules out the
 *    strongest form of shared-bug agreement — a model that *calls* or transliterates the
 *    implementation it checks. It does not rule out two independent readings converging on the
 *    same misreading of the spec, which is exactly what eeys was.
 * 2. **A divergence control** — [DivergenceControlTest], and read its KDoc before quoting this
 *    line: **`[ORA1-DIFF-09]`/BS-12 as specified is NOT satisfiable against today's kernel**,
 *    and that file pins the measurement instead of a control that cannot fire. A naive
 *    arrival-order fold — the deliberately wrong reference BS-12 asked for — is indistinguishable
 *    from the real reference under a single writer, and under multiple writers agrees with the
 *    *kernel* on exactly the seeds the real reference fails. BS-12 is **blocked on
 *    computenet-eeys** — on the `[24-SET-03]` observer disagreement that bead *settled*, not on
 *    the bead itself, which is **closed** (2026-08-20, PR #365) having found the reference
 *    model, not the kernel, to be the wrong side. So no answer from eeys is still pending, and
 *    its closure does not unblock this: what would actually make BS-12 buildable is the
 *    `Resolves` bullet of the `concord/corpus/DISPUTES.md` entry — a wrongness this kernel does
 *    not genuinely share, or a `SetCell` remove that becomes writer-scoped (the tripwire
 *    [DivergenceControlTest]'s second test carries). It is filed in
 *    `concord/corpus/DISPUTES.md` rather than weakened into a
 *    passing control. So this defense is today the **weakest of the four**, and saying so is
 *    the point of this section: the sweep currently has no live demonstration that a wrong
 *    *source* model reddens it.
 * 3. **A mutation check** — [MutationCheckTest] (`[ORA1-DIFF-10]`/BS-13). A deliberately wrong
 *    operator model is caught by the differential machinery and attributed to the right
 *    terminal. This is the surviving demonstration of discriminating power, and it covers the
 *    derived-operator half of the vocabulary that (2) cannot currently reach.
 * 4. **A corpus cross-check** — [civictech.oracle.corpus.CorpusCrossCheckTest] (epic §8). The
 *    reference model reproduces the hand-authored `concord/corpus/24-data-cells` scenarios it
 *    covers, with a completeness guard so the covered set cannot shrink silently. This ties the
 *    model to human-authored expectations rather than to the kernel — bounded two ways. It runs
 *    only over the scenarios that exist, a small, fixed set beside a 200-seed sweep (22 of the
 *    29 files; the other 7 are listed there with written out-of-vocabulary reasons). And each
 *    case is **transcribed by hand** into Kotlin rather than parsed from its yaml — `:oracle`
 *    carries no YAML dependency and its `ModuleDependencyTest` bars one on `:concord` — so the
 *    completeness guard sees a yaml file appearing or disappearing, but **not** a yaml whose
 *    content drifts away from the transcription that cites it. That limit is stated at the
 *    test's own KDoc too.
 *
 * The vocabulary the reference deliberately does **not** cover, each exclusion with a written
 * reason verified against kernel source, is the other half of this ledger `[ORA1-HONEST-02]`:
 * see the file KDoc of `civictech.oracle.model.MapCellModel`. Both halves are pinned by
 * [civictech.oracle.HonestyLedgerTest], so this is build-checked prose, not decoration.
 *
 * ## ORA2's model is LESS independent, and what compensates `ORA2 §HONEST-01`
 *
 * The four defenses above are ORA1's. ORA2 widens the vocabulary with the tagged/keyed family
 * (`civictech.oracle.model.DotModel`), and that widening costs something the first defense —
 * independence — cannot fully keep. `[ORA1-MODEL-03]` forbids ORA1's model from reading tag
 * identity, tag counts or any `SetDelta`/`TaggedMapDelta` internal, precisely so it cannot agree
 * with the kernel about a shared bug in the tag algebra. An OR-map's *value*, unlike an OR-set's
 * *membership*, is decided by a total order over dots — `[24-TMAP-03]` — and no reference that
 * refuses to name a dot can state which of two concurrent puts wins. So `DotModel` reads
 * **modelled dot order** (its own `civictech.oracle.model.DotOrder`, minted from the script,
 * never a kernel `Timestamp`) and is therefore **LESS independent of the tag algebra it checks**
 * than ORA1's membership-only model is.
 *
 * What compensates is not an argument, it is four discrimination controls, each a named test in
 * `civictech.oracle.tagged.TaggedControlsTest` — **a green ORA2 sweep is not evidence without
 * them**, the same blocking status feature computenet-4ru.1 §4.9 gives them in as many words:
 *
 * - `ORA2 §CTL-01` — the tagged map's reads replaced by an arrival-order (untagged
 *   `MapDelta`/`MapView`) fold must FAIL on at least one seed of a fixed range (BS-13).
 * - `ORA2 §CTL-02` — an inverted dot order must be detected and attributed to the right key.
 * - `ORA2 §CTL-03` — reset-remove replaced by remove-all must be detected, naming the key
 *   (BS-4) — proving the add-wins boundary is actually exercised.
 * - `ORA2 §CTL-04` — one replica's withheld gossip must be reported as a divergence naming both
 *   replicas and the differing key (BS-8), not passed or silently resolved to one answer.
 *
 * And the same honesty applies to the controls themselves: **not one of them observes state a
 * kernel replica produced.** The bound is unchanged; its former *reason* is not. Until
 * computenet-6v7y, `CaseExecution` wired no `OR_MAP` script source and never folded a tagged
 * terminal, so no generated OR-map case reached the differential runner at all. It now resolves
 * both — an `orMap` source binds through the same `MapOps` surface `MapCell` uses, and an
 * `orMap` terminal folds through `TaggedMapTerminalFold` instead of the arrival-order
 * `MapTerminalFold` — so a **single-instance** generated OR-map case does reach the runner
 * today, and for that case there IS now a `DifferentialRunner` path to substitute a mutant
 * through. Two things keep the bound true anyway: **no control below has been written onto that
 * path** (each still runs where it was written), and the path is single-instance only —
 * `SingleInstanceOrMapModel` refuses a slice carrying gossip deliveries, and `CaseExecution`
 * materialises no replicas, so the *replicated* mesh has no runner path even now. Marker by
 * marker, rather than by a count:
 *
 * - `ORA2 §CTL-01` and `ORA2 §CTL-03` are **model-vs-model**: a mutant reference compared
 *   against `DotModel` directly, with no runner in the loop at all.
 * - `ORA2 §CTL-02` and `ORA2 §CTL-04` both drive the real, unmocked `ConvergenceCheck.check` —
 *   the same seam, entered the same way, differing only in the mutation site — but each over a
 *   **hand-built** `MeshObservation` whose per-replica folds come from `DotModel`, not from
 *   replicas the kernel ran.
 *
 * So what the four establish is that **the reference would catch these defects if a kernel-driven
 * OR-map case reached it**; what they do NOT establish is that any of THESE FOUR observes one.
 * That second half used to be a missing runner path; computenet-6v7y closed the single-instance
 * half of it, so what remains is that no control has been ported onto the path, and that CTL-04's
 * replicated mesh still has none. Both are the sweep/differential work, not this ledger's to
 * close.
 *
 * Read that as a bound on the four controls and on the DIFFERENTIAL RUNNER path — not as a claim
 * that no OR-map coverage anywhere is kernel-driven, which would be false in two distinct ways.
 * Kernel-driven OR-map coverage does exist outside this file, at two different levels of
 * hand-authorship, and a reader needs both to size what is missing:
 *
 * - **Hand-built meshes**: `civictech.oracle.tagged.ConvergenceCheckTest`'s BS-1, BS-6 and BS-7
 *   drive live `OrMapCell` replicas in a `SimWorld` through this same `ConvergenceCheck`, inverted
 *   dot order included. These are the tests that actually discriminate concurrent dot resolution:
 *   reversing the kernel's `TaggedMapDelta.DOT_ORDER` tie-break reddens exactly four tests and all
 *   four are here (review of computenet-4ru.1, 2026-08-21).
 * - **Generated meshes**: `civictech.oracle.tagged.ConvergenceSweepTest` drives 40 GENERATED
 *   three-replica `OrMapCell` meshes in a `SimWorld` — so a generated replicated mesh IS
 *   kernel-driven today, and the "no runner path" clause above is specifically about
 *   `CaseExecution`/`DifferentialRunner`, not about the kernel never running a generated mesh.
 *   Since computenet-9892 that sweep DOES establish the `ORA2 §DIFF-08` "at scale" clause: it
 *   enters `ConvergenceCheck.check()` unchanged, because the mesh is driven under its own
 *   `CaseDelivery` schedule (silenced at spawn by unsubscribing every derived gossip ref, then one
 *   directed `streamTo` edge per stated delivery, retracted again) instead of under a full-sync
 *   mutual barrier — which is what made the old drive's causality a cyclic `Delivery` graph. And it
 *   realises the concurrency the previous drive discarded: its shipped report prints `max live dots
 *   at any key = 3` with 11 of 40 seeds carrying a counter tie, against a generator-achieved mean of
 *   0.969. The `DOT_ORDER` mutation above now reddens it on 7 of 40 seeds
 *   (`ReplicasAgreeButWrong`, first at seed 5), where before computenet-9892 it left it green.
 *
 * ## What is filed rather than built `ORA2 §HONEST-03`
 *
 * The one gap the paragraph above still names is recorded in `concord/corpus/DISPUTES.md`, per the
 * epic's rule — and AGENTS.md's — that a requirement which cannot be checked honestly is **filed**,
 * never weakened into a passing scenario. (`ORA2 §DIFF-08` "at scale" was filed here too until
 * computenet-9892 built the drive that closes it; its entry was deleted in the same change, which is
 * what that entry's own `Resolves` clause instructed.)
 *
 * - BS-9 / `ORA2 §DIFF-07` — no operator in the vocabulary consumes a `TaggedMapDelta` outlet, so
 *   the two-path diamond BS-9 states is unconstructible and `TaggedWavePrefixTest` exercises a bare
 *   `orMap` source terminal instead. Filed with the `MapDelta`-vs-`TaggedMapDelta` typing bound;
 *   `96 §E1.5`'s `UntagCell`/`TaggedMapView` is what would resolve it.
 *
 * The ORA1 half of the same rule is `[ORA1-DIFF-09]`/BS-12, filed in the same file. Both remaining
 * filings are pinned by [civictech.oracle.HonestyLedgerTest], so neither can be silently deleted.
 *
 * Pinned by [civictech.oracle.HonestyLedgerTest] beside `[ORA1-HONEST-01]`, so this statement is
 * build-checked prose too, not a paragraph a refactor can quietly drop.
 *
 * ## What each sweep records about the optional families `ORA2 §HONEST-02`
 *
 * ORA2's vocabulary has optional families — the weighted (Z-set) family and the E1.4/E1.5
 * adopters — that may not exist in the kernel at all (`civictech.oracle.bind.OptionalFamilies`).
 * [reportOptionalFamilies] consumes that availability surface and prints, once per sweep, which
 * families were active and which reported not-applicable with their reason (BS-15): never
 * silently skipped, never a disabled test, never a stub that passes vacuously. This extends the
 * existing progress/failure report [run] already prints; it is not a second report format.
 *
 * ## Non-goals
 *
 * The wave-prefix subset and its own knob, late-joiner and multi-host sweep configurations,
 * and divergence/mutation controls all belong to their own tasks. This object is the plain
 * "run the range and report the density" driver they can each reuse.
 */
object OracleSweep {

    /**
     * The system property `oracle/build.gradle.kts` forwards `-Poracle.seeds` into, read by
     * [defaultSeedCount]. Named after the Gradle property so the two are searchable together.
     */
    const val SEED_COUNT_PROPERTY: String = "oracle.seeds"

    /**
     * The default number of seeds a sweep runs — chosen from the measurement recorded in this
     * object's KDoc (~3 ms per seed at BS-1 size on the machine that measured it, so under a
     * second for the whole range).
     */
    const val DEFAULT_SEED_COUNT: Int = 200

    /**
     * Where a sweep currently is: [seed] is the seed being generated and executed, [index] its
     * 1-based position in the sweep, [total] the number of seeds the sweep will run.
     *
     * [toString] is the line the default progress reporter prints, so a caller that wants the
     * default format with a different sink writes `onProgress = { logger.info(it.toString()) }`.
     */
    data class Progress(val seed: Long, val index: Int, val total: Int) {
        override fun toString(): String = "[oracle-sweep] case $index/$total seed=$seed"
    }

    /**
     * The seed count this JVM sweeps by default: [SEED_COUNT_PROPERTY] when it holds a
     * positive integer, [DEFAULT_SEED_COUNT] otherwise.
     *
     * A malformed or non-positive value falls back **loudly** (a printed line) rather than
     * throwing: a typo in a local `-Poracle.seeds` should not look like a test failure, and it
     * must not silently run a sweep of a size nobody asked for either.
     */
    fun defaultSeedCount(): Int {
        val raw = System.getProperty(SEED_COUNT_PROPERTY) ?: return DEFAULT_SEED_COUNT
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null || parsed <= 0) {
            println(
                "[oracle-sweep] ignoring -P$SEED_COUNT_PROPERTY=$raw (not a positive integer); " +
                    "sweeping the default $DEFAULT_SEED_COUNT seeds",
            )
            return DEFAULT_SEED_COUNT
        }
        return parsed
    }

    /** The default seed range: `0 until `[defaultSeedCount]. Always anchored at 0 — see the KDoc. */
    fun defaultSeeds(): LongRange = 0L until defaultSeedCount().toLong()

    /**
     * `ORA2 §HONEST-02`/BS-15: one line per optional family, recording whether it was active in
     * the kernel this JVM is running against or reported not-applicable with its reason. Called
     * once per [run], before the range starts, and returns the lines it printed so a test can
     * assert on them directly rather than capturing stdout.
     *
     * Consumes [OptionalFamilies.probe] — the availability surface `civictech.oracle.bind`
     * already exposes — rather than probing the classpath a second way; this is the "extend the
     * existing report, never a second report format" clause in as many words.
     */
    fun reportOptionalFamilies(families: List<OptionalFamilies.Availability> = OptionalFamilies.probe()): List<String> {
        val lines = families.map { family ->
            val status = if (family.available) "active" else "not-applicable (${family.reason})"
            "[oracle-sweep] optional family '${family.family}': $status"
        }
        lines.forEach { println(it) }
        return lines
    }

    /**
     * Sweeps [seeds], generating each case from [config] and executing it, and throws
     * `forEachSeed`'s one density summary if any seed did not reach [RunOutcome.Success].
     *
     * [config] is validated against [civictech.oracle.bind.OperatorCatalog] **once**, at the
     * single [CaseGenerator] this function constructs, rather than per seed — so a vocabulary
     * naming an unregistered id fails before the first case instead of 200 times.
     *
     * A seed "fails" when its outcome is anything other than [RunOutcome.Success], and also
     * when generation or execution *throws* (an unresolvable catalog id, a wiring bug in a
     * generated case): `forEachSeed` collects throwables, so both land in the same density
     * number instead of aborting the range.
     *
     * @param config the generator configuration every case in the sweep is drawn from.
     * @param seeds the seeds to run. Defaults to [defaultSeeds], i.e. the `-Poracle.seeds`
     *   knob; pass an explicit range for a focused sweep that should not follow the knob.
     * @param stepBudget each case's step budget, spent inside [DifferentialRunner.run]. Read
     *   this object's "Known taxonomy edge" KDoc before lowering it.
     * @param reference the substitutable oracle, forwarded to [DifferentialRunner.run]; `null`
     *   (the default) resolves each case's reference from the catalog, which is what a real
     *   sweep wants. A non-null value is the divergence-control seam — a deliberately wrong
     *   reference must make the sweep report failures — and is how this file's own
     *   density test provokes failures without needing a broken kernel.
     * @param onProgress called once per seed before that seed runs `[ORA1-PERF-03]`. Defaults
     *   to printing [Progress] to stdout.
     */
    fun run(
        config: GeneratorConfig,
        seeds: LongRange = defaultSeeds(),
        stepBudget: Int = DifferentialRunner.DEFAULT_STEP_BUDGET,
        reference: Reference? = null,
        onProgress: (Progress) -> Unit = { println(it) },
    ) {
        reportOptionalFamilies()
        val generator = CaseGenerator(config)
        val total = (seeds.last - seeds.first + 1L).coerceAtLeast(0L).toInt()
        var index = 0
        forEachSeed(seeds) { seed ->
            index++
            onProgress(Progress(seed, index, total))
            val case = generator.generate(seed)
            val outcome = DifferentialRunner.run(case, reference = reference, stepBudget = stepBudget)
            if (outcome != RunOutcome.Success) {
                val report = "seed=$seed (case $index/$total): ${describe(outcome)}"
                // Printed as well as thrown: forEachSeed's summary quotes only the FIRST
                // failure, so without this line the other failing seeds of a density are
                // invisible and cost a second sweep to enumerate.
                println("[oracle-sweep] FAILED $report")
                throw AssertionError(report)
            }
        }
    }

    /**
     * One line describing a non-success outcome.
     *
     * **Every script-carrying kind is rendered field by field rather than by `toString`**, and
     * that is the whole point of this function: a `script` field holds every event of the case
     * (200 at BS-1 size), and printing it once per failing seed buries the evidence — the fields
     * that say *what* disagreed — in kilobytes of replayable input. The script is recoverable
     * from the seed and the config, which is exactly why the seed is what a failure carries.
     *
     * Four kinds carry one: [RunOutcome.Mismatch], [RunOutcome.WavePrefixViolation],
     * [RunOutcome.ReplicaDivergence] and [RunOutcome.ReplicasAgreeButWrong] — the last two added
     * by computenet-4ru.1.5 after 4ru.1.4's reviewer flagged that the `else` branch below used to
     * dump both mesh verdicts' whole `script` field via `toString()`, the exact kilobytes-of-
     * replayable-input problem this function otherwise exists to avoid. The remaining kinds
     * ([RunOutcome.NonQuiescence], [RunOutcome.DeadLetterFailure],
     * [RunOutcome.ModelEvaluationFailure]) hold no script, so `toString` is the honest rendering
     * for them — a new sealed kind that *does* carry one needs a branch here, not the `else`.
     *
     * Measured at feature-review time (2026-08-19, Darwin arm64) on the single-source config
     * `WavePrefixTest.generatedSweepConfig` at `scriptLength = 30`, seeds `0 until 60`: the
     * `else` branch rendered each of the five `WavePrefixViolation` seeds at ~1.9–2.0 kB with the
     * whole `Script(...)` inline, against ~0.57 kB and no script for the `Mismatch` seeds through
     * the branch above. Not reachable from the BS-1 sweep — `sourceCount = 4` admits no prefix
     * checking at all ([WavePrefixOracle.appliesTo]; measured 0 of 200 seeds) — but reachable
     * from any single-source sweep config, because [run] passes no [WavePrefixOption] and so
     * inherits [WavePrefixOption.DEFAULT].
     *
     * `internal` rather than private so `OracleSweepTest` can assert the rendering directly,
     * without depending on which generated seeds happen to violate.
     */
    internal fun describe(outcome: RunOutcome): String = when (outcome) {
        is RunOutcome.Mismatch ->
            "Mismatch(terminal=${outcome.terminal}, difference=${outcome.difference}, " +
                "expected=${outcome.expected}, actual=${outcome.actual}, " +
                "spec=${outcome.renderedGraphSpec})"

        is RunOutcome.WavePrefixViolation ->
            "WavePrefixViolation(terminal=${outcome.terminal}, kind=${outcome.kind}, " +
                "observationIndex=${outcome.observationIndex}, matchedFloor=${outcome.matchedFloor}, " +
                "regressedTo=${outcome.regressedTo}, observed=${outcome.observed}, " +
                "nearestPrefixes=${outcome.nearestPrefixes}, spec=${outcome.renderedGraphSpec})"

        is RunOutcome.ReplicaDivergence ->
            "ReplicaDivergence(logicalId=${outcome.logicalId}, caseMarker=${outcome.caseMarker}, " +
                "expected=${outcome.expected}, perReplica=${outcome.perReplica}, keys=${outcome.keys})"

        is RunOutcome.ReplicasAgreeButWrong ->
            "ReplicasAgreeButWrong(logicalId=${outcome.logicalId}, caseMarker=${outcome.caseMarker}, " +
                "replicas=${outcome.replicas}, difference=${outcome.difference}, " +
                "expected=${outcome.expected}, actual=${outcome.actual}, keys=${outcome.keys})"

        else -> outcome.toString()
    }
}
