package civictech.oracle.bind

import civictech.cell.data.OrMapCell
import civictech.cell.graph.CellFactory
import civictech.oracle.model.ElementShape
import civictech.oracle.model.SingleInstanceOrMapModel
import java.io.Serializable

/**
 * ORA2's tagged/keyed catalog registrations (computenet-4ru.1.2), beside [CoreOperators]'
 * ORA1 slice — the same [OperatorCatalog.register] seam, no second catalog and no second
 * registration path (feature computenet-4ru.1's REUSE clause).
 *
 * ## One family registered, three not — read this before trusting an id's name
 *
 * The task this file closes set out to register four kernel cells: `OrMapCell`,
 * `KeyedSetCell` "deepened past membership", `MergeableGroupByCell`, and `PnCounterCell`
 * "resolved by pointwise-max". Only `OrMapCell` is registered below, and even that is
 * restricted (next section). The other three are **not**, for reasons verified against the
 * actual kernel/model surface rather than assumed from the bead's prose — recorded here and on
 * the bead (computenet-4ru.1.2) rather than forced through. All three share one root cause:
 * `OperatorCatalog.register`'s seam admits exactly two evaluation shapes —
 * [SourceModel.evaluate]`(slice: SourceScript)` for arity-0 entries and
 * [civictech.oracle.model.OperatorModel.evaluate]`(inputs: List<ModelState>)` for arity>=1 ones —
 * and each of these three needs strictly more than either shape can supply.
 *
 * - **`KeyedSetCell` cannot be "deepened past membership" at all through this seam.**
 *   `KeyedSetCell.outlet` is `Subscribe<Propagate<SetDelta<E>>>`
 *   (`kernel/src/main/kotlin/civictech/cell/data/KeyedSetCell.kt`) — the emitted delta
 *   carries **elements only**, never the key. `civictech.oracle.model.KeyedReputModel`
 *   (built by the dependency task, computenet-4ru.1.1) computes a key -> element table, which
 *   is a genuinely different observable from what this outlet can ever produce; there is no
 *   dataflow terminal a differential run could compare it against. `ORA1`'s existing
 *   `keyedSet` entry (`CoreOperators.Ids.KEYED_SET`, using `KeyedSetSourceModel`) already
 *   registers this cell at the only granularity its outlet supports — the live element set.
 *   Reaching per-key coverage needs a kernel change (a keyed outlet, or a bounded-read-based
 *   observation path the differential runner does not use today), and this task's own
 *   NON-GOALS forbid kernel edits. The bead's "CURRENT STATE" claim that this cell "deepens to
 *   per-key value coverage" does not hold against the kernel as it stands.
 *
 * - **`MergeableGroupByCell` cannot be registered as either shape.** The kernel truth: its
 *   `inlet` is `FanInlet<Propagate<SetDelta<E>>>` — a downstream operator that must be wired to
 *   an upstream element-emitting source, exactly like `GroupByCell`, so `shape = source(..)` is
 *   wrong (nothing can drive this cell with raw ops the way a generator drives an actual source
 *   cell). That leaves `shape = unary(..)`, which needs an
 *   [OperatorModel][civictech.oracle.model.OperatorModel] — a pure function of the upstream's
 *   already-computed [ModelState]. But `civictech.oracle.model.MergeableGroupByModel` (also
 *   computenet-4ru.1.1) is deliberately a [SourceModel], not an `OperatorModel`, and its own
 *   KDoc explains why that is not a shortcut to fix: the model has to distinguish "never added"
 *   from "added, then removed upstream" (the cell's whole point is that removal does NOT shrink
 *   the aggregate), and a live `ModelState.SetState` has already thrown that distinction away —
 *   an `OperatorModel` reading only the live set "could not tell them apart and would have to
 *   guess." An adapter that reconstructed aggregates from the live set would be **silently
 *   wrong**, not merely restricted, on any case where the upstream source ever removes an
 *   element — and unlike the `orMap` restriction below, there is no signal available at
 *   evaluation time to detect that case and fail loudly instead; the information is already
 *   gone by the time an `OperatorModel` sees its input. Building one would trade honest
 *   non-registration for a landmine, which is worse.
 *
 * - **`PnCounterCell`'s pointwise-max convergence cannot be checked from a single
 *   [SourceScript].** `civictech.oracle.model.PnCounterConvergenceModel.converged(script)`
 *   (also computenet-4ru.1.1) needs every replica's own slice to merge across — that is the
 *   entire point of a convergence model, and it is precisely what [SourceModel.evaluate]'s
 *   one-slice signature cannot supply. A registration restricted to what one slice *can*
 *   answer would compute `incs - decs` for that one source, which is byte-for-byte what the
 *   already-registered `pnCounter` entry (`CoreOperators.Ids.PN_COUNTER`, using
 *   `PnCounterSourceModel`) already computes — a second id with identical single-instance
 *   behaviour is not new coverage, it is a second name for the same check, and registering it
 *   would be exactly the "registered but exercises nothing new" outcome `VocabularyCompletenessTest`
 *   and `CatalogReachabilityTest`'s own honesty framing warn against. The convergence property
 *   `ORA2 §DIFF-01..09` actually wants is a multi-instance property; it belongs to whichever
 *   task builds the mesh differential, calling `PnCounterConvergenceModel.converged`/`fold`
 *   directly over a `Script` it assembles itself, not to a per-node catalog entry.
 *
 * All three are architecture findings about the gap between `OperatorCatalog`'s per-node,
 * two-shape evaluation contract and ORA2's models — DotModel, MergeableGroupByModel and
 * PnCounterConvergenceModel each need either the whole multi-instance script or a writer's
 * full add/remove history, and neither SourceModel nor OperatorModel's signature carries that.
 * None of this is something wiring changes in `bind/` can close without either weakening a
 * model to a narrower, sometimes-wrong claim, or extending the evaluation contract itself
 * (`civictech.oracle.model.ReferenceOp`/`ReferenceModel`, outside this task's file claim, and a
 * design decision bigger than one task should make silently). Reported on computenet-4ru.1.2
 * rather than forced through.
 *
 * ## `orMap` — registered, and restricted the same honest way `MapCell` is
 *
 * `OrMapCell.inlet` is `Use<MapOps<K, V>>` — a genuine source cell, drivable directly by raw
 * ops exactly like `SetCell`/`KeyedSetCell`/`MapCell`/`CounterCell`/`PnCounterCell` — so
 * `shape = source(..)` is kernel-honest here, unlike `MergeableGroupByCell` above. [DotModel]
 * has the same shape of problem as [civictech.oracle.model.PnCounterConvergenceModel] in
 * principle: its cross-instance merge (`DotModel.Fold.applyDeliveries`) reads
 * `script.slice(delivery.from)`, which needs the **whole** multi-instance [Script], not one
 * [SourceScript]. Unlike the PN-counter case, though, a single OrMapCell instance's *own*
 * semantics — atomic retract-then-add on `put`, reset-remove tombstoning, dot minting — are
 * real, new coverage no other registered entry provides, and they are fully answerable from
 * one slice with zero peer instances (no deliveries to resolve), and — unlike
 * `MergeableGroupByCell` above — a slice carrying deliveries IS detectable at evaluation time
 * (`slice.deliveries.isNotEmpty()`), so the restriction can fail loudly instead of silently
 * guessing. [SingleInstanceOrMapModel] registers exactly that: correct for a delivery-free
 * slice, and it throws, by name, rather than silently ignoring a delivery it cannot honestly
 * resolve — the same idiom `civictech.oracle.model.MapCellSourceModel.MultiWriterMapSliceException`
 * already uses for the single-writer restriction on `MapCell`. The replicated-mesh differential
 * (`ORA2 §DIFF-01..09`) stays the sweep/runner task's, driving `DotModel.converged`/`stateOf`
 * directly.
 *
 * `SingleInstanceOrMapModel` itself lives in `civictech.oracle.model`
 * (`TaggedKeyedModels.kt`), not here beside its registration — `ORA1 §MODEL-10`/
 * `ORA2 §MODEL-11`'s import boundary is enforced by scanning `civictech.oracle.model`'s source
 * set, and this file legitimately imports the concrete kernel cell `OrMapCell` for wiring, which
 * a `ReferenceOp` implementation must never do (computenet-n00e; `ModelImportBoundaryTest`).
 */
