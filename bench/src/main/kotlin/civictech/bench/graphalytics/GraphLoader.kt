package civictech.bench.graphalytics

import java.io.Serializable
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Properties

/** The graph and all metadata needed by later Graphalytics tasks. */
data class LoadedGraph(
    val name: String,
    val directed: Boolean,
    val vertices: List<Long>,
    val edges: List<Edge>,
    val weights: Map<Edge, Double>?,
    val declaredVertices: Int,
    val declaredEdges: Int,
    val parameters: GraphParameters,
    /** True when the undirected file relation was expanded in both directions. */
    val symmetrised: Boolean = !directed,
)

data class Edge(val src: Long, val dst: Long) : Serializable

/** Typed algorithm and edge-property settings read from the dataset properties file. */
data class GraphParameters(
    val bfsSourceVertex: Long?,
    val cdlpMaxIterations: Int?,
    val prDampingFactor: Double?,
    val prNumIterations: Int?,
    val ssspWeightProperty: String?,
    val ssspSourceVertex: Long?,
    val algorithms: Set<Algorithm>,
    val edgePropertyNames: List<String>,
    val edgePropertyTypes: List<String>,
)

/** Thrown when file contents disagree with the declared Graphalytics metadata. */
class LoadFidelityException(message: String) : IllegalArgumentException(message)

object GraphLoader {

    fun load(dataset: DatasetRef): LoadedGraph {
        val properties = Properties().also { props ->
            dataset.open("${dataset.name}.properties").bufferedReader().use(props::load)
        }
        val prefix = "graph.${dataset.name}."
        fun property(key: String): String? = properties.getProperty(prefix + key)?.trim()?.takeIf { it.isNotEmpty() }
        fun required(key: String): String = property(key)
            ?: throw IllegalArgumentException("Dataset '${dataset.name}' is missing property '${prefix + key}'")

        val declaredVertices = required("meta.vertices").toInt()
        val declaredEdges = required("meta.edges").toInt()
        val directed = required("directed").toBooleanStrict()
        val vertices = dataset.open(required("vertex-file")).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).map(String::toLong).toList()
        }

        val edgeRows = dataset.open(required("edge-file")).bufferedReader().useLines { lines ->
            lines
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { line ->
                    val fields = line.split(Regex("\\s+"))
                    require(fields.size == 2 || fields.size == 3) {
                        "Malformed edge in dataset '${dataset.name}': '$line'"
                    }
                    ParsedEdge(
                        edge = Edge(fields[0].toLong(), fields[1].toLong()),
                        weight = fields.getOrNull(2)?.toDouble(),
                    )
                }
                .toList()
        }

        checkDeclaredCount(dataset.name, "meta.vertices", declaredVertices, vertices.size)
        checkDeclaredCount(dataset.name, "meta.edges", declaredEdges, edgeRows.size)

        val edges = LinkedHashSet<Edge>()
        val weights = LinkedHashMap<Edge, Double>()
        val hasWeightedRows = edgeRows.any { it.weight != null }
        val hasUnweightedRows = edgeRows.any { it.weight == null }
        require(!(hasWeightedRows && hasUnweightedRows)) {
            "Dataset '${dataset.name}' mixes weighted and unweighted edge rows"
        }
        edgeRows.forEach { row ->
            val orientations = if (directed) {
                listOf(row.edge)
            } else {
                listOf(row.edge, Edge(row.edge.dst, row.edge.src))
            }
            orientations.forEach { edge ->
                edges += edge
                row.weight?.let {
                    val previous = weights.put(edge, it)
                    require(previous == null || previous == it) {
                        "Dataset '${dataset.name}' assigns conflicting weights to edge $edge"
                    }
                }
            }
        }

        val parameters = GraphParameters(
            bfsSourceVertex = property("bfs.source-vertex")?.toLong(),
            cdlpMaxIterations = property("cdlp.max-iterations")?.toInt(),
            prDampingFactor = property("pr.damping-factor")?.toDouble(),
            prNumIterations = property("pr.num-iterations")?.toInt(),
            ssspWeightProperty = property("sssp.weight-property"),
            ssspSourceVertex = property("sssp.source-vertex")?.toLong(),
            algorithms = property("algorithms")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map { Algorithm.valueOf(it.uppercase()) }
                ?.toSet()
                ?: emptySet(),
            edgePropertyNames = property("edge-properties.names")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: emptyList(),
            edgePropertyTypes = property("edge-properties.types")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: emptyList(),
        )

        return LoadedGraph(
            name = dataset.name,
            directed = directed,
            vertices = vertices,
            edges = edges.toList(),
            weights = weights.takeIf { it.isNotEmpty() },
            declaredVertices = declaredVertices,
            declaredEdges = declaredEdges,
            parameters = parameters,
            symmetrised = !directed,
        )
    }

    private fun checkDeclaredCount(dataset: String, field: String, declared: Int, actual: Int) {
        if (declared != actual) {
            throw LoadFidelityException(
                "Dataset '$dataset' $field mismatch: declared $declared, actual $actual",
            )
        }
    }

    private data class ParsedEdge(val edge: Edge, val weight: Double?)
}
