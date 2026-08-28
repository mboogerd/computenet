package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Feature computenet-051.4, task computenet-051.4.2 — live-instance accounting
 * per module `[JAR1-UNL-01]`: `ModuleLoader.track(registry)` attaches to
 * `LocationRegistry.onPublish`/`onUnpublish` (the seam `ManagedHost`'s
 * `LifecycleTransition` KDoc names as the spawn/despawn observation point) and
 * attributes each published ref to the loaded [ModuleHandle] whose
 * [ModuleHandle.classLoader] defined the published cell's class, exposed as
 * [ModuleHandle.liveInstances].
 *
 * No kernel change, no [civictech.nature.CellFactory] wrapping — this test
 * exercises exactly the observation seam already landed on `main`
 * ([LocationRegistry.publish] capturing the class before firing `onPublish`,
 * [LocationRegistry.unpublish] firing `onUnpublish`).
 */
class ModuleInstanceAccountingTest {

    private companion object {
        const val GREETING_CELL = "civictech.loader.fixture.validbasic.GreetingCell"
    }

    /** A host-defined cell, never loaded through any [ModuleClassLoader] — the negative case. */
    private class LocalCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell

    /** Reflectively constructs a fresh instance of the fixture's `GreetingCell`, defined by [handle]'s own loader. */
    private fun newModuleCell(handle: ModuleHandle): Cell {
        val cellClass = handle.classLoader.loadClass(GREETING_CELL)
        val ctor = cellClass.getDeclaredConstructor(CellRef::class.java)
        return ctor.newInstance(CellRef(UUID.randomUUID())) as Cell
    }

    @Test
    fun `liveInstances counts module-spawned cells and drops them on despawn`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        FixtureJars.withLoadedModule(FixtureJars.validBasic, loader) { handle ->
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)
            val tracker = loader.track(registry)
            try {
                handle.liveInstances shouldBe 0

                val cellA = newModuleCell(handle)
                val cellB = newModuleCell(handle)

                host.managementInlet.call.spawn(cellA)
                awaitUntil("cellA counted") { handle.liveInstances == 1 }

                host.managementInlet.call.spawn(cellB)
                awaitUntil("cellB counted") { handle.liveInstances == 2 }

                host.managementInlet.call.despawn(cellA.ref)
                awaitUntil("cellA dropped") { handle.liveInstances == 1 }

                host.managementInlet.call.despawn(cellB.ref)
                awaitUntil("cellB dropped") { handle.liveInstances == 0 }
            } finally {
                tracker.close()
            }
        }
    }

    @Test
    fun `a host-defined cell is never counted against any module`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        FixtureJars.withLoadedModule(FixtureJars.validBasic, loader) { handle ->
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)
            val tracker = loader.track(registry)
            try {
                val local = LocalCell()
                host.managementInlet.call.spawn(local)

                // give the hook a chance to run (and not count anything) before asserting
                awaitUntil("publish observed") { registry.locate(local.ref) != null }
                handle.liveInstances shouldBe 0

                host.managementInlet.call.despawn(local.ref)
                awaitUntil("despawn observed") { registry.locate(local.ref) == null }
                handle.liveInstances shouldBe 0
            } finally {
                tracker.close()
            }
        }
    }

    @Test
    fun `detaching the tracker stops counting further publishes`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        FixtureJars.withLoadedModule(FixtureJars.validBasic, loader) { handle ->
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)
            val tracker = loader.track(registry)

            val cellA = newModuleCell(handle)
            host.managementInlet.call.spawn(cellA)
            awaitUntil("cellA counted") { handle.liveInstances == 1 }

            tracker.close()

            val cellB = newModuleCell(handle)
            host.managementInlet.call.spawn(cellB)
            awaitUntil("cellB published") { registry.locate(cellB.ref) != null }
            // detached: cellB is never attributed, and cellA's earlier count is
            // left exactly as it was — closing the tracker does not reset it.
            handle.liveInstances shouldBe 1

            host.managementInlet.call.despawn(cellA.ref)
            host.managementInlet.call.despawn(cellB.ref)
            awaitUntil("both despawned") {
                registry.locate(cellA.ref) == null && registry.locate(cellB.ref) == null
            }
            // cellA's despawn is no longer observed either, post-detach: the count
            // it left behind is stale bookkeeping, not a live re-derivation.
            handle.liveInstances shouldBe 1
        }
    }

    @Test
    fun `a cell already live before track attaches is not counted until it re-publishes`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        FixtureJars.withLoadedModule(FixtureJars.validBasic, loader) { handle ->
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)

            val preExisting = newModuleCell(handle)
            host.managementInlet.call.spawn(preExisting)
            awaitUntil("pre-existing cell published") { registry.locate(preExisting.ref) != null }

            val tracker = loader.track(registry)
            try {
                // attach-time-forward only: a publish this tracker never observed
                // stays uncounted.
                handle.liveInstances shouldBe 0

                host.managementInlet.call.despawn(preExisting.ref)
                awaitUntil("pre-existing cell despawned") { registry.locate(preExisting.ref) == null }
                handle.liveInstances shouldBe 0
            } finally {
                tracker.close()
            }
        }
    }
}
