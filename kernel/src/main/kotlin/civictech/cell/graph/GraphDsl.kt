package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.nature.manifestOf
import civictech.cell.host.HostManagementApi
import civictech.cell.replication.Interest
import civictech.nature.Manifest
import civictech.cell.port.LinkResult
import civictech.cell.port.Port
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.identity
import java.io.Serializable
import java.util.UUID
import kotlin.random.Random

/**
 * Creates the cell for one spawn step, ref-aware (93 I-21 §4.1): the host (or,
 * for co-located replay, the applier) resolves an [IdentityBinding] to a
 * concrete [CellRef] *before* construction and hands it in, so the built
 * [Cell] always carries the ref the binding chose. Serializable so a recorded
 * [GraphSpec] is graphs-as-data (G-30) and the factory is the wire-crossing
 * construction form — a live cell never crosses the wire, only this.
 */
fun interface CellFactory : Serializable {
    fun create(ref: CellRef): Cell
}

/** [CellFactory] that remembers the concrete cell type — SAM-compatible with every existing `spawn { … }` lambda. */
fun interface TypedCellFactory<C : Cell> : CellFactory {
    override fun create(ref: CellRef): C
}

/**
 * Which [CellRef] a spawn step should produce (93 I-21 §4.1/4.2) — not a new
 * construction semantic, a choice of ref: `spawn` already takes a cell
 * carrying *some* ref; the binding only chooses *which* one.
 */
sealed interface IdentityBinding : Serializable {
    /** Mint a fresh `(logicalId, instanceId)` — the shipped replay-as-new-graph default. */
    data object FreshLogical : IdentityBinding

    /** Mint a fresh instanceId under a given logicalId — identity-preserving spawn
     * (a candidate version, or a deliberately seeded replica). */
    data class NewInstanceOf(val logicalId: UUID) : IdentityBinding

    /** Materialize a specific full ref — deterministic tests, migration targets, and
     * idempotent re-apply: re-applying an `Exact` spawn of a live ref hits the
     * live-ref spawn guard and rejects loudly (G-51). */
    data class Exact(val ref: CellRef) : IdentityBinding

    /**
     * Resolves this binding to a concrete [CellRef]. Shared by [ManagedHost][civictech.cell.host.ManagedHost]'s
     * `spawnBound` (host-side, for the wire form) and [GraphSpec.applyTo] (client-side, for
     * the co-located/local replay path) so both mint refs identically.
     *
     * instanceId minting for [NewInstanceOf] is a random `Long` — the same
     * birthday-bound argument G-57 already accepts for instanceId minting;
     * a caller-chosen collision discipline across hosts remains that gap's
     * open follow-up, not this ticket's.
     */
    fun resolve(): CellRef = when (this) {
        FreshLogical -> CellRef(UUID.randomUUID())
        is NewInstanceOf -> CellRef(logicalId, Random.nextLong())
        is Exact -> ref
    }
}

sealed interface GraphStep : Serializable

data class SpawnStep(
    val handle: String,
    val factory: CellFactory,
    val identity: IdentityBinding = IdentityBinding.FreshLogical,
    /** Spec-local handle of the parent, resolved to a [CellRef] at apply time
     * (organelle nesting, G-28) — never a step of its own (93 I-21 §4.3). */
    val parent: String? = null,
) : GraphStep

data class ConnectStep(val from: String, val outlet: String, val to: String, val inlet: String) : GraphStep

/**
 * PN-13 — one instance's declared slot in a heterogeneous instance set (spec
 * 40/42 §Interest-scoped instance sets, 51 §Graph construction DSL): the
 * [interest] it is assigned, plus placement/durability/frontier hints. Every
 * field is *data* — the DSL gains parameters, not verbs (51): the [interest] is
 * the formation assignment (PN-6 made it a management invocation; here it is
 * folded into construction), and a subsequent journaled *re*assignment is the
 * runtime counterpart, not a declaration.
 *
 * - [instanceId] the set-local ordinal — drives the spawn handle and the
 *   partition function; the actual `(logicalId, instanceId)` ref is minted fresh
 *   by [IdentityBinding.NewInstanceOf] on each replay (memberships/links are
 *   interest-determined, invariant to the minted ids).
 * - [placement] host-selector hint (recorded, routed by the multi-host replay
 *   driver — 93 I-15, driver unbuilt).
 * - [journalId] the journal a `DURABLE` instance binds to; `null` ⇒ a
 *   journal-less host, refused for a durable cell at declaration ([InstanceSetStep.validate]).
 * - [frontierPolicy] the frontier-policy hint for this instance.
 */
