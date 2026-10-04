package civictech.runtime

import civictech.cell.CellRef
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.InstanceSetStep
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.UnlinkStep
import civictech.cell.link.LinkOptions
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** One cross-node link, with both endpoint identities resolved before either node applies it. */
data class CrossEdge(
    val fromHandle: String,
    val fromRef: CellRef,
    val outlet: String,
    val toHandle: String,
    val toRef: CellRef,
    val inlet: String,
)

/**
 * Pure placement of one lowered [GraphSpec] for [node].
 *
 * The plan retains only locally applicable steps. Cross-node connects become
 * producer or consumer halves for the runtime driver; no host or transport is
 * touched while the plan is built.
 */
class PlacementPlan private constructor(
    val node: String,
    val localSpec: GraphSpec,
    val producerHalves: List<CrossEdge>,
    val consumerHalves: List<CrossEdge>,
    private val refs: Map<String, CellRef>,
    private val nodes: Map<String, String?>,
    private val replicatedHandles: Set<String>,
    private val spawns: Map<String, SpawnStep>,
    private val activeHandles: Set<String>,
    private val crossEdges: List<CrossEdge>,
) {
    /** The mesh-stable identity of a non-replicated spawn handle. */
    fun refOf(handle: String): CellRef {
        if (handle in replicatedHandles) {
            throw IllegalStateException("spawn step '$handle': replicated handle has no single CellRef")
        }
        return refs[handle] ?: throw IllegalStateException("unknown spawn handle '$handle'")
    }

    /** The assigned node, or null when a null-selector replica is local on every node. */
    fun nodeOf(handle: String): String? {
        if (handle !in nodes) throw IllegalStateException("unknown spawn handle '$handle'")
        return nodes[handle]
    }

    companion object {
        /**
         * Plans [spec] for [node], or returns null when placement is disabled by
         * an empty [Manifest.placements] map.
         */
        fun of(spec: GraphSpec, manifest: Manifest, node: String): PlacementPlan? {
            if (manifest.placements.isEmpty()) return null
            return plan(spec, manifest, node, previous = null)
        }

        /**
         * Plans one graph [spec] delta against [previous]'s cumulative handle,
         * identity and cross-edge state. The returned halves contain only links
         * introduced by this delta; its private state carries the cumulative
         * fold for the next call.
         */
        fun of(
            spec: GraphSpec,
            manifest: Manifest,
            node: String,
            previous: PlacementPlan,
        ): PlacementPlan? {
            if (manifest.placements.isEmpty()) return null
            require(previous.node == node) {
                "prior placement plan belongs to node '${previous.node}', not '$node'"
            }
            return plan(spec, manifest, node, previous)
        }

        private fun plan(
            spec: GraphSpec,
            manifest: Manifest,
            node: String,
            previous: PlacementPlan?,
        ): PlacementPlan {

            val lowered = spec.lowered()
            val deltaSpawns = lowered.filterIsInstance<SpawnStep>()

            // Placement names are a whole-spec precondition. Refuse a missing
            // selector before pinning identities or classifying any later step.
            deltaSpawns.forEach { step ->
                if (!(step.replicated && step.placement == null)) {
                    val selector = step.placement ?: "default"
                    if (selector !in manifest.placements) {
                        throw IllegalStateException(
                            "spawn step '${step.handle}': placement '$selector' names no entry " +
                                "in manifest.placements",
                        )
                    }
                }
            }

            val spawnByHandle = LinkedHashMap(previous?.spawns.orEmpty())
            deltaSpawns.forEach { step ->
                check(step.handle !in spawnByHandle) {
                    "duplicate handle '${step.handle}'"
                }
                spawnByHandle[step.handle] = if (step.replicated) {
                    step
                } else {
                    step.copy(identity = pin(step.handle, step.identity))
                }
            }

            val assignedNodes = LinkedHashMap(previous?.nodes.orEmpty())
            deltaSpawns.forEach { step ->
                assignedNodes[step.handle] = if (step.replicated && step.placement == null) {
                    null
                } else {
                    manifest.placements.getValue(step.placement ?: "default")
                }
            }
            val refs = LinkedHashMap(previous?.refs.orEmpty())
            deltaSpawns.filterNot { it.replicated }.forEach { step ->
                refs[step.handle] = (spawnByHandle.getValue(step.handle).identity as IdentityBinding.Exact).ref
            }
            val replicated = previous?.replicatedHandles.orEmpty().toMutableSet()
            replicated += deltaSpawns.filter { it.replicated }.map { it.handle }

            fun spawn(handle: String): SpawnStep = spawnByHandle[handle]
                ?: throw IllegalStateException("unknown spawn handle '$handle'")

            fun local(handle: String): Boolean {
                val step = spawn(handle)
                return step.replicated && step.placement == null || assignedNodes.getValue(handle) == node
            }

            fun crossEdge(step: ConnectStep): CrossEdge {
                val key = edgeKey(step.from, step.outlet, step.to, step.inlet)
                listOf(step.from, step.to).forEach { handle ->
                    val endpoint = spawn(handle)
                    if (endpoint.replicated) {
                        throw IllegalStateException(
                            "link $key: replicated handle '$handle' has no single remote ref",
                        )
                    }
                    if (endpoint.family != null) {
                        throw IllegalStateException(
                            "link $key: family handle '$handle' has no port",
                        )
                    }
                }
                return CrossEdge(
                    fromHandle = step.from,
                    fromRef = refs.getValue(step.from),
                    outlet = step.outlet,
                    toHandle = step.to,
                    toRef = refs.getValue(step.to),
                    inlet = step.inlet,
                )
            }

            val localSteps = mutableListOf<GraphStep>()
            val producerHalves = mutableListOf<CrossEdge>()
            val consumerHalves = mutableListOf<CrossEdge>()
            val active = previous?.activeHandles.orEmpty().toMutableSet()
            val liveCrossEdges = previous?.crossEdges.orEmpty().toMutableList()

            lowered.forEach { step ->
                when (step) {
                    is SpawnStep -> {
                        check(active.add(step.handle)) { "duplicate handle '${step.handle}'" }
                        if (local(step.handle)) localSteps += spawnByHandle.getValue(step.handle)
                    }

                    is ConnectStep -> {
                        requireActive(active, step.from)
                        requireActive(active, step.to)
                        val fromLocal = local(step.from)
                        val toLocal = local(step.to)
                        val crossesNodes =
                            assignedNodes.getValue(step.from) != assignedNodes.getValue(step.to)
                        when {
                            fromLocal && toLocal -> localSteps += step
                            crossesNodes -> {
                                val key = edgeKey(step.from, step.outlet, step.to, step.inlet)
                                if (step.options != LinkOptions.DEFAULT) {
                                    throw IllegalStateException(
                                        "link $key: link options are not supported across nodes (staged/Observe)",
                                    )
                                }
                                val edge = crossEdge(step)
                                if (fromLocal) producerHalves += edge
                                if (toLocal) consumerHalves += edge
                                liveCrossEdges += edge
                            }
                        }
                    }

                    is UnlinkStep -> {
                        requireActive(active, step.from)
                        requireActive(active, step.to)
                        val key = edgeKey(step.from, step.outlet, step.to, step.inlet)
                        if (!local(step.from) || !local(step.to)) {
                            throw IllegalStateException("unlink $key: cross-node unlink is not supported")
                        }
                        localSteps += step
                    }

                    is DespawnStep -> {
                        requireActive(active, step.handle)
                        val hasCrossEdge = liveCrossEdges.any {
                            it.fromHandle == step.handle || it.toHandle == step.handle
                        }
                        if (hasCrossEdge) {
                            throw IllegalStateException(
                                "despawn '${step.handle}': cross-node despawn is not supported",
                            )
                        }
                        if (local(step.handle)) {
                            localSteps += step
                        }
                        active.remove(step.handle)
                    }

                    is InstanceSetStep -> error("InstanceSetStep must be lowered before placement")
                }
            }

            return PlacementPlan(
                node = node,
                localSpec = GraphSpec(localSteps.toList()),
                producerHalves = producerHalves.toList(),
                consumerHalves = consumerHalves.toList(),
                refs = refs,
                nodes = assignedNodes,
                replicatedHandles = replicated,
                spawns = spawnByHandle,
                activeHandles = active,
                crossEdges = liveCrossEdges,
            )
        }

        private fun pin(handle: String, identity: IdentityBinding): IdentityBinding.Exact {
            val placementId = UUID.nameUUIDFromBytes("computenet-placement:$handle".toByteArray(UTF_8))
            return when (identity) {
                IdentityBinding.FreshLogical -> IdentityBinding.Exact(CellRef(placementId))
                is IdentityBinding.NewInstanceOf ->
                    IdentityBinding.Exact(CellRef(identity.logicalId, placementId.leastSignificantBits))
                is IdentityBinding.Exact -> identity
            }
        }

        private fun requireActive(active: Set<String>, handle: String) {
            if (handle !in active) throw IllegalStateException("unknown handle '$handle'")
        }

        private fun edgeKey(from: String, outlet: String, to: String, inlet: String): String =
            "$from.$outlet->$to.$inlet"
    }
}
