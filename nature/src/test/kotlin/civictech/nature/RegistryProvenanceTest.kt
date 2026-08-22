package civictech.nature

import civictech.gen.wire.ProxyConstructor
import civictech.gen.wire.ProxyModule
import civictech.gen.wire.ProxyRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JAR1 [JAR1-REG-01..07]: module provenance, validate-then-commit registration,
 * and `unregister(owner)` across ContractRegistry / ProtocolRegistry /
 * ProxyRegistry.
 *
 * Epic risk R6 — these registries are process-global singletons shared by every
 * test in the JVM (see the once-per-classloader note at
 * `gen/src/test/kotlin/civictech/gen/wire/ContractProcessorTest.kt`). So every
 * test here registers under its OWN [ModuleId], uses probe types that cannot
 * collide with the host classpath scan, and unregisters in a `finally`.
 */

/** Probe interfaces: real classes, so [ContractRegistry.idsOf] is exercised reflectively. */
interface ProbeAlpha { fun alpha(value: Long) }
interface ProbeBeta { fun beta(value: Long) }
interface ProbeGamma { fun gamma(value: Long) }
interface ProbeIncumbent { fun incumbent(value: Long) }
interface ProbeShared { fun shared(value: Long) }
interface ProbeComplete { fun complete(value: Long) }
interface ProbeProxied { fun proxied(value: Long) }

private fun descriptorOf(iface: Class<*>, contractId: Long = StableHash.of(iface.name)): ContractDescriptor =
    ContractDescriptor(
        contractId = contractId,
        fqn = iface.name,
        management = false,
        methods = iface.declaredMethods.map { m ->
            val d = JvmDescriptors.of(m)
            MethodDescriptor(StableHash.of("${iface.name}#${m.name}$d"), m.name, d)
        },
    )

private fun moduleOf(
    contracts: List<ContractDescriptor> = emptyList(),
    cells: List<CellDescriptor> = emptyList(),
    protocols: List<ProtocolDescriptor> = emptyList(),
): ContractModule = object : ContractModule {
    override val contracts: List<ContractDescriptor> = contracts
    override val cells: List<CellDescriptor> = cells
    override val protocols: List<ProtocolDescriptor> = protocols
}

private fun proxyModuleOf(vararg entries: Pair<Class<*>, ProxyConstructor>): ProxyModule = object : ProxyModule {
    override val factories: Map<Class<*>, ProxyConstructor> = entries.toMap()
}

/** A stand-in for a KSP-generated proxy constructor; never invoked here. */
private val stubConstructor: ProxyConstructor = { _ -> Any() }

class RegistryProvenanceTest {

    /**
     * B7, red-first form, expressed against the single-module seam as it existed
     * before this change: a module contributing three contracts of which the third
     * collides must leave NONE of the three installed. Against the per-entry
     * mutating loop this failed with "alpha survived a refused registration".
     */
    @Test
    fun `b7 a refused module leaves none of its contracts installed`() {
        val incumbentOwner = ModuleId("b7-incumbent")
        val incumbent = descriptorOf(ProbeIncumbent::class.java)
        ContractRegistry.register(moduleOf(contracts = listOf(incumbent)), incumbentOwner)
        try {
            val alpha = descriptorOf(ProbeAlpha::class.java)
            val beta = descriptorOf(ProbeBeta::class.java)
            // Same contractId as the incumbent, different FQN — the refused entry.
            val gamma = descriptorOf(ProbeGamma::class.java, contractId = incumbent.contractId)

            val ex = assertFailsWith<IllegalArgumentException> {
                ContractRegistry.register(moduleOf(contracts = listOf(alpha, beta, gamma)), ModuleId("b7-late"))
            }
            val message = ex.message ?: ""
            assertTrue(ProbeIncumbent::class.java.name in message, "message lacks incumbent fqn: $message")
            assertTrue(ProbeGamma::class.java.name in message, "message lacks incoming fqn: $message")
            assertTrue(incumbent.contractId.toString() in message, "message lacks colliding id: $message")

            assertNull(ContractRegistry.contract(alpha.contractId), "alpha survived a refused registration")
            assertNull(ContractRegistry.contract(beta.contractId), "beta survived a refused registration")
            assertNull(ContractRegistry.descriptor(ProbeAlpha::class.java), "alpha resolvable by fqn after refusal")
            assertNull(
                ContractRegistry.idsOf(ProbeAlpha::class.java.declaredMethods.first()),
                "alpha's method key resolves after a refused registration",
            )
            assertEquals(incumbent, ContractRegistry.contract(incumbent.contractId), "incumbent was disturbed")
        } finally {
            ModuleRegistration.unregister(incumbentOwner)
            ModuleRegistration.unregister(ModuleId("b7-late"))
        }
    }

