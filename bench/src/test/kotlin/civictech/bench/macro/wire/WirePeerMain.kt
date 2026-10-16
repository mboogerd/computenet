package civictech.bench.macro.wire

import civictech.bench.macro.InstrumentedScheduler
import civictech.bench.macro.SweepRunner
import civictech.bench.macro.mesh.MeshRefs
import civictech.bench.macro.mesh.SetDeltaInletProxy
import civictech.bench.macro.mesh.SetDeltaLeftProxy
import civictech.bench.macro.mesh.SetDeltaRightProxy
import civictech.bench.macro.mesh.SetOpsProxy
import civictech.bench.macro.shape.GeneratedShape
import civictech.bench.macro.shape.Shape
import civictech.bench.macro.shape.ShapeSpec
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.Peering
import civictech.testkit.JvmPeer
import civictech.wire.WsTransport
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/** One forked REAL peer used to cross-check the in-process benchmark mesh over WebSockets. */
object WirePeerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val index = inferredIndex(args)
        try {
            val launch = WirePeerProtocol.parseArgv(args)
            WirePeer(launch).use { it.run() }
        } catch (failure: Throwable) {
            val cause = if (failure is PeerFailure) failure.causeText else describe(failure)
            println(WirePeerProtocol.failed(index, cause))
            System.out.flush()
            exitProcess(1)
        }
    }

    private fun inferredIndex(args: Array<String>): Int {
        val at = args.indexOf("--index")
        return args.getOrNull(at + 1)?.toIntOrNull() ?: 0
    }
}

private class WirePeer(private val launch: WirePeerProtocol.Launch) : AutoCloseable {
    private val coordinate = launch.coordinate
    private val index = launch.index
    private val registry = LocationRegistry()
    private val scheduler = InstrumentedScheduler(VirtualThreadScheduler("bench-wire-$index"))
    private val dataHost = ManagedHost(scheduler = scheduler, registry = registry)
    private val bridgeHost = ManagedHost(scheduler = scheduler, registry = registry)
    private val side = Peering.Side(registry, bridgeHost, peer = PeerId("bench-$index"))
    private val deadLetters = CopyOnWriteArrayList<DeadLetter>()
    private val byteCounters = mutableListOf<AtomicLong>()
    private val connections = mutableListOf<WsTransport.WsConnection>()

    private var listener: WsTransport.WsListener? = null
    private var generated: GeneratedShape? = null
    private var placementByHandle: Map<String, Int> = emptyMap()
    private var startCounts: PeerCounts? = null

    init {
        listOf(dataHost, bridgeHost).forEach { host ->
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }
    }

    fun run() {
        val activeListener = WsTransport.listen(0, side).also { listener = it }
        protocolLine(JvmPeer.PORT_LINE_PREFIX + "ws " + activeListener.port)
        launch.peerPorts.toSortedMap().forEach { (_, port) ->
            connections += WsTransport.connect(URI("ws://localhost:$port"), side)
        }

        val expectedRemoteTargets = applySlice(regenerate())
        awaitAnnouncements(expectedRemoteTargets)
        installByteTaps(expectedRemoteTargets)
        checkHealthy()
        protocolLine(WirePeerProtocol.ready(index))

        while (true) {
            val command = readlnOrNull() ?: return
            checkHealthy()
            when (command.trim()) {
                WirePeerProtocol.GO -> beginAndDrive()
                WirePeerProtocol.READ -> protocolLine(WirePeerProtocol.counts(index, awaitCounts()))
                WirePeerProtocol.STOP -> return
                else -> failPeer("unknown command '$command'")
            }
        }
    }

    override fun close() {
        listener?.let { runCatching { it.stop(1_000) } }
        connections.forEach { runCatching { it.shutdown() } }
        scheduler.shutdown()
    }

    private fun regenerate(): GeneratedShape {
        val shape = runCatching { Shape.valueOf(coordinate.shape) }
            .getOrElse { throw IllegalArgumentException("unknown shape '${coordinate.shape}'", it) }
        return ShapeSpec(
            shape = shape,
            seed = coordinate.shapeSeed,
            cardinality = SweepRunner.nodeCount(shape, coordinate.fanOut),
            fanOut = coordinate.fanOut,
            hostCount = coordinate.hostCount,
        ).generate().also { generated = it }
    }

