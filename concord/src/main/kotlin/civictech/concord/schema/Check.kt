package civictech.concord.schema

import civictech.concord.value.Value
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed check vocabulary (§1.4) as it appears **in a scenario** — the
 * declarative form kaml deserializes. The *executable* evaluators that consume
 * these against driver observations live in `civictech.concord.check` (stubbed in
 * W0, filled in W1-B). A scenario passes only if every check holds on every run
 * of the schedule sweep (§1.4); `kind: control` scenarios are asserted to fail.
 *
 * Canonical YAML: `type`-discriminated, e.g. `{type: final-view, view: v,
 * equals: [pear, plum]}`. Growing this vocabulary is a spec change (Concord P5).
 */
@Serializable
sealed interface Check

/** At quiescence, `readView(view)` equals the golden [expected]. */
@Serializable
@SerialName("final-view")
data class FinalView(
    val view: String,
    @SerialName("equals") @Contextual val expected: Value,
) : Check

/** All listed [views] hold equal folds at quiescence. */
@Serializable
@SerialName("views-converge")
data class ViewsConverge(val views: List<String>) : Check

/**
 * [view] equals the harness-side batch oracle (catalog semantics over the
 * accepted-op multiset). `view: "*"` (all views) is a generative extension
 * handled in W4-C.
 */
@Serializable
@SerialName("incremental-equals-batch")
data class IncrementalEqualsBatch(val view: String) : Check

/** A late-linked view's fold equals an early-linked view's fold. */
@Serializable
@SerialName("late-join-equals-early")
data class LateJoinEqualsEarly(
    val early: String? = null,
    val late: String? = null,
) : Check

/** Every event on [view]'s observation stream satisfies the catalog predicate [fn]. */
@Serializable
@SerialName("observations-all-satisfy")
data class ObservationsAllSatisfy(val view: String, val fn: String) : Check

/** [view]'s observation stream never regresses under [order] (default: natural). */
@Serializable
@SerialName("observations-monotone")
data class ObservationsMonotone(val view: String, val order: String? = null) : Check

/** All live replicas of the [logical] id hold equal folds (dist profile). */
@Serializable
@SerialName("replicas-converge")
data class ReplicasConverge(val logical: String) : Check

/** Zero dead letters across all hosts. */
@Serializable
@SerialName("no-dead-letters")
data object NoDeadLetters : Check

/** Effectful [sink] acted exactly [exactly] times per [key] (durability dedup). */
@Serializable
@SerialName("effect-count")
data class EffectCount(
    val sink: String,
    val key: String? = null,
    val exactly: Int,
) : Check

/**
 * The **set-shaped** glitch-freedom check (spec 22 `22-GF-01`/`22-GF-02`;
 * DISPUTES.md `22-GF-DIAMOND-01`/`22-GF-NESTED-01`/`22-WAVE-FANIN-01`):
 * [view]'s observation stream must never expose a torn fork-join delivery — a
 * state where one arm of a wave has landed and its sibling has not.
 * `observations-all-satisfy` cannot express this: its `fn` is drawn from the
 * frozen (scalar) function catalog, and every scalar predicate (`even`,
 * `mod-eq`, …) trivially fails a set-valued (`ListVal`) observation, so there
 * is no way to assert wave-completeness over a SET stream with the existing
 * vocabulary.
 *
 * Semantics: every value on [view]'s stream must equal the [source]
 * set-source cell's own fold at *some* whole prefix of its accepted
 * `add`/`remove` op sequence (script order, `times` expanded) — i.e. the
 * observation is exactly what the source looked like after 0..N whole
 * completed ops, never a state that could only arise from part of one op's
 * fork-join delivery. The evaluator (`civictech.concord.check.Checks`) computes
 * these prefixes itself (harness-only, `add`/`remove` semantics), so this check
 * stays kernel-free and driver-neutral like the rest of the vocabulary — it
 * does not reuse the batch oracle (which only knows the *final* fold) or the
 * function catalog (which is scalar-shaped).
 */
@Serializable
@SerialName("observations-whole-waves")
data class ObservationsWholeWaves(
    val view: String,
    val source: String,
) : Check
