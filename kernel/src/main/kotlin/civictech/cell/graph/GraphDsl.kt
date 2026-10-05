package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Gossiping
import civictech.cell.evolve.Effectful
import civictech.cell.evolve.Shadow
import civictech.cell.nature.manifestOf
import civictech.cell.host.HostManagementApi
import civictech.cell.host.KeyedCells
import civictech.cell.host.DurableInput
import civictech.cell.link.Interest
import civictech.cell.link.Link
import civictech.cell.link.LinkOptions
import civictech.nature.Manifest
import civictech.cell.link.LinkResult
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
 * The key encoding and decoding used when a [KeyedFamily] renders and parses
 * its `TopoEvent.FamilyKey` topology record in the selected journal. A
 * [GraphSpec] is serialized whole, so a custom codec's lambdas must be
 * `@JvmSerializableLambda` (as the built-in codecs' are).
 */
class KeyCodec(
    val render: (Any) -> String,
    val parse: (String) -> Any,
) : Serializable {
    companion object {
        /** The default codec for string keys. */
        val Strings = KeyCodec(@JvmSerializableLambda { it as String }, @JvmSerializableLambda { it })

        /** A decimal codec for long keys. */
        val Longs = KeyCodec(@JvmSerializableLambda { (it as Long).toString() }, @JvmSerializableLambda { it.toLong() })
    }
}

/** The declarative parameters for a lazily-spawned keyed cell family. */
data class KeyedFamily(
    val namespace: String,
    val keys: KeyCodec = KeyCodec.Strings,
    val journalId: String? = null,
) : Serializable

/** A cell factory whose construction also receives the family key. */
fun interface KeyedCellFactory : CellFactory {
    fun create(key: Any, ref: CellRef): Cell

    override fun create(ref: CellRef): Cell =
        throw UnsupportedOperationException("keyed family factory needs a key")
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
    /** Spawn through [civictech.cell.replication.Replication] rather than directly on a host. */
    val replicated: Boolean = false,
    /** Journal handle resolved by [ApplyContext] before this cell is spawned. */
    val journalId: String? = null,
    /** Apply [Shadow]'s effect suppression after this cell is spawned. */
    val shadow: Boolean = false,
    /** Lazily-spawned keyed family parameters; a family handle has no single cell ref. */
    val family: KeyedFamily? = null,
    /** Named durable inputs exposed for this journaled cell after application. */
    val inputs: Set<String> = emptySet(),
    /**
     * Placement selector resolved by the runtime placement driver against
     * `Manifest.placements` (computenet-8k723); null reads as `default`. Not
     * journaled: a node journals only the steps it applied.
     */
    val placement: String? = null,
) : GraphStep {
    init {
        if (family != null) {
            require(factory is KeyedCellFactory) {
                "spawn step '$handle': parameter 'family' requires a KeyedCellFactory"
            }
        }
        require(inputs.isEmpty() || journalId != null) {
            "spawn step '$handle': parameter 'inputs' requires 'journalId'"
        }
        require(inputs.isEmpty() || family == null) {
            "spawn step '$handle': a keyed family cannot declare inputs"
        }
    }
}

data class ConnectStep(
    val from: String,
    val outlet: String,
    val to: String,
    val inlet: String,
    val options: LinkOptions = LinkOptions.DEFAULT,
) : GraphStep

/** Detaches the link admitted by an earlier [ConnectStep] with the same edge key. */
data class UnlinkStep(val from: String, val outlet: String, val to: String, val inlet: String) : GraphStep

/** Unlinks every live edge touching [handle], then removes that cell and frees its handle. */
data class DespawnStep(val handle: String) : GraphStep

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
 * - [placement] host-selector hint, threaded into the lowered [SpawnStep.placement]
 *   and resolved by the runtime placement driver against the manifest (computenet-8k723).
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
    val replicated: Boolean = false,
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
                replicated = spec.replicated,
                journalId = spec.journalId,
                placement = spec.placement,
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
 * `"from.outlet->to.inlet"` (connect steps), or
 * `"unlink from.outlet->to.inlet"` (unlink steps).
 */
data class ApplyReport(val results: Map<String, StepResult>) : Serializable {
    val allApplied: Boolean get() = results.values.all { it is StepResult.Applied }
}

/**
 * A graph as data: an ordered step list, each lowering to a host-management
 * invocation — nothing the spec does is beyond `spawn`/`connect`/`Link.unlink()` (51). Replay
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
     * Local parameter-aware application. The complete lowered delta is resolved
     * before the first host operation and journaled write-ahead when the context
     * owns a topology journal. Replicated factories are prepared before the
     * append so their type can be validated; ordinary factories run after it,
     * making a construction failure recoverably loud rather than unrecorded.
     */
    fun apply(context: ApplyContext): AppliedGraph {
        val lowered = lowered()
        lowered.filterIsInstance<SpawnStep>()
            .firstOrNull { it.replicated && context.replication == null }
            ?.let { throw missingReplication(it.handle) }
        lowered.filterIsInstance<SpawnStep>()
            .firstOrNull { step ->
                step.journalId != null && step.journalId !in context.journals
            }
            ?.let { step ->
                throw missingJournal(step.handle, step.journalId!!)
            }
        lowered.filterIsInstance<SpawnStep>()
            .firstOrNull { step ->
                val journalId = step.family?.journalId
                journalId != null && journalId !in context.journalDirs
            }
            ?.let { step ->
                throw missingFamilyJournal(step.handle, step.family!!.journalId!!)
            }

        // Resolve the complete delta to concrete refs before the first host operation. This
        // validates every duplicate/unknown handle before journaling, while factories remain
        // on the apply side of the write-ahead append (a throwing factory still leaves the
        // delta durable for loud recovery).
        val active = context.handles.toMutableMap()
        val occupied = (context.handles.keys + context.live().families.keys).toMutableSet()
        val familyHandles = context.live().families.keys.toMutableSet()
        val events = ArrayList<TopoEvent>(lowered.size)
        val preparedReplicas = mutableMapOf<Int, Cell>()
        val displayKeys = mutableMapOf<Int, String>()
        fun resolve(handle: String): CellRef = active[handle]
            ?: throw IllegalStateException("unknown handle '$handle'")
        lowered.forEachIndexed { index, step ->
            when (step) {
                is SpawnStep -> {
                    check(occupied.add(step.handle)) { "duplicate handle '${step.handle}'" }
                    if (step.family != null) {
                        familyHandles += step.handle
                        events += TopoEvent.Family(
                            step.handle,
                            step.family,
                            step.factory as KeyedCellFactory,
                        )
                    } else {
                        val ref = step.identity.resolve()
                        val event = TopoEvent.Spawn(
                            step.handle,
                            ref,
                            step.factory,
                            step.parent?.let(::resolve),
                            step.replicated,
                            step.journalId,
                            step.shadow,
                        )
                        active[step.handle] = ref
                        events += event
                        if (step.replicated) {
                            val cell = step.factory.create(ref)
                            requireBoundRef(step.handle, step.identity, ref, cell.ref)
                            if (cell !is Gossiping<*>) {
                                throw IllegalStateException(
                                    "spawn step '${step.handle}': parameter 'replicated' requires a replicable (Gossiping) cell " +
                                        "(built ${cell.javaClass.name})",
                                )
                            }
                            cell.replicationRefusal?.let { throw IllegalStateException(it) }
                            preparedReplicas[index] = cell
                        }
                    }
                }

                is ConnectStep -> {
                    val key = stepKey(step)
                    if (step.from in familyHandles) throw familyLinkRefusal(step.from, key)
                    if (step.to in familyHandles) throw familyLinkRefusal(step.to, key)
                    displayKeys[index] = key
                    events += TopoEvent.Connect(
                        resolve(step.from), step.outlet, resolve(step.to), step.inlet, step.options,
                    )
                }

                is UnlinkStep -> {
                    val key = stepKey(step)
                    if (step.from in familyHandles) throw familyLinkRefusal(step.from, key)
                    if (step.to in familyHandles) throw familyLinkRefusal(step.to, key)
                    displayKeys[index] = key
                    events += TopoEvent.Unlink(
                        resolve(step.from), step.outlet, resolve(step.to), step.inlet,
                    )
                }

                is DespawnStep -> {
                    val ref = active.remove(step.handle)
                        ?: throw IllegalStateException("unknown handle '${step.handle}'")
                    occupied.remove(step.handle)
                    events += TopoEvent.Despawn(ref)
                }

                is InstanceSetStep -> error("InstanceSetStep must be lowered before apply")
            }
        }

        context.journalTopology(events)

        val refs = mutableMapOf<String, CellRef>()
        val families = mutableMapOf<String, KeyedCells<*>>()
        val inputs = mutableMapOf<String, Map<String, DurableInput>>()
        val deltaLinks = linkedMapOf<String, TopologyLinkKey>()
        events.forEachIndexed { index, event ->
            when (event) {
                is TopoEvent.Spawn -> {
                    val ref = context.applySpawn(event, preparedReplicas[index])
                    refs[event.handle] = ref
                    val step = lowered[index] as SpawnStep
                    if (step.inputs.isNotEmpty()) {
                        inputs[event.handle] = step.inputs.associateWith { name ->
                            context.host.durableInput(ref, name)
                        }
                    }
                }
                is TopoEvent.Family -> {
                    context.apply(event)
                    families[event.handle] = checkNotNull(context.familyFor(event.handle))
                }
                is TopoEvent.Connect -> {
                    context.applyConnect(event)
                    deltaLinks[displayKeys[index] ?: stepKey(event)] = TopologyLinkKey.of(event)
                }
                is TopoEvent.Unlink -> {
                    context.applyUnlink(event)
                    deltaLinks.remove(displayKeys[index] ?: stepKey(event))
                }
                is TopoEvent.Despawn -> {
                    context.applyDespawn(event)
                    refs.entries.removeIf { it.value == event.ref }
                    deltaLinks.entries.removeIf { (_, key) -> key.from == event.ref || key.to == event.ref }
                }
                is TopoEvent.FamilyKey -> error("GraphSpec does not emit FamilyKey directly")
            }
        }
        val links = deltaLinks.mapNotNull { (key, topologyKey) ->
            context.linkFor(topologyKey)?.let { key to it }
        }.toMap()
        return AppliedGraph(refs.toMap(), families.toMap(), links, inputs.toMap())
    }

    /**
     * Local, co-located replay (51 §Graph construction DSL): synchronous loud
     * failure, unchanged — the first rejected `connect` throws, and a `spawn`
     * whose resolved ref is already live throws too (the ordinary live-ref
     * spawn guard). Every step's [IdentityBinding] is resolved by the applier
     * before construction, so the wire-crossing factory shape is used
     * uniformly whether the target is local or (via [applyRemote]) remote.
     */
    fun applyTo(host: Use<HostManagementApi>): Map<String, CellRef> {
        val lowered = lowered()
        lowered.filterIsInstance<SpawnStep>().firstOrNull { it.family != null }?.let { step ->
            throw unsupportedFamily(step.handle, "applyTo(Use<HostManagementApi>)")
        }
        lowered.filterIsInstance<SpawnStep>().firstOrNull { it.inputs.isNotEmpty() }?.let { step ->
            throw unsupportedInputs(step.handle, "applyTo(Use<HostManagementApi>)")
        }
        lowered.filterIsInstance<SpawnStep>().firstOrNull { it.replicated }?.let { step ->
            throw unsupportedReplication(step.handle, "applyTo(Use<HostManagementApi>)")
        }
        lowered.filterIsInstance<SpawnStep>().firstOrNull { it.journalId != null }?.let { step ->
            throw unsupportedJournal(step.handle, "applyTo(Use<HostManagementApi>)")
        }
        lowered.filterIsInstance<SpawnStep>().firstOrNull { it.shadow }?.let { step ->
            throw unsupportedShadow(step.handle, "applyTo(Use<HostManagementApi>)")
        }
        val refs = mutableMapOf<String, CellRef>()
        val links = mutableMapOf<String, Link>()
        val endpoints = mutableMapOf<String, Pair<CellRef, CellRef>>()
        lowered.forEach { step ->
            when (step) {
                is SpawnStep -> {
                    val ref = step.identity.resolve()
                    val cell = step.factory.create(ref)
                    requireBoundRef(step.handle, step.identity, ref, cell.ref)
                    refs[step.handle] = host.call.spawn(cell)
                }

                is ConnectStep -> {
                    val key = stepKey(step)
                    val result = host.call.connectStep(
                        refs.getValue(step.from), step.outlet,
                        refs.getValue(step.to), step.inlet,
                        step.options,
                    )
                    check(result !is LinkResult.Rejected) {
                        "link ${step.from}.${step.outlet} → ${step.to}.${step.inlet} rejected: " +
                            (result as LinkResult.Rejected).reason
                    }
                    if (result is LinkResult.Connected) {
                        links[key] = result.link
                        endpoints[key] = refs.getValue(step.from) to refs.getValue(step.to)
                    }
                }

                is UnlinkStep -> {
                    val key = stepKey(step)
                    val link = links.remove(key) ?: throw unresolvedUnlink(key)
                    endpoints.remove(key)
                    link.unlink()
                }

                is DespawnStep -> {
                    val ref = refs.remove(step.handle)
                        ?: throw IllegalStateException("unknown handle '${step.handle}'")
                    endpoints.filterValues { (from, to) -> from == ref || to == ref }.keys.toList().forEach { key ->
                        links.remove(key)?.unlink()
                        endpoints.remove(key)
                    }
                    host.call.despawn(ref)
                }

                // Unreachable: lowered() expands every InstanceSetStep to SpawnSteps.
                is InstanceSetStep -> error("InstanceSetStep must be lowered before apply")
            }
        }
        return refs
    }

    /**
     * [applyRemote] with no progress observer (computenet-4jdw0-D2) — every
     * existing caller's behaviour and returned [ApplyReport] are unchanged.
     */
    fun applyRemote(host: Use<HostManagementApi>): ApplyReport = applyRemote(host, ApplyProgress { })

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
     *
     * [progress] is invoked synchronously, once per [lowered] step, in step
     * order, immediately after that step's [StepResult] is folded into the
     * returned [ApplyReport] (computenet-4jdw0-D1/D2) — every branch that
     * writes a [StepResult] reports its [StepEvent] before the next step runs.
     */
    fun applyRemote(host: Use<HostManagementApi>, progress: ApplyProgress): ApplyReport {
        val refs = mutableMapOf<String, CellRef>()
        val results = mutableMapOf<String, StepResult>()
        val lowered = lowered()
        val familyHandles = lowered.filterIsInstance<SpawnStep>()
            .filter { it.family != null }
            .mapTo(mutableSetOf()) { it.handle }
        lowered.forEachIndexed { index, step ->
            when (step) {
                is SpawnStep -> {
                    if (step.family != null) {
                        results[step.handle] = StepResult.Rejected(
                            "spawn step '${step.handle}': parameter 'family' is not supported by applyRemote",
                        )
                    } else if (step.inputs.isNotEmpty()) {
                        results[step.handle] = StepResult.Rejected(
                            "spawn step '${step.handle}': parameter 'inputs' is not supported by applyRemote",
                        )
                    } else if (step.replicated) {
                        results[step.handle] = StepResult.Rejected(
                            "spawn step '${step.handle}': parameter 'replicated' is not supported by applyRemote",
                        )
                    } else if (step.journalId != null) {
                        results[step.handle] = StepResult.Rejected(
                            "spawn step '${step.handle}': parameter 'journalId' is not supported by applyRemote",
                        )
                    } else if (step.shadow) {
                        results[step.handle] = StepResult.Rejected(
                            "spawn step '${step.handle}': parameter 'shadow' is not supported by applyRemote",
                        )
                    } else {
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
                    // Outside the try: a throw from the callback propagates (D2) and
                    // is never folded into the report as the step's own failure.
                    progress.onStep(StepEvent(index, step.handle, results.getValue(step.handle)))
                }

                is ConnectStep -> {
                    val key = "${step.from}.${step.outlet}->${step.to}.${step.inlet}"
                    val from = refs[step.from]
                    val to = refs[step.to]
                    if (step.from in familyHandles) {
                        results[key] = StepResult.Rejected(familyLinkReason(step.from, key))
                    } else if (step.to in familyHandles) {
                        results[key] = StepResult.Rejected(familyLinkReason(step.to, key))
                    } else if (from == null || to == null) {
                        results[key] = StepResult.Rejected(
                            "endpoint not constructed: '${step.from}' or '${step.to}' was rejected/missing",
                        )
                    } else {
                        try {
                            when (val result = host.call.connectStep(from, step.outlet, to, step.inlet, step.options)) {
                                is LinkResult.Rejected -> results[key] = StepResult.Rejected(result.reason)
                                else -> results[key] = StepResult.Applied(null)
                            }
                        } catch (e: Exception) {
                            results[key] = StepResult.Rejected(e.message ?: e.toString())
                        }
                    }
                    progress.onStep(StepEvent(index, key, results.getValue(key)))
                }

                is UnlinkStep -> {
                    val key = unlinkStepKey(step)
                    results[key] = StepResult.Rejected(
                        "unlink step '${stepKey(step)}' is not supported by applyRemote: " +
                            "HostManagementApi has no disconnect operation",
                    )
                    progress.onStep(StepEvent(index, key, results.getValue(key)))
                }

                is DespawnStep -> {
                    val key = despawnStepKey(step)
                    results[key] = StepResult.Rejected(
                        "despawn step '${step.handle}' is not supported by applyRemote",
                    )
                    progress.onStep(StepEvent(index, key, results.getValue(key)))
                }

                // Unreachable: lowered() expands every InstanceSetStep to SpawnSteps.
                is InstanceSetStep -> error("InstanceSetStep must be lowered before apply")
            }
        }
        return ApplyReport(results)
    }
}

/**
 * x0oag-D3: a step with [LinkOptions.DEFAULT] keeps the 4-arg `connect` it
 * made before options existed, so a [HostManagementApi] decorator that
 * intercepts only that overload (inspect's `StagedApplier` recorder, which
 * records the links its UNWIND retracts) still sees every parameter-free edge.
 */
internal fun HostManagementApi.connectStep(
    from: CellRef,
    outletName: String,
    to: CellRef,
    inletName: String,
    options: LinkOptions,
): LinkResult =
    if (options == LinkOptions.DEFAULT) {
        connect(from, outletName, to, inletName)
    } else {
        connect(from, outletName, to, inletName, options)
    }

private fun stepKey(step: ConnectStep): String = "${step.from}.${step.outlet}->${step.to}.${step.inlet}"

private fun stepKey(step: UnlinkStep): String = "${step.from}.${step.outlet}->${step.to}.${step.inlet}"

private fun stepKey(event: TopoEvent.Connect): String =
    "${event.from}.${event.outlet}->${event.to}.${event.inlet}"

private fun stepKey(event: TopoEvent.Unlink): String =
    "${event.from}.${event.outlet}->${event.to}.${event.inlet}"

private fun unlinkStepKey(step: UnlinkStep): String = "unlink ${stepKey(step)}"

private fun despawnStepKey(step: DespawnStep): String = "despawn ${step.handle}"

private fun unresolvedUnlink(key: String): IllegalStateException = IllegalStateException(
    "unlink step '$key': no earlier connected edge in this apply",
)

internal fun missingReplication(handle: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'replicated' requires ApplyContext.replication",
)

private fun unsupportedReplication(handle: String, path: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'replicated' cannot be applied by $path; use apply(ApplyContext)",
)

internal fun missingJournal(handle: String, journalId: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'journalId' names '$journalId', but ApplyContext.journals has no such journal",
)

private fun unsupportedJournal(handle: String, path: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'journalId' cannot be applied by $path; use apply(ApplyContext)",
)

private fun unsupportedInputs(handle: String, path: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'inputs' cannot be applied by $path; use apply(ApplyContext)",
)

private fun unsupportedShadow(handle: String, path: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'shadow' cannot be applied by $path; use apply(ApplyContext)",
)

internal fun suppressShadow(cell: Cell) {
    if (cell is Effectful) Shadow.suppress(cell) else Shadow.suppressEffectContracts(cell)
}

internal fun missingFamilyJournal(handle: String, journalId: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'family.journalId' names '$journalId', " +
        "but ApplyContext.journalDirs has no such journal directory",
)

internal fun unsupportedFamily(handle: String, path: String): IllegalStateException = IllegalStateException(
    "spawn step '$handle': parameter 'family' cannot be applied by $path; use graph(ApplyContext)",
)

private fun familyLinkReason(handle: String, key: String): String =
    "link '$key' names family handle '$handle' (parameter 'family' has no single port)"

private fun familyLinkRefusal(handle: String, key: String): IllegalStateException =
    IllegalStateException(familyLinkReason(handle, key))

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
class GraphBuilder private constructor(
    private val host: Use<HostManagementApi>,
    private val context: ApplyContext?,
) {
    internal constructor(host: Use<HostManagementApi>) : this(host, null)
    internal constructor(context: ApplyContext) : this(context.host.managementInlet, context)

    private val steps = mutableListOf<GraphStep>()
    private val names = mutableSetOf<String>()
    private val links = mutableMapOf<String, Link>()
    private val linkSteps = mutableMapOf<String, ConnectStep>()

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
        replicated: Boolean = false,
        journalId: String? = null,
        shadow: Boolean = false,
        inputs: Set<String> = emptySet(),
        placement: String? = null,
        factory: TypedCellFactory<C>,
    ): TypedCellHandle<C> {
        require(names.add(name)) { "duplicate handle '$name'" }
        require(context?.hasHandle(name) != true) { "duplicate handle '$name'" }
        if (context == null) {
            if (inputs.isNotEmpty()) throw unsupportedInputs(name, "graph(Use<HostManagementApi>)")
            if (journalId != null) throw unsupportedJournal(name, "graph(Use<HostManagementApi>)")
            if (shadow) throw unsupportedShadow(name, "graph(Use<HostManagementApi>)")
        } else {
            if (replicated && context.replication == null) throw missingReplication(name)
            if (journalId != null && journalId !in context.journals) throw missingJournal(name, journalId)
        }
        val ref = identity.resolve()
        val step = SpawnStep(
            handle = name,
            factory = factory,
            identity = identity,
            parent = parent?.name,
            replicated = replicated,
            journalId = journalId,
            shadow = shadow,
            inputs = inputs,
            placement = placement,
        )
        val event = TopoEvent.Spawn(name, ref, factory, parent?.ref, replicated, journalId, shadow)
        context?.journalTopology(listOf(event))
        val cell = factory.create(ref)
        requireBoundRef(name, identity, ref, cell.ref)
        val spawnedRef = context?.applySpawn(event, cell) ?: spawn(step, cell)
        steps += step
        return TypedCellHandle(name, spawnedRef, this, cell)
            .also { handlesByRef[it.ref] = it }
    }

    /** Declare and construct a keyed family; family cells are spawned lazily by [KeyedCells]. */
    fun family(
        name: String,
        namespace: String,
        keys: KeyCodec = KeyCodec.Strings,
        journalId: String? = null,
        factory: KeyedCellFactory,
    ): KeyedCells<Any> {
        val applyContext = context
            ?: throw unsupportedFamily(name, "graph(Use<HostManagementApi>)")
        require(names.add(name)) { "duplicate handle '$name'" }
        require(!applyContext.hasHandle(name) && name !in applyContext.live().families) { "duplicate handle '$name'" }
        if (journalId != null && journalId !in applyContext.journalDirs) {
            throw missingFamilyJournal(name, journalId)
        }
        val step = SpawnStep(
            handle = name,
            factory = factory,
            family = KeyedFamily(namespace, keys, journalId),
        )
        val event = TopoEvent.Family(name, step.family!!, factory)
        applyContext.journalTopology(listOf(event))
        applyContext.apply(event)
        @Suppress("UNCHECKED_CAST")
        val family = checkNotNull(applyContext.familyFor(name)) as KeyedCells<Any>
        steps += step
        return family
    }

    /** Source-compatible positional form from before the replicated parameter. */
    fun <C : Cell> spawn(
        name: String,
        identity: IdentityBinding,
        parent: CellHandle?,
        factory: TypedCellFactory<C>,
    ): TypedCellHandle<C> = spawn(name, identity, parent, replicated = false, factory = factory)

    private fun spawn(step: SpawnStep, cell: Cell): CellRef {
        if (step.replicated) throw unsupportedReplication(step.handle, "graph(Use<HostManagementApi>)")
        return host.call.spawn(cell)
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
        val lowered = step.lower().filterIsInstance<SpawnStep>()
        lowered.firstOrNull { it.replicated && context == null }?.let { s ->
            throw unsupportedReplication(s.handle, "graph(Use<HostManagementApi>)")
        }
        if (context == null) {
            lowered.firstOrNull { it.journalId != null }?.let { s ->
                throw unsupportedJournal(s.handle, "graph(Use<HostManagementApi>)")
            }
            lowered.firstOrNull { it.shadow }?.let { s ->
                throw unsupportedShadow(s.handle, "graph(Use<HostManagementApi>)")
            }
        }
        val applied = context?.let { GraphSpec(lowered).apply(it) }
        val handles = lowered.map { s ->
            require(names.add(s.handle)) { "duplicate handle '${s.handle}'" }
            val spawnedRef = if (applied != null) {
                applied.refs.getValue(s.handle)
            } else {
                val ref = s.identity.resolve()
                val cell = s.factory.create(ref)
                requireBoundRef(s.handle, s.identity, ref, cell.ref)
                host.call.spawn(cell)
            }
            CellHandle(s.handle, spawnedRef, this).also { handlesByRef[it.ref] = it }
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
        context?.adopt(name, cell.ref)
        return CellHandle(name, cell.ref, this).also { handlesByRef[it.ref] = it }
    }

    fun connect(
        from: CellHandle,
        outlet: String,
        to: CellHandle,
        inlet: String,
        options: LinkOptions = LinkOptions.DEFAULT,
    ) {
        val step = ConnectStep(from.name, outlet, to.name, inlet, options)
        val event = TopoEvent.Connect(from.ref, outlet, to.ref, inlet, options)
        context?.journalTopology(listOf(event))
        val link = if (context != null) {
            context.applyConnect(event)
        } else {
            val result = host.call.connectStep(from.ref, outlet, to.ref, inlet, options)
            check(result !is LinkResult.Rejected) {
                "link ${from.name}.$outlet → ${to.name}.$inlet rejected: ${(result as LinkResult.Rejected).reason}"
            }
            (result as? LinkResult.Connected)?.link
        }
        if (link != null) {
            links[stepKey(step)] = link
            linkSteps[stepKey(step)] = step
        }
        steps += step
    }

    /** Detaches and records an edge this builder connected earlier. */
    fun unlink(from: CellHandle, outlet: String, to: CellHandle, inlet: String) {
        val step = UnlinkStep(from.name, outlet, to.name, inlet)
        val key = stepKey(step)
        val event = TopoEvent.Unlink(from.ref, outlet, to.ref, inlet)
        context?.journalTopology(listOf(event))
        if (context != null) {
            context.applyUnlink(event)
        } else {
            val link = links[key] ?: throw unresolvedUnlink(key)
            link.unlink()
        }
        links.remove(key)
        linkSteps.remove(key)
        steps += step
    }

    /** Unlinks every live edge touching [handle], despawns it, and records one inverse step. */
    fun despawn(handle: CellHandle) {
        require(handle.name in names) { "unknown handle '${handle.name}'" }
        val step = DespawnStep(handle.name)
        val event = TopoEvent.Despawn(handle.ref)
        context?.journalTopology(listOf(event))
        if (context != null) {
            context.applyDespawn(event)
        } else {
            linkSteps.filterValues { it.from == handle.name || it.to == handle.name }.keys.toList().forEach { key ->
                links.remove(key)?.unlink()
                linkSteps.remove(key)
            }
            host.call.despawn(handle.ref)
        }
        handlesByRef.remove(handle.ref)
        names.remove(handle.name)
        steps += step
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
    fun <Api> link(out: Subscribe<Api>, inn: Serve<Api>, options: LinkOptions = LinkOptions.DEFAULT) {
        val from = out.requireHandle("outlet")
        val to = inn.requireHandle("inlet")
        connect(from.first, from.second, to.first, to.second, options)
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

/** Parameter-aware [graph] builder backed by [ApplyContext]. */
fun graph(context: ApplyContext, block: GraphBuilder.() -> Unit): GraphSpec =
    GraphBuilder(context).apply(block).spec()

/**
 * [graph] variant that also returns the block's result (T08 finding 3): the
 * documented happy path was `lateinit var refs` mutated from inside the block,
 * then read back after — `lookup` returning `A?` for a ref the DSL just minted
 * carried no actionable information. `val (refs, spec) = graphOf(host) { ...;
 * Refs(...) }` returns the block's last expression directly, no `lateinit`/`!!`.
 *
 * Ships under a distinct name rather than as a same-named generic overload of
 * [graph]: verified (a standalone Kotlin/JVM overload-resolution probe) that a
 * bare-lambda call to `graph(host) { … }` always resolves to the existing
 * `Unit` overload regardless of the block's actual last-expression type — an
 * `R`-generic overload of the same name is therefore never reachable, a
 * silently dead entry point, not merely an "ambiguous" one.
 */
fun <R> graphOf(host: Use<HostManagementApi>, block: GraphBuilder.() -> R): Pair<R, GraphSpec> {
    val builder = GraphBuilder(host)
    val result = builder.block()
    return result to builder.spec()
}

/** Parameter-aware [graphOf] builder backed by [ApplyContext]. */
fun <R> graphOf(context: ApplyContext, block: GraphBuilder.() -> R): Pair<R, GraphSpec> {
    val builder = GraphBuilder(context)
    val result = builder.block()
    return result to builder.spec()
}
