package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.data.CountCell
import civictech.cell.data.SetCell
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * W3.6 (G-51 core): the spawn-step parameters (identity/parent/factory),
 * [IdentityBinding]s, and the [HostManagementApi.spawnBound][civictech.cell.host.HostManagementApi.spawnBound]
 * wire form — exercised through [GraphSpec.applyRemote], which degrades loud
 * construction failure from synchronous (co-located [GraphSpec.applyTo]) to
 * asynchronous dead-letter reporting (93 I-21 §4.4).
 */
class GraphSpecRemoteApplyTest {

    private fun deadLettersOf(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : civictech.cell.Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                letters += value
            }
        }, PortRef.generate()))
        return letters
    }

    @Test
    fun `Exact re-apply of a live ref is an idempotent, dead-lettered rejection`() {
        val controller = SimulationController(seed = 7)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = deadLettersOf(host)

        val liveRef = CellRef(java.util.UUID.randomUUID())
        host.managementInlet.call.spawn(SetCell<String>(ref = liveRef))

        val spec = GraphSpec(
            listOf(SpawnStep("dup", CellFactory { ref -> SetCell<String>(ref = ref) }, IdentityBinding.Exact(liveRef))),
        )

        val report = spec.applyRemote(host.managementInlet)

        // loud: observably dead-lettered on the target host...
        letters.size shouldBe 1
        letters[0].description shouldContain liveRef.toString()
        // ...but asynchronous: the applier never sees a thrown exception —
        // it gets a structured, non-throwing report instead.
        (report.results.getValue("dup") is StepResult.Rejected) shouldBe true
        report.allApplied shouldBe false

        // idempotent: re-applying again is *still* just one more observed rejection —
        // no crash, no duplicate, no change in host state.
        val secondReport = spec.applyRemote(host.managementInlet)
        (secondReport.results.getValue("dup") is StepResult.Rejected) shouldBe true
        letters.size shouldBe 2
    }

    @Test
    fun `a remote replay with one rejecting step still applies the remaining steps (partial + report)`() {
        val controller = SimulationController(seed = 8)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = deadLettersOf(host)

        val liveRef = CellRef(java.util.UUID.randomUUID())
        host.managementInlet.call.spawn(SetCell<String>(ref = liveRef))

        val spec = GraphSpec(
            listOf(
                // rejecting: re-applies a live ref (G-16 idempotent-reject guard)
                SpawnStep("dup", CellFactory { ref -> SetCell<String>(ref = ref) }, IdentityBinding.Exact(liveRef)),
                // accepting: an ordinary fresh spawn, applied via the same wire form
                SpawnStep("count", CellFactory { ref -> CountCell<String>(ref = ref) }),
            ),
        )

        val report = spec.applyRemote(host.managementInlet)

        (report.results.getValue("dup") is StepResult.Rejected) shouldBe true
        val countResult = report.results.getValue("count")
        (countResult is StepResult.Applied) shouldBe true
        report.allApplied shouldBe false

        // the target host observed exactly the one rejection, loudly
        letters.size shouldBe 1

        // the accepted step really did construct a live cell reachable on the host:
        // re-spawning at the same ref now itself hits the live-ref guard.
        val countRef = (countResult as StepResult.Applied).ref!!
        val redup = spec.copy(
            steps = listOf(SpawnStep("count2", CellFactory { ref -> CountCell<String>(ref = ref) }, IdentityBinding.Exact(countRef))),
        ).applyRemote(host.managementInlet)
        (redup.results.getValue("count2") is StepResult.Rejected) shouldBe true
        letters.size shouldBe 2
    }
}
