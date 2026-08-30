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

/**
 * **A bounded read perturbs nothing** (spec 21 §Pull, `[21-PULL-02]`): across
 * every `read-state` walk this scenario performed on [cell], that cell's wave
 * plane did not advance.
 *
 * No existing check can express this. Every one of them reads a *fold*
 * (`readView`), an *observation stream*, the dead-letter list or the effect log
 * — all of which answer "what is the state now", none of which answer "did
 * anything move". `no-dead-letters` is the closest, and it only excludes the
 * failure channel: a read that emitted a perfectly well-formed delta produces
 * no dead letter at all. `final-view` is likewise blind to an emission that
 * happened to be idempotent, which for a convergent set family is *every*
 * re-emission of state the consumer already holds — precisely the emission a
 * read must not make.
 *
 * The wave plane is the load-bearing observation rather than a downstream
 * consumer's stream, because the model makes it so: every delivery carries a
 * fresh per-source wave position minted by the emitting outlet (spec 20/22), so
 * a plane that did not move is a delivery that did not happen — while a
 * consumer's stream length is materialized off the producing cell's execution
 * context and so states something about notifier timing rather than about the
 * graph.
 */
@Serializable
@SerialName("wave-plane-unchanged")
data class WavePlaneUnchanged(val cell: String) : Check

/**
 * **A walk sees the whole cell, once** (spec 24 `[24-BOUND-01]`/`[24-BOUND-02]`):
 * for every `read-state` walk this scenario performed on [cell] — every page
 * carried a frontier stamp and all of them were equal; no entry key appeared on
 * more than one page of one walk; and the union of the walk's live entries
 * equals [view]'s fold.
 *
 * `incremental-equals-batch` and `final-view` compare a *view's* fold against an
 * oracle or a golden; neither can see a page at all, so neither can tell a
 * correct read from one that silently dropped, duplicated or truncated entries
 * — a paged read that returned nothing would leave both of them perfectly
 * green. The comparison is genuinely two-sided: [view]'s fold arrives by
 * push-propagation over a link, the pages come from the source cell's own
 * state, so agreement is not the same code answering twice.
 *
 * The equal-stamp requirement is [21-PULL-03]'s antecedent, asserted rather
 * than assumed: a walk whose frontier moved is a *smeared* read for which the
 * union is not claimed to equal anything, and this check reports that as a
 * failure rather than passing on a vacuously false antecedent.
 */
@Serializable
@SerialName("pages-equal-view")
data class PagesEqualView(
    val cell: String,
    val view: String,
) : Check

/**
 * **A cell's outlet emitted exactly N times over a bounded window** (spec 40/42
 * §echo termination; `[KE1-33]`'s duplicate-delivery half): counted from
 * immediately before script step [since] (1-based into the scenario's `script:`
 * list) to check time, [cell]'s outlet produced exactly [exactly] emissions.
 *
 * No existing check can express it, and the reason is structural rather than
 * incidental: every other check reads *state*. `final-view`,
 * `views-converge`, `replicas-converge` and `incremental-equals-batch` read a
 * fold; `observations-*` read a stream whose events are folds;
 * `pages-equal-view` reads a walk over a cell's own state. Echo termination is
 * invisible in all of them **by construction** — the dot algebra is idempotent,
 * so re-absorbing an already-held dot changes no fold, no view and no replica
 * comparison, and a re-emission of exactly that dot is therefore
 * state-indistinguishable from no emission at all.
 *
 * The two checks that do not read a fold are no closer. `effect-count` reads an
 * *effect* log, which exists only at a durable effect boundary (`dur`), not at
 * a replica's gossip outlet. `wave-plane-unchanged` does observe emission, and
 * observes it only *around a `read-state` walk* — it quantifies over the walks
 * recorded in `CheckContext.reads`, so a scenario with no bounded read has
 * nothing for it to assert, and its assertion is "zero, across a read" rather
 * than "exactly N, across a stated window".
 *
 * What is asserted is a **count over a bounded window**, and nothing else: no
 * emission identity, no ordering, no scheduling, no frame content. The window's
 * lower edge is a script position rather than a wall-clock or step count so
 * that it is stated in the scenario's own vocabulary; its upper edge is check
 * time, i.e. after the run's final quiescence.
 *
 * **Well-definedness.** The window is only meaningful when the step at [since]
 * and every later step are separated from all earlier steps by a `quiesce`
 * barrier — §Script semantics leaves delivery interleaving between barriers to
 * the implementation, so without the barrier the count would assert an
 * interleaving the schema deliberately does not fix. The evaluator **fails
 * loudly** on a scenario that violates it, exactly as it fails on an
 * out-of-range [since], a missing runner baseline, or a driver that cannot
 * observe [cell] — a silent 0 would be the vacuous pass this check exists to
 * prevent.
 */
