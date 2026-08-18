package civictech.oracle.run

import civictech.cell.CellRef
import civictech.cell.data.CounterOps
import civictech.cell.data.KeyedSetOps
import civictech.cell.data.MapOps
import civictech.cell.data.PnCounterOps
import civictech.cell.data.SetOps
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.HostedCellProxy
import civictech.cell.link.LinkResult
import civictech.cell.port.Use
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.TerminalSpec
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ElementShape
import civictech.oracle.model.ModelNode
import civictech.oracle.model.NodeId
import civictech.oracle.model.OperatorModel
import civictech.oracle.model.ReferenceModel
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceModel
import civictech.testkit.SimWorld

/**
 * The three things a *generated* case needs before [DifferentialRunner]'s shared core can run
 * it: the catalog-resolved reference model, the live graph (spec applied, terminals folded,
 * sources bound), and the rendered spec a `[ORA1-DIFF-02]` report carries.
 *
 * ## Why catalog resolution lives here and not in the model
 *
 * `civictech.oracle.model` deliberately does not import `civictech.oracle.bind`: `bind`
 * already depends on `model` (for `ReferenceOp` and `ElementShape`), so the reverse edge would
 * be a package cycle, and the independence is also what makes the model a *check* on the
 * implementation rather than a second copy of it (epic computenet-4ru design D2). A
 * `CaseTopology` names catalog **ids**; something has to turn those into `ReferenceOp`
 * instances, and that something is the runner.
 *
 * ## Failure is loud and NAMES the id
 *
 * An id nothing is registered under, or an id whose registered model is the wrong
 * sub-interface for the arity its topology node uses, throws naming the id. It is never a
 * silent skip: a skipped operator is a green run that checked less than it claims, which is
 * the failure mode the whole epic is built to avoid (`[ORA1-API-02]`/`[ORA1-GEN-08]` make the
 * same argument at registration time).
 *
 * ## Why there IS a `when` over catalog ids here
 *
 * [scriptSourceFor] branches on the source families' ids, and that is not the branch
 * `[ORA1-API-03]` forbids. That requirement is about the *generator*: a consumer registering a
 * new operator must be picked up without generator edits, and `GraphGenerator` accordingly has
 * no id branch anywhere. Driving is different in kind — a script event has to become an actual
 * typed kernel call (`SetOps.add`, `CounterOps.increment`), and no shape rule carries that.
 * A consumer registering a new *operator* needs nothing here; only a new **source family**
 * does, and it gets a named refusal rather than silence until it is added.
 */
object CaseExecution {

    /**
     * [topology] resolved through [OperatorCatalog] into the model a differential run compares
     * against.
     *
     * Arity-0 nodes become [ModelNode.Source] (their entry's model must be a [SourceModel]);
     * every other node becomes [ModelNode.Operator] (an [OperatorModel]) whose inputs are the
     * topology's declared input order — which is `ShapeRule.inputs` port order, the order
     * [OperatorModel.evaluate] contracts on. Terminals become the name → [NodeId] map.
     *
     * @throws IllegalStateException naming the catalog id, if it is unregistered, or if its
     *   model is not the sub-interface its node's arity requires.
     * @throws IllegalArgumentException if an arity-0 node names no [SourceId] — a source cell
     *   with no script slice has no defined fold.
     */
    fun referenceModelFor(topology: CaseTopology): ReferenceModel {
        val nodes = topology.nodes.map { node ->
            val entry = OperatorCatalog.entry(node.catalogId)
                ?: error(
                    "Topology node '${node.handle}' names catalog id '${node.catalogId}', which " +
                        "is not registered in OperatorCatalog; registered ids are " +
                        "${OperatorCatalog.ids().sorted()}. An unresolvable id fails here rather " +
                        "than being skipped, because a skipped operator is a green run that " +
                        "checked less than it claims.",
                )
            if (entry.shape.arity == 0) {
                val model = entry.model as? SourceModel
                    ?: error(
                        "Catalog id '${node.catalogId}' (topology node '${node.handle}') is " +
                            "arity 0, so its registered model must be a SourceModel; it is a " +
                            "${entry.model::class.simpleName}.",
                    )
                val source = requireNotNull(node.source) {
                    "Topology node '${node.handle}' instantiates arity-0 catalog id " +
                        "'${node.catalogId}' but names no SourceId; a source with no script " +
                        "slice has no defined fold."
                }
                ModelNode.Source(NodeId(node.handle), source, model)
            } else {
                val model = entry.model as? OperatorModel
                    ?: error(
                        "Catalog id '${node.catalogId}' (topology node '${node.handle}') has " +
                            "arity ${entry.shape.arity}, so its registered model must be an " +
                            "OperatorModel; it is a ${entry.model::class.simpleName}.",
                    )
                ModelNode.Operator(NodeId(node.handle), model, node.inputs.map { NodeId(it) })
            }
        }
        return ReferenceModel(nodes, topology.terminals.associate { it.name to NodeId(it.handle) })
    }