data class InstanceSpec(
    val interest: Interest,
    val instanceId: Int,
    val placement: String? = null,
    val journalId: String? = null,
    val frontierPolicy: String? = null,
) : Serializable

/**
 * PN-13 — builds the cell for one instance from its resolved [CellRef] and its
 * [InstanceSpec] (so the declared [InstanceSpec.interest] and hints are baked in
 * at construction). Serializable so a recorded [InstanceSetStep] is graphs-as-data.
 */
fun interface InstanceFactory : Serializable {
    fun build(ref: CellRef, spec: InstanceSpec): Cell
}

/**
 * PN-13 — the per-instance [CellFactory] an [InstanceSetStep] lowers to: a
 * *data class* over `(base, spec)`, so a lowered [SpawnStep] is structurally
 * `equals` to a hand-written one carrying the same base factory and spec — the
 * "parameters, not verbs" check (51). A raw lambda closure would defeat that
 * equality; this preserves it.
 */
data class InstanceCellFactory(val base: InstanceFactory, val spec: InstanceSpec) : CellFactory {
    override fun create(ref: CellRef): Cell = base.build(ref, spec)
}

/**
 * PN-13 — the composed-node declaration (spec 40/42, 51 §Graph construction DSL):
 * one [logicalId] and a heterogeneous set of [instances] (`instances =
 * f(interestPartition, replicationFactor)`), each carrying its own interest and
 * hints. It **lowers** ([lower]) to N × [SpawnStep] under
 * [IdentityBinding.NewInstanceOf] — nothing the host doesn't already accept (51:
 * "the DSL gains parameters, not verbs"); the N interest assignments are the
 * per-instance [InstanceCellFactory]s' construction-time formation assignments.
 *
 * Mis-compositions are refused at declaration ([validate], stricter than PN-12's
 * host-level soft count): partitioning a cell whose manifest lacks `PARTITIONED`
 * (a SINGLETON cell) is refused on the `INSTANCE_SCOPING` axis, and a `DURABLE`
 * cell declared journal-less is refused on the `DURABLE` nature.
 */
data class InstanceSetStep(
    val handle: String,
    val logicalId: UUID,
    val factory: InstanceFactory,
    val instances: List<InstanceSpec>,
) : GraphStep {

    /** The primitive steps this declaration lowers to — N identity-preserving spawns. */
    fun lower(): List<GraphStep> {
        validate()
        return instances.map { spec ->
            SpawnStep(
                handle = "$handle-${spec.instanceId}",
                factory = InstanceCellFactory(factory, spec),
                identity = IdentityBinding.NewInstanceOf(logicalId),
            )
        }
    }

    /**
     * Cold structural pre-validation (the 51/93 I-21 gap, scoped to this
     * declaration): a sample cell's [manifestOf] is read to refuse the two
     * mis-compositions the ticket names, each message naming the offending axis.
     */
    internal fun validate() {
        require(instances.isNotEmpty()) { "instance set '$handle': no instances declared" }
        val sample = factory.build(IdentityBinding.NewInstanceOf(logicalId).resolve(), instances.first())
        val manifest = manifestOf(sample.javaClass)
        // partitioning = a multi-instance set whose interests are not all Total
        // (a disjoint/partial assignment); replication (all-Total) is not.
        val partitioning = instances.size > 1 && instances.any { it.interest != Interest.Total }
        require(!(partitioning && Manifest.PARTITIONED !in manifest)) {
            "instance set '$handle': cannot partition a SINGLETON cell " +
                "${sample.javaClass.simpleName} (manifest $manifest lacks PARTITIONED) — " +
                "refused on the INSTANCE_SCOPING axis"
        }
        if (Manifest.DURABLE in manifest) {
            val journalless = instances.filter { it.journalId == null }.map { it.instanceId }
            require(journalless.isEmpty()) {
                "instance set '$handle': DURABLE cell ${sample.javaClass.simpleName} declared " +
                    "on a journal-less host (instances $journalless carry no journal id) — " +
                    "refused on the DURABLE nature"
            }
        }
    }
}

