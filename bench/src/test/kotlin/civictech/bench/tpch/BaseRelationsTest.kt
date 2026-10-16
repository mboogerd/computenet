package civictech.bench.tpch

import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BaseRelationsTest {

    @Test
    fun `SIM phase counts a drained scheduler step once`() {
        lateinit var scheduler: HostScheduler
        val rig = TpchRig.open(
            drive = Drive.SIM,
            seed = 7L,
            hooks = TpchRigHooks(
                hostFactory = { phaseScheduler, journal, dispatchBatch ->
                    scheduler = phaseScheduler
                    ManagedHost(
                        scheduler = phaseScheduler,
                        journal = journal,
                        dispatchBatch = dispatchBatch,
                    )
                },
            ),
        )
        try {
            rig.beginPhase()
            scheduler.submit(priority = 0) {}
            val figure = assertInstanceOf(Figure.Work::class.java, rig.completePhase("one-step"))
            assertEquals(1L, figure.steps)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `SIM load and refreshes quiesce with work figures`() {
        val base = TpchGenerator.generate(seed = 7L, sf = 0.001)
        val rig = TpchRig.open(Drive.SIM, seed = 7L)
        try {
            val relations = BaseRelations.spawn(rig)
            val loadFigure = rig.beginPhase().let {
                relations.load(base)
                rig.completePhase("load")
            }
            assertInstanceOf(Figure.Work::class.java, loadFigure)
            // Ingress must pass through the host scheduler: a bare-cell call reads 0.
            assertTrue((loadFigure as Figure.Work).steps > 0, "SIM load work was $loadFigure")
            assertEquals(LogicalTables(base).counts(), relations.membership())

            val rf1 = RefreshStream.generate(
                RefreshPlan(seed = 9L, sf = 0.001, batches = listOf(RefreshKind.RF1)),
                base,
            ).single()
            rig.beginPhase()
            relations.apply(rf1)
            val rf1Figure = rig.completePhase("RF1")
            assertInstanceOf(Figure.Work::class.java, rf1Figure)
            assertTrue((rf1Figure as Figure.Work).steps > 0, "SIM RF1 work was $rf1Figure")

            val rf2 = RefreshBatch.Rf2((rf1 as RefreshBatch.Rf1).orders.map { it.orderKey }.sorted())
            rig.beginPhase()
            relations.apply(rf2)
            val rf2Figure = rig.completePhase("RF2")
            assertInstanceOf(Figure.Work::class.java, rf2Figure)
            assertTrue((rf2Figure as Figure.Work).steps > 0, "SIM RF2 work was $rf2Figure")
            assertEquals(base, relations.logicalTables.snapshot())
            assertEquals(LogicalTables(base).counts(), relations.membership())
            assertRowsMatch(relations)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `REAL load and refreshes quiesce with duration figures`() {
        val base = TpchGenerator.generate(seed = 7L, sf = 0.001)
        val rig = TpchRig.open(Drive.REAL, seed = 7L)
        try {
            val relations = BaseRelations.spawn(rig)
            rig.beginPhase()
            relations.load(base)
            assertInstanceOf(Figure.Duration::class.java, rig.completePhase("load"))
            assertEquals(LogicalTables(base).counts(), relations.membership())

            val rf1 = RefreshStream.generate(
                RefreshPlan(seed = 9L, sf = 0.001, batches = listOf(RefreshKind.RF1)),
                base,
            ).single() as RefreshBatch.Rf1
            rig.beginPhase()
            relations.apply(rf1)
            assertInstanceOf(Figure.Duration::class.java, rig.completePhase("RF1"))
            assertEquals(relations.logicalTables.counts(), relations.membership())
            assertRowsMatch(relations)

            rig.beginPhase()
            relations.apply(RefreshBatch.Rf2(rf1.orders.map { it.orderKey }.sorted()))
            assertInstanceOf(Figure.Duration::class.java, rig.completePhase("RF2"))
            assertEquals(LogicalTables(base).counts(), relations.membership())
            assertRowsMatch(relations)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `load modes expose KBLK labels and synchronous WAL`() {
        assertEquals(LoadModes.names, listOf("volatile", "wal-bound", "batched-8", "batched-ingress-64"))
        assertEquals(LoadModeLabel("volatile", "per-message"), loadModeLabel(null, 1))
        assertEquals(LoadModeLabel("in-memory", "batched(64)"), loadModeLabel(InMemoryJournal(), 64))
    }

    @Test
    fun `a synchronous file journal is labelled WAL-bound`(@TempDir root: Path) {
        val journal = FileJournal(root.resolve("tpch.journal").toFile())
        assertEquals(LoadModeLabel("WAL-bound", "per-message"), loadModeLabel(journal, 1))
        assertTrue(journal.durability.name.isNotBlank())
    }

    @Test
    fun `a journal-backed SIM load can encode every row type`(@TempDir root: Path) {
        val base = TpchGenerator.generate(seed = 7L, sf = 0.0001)
        val journalFile = root.resolve("tpch-rows.journal").toFile()
        val rig = TpchRig.open(
            drive = Drive.SIM,
            seed = 7L,
            journal = FileJournal(journalFile),
        )
        try {
            val relations = BaseRelations.spawn(rig)
            rig.beginPhase()
            relations.load(base)
            rig.completePhase("journal-load")
            assertEquals(LogicalTables(base).counts(), relations.membership())
            // The rows were journalled, i.e. encoded through TpchWireSerializers.
            assertTrue(journalFile.length() > 0, "journal is empty: ingress bypassed the host")
        } finally {
            rig.close()
        }
    }

    /** Row-level, not only count-level, equality for the two refreshed relations. */
    private fun assertRowsMatch(relations: BaseRelations) {
        assertEquals(relations.logicalTables.orders.toSet(), relations.orders.membership())
        assertEquals(relations.logicalTables.lineitem.toSet(), relations.lineitem.membership())
    }
}