    /**
     * B7 in full: the contribution spans a ContractModule and a ProxyModule — two
     * separate ServiceLoader services — so only a seam that validates across both
     * can leave ProxyRegistry without a factory when a contract collides
     * [JAR1-REG-01][JAR1-REG-02].
     */
    @Test
    fun `b7 a refused contribution leaves all three registries untouched`() {
        val incumbentOwner = ModuleId("b7x-incumbent")
        val incumbent = descriptorOf(ProbeIncumbent::class.java)
        val protocol = ProtocolDescriptor("b7x-protocol", incumbent.contractId, ProtocolDirection.UPSTREAM, 0)
        ModuleRegistration.register(
            owner = incumbentOwner,
            contractModules = listOf(moduleOf(contracts = listOf(incumbent), protocols = listOf(protocol))),
        )
        try {
            val alpha = descriptorOf(ProbeAlpha::class.java)
            val beta = descriptorOf(ProbeBeta::class.java)
            val gamma = descriptorOf(ProbeGamma::class.java, contractId = incumbent.contractId)
            val proxies = proxyModuleOf(ProbeProxied::class.java to stubConstructor)

            val ex = assertFailsWith<RegistrationRefusedException> {
                ModuleRegistration.register(
                    owner = ModuleId("b7x-late"),
                    contractModules = listOf(moduleOf(contracts = listOf(alpha, beta, gamma))),
                    proxyModules = listOf(proxies),
                )
            }
            assertTrue(ProbeIncumbent::class.java.name in ex.message!!, "message lacks incumbent fqn")
            assertTrue(ProbeGamma::class.java.name in ex.message!!, "message lacks incoming fqn")

            assertNull(ContractRegistry.contract(alpha.contractId), "alpha survived a refused contribution")
            assertNull(ContractRegistry.contract(beta.contractId), "beta survived a refused contribution")
            assertNull(
                ContractRegistry.idsOf(ProbeBeta::class.java.declaredMethods.first()),
                "beta's method key resolves after a refused contribution",
            )
            assertNull(ProxyRegistry.factory(ProbeProxied::class.java), "ProxyRegistry gained a factory")
            assertEquals(protocol, ProtocolRegistry.protocol("b7x-protocol"), "incumbent protocol was disturbed")
        } finally {
            ModuleRegistration.unregister(incumbentOwner)
            ModuleRegistration.unregister(ModuleId("b7x-late"))
        }
    }

    /**
     * B6: a byte-equal descriptor from a second module is accepted idempotently and
     * recorded as an additional contributor; when it leaves, the entry still
     * resolves for the first [JAR1-REG-03][JAR1-REG-06].
     */
    @Test
    fun `b6 duplicate registration is idempotent and reversible per contributor`() {
        val a = ModuleId("b6-a")
        val b = ModuleId("b6-b")
        val shared = descriptorOf(ProbeShared::class.java)
        ContractRegistry.register(moduleOf(contracts = listOf(shared)), a)
        try {
            // A structurally-equal descriptor built independently — equality is structural.
            val equalCopy = descriptorOf(ProbeShared::class.java)
            ContractRegistry.register(moduleOf(contracts = listOf(equalCopy)), b)

            assertEquals(shared, ContractRegistry.contract(shared.contractId), "the live descriptor changed")
            assertEquals(
                listOf(a, b),
                ContractRegistry.contributorsOf(shared.contractId),
                "both modules should be recorded as contributors",
            )

            ModuleRegistration.unregister(b)
            assertEquals(
                shared,
                ContractRegistry.contract(shared.contractId),
                "the contract stopped resolving after one of two contributors left",
            )
            assertEquals(listOf(a), ContractRegistry.contributorsOf(shared.contractId))
        } finally {
            ModuleRegistration.unregister(a)
            ModuleRegistration.unregister(b)
        }
        assertNull(ContractRegistry.contract(shared.contractId), "the last contributor's exit left the entry behind")
    }