/**
 * Enforces that a spawned [Cell] carries the ref its [IdentityBinding] chose —
 * but only when the binding made an *explicit* choice ([IdentityBinding.NewInstanceOf]/
 * [IdentityBinding.Exact]): a factory ignoring the resolved ref there would silently
 * defeat identity-preserving spawn and the `Exact` idempotent-reject guard (93 I-21
 * §4.2). [IdentityBinding.FreshLogical] does not enforce this — "any fresh ref will
 * do" — so pre-existing zero-arg-style factories (`{ SetCell<String>() }`, which
 * mint their own default random ref) keep working unchanged.
 */
internal fun requireBoundRef(handle: String, identity: IdentityBinding, resolved: CellRef, built: CellRef) {
    if (identity == IdentityBinding.FreshLogical) return
    require(built == resolved) {
        "spawn step '$handle': factory must construct a cell with ref $resolved " +
            "(built $built) — identity binding $identity chooses the ref (93 I-21 §4.2)"
    }
}

/** The outcome of one [GraphStep] applied by [GraphSpec.applyRemote]. */
sealed interface StepResult : Serializable {
    data class Applied(val ref: CellRef?) : StepResult
    data class Rejected(val reason: String) : StepResult
}

/**
 * The eventual fold of a remote [GraphSpec] application (93 I-21 §4.4, G-51):
 * a structured per-step result an applier can inspect after [GraphSpec.applyRemote]
 * returns, keyed by the step's spec-local handle (spawn steps) or
 * `"from.outlet->to.inlet"` (connect steps).
 */
data class ApplyReport(val results: Map<String, StepResult>) : Serializable {
    val allApplied: Boolean get() = results.values.all { it is StepResult.Applied }
}

/**
 * A graph as data: an ordered step list, each lowering to a host-management
 * invocation — nothing the spec does is beyond `spawn`/`connect` (51). Replay
 * onto any host creates fresh cells (fresh refs) with the same topology by
 * default ([IdentityBinding.FreshLogical]); an explicit binding preserves or
 * targets a specific identity instead.
 */
data class GraphSpec(val steps: List<GraphStep>) : Serializable {

    /**
     * PN-13 — the primitive step list: every [InstanceSetStep] expanded to its
     * N × [SpawnStep] lowering, all other steps passed through. Apply and replay
     * run over this; the recorded [steps] keep the high-level declaration
     * (graphs-as-data), and the two agree by construction — the same [lower].
     */
    fun lowered(): List<GraphStep> =
        steps.flatMap { if (it is InstanceSetStep) it.lower() else listOf(it) }

    /**
     * Local, co-located replay (51 §Graph construction DSL): synchronous loud
     * failure, unchanged — the first rejected `connect` throws, and a `spawn`
     * whose resolved ref is already live throws too (the ordinary live-ref
     * spawn guard). Every step's [IdentityBinding] is resolved by the applier
     * before construction, so the wire-crossing factory shape is used
     * uniformly whether the target is local or (via [applyRemote]) remote.
     */
    fun applyTo(host: Use<HostManagementApi>): Map<String, CellRef> {
        val refs = mutableMapOf<String, CellRef>()
        lowered().forEach { step ->
            when (step) {
                is SpawnStep -> {
                    val ref = step.identity.resolve()
                    val cell = step.factory.create(ref)
                    requireBoundRef(step.handle, step.identity, ref, cell.ref)
                    refs[step.handle] = host.call.spawn(cell)
                }

                is ConnectStep -> {
                    val result = host.call.connect(
                        refs.getValue(step.from), step.outlet,
                        refs.getValue(step.to), step.inlet,
                    )
                    check(result !is LinkResult.Rejected) {
                        "link ${step.from}.${step.outlet} → ${step.to}.${step.inlet} rejected: " +
                            (result as LinkResult.Rejected).reason
                    }
                }

                // Unreachable: lowered() expands every InstanceSetStep to SpawnSteps.
                is InstanceSetStep -> error("InstanceSetStep must be lowered before apply")
            }
        }
        return refs
    }

