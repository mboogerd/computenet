package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.graphalytics.algorithms.GraphalyticsWireSerializers
import civictech.bench.graphalytics.algorithms.Lcc
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.Journal
import civictech.cell.graph.graph
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.wire.WireCodec
import civictech.testkit.awaitDrained
import java.util.Properties

internal enum class RunPhase { LOAD, COMPUTE }

internal class GraRunHooks(
    val hostFactory: (HostScheduler, Journal?, Int) -> ManagedHost = { scheduler, journal, dispatchBatch ->
        ManagedHost(scheduler = scheduler, journal = journal, dispatchBatch = dispatchBatch)
    },
    val phaseQuiesced: (RunPhase) -> Unit = {},
)

/** The two-phase Graphalytics load, fidelity, compute, and validation protocol. */
object GraRun {

    fun run(
        dataset: DatasetRef,
        algorithm: Algorithm = Algorithm.LCC,
        drive: Drive,
        seed: Long? = null,
        journal: Journal? = null,
        dispatchBatch: Int = 1,
        env: RunEnvironment,
    ): GraRecord = runLoaded(
        dataset = dataset,
        loaded = GraphLoader.load(dataset),
        algorithm = algorithm,
        drive = drive,
        seed = seed,
        journal = journal,
        dispatchBatch = dispatchBatch,
        env = env,
    )

    internal fun runLoaded(
        dataset: DatasetRef,
        loaded: LoadedGraph,
        algorithm: Algorithm = Algorithm.LCC,
        drive: Drive,
        seed: Long? = null,
        journal: Journal? = null,
        dispatchBatch: Int = 1,
        env: RunEnvironment,
        vertexPayload: Class<*> = java.lang.Long::class.java,
        hooks: GraRunHooks = GraRunHooks(),
    ): GraRecord {
        require(algorithm == Algorithm.LCC) {
            "GraRun's non-iterative slice executes LCC only; $algorithm requires the E5 follow-on"
        }
        require(dispatchBatch >= 1) { "dispatchBatch must be >= 1, was $dispatchBatch" }

        // This is intentionally before rig construction: [BB-GRA-28] refuses an
        // exclusive payload without constructing a ManagedHost.
        val lccBuilder = Lcc.builder(vertexPayload)
        val rawProperties = rawProperties(dataset)
        val sourceCommit = sourceCommit()
        if (journal != null) ensureWireSerializers()
        val rig = rig(drive, seed, journal, dispatchBatch, hooks)

        try {
            lateinit var edges: SetCell<Edge>
            lateinit var edgeRef: civictech.cell.CellRef

            val loadStarted = System.nanoTime()
            graph(rig.host.managementInlet) {
                val handle = spawn("graphalytics-edges") { ref -> SetCell<Edge>(ref) }
                edges = handle.cell
                edgeRef = handle.ref
            }
            val edgeApi = rig.host.lookup<SetApi<Edge>>(edgeRef)
                ?: error("loaded edge relation $edgeRef was not hosted")
            loaded.edges.forEach(edgeApi.inlet.call::add)
            val loadFigure = rig.completePhase(RunPhase.LOAD, loadStarted)

            verifyLoadFidelity(loaded, edges.membership().size)

            val computeStarted = System.nanoTime()
            val lcc = lccBuilder.attach(rig.host.managementInlet, edges)
            val computeFigure = rig.completePhase(RunPhase.COMPUTE, computeStarted)
            val actual = lcc.read(loaded.vertices)
            val validation = validate(
                algorithm = Algorithm.LCC,
                expected = Datasets.referenceOutput(dataset, Algorithm.LCC),
                actual = actual,
            )
            val header = RunHeader(
                dataset = DatasetFacts(
                    name = loaded.name,
                    declaredVertices = loaded.declaredVertices,
                    declaredEdges = loaded.declaredEdges,
                    sourceCommit = sourceCommit,
                ),
                algorithm = Algorithm.LCC,
                parameters = rawProperties,
                drive = drive,
                scheduler = rig.schedulerLabel,
                loadMode = loadMode(journal, dispatchBatch),
                env = env,
                iterations = Iterations.NonIterative,
                retainedState = lcc.retainedState(edges.membership().size.toLong()),
            )

            return when (validation) {
                is Validation.Passed -> GraRecord.Validated(
                    header = header,
                    load = loadFigure,
                    compute = computeFigure,
                    validation = validation,
                )

                is Validation.Failed -> GraRecord.Failed(header, validation)
            }
        } finally {
            rig.close()
        }
    }

