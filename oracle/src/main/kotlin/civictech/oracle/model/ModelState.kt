package civictech.oracle.model

import java.io.Serializable

/**
 * What the reference model says a node *is*, at quiescence — the value a differential run
 * compares a kernel terminal's observable against.
 *
 * One variant per [ElementShape] family, because the shape vocabulary is what the generator
 * links graphs with: an edge whose shape is `SetOf(..)` carries a [SetState], a `MapOf(..)`
 * edge a [MapState], a `Scalar` edge a [ScalarState]. Keeping the two vocabularies in step is
 * what lets [ReferenceModel] type-check a graph description structurally rather than by
 * knowing which operator produced which node.
 *
 * Payloads are `Any?` for the same reason [ScriptEvent]'s are — see its KDoc. Every variant
 * is a `data class` over an immutable collection, so equality is structural and a state can
 * be compared, hashed, and asserted on directly. That structural equality is also
 * `ORA1 §MODEL-11`'s instrument: "two evaluations of the same script produce equal results"
 * is `eval(s) == eval(s)`, and nothing here holds identity.
 *
 * `ORA1 §MODEL-03` binds: a state records membership and value only. There is no variant
 * for a tag set, a tag count, or a wave id, and there deliberately never will be — a model
 * that could name a tag would stop being an independent check of the tag algebra.
 */
sealed interface ModelState : Serializable {

    /**
     * A set-shaped node: the elements that are **live**. For an OR-set source that is
     * [Membership.live] over its slice; for a derived operator it is a total recomputation
     * over its inputs' live sets, never an incremental mirror of the kernel's delta stream.
     */
    data class SetState(val elements: Set<Any?>) : ModelState {
        constructor(vararg elements: Any?) : this(elements.toSet())
    }

    /** A keyed node: the live key → value bindings. A key with no live value is ABSENT, never present with a stale or identity value (`[24-OP-GROUPBY-02]`). */
    data class MapState(val entries: Map<Any?, Any?>) : ModelState

    /** A single-valued node: a counter reading, a count, any aggregate that is not decomposable. */
    data class ScalarState(val value: Any?) : ModelState

    companion object {
        val EMPTY_SET: SetState = SetState(emptySet())
        val EMPTY_MAP: MapState = MapState(emptyMap())
    }
}

/**
 * [this] as a [ModelState.SetState], or a named failure.
 *
 * Every operator model starts by narrowing its inputs, and a mis-wired graph must say which
 * shape it got rather than throwing `ClassCastException` from inside a fold.
 */
fun ModelState.asSet(context: String): ModelState.SetState =
    this as? ModelState.SetState
        ?: error("$context expects a set-shaped input, got ${this::class.simpleName}: $this")

/** [this] as a [ModelState.MapState], or a named failure. See [asSet]. */
fun ModelState.asMap(context: String): ModelState.MapState =
    this as? ModelState.MapState
        ?: error("$context expects a map-shaped input, got ${this::class.simpleName}: $this")

/** [this] as a [ModelState.ScalarState], or a named failure. See [asSet]. */
fun ModelState.asScalar(context: String): ModelState.ScalarState =
    this as? ModelState.ScalarState
        ?: error("$context expects a scalar input, got ${this::class.simpleName}: $this")