    /**
     * Remote application (93 I-21 §4.4, G-51): every spawn step ships through
     * [HostManagementApi.spawnBound] — the factory-based wire form, never a
     * live [Cell]. Loud failure degrades from synchronous to asynchronous:
     * a rejected step does **not** abort the apply or throw to the caller —
     * "never a synchronous cross-wire reply" — it dead-letters on the target
     * host (observable via its `deadLetterOutlet`) and is folded into the
     * returned [ApplyReport] instead. Remaining steps still apply — this is
     * the decided **partial + report** semantics; compensating rollback of
     * the successful prefix (full partial-apply *atomicity*) is explicitly
     * research-gated (95 §R4) and is NOT implemented here.
     */
    fun applyRemote(host: Use<HostManagementApi>): ApplyReport {
        val refs = mutableMapOf<String, CellRef>()
        val results = mutableMapOf<String, StepResult>()
        lowered().forEach { step ->
            when (step) {
                is SpawnStep -> {
                    val parentRef = step.parent?.let { refs[it] }
                    try {
                        val ref = host.call.spawnBound(step.factory, step.identity, parentRef)
                        refs[step.handle] = ref
                        results[step.handle] = StepResult.Applied(ref)
                    } catch (e: Exception) {
                        // dead-lettered on the target host already (ManagedHost.spawnBound);
                        // here we only fold the outcome into the report, never rethrow —
                        // the wire form never surfaces a synchronous cross-wire reply.
                        results[step.handle] = StepResult.Rejected(e.message ?: e.toString())
                    }
                }

                is ConnectStep -> {
                    val key = "${step.from}.${step.outlet}->${step.to}.${step.inlet}"
                    val from = refs[step.from]
                    val to = refs[step.to]
                    if (from == null || to == null) {
                        results[key] = StepResult.Rejected(
                            "endpoint not constructed: '${step.from}' or '${step.to}' was rejected/missing",
                        )
                    } else {
                        try {
                            when (val result = host.call.connect(from, step.outlet, to, step.inlet)) {
                                is LinkResult.Rejected -> results[key] = StepResult.Rejected(result.reason)
                                else -> results[key] = StepResult.Applied(null)
                            }
                        } catch (e: Exception) {
                            results[key] = StepResult.Rejected(e.message ?: e.toString())
                        }
                    }
                }

                // Unreachable: lowered() expands every InstanceSetStep to SpawnSteps.
                is InstanceSetStep -> error("InstanceSetStep must be lowered before apply")
            }
        }
        return ApplyReport(results)
    }
}

open class CellHandle internal constructor(
    val name: String,
    val ref: CellRef,
    internal val builder: GraphBuilder,
) {
    /** Default-port link: `writer linkTo union` connects "outlet" → "inlet". */
    infix fun linkTo(target: CellHandle) = builder.connect(this, "outlet", target, "inlet")
}

/**
 * A [CellHandle] that keeps the locally-built instance — typed port access
 * for [GraphBuilder.link]. Local-apply only; a remote spawn has no instance
 * on this side.
 */
class TypedCellHandle<C : Cell> internal constructor(
    name: String,
    ref: CellRef,
    builder: GraphBuilder,
    val cell: C,
) : CellHandle(name, ref, builder)

/**
 * A thin veneer over the host protocol (G-30): every builder operation both
 * applies immediately through [host] and records into the [GraphSpec]. No new
 * semantics in the DSL layer, ever.
 */
class GraphBuilder internal constructor(private val host: Use<HostManagementApi>) {
    private val steps = mutableListOf<GraphStep>()
    private val names = mutableSetOf<String>()

    /** Spec-local handle by resolved [CellRef] — lets typed [link] recover the
     * handle name a port's owner was spawned under (typed-port-links). */
    private val handlesByRef = mutableMapOf<CellRef, CellHandle>()

    /**
     * @param identity which [CellRef] the spawned cell should carry (default: fresh).
     * @param parent the already-spawned handle this cell nests under (organelle
     *   nesting, G-28) — recorded on the step, not enforced by the DSL layer.
     */
    fun <C : Cell> spawn(
        name: String,
        identity: IdentityBinding = IdentityBinding.FreshLogical,
        parent: CellHandle? = null,
        factory: TypedCellFactory<C>,
    ): TypedCellHandle<C> {
        require(names.add(name)) { "duplicate handle '$name'" }
        val ref = identity.resolve()
        val cell = factory.create(ref)
        requireBoundRef(name, identity, ref, cell.ref)
        steps += SpawnStep(name, factory, identity, parent?.name)
        return TypedCellHandle(name, host.call.spawn(cell), this, cell)
            .also { handlesByRef[it.ref] = it }
    }

