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
interface ProbeShredProxied { fun shredProxied(value: Long) }

/** A real class so its name can back a [CellDescriptor] and be looked up via [ContractRegistry.cellDescriptor]. */
interface ProbeCellHost

/** computenet-b7fr: module-then-module cell repoint probe. */
interface ProbeCellRepoint

/** computenet-b7fr: HOST-then-module cell repoint probe. */
interface ProbeCellHostRepoint

/** computenet-b7fr: three-contributor ordering probe (middle / first contributor departs). */
interface ProbeCellOrder

/** computenet-b7fr: re-registration ordering probe (one contributor registers the same fqn twice). */
interface ProbeCellReregister

/**
 * computenet-dhgy: byFqn/byMethodKey repoint probe — two distinct contractIds
 * sharing one fqn (only reachable via hand-constructed descriptors today; see
 * [descriptorOf]'s `contractId` override).
 */
interface ProbeContractFqnRepoint { fun repoint(value: Long) }

/**
 * computenet-nh51: heterogeneous method sets on one fqn — the departing
 * contributor is `byMethodKey`'s holder while a later, method-less contributor
 * holds `byFqn`. Own interface per probe so each ordering is independent of the
 * others' cleanup.
 */
interface ProbeMethodSetHolder { fun ord(value: Long) }

/** computenet-nh51 sibling ordering: three contributors, the MIDDLE one departs. */
interface ProbeMethodSetMiddle { fun ord(value: Long) }

/** computenet-nh51 sibling ordering: the FIRST contributor departs, then the fqn holder. */
interface ProbeMethodSetFirstThenHolder { fun ord(value: Long) }

/** computenet-nh51 sibling ordering: one module registers the same fqn twice, under two contractIds. */
interface ProbeMethodSetTwice { fun ord(value: Long) }

/** computenet-nh51 sibling ordering: a second module re-contributes an already-held contractId, then departs. */
interface ProbeMethodSetRecontributed { fun ord(value: Long) }

/**
 * computenet-nh51 review: the holder departs leaving TWO live survivors, so
 * *which* survivor is chosen is observable — the only shape that pins
 * `removeOwner`'s "newest live survivor" ordering for both tables.
 */
