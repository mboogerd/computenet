package civictech.query.run.controls

import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TypedCellFactory
import civictech.query.lower.FlatMapFactory
import civictech.query.lower.GroupByFactory
import civictech.query.run.CompiledQuery

/*
 * Post-lowering rewrites that swap ONE spawn of a compiled spec for a deliberately wrong
 * test-scope cell (cab.6-D7, the `GatingEvidenceTest.withCapturedAntijoin` precedent). The
 * production `Lowering` grows no hook and no "wrong mode" flag (cab.6-D3): every rewrite
 * builds a NEW `GraphSpec` and a `CompiledQuery.copy`, leaving the receiver untouched.
 */

/**
 * A copy of this query whose spawn at [handle] builds [factory] instead. Fails unless exactly
 * one spawn step carries [handle]. The rest of the spec — handles, connects, identities — is
 * kept verbatim, so the substituted cell must register the same port names it replaces.
 */
fun CompiledQuery.withFactory(handle: String, factory: TypedCellFactory<*>): CompiledQuery {
    val steps = spec.lowered()
    val matches = steps.count { it is SpawnStep && it.handle == handle }
    check(matches == 1) {
        "withFactory: expected exactly one spawn with handle '$handle', found $matches; " +
            "spawns=${steps.filterIsInstance<SpawnStep>().map { it.handle }}"
    }
    return copy(
        spec = GraphSpec(
            steps.map { step -> if (step is SpawnStep && step.handle == handle) step.copy(factory = factory) else step },
        ),
    )
}

/** The single spawn under [root] whose factory is a [T]; fails on zero or several. */
private inline fun <reified T> CompiledQuery.singleSpawnOf(root: String): Pair<String, T> {
    val found = spec.lowered()
        .filterIsInstance<SpawnStep>()
        .filter { it.handle.startsWith("$root/") && it.factory is T }
    check(found.size == 1) {
        "expected exactly one ${T::class.simpleName} spawn under root '$root', found ${found.map { it.handle }}"
    }
    return found.single().handle to found.single().factory as T
}

/**
 * `[QRY1-ORA-07]`'s divergence control: [root]'s `Project` spawn (its one [FlatMapFactory])
 * replaced by a [LastWinsProjectCell] over the same `RowProjection`.
 */
fun CompiledQuery.lastWinsProjection(root: String): CompiledQuery {
    val (handle, factory) = singleSpawnOf<FlatMapFactory>(root)
    return withFactory(handle, LastWinsProjectFactory(factory.transform))
}

/**
 * `[QRY1-ORA-08]`'s mutation (BS-16): [root]'s [GroupByFactory] spawn replaced by a
 * [StickyGroupByCell] that never removes a dead group. COUNT only.
 */
fun CompiledQuery.stickyGroupBy(root: String): CompiledQuery {
    val (handle, factory) = singleSpawnOf<GroupByFactory>(root)
    return withFactory(handle, StickyGroupByFactory(factory.key, factory.spec))
}