    /**
     * [spec] rendered for a mismatch report `[ORA1-DIFF-02]`: one line per lowered step, in
     * apply order, with handles visible and — where [catalogIds] supplies them — the catalog id
     * each handle instantiates.
     *
     * A `SpawnStep` carries a `CellFactory`, not a catalog id, so the ids come from the
     * topology the spec was lowered from rather than from the spec itself.
     */
    fun renderSpec(spec: GraphSpec, catalogIds: Map<String, String> = emptyMap()): String =
        spec.lowered().joinToString("\n") { step ->
            when (step) {
                is SpawnStep -> {
                    val id = catalogIds[step.handle]
                    if (id == null) "spawn ${step.handle}" else "spawn ${step.handle} : $id"
                }

                is ConnectStep -> "connect ${step.from}.${step.outlet} -> ${step.to}.${step.inlet}"
                else -> step.toString()
            }
        }

    /**
     * Applies [case]'s spec onto [world]'s host, links a [TerminalFold] behind every eager
     * terminal, and binds every source node to a [ScriptSource].
     *
     * **Late terminals are skipped here.** A `TerminalSpec(late = true)` is linked only after a
     * mid-script barrier, which is the late-joiner sibling task's; this task treats the barrier
     * as a quiesce point and nothing more, so linking a late terminal eagerly would silently
     * turn it into an early one and make the sibling's requirement untestable.
     *
     * Everything is placed on the single [SimWorld] host regardless of
     * [CaseTopology.placement]: multi-host placement is the sibling task's too, and a topology
     * that asks for a second ordinal is refused rather than silently collapsed.
     */
    fun applyCase(case: GeneratedCase, world: SimWorld): CaseGraph = assemble(case, world).graph

    /**
     * Everything [applyCase] builds, plus the handle → [CellRef] map and the **mutable**
     * terminals map backing [CaseGraph.terminals] — needed by [linkLateTerminals] (BS-7,
     * `[ORA1-DIFF-05]`) to add a late terminal's fold after the mid-script
     * [civictech.oracle.gen.CaseStep.Barrier], without re-applying the spec (which would
     * double-spawn every cell) and without changing [CaseGraph]'s own shape.
     *
     * [CaseGraph.terminals] is typed `Map`, but the instance behind it here is exactly
     * [terminals] — the same `LinkedHashMap`, aliased, not copied — so mutating [terminals]
     * through this assembly is visible through [CaseGraph.terminals] too.
     */
    class CaseAssembly internal constructor(
        val graph: CaseGraph,
        internal val refs: Map<String, CellRef>,
        internal val terminals: MutableMap<String, TerminalFold>,
    )