    /** [JAR1-REG-04]: the host module is not unregisterable, and its descriptors survive the attempt. */
    @Test
    fun `host attribution is refused unregistration and survives`() {
        val hostContract = descriptorOf(ProbeComplete::class.java)
        // Default owner is HOST — the same attribution the init-time ServiceLoader scan gets.
        ContractRegistry.register(moduleOf(contracts = listOf(hostContract)))

        assertEquals(listOf(ModuleId.HOST), ContractRegistry.contributorsOf(hostContract.contractId))
        assertFailsWith<IllegalArgumentException> { ModuleRegistration.unregister(ModuleId.HOST) }
        assertFailsWith<IllegalArgumentException> { ContractRegistry.unregister(ModuleId.HOST) }
        assertFailsWith<IllegalArgumentException> { ProtocolRegistry.unregister(ModuleId.HOST) }
        assertFailsWith<IllegalArgumentException> { ProxyRegistry.unregister(ModuleId.HOST) }

        assertEquals(hostContract, ContractRegistry.contract(hostContract.contractId), "host descriptor removed")
        // Deliberately not cleaned up: HOST contributions are permanent by design,
        // which is why this test uses a probe type no other test registers.
    }

    /** [JAR1-REG-07]: every resolution path answers after a successful registration. */
    @Test
    fun `every resolution path answers after registration`() {
        val owner = ModuleId("resolution-complete")
        val contract = descriptorOf(ProbeAlpha::class.java)
        val cell = CellDescriptor(fqn = "civictech.nature.ResolutionProbeCell", color = CellColor.PURE)
        val protocol = ProtocolDescriptor("resolution-protocol", contract.contractId, ProtocolDirection.DOWNSTREAM, 1)
        val proxies = proxyModuleOf(ProbeProxied::class.java to stubConstructor)

        ModuleRegistration.register(
            owner = owner,
            contractModules = listOf(
                moduleOf(contracts = listOf(contract), cells = listOf(cell), protocols = listOf(protocol)),
            ),
            proxyModules = listOf(proxies),
        )
        try {
            assertEquals(contract, ContractRegistry.contract(contract.contractId))
            assertEquals(contract, ContractRegistry.descriptor(ProbeAlpha::class.java))
            assertEquals(
                contract.methods.single(),
                ContractRegistry.method(contract.contractId, contract.methods.single().methodId),
            )
            assertEquals(
                contract.contractId to contract.methods.single().methodId,
                ContractRegistry.idsOf(ProbeAlpha::class.java.declaredMethods.first()),
            )
            assertNotNull(ContractRegistry.cells.find { it.fqn == cell.fqn })
            assertEquals(protocol, ProtocolRegistry.protocol("resolution-protocol"))
            assertEquals(protocol, ProtocolRegistry.protocol(contract.contractId))
            assertNotNull(ProxyRegistry.factory(ProbeProxied::class.java))
            assertEquals(listOf(owner), ProxyRegistry.contributorsOf(ProbeProxied::class.java))
        } finally {
            ModuleRegistration.unregister(owner)
        }

        // Unregistration is total across the three registries.
        assertNull(ContractRegistry.contract(contract.contractId))
        assertNull(ContractRegistry.idsOf(ProbeAlpha::class.java.declaredMethods.first()))
        assertNull(ProtocolRegistry.protocol("resolution-protocol"))
        assertNull(ProxyRegistry.factory(ProbeProxied::class.java))
    }

    /** [JAR1-REG-01]: `validate` is a dry run — a conflict is reported without touching anything. */
    @Test
    fun `validate reports conflicts without mutating`() {
        val owner = ModuleId("validate-dry-run")
        val incumbent = descriptorOf(ProbeShared::class.java)
        ContractRegistry.register(moduleOf(contracts = listOf(incumbent)), owner)
        try {
            val clashing = descriptorOf(ProbeGamma::class.java, contractId = incumbent.contractId)
            val report = ModuleRegistration.validate(
                contractModules = listOf(moduleOf(contracts = listOf(descriptorOf(ProbeBeta::class.java), clashing))),
            )
            assertTrue(!report.isValid, "expected a conflict")
            assertEquals(ConflictKind.CONTRACT_ID, report.conflicts.single().kind)
            assertNull(
                ContractRegistry.descriptor(ProbeBeta::class.java),
                "validate installed a descriptor — it must not mutate",
            )
            assertEquals(ValidationReport.OK, ModuleRegistration.validate(listOf(moduleOf())))
        } finally {
            ModuleRegistration.unregister(owner)
        }
    }
}
