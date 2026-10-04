package civictech.runtime

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory as FactoryContract
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The identical graph and manifests used by the in-process and forked placement tests. */
object PlacementFixture {

    /** Build the placement pipeline from scratch in every JVM. */
    fun spec(): GraphSpec = GraphSpec(
        listOf(
            SpawnStep(
                handle = "items",
                factory = SetFactory("items"),
                placement = "source",
            ),
            SpawnStep(
                handle = "union",
                factory = UnionFactory("union"),
                placement = "op",
            ),
            SpawnStep(
                handle = "relay",
                factory = UnionFactory("relay"),
                placement = "relay",
            ),
            SpawnStep(
                handle = "view",
                factory = FoldFactory("view"),
                placement = "sink",
            ),
            ConnectStep("items", "outlet", "union", "inlet"),
            ConnectStep("union", "outlet", "relay", "inlet"),
            ConnectStep("relay", "outlet", "view", "inlet"),
        ),
    )

    fun captured(handle: String): Cell =
        captured[handle] ?: throw IllegalStateException("placement fixture did not capture '$handle'")

    fun resetCaptures() {
        captured.clear()
    }

    fun singleHost(): Manifest = Manifest(
        nodes = mapOf(
            "a" to NodeSpec(
                transport = "ws",
                listen = "ws://127.0.0.1:0",
                peerName = "a",
            ),
        ),
        placements = selectorsTo("a"),
    )

    fun threeJvm(): Manifest = Manifest(
        nodes = mapOf(
            "a" to NodeSpec(
                transport = "ws",
                listen = "ws://127.0.0.1:0",
                peerName = "a",
            ),
            "b" to NodeSpec(
                transport = "ws",
                listen = "ws://127.0.0.1:0",
                dial = listOf("a"),
                peerName = "b",
            ),
            "c" to NodeSpec(
                transport = "ws",
                dial = listOf("a", "b"),
                peerName = "c",
            ),
        ),
        placements = mapOf(
            "source" to "a",
            "op" to "b",
            "relay" to "c",
            "sink" to "a",
        ),
    )

    fun threeJvmDurable(root: Path): Manifest = threeJvm().let { manifest ->
        manifest.copy(
            nodes = manifest.nodes.mapValues { (name, node) ->
                node.copy(
                    journalDir = root.resolve(name).toString(),
                    journalTopology = true,
                )
            },
        )
    }

    private data class SetFactory(private val handle: String) : FactoryContract {
        override fun create(ref: CellRef): SetCell<String> =
            SetCell<String>(ref).also { captured[handle] = it }
    }

    private data class UnionFactory(private val handle: String) : FactoryContract {
        override fun create(ref: CellRef): UnionSetCell<String> =
            UnionSetCell<String>(ref).also { captured[handle] = it }
    }

    private data class FoldFactory(private val handle: String) : FactoryContract {
        override fun create(ref: CellRef): SetFoldCell =
            SetFoldCell(ref).also { captured[handle] = it }
    }

    private fun selectorsTo(node: String): Map<String, String> =
        listOf("source", "op", "relay", "sink").associateWith { node }

    private val captured = ConcurrentHashMap<String, Cell>()

    /** A minimal sink fold kept local because kernel test helpers are not on :runtime's classpath. */
    class SetFoldCell(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        @Volatile
        var membership: Set<String> = emptySet()
            private set

        private val adds = mutableMapOf<String, MutableSet<Timestamp>>()
        private val dels = mutableMapOf<String, MutableSet<Timestamp>>()

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) = apply(value)
            })
        }

        private fun apply(delta: SetDelta<String>) {
            synchronized(this) {
                delta.adds.forEach { (element, tags) ->
                    adds.getOrPut(element) { mutableSetOf() } += tags
                }
                delta.dels.forEach { (element, tags) ->
                    dels.getOrPut(element) { mutableSetOf() } += tags
                }
                membership = adds.keys.filterTo(mutableSetOf()) { element ->
                    (adds.getValue(element) - dels[element].orEmpty()).isNotEmpty()
                }
            }
        }
    }
}
