package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.HostManagementApi
import civictech.cell.port.LinkResult
import civictech.cell.port.Use
import java.io.Serializable

/**
 * Creates the cell for one spawn step. Serializable so a recorded [GraphSpec]
 * is graphs-as-data (G-30): keep captured values serializable.
 */
fun interface CellFactory : Serializable {
    fun create(): Cell
}

sealed interface GraphStep : Serializable

data class SpawnStep(val handle: String, val factory: CellFactory) : GraphStep

data class ConnectStep(val from: String, val outlet: String, val to: String, val inlet: String) : GraphStep

/**
 * A graph as data: an ordered step list, each lowering to a host-management
 * invocation — nothing the spec does is beyond `spawn`/`connect` (51). Replay
 * onto any host creates fresh cells (fresh refs) with the same topology.
 */
data class GraphSpec(val steps: List<GraphStep>) : Serializable {
    fun applyTo(host: Use<HostManagementApi>): Map<String, CellRef> {
        val refs = mutableMapOf<String, CellRef>()
        steps.forEach { step ->
            when (step) {
                is SpawnStep -> refs[step.handle] = host.call.spawn(step.factory.create())
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

    fun spawn(name: String, factory: CellFactory): CellHandle {
        require(names.add(name)) { "duplicate handle '$name'" }
        steps += SpawnStep(name, factory)
        return CellHandle(name, host.call.spawn(factory.create()), this)
    }

    fun connect(from: CellHandle, outlet: String, to: CellHandle, inlet: String) {
        val result = host.call.connect(from.ref, outlet, to.ref, inlet)
        check(result !is LinkResult.Rejected) {
            "link ${from.name}.$outlet → ${to.name}.$inlet rejected: ${(result as LinkResult.Rejected).reason}"
        }
        steps += ConnectStep(from.name, outlet, to.name, inlet)
    }

    internal fun spec() = GraphSpec(steps.toList())
}

/** Builds a graph on [host] and returns its replayable [GraphSpec]. */
fun graph(host: Use<HostManagementApi>, block: GraphBuilder.() -> Unit): GraphSpec =
    GraphBuilder(host).apply(block).spec()