    /**
     * PN-13 — declare a heterogeneous instance set (spec 40/42, 51): records one
     * [InstanceSetStep] (graphs-as-data) and applies its [InstanceSetStep.lower]
     * spawns immediately, returning a handle per instance (in `instances` order).
     * Validation is loud at declaration; the recorded step re-lowers identically
     * on replay ([GraphSpec.lowered]).
     */
    fun instanceSet(
        handle: String,
        logicalId: UUID,
        factory: InstanceFactory,
        instances: List<InstanceSpec>,
    ): List<CellHandle> {
        val step = InstanceSetStep(handle, logicalId, factory, instances)
        val handles = step.lower().filterIsInstance<SpawnStep>().map { s ->
            require(names.add(s.handle)) { "duplicate handle '${s.handle}'" }
            val ref = s.identity.resolve()
            val cell = s.factory.create(ref)
            requireBoundRef(s.handle, s.identity, ref, cell.ref)
            CellHandle(s.handle, host.call.spawn(cell), this).also { handlesByRef[it.ref] = it }
        }
        steps += step
        return handles
    }

    /**
     * Brings an already-spawned, app-owned [cell] into the algebra as a
     * [CellHandle] so combinators (`filter`/`count`/`intersect`/`union`) can
     * chain off it. Unlike [spawn] this records **no** [SpawnStep] and issues
     * **no** host `spawn` — the cell already lives on the host, so re-spawning
     * would double it. The trade-off is deliberate: a [GraphSpec] whose chain
     * roots at an adopted handle is not self-contained for replay (the app owns
     * that cell's lifecycle); only the connects it participates in are recorded.
     * No new semantics — just a handle over an existing ref (G-30).
     */
    fun adopt(cell: Cell): CellHandle {
        val name = "adopted-${cell.ref.id}"
        require(names.add(name)) { "cell ${cell.ref} already adopted" }
        return CellHandle(name, cell.ref, this).also { handlesByRef[it.ref] = it }
    }

    fun connect(from: CellHandle, outlet: String, to: CellHandle, inlet: String) {
        val result = host.call.connect(from.ref, outlet, to.ref, inlet)
        check(result !is LinkResult.Rejected) {
            "link ${from.name}.$outlet → ${to.name}.$inlet rejected: ${(result as LinkResult.Rejected).reason}"
        }
        steps += ConnectStep(from.name, outlet, to.name, inlet)
    }

    /**
     * Typed overload of [connect] (typed-port-links, 05): connects two typed
     * port *objects*, recovering each port's `(ownerRef, name)` from its
     * [PortIdentity] and lowering onto the exact same [connect] call — the
     * recorded [ConnectStep] is byte-identical to the string form, so a graph
     * built with [link] and one built with [connect] replay identically. The
     * shared [Api] type parameter makes a payload mismatch or a wrong-direction
     * wiring a compile error (see [civictech.cell.host.link]).
     *
     * Port objects come from the [TypedCellHandle.cell] the builder keeps —
     * `link(a.cell.outlet, b.cell.inlet)` — so factories stay pure
     * (replay-safe) while wiring stays typed. Both ports must belong to cells
     * [spawn]ed on this builder; a port whose owner is unknown here (or
     * carries no identity) falls back to the string [connect].
     */
    fun <Api> link(out: Subscribe<Api>, inn: Serve<Api>) {
        val from = out.requireHandle("outlet")
        val to = inn.requireHandle("inlet")
        connect(from.first, from.second, to.first, to.second)
    }

    private fun Port.requireHandle(role: String): Pair<CellHandle, String> {
        val id = identity() ?: throw IllegalArgumentException(
            "link: the $role port carries no (ownerRef, name) identity — use connect(handle, name, ...) instead",
        )
        val handle = handlesByRef[id.owner] ?: throw IllegalArgumentException(
            "link: the $role port's owning cell ${id.owner} was not spawned on this builder — " +
                "use connect(handle, name, ...) instead",
        )
        return handle to id.name
    }

    internal fun spec() = GraphSpec(steps.toList())
}

/** Builds a graph on [host] and returns its replayable [GraphSpec]. */
fun graph(host: Use<HostManagementApi>, block: GraphBuilder.() -> Unit): GraphSpec =
    GraphBuilder(host).apply(block).spec()
