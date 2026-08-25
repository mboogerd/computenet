package civictech.oracle.run

import civictech.cell.Cell
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
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.inlet
import civictech.cell.link.Linked
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.PortAddress
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
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
 *
 * [foldFor] carries the same exception for the same reason at the other end of the graph: a
 * `ShapeRule` states shapes, and no shape says which *delta type* an outlet emits, so the ids
 * whose delta family does not follow from their shape — the tagged family, which declares
 * `MapOf` and emits `TaggedMapDelta` — are named there. See its own KDoc.
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
     * Applies [case]'s spec across every host its [CaseTopology.placement] names, links a
     * [TerminalFold] behind every eager terminal, and binds every source node to a
     * [ScriptSource].
     *
     * **Late terminals are skipped here.** A `TerminalSpec(late = true)` is linked only after a
     * mid-script barrier, which is the late-joiner sibling task's; this task treats the barrier
     * as a quiesce point and nothing more, so linking a late terminal eagerly would silently
     * turn it into an early one and make the sibling's requirement untestable.
     */
    fun applyCase(case: GeneratedCase, world: SimWorld): CaseGraph = assemble(case, world).graph

    /**
     * Everything [applyCase] builds, plus the handle → [CellRef] map and the **mutable**
     * terminals map backing [CaseGraph.terminals] — needed by [linkLateTerminals] (BS-7,
     * `[ORA1-DIFF-05]`) to add a late terminal's fold after the mid-script
     * [civictech.oracle.gen.CaseStep.Barrier], without re-applying the spec (which would
     * double-spawn every cell) and without changing [CaseGraph]'s own shape. [hosts] (BS-9) is
     * every host ordinal [assemble] spawned onto — needed by [linkLateTerminals] to spawn a
     * late terminal's fold on the SAME host as the node it reads, exactly as [assemble] does
     * for an eager terminal.
     *
     * [CaseGraph.terminals] is typed `Map`, but the instance behind it here is exactly
     * [terminals] — the same `LinkedHashMap`, aliased, not copied — so mutating [terminals]
     * through this assembly is visible through [CaseGraph.terminals] too.
     */
    class CaseAssembly internal constructor(
        val graph: CaseGraph,
        internal val refs: Map<String, CellRef>,
        internal val terminals: MutableMap<String, TerminalFold>,
        internal val hosts: Map<Int, ManagedHost>,
        internal val placement: Map<String, Int>,
    )

    /**
     * [applyCase]'s full build (BS-9, `[ORA1-GEN-10]`'s runner half): one [ManagedHost] per
     * distinct ordinal [case]'s [CaseTopology.placement] names, all sharing [world]'s single
     * [civictech.cell.host.LocationRegistry] — ordinal `0` is [world]'s own host (so a
     * single-host case, every handle mapping to `0`, runs exactly as it always did), and every
     * other ordinal gets a fresh [ManagedHost] on [world]'s shared scheduler.
     *
     * Every [SpawnStep] lands on its handle's own host. A [ConnectStep] whose two ends share a
     * host connects the ordinary same-host way; one that crosses hosts gets **two** things: the
     * data path, a registry-resolved [civictech.cell.Propagate] handle to the target port
     * ([civictech.cell.host.inlet]) wrapped as a fixed [Use] for `ManagedHost.connect(from,
     * outlet, to: Use<*>)`, and — since computenet-vpiz — a real **bridged link pair** over the
     * same edge ([bridgeAcrossCut]), so the target inlet carries a link identity instead of
     * nothing. This REPLACES the former `require(placement.values.all { it == 0 })` guard: a
     * topology naming a second ordinal now runs on a second host instead of being refused.
     *
     * Then links a fold behind every **eager** (non-late) terminal — on the SAME host as the
     * node it reads, so that link is always same-host too — and binds every source.
     */
    fun assemble(case: GeneratedCase, world: SimWorld): CaseAssembly {
        val ordinals = (case.topology.placement.values.toSet() + 0).sorted()
        val hosts: Map<Int, ManagedHost> = ordinals.associateWith { ordinal ->
            if (ordinal == 0) {
                world.host
            } else {
                ManagedHost(scheduler = world.controller.scheduler(), registry = world.registry)
            }
        }
        fun hostFor(handle: String): ManagedHost = hosts.getValue(case.topology.placement[handle] ?: 0)

        val refs = mutableMapOf<String, CellRef>()
        val cells = mutableMapOf<String, Cell>()
        case.spec.lowered().forEach { step ->
            when (step) {
                is SpawnStep -> {
                    val ref = step.identity.resolve()
                    val cell = step.factory.create(ref)
                    cells[step.handle] = cell
                    refs[step.handle] = hostFor(step.handle).managementInlet.call.spawn(cell)
                }

                is ConnectStep -> {
                    val fromHost = hostFor(step.from)
                    val toHost = hostFor(step.to)
                    val fromRef = refs.getValue(step.from)
                    val toRef = refs.getValue(step.to)
                    if (fromHost === toHost) {
                        val result = fromHost.managementInlet.call
                            .connect(fromRef, step.outlet, toRef, step.inlet)
                        check(result !is LinkResult.Rejected) {
                            "connect ${step.from}.${step.outlet} -> ${step.to}.${step.inlet} " +
                                "rejected: ${(result as LinkResult.Rejected).reason}"
                        }
                    } else {
                        val sink = world.registry.inlet<Any>(toRef, step.inlet)
                        fromHost.managementInlet.call.connect(fromRef, step.outlet, Use.fixed(sink, PortRef.generate()))
                        bridgeAcrossCut(step, cells.getValue(step.from), cells.getValue(step.to), world.registry)
                    }
                }

                else -> error(
                    "GeneratedCase specs never carry an InstanceSetStep; case.spec.lowered() " +
                        "already expands one to plain SpawnSteps if it did.",
                )
            }
        }
        val nodes = case.topology.nodes.associateBy { it.handle }

        val terminals = LinkedHashMap<String, TerminalFold>()
        case.topology.terminals.filter { !it.late }.forEach { terminal ->
            terminals[terminal.name] = linkTerminal(terminal, nodes, refs, ::hostFor)
        }

        val sources = LinkedHashMap<SourceId, ScriptSource>()
        case.topology.nodes.forEach { node ->
            val source = node.source ?: return@forEach
            val ref = refs[node.handle]
                ?: error("Source node '${node.handle}' was not spawned by the applied GraphSpec")
            sources[source] = scriptSourceFor(node.catalogId, node.handle, ref, world)
        }

        val extraHosts = hosts.filterKeys { it != 0 }.values.toList()

        return CaseAssembly(
            graph = CaseGraph(terminals = terminals, sources = sources, extraHosts = extraHosts),
            refs = refs,
            terminals = terminals,
            hosts = hosts,
            placement = case.topology.placement,
        )
    }

    /**
     * Wires a cross-host [ConnectStep] as a **real bridged link pair** (computenet-vpiz,
     * `[22-GF-03]`), on top of the data-plane handle [assemble] already issues.
     *
     * ## What this replaces, and why it had to
     *
     * Before this, a cross-host edge was *only* that data handle — a bare `Propagate` resolved
     * from the [LocationRegistry], wrapped in [Use.fixed], issued on the SOURCE host — so the
     * **target inlet registered no link at all**: 1 link for a same-host connect, 0 across the
     * cut, measured on otherwise identical cells (`CaseExecutionTest`, re-deriving
     * computenet-g25w). Link identity is what every frontier bookkeeping is keyed by:
     * [civictech.cell.consistency.WaveFrontier] folds its edge set from per-link
     * `EdgeOpen`/`EdgeClose`, and watermarks, `Progress(thru)` absorb-acks and stall markers all
     * travel per-link. A wave-frontier join fed across that cut would have folded its
     * completeness condition over an edge set *missing the cross-host arm* — the opposite of
     * `[22-GF-03]`. computenet-xj0v shipped a named refusal as an interim tripwire; this is the
     * bridge that refusal stood in for, so the refusal is gone.
     *
     * ## Why no bridge *cells* are needed here
     *
     * [civictech.cell.wire.BridgeEgressCell]/[civictech.cell.wire.BridgeIngressCell] exist to
     * turn an invocation into bytes and back across a real transport. A [SimWorld]'s hosts are
     * one process sharing **one** [LocationRegistry], and `LocationRegistry::deliver` is already
     * an [InvocationSink] that routes a [civictech.cell.proxy.HostedPortInvocation] to whichever
     * host owns the ref — so it is the egress for both halves, and protocol frames reach the
     * peer endpoint without a codec round trip. That is the only simplification against
     * `kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeBridgedDiamondTest.kt`, which
     * builds this same `[22-GF-03]` shape from `:kernel` alone; the link types, the handshake
     * and the `EdgeOpen` path are identical. **What is therefore NOT exercised here is the wire
     * codec**: this harness pins cross-host *link identity and frontier bookkeeping*, not frame
     * encoding, and a genuinely serialized cut would need the bridge cell pair.
     *
     * ## The pair, and which half does what
     *
     * [bridgeTo] on the producer's outlet registers a [civictech.cell.wire.WireEdgeLink] on the
     * outlet's own bookkeeping and fires `EdgeOpen` across [sink], landing on the consumer
     * inlet's real port through the ordinary `PORT_PROTOCOL` delivery path. [bridgeFrom] on the
     * consumer's inlet registers the reverse half, so an upstream emission walking
     * `inlet.linking.links` finds an edge to route back over — and so the inlet reports the
     * arm at all. Data still rides the [Use.fixed] handle [assemble] issues: a bridged link
     * carries protocol frames, not payloads (its `toPort` is null on the producer side), which
     * is exactly the split `GlitchFreeBridgedDiamondTest` uses.
     *
     * Both halves go through the shared `handshake`, so inlet link policies and the peer
     * allowlist fire on this edge as on a local one — a [LinkResult.Rejected] here is a real
     * refusal and is raised rather than swallowed.
     *
     * @throws IllegalStateException if either endpoint port is missing or not a linkable port,
     *   or if either half of the handshake is rejected.
     */
    private fun bridgeAcrossCut(step: ConnectStep, fromCell: Cell, toCell: Cell, registry: LocationRegistry) {
        val outlet = portOf<FanOutlet<*>>(fromCell, step.outlet, step, "source outlet")
        val inlet = portOf<FanInlet<*>>(toCell, step.inlet, step, "target inlet")
        val egress = InvocationSink(registry::deliver)
        val fromAddr = PortAddress(fromCell.ref, step.outlet)
        val toAddr = PortAddress(toCell.ref, step.inlet)

        val producerHalf = outlet.bridgeTo(selfAddr = fromAddr, toAddr = toAddr, sink = egress)
        check(producerHalf !is LinkResult.Rejected) {
            "bridging ${step.from}.${step.outlet} -> ${step.to}.${step.inlet} across a host cut " +
                "was rejected on the producer half: ${(producerHalf as LinkResult.Rejected).reason}"
        }
        val consumerHalf = inlet.bridgeFrom(selfAddr = toAddr, fromAddr = fromAddr, sink = egress)
        check(consumerHalf !is LinkResult.Rejected) {
            "bridging ${step.from}.${step.outlet} -> ${step.to}.${step.inlet} across a host cut " +
                "was rejected on the consumer half: ${(consumerHalf as LinkResult.Rejected).reason}"
        }
    }

    /**
     * [cell]'s port named [port], as the `Linked & Port` receiver [bridgeTo]/[bridgeFrom]
     * require — a named failure rather than a raw `ClassCastException`, because a handle naming
     * a port the cell does not register, or registers with the wrong fan direction, is a
     * spec-lowering bug and should say which.
     *
     * `T` is supplied explicitly at both call sites ([FanOutlet] for the producer half,
     * [FanInlet] for the consumer half); it cannot be inferred, since the receiver constraint is
     * an intersection Kotlin has no return type for.
     */
    private inline fun <reified T> portOf(cell: Cell, port: String, step: ConnectStep, role: String): T
        where T : Linked, T : Port {
        val resolved = PortRegistry.of(cell)[port]
            ?: error(
                "connect ${step.from}.${step.outlet} -> ${step.to}.${step.inlet} names $role " +
                    "'$port', which cell ${cell.ref} does not register; its ports are " +
                    "${PortRegistry.of(cell).names()}.",
            )
        return resolved as? T
            ?: error(
                "connect ${step.from}.${step.outlet} -> ${step.to}.${step.inlet} names $role " +
                    "'$port', which is a ${resolved::class.simpleName} and not a " +
                    "${T::class.simpleName}, so it cannot carry a bridged link across a host cut.",
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
        fun hostFor(handle: String): ManagedHost = assembly.hosts.getValue(assembly.placement[handle] ?: 0)
        case.topology.terminals.filter { it.late }.forEach { terminal ->
            if (assembly.terminals.containsKey(terminal.name)) return@forEach
            assembly.terminals[terminal.name] = linkTerminal(terminal, nodes, assembly.refs, ::hostFor)
        }
    }

    /**
     * Spawns a [TerminalFold] matching [terminal]'s node's output shape ON THE SAME HOST as
     * that node — [hostFor] is [assemble]'s placement-derived lookup — and connects it there,
     * so the link is always same-host regardless of which ordinal the node itself landed on.
     */
    private fun linkTerminal(
        terminal: TerminalSpec,
        nodes: Map<String, TopologyNode>,
        refs: Map<String, CellRef>,
        hostFor: (String) -> ManagedHost,
    ): TerminalFold {
        val node = nodes[terminal.handle]
            ?: error("Terminal '${terminal.name}' reads handle '${terminal.handle}', which the topology does not declare")
        val entry = OperatorCatalog.entry(node.catalogId)
            ?: error("Terminal '${terminal.name}' reads node '${node.handle}', whose catalog id '${node.catalogId}' is not registered")
        val ref = refs[terminal.handle]
            ?: error("Terminal '${terminal.name}' reads handle '${terminal.handle}', which the applied GraphSpec did not spawn")
        val host = hostFor(terminal.handle)

        val fold = foldFor(entry.shape.output, node.catalogId)
        host.managementInlet.call.spawn(fold)
        val result = host.managementInlet.call
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
     * The fold that matches the delta family [catalogId]'s outlet actually carries.
     *
     * ## Shape decides it, EXCEPT where two families share a shape
     *
     * For every untagged entry the declared output shape decides the delta family, and picking
     * the fold from the shape is what carries the *width* pinning through catalog resolution
     * instead of re-deciding it per case: `SetOf` → `SetDelta` → [SetTerminalFold]; `MapOf` →
     * `MapDelta` → [MapTerminalFold]; `Scalar` → `CounterDelta` → [ScalarTerminalFold]. The
     * widths are not interchangeable — `count`'s scalar arrives as a `Long` while
     * `presenceCount`'s map values stay `Int`, and `ModelState`'s structural equality
     * distinguishes `ScalarState(2L)` from `ScalarState(2)`.
     *
     * The **tagged** family breaks that correspondence, and this is where computenet-6v7y found
     * it. `orMap` declares `MapOf` — correctly: what a caller reads out of an OR-map IS a map —
     * but `OrMapCell.outlet` carries a
     * [civictech.cell.data.delta.TaggedMapDelta], not a `MapDelta`. Shape alone therefore
     * resolved it to [MapTerminalFold], and two things followed: the fold's inlet could not
     * even accept the stream (a `ClassCastException` per delta, arriving as a dead letter), and
     * had it been able to, [civictech.cell.data.view.MapView] would have folded by **arrival
     * order** — which is `ORA2 §CTL-01`'s deliberately-wrong control, not the reading
     * `[24-TMAP-03]` defines. So the tagged ids are named here, ahead of the shape dispatch.
     *
     * `pnCounter` (computenet-f5zo) is the same defect one family over: it declares a bare
     * `Scalar` output — correctly, same as `counter` — but `PnCounterCell.outlet` carries a
     * [civictech.cell.data.delta.PnCounterDelta], not a `CounterDelta`. Shape alone resolved it
     * to [ScalarTerminalFold], whose inlet cannot accept the stream (the same per-delta
     * `ClassCastException`, dead-lettered) and which, had it been able to, would have SUMMED
     * arriving amounts — not idempotent, so a gossip echo double-counts — where
     * [PnCounterTerminalFold] merges by pointwise max, the reading `[24-OP-PNCOUNTER-01]`
     * defines. So `pnCounter` is named here too, ahead of the shape dispatch.
     *
     * ## Why an id branch, and why it is not `[ORA1-API-03]`'s forbidden one
     *
     * The same argument [scriptSourceFor] carries, one seam over: a `ShapeRule` states shapes,
     * and no shape says which delta type an outlet emits. `[ORA1-API-03]` is about the
     * *generator* — a consumer registering a new operator must be picked up without generator
     * edits, and `GraphGenerator` has no id branch anywhere. Registering a new operator over an
     * existing delta family still needs nothing here; only a new **delta family** does, and
     * adding one is a change at this seam either way.
     */
    private fun foldFor(output: ElementShape, catalogId: String): TerminalFold = when (catalogId) {
        TaggedOperators.Ids.OR_MAP -> TaggedMapTerminalFold<Any?, Any?>()
        CoreOperators.Ids.PN_COUNTER -> PnCounterTerminalFold()

        else -> when (output) {
            is ElementShape.SetOf -> SetTerminalFold<Any?>()
            is ElementShape.MapOf -> MapTerminalFold<Any?, Any?>()
            ElementShape.Scalar -> ScalarTerminalFold()
            is ElementShape.Tuple -> error(
                "Catalog id '$catalogId' declares output shape $output, which no kernel delta " +
                    "family carries on its own; a tuple stream is observed as SetOf(Tuple(n)).",
            )
            // Unreachable via this path today (computenet-880k): the only registration whose
            // output is `ElementShape.TaggedMapOf` is `orMap`, and the `TaggedOperators.Ids.OR_MAP`
            // branch above already claims it before this `when` is ever reached. Kept as a named
            // error rather than folded into an `else`, matching the `Tuple` arm right above it —
            // a future TaggedMapOf-shaped registration that reaches here by some other id needs a
            // real `TerminalFold` for `TaggedMapDelta`, which does not exist yet.
            is ElementShape.TaggedMapOf -> error(
                "Catalog id '$catalogId' declares output shape $output (TaggedMapOf), which only " +
                    "has a TerminalFold via the TaggedOperators.Ids.OR_MAP id branch above; no " +
                    "shape-only TaggedMapDelta fold exists.",
            )
        }
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

        /* `OrMapCell.inlet` is `Use<MapOps<K, V>>` — the SAME `@Contract` `MapCell` exposes,
         * verbatim (`OrMapApi`'s own KDoc: "the tagged map is a new convergence semantics for the
         * same keyed-write vocabulary, not a new vocabulary"). So the driving surface is
         * `MapInlet` and the branch is the MAP one; what differs is downstream, in the delta the
         * outlet carries and therefore in the fold [foldFor] picks. Sharing the branch is the
         * honest encoding of that: two ids, one ops surface. */
        TaggedOperators.Ids.OR_MAP -> {
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