    private fun applySlice(shape: GeneratedShape): Set<CellRef> {
        val steps = shape.spec.lowered()
        val spawns = steps.filterIsInstance<SpawnStep>()
        val connects = steps.filterIsInstance<ConnectStep>()
        require(spawns.size + connects.size == steps.size) {
            "wire peer supports only SpawnStep and ConnectStep"
        }
        placementByHandle = spawns.associate { spawn -> spawn.handle to placementOf(spawn) }
        require(placementByHandle.size == spawns.size) { "duplicate spawn handle in generated shape" }

        spawns.filter { placementByHandle.getValue(it.handle) == index }.forEach { spawn ->
            val ref = MeshRefs.ref(spawn.handle, index, coordinate.hostCount)
            val cell = spawn.factory.create(ref)
            require(cell.ref == ref) {
                "spawn '${spawn.handle}' factory returned ref ${cell.ref}, expected $ref"
            }
            dataHost.managementInlet.call.spawn(cell)
        }

        val remoteTargets = linkedSetOf<CellRef>()
        connects.filter { placementByHandle[it.from] == index }.forEach { connect ->
            val sourcePeer = placementByHandle[connect.from]
                ?: throw IllegalArgumentException("connect names unknown source '${connect.from}'")
            val targetPeer = placementByHandle[connect.to]
                ?: throw IllegalArgumentException("connect names unknown target '${connect.to}'")
            val fromRef = MeshRefs.ref(connect.from, sourcePeer, coordinate.hostCount)
            val toRef = MeshRefs.ref(connect.to, targetPeer, coordinate.hostCount)
            if (sourcePeer == targetPeer) {
                val result = dataHost.managementInlet.call.connect(
                    fromRef,
                    connect.outlet,
                    toRef,
                    connect.inlet,
                    connect.options,
                )
                check(result !is LinkResult.Rejected) {
                    "link ${connect.from}.${connect.outlet} → ${connect.to}.${connect.inlet} rejected: " +
                        (result as LinkResult.Rejected).reason
                }
            } else {
                validateRemoteConnect(connect)
                dataHost.managementInlet.call.connect(
                    fromRef,
                    connect.outlet,
                    Use.fixed(routedDeltaInlet(toRef, connect.inlet), PortRef.generate()),
                )
                remoteTargets += toRef
            }
        }
        return remoteTargets
    }

    private fun validateRemoteConnect(step: ConnectStep) {
        require(step.options == LinkOptions.DEFAULT) {
            "cross-host target '${step.to}' uses unsupported non-default LinkOptions"
        }
        require(step.inlet in ROUTED_DELTA_INLETS) {
            "cross-host target '${step.to}' uses unsupported inlet '${step.inlet}'"
        }
    }

    private fun routedDeltaInlet(toRef: CellRef, inlet: String): Propagate<SetDelta<Long>> =
        when (inlet) {
            "inlet" -> (HostedCellProxy.create(toRef, registry, SetDeltaInletProxy::class.java) as SetDeltaInletProxy)
                .inlet.call

            "left" -> (HostedCellProxy.create(toRef, registry, SetDeltaLeftProxy::class.java) as SetDeltaLeftProxy)
                .left.call

            "right" -> (HostedCellProxy.create(toRef, registry, SetDeltaRightProxy::class.java) as SetDeltaRightProxy)
                .right.call

            else -> error("validated routed inlet '$inlet' was not recognized")
        }

