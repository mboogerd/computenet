package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.graphalytics.algorithms.GraphalyticsWireSerializers
import civictech.bench.graphalytics.algorithms.Lcc
import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.graph
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.wire.WireCodec
import civictech.testkit.awaitDrained
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal enum class RunPhase { LOAD, RECOVER, COMPUTE }

private data class PhaseStart(
    val startedNanos: Long,
    val awaitedSteps: Long,
)

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

    /**
     * Rebuild the edge relation from the synchronous WAL at [journalDir], then
     * run the ordinary compute and validation phase without re-ingesting edges.
     *
     * The completed pre-crash run must have used a [FileJournal] opened at
     * [journalPath]. Recovery opens a fresh journal instance for that same file.
     */
    fun recoverAndCompute(
        dataset: DatasetRef,
        journalDir: Path,
        drive: Drive,
        seed: Long? = null,
        env: RunEnvironment,
    ): GraRecord {
        val journal: Journal? = FileJournal(journalPath(dataset, journalDir).toFile())
        return recoverLoaded(
            dataset = dataset,
            loaded = GraphLoader.load(dataset),
            drive = drive,
            seed = seed,
            journal = journal,
            env = env,
        )
    }

    /** File used by both the completed run and its fresh-host recovery. */
    internal fun journalPath(dataset: DatasetRef, journalDir: Path): Path =
        journalDir.resolve("${dataset.name}-edges.journal")

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
            val loadStarted = rig.beginPhase()
            val edges = spawnEdges(rig, dataset)
            val edgeApi = rig.host.lookup<SetApi<Edge>>(edgeRef(dataset))
                ?: error("loaded edge relation ${edgeRef(dataset)} was not hosted")
            loaded.edges.forEach(edgeApi.inlet.call::add)
            val loadFigure = rig.completePhase(RunPhase.LOAD, loadStarted)

            verifyLoadFidelity(loaded, edges.membership().size)
            return computeAndValidate(
                dataset = dataset,
                loaded = loaded,
                edges = edges,
                lccBuilder = lccBuilder,
                rig = rig,
                loadFigure = loadFigure,
                rawProperties = rawProperties,
                sourceCommit = sourceCommit,
                drive = drive,
                journal = journal,
                dispatchBatch = dispatchBatch,
                env = env,
                recovered = null,
            )
        } finally {
            rig.close()
        }
    }

    private fun recoverLoaded(
        dataset: DatasetRef,
        loaded: LoadedGraph,
        drive: Drive,
        seed: Long?,
        journal: Journal?,
        env: RunEnvironment,
    ): GraRecord {
        val lccBuilder = Lcc.builder()
        val rawProperties = rawProperties(dataset)
        val sourceCommit = sourceCommit()
        journal?.let { ensureWireSerializers() }
        val rig = rig(drive, seed, journal, dispatchBatch = 1, hooks = GraRunHooks())

        try {
            val edges = spawnEdges(rig, dataset)
            val recoveryStarted = rig.beginPhase()
            journal?.let(rig.host::recoverFrom)
            val recoveryFigure = rig.completePhase(RunPhase.RECOVER, recoveryStarted)

            verifyLoadFidelity(loaded, edges.membership().size)
            return computeAndValidate(
                dataset = dataset,
                loaded = loaded,
                edges = edges,
                lccBuilder = lccBuilder,
                rig = rig,
                loadFigure = recoveryFigure,
                rawProperties = rawProperties,
                sourceCommit = sourceCommit,
                drive = drive,
                journal = journal,
                dispatchBatch = 1,
                env = env,
                recovered = true,
            )
        } finally {
            rig.close()
        }
    }

    private fun spawnEdges(rig: RunRig, dataset: DatasetRef): SetCell<Edge> {
        lateinit var edges: SetCell<Edge>
        graph(rig.host.managementInlet) {
            edges = spawn(
                name = "graphalytics-edges",
                identity = IdentityBinding.Exact(edgeRef(dataset)),
            ) { ref -> SetCell<Edge>(ref) }.cell
        }
        return edges
    }

    private fun edgeRef(dataset: DatasetRef): CellRef = CellRef(
        UUID.nameUUIDFromBytes(
            "civictech.bench.graphalytics/${dataset.name}/edges"
                .toByteArray(StandardCharsets.UTF_8),
        ),
    )

    private fun computeAndValidate(
        dataset: DatasetRef,
        loaded: LoadedGraph,
        edges: SetCell<Edge>,
        lccBuilder: Lcc.Builder,
        rig: RunRig,
        loadFigure: Figure,
        rawProperties: Map<String, String>,
        sourceCommit: String,
        drive: Drive,
        journal: Journal?,
        dispatchBatch: Int,
        env: RunEnvironment,
        recovered: Boolean?,
    ): GraRecord {
        val computeStarted = rig.beginPhase()
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
            recovered = recovered,
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
            val scheduler = AwaitCountingScheduler(controller, controller.scheduler())
            val host = hooks.hostFactory(scheduler, journal, dispatchBatch)
            object : RunRig {
                override val host: ManagedHost = host
                override val schedulerLabel: String = "SimulationController"

                override fun beginPhase(): PhaseStart = PhaseStart(
                    startedNanos = System.nanoTime(),
                    awaitedSteps = scheduler.awaitedSteps,
                )

                override fun completePhase(phase: RunPhase, started: PhaseStart): Figure {
                    val stepsConsumedByAwait = scheduler.awaitedSteps - started.awaitedSteps
                    val stepsDrainedAtEnd = controller.runToIdle().toLong()
                    hooks.phaseQuiesced(phase)
                    return Figure.Work(stepsConsumedByAwait + stepsDrainedAtEnd)
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

                override fun beginPhase(): PhaseStart = PhaseStart(
                    startedNanos = System.nanoTime(),
                    awaitedSteps = 0L,
                )

                override fun completePhase(phase: RunPhase, started: PhaseStart): Figure {
                    scheduler.awaitDrained("Graphalytics ${phase.name.lowercase()} phase")
                    hooks.phaseQuiesced(phase)
                    return Figure.Duration(
                        nanos = System.nanoTime() - started.startedNanos,
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
        fun beginPhase(): PhaseStart
        fun completePhase(phase: RunPhase, started: PhaseStart): Figure
    }

    /**
     * observed: graph-DSL spawn/connect calls are awaited host-management calls.
     * [SimulationController]'s scheduler advances the controller inside [HostScheduler.await],
     * so link-time catch-up can consume all compute work before a later `runToIdle()` starts.
     * Count those exact controller steps here, then add the final drain's own step count.
     */
    private class AwaitCountingScheduler(
        private val controller: SimulationController,
        private val delegate: HostScheduler,
    ) : HostScheduler by delegate {
        var awaitedSteps: Long = 0L
            private set

        override fun <T> await(future: CompletableFuture<T>): T {
            while (!future.isDone) {
                check(controller.step()) { "simulation quiescent but awaited future incomplete" }
                awaitedSteps++
            }
            return delegate.await(future)
        }
    }
}