    private fun verifyLoadFidelity(loaded: LoadedGraph, relationRows: Int) {
        val expectedRelationRows = if (loaded.symmetrised) {
            Math.multiplyExact(loaded.declaredEdges, 2)
        } else {
            loaded.declaredEdges
        }
        val vertexRows = loaded.vertices.size
        val suppliedEdgeRows = loaded.edges.size
        if (
            vertexRows != loaded.declaredVertices ||
            suppliedEdgeRows != expectedRelationRows ||
            relationRows != expectedRelationRows
        ) {
            throw LoadFidelityException(
                "Dataset '${loaded.name}' failed the post-load fidelity gate: " +
                    "vertices declared=${loaded.declaredVertices} supplied=$vertexRows; " +
                    "edge relation expected=$expectedRelationRows supplied=$suppliedEdgeRows loaded=$relationRows",
            )
        }
    }

    private fun rawProperties(dataset: DatasetRef): Map<String, String> {
        val properties = Properties().also { props ->
            dataset.open("${dataset.name}.properties").bufferedReader().use(props::load)
        }
        return properties.stringPropertyNames().sorted().associateWith(properties::getProperty)
    }

    private fun sourceCommit(): String {
        val source = Datasets::class.java.getResourceAsStream("/graphalytics/SOURCE.md")
            ?: error("Missing /graphalytics/SOURCE.md")
        val text = source.bufferedReader().use { it.readText() }
        return Regex("commit\\s+`([0-9a-f]{40})`")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: error("SOURCE.md does not name a 40-character source commit")
    }

    private val wireSerializersRegistered: Unit by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WireCodec.contribute(GraphalyticsWireSerializers)
    }

    private fun ensureWireSerializers() {
        wireSerializersRegistered
    }

    private fun loadMode(journal: Journal?, dispatchBatch: Int): LoadModeLabel {
        val durability = when (journal?.durability) {
            null -> "volatile"
            DurabilityClass.SYNCHRONOUS -> "WAL-bound"
            DurabilityClass.IN_MEMORY -> "in-memory"
            DurabilityClass.BATCHED -> {
                require(journal is BatchedFileJournal) {
                    "BATCHED durability requires BatchedFileJournal so syncEvery can be recorded"
                }
                "batched(syncEvery=${journal.syncEvery})"
            }
        }
        val ingress = if (dispatchBatch == 1) "per-message" else "batched($dispatchBatch)"
        return LoadModeLabel(durability, ingress)
    }

    private fun rig(
        drive: Drive,
        seed: Long?,
        journal: Journal?,
        dispatchBatch: Int,
        hooks: GraRunHooks,
    ): RunRig = when (drive) {
        Drive.SIM -> {
            val controller = SimulationController(seed)
            val scheduler = controller.scheduler()
            val host = hooks.hostFactory(scheduler, journal, dispatchBatch)
            object : RunRig {
                override val host: ManagedHost = host
                override val schedulerLabel: String = "SimulationController"

                override fun completePhase(phase: RunPhase, startedNanos: Long): Figure {
                    val steps = controller.runToIdle().toLong()
                    hooks.phaseQuiesced(phase)
                    return Figure.Work(steps)
                }

                override fun close() = scheduler.shutdown()
            }
        }

        Drive.REAL -> {
            val scheduler = VirtualThreadScheduler("bench-graphalytics-lcc")
            val host = hooks.hostFactory(scheduler, journal, dispatchBatch)
            object : RunRig {
                override val host: ManagedHost = host
                override val schedulerLabel: String = "VirtualThreadScheduler"

                override fun completePhase(phase: RunPhase, startedNanos: Long): Figure {
                    scheduler.awaitDrained("Graphalytics ${phase.name.lowercase()} phase")
                    hooks.phaseQuiesced(phase)
                    return Figure.Duration(
                        nanos = System.nanoTime() - startedNanos,
                        dispersionNanos = 0,
                    )
                }

                override fun close() = scheduler.shutdown()
            }
        }
    }

    private interface RunRig : AutoCloseable {
        val host: ManagedHost
        val schedulerLabel: String
        fun completePhase(phase: RunPhase, startedNanos: Long): Figure
    }
}
