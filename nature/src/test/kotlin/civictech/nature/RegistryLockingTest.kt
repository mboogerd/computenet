package civictech.nature

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused regression cover for computenet-pu0c: the three lock-free
 * defensive-copy getters ([ContractRegistry.contracts],
 * [ContractRegistry.cells], [ProtocolRegistry.protocols]) must not raise while
 * another thread mutates the registry under [RegistryMutation.lock].
 *
 * `ModuleRegistration.ensureRegistriesInitialized()` reads all three OUTSIDE
 * that lock on purpose — taking the lock there would let a second thread block
 * on a registry's class-init monitor while the holder blocks on nothing — so
 * "safe to read lock-free" is a property of the getters themselves, and this is
 * where it is pinned. [JAR1-REG-09].
 *
 * Two instruments, deliberately both:
 *  - a DETERMINISTIC characterisation of the exact stdlib mechanism, using a
 *    collection that reports `size == 1` and then yields nothing: Kotlin's
 *    `Iterable.toList()` special-cases `size == 1` as `iterator().next()` and
 *    throws on it, while [concurrentSnapshot] returns the empty list. It fails
 *    the moment any of the getters is written back to `.toList()`, without
 *    depending on a scheduler.
 *  - a BOUNDED contention loop per getter, driving the registry's size across
 *    the 1↔0 boundary that opens the window, which is the shape that actually
 *    crashed in the wild.
 *
 * The broad stress cover for [JAR1-REG-09] stays in `RegistryConcurrencyTest`;
 * this file only pins the getters.
 */
interface ProbeLockA { fun call(value: Long) }
interface ProbeLockB { fun call(value: Long) }
interface ProbeLockC { fun call(value: Long) }

private fun lockDescriptorOf(iface: Class<*>): ContractDescriptor =
    ContractDescriptor(
        contractId = StableHash.of(iface.name),
        fqn = iface.name,
        management = false,
        methods = iface.declaredMethods.map { m ->
            val d = JvmDescriptors.of(m)
            MethodDescriptor(StableHash.of("${iface.name}#${m.name}$d"), m.name, d)
        },
    )

private fun lockModuleOf(
    contract: ContractDescriptor,
    cellList: List<CellDescriptor> = emptyList(),
    protocolList: List<ProtocolDescriptor> = emptyList(),
): ContractModule = object : ContractModule {
    override val contracts: List<ContractDescriptor> = listOf(contract)
    override val cells: List<CellDescriptor> = cellList
    override val protocols: List<ProtocolDescriptor> = protocolList
}

/**
 * A collection standing exactly where a [ConcurrentHashMap] view stands when an
 * entry is removed between the `size()` read and the `iterator()` call: it
 * reports one element and then produces none.
 */
private class VanishingSingleton : AbstractCollection<String>() {
    override val size: Int = 1
    override fun iterator(): Iterator<String> = emptyList<String>().iterator()
}

class RegistryLockingTest {

    /**
     * The mechanism, without a scheduler: `toList()` trusts a separately-read
     * size, [concurrentSnapshot] does not.
     */
    @Test
    fun `snapshot of a collection whose size read disagrees with its iterator does not throw`() {
        val vanishing = VanishingSingleton()

        // Documents WHY the getters may not use toList(): this is the stdlib
        // size == 1 fast path, `listOf(iterator().next())`.
        assertFailsWith<NoSuchElementException> { (vanishing as Iterable<String>).toList() }

        assertEquals(emptyList(), vanishing.concurrentSnapshot())
    }

    /** A snapshot of a live ConcurrentHashMap view is a plain copy, not a live view. */
    @Test
    fun `snapshot is a defensive copy, not a live view`() {
        val map = ConcurrentHashMap<String, String>()
        map["a"] = "1"
        val snapshot = map.values.concurrentSnapshot()
        map["b"] = "2"
        map.remove("a")

        assertEquals(listOf("1"), snapshot)
    }

    @Test
    fun `contracts getter survives concurrent register-unregister of a single contract`() {
        val owner = ModuleId("lock-probe-contracts")
        val module = lockModuleOf(lockDescriptorOf(ProbeLockA::class.java))

        val raised = hammer(owner, module) { ContractRegistry.contracts }

        assertNull(raised, "ContractRegistry.contracts raised under concurrent mutation: $raised")
        assertTrue(ContractRegistry.contributorsOf(StableHash.of(ProbeLockA::class.java.name)).isEmpty())
    }

    @Test
    fun `cells getter survives concurrent register-unregister of a single cell descriptor`() {
        val owner = ModuleId("lock-probe-cells")
        val module = lockModuleOf(
            lockDescriptorOf(ProbeLockB::class.java),
            cellList = listOf(CellDescriptor(fqn = "civictech.nature.LockProbeCell", color = CellColor.PURE)),
        )

        val raised = hammer(owner, module) { ContractRegistry.cells }

        assertNull(raised, "ContractRegistry.cells raised under concurrent mutation: $raised")
        assertTrue(ContractRegistry.cellContributorsOf("civictech.nature.LockProbeCell").isEmpty())
    }

    @Test
    fun `protocols getter survives concurrent register-unregister of a single protocol`() {
        val owner = ModuleId("lock-probe-protocols")
        val contract = lockDescriptorOf(ProbeLockC::class.java)
        val module = lockModuleOf(
            contract,
            protocolList = listOf(
                ProtocolDescriptor(
                    protocolId = "lock-probe-protocol",
                    contractId = contract.contractId,
                    direction = ProtocolDirection.DOWNSTREAM,
                    band = 0,
                ),
            ),
        )

        val raised = hammer(owner, module) { ProtocolRegistry.protocols }

        assertNull(raised, "ProtocolRegistry.protocols raised under concurrent mutation: $raised")
        assertTrue(ProtocolRegistry.contributorsOf("lock-probe-protocol").isEmpty())
    }

    /**
     * One mutator thread cycling [owner]'s single-entry [module] in and out of
     * the registries while one reader thread takes [read]'s snapshot lock-free.
     * Returns the first throwable either thread raised, or null.
     *
     * Bounded by iteration count, not by wall clock: the loop is ~a few hundred
     * ms and cannot hang the suite. It is a *contention* probe, so a green run
     * is evidence in proportion to how often the window opens — the
     * deterministic test above is what pins the mechanism.
     */
    private fun hammer(owner: ModuleId, module: ContractModule, read: () -> Collection<Any>): Throwable? {
        val iterations = 4000
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)

        val mutator = Thread {
            start.await()
            repeat(iterations) {
                try {
                    ModuleRegistration.register(owner, contractModules = listOf(module))
                    ModuleRegistration.unregister(owner)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    return@Thread
                }
            }
        }
        val reader = Thread {
            start.await()
            while (mutator.isAlive) {
                try {
                    read()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    return@Thread
                }
            }
        }

        mutator.start()
        reader.start()
        start.countDown()
        mutator.join(TimeUnit.SECONDS.toMillis(60))
        reader.join(TimeUnit.SECONDS.toMillis(60))

        // Leave no residue for sibling tests sharing these process-global objects.
        runCatching { ModuleRegistration.unregister(owner) }
        return failure.get()
    }
}