    private fun awaitAnnouncements(expected: Set<CellRef>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JvmPeer.LAUNCH_TIMEOUT_MS)
        while (!registry.remoteRefs().containsAll(expected)) {
            checkHealthy()
            if (System.nanoTime() >= deadline) {
                val missing = expected - registry.remoteRefs()
                failPeer("announcements: missing ${missing.joinToString()}")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun installByteTaps(targets: Set<CellRef>) {
        val seen = Collections.newSetFromMap(IdentityHashMap<BridgeEgressCell, Boolean>())
        targets.forEach { target ->
            val remote = registry.location(target) as? LocationRegistry.Remote
                ?: failPeer("announcements: $target is ${registry.location(target)?.javaClass?.name ?: "absent"}")
            val sink = remote.sink
            if (sink !is BridgeEgressCell) {
                failPeer(
                    "byte-seam: ${sink.javaClass.name} (expected BridgeEgressCell from WsTransport.kt:1088)",
                )
            }
            if (seen.add(sink)) {
                val bytes = AtomicLong()
                sink.outlet.subscribe(
                    Use.fixed(
                        object : Propagate<ByteArray> {
                            override fun propagate(value: ByteArray) {
                                bytes.addAndGet(value.size.toLong())
                            }
                        },
                        PortRef.generate(),
                    ),
                )
                byteCounters += bytes
            }
        }
    }

    private fun beginAndDrive() {
        require(startCounts == null) { "go may only be sent once" }
        checkHealthy()
        startCounts = currentCounts()
        val shape = checkNotNull(generated) { "shape was not generated" }
        val inlets = shape.sources
            .filter { placementByHandle.getValue(it) == index }
            .associateWith(::sourceInlet)
        var element = 0L
        while (element < coordinate.cardinality) {
            val source = shape.sources[(element % shape.sources.size).toInt()]
            if (placementByHandle.getValue(source) == index) {
                inlets.getValue(source).add(element)
            }
            element++
        }
        checkHealthy()
    }

    private fun sourceInlet(handle: String): SetOps<Long> {
        val peer = placementByHandle.getValue(handle)
        require(peer == index) { "source '$handle' is placed on peer $peer, not peer $index" }
        val ref = MeshRefs.ref(handle, index, coordinate.hostCount)
        return (HostedCellProxy.create(ref, registry, SetOpsProxy::class.java) as SetOpsProxy).inlet.call
    }

    private fun awaitCounts(): PeerCounts {
        val start = startCounts ?: failPeer("read before go")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JvmPeer.LAUNCH_TIMEOUT_MS)
        var last = currentCounts() - start
        var stableSince = System.nanoTime()
        while (true) {
            checkHealthy()
            val now = System.nanoTime()
            val current = currentCounts() - start
            val locallyIdle = scheduler.idle && current.received == current.enqueued
            if (!locallyIdle || current != last) {
                last = current
                stableSince = now
            } else if (now - stableSince >= TimeUnit.MILLISECONDS.toNanos(WirePeerProtocol.QUIET_MILLIS)) {
                checkHealthy()
                return current
            }
            if (now >= deadline) {
                failPeer(
                    "quiescence: idle=${scheduler.idle} received=${current.received} " +
                        "enqueued=${current.enqueued} counts=$current",
                )
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun currentCounts(): PeerCounts {
        val activeListener = checkNotNull(listener) { "listener has not started" }
        return PeerCounts(
            sent = activeListener.framesSent + connections.sumOf { it.framesSent },
            received = activeListener.framesReceived + connections.sumOf { it.framesReceived },
            enqueued = activeListener.framesEnqueued + connections.sumOf { it.framesEnqueued },
            bytes = byteCounters.sumOf { it.get() },
        )
    }

    private fun checkHealthy() {
        scheduler.failures.firstOrNull()?.let { failPeer(describe(it)) }
        deadLetters.firstOrNull()?.let { failPeer("dead letter: $it") }
    }

    private fun placementOf(step: SpawnStep): Int {
        val placement = step.placement
            ?: throw IllegalArgumentException("spawn '${step.handle}' has null placement")
        val peer = HOST_PLACEMENT.matchEntire(placement)?.groupValues?.get(1)?.toIntOrNull()
        require(peer != null && peer in 0 until coordinate.hostCount) {
            "spawn '${step.handle}' has placement '$placement' outside host0..host${coordinate.hostCount - 1}"
        }
        return peer
    }

    private fun protocolLine(line: String) {
        println(line)
        System.out.flush()
    }

    companion object {
        private const val POLL_MILLIS = 5L
        private val HOST_PLACEMENT = Regex("host(\\d+)")
        private val ROUTED_DELTA_INLETS = setOf("inlet", "left", "right")
    }
}

private class PeerFailure(val causeText: String) : IllegalStateException(causeText)

private fun failPeer(cause: String): Nothing = throw PeerFailure(cause)

private fun describe(failure: Throwable): String =
    "${failure.javaClass.name}: ${failure.message}"
