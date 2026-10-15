package civictech.bench.graphalytics.algorithms

import civictech.bench.graphalytics.Edge
import civictech.bench.graphalytics.GrowthNote
import civictech.bench.graphalytics.RetainedState
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.data.Aggregators
import civictech.cell.data.SetCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.WireSerializers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable as KotlinSerializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.Serializable
import java.util.UUID

/** One candidate neighbour pair for vertex [v]. */
@KotlinSerializable
data class Candidate(val v: Long, val u: Long, val w: Long) : Serializable

/** Journal/wire registrations for every application payload this graph emits. */
internal object GraphalyticsWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(Edge::class, EdgeSerializer)
            subclass(Candidate::class, Candidate.serializer())
        }
    }
}

/** The loader-owned [Edge] is intentionally kept unchanged; this is its app serializer. */
private object EdgeSerializer : KSerializer<Edge> {
    override val descriptor: SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor(
            "civictech.bench.graphalytics.Edge",
        ) {
            element("src", PrimitiveSerialDescriptor("src", PrimitiveKind.LONG))
            element("dst", PrimitiveSerialDescriptor("dst", PrimitiveKind.LONG))
        }

    override fun serialize(encoder: Encoder, value: Edge) {
        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, value.src)
            encodeLongElement(descriptor, 1, value.dst)
        }
    }

    override fun deserialize(decoder: Decoder): Edge = decoder.decodeStructure(descriptor) {
        var src: Long? = null
        var dst: Long? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> src = decodeLongElement(descriptor, 0)
                1 -> dst = decodeLongElement(descriptor, 1)
                else -> error("Unexpected Edge field index $index")
            }
        }
        Edge(
            src = requireNotNull(src) { "Edge.src is missing" },
            dst = requireNotNull(dst) { "Edge.dst is missing" },
        )
    }
}

/**
 * A hosted Local Clustering Coefficient graph built only from kernel operator cells.
 *
 * The two integer folds are read after quiescence. The final division in [read] is
 * output formatting; every relational operation and aggregate stays on the linked
 * cell graph.
 */
