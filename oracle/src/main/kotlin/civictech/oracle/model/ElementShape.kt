package civictech.oracle.model

/**
 * The shape of the elements carried on one edge of a generated case — the vocabulary the
 * generator uses to decide which operator may consume which stream, so that no generated
 * graph can fail to link (`[ORA1-GEN-02]`).
 *
 * This is `civictech.oracle.model`, so `[ORA1-MODEL-10]` binds: nothing here may reference
 * a `civictech.cell.data.op` type or a concrete data-cell class. That is what keeps the
 * oracle an *independent* reference rather than a second copy of the implementation, and it
 * is why a shape is described structurally (a set of pairs) rather than by naming the cell
 * that produces it (`JoinSetCell`). The sibling task computenet-4ru.3.2 makes that rule a
 * failing test rather than a comment.
 *
 * It generalizes the fixed regimes concord's `ScenarioGenerator` enumerates
 * (`concord/src/main/kotlin/civictech/concord/generator/ScenarioGenerator.kt`, `enum class
 * Shape { INT_SET, PAIR_SET, TRIPLE_SET, MAP, SCALAR }`), which cannot express a set of
 * 4-tuples or a map whose values are sets without a new enum constant and a new branch in
 * every `when` over it. Composing instead of enumerating is what lets a consumer register an
 * operator over a shape nobody anticipated ([ORA1-API-03]). The five concord regimes are the
 * following instances, which is the whole of what is lost by the generalization — nothing:
 *
 * | concord regime | here                              |
 * |----------------|-----------------------------------|
 * | `INT_SET`      | `SetOf(Scalar)`                   |
 * | `PAIR_SET`     | `SetOf(Tuple(2))`                 |
 * | `TRIPLE_SET`   | `SetOf(Tuple(3))`                 |
 * | `MAP`          | `MapOf(Scalar, Scalar)`           |
 * | `SCALAR`       | `Scalar`                          |
 *
 * Deliberately structural and deliberately untyped in the element domain: a shape says a
 * stream carries pairs, not that it carries `Pair<Int, String>`. The generator picks the
 * element domain from its own `GeneratorConfig` (`[ORA1-GEN-04]`), and a shape that also
 * pinned the value type would make every registration a type-level negotiation with the
 * generator — exactly the generator edit [ORA1-API-03] exists to avoid.
 */
sealed interface ElementShape {

    /** A single value: a counter reading, an aggregate, any non-decomposable element. */
    data object Scalar : ElementShape

    /**
     * An element of [arity] positionally-addressed fields — concord's pair and triple
     * regimes, and any wider tuple a join or a flat-map produces.
     *
     * Arity 1 is [Scalar], not `Tuple(1)`: two spellings of one shape would make shape
     * equality — which is how the generator decides an edge is linkable — answer "no" to
     * two streams that are in fact compatible.
     */
    data class Tuple(val arity: Int) : ElementShape {
        init {
            require(arity >= 2) { "Tuple arity must be at least 2 (arity 1 is ElementShape.Scalar), got $arity" }
        }
    }

    /** A set-shaped stream of [element] — the OR-set family and everything downstream of it. */
    data class SetOf(val element: ElementShape) : ElementShape

    /** A keyed stream: [key] to [value] — the map/group-by family. */
    data class MapOf(val key: ElementShape, val value: ElementShape) : ElementShape

    /**
     * A keyed stream of [key] to [value], carried on the kernel's `TaggedMapDelta` rather than
     * `MapDelta` — `civictech.cell.data.OrMapCell`'s outlet and nothing else today
     * (computenet-880k).
     *
     * Deliberately a **separate variant from [MapOf], not the same shape with an extra flag**,
     * and deliberately **not equal to `MapOf(key, value)` for the same [key]/[value]** even
     * though both describe "a keyed stream of scalars to scalars" at the structural level this
     * type otherwise works at. The distinction this variant exists to carry is not structural —
     * it is which kernel delta type rides the wire, and the two are not interchangeable at a
     * link: `JoinCell`/`CombineLatestCell`/`LookupJoinCell`'s `inlet`s are typed to
     * `Propagate<MapDelta<K, V>>`, and connecting an `OrMapCell` outlet (`Propagate<TaggedMapDelta<K,
     * V>>`) to one is a genuine kernel type violation, not a legitimate generated edge — wiring
     * one produces a `ClassCastException` deep in delivery, not a compile error, because
     * `ShapeRule`/`GraphSpec` carry no generic parameter for `GraphGenerator.satisfiedBy` to
     * check against. Before this variant existed, `TaggedOperators`' `orMap` registered as
     * plain `MapOf(Scalar, Scalar)` — byte-identical to `CoreOperators`' `SCALAR_MAP` — so shape
     * equality wrongly declared the two link-compatible and `GraphGenerator` emitted the
     * illegal edge on 20/20 seeds (computenet-880k's measured repro). Keeping this as its own
     * variant, rather than a boolean or nominal tag on [MapOf], is what makes that
     * mis-unification structurally impossible again: a `when` or `==` over [ElementShape] sees
     * two distinct cases, the same way [SetOf] and [MapOf] themselves never conflate a set with
     * a map of the same element shape.
     */
    data class TaggedMapOf(val key: ElementShape, val value: ElementShape) : ElementShape
}