    /**
     * [applyCase]'s full build: spawns every node ([GraphSpec.applyTo]), links a fold behind
     * every **eager** (non-late) terminal, and binds every source — same behavior as before,
     * just also handing back the pieces [linkLateTerminals] needs.
     */
    fun assemble(case: GeneratedCase, world: SimWorld): CaseAssembly {
        require(case.topology.placement.values.all { it == 0 }) {
            "Topology places handles on host ordinals " +
                "${case.topology.placement.values.distinct().sorted()}; this assembly path " +
                "only ever ran on one host, and collapsing a second ordinal onto it would " +
                "silently run a different case than the one asked for."
        }

        val refs = case.spec.applyTo(world.host.managementInlet)
        val nodes = case.topology.nodes.associateBy { it.handle }

        val terminals = LinkedHashMap<String, TerminalFold>()
        case.topology.terminals.filter { !it.late }.forEach { terminal ->
            terminals[terminal.name] = linkTerminal(terminal, nodes, refs, world)
        }

        val sources = LinkedHashMap<SourceId, ScriptSource>()
        case.topology.nodes.forEach { node ->
            val source = node.source ?: return@forEach
            val ref = refs[node.handle]
                ?: error("Source node '${node.handle}' was not spawned by the applied GraphSpec")
            sources[source] = scriptSourceFor(node.catalogId, node.handle, ref, world)
        }

        return CaseAssembly(
            graph = CaseGraph(terminals = terminals, sources = sources),
            refs = refs,
            terminals = terminals,
        )
    }

    /**
     * Links every [TerminalSpec.late] terminal not already linked — the driving loop calls
     * this exactly once, at [case]'s single [civictech.oracle.gen.CaseStep.Barrier], after
     * [assembly]'s graph has quiesced (BS-7, `[ORA1-DIFF-05]`; `[24-CATCHUP-01]`/
     * `[21-CATCHUP-02]`: a late link plus catch-up converges the late view to the early one).
     *
     * Linking before the barrier would silently turn the late terminal into an early one and
     * make the property untestable; linking here mirrors `GenerativeGraphTest`'s mid-run late
     * joiner (`kernel/src/test/kotlin/civictech/cell/verify/GenerativeGraphTest.kt`), which
     * spawns and connects the collector cell mid-script rather than at graph-build time.
     *
     * Idempotent: a terminal already present in [CaseAssembly.terminals] (already linked, by
     * this call or an earlier one) is skipped, so a script with more than one Barrier — none
     * exist today; `ScriptGenerator` emits at most one — would not double-link.
     */
    fun linkLateTerminals(case: GeneratedCase, world: SimWorld, assembly: CaseAssembly) {
        val nodes = case.topology.nodes.associateBy { it.handle }
        case.topology.terminals.filter { it.late }.forEach { terminal ->
            if (assembly.terminals.containsKey(terminal.name)) return@forEach
            assembly.terminals[terminal.name] = linkTerminal(terminal, nodes, assembly.refs, world)
        }
    }

    /** Spawns a [TerminalFold] matching [terminal]'s node's output shape and connects it. */
    private fun linkTerminal(
        terminal: TerminalSpec,
        nodes: Map<String, TopologyNode>,
        refs: Map<String, CellRef>,
        world: SimWorld,
    ): TerminalFold {
        val node = nodes[terminal.handle]
            ?: error("Terminal '${terminal.name}' reads handle '${terminal.handle}', which the topology does not declare")
        val entry = OperatorCatalog.entry(node.catalogId)
            ?: error("Terminal '${terminal.name}' reads node '${node.handle}', whose catalog id '${node.catalogId}' is not registered")
        val ref = refs[terminal.handle]
            ?: error("Terminal '${terminal.name}' reads handle '${terminal.handle}', which the applied GraphSpec did not spawn")

        val fold = foldFor(entry.shape.output, node.catalogId)
        world.host.managementInlet.call.spawn(fold)
        val result = world.host.managementInlet.call
            .connect(ref, entry.shape.outputPort, fold.ref, TERMINAL_INLET)
        check(result !is LinkResult.Rejected) {
            "Linking terminal '${terminal.name}' to ${node.handle}.${entry.shape.outputPort} " +
                "was rejected: ${(result as LinkResult.Rejected).reason}"
        }
        return fold
    }

    /** Every [TerminalFold] registers its inlet under this name (see `TerminalFold.kt`). */
    private const val TERMINAL_INLET = "inlet"

