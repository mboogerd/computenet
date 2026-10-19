package civictech.demo.studio.typespike

import civictech.cell.*
import civictech.cell.host.*
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.demo.studio.*
import civictech.nature.ContractRegistry
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID

@Timeout(30)
class KernelTypesSpikeTest {
    private fun cell(kind: String, command: String = "echo {{c.repo}}") = StudioCell(CellRef(UUID.randomUUID()),
        StudioDefinition(UUID.randomUUID().toString(), kind, nodeConfig(kind,
            if (kind == "exec") obj("command" to JsonPrimitive(command)) else obj())))

    @Test fun sameStudioClassHasNoPayloadNatureAndMismatchedWireIsAdmitted() {
        val sim = SimulationController(17); val scheduler = sim.scheduler()
        try {
            val host = ManagedHost(registry = LocationRegistry(), scheduler = scheduler)
            val text = cell("text"); val git = cell("git"); val target = cell("exec")
            listOf(text, git, target).forEach { host.managementInlet.call.spawn(it) }; sim.runToIdle()
            assertEquals(text.javaClass, git.javaClass)
            assertEquals(ContractRegistry.cellDescriptor(text.javaClass), ContractRegistry.cellDescriptor(git.javaClass))
            assertTrue(text.outlet.natures.isDefault); assertTrue(target.inlet.natures.isDefault)
            val result = host.managementInlet.call.connect(text.ref, "outlet", target.ref, "inlet", LinkOptions(staged = true))
            assertInstanceOf(LinkResult.Connected::class.java, result)
            (result as LinkResult.Connected).link.unlink()
        } finally { scheduler.shutdown() }
    }

    @Test fun publicPerInstanceLinkPolicyMakesKernelRefuseBeforeInstallingAnything() {
        val sim = SimulationController(18); val scheduler = sim.scheduler()
        try {
            val host = ManagedHost(registry = LocationRegistry(), scheduler = scheduler)
            val text = cell("text"); val git = cell("git"); val target = cell("exec")
            val offered = mapOf(text.ref to Output(Kind.text), git.ref to Output(Kind.gitRange))
            val requirement = infer("{{c.repo}}").getValue("c")
            target.inlet.linking.policies += LinkPolicy { request ->
                val check = satisfies(offered.getValue(requireNotNull(request.from.cell)), requirement)
                check.message?.takeIf { check.admission == Admission.REJECT }?.let { LinkResult.Rejected(it) }
            }
            listOf(text, git, target).forEach { host.managementInlet.call.spawn(it) }; sim.runToIdle()
            val rejected = host.managementInlet.call.connect(text.ref, "outlet", target.ref, "inlet", LinkOptions(staged = true))
            assertInstanceOf(LinkResult.Rejected::class.java, rejected)
            rejected as LinkResult.Rejected
            assertNull(rejected.mismatch) // policy refusal, NOT NatureMismatch
            assertEquals("c: requires field 'repo'; text offers new, old", rejected.reason)
            assertTrue(text.outlet.linking.links.isEmpty()); assertTrue(target.inlet.linking.links.isEmpty())
            val accepted = host.managementInlet.call.connect(git.ref, "outlet", target.ref, "inlet", LinkOptions(staged = true))
            assertInstanceOf(LinkResult.Connected::class.java, accepted)
            assertEquals(1, target.inlet.linking.links.size)
            (accepted as LinkResult.Connected).link.unlink()
        } finally { scheduler.shutdown() }
    }
}
