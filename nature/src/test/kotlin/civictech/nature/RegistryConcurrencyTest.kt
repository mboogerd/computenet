package civictech.nature

import java.time.Duration
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertTimeoutPreemptively

/**
 * [JAR1-REG-09]: register/unregister safe under concurrent invocation.
 *
 * Design (this task's decided shape): N worker threads x M iterations,
 * gated behind a [CountDownLatch] so they actually contend rather than
 * running in sequence by scheduling accident. Each iteration exercises two
 * shapes concurrently:
 *
 *  - a PRIVATE module, one real interface per thread (so cross-thread
 *    private contractIds cannot collide), registered then unregistered
 *    every iteration — resolution (contract/descriptor/idsOf) is asserted
 *    both after registering and after unregistering.
 *  - a SHARED, byte-equal descriptor (the B6 shape) that every thread
 *    registers under its own [ModuleId] every iteration — this is the
 *    contended key: [ContractRegistry.contributorsOf] on it is read right
 *    after our own registration commits, and whenever another thread's
 *    contribution is still on the multiset alongside ours, that is direct,
 *    non-timing evidence of genuine interleaving (not merely "ran on
 *    multiple threads", but "another owner's contribution was actually
 *    present concurrently with ours").
 *
 * Per-assertion-family production behaviour observed (PER-TEST TRACING —
 * mutating production code is out of this task's file claim):
 *  - private register/unregister loop: [ModuleRegistration.register] /
 *    [ModuleRegistration.unregister] taking [RegistryMutation.lock] under
 *    concurrent callers without corrupting [ContractRegistry]'s
 *    `byId`/`byFqn`/`byMethodKey` maps (a torn write would surface as a
 *    resolution assertion failing on ANY thread, since threads share those
 *    maps even though their contractIds do not collide).
 *  - shared descriptor register/unregister: [Provenance.add] /
 *    [Provenance.drop]'s multiset semantics under contention — the
 *    `contributors[key] = (contributors[key] ?: emptyList()) + owner`
 *    read-modify-write in [Provenance.add] is exactly the kind of operation
 *    a race would corrupt (a lost update drops a contributor silently); the
 *    `contentionObserved`/`maxConcurrentContributors` counters below are
 *    direct evidence that concurrent adds to the SAME key actually
 *    happened, not just concurrent adds to disjoint keys.
 *  - final state: after every thread has both registered and unregistered
 *    every iteration, the shared descriptor has zero contributors left and
 *    resolves to null — proving [Provenance.drop] under contention still
 *    converges to the correct final multiset rather than merely "some
 *    intermediate state looked fine".
 */
interface ProbeConc0 { fun call(value: Long) }
interface ProbeConc1 { fun call(value: Long) }
interface ProbeConc2 { fun call(value: Long) }
interface ProbeConc3 { fun call(value: Long) }
interface ProbeConc4 { fun call(value: Long) }
interface ProbeConc5 { fun call(value: Long) }
interface ProbeConc6 { fun call(value: Long) }
interface ProbeConc7 { fun call(value: Long) }
interface ProbeConcShared { fun shared(value: Long) }

private val PRIVATE_INTERFACES: List<Class<*>> = listOf(
    ProbeConc0::class.java, ProbeConc1::class.java, ProbeConc2::class.java, ProbeConc3::class.java,
    ProbeConc4::class.java, ProbeConc5::class.java, ProbeConc6::class.java, ProbeConc7::class.java,
)

private fun concDescriptorOf(iface: Class<*>, contractId: Long = StableHash.of(iface.name)): ContractDescriptor =
    ContractDescriptor(
        contractId = contractId,
        fqn = iface.name,
        management = false,
        methods = iface.declaredMethods.map { m ->
            val d = JvmDescriptors.of(m)
            MethodDescriptor(StableHash.of("${iface.name}#${m.name}$d"), m.name, d)
        },
    )

private fun concModuleOf(contract: ContractDescriptor): ContractModule = object : ContractModule {
    override val contracts: List<ContractDescriptor> = listOf(contract)
}

class RegistryConcurrencyTest {

    private val threadCount = 8
    private val iterationsPerThread = 200