interface ProbeMethodSetNewestSurvivor { fun ord(value: Long) }

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
        val cell = CellDescriptor(fqn = ProbeCellHost::class.java.name, color = CellColor.PURE)
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
            // [JAR1-REG-07] names cellDescriptor by hand — exercise that path, not only `cells`.
            assertEquals(cell, ContractRegistry.cellDescriptor(ProbeCellHost::class.java))
            assertEquals(listOf(owner), ContractRegistry.cellContributorsOf(cell.fqn))
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
        assertNull(ContractRegistry.cellDescriptor(ProbeCellHost::class.java), "the cell descriptor outlived its owner")
    }

    /**
     * The proxy half of [JAR1-REG-06] / the task's decided point 8: constructors are
     * not comparable, so a second contributor of the same `Class` key is recorded as
     * an additional contributor WITHOUT repointing the live entry (first writer wins),
     * and the entry survives until the last contributor leaves.
     */
    @Test
    fun `proxy entries are a contributor multiset, not last-writer`() {
        val a = ModuleId("proxy-a")
        val b = ModuleId("proxy-b")
        val ctorA: ProxyConstructor = { _ -> Any() }
        val ctorB: ProxyConstructor = { _ -> Any() }
        ProxyRegistry.register(proxyModuleOf(ProbeShredProxied::class.java to ctorA), a)
        try {
            ProxyRegistry.register(proxyModuleOf(ProbeShredProxied::class.java to ctorB), b)
            assertEquals(listOf(a, b), ProxyRegistry.contributorsOf(ProbeShredProxied::class.java))
            assertTrue(
                ProxyRegistry.factory(ProbeShredProxied::class.java) === ctorA,
                "a second contributor repointed the live constructor",
            )

            ModuleRegistration.unregister(a)
            assertTrue(
                ProxyRegistry.factory(ProbeShredProxied::class.java) === ctorA,
                "the factory stopped resolving after one of two contributors left",
            )
            assertEquals(listOf(b), ProxyRegistry.contributorsOf(ProbeShredProxied::class.java))
        } finally {
            ModuleRegistration.unregister(a)
            ModuleRegistration.unregister(b)
        }
        assertNull(
            ProxyRegistry.factory(ProbeShredProxied::class.java),
            "the last contributor's exit left the proxy entry behind",
        )
    }

    /**
     * computenet-b7fr, module-then-module ordering: a later contributor's
     * CellDescriptor repoints the fqn (cells are not validated,
     * [JAR1-REG-01]); when that later contributor unregisters, the earlier
     * (still-live) contributor's descriptor must resolve again — not the
     * departed one's.
     */
    @Test
    fun `b7fr module-then-module cell repoint reverses on unregister`() {
        val first = ModuleId("b7fr-first")
        val second = ModuleId("b7fr-second")
        val d1 = CellDescriptor(fqn = ProbeCellRepoint::class.java.name, color = CellColor.PURE)
        val d2 = CellDescriptor(fqn = ProbeCellRepoint::class.java.name, color = CellColor.BLOCKING)
        ContractRegistry.register(moduleOf(cells = listOf(d1)), first)
        try {
            ContractRegistry.register(moduleOf(cells = listOf(d2)), second)
            assertEquals(d2, ContractRegistry.cellDescriptor(ProbeCellRepoint::class.java), "second contributor's descriptor did not take")

            ModuleRegistration.unregister(second)
            assertEquals(
                d1,
                ContractRegistry.cellDescriptor(ProbeCellRepoint::class.java),
                "the departed contributor's descriptor outlived it",
            )
        } finally {
            ModuleRegistration.unregister(first)
            ModuleRegistration.unregister(second)
        }
    }

    /**
     * computenet-b7fr, HOST-then-module ordering: the init-time scan attributes
     * a CellDescriptor to [ModuleId.HOST]; a module registering later can still
     * repoint the same fqn (cells are not validated), and unregistering that
     * module must restore HOST's descriptor rather than leaving the departed
     * module's behind.
     */
    @Test
    fun `b7fr host-then-module cell repoint reverses on unregister`() {
        val module = ModuleId("b7fr-host-then-module")
        val hostDescriptor = CellDescriptor(fqn = ProbeCellHostRepoint::class.java.name, color = CellColor.PURE)
        val moduleDescriptor = CellDescriptor(fqn = ProbeCellHostRepoint::class.java.name, color = CellColor.BLOCKING)
        // Default owner is HOST — the same attribution the init-time ServiceLoader scan gets.
        ContractRegistry.register(moduleOf(cells = listOf(hostDescriptor)))
        try {
            ContractRegistry.register(moduleOf(cells = listOf(moduleDescriptor)), module)
            assertEquals(
                moduleDescriptor,
                ContractRegistry.cellDescriptor(ProbeCellHostRepoint::class.java),
                "module contributor's descriptor did not take",
            )

            ModuleRegistration.unregister(module)
            assertEquals(
                hostDescriptor,
                ContractRegistry.cellDescriptor(ProbeCellHostRepoint::class.java),
                "the departed module's descriptor outlived it; HOST's descriptor should have resolved again",
            )
        } finally {
            ModuleRegistration.unregister(module)
            // HOST contribution is deliberately not cleaned up — permanent by design.
        }
    }

    /**
     * computenet-b7fr, orderings beyond the two the fix was filed against. The
     * criterion is universal ("a still-live contributor's descriptor, never the
     * departed one's"), so the invariant `removeOwner` has to hold is: after any
     * unregistration, `cellsByFqn[fqn]` is the *last surviving* contribution in
     * registration order — exactly what `commit`'s last-writer-wins would have
     * left had the departed contributor never registered.
     *
     * Three contributors on one fqn: the FIRST departs (a no-op — C is still the
     * last writer), then C, the contributor that actually repointed the fqn,
     * departs while B survives (the repoint must reverse to B, not to C and not
     * to the already-departed A), then B departs and the fqn is orphaned.
     */
    @Test
    fun `b7fr three contributors resolve the last surviving one whichever departs`() {
        val a = ModuleId("b7fr-order-a")
        val b = ModuleId("b7fr-order-b")
        val c = ModuleId("b7fr-order-c")
        val fqn = ProbeCellOrder::class.java.name
        val dA = CellDescriptor(fqn = fqn, color = CellColor.PURE)
        val dB = CellDescriptor(fqn = fqn, color = CellColor.BLOCKING)
        val dC = CellDescriptor(fqn = fqn, color = CellColor.SUSPENDING)
        ContractRegistry.register(moduleOf(cells = listOf(dA)), a)
        try {
            ContractRegistry.register(moduleOf(cells = listOf(dB)), b)
            ContractRegistry.register(moduleOf(cells = listOf(dC)), c)

            ModuleRegistration.unregister(a) // the FIRST contributor; C is still the last writer
            assertEquals(dC, ContractRegistry.cellDescriptor(ProbeCellOrder::class.java), "first contributor departed: C still holds the fqn")

            ModuleRegistration.unregister(c) // the contributor that repointed it; B survives
            assertEquals(dB, ContractRegistry.cellDescriptor(ProbeCellOrder::class.java), "the departed repointer's descriptor outlived it; B's should have resolved")

            ModuleRegistration.unregister(b)
            assertNull(ContractRegistry.cellDescriptor(ProbeCellOrder::class.java), "no contributor left: the fqn must be gone, not stranded")
        } finally {
            ModuleRegistration.unregister(a)
            ModuleRegistration.unregister(b)
            ModuleRegistration.unregister(c)
        }
    }

    /**
     * computenet-b7fr: one contributor registering the same fqn twice contributes
     * twice, so `unregister` must drop *both* of its contributions and fall back
     * to the other contributor's — not to its own earlier descriptor.
     */
    @Test
    fun `b7fr a contributor registering the same fqn twice drops both contributions`() {
        val a = ModuleId("b7fr-rereg-a")
        val b = ModuleId("b7fr-rereg-b")
        val fqn = ProbeCellReregister::class.java.name
        val first = CellDescriptor(fqn = fqn, color = CellColor.PURE)
        val other = CellDescriptor(fqn = fqn, color = CellColor.BLOCKING)
        val again = CellDescriptor(fqn = fqn, color = CellColor.SUSPENDING)
        ContractRegistry.register(moduleOf(cells = listOf(first)), a)
        try {
            ContractRegistry.register(moduleOf(cells = listOf(other)), b)
            ContractRegistry.register(moduleOf(cells = listOf(again)), a)
            assertEquals(again, ContractRegistry.cellDescriptor(ProbeCellReregister::class.java), "A's second contribution did not take")

            ModuleRegistration.unregister(a)
            assertEquals(
                other,
                ContractRegistry.cellDescriptor(ProbeCellReregister::class.java),
                "A departed: B's descriptor must resolve, not either of A's",
            )

            ModuleRegistration.unregister(b)
            assertNull(ContractRegistry.cellDescriptor(ProbeCellReregister::class.java), "no contributor left: the fqn must be gone")
        } finally {
            ModuleRegistration.unregister(a)
            ModuleRegistration.unregister(b)
        }
    }

    /**
     * computenet-dhgy: `ContractRegistry.stage` validates by contractId only, so
     * two ContractDescriptors sharing an fqn but carrying different contractIds
     * (and equal methodIds, so no METHOD_KEY conflict either) are both accepted;
     * `commit` does `byFqn[fqn] = later`, repointing it. `contractProvenance` is
     * keyed by contractId, so unregistering the later contributor must not leave
     * `byFqn`/`byMethodKey` stranded with no entry while the earlier contract is
     * still live in `byId` — the same repoint-and-strand hole computenet-b7fr
     * fixed for `cellsByFqn`, one table over.
     */
    @Test
    fun `dhgy byFqn and byMethodKey repoint reverses on unregister, not stranded`() {
        val moduleA = ModuleId("dhgy-a")
        val moduleB = ModuleId("dhgy-b")
        val fqn = ProbeContractFqnRepoint::class.java.name
        val method = ProbeContractFqnRepoint::class.java.declaredMethods.first()
        val descriptorA = descriptorOf(ProbeContractFqnRepoint::class.java, contractId = 111_111L)
        val descriptorB = descriptorOf(ProbeContractFqnRepoint::class.java, contractId = 222_222L)
        ContractRegistry.register(moduleOf(contracts = listOf(descriptorA)), moduleA)
        try {
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorB)), moduleB)
            assertEquals(descriptorB, ContractRegistry.descriptor(ProbeContractFqnRepoint::class.java), "B's descriptor did not take")

            ModuleRegistration.unregister(moduleB)

            assertNotNull(
                ContractRegistry.contract(descriptorA.contractId),
                "A's contract should still be live in byId",
            )
            assertEquals(
                descriptorA,
                ContractRegistry.descriptor(ProbeContractFqnRepoint::class.java),
                "byFqn lost a still-live contract",
            )
            assertEquals(
                descriptorA.contractId to descriptorA.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "byMethodKey lost a still-live contract's method",
            )
        } finally {
            ModuleRegistration.unregister(moduleA)
            ModuleRegistration.unregister(moduleB)
        }
        assertNull(ContractRegistry.descriptor(ProbeContractFqnRepoint::class.java), "no contributor left: fqn must be gone")
        assertNull(ContractRegistry.idsOf(method), "no contributor left: method key must be gone")
    }

    /**
     * computenet-nh51: `removeOwner` gated the whole `byFqn`/`byMethodKey` restore
     * on `wasFqnHolder`, which is the wrong gate for `byMethodKey` — its holder and
     * `byFqn`'s holder DIVERGE as soon as contributors sharing one fqn carry
     * different method sets. A later contributor that lacks method `m` repoints
     * `byFqn` without repointing `byMethodKey[fqn#m]`, so the departing contributor
     * is `byMethodKey`'s holder while not being `byFqn`'s, no restore ran, and
     * `idsOf(m)` returned null with a still-live contract owning `m` in `byId`.
     * Against the pre-fix code this failed with
     * "A still live and owns method m ==> expected: <1111111> but was: <null>".
     */
    @Test
    fun `nh51 a departing byMethodKey holder that is not the fqn holder repoints per method key`() {
        val a = ModuleId("nh51-a")
        val b = ModuleId("nh51-b")
        val c = ModuleId("nh51-c")
        val iface = ProbeMethodSetHolder::class.java
        val method = iface.declaredMethods.first()
        val descriptorA = descriptorOf(iface, contractId = 1_111_111L)
        val descriptorB = descriptorOf(iface, contractId = 2_222_222L)
        // The later contributor carries NO methods: it takes byFqn without taking byMethodKey.
        val descriptorC = descriptorOf(iface, contractId = 9_999_999L).copy(methods = emptyList())
        try {
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorA)), a)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorB)), b)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorC)), c)
            assertEquals(descriptorC, ContractRegistry.descriptor(iface), "C holds byFqn")
            assertEquals(
                descriptorB.contractId to descriptorB.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "B holds byMethodKey",
            )

            ModuleRegistration.unregister(b)

            assertNotNull(ContractRegistry.contract(descriptorA.contractId), "A is still live in byId")
            assertEquals(
                descriptorA.contractId to descriptorA.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "A still live and owns method m",
            )
            assertEquals(descriptorC, ContractRegistry.descriptor(iface), "byFqn holder is untouched by B's departure")
        } finally {
            listOf(a, b, c).forEach { ModuleRegistration.unregister(it) }
        }
        assertNull(ContractRegistry.descriptor(iface), "no contributor left: fqn must be gone")
        assertNull(ContractRegistry.idsOf(method), "no contributor left: method key must be gone")
    }

    /** computenet-nh51 sibling ordering: three contributors, the middle one departs. */
    @Test
    fun `nh51 three contributors on one fqn, the middle one departs`() {
        val a = ModuleId("nh51-mid-a")
        val b = ModuleId("nh51-mid-b")
        val c = ModuleId("nh51-mid-c")
        val iface = ProbeMethodSetMiddle::class.java
        val method = iface.declaredMethods.first()
        val descriptorA = descriptorOf(iface, contractId = 1_111_112L)
        val descriptorB = descriptorOf(iface, contractId = 2_222_223L)
        val descriptorC = descriptorOf(iface, contractId = 3_333_334L)
        try {
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorA)), a)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorB)), b)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorC)), c)

            ModuleRegistration.unregister(b)

            assertEquals(descriptorC, ContractRegistry.descriptor(iface), "C still holds byFqn")
            assertEquals(
                descriptorC.contractId to descriptorC.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "C still holds byMethodKey",
            )
        } finally {
            listOf(a, b, c).forEach { ModuleRegistration.unregister(it) }
        }
    }

    /** computenet-nh51 sibling ordering: the first contributor departs, then the fqn holder. */
    @Test
    fun `nh51 three contributors on one fqn, the first departs then the holder`() {
        val a = ModuleId("nh51-first-a")
        val b = ModuleId("nh51-first-b")
        val c = ModuleId("nh51-first-c")
        val iface = ProbeMethodSetFirstThenHolder::class.java
        val method = iface.declaredMethods.first()
        val descriptorA = descriptorOf(iface, contractId = 1_111_113L)
        val descriptorB = descriptorOf(iface, contractId = 2_222_224L)
        val descriptorC = descriptorOf(iface, contractId = 3_333_335L)
        try {
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorA)), a)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorB)), b)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorC)), c)

            ModuleRegistration.unregister(a)
            assertEquals(descriptorC, ContractRegistry.descriptor(iface), "after A departs")
            assertEquals(
                descriptorC.contractId to descriptorC.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "after A departs, idsOf",
            )

            ModuleRegistration.unregister(c)
            assertEquals(descriptorB, ContractRegistry.descriptor(iface), "after C departs, B survives")
            assertEquals(
                descriptorB.contractId to descriptorB.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "after C departs, idsOf falls back to B",
            )
        } finally {
            listOf(a, b, c).forEach { ModuleRegistration.unregister(it) }
        }
    }

    /** computenet-nh51 sibling ordering: one module registers the same fqn twice, under two contractIds. */
    @Test
    fun `nh51 one module registering the same fqn twice drops both on unregister`() {
        val a = ModuleId("nh51-twice-a")
        val iface = ProbeMethodSetTwice::class.java
        val method = iface.declaredMethods.first()
        val descriptorA = descriptorOf(iface, contractId = 1_111_114L)
        val descriptorB = descriptorOf(iface, contractId = 2_222_225L)
        try {
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorA, descriptorB)), a)
            assertEquals(descriptorB, ContractRegistry.descriptor(iface), "later contribution wins in-module")

            ModuleRegistration.unregister(a)

            assertNull(ContractRegistry.descriptor(iface), "both contributions gone")
            assertNull(ContractRegistry.idsOf(method), "both contributions gone, idsOf")
        } finally {
            ModuleRegistration.unregister(a)
        }
    }

    /** computenet-nh51 sibling ordering: a second module re-contributes an already-held contractId, then departs. */
    @Test
    fun `nh51 a re-contributed contractId survives its second contributor departing`() {
        val a = ModuleId("nh51-recon-a")
        val b = ModuleId("nh51-recon-b")
        val c = ModuleId("nh51-recon-c")
        val iface = ProbeMethodSetRecontributed::class.java
        val method = iface.declaredMethods.first()
        val descriptorA = descriptorOf(iface, contractId = 1_111_115L)
        val descriptorB = descriptorOf(iface, contractId = 2_222_226L)
        try {
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorA)), a)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorB)), b)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorA)), c)
            assertEquals(descriptorA, ContractRegistry.descriptor(iface), "A's descriptor committed last")

            ModuleRegistration.unregister(c)
            assertEquals(descriptorA, ContractRegistry.descriptor(iface), "A still contributes contractId A")
            assertEquals(
                descriptorA.contractId to descriptorA.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "A still holds byMethodKey",
            )

            ModuleRegistration.unregister(a)
            assertEquals(descriptorB, ContractRegistry.descriptor(iface), "B survives")
            assertEquals(
                descriptorB.contractId to descriptorB.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "B survives, idsOf",
            )
        } finally {
            listOf(a, b, c).forEach { ModuleRegistration.unregister(it) }
        }
    }

    /**
     * computenet-nh51 (review): `removeOwner` repoints both tables to the **newest**
     * live survivor — the last-writer-wins order `commit` applies — and the code says
     * so in two places (`ContractFqnIndex.remove`'s "newest last" KDoc and the
     * "Newest first" comment on `survivors`). Every other ordering here leaves at most
     * ONE survivor at the moment a holder departs, so newest and oldest coincide and
     * the claim is unconstrained: reversing either choice keeps the whole suite green.
     * This is the shape that separates them — the holder departs with two live
     * survivors, both declaring the method key — and it pins the fallback chain as
     * survivors are consumed newest-first.
     */
    @Test
    fun `nh51 the holder departing with two live survivors repoints to the newest`() {
        val a = ModuleId("nh51-newest-a")
        val b = ModuleId("nh51-newest-b")
        val c = ModuleId("nh51-newest-c")
        val iface = ProbeMethodSetNewestSurvivor::class.java
        val method = iface.declaredMethods.first()
        val descriptorA = descriptorOf(iface, contractId = 4_444_441L)
        val descriptorB = descriptorOf(iface, contractId = 4_444_442L)
        val descriptorC = descriptorOf(iface, contractId = 4_444_443L)
        try {
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorA)), a)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorB)), b)
            ContractRegistry.register(moduleOf(contracts = listOf(descriptorC)), c)

            // C holds both tables; A and B both remain live and both declare the key.
            ModuleRegistration.unregister(c)
            assertEquals(descriptorB, ContractRegistry.descriptor(iface), "byFqn repoints to B, the newest survivor")
            assertEquals(
                descriptorB.contractId to descriptorB.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "byMethodKey repoints to B, the newest survivor declaring the key",
            )

            // One survivor left: the chain falls back to A rather than to nothing.
            ModuleRegistration.unregister(b)
            assertEquals(descriptorA, ContractRegistry.descriptor(iface), "byFqn falls back to A")
            assertEquals(
                descriptorA.contractId to descriptorA.methods.single().methodId,
                ContractRegistry.idsOf(method),
                "byMethodKey falls back to A",
            )
        } finally {
            listOf(a, b, c).forEach { ModuleRegistration.unregister(it) }
        }
        assertNull(ContractRegistry.descriptor(iface), "no contributor left: fqn must be gone")
        assertNull(ContractRegistry.idsOf(method), "no contributor left: method key must be gone")
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