object TaggedOperators {

    /** The catalog ids this file binds. See this file's own KDoc for which ORA2 ids are NOT here, and why. */
    object Ids {
        const val OR_MAP = "orMap"

        /** Every id this file registers, in registration order. */
        val ALL: List<String> = listOf(OR_MAP)
    }

    private val SCALAR = ElementShape.Scalar

    /**
     * `OrMapCell.outlet` carries `Propagate<TaggedMapDelta<K, V>>`, not `MapDelta` — deliberately
     * [ElementShape.TaggedMapOf], not the plain [ElementShape.MapOf] every other map-shaped
     * entry in [CoreOperators] uses. Registering `orMap` as `MapOf(Scalar, Scalar)` here made it
     * shape-equal to `CoreOperators`' `SCALAR_MAP`, so `GraphGenerator` treated `orMap`'s output
     * as fit to feed `join`/`combineLatest`/`lookupJoin` — an edge that is a genuine kernel type
     * violation (`TaggedMapDelta` cannot cast to `MapDelta`), reachable on 20/20 generated seeds
     * before this fix (computenet-880k). [ElementShape.TaggedMapOf]'s own KDoc has the full
     * mechanism; the fix is entirely this one shape, since `GraphGenerator.satisfiedBy` already
     * refuses by shape *inequality* — no generator edit is needed once the two shapes are
     * genuinely distinct.
     */
    private val TAGGED_SCALAR_MAP = ElementShape.TaggedMapOf(SCALAR, SCALAR)