    @Test
    fun `concurrent register and unregister across distinct and shared descriptors stays consistent`() {
        // The shared, byte-equal descriptor every thread contends on (B6 shape under contention).
        val sharedDescriptor = concDescriptorOf(ProbeConcShared::class.java)

        val startGate = CountDownLatch(1)
        val readyGate = CountDownLatch(threadCount)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        // Evidence counters — read AFTER our own registration commits, so a
        // count > 1 means another owner's contribution to the SAME key was
        // present at the same instant as ours: genuine interleaving, not a
        // timing assertion.
        val contentionHits = AtomicInteger(0)
        val maxConcurrentContributors = AtomicInteger(1)

        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val futures = (0 until threadCount).map { t ->
                pool.submit {
                    val privateIface = PRIVATE_INTERFACES[t]
                    val privateDescriptor = concDescriptorOf(privateIface)
                    try {
                        readyGate.countDown()
                        startGate.await(30, TimeUnit.SECONDS)

                        repeat(iterationsPerThread) { i ->
                            val privateOwner = ModuleId("conc-private-t$t-i$i")
                            val sharedOwner = ModuleId("conc-shared-t$t-i$i")

                            // --- private module: no cross-thread contractId collision ---
                            // Registration and its checks are wrapped so unregister runs on the
                            // failure path too (a check throwing must not leak the contributor id
                            // into the process-global registries — see the class doc).
                            ModuleRegistration.register(privateOwner, contractModules = listOf(concModuleOf(privateDescriptor)))
                            try {
                                check(ContractRegistry.contract(privateDescriptor.contractId) == privateDescriptor) {
                                    "t$t/i$i: private contract failed to resolve after register"
                                }
                                check(ContractRegistry.descriptor(privateIface) == privateDescriptor) {
                                    "t$t/i$i: private descriptor lookup by class failed after register"
                                }
                                check(ContractRegistry.idsOf(privateIface.declaredMethods.first()) != null) {
                                    "t$t/i$i: private idsOf failed to resolve after register"
                                }
                            } finally {
                                ModuleRegistration.unregister(privateOwner)
                            }
                            check(ContractRegistry.contract(privateDescriptor.contractId) == null) {
                                "t$t/i$i: private contract survived unregister"
                            }
                            check(ContractRegistry.idsOf(privateIface.declaredMethods.first()) == null) {
                                "t$t/i$i: private idsOf resolved after unregister"
                            }

                            // --- shared, byte-equal module: the contended key ---
                            ModuleRegistration.register(sharedOwner, contractModules = listOf(concModuleOf(sharedDescriptor)))
                            try {
                                check(ContractRegistry.contract(sharedDescriptor.contractId) == sharedDescriptor) {
                                    "t$t/i$i: shared contract failed to resolve after register"
                                }
                                val contributorsNow = ContractRegistry.contributorsOf(sharedDescriptor.contractId)
                                check(sharedOwner in contributorsNow) {
                                    "t$t/i$i: our own contribution missing from the shared multiset right after registering"
                                }
                                if (contributorsNow.size > 1) {
                                    contentionHits.incrementAndGet()
                                    maxConcurrentContributors.updateAndGet { prev -> maxOf(prev, contributorsNow.size) }
                                }
                            } finally {
                                ModuleRegistration.unregister(sharedOwner)
                            }
                        }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
            readyGate.await(30, TimeUnit.SECONDS)
            startGate.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} worker thread(s) raised; first: ${failures.first()}",
                failures.first(),
            )
        }

        // Non-vacuousness: report and require actual observed interleaving on the
        // shared key, not merely "8 threads ran". With threadCount=8 threads all
        // hammering the same key behind one start gate, this is expected to fire
        // on essentially every run; if it does not, that is reported honestly
        // rather than the test being shipped as if it proved concurrency.
        println(
            "RegistryConcurrencyTest: threadCount=$threadCount iterationsPerThread=$iterationsPerThread " +
                "totalIterations=${threadCount * iterationsPerThread} " +
                "contentionHits=${contentionHits.get()} maxConcurrentContributors=${maxConcurrentContributors.get()}",
        )
        assertTrue(
            contentionHits.get() > 0,
            "expected at least one observed contention on the shared descriptor's contributor multiset " +
                "(another owner's contribution present alongside ours) — observed none, which means this run " +
                "did not evidence genuine interleaving",
        )
        assertTrue(
            maxConcurrentContributors.get() > 1,
            "expected the shared descriptor's contributor multiset to be observed with more than one live " +
                "contributor at least once",
        )

        // Final convergence: every thread unregistered every iteration, so the
        // shared descriptor and every private descriptor must be fully gone.
        assertNull(ContractRegistry.contract(sharedDescriptor.contractId), "shared contract outlived every contributor")
        assertEquals(emptyList(), ContractRegistry.contributorsOf(sharedDescriptor.contractId))
        PRIVATE_INTERFACES.forEach { iface ->
            val descriptor = concDescriptorOf(iface)
            assertNull(ContractRegistry.contract(descriptor.contractId), "private contract for $iface outlived its owner")
            assertNull(ContractRegistry.descriptor(iface), "private descriptor for $iface outlived its owner")
        }
    }

