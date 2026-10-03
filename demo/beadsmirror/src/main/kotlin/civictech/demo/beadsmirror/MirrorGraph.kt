package civictech.demo.beadsmirror

import civictech.cell.Propagate
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.graph.AppliedGraph
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DurableInput
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorCellCapture
import civictech.demo.beadsmirror.projector.MirrorCellFactory
import civictech.demo.beadsmirror.projector.MirrorCellKind
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorCells
import civictech.demo.beadsmirror.projector.MirrorDeltaInlet
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.runtime.Runtime
import java.nio.file.Files
import java.nio.file.Path

/**
 * The hosted, journaled graph behind one workspace mirror, independent of solo or peered mode.
 * Both modes apply the same pinned-ref [spec], expose the same durable feed input, and rebuild a
 * projector only from cells materialized by [MirrorCellFactory].
 */
class MirrorGraph internal constructor(
    val host: ManagedHost,
    val refs: MirrorCellRefs,
    val replicated: Boolean,
    val recovered: Boolean,
    private val applyGraph: (GraphSpec) -> AppliedGraph,
    private val checkpointGraph: () -> Unit,
    private val closeGraph: () -> Unit,
) : AutoCloseable {

    /** The complete first-start graph; the same steps are reused after a despawn during a swap. */
    fun spec(): GraphSpec = spec(refs, replicated)

    /** Apply one topology delta through this graph's topology journal. */
    fun apply(spec: GraphSpec): AppliedGraph = applyGraph(spec)

    /** Compact the recovered journal only after replay has completely applied. */
    fun checkpoint() = checkpointGraph()

    /**
     * Attach application reads and hosted writes to the concrete cells materialized by [applied]
     * (or by topology recovery when [applied] is `null`).
     */
    internal fun projector(
        minter: DotMinter,
        applied: AppliedGraph? = null,
        factory: (
            DotMinter,
            MirrorCells,
            Propagate<TaggedMapDelta<MirrorKey, String>>,
            Propagate<SetDelta<MirrorEdge>>,
        ) -> MirrorProjector = { projectorMinter, cells, mapInlet, edgeInlet ->
            MirrorProjector(
                minter = projectorMinter,
                cell = cells.cell,
                edges = cells.edges,
                mapInlet = mapInlet,
                edgeInlet = edgeInlet,
            )
        },
    ): MirrorProjector {
        applied?.refs?.getValue(MAP_HANDLE)?.let { check(it == refs.mapRef) }
        applied?.refs?.getValue(EDGES_HANDLE)?.let { check(it == refs.edgeRef) }
        val cells = MirrorCellCapture.take(refs.mapRef, refs.edgeRef)
        return factory(
            minter,
            cells,
            deltaInlet(host, refs.mapRef),
            deltaInlet(host, refs.edgeRef),
        )
    }

    /** The feed input returned by this apply, or re-derived from the recovered host. */
    fun input(applied: AppliedGraph? = null): DurableInput =
        applied?.inputs?.getValue(MAP_HANDLE)?.getValue(FEED_INPUT)
            ?: host.durableInput(refs.mapRef, FEED_INPUT)

    override fun close() = closeGraph()

    companion object {
        internal const val MAP_HANDLE = "map"
        internal const val EDGES_HANDLE = "edges"
        internal const val JOURNAL_ID = "main"
        internal const val FEED_INPUT = "feed"

        /** Solo composition: the same journal layout and recovery order as [Runtime.boot]. */
        fun solo(runDir: Path, identity: String): MirrorGraph {
            Files.createDirectories(runDir)
            val refs = MirrorCellRefs(identity, MirrorCellRefs.LISTENER)
            val journal = checkNotNull(KeyedCells.hostJournal(runDir.resolve(JOURNAL_ID).toFile()))
            val registry = LocationRegistry()
            lateinit var context: ApplyContext
            val host = ManagedHost(
                registry = registry,
                journalFor = { ref -> context.journalFor(ref) },
            )
            context = ApplyContext(
                host = host,
                journals = mapOf(JOURNAL_ID to journal),
                topology = journal,
            )
            val recovered = journal.replay().isNotEmpty()
            if (recovered) {
                context.recover(journal).awaitApplied(30_000)
                requireRecoveredHandles(context.handles, refs, runDir.resolve(JOURNAL_ID))
                host.checkpoint(journal)
            } else {
                spec(refs, replicated = false).apply(context)
            }
            return MirrorGraph(
                host = host,
                refs = refs,
                replicated = false,
                recovered = recovered,
                applyGraph = { it.apply(context) },
                checkpointGraph = { host.checkpoint(journal) },
                closeGraph = {},
            )
        }

        internal fun runtime(node: Runtime.Node, refs: MirrorCellRefs): MirrorGraph {
            if (node.recovered) requireRecoveredHandles(node.refs, refs, Path.of(node.name))
            return MirrorGraph(
                host = node.mainHost,
                refs = refs,
                replicated = true,
                recovered = node.recovered,
                applyGraph = node::apply,
                checkpointGraph = { node.mainHost.checkpoint(node.journals.getValue(JOURNAL_ID)) },
                closeGraph = node::close,
            )
        }

        internal fun spec(refs: MirrorCellRefs, replicated: Boolean): GraphSpec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = MAP_HANDLE,
                    factory = MirrorCellFactory(MirrorCellKind.MAP),
                    identity = IdentityBinding.Exact(refs.mapRef),
                    replicated = replicated,
                    journalId = JOURNAL_ID,
                    inputs = setOf(FEED_INPUT),
                ),
                SpawnStep(
                    handle = EDGES_HANDLE,
                    factory = MirrorCellFactory(MirrorCellKind.EDGES),
                    identity = IdentityBinding.Exact(refs.edgeRef),
                    replicated = replicated,
                    journalId = JOURNAL_ID,
                ),
            ),
        )

        private fun requireRecoveredHandles(
            handles: Map<String, civictech.cell.CellRef>,
            refs: MirrorCellRefs,
            journalPath: Path,
        ) {
            val expected = mapOf(MAP_HANDLE to refs.mapRef, EDGES_HANDLE to refs.edgeRef)
            expected.entries.firstOrNull { handles[it.key] != it.value }?.let { missing ->
                error(
                    "recovered beadsmirror topology in '$journalPath' does not contain " +
                        "${missing.key}=${missing.value}",
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <D> deltaInlet(host: ManagedHost, ref: civictech.cell.CellRef): Propagate<D> =
            (checkNotNull(host.lookup(ref, MirrorDeltaInlet::class.java)) as MirrorDeltaInlet<D>)
                .deltaInlet.call
    }
}