    /**
     * Binds every id in [Ids.ALL] into [OperatorCatalog].
     *
     * @throws IllegalStateException if any of them is already registered — same contract as
     *   [CoreOperators.registerAll].
     */
    fun registerAll() {
        /* `OrMapCell` — single-instance dot semantics only. See this file's KDoc for the
         * scope restriction and why it stops there, and for why `KeyedSetCell`, `PnCounterCell`
         * and `MergeableGroupByCell` are not registered here at all. */
        OperatorCatalog.register(
            id = Ids.OR_MAP,
            shape = ShapeRule.source(TAGGED_SCALAR_MAP),
            kernel = CellFactory { ref -> OrMapCell<Any?, Any?>(ref) },
            model = SingleInstanceOrMapModel,
        )
    }
}

/**
 * The availability gate for ORA2's optional families (`ORA2 §WGT-06`/BS-15, the
 * `ORA2 §ADOPT-01..04` gate) — computenet-4ru.1.2's other half.
 *
 * These six kernel types are the weighted (Z-set) family and the E1.4/E1.5 adopters `96 §E6`
 * and `96 §E1.4`/`§E1.5` describe. When this object was written (2026-08-21) none of them
 * existed under `kernel/src/main`, so every family reported `available = false`. **That is no
 * longer true of `UntagCell`**: `96 §E1.5`'s adapter half landed it at
 * `civictech.cell.data.op.UntagCell` (epic `computenet-j2x`, feature `computenet-j2x.3`), the
 * FQN [CANDIDATES] already guessed, so [probe] now reports that one family `available = true`
 * with a `null` reason. The other five still report absent — see the caveat below for why
 * `TaggedMapView`'s absence is a *stale guess* rather than a genuinely missing type.
 *
 * Availability is not registration. [OperatorCatalog.register] needs a real
 * [CellFactory][civictech.cell.graph.CellFactory] *and* an evaluable model, and for the five
 * still-absent families there is no cell to build a factory from. `UntagCell` now has the
 * factory half, but registering it would additionally need a `civictech.oracle.model`
 * reference model for a `TaggedMapDelta -> MapDelta` unary — a new file in the model source
 * set, whose import boundary (`ORA1 §MODEL-10`/`ORA2 §MODEL-11`) forbids it from naming the
 * kernel cell it mirrors, i.e. a genuine second implementation of the adapter's effective-only
 * diff. That is real design work, not wiring, and it is deliberately NOT done in
 * `computenet-j2x.3.4` (whose acceptance is the three cross-module registrations only). Until
 * it lands, `UntagCell` is *available and unregistered*, and this comment is the record of why
 * — the same honest-non-registration idiom this file's [TaggedOperators] KDoc uses for
 * `KeyedSetCell`, `MergeableGroupByCell` and `PnCounterCell`.
 *
 * So absence — where it still holds — is reported, not registered: [probe] returns one
 * [Availability] per optional family, each absent one carrying `available = false` and a
 * written [Availability.reason]. That is the honest gate BS-15 asks for — "reported
 * not-applicable with the reason recorded," never silently skipped, never a disabled test,
 * never a stub that passes vacuously. The per-sweep *recording* of this result
 * (`ORA2 §HONEST-02` in `OracleSweep`) is the controls/honesty task's; what this object
 * contributes is the probe result itself, on the catalog/entry surface, for that task to read.
 *
 * ## Why reflection, and why the class names below are a best-effort guess
 *
 * [probe] loads each type by its fully-qualified name via `Class.forName`, catching
 * `ClassNotFoundException` — the "compile-time absence marker" alternative the bead offers
 * would need these exact types to exist as *something* (even an empty marker interface) for
 * `bind/` to reference them, which is no different from building the cell early. Reflection
 * needs no such thing: it can name a class that has never existed and fail closed.
 *
 * The fully-qualified names are this object's own guess at where `96 §E6`/`§E1.4`/`§E1.5`
 * will land these types — cells beside their siblings in `civictech.cell.data`
 * (`WeightedSetCell`, mirroring `SetCell`/`OrMapCell`) or `civictech.cell.data.op`
 * (`TagsToWeightsCell`, `WeightsToTagsCell`, `UntagCell`, mirroring the rest of that package),
 * deltas in `civictech.cell.data.delta` (`WeightedSetDelta`, mirroring `SetDelta`/`TaggedMapDelta`),
 * and `TaggedMapView` beside `OrMapCell` in `civictech.cell.data`. **If `96 §E6` lands any of
 * these under a different package**, [probe] keeps reporting that family `available = false`
 * with today's reason even after the class exists — a guessed FQN that turns out wrong fails
 * toward "absent," never toward a false "present," but it does mean the guess has to be
 * corrected in the same change that lands the kernel type, not discovered later by a sweep
 * silently staying dark. `TaggedOperatorsTest` pins each family's expected availability
 * one by one, so every such correction is a visible, deliberate edit rather than a silent one.
 *
 * ### `UntagCell` vindicated the guess; `TaggedMapView` did not — and its entry is knowingly stale
 *
 * `UntagCell` landed at exactly the guessed `civictech.cell.data.op.UntagCell`, so the probe
 * flipped to `available = true` with no edit to [CANDIDATES] at all — the mechanism above
 * working as designed.
 *
 * `TaggedMapView` is the counter-case this section predicted. It **exists** as of
 * `96 §E1.5`'s view half, but at `civictech.cell.data.view.TaggedMapView` — a `.view`
 * subpackage, not `civictech.cell.data` — so [CANDIDATES]' guess misses it and [probe] still
 * reports it absent. That report is *false*, in the fail-toward-absent direction this section
 * calls out. It is left uncorrected here only because `computenet-j2x.3.4`'s acceptance scopes
 * that task to `UntagCell`'s status alone and requires the other five families to keep the
 * availability they already had; the correction (one FQN, plus flipping its pin in
 * `TaggedOperatorsTest`) belongs to its own item, filed as a follow-up. **Do not read
 * `TaggedMapView: available = false` as evidence the type is missing.**
 */
