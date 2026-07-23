package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.HostManagementApi
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
     * Local, co-located replay (51 §Graph construction DSL): synchronous loud
     * failure, unchanged — the first rejected `connect` throws, and a `spawn`
     * whose resolved ref is already live throws too (the ordinary live-ref
     * spawn guard). Every step's [IdentityBinding] is resolved by the applier
     * before construction, so the wire-crossing factory shape is used
     * uniformly whether the target is local or (via [applyRemote]) remote.
     */
    fun applyTo(host: Use<HostManagementApi>): Map<String, CellRef> {
        val refs = mutableMapOf<String, CellRef>()
        steps.forEach { step ->
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
        steps.forEach { step ->
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
            }
        }
        return ApplyReport(results)
    }
}

class CellHandle internal constructor(
    val name: String,
    val ref: CellRef,
    private val builder: GraphBuilder,
) {
    /** Default-port link: `writer linkTo union` connects "outlet" → "inlet". */
    infix fun linkTo(target: CellHandle) = builder.connect(this, "outlet", target, "inlet")
}

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
    fun spawn(
        name: String,
        identity: IdentityBinding = IdentityBinding.FreshLogical,
        parent: CellHandle? = null,
        factory: CellFactory,
    ): CellHandle {
        require(names.add(name)) { "duplicate handle '$name'" }
        val ref = identity.resolve()
        val cell = factory.create(ref)
        requireBoundRef(name, identity, ref, cell.ref)
        steps += SpawnStep(name, factory, identity, parent?.name)
        return CellHandle(name, host.call.spawn(cell), this).also { handlesByRef[it.ref] = it }
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
     * Both ports must belong to cells [spawn]ed on this builder; a port whose
     * owner is unknown here (or carries no identity) falls back to the string
     * [connect].
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