class Lcc private constructor(
    private val neighbours: SetCountCollectorCell<Edge>,
    private val candidates: SetCountCollectorCell<Candidate>,
    private val triangles: SetCountCollectorCell<Candidate>,
    private val triangleCounts: LongKeyMapCollectorCell,
    private val degrees: LongKeyMapCollectorCell,
) {

    /** Materialise one coefficient for every declared vertex, including isolated ones. */
    fun read(vertices: List<Long>): Map<Long, Double> {
        val triangleValues = triangleCounts.values()
        val degreeValues = degrees.values()
        return vertices.associateWith { vertex ->
            val degree = degreeValues[vertex] ?: 0L
            val triangleCount = triangleValues[vertex] ?: 0L
            if (degree < 2L) {
                0.0
            } else {
                triangleCount.toDouble() / (degree * (degree - 1L))
            }
        }
    }

    internal fun retainedState(edgeRows: Long): RetainedState = RetainedState(
        edgeRows = edgeRows,
        neighbourRows = neighbours.size.toLong(),
        candidateRows = candidates.size.toLong(),
        triangleRows = triangles.size.toLong(),
        foldKeys = mapOf(
            "triangles" to triangleCounts.size.toLong(),
            "degree" to degrees.size.toLong(),
        ),
        growthAcrossIterations = GrowthNote.NotApplicableNonIterative,
    )

    /** A payload-validated plan that can be created before a host exists. */
    class Builder internal constructor() {

        /** Attach the operator pipeline to the already-loaded [edges] relation. */
        internal fun attach(host: Use<HostManagementApi>, edges: SetCell<Edge>): Lcc {
            lateinit var neighbourCollector: SetCountCollectorCell<Edge>
            lateinit var candidateCollector: SetCountCollectorCell<Candidate>
            lateinit var triangleCollector: SetCountCollectorCell<Candidate>
            lateinit var triangleCountCollector: LongKeyMapCollectorCell
            lateinit var degreeCollector: LongKeyMapCollectorCell

            graph(host) {
                // The load phase owns this cell. Adoption records no second spawn and
                // makes its typed outlet available to this phase's graph DSL links.
                adopt(edges)

                val neighbours = spawn("lcc-neighbours") { ref ->
                    FlatMapSetCell(ref = ref) { edge: Edge ->
                        listOf(edge, Edge(edge.dst, edge.src))
                    }
                }
                val pairs = spawn("lcc-candidates") { ref ->
                    JoinSetCell<Edge, Edge, Long, Candidate>(
                        ref = ref,
                        leftKey = Edge::src,
                        rightKey = Edge::src,
                        combine = { left, right ->
                            Candidate(v = left.src, u = left.dst, w = right.dst)
                        },
                    )
                }
                val matchedTriangles = spawn("lcc-triangles") { ref ->
                    SemiJoinCell<Candidate, Edge, Pair<Long, Long>>(
                        ref = ref,
                        leftKey = { candidate -> candidate.u to candidate.w },
                        rightKey = { edge -> edge.src to edge.dst },
                    )
                }
                val triangleFold = spawn("lcc-triangle-counts") { ref ->
                    GroupByCell<Candidate, Long, Long, Long>(
                        ref = ref,
                        keyFn = Candidate::v,
                        aggregator = Aggregators.count(),
                    )
                }
                val degreeFold = spawn("lcc-degrees") { ref ->
                    GroupByCell<Edge, Long, Long, Long>(
                        ref = ref,
                        keyFn = Edge::src,
                        aggregator = Aggregators.count(),
                    )
                }
                val neighbourRows = spawn("lcc-neighbour-rows") { ref ->
                    setCountCollector<Edge>(ref)
                }
                val candidateRows = spawn("lcc-candidate-rows") { ref ->
                    setCountCollector<Candidate>(ref)
                }
                val triangleRows = spawn("lcc-triangle-rows") { ref ->
                    setCountCollector<Candidate>(ref)
                }
                val triangleCounts = spawn("lcc-triangle-count-values") { ref ->
                    LongKeyMapCollectorCell(ref)
                }
                val degreeCounts = spawn("lcc-degree-values") { ref ->
                    LongKeyMapCollectorCell(ref)
                }

                link(edges.outlet, neighbours.cell.inlet)
                link(neighbours.cell.outlet, pairs.cell.left)
                link(neighbours.cell.outlet, pairs.cell.right)
                link(pairs.cell.outlet, matchedTriangles.cell.left)
                link(edges.outlet, matchedTriangles.cell.right)
                link(matchedTriangles.cell.outlet, triangleFold.cell.inlet)
                link(neighbours.cell.outlet, degreeFold.cell.inlet)

                link(neighbours.cell.outlet, neighbourRows.cell.inlet)
                link(pairs.cell.outlet, candidateRows.cell.inlet)
                link(matchedTriangles.cell.outlet, triangleRows.cell.inlet)
                link(triangleFold.cell.outlet, triangleCounts.cell.inlet)
                link(degreeFold.cell.outlet, degreeCounts.cell.inlet)

                neighbourCollector = neighbourRows.cell
                candidateCollector = candidateRows.cell
                triangleCollector = triangleRows.cell
                triangleCountCollector = triangleCounts.cell
                degreeCollector = degreeCounts.cell
            }

            return Lcc(
                neighbours = neighbourCollector,
                candidates = candidateCollector,
                triangles = triangleCollector,
                triangleCounts = triangleCountCollector,
                degrees = degreeCollector,
            )
        }
    }

    companion object {
        /**
         * Validate the payload type before returning anything capable of hosting cells.
         * This keeps an exclusive payload off every benchmark failure path.
         */
        fun builder(vertexPayload: Class<*> = java.lang.Long::class.java): Builder {
            require(
                !Owned::class.java.isAssignableFrom(vertexPayload) &&
                    !Leased::class.java.isAssignableFrom(vertexPayload),
            ) {
                "[BB-GRA-28] LCC graph refuses Owned or Leased vertex payloads: ${vertexPayload.name}"
            }
            return Builder()
        }
    }
}

/** A tag-correct materialized count of a set-shaped operator output. */
private class SetCountCollectorCell<E>(
    override val ref: CellRef,
    inletPort: FanInlet<Propagate<SetDelta<E>>>,
) : Cell {
    val inlet = registerPort("inlet", inletPort)
    private val view = SetView<E>()

    val size: Int get() = view.size

    init {
        inlet.onEach(view::apply)
    }
}

private inline fun <reified E> setCountCollector(
    ref: CellRef = CellRef(UUID.randomUUID()),
): SetCountCollectorCell<E> =
    SetCountCollectorCell(ref, FanInlet.create<Propagate<SetDelta<E>>>())

/** Collector for the two `MapDelta<Long, Long>` LCC folds. */
internal class LongKeyMapCollectorCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Long, Long>>>())
    private val view = MapView<Long, Long>()

    val size: Int get() = view.size

    fun values(): Map<Long, Long> = view.current()

    init {
        inlet.onEach(view::apply)
    }
}
