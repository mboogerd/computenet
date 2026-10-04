package civictech.demo.beadsmirror.projector

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.durability.InMemoryJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID

/** a3v8u-D5: hosted projector writes cross the durable intake and factories survive topology serialization. */
class HostedProjectorTest {

    @Suppress("UNCHECKED_CAST")
    private fun <D> deltaInlet(host: ManagedHost, ref: CellRef): Propagate<D> =
        (checkNotNull(host.lookup(ref, MirrorDeltaInlet::class.java)) as MirrorDeltaInlet<D>)
            .deltaInlet.call

    private fun record(): ChangeRecord = ChangeRecord(
        commitHash = "commit-1",
        position = FeedPosition(1, 0),
        issueId = "B",
        diffType = DiffType.ADDED,
        fieldDiffs = listOf(FieldDiff("status", old = null, new = JsonPrimitive("open"))),
        edgeDiffs = listOf(EdgeDiff(DiffType.ADDED, "B", "A", "blocks")),
    )

    @Test
    fun `hosted projector writes both cells through the journaled host intake`() {
        val scheduler = VirtualThreadScheduler("beadsmirror-hosted-projector-test")
        try {
            val journal = InMemoryJournal()
            val registry = LocationRegistry()
            lateinit var context: ApplyContext
            val host = ManagedHost(
                scheduler = scheduler,
                registry = registry,
                journalFor = { ref -> context.journalFor(ref) },
            )
            context = ApplyContext(host, journals = mapOf("main" to journal))
            val refs = MirrorCellRefs("hosted-projector-test", MirrorCellRefs.LISTENER)
            GraphSpec(
                listOf(
                    SpawnStep(
                        handle = "map",
                        factory = MirrorCellFactory(MirrorCellKind.MAP),
                        identity = IdentityBinding.Exact(refs.mapRef),
                        journalId = "main",
                    ),
                    SpawnStep(
                        handle = "edges",
                        factory = MirrorCellFactory(MirrorCellKind.EDGES),
                        identity = IdentityBinding.Exact(refs.edgeRef),
                        journalId = "main",
                    ),
                )
            ).apply(context)
            host.quiescence().await(30_000, "beadsmirror hosted projector spawn")

            val cells = MirrorCellCapture.take(refs.mapRef, refs.edgeRef)
            val projector = MirrorProjector(
                minter = DotMinter("hosted-projector-test"),
                cell = cells.cell,
                edges = cells.edges,
                mapInlet = deltaInlet<TaggedMapDelta<MirrorKey, String>>(host, refs.mapRef),
                edgeInlet = deltaInlet<SetDelta<MirrorEdge>>(host, refs.edgeRef),
            )
            fun frameCount(): Int = journal.replay()
                .count { JournalRecords.decode(it) is DecodedJournalRecord.Frame }
            val framesBefore = frameCount()

            projector.apply(record())
            host.quiescence().await(30_000, "beadsmirror hosted projector write")

            projector.view() shouldBe mapOf("B" to mapOf("status" to "\"open\""))
            projector.edgeView() shouldBe setOf(MirrorEdge("B", "A", "blocks"))
            frameCount() shouldBe framesBefore + 2
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `mirror factories round trip through Java serialization and publish their cells`() {
        fun roundTrip(factory: MirrorCellFactory): MirrorCellFactory {
            val bytes = ByteArrayOutputStream().also { output ->
                ObjectOutputStream(output).use { it.writeObject(factory) }
            }.toByteArray()
            return ObjectInputStream(ByteArrayInputStream(bytes)).use {
                it.readObject().shouldBeInstanceOf<MirrorCellFactory>()
            }
        }

        val mapRef = CellRef(UUID.nameUUIDFromBytes("mirror-factory-map".toByteArray()))
        val map = roundTrip(MirrorCellFactory(MirrorCellKind.MAP)).create(mapRef)
        map.shouldBeInstanceOf<OrMapCell<*, *>>()
        MirrorCellCapture.take(mapRef) shouldBe map

        val edgeRef = CellRef(UUID.nameUUIDFromBytes("mirror-factory-edges".toByteArray()))
        val edges = roundTrip(MirrorCellFactory(MirrorCellKind.EDGES)).create(edgeRef)
        edges.shouldBeInstanceOf<SetCell<*>>()
        MirrorCellCapture.take(edgeRef) shouldBe edges
    }
}