object OptionalFamilies {

    /** One optional family's availability: present in the kernel, or absent with a written reason. */
    data class Availability(val family: String, val available: Boolean, val reason: String?) : Serializable

    private const val DATA_PKG = "civictech.cell.data"
    private const val DELTA_PKG = "civictech.cell.data.delta"
    private const val OP_PKG = "civictech.cell.data.op"

    /** `family name -> best-effort fully-qualified class name`, in the order [probe] reports them. */
    private val CANDIDATES: List<Pair<String, String>> = listOf(
        "WeightedSetDelta" to "$DELTA_PKG.WeightedSetDelta",
        "WeightedSetCell" to "$DATA_PKG.WeightedSetCell",
        "TagsToWeightsCell" to "$OP_PKG.TagsToWeightsCell",
        "WeightsToTagsCell" to "$OP_PKG.WeightsToTagsCell",
        "UntagCell" to "$OP_PKG.UntagCell",
        "TaggedMapView" to "$DATA_PKG.TaggedMapView",
    )

    /**
     * Every optional family's availability, in [CANDIDATES] order. Never empty, never silently
     * missing a family — BS-15's "never skipped" clause means the absence has to be a value in
     * this list, not the family's entry not being here at all.
     */
    fun probe(): List<Availability> = CANDIDATES.map { (family, fqcn) -> probeOne(family, fqcn) }

    /**
     * `internal`, not `private`: computenet-n00e's positive-arm test calls this directly with a
     * real, present class name to prove [Availability.available] can be `true` — [probe] alone
     * only ever exercises the [CANDIDATES] list, every entry of which is absent today, so
     * nothing committed proved the `true` branch without this seam.
     */
    internal fun probeOne(family: String, fqcn: String): Availability =
        try {
            Class.forName(fqcn)
            Availability(family, available = true, reason = null)
        } catch (e: ClassNotFoundException) {
            Availability(
                family = family,
                available = false,
                reason = "'$fqcn' is absent from the kernel classpath — the optional family activates " +
                    "only where 96 §E6/§E1.4/§E1.5 has landed it (ORA2 §WGT-06/BS-15); until then it " +
                    "is reported not-applicable, never skipped or stubbed.",
            )
        }
}