    /**
     * A second, tighter run bounded with [assertTimeoutPreemptively] per
     * AGENTS.md ("assert semantic outcomes... not internal scheduling
     * timing") — the timeout bounds the whole run against a hang, it is not
     * itself the assertion. Exercises the same contended shared key with a
     * fresh descriptor so it cannot share state with the first test.
     *
     * The overlap `contentionHits` witnesses is made STRUCTURAL rather than
     * asserted (computenet-96fs): every worker parks on a shared
     * [CyclicBarrier] after its own [ModuleRegistration.register] returns
     * and before it reads [ContractRegistry.contributorsOf] for that
     * iteration. [ModuleRegistration.register] takes and releases
     * [RegistryMutation.lock] internally (it returns only after the
     * `synchronized` block completes), so by the time any worker reaches the
     * barrier it no longer holds that lock — parking on the barrier cannot
     * deadlock the registry. Because the barrier requires all [threads]
     * workers to arrive before any of them proceeds, every worker that gets
     * past it is guaranteed to observe every other worker's registration for
     * that same iteration already committed: `contributorsOf(...).size ==
     * threads` by construction, not by scheduling luck. `contentionHits > 0`
     * is therefore a fact about this test, not about the runner.
     *
     * The barrier await is bounded (`barrier.await(timeout, unit)`) so a
     * worker that never reaches the barrier — e.g. because a `check(...)`
     * above it threw — cannot hang its seven siblings forever: the JDK
     * [CyclicBarrier] contract breaks the barrier for every other waiting
     * party once one party's bounded wait times out, so the survivors fail
     * fast with [BrokenBarrierException]/[TimeoutException] instead of
     * blocking for the outer 60s and reporting an unrelated timeout. Every
     * worker's real exception (including a broken-barrier one) is still
     * routed through the existing `failures` queue and surfaces as itself in
     * the final `AssertionError`, so a genuine assertion failure is not
     * masked by the barrier machinery.
     *
     * The main stress test above (`totalIterations` = 1600, observed
     * `contentionHits=1005` on a real run) is NOT changed: its contention
     * window is wide enough — 8 threads free-running with no per-iteration
     * synchronization, hammering one shared key for 200 iterations each —
     * that it has never been observed to flake, unlike this test's narrower
     * 100-iteration, otherwise-identical window that CI did observe going
     * red then green on the same commit. The same fragility argument
     * technically applies to it too, but forcing lockstep there would change
     * what it exercises (free-running contention, not barrier-gated
     * contention) for no evidenced benefit; it is left as the free-running
     * witness test the fix intentionally keeps un-forced.
     */
    @Test
    fun `bounded concurrent contention on a shared descriptor never loses a contributor`() {
        val boundDescriptor = concDescriptorOf(ProbeConcShared::class.java, contractId = StableHash.of("bounded-shared"))
        val threads = 8
        val iterations = 100
        val startGate = CountDownLatch(1)
        val contentionHits = AtomicInteger(0)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        // Forces the overlap the test witnesses: every worker parks here,
        // inside its own registered window (register() has returned and
        // released RegistryMutation.lock; unregister() has not yet run),
        // until all `threads` workers have done the same for this
        // iteration. See the class doc above for the deadlock-safety and
        // non-vacuousness argument.
        val barrier = CyclicBarrier(threads)

        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val futures = (0 until threads).map { t ->
                    pool.submit {
                        try {
                            startGate.await(30, TimeUnit.SECONDS)
                            repeat(iterations) { i ->
                                val owner = ModuleId("bounded-shared-t$t-i$i")
                                ModuleRegistration.register(owner, contractModules = listOf(concModuleOf(boundDescriptor)))
                                try {
                                    // Bounded: a sibling that never arrives (e.g. it threw
                                    // above) breaks the barrier for everyone waiting once the
                                    // timeout elapses, rather than hanging this thread forever.
                                    try {
                                        barrier.await(20, TimeUnit.SECONDS)
                                    } catch (e: TimeoutException) {
                                        throw AssertionError("t$t/i$i: timed out waiting for siblings at the contention barrier", e)
                                    } catch (e: BrokenBarrierException) {
                                        throw AssertionError("t$t/i$i: contention barrier broken by a sibling failure", e)
                                    }
                                    val contributors = ContractRegistry.contributorsOf(boundDescriptor.contractId)
                                    check(owner in contributors) { "t$t/i$i: own contribution missing right after register" }
                                    if (contributors.size > 1) contentionHits.incrementAndGet()
                                } finally {
                                    ModuleRegistration.unregister(owner)
                                }
                            }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                }
                startGate.countDown()
                futures.forEach { it.get(50, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
                pool.awaitTermination(30, TimeUnit.SECONDS)
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} worker thread(s) raised; first: ${failures.first()}", failures.first())
        }
        println("bounded contention run: threads=$threads iterations=$iterations contentionHits=${contentionHits.get()}")
        assertTrue(contentionHits.get() > 0, "expected genuine interleaving on the bounded run's shared descriptor")
        assertNull(ContractRegistry.contract(boundDescriptor.contractId), "bounded shared contract outlived every contributor")
    }
}
