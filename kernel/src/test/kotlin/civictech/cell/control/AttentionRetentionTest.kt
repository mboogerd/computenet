package civictech.cell.control

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * **computenet-3u6x: `AttentionSupport.registries` held its own `WeakHashMap`
 * key in a direct strong field, so every cell `AttentionSupport.of` was ever
 * called on was immortal.**
 *
 * `AttentionSupport.registries` is a JVM-global `WeakHashMap<owner,
 * AttentionSupport>`. A `WeakHashMap` reclaims an entry only when its *key*
 * stops being strongly reachable, and it holds its *values* strongly — so any
 * strong path value → key makes the entry, and the key, immortal.
 * `AttentionSupport` carried `private val owner: Any`, which is that path
 * unconditionally: not through a closure, not depending on how the owner
 * happened to be written.
 *
 * That makes it a strictly worse shape than `PortRegistry.registries`
 * (computenet-w5sm) and `ProtocolSupport.registries` (PN-9), whose cycles need
 * the owner's *served implementation* to capture the owner — the `DetachedCell`
 * control in [civictech.cell.port.PortRegistryRetentionTest] shows a
 * non-capturing cell IS collected there. Here there was no such escape, and
 * unlike the other two there was no eviction path at all: `PortRegistry` has
 * `release`, `ProtocolSupport` has `unbind`, and `ManagedHost.unbindPortsRecursively`
 * calls both on despawn; `AttentionSupport` had neither.
 *
 * The arms:
 *
 * - [a dropped cell that nothing attended is collectable] — the control that
 *   makes the mechanism specific. It is the exact `DetachedCell` shape
 *   `PortRegistryRetentionTest` proves collectable, so anything the next arm
 *   observes is attributable to the `AttentionSupport.of` call and nothing else.
 * - [a dropped cell is collectable after one AttentionSupport of call] — the
 *   defect, by execution. **This arm failed before the fix** with
 *   `expected:<null> but was:<...DetachedCell@...>`, and is the whole claim.
 * - [releasing the AttentionSupport entry also makes it collectable] — the
 *   explicit eviction `ManagedHost.unbindPortsRecursively` now calls on despawn.
 * - [a despawned hosted cell is collectable] — why that eviction is not
 *   redundant with the weak owner. `ManagedHost` spawn installs an
 *   `onBandChange` listener whose closure captures the cell, so the entry can
 *   reach its own key again through a path this class does not own. Mutation-
 *   checked: deleting the `AttentionSupport.release(cell)` line from
 *   `unbindPortsRecursively` fails this arm alone, with
 *   `Expected null but actual was ...DetachedCell@c5dc4a2`, and leaves the other
 *   five green.
 * - [a live owner still aggregates and bands] — the weakened `owner` must not
 *   change behaviour while the owner is alive; `forEachLinkedPort` still finds
 *   the owner's ports.
 * - [an attention support whose owner is gone is inert] — and must not throw
 *   when it does not.
 *
 * Every builder below is a **separate method** returning only a
 * [WeakReference], and must stay one. A named local in a test method's own
 * frame — including inside an inlined `run { }` — keeps its object reachable
 * for the whole method, which silently turns every assertion here into a pass.
 *
 * Deliberately **not** claimed here: any share of `WsAnnouncementStressTest`'s
 * ~206 KB/iteration. computenet-uo75's JFR path-to-gc-roots at iteration 5000
 * attributed 4019 of 4077 sampled live objects to `PortRegistry.registries`,
 * not to this map, and whether `AttentionSupport.of` is reached on that path at
 * all is unmeasured. This is a latent defect of the same shape, fixed on its own
 * evidence.
 */
class AttentionRetentionTest {

    /**
     * The control shape from [civictech.cell.port.PortRegistryRetentionTest]:
     * the served implementation captures nothing, so no port-side edge reaches
     * the cell and the cell is collectable on its own.
     */
    class DetachedCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    @Test
    fun `a dropped cell that nothing attended is collectable`() {
        val cell = droppedDetached(attend = false)
        collect()
        cell.get() shouldBe null
    }

    @Test
    fun `a dropped cell is collectable after one AttentionSupport of call`() {
        val cell = droppedDetached(attend = true)
        collect()
        cell.get() shouldBe null
    }

    @Test
    fun `releasing the AttentionSupport entry also makes it collectable`() {
        val cell = droppedDetached(attend = true, release = true)
        collect()
        cell.get() shouldBe null
    }

    @Test
    fun `a despawned hosted cell is collectable`() {
        val cell = spawnedThenDespawned()
        collect()
        cell.get() shouldBe null
    }

    @Test
    fun `a live owner still aggregates and bands`() {
        val cell = DetachedCell()
        val support = AttentionSupport.of(cell)
        support.band shouldBe AttentionBand.NORMAL
        support.attend(1f)
        support.band shouldBe AttentionBand.HIGH
        support.attend(0f)
        support.band shouldBe AttentionBand.NONE
        // keep the cell strongly reachable for the whole assertion block
        cell.ref shouldBe cell.ref
    }

    @Test
    fun `an attention support whose owner is gone is inert`() {
        val support = supportForDroppedOwner()
        collect()
        // no owner ⇒ no ports to walk; every entry point must be a safe no-op
        support.refresh()
        support.attend(1f)
        support.scatter = { true }
        support.band shouldBe AttentionBand.HIGH
    }

    /**
     * Builds a [DetachedCell], optionally calls `AttentionSupport.of` on it
     * (the single call that is the whole claim) and optionally releases the
     * entry, and returns only a [WeakReference] — so no strong reference to the
     * cell outlives this call frame.
     */
    private fun droppedDetached(attend: Boolean, release: Boolean = false): WeakReference<DetachedCell> {
        val cell = DetachedCell()
        if (attend) AttentionSupport.of(cell)
        if (release) AttentionSupport.release(cell)
        return WeakReference(cell)
    }

    /**
     * Spawns a cell on a host that HAS an attention policy — so spawn installs
     * the `onBandChange` listener whose closure captures the cell, the entry's
     * own key — despawns it, and returns only a [WeakReference]. The host and
     * its controller are locals of this frame and die with it, so what is left
     * holding the cell can only be a global map.
     */
    private fun spawnedThenDespawned(): WeakReference<DetachedCell> {
        val world = SimWorld(attention = AttentionPolicy())
        val cell = DetachedCell()
        world.host.managementInlet.call.spawn(cell)
        world.controller.runToIdle()
        world.host.managementInlet.call.despawn(cell.ref)
        world.controller.runToIdle()
        return WeakReference(cell)
    }

    /** Returns the support without any strong reference to its owner. */
    private fun supportForDroppedOwner(): AttentionSupport = AttentionSupport.of(DetachedCell())

    /**
     * Force enough collection that a weakly-reachable object is cleared.
     * Several rounds with allocation between them rather than a single
     * `System.gc()`: one call is a hint, and a weak referent can survive a
     * young collection.
     */
    private fun collect() {
        repeat(8) {
            System.gc()
            @Suppress("UNUSED_EXPRESSION")
            ByteArray(1 shl 20)
        }
        System.gc()
    }
}
