package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.ManagedHost
import civictech.cell.protocol.ProtocolSupport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * **computenet-uo75 / computenet-w5sm: `PortRegistry.registries` was what
 * retained a dropped cell graph. It no longer does, and this is the regression
 * test that says so by execution.**
 *
 * `PortRegistry.registries` is a JVM-global `WeakHashMap<owner, PortRegistry>`.
 * A `WeakHashMap` reclaims an entry only when its *key* stops being strongly
 * reachable, and it holds its *values* strongly — so an entry whose value can
 * reach its own key is immortal, and it makes the key immortal with it. That is
 * the ordinary shape of a cell: `ManagedHost.managementInlet` is served with a
 * proxy whose handler closes over the host, so
 * `registries[host] -> PortRegistry -> managementInlet -> served proxy -> host`
 * closes the loop through the map's strong value edge. `ProtocolSupport`'s own
 * `registries` is the same hazard on a port key, and says so in its `unbind`
 * KDoc (PN-9); this test establishes that the `PortRegistry` one is real and is
 * the reference that holds a whole dropped graph.
 *
 * ## The measurement this test is the small end of
 *
 * `WsAnnouncementStressTest` retains ~199 KB per iteration (computenet-uo75,
 * three agreeing measurements). JFR old-object sampling with
 * `path-to-gc-roots=true`, dumped from a live run at iteration 5000, attributed
 * 4019 of 4077 sampled live objects — 230 MB of 232 MB sampled — to one chain
 * ending in this map, verbatim from `jfr print --events OldObjectSample`:
 *
 * ```
 *   byte[65536]
 *   hb : java.nio.HeapByteBuffer
 *   item : java.util.concurrent.LinkedBlockingQueue$Node
 *   head : java.util.concurrent.LinkedBlockingQueue
 *   buffers : civictech.wire.WsTransport$WsListener
 *   wsl : org.java_websocket.WebSocketImpl
 *   arg$1 : civictech.wire.WsTransport$WsListener$$Lambda...
 *   $send : civictech.wire.WsTransport$Session$1
 *   call : civictech.cell.port.Use$Companion$fixed$1
 *   ... : java.util.concurrent.ConcurrentHashMap
 *   consumers : civictech.cell.port.FanOutlet
 *   ... : java.util.LinkedHashMap Size: 1
 *   ports : civictech.cell.port.PortRegistry
 *   ... : java.util.WeakHashMap$Entry[131072]
 *   table : java.util.WeakHashMap Size: 61553
 *   m : java.util.Collections$SynchronizedMap
 *   registries : java.lang.Class Class Name: civictech.cell.port.PortRegistry
 * ```
 *
 * So the retained `WebSocketServer` the histogram delta showed (~9.9 terminated
 * `WebSocketWorker`s and ~128 KB of `byte[]` per iteration) is *pinned* by this
 * map, not by anything transport-side: 61553 registries were live at 5250
 * iterations, 11.7 per iteration, one per owner the iteration constructed.
 *
 * ## What each arm here decides
 *
 * - [a dropped ManagedHost IS collectable] — the fix, by execution. No
 *   transport, no sockets, no test scaffolding holds the host; dropping it and
 *   collecting clears a `WeakReference` to it. Before computenet-w5sm this arm
 *   read `shouldNotBe null` and passed: that was the defect.
 * - [a dropped cell whose port implementation closes over it IS collectable]
 *   — the same thing at the smallest possible scale: one cell, one port, one
 *   served implementation that reads a field of the cell. Also flipped by
 *   computenet-w5sm.
 * - [a dropped cell whose port implementation does not close over it IS
 *   collectable] — the control that made the mechanism specific. The identical
 *   cell shape whose served implementation captures nothing was reclaimed even
 *   before the fix, so the map was not retaining its keys by being a registry;
 *   it retained exactly the owners its own values could reach. Measured: the
 *   two cells differ only in whether the object expression touches a property
 *   of the cell. It is kept because it is what makes arm 2's flip a statement
 *   about the capture edge rather than about maps in general.
 * - [releasing the PortRegistry entry is what makes it collectable] — the
 *   bisect. The *same* host and the *same* self-referencing cell, with
 *   `PortRegistry.release(owner)` (and `ProtocolSupport.unbind`, the pair
 *   `ManagedHost.unbindPortsRecursively` already uses on despawn) called before
 *   they are dropped, ARE collected. That is what distinguishes "this map holds
 *   it" from "something else does". Review of computenet-uo75 (2026-08-13)
 *   narrowed the pair to the single call: `PortRegistry.release` **alone** is
 *   sufficient — both shapes are collected without `ProtocolSupport.unbind` —
 *   and `unbind` is a no-op for them, because `ProtocolSupport.registries` holds
 *   no entry for these ports at all (nothing here calls `ProtocolSupport.of`;
 *   `FanInlet` acquires it lazily in `onEdgeEvent`). `unbind` is kept in the
 *   arm because it is what despawn actually does, not because it is
 *   load-bearing. Post-fix this arm no longer bisects anything (arms 1 and 2
 *   are collected without it); it stays as the guard that an explicit release
 *   is still correct and still eager.
 *
 * Every builder below is a **separate method** that returns only a
 * [WeakReference], and must stay one. A named local in a test method's own frame
 * — including inside an inlined `run { }` — keeps its object reachable for the
 * whole method. While arms 1 and 2 asserted the leak (`shouldNotBe null`) that
 * silently turned them into passes — the first revision of the review's own
 * perturbation probes did exactly that. Now that they assert collectability it
 * would turn them into spurious *failures* instead, which is the friendlier
 * direction, but the discipline is unchanged.
 *
 * ## The fix these arms pin (computenet-w5sm)
 *
 * `PortRegistry` holds its ports through a `WeakReference` — it is an index,
 * not an owner — so the map's value can no longer reach its own key. Nothing
 * else changed: no public API, no `Port` implementation, no call site. The
 * anchor that keeps a live owner's ports alive is the owner itself, which is
 * the registration contract already (`registerPort`: "the name must match the
 * property it is assigned to"). Name entries are never removed, so `names()`
 * and `register`'s duplicate check are unaffected.
 *
 * That matters because nothing in `civictech.cell` exposes a release for a
 * *top-level* owner: `PortRegistry.release` is `internal`, and `ManagedHost`
 * has no close/shutdown that calls it. Before the fix a host process that
 * constructed and dropped `ManagedHost`s leaked every one of them and
 * everything they hosted, with no way for a caller outside `:kernel` to
 * prevent it — a product defect, not a stress-harness one.
 *
 * Measured on the same harness the leak was measured on
 * (`WsAnnouncementStressTestKt`, `-Xmx4g`, JDK 25 macOS aarch64): before, the
 * run stopped at its 80%-retained heap ceiling at iteration 15878 = 211
 * KB/iteration; after, 20000 iterations complete with `heapRetained=0.5%`. JFR
 * old-object sampling with `path-to-gc-roots=true` at iteration 5000 attributed
 * 4019 of 4077 sampled objects (230 MB of 232 MB) to `PortRegistry.registries`
 * before, and 0 of 219 after.
 */
class PortRegistryRetentionTest {

    /**
     * The ordinary shape: the served implementation reads [seen], a property of
     * the cell, so the object expression carries a reference to the cell and
     * `registries[cell] -> PortRegistry -> inlet -> impl -> cell` closes.
     */
    class SelfServingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val seen = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    seen += input
                }
            })
        }
    }

    /** The control: same shape, but the implementation captures nothing. */
    class DetachedCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    @Test
    fun `a dropped ManagedHost IS collectable`() {
        val host = droppedHost()
        collect()
        host.get() shouldBe null
    }

    @Test
    fun `a dropped cell whose port implementation closes over it IS collectable`() {
        val cell = droppedSelfServing()
        collect()
        cell.get() shouldBe null
    }

    @Test
    fun `a dropped cell whose port implementation does not close over it IS collectable`() {
        val cell = droppedDetached()
        collect()
        cell.get() shouldBe null
    }

    /**
     * The anchor invariant that computenet-w5sm's weak port values rely on: a
     * **live** owner's ports survive collection, because the owner holds them.
     * `cell` is a named local here on purpose — that is exactly the strong
     * reference under test, and it is the one shape in this file where a frame
     * local is correct rather than a bug.
     */
    @Test
    fun `a live owner's ports survive collection`() {
        val cell = SelfServingCell()
        collect()
        PortRegistry.of(cell)["inlet"] shouldBeSameInstanceAs cell.inlet
        PortRegistry.of(cell).names() shouldBe setOf("inlet")
    }

    @Test
    fun `releasing the PortRegistry entry is what makes it collectable`() {
        val host = droppedHost(release = true)
        collect()
        host.get() shouldBe null

        val cell = droppedSelfServing(release = true)
        collect()
        cell.get() shouldBe null
    }

    /**
     * Builds a host, optionally releases its global-map entries, and returns
     * only a [WeakReference] to it — so no strong reference to the host outlives
     * this call frame.
     */
    private fun droppedHost(release: Boolean = false): WeakReference<ManagedHost> {
        val host = ManagedHost()
        if (release) {
            ProtocolSupport.unbind(host)
            PortRegistry.release(host)
        }
        return WeakReference(host)
    }

    private fun droppedSelfServing(release: Boolean = false): WeakReference<SelfServingCell> {
        val cell = SelfServingCell()
        if (release) {
            ProtocolSupport.unbind(cell)
            PortRegistry.release(cell)
        }
        return WeakReference(cell)
    }

    private fun droppedDetached(): WeakReference<DetachedCell> = WeakReference(DetachedCell())

    /**
     * Force enough collection that a weakly-reachable object is cleared. Several
     * rounds with allocation between them rather than a single `System.gc()`:
     * one call is a hint, and a weak referent can survive a young collection.
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