@Serializable
@SerialName("emission-count")
data class EmissionCount(
    val cell: String,
    val since: Int,
    val exactly: Int,
) : Check

/**
 * **An undeliverable frame was refused at this cell, and the refusal was
 * accounted** (spec 24 §Effectful `[24-DUR-06]`): over the whole run, [cell]
 * refused exactly [exactly] deliveries as undeliverable-for-want-of-a-position
 * — discharging what each carried rather than acting on it.
 *
 * `[24-DUR-06]` has three conjuncts: the frame is refused, its exclusive
 * payloads are discharged, and the refusal is **accounted**. The existing
 * vocabulary can state none of them.
 *
 * - [NoDeadLetters] is the only dead-letter surface, and it is **sense-
 *   inverted** here: it asserts *zero* across all hosts, while a scenario for
 *   this requirement has to assert that a refusal HAPPENED. It is not merely
 *   the wrong polarity — it quantifies over every host with no way to name a
 *   cell, so even a "some dead letter exists" reading of it would not say
 *   *this* cell refused *this* delivery.
 * - `{type: effect-count, sink: s, exactly: 0}` states only the other half —
 *   the effect did not fire — and **alone it is satisfied by a silent drop**,
 *   which is precisely the failure `[24-DUR-06]` forbids. A kernel that lost
 *   the frame on the floor and one that refused and accounted it are
 *   indistinguishable to it.
 *
 * **Why a dedicated check rather than a counted dead-letter one.** A
 * `dead-letter-count` would bind the requirement to one *reporting channel*.
 * The spec requires the refusal to be **accounted**; it does not require it to
 * be a dead letter, and an implementation that accounts refusals on their own
 * channel (as this model's own `BoundaryPolicy` denials already do, deliberately
 * kept off the fault counter) would fail a conformance check it actually
 * satisfies. It would also overload the corpus's most common assertion — the
 * meaning of the `no-dead-letters` surface is "zero everywhere", and giving the
 * same surface a second, counted reading makes every existing use ambiguous.
 * A refusal count names the quantity the requirement names, at the cell the
 * requirement names it at.
 *
 * **No window.** Unlike [EmissionCount], this is a whole-run total, and
 * deliberately so: legitimate traffic produces emissions continuously, so an
 * emission count is only meaningful over a stated window, whereas a *refusal*
 * is produced by nothing a correct scenario does except the drive under test.
 * The run total is therefore already the quantity of interest, and a `since:`
 * would buy nothing while importing the unbarriered-window hazard that one has
 * to carry.
 *
 * **Nothing passes vacuously.** `exactly: 0` is satisfied by having nothing to
 * count, so a driver that does not observe refusals at [cell] MUST fail loudly
 * rather than answer `0` — see [civictech.concord.driver.Driver.refusalCount].
 * The evaluator reports that refusal as this check's failure.
 */
@Serializable
@SerialName("refusal-count")
data class RefusalCount(
    val cell: String,
    val exactly: Int,
) : Check
