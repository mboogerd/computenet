package civictech.agora

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.durability.FileJournal
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.timetravel.cli.InspectReport
import civictech.timetravel.cli.Main
import civictech.timetravel.cli.ReconstructReport
import civictech.timetravel.diff.DivergenceClass
import civictech.timetravel.diff.FidelityDto
import civictech.timetravel.diff.RunDiffReport
import civictech.timetravel.diff.StateView
import civictech.timetravel.diff.Verdict
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.reconstruct.CellStateView
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.Serializable
import java.util.UUID

/**
 * The agora time-travel walkthrough (TTD1 F7, computenet-3qkx1.3; epic computenet-ocv §7 item 6,
 * design 3qkx1-D6/D7/D12): a real agora run is recorded to a `FileJournal` plus its `graph.jsonl`
 * structure log, then `inspect`, `reconstruct` and `diff` are driven through the CLI entry point
 * [Main.run] exactly as a user would run them, with `--graph-provider civictech.agora.AgoraGraphSource`
 * supplying the topology.
 *
 * The fixture is acyclic with explicit refs (D12): on a DAG a claim's credence is a function of
 * its current stances and influences, not of arrival order, so the reconstruction — on a host with
 * no agora attention policy — must equal the live run exactly, and a second run of the same
 * script produces a journal the diff finds identical.
 */
class TimeTravelWalkthroughTest {

    /** The CLI DTOs' settings (`CANONICAL_JSON` is internal to `:timetravel`; same config, D8). */
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    private data class CliResult(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, "UTF-8").use { o ->
            PrintStream(err, true, "UTF-8").use { e -> Main.run(arrayOf(*args), o, e) }
        }
        return CliResult(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private val a = CellRef(UUID(1, 1))
    private val b = CellRef(UUID(2, 2))
    private val c = CellRef(UUID(3, 3))
    private val d = CellRef(UUID(4, 4))

    /**
     * Runs the D12 script into [dir] (`host.journal` + `graph.jsonl`) on the deterministic
     * scheduler and returns the service plus every held cell's live snapshot, captured at rest.
     */
    private fun record(dir: File, seed: Long = 11, extraClaim: Boolean = false): Pair<AgoraService, Map<CellRef, Serializable>> {
        dir.mkdirs()
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            journal = FileJournal(File(dir, "host.journal")),
        )
        val service = AgoraService(host, registry, structureLog = File(dir, "graph.jsonl"))
        service.createClaim("A", a)
        service.createClaim("B", b)
        service.createClaim("C", c)
        service.createEdge(a, b, Polarity.ATTACK, CellRef(UUID(11, 11)))
        service.createEdge(c, b, Polarity.SUPPORT, CellRef(UUID(12, 12)))
        service.setStance(a, "u1", 0.9)
        service.setStance(c, "u2", 0.8)
        service.setStance(b, "u3", 0.6)
        controller.runToIdle()
        if (extraClaim) {
            service.createClaim("D", d)
            controller.runToIdle()
        }
        return service to service.cells().associate { it.ref to (it as Stateful).snapshot() }
    }

    private val allowListDegraded = FidelityDto(Verdict.DEGRADED, listOf(Reason.UNKNOWN_DETERMINISM))

    @Test
    fun `inspect lists a record for every cell and every PORT_API frame hydrates as propagate`(@TempDir root: File) {
        val dir = File(root, "a")
        val (service, _) = record(dir)

        val result = cli("inspect", File(dir, "host.journal").path, "--json")
        withClue(result.err) { result.code shouldBe 0 }
        val journal = json.decodeFromString<InspectReport>(result.out.trim()).journals.single()

        val refsInJournal = journal.records.mapNotNull { it.cellRef }.toSet()
        refsInJournal shouldContainAll service.cells().map { it.ref.id.toString() }

        val portApi = journal.records.filter { it.type == "PORT_API" }
        portApi.shouldNotBeEmpty()
        for (record in portApi) {
            withClue("record #${record.index} ${record.label}") {
                (Reason.NO_DESCRIPTOR in record.reasons) shouldBe false
                record.methodName shouldBe "propagate"
            }
        }
    }

    @Test
    fun `reconstruct through AgoraGraphSource equals the live run, every cell degraded by the allow-list`(@TempDir root: File) {
        val dir = File(root, "a")
        val (_, live) = record(dir)
        val journalPath = File(dir, "host.journal").path

        val inspected = cli("inspect", journalPath, "--json")
        val last = json.decodeFromString<InspectReport>(inspected.out.trim()).journals.single().recordCount - 1
        val args = arrayOf(
            "reconstruct", journalPath, "--at", last.toString(),
            "--graph-provider", "civictech.agora.AgoraGraphSource", "--graph-arg", dir.path,
        )

        val result = cli(*args, "--json")
        withClue(result.err) { result.code shouldBe 0 }
        val report = json.decodeFromString<ReconstructReport>(result.out.trim())

        report.run shouldBe allowListDegraded
        val byRef = report.cells.associateBy { it.cellRef }
        live.keys.map { it.id.toString() }.toSet() shouldBe byRef.keys
        for ((ref, snapshot) in live) {
            val cell = byRef.getValue(ref.id.toString())
            withClue("cell ${ref.id} (${cell.cellClass})") {
                cell.fidelity shouldBe allowListDegraded
                cell.view.shouldNotBeNull() shouldBe StateView.of(CellStateView.of(snapshot))
            }
        }

        val text = cli(*args)
        withClue(text.err) { text.code shouldBe 0 }
        val lines = text.out.lines()
        lines.count { "UNKNOWN_DETERMINISM" in it && "allow-list" in it } shouldBe 1
        Regex("allow-list").findAll(text.out).count() shouldBe 1
    }

    @Test
    fun `diff finds a same-seed rerun identical and an extra claim ONLY_IN_B`(@TempDir root: File) {
        val dirA = File(root, "a")
        val dirB = File(root, "b")
        val dirC = File(root, "c")
        record(dirA)
        record(dirB)
        record(dirC, extraClaim = true)
        val journalA = File(dirA, "host.journal").path

        val same = cli("diff", journalA, File(dirB, "host.journal").path, "--json")
        withClue(same.err + same.out) { same.code shouldBe 0 }
        json.decodeFromString<RunDiffReport>(same.out.trim()).divergence shouldBe null

        val extra = cli("diff", journalA, File(dirC, "host.journal").path, "--json")
        withClue(extra.err) { extra.code shouldBe 1 }
        val divergence = json.decodeFromString<RunDiffReport>(extra.out.trim()).divergence
        divergence shouldNotBe null
        divergence!!.kind shouldBe DivergenceClass.ONLY_IN_B
    }
}
