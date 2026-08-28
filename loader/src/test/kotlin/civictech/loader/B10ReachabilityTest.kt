package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.gen.wire.ProxyRegistry
import civictech.nature.ContractRegistry
import civictech.testkit.awaitUntil
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Feature computenet-051.4, task computenet-051.4.4 — scenario **B10**,
 * `[JAR1-UNL-05]`: after an unload and once the caller drops its
 * [ModuleHandle], the [ModuleClassLoader] and the classes it defined become
 * unreachable and eligible for GC.
 *
 * ## Which route this test took — the feature's honesty clause
 *
 * The feature's acceptance requires this file to state plainly whether B10
 * passed on a **real GC** (a [WeakReference] actually clearing) or was reduced
 * to a **bookkeeping-reachability** assertion.
 *
 * **It passed on a real GC.** [the unloaded classloader becomes weakly
 * reachable and is collected] drops every strong reference this test holds —
 * handle, classes, cell instances, tracker, host, registry — keeps only a
 * [WeakReference] to the loader, and drives a bounded `System.gc()` +
 * allocation-pressure loop until that reference clears. If that loop is ever
 * seen to time out on some machine, the sanctioned reduction is the *second*
 * test here, which asserts unreachability through the loader's own bookkeeping
 * only; the reduction must be reported, and this KDoc updated, rather than the
 * GC test being deleted or its bound quietly raised past honesty.
 *
 * The bookkeeping test is kept alongside the GC one regardless: it is what
 * localises a failure. A cleared [WeakReference] says the loader was collected;
 * the bookkeeping assertions say *which* of the loader's own maps would have
 * pinned it if it had not been.
 *
 * The epic's honest non-requirement still stands: this is unreachability from
 * **the loader's own bookkeeping**, not a claim that unloading always reclaims
 * memory. `ThreadLocal`s, JDK-internal caches and a serializer cache can pin a
 * classloader from outside anything `:loader` owns.
 */
class B10ReachabilityTest {

    private companion object {
        const val GREETING_CELL = "civictech.loader.fixture.validbasic.GreetingCell"
        const val GREETING_API = "civictech.loader.fixture.validbasic.GreetingApi"

        /** Bounded, per the scenario: attempts, not an unbounded spin. */
        const val GC_ATTEMPTS = 40
    }

    /**
     * Load, spawn and despawn a module cell so the tracker genuinely saw it, then
     * unload — returning only a [WeakReference] to the module's classloader and
     * nothing that could keep it alive.
     *
     * Everything strong lives inside this function's frame on purpose: when it
     * returns, the frame is gone, which is what makes the weak reference the last
     * one. A `val handle = ...` in the test method would be a live local for the
     * whole method and would defeat the point.
     */
    private fun loadUseAndUnload(): WeakReference<ModuleClassLoader> {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        val handle = loader.load(FixtureJars.validBasic)
        val weak = WeakReference(handle.classLoader)

        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        loader.track(registry).use {
            val cellClass = handle.classLoader.loadClass(GREETING_CELL)
            val cell = cellClass.getDeclaredConstructor(CellRef::class.java)
                .newInstance(CellRef(UUID.randomUUID())) as Cell
            host.managementInlet.call.spawn(cell)
            awaitUntil("the module cell is counted") { handle.liveInstances == 1 }
            host.managementInlet.call.despawn(cell.ref)
            awaitUntil("the module cell is gone") { handle.liveInstances == 0 }
        }

        loader.unload(handle)
        handle.state shouldBe ModuleState.CLOSED
        return weak
    }

    @Test
    fun `the unloaded classloader becomes weakly reachable and is collected — REAL GC route`() {
        val weak = loadUseAndUnload()

        var collected = false
        var attempts = 0
        while (attempts < GC_ATTEMPTS && !collected) {
            attempts++
            // Allocation pressure alongside the hint: System.gc() is advisory, and a
            // young-generation-only collection will not reclaim a classloader.
            @Suppress("UNUSED_VARIABLE")
            val ballast = ByteArray(1 shl 20)
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
            collected = weak.get() == null
        }

        withClue(
            "UNL-05: the ModuleClassLoader was still strongly reachable after $GC_ATTEMPTS GC " +
                "attempts. If this is reproducible, the honest response is to find what pins it " +
                "(the bookkeeping test below localises loader-owned pins) or to report the " +
                "reduction to the bookkeeping route — never to delete or loosen this test."
        ) {
            collected shouldBe true
        }
    }

    @Test
    fun `after unload nothing in the loader's own bookkeeping still reaches the module`() {
        // The sanctioned reduction of B10, kept as a companion assertion: it names
        // every map `:loader` owns that could pin an unloaded module, so a GC
        // failure above can be attributed rather than guessed at.
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        val handle = loader.load(FixtureJars.validBasic)
        val moduleLoader = handle.classLoader
        val apiClass = moduleLoader.loadClass(GREETING_API)

        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        loader.track(registry).use {
            val cell = moduleLoader.loadClass(GREETING_CELL)
                .getDeclaredConstructor(CellRef::class.java)
                .newInstance(CellRef(UUID.randomUUID())) as Cell
            host.managementInlet.call.spawn(cell)
            awaitUntil("counted") { handle.liveInstances == 1 }
            host.managementInlet.call.despawn(cell.ref)
            awaitUntil("uncounted") { handle.liveInstances == 0 }
        }

        loader.unload(handle)

        withClue("ModuleClassLoader's live-loader set no longer holds it") {
            ModuleClassLoader.openLoaders.contains(moduleLoader) shouldBe false
        }
        withClue("the ModuleLoader no longer lists the handle") {
            loader.loaded().contains(handle) shouldBe false
        }
        withClue("the tracker's accounting is empty — a pinned ref→handle entry is a leak, not a reduction") {
            handle.liveInstances shouldBe 0
        }
        withClue("the registries hold no descriptor of the departed module") {
            ContractRegistry.descriptor(apiClass) shouldBe null
            ContractRegistry.cellDescriptor(moduleLoader.loadClass(GREETING_CELL)) shouldBe null
        }
        withClue("and ProxyRegistry no longer resolves a factory to the departed module's constructor") {
            // The repoint fix of computenet-051.4.1 is what makes this hold when
            // another module shares the interface; here no other module does, so the
            // entry must be gone outright.
            ProxyRegistry.factory(apiClass) shouldBe null
        }
    }
}