    /**
     * The fold that matches an operator's declared output shape — which is what decides the
     * delta family its outlet carries.
     *
     * `SetOf` → `SetDelta` → [SetTerminalFold]; `MapOf` → `MapDelta` → [MapTerminalFold];
     * `Scalar` → `CounterDelta` → [ScalarTerminalFold]. **The widths are not
     * interchangeable**: `count`'s scalar arrives as a `Long` while `presenceCount`'s map
     * values stay `Int`, and `ModelState`'s structural equality distinguishes
     * `ScalarState(2L)` from `ScalarState(2)`. Picking the fold by shape is what carries that
     * pinning through catalog resolution instead of re-deciding it per case.
     */
    private fun foldFor(output: ElementShape, catalogId: String): TerminalFold = when (output) {
        is ElementShape.SetOf -> SetTerminalFold<Any?>()
        is ElementShape.MapOf -> MapTerminalFold<Any?, Any?>()
        ElementShape.Scalar -> ScalarTerminalFold()
        is ElementShape.Tuple -> error(
            "Catalog id '$catalogId' declares output shape $output, which no kernel delta " +
                "family carries on its own; a tuple stream is observed as SetOf(Tuple(n)).",
        )
    }

    /** `SetCell`'s inlet, reached by name through a hosted invocation. */
    private interface SetInlet {
        val inlet: Use<SetOps<Any?>>
    }

    /** `KeyedSetCell`'s inlet. */
    private interface KeyedSetInlet {
        val inlet: Use<KeyedSetOps<Any?, Any?>>
    }

    /** `MapCell`'s inlet. */
    private interface MapInlet {
        val inlet: Use<MapOps<Any?, Any?>>
    }

    /** `CounterCell`'s inlet. */
    private interface CounterInlet {
        val inlet: Use<CounterOps>
    }

    /** `PnCounterCell`'s inlet. */
    private interface PnCounterInlet {
        val inlet: Use<PnCounterOps>
    }

    /**
     * A source node's kernel binding, reached through a **hosted proxy**.
     *
     * The proxy is not a stylistic choice. A direct inlet call on a co-hosted graph dispatches
     * inline and costs ZERO scheduler steps, so a case driven that way settles without the
     * controller ever running and every budget, partial-drain or non-quiescence assertion over
     * it passes vacuously — measured and reproduced on computenet-4ru.8.2. `HostedCellProxy`
     * routes through `ManagedHost.enqueueHostedInvocation`, so each op costs at least one step.
     *
     * @throws IllegalStateException naming the id, for a source family with no binding here.
     */
    private fun scriptSourceFor(
        catalogId: String,
        handle: String,
        ref: CellRef,
        world: SimWorld,
    ): ScriptSource = when (catalogId) {
        CoreOperators.Ids.SET -> {
            val ops = proxy(ref, world, SetInlet::class.java).inlet.call
            object : ScriptSource {
                override fun add(element: Any?) = ops.add(element)
                override fun remove(element: Any?) = ops.remove(element)
            }
        }

        CoreOperators.Ids.KEYED_SET -> {
            val ops = proxy(ref, world, KeyedSetInlet::class.java).inlet.call
            object : ScriptSource {
                override fun put(key: Any?, element: Any?) = ops.put(key, element)
                override fun removeKey(key: Any?) = ops.remove(key)
            }
        }

        CoreOperators.Ids.MAP -> {
            val ops = proxy(ref, world, MapInlet::class.java).inlet.call
            object : ScriptSource {
                override fun put(key: Any?, element: Any?) = ops.put(key, element)
                override fun removeKey(key: Any?) = ops.remove(key)
            }
        }

        CoreOperators.Ids.COUNTER -> {
            val ops = proxy(ref, world, CounterInlet::class.java).inlet.call
            object : ScriptSource {
                override fun increment(amount: Long) = ops.increment(amount)
                override fun decrement(amount: Long) = ops.decrement(amount)
            }
        }

        CoreOperators.Ids.PN_COUNTER -> {
            val ops = proxy(ref, world, PnCounterInlet::class.java).inlet.call
            object : ScriptSource {
                override fun increment(amount: Long) = ops.increment(amount)
                override fun decrement(amount: Long) = ops.decrement(amount)
            }
        }

        else -> error(
            "Source node '$handle' instantiates catalog id '$catalogId', for which the runner " +
                "has no script binding; a script event has to become a typed kernel call and no " +
                "ShapeRule carries that. Adding a source family is a change here, and a named " +
                "refusal beats a silently undriven source.",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(ref: CellRef, world: SimWorld, type: Class<T>): T =
        HostedCellProxy.create(ref, world.registry, type) as T
}
