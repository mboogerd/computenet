package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.IntakeClosedException
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.port.Use
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ReplicationConcurrencyTest {
    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Fixture {
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        val remote = CellRef(logicalId, 0)
        val received = CopyOnWriteArrayList<HostedPortInvocation>()
        val sink = InvocationSink { received += it }

        init {
            replication.replicate(local, host)
            ops(local).add("incumbent")
            controller.runToIdle()
        }

        fun ops(cell: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        fun pauseFormation(): PausedFormation {
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            replication.beforeLinkLifecycle = { candidate, target ->
                if (candidate === local && target == remote) {
                    entered.countDown()
                    check(resume.await(20, TimeUnit.SECONDS)) { "the retirement path never released formation" }
                }
            }
            val publishing = FutureTask<Unit> {
                registry.publishFromPeer(remote, sink, peer = REMOTE) shouldBe null
                Unit
            }
            Thread.ofVirtual().start(publishing)
            check(entered.await(20, TimeUnit.SECONDS)) { "formation never reached the lifecycle boundary" }
            replication.beforeLinkLifecycle = null
            return PausedFormation(resume, publishing)
        }

        fun reannounce() {
            received.clear()
            registry.publish(remote, sink, peer = REMOTE)
            controller.runToIdle()
        }

        fun receivedAdds(): Set<String> = received.flatMapTo(mutableSetOf()) { invocation ->
            (invocation.invocation.args.firstOrNull() as? SetDelta<*>)
                ?.adds?.keys?.filterIsInstance<String>().orEmpty()
        }
    }

    private class PausedFormation(
        private val resume: CountDownLatch,
        private val publishing: FutureTask<Unit>,
    ) {
        fun finish() {
            resume.countDown()
            publishing.get(20, TimeUnit.SECONDS)
        }
    }

    private companion object {
        val REMOTE = PeerId("remote")
        val AUTHOR = PeerId("author")

        fun authorityAdapterRef(ref: CellRef): CellRef = CellRef(
            UUID.nameUUIDFromBytes("write-authority:${ref.id}".toByteArray(StandardCharsets.UTF_8)),
            ref.instanceId,
        )
    }

    @Test
    fun `same-thread unpublish during unlimited catch-up leaves no stale link`() {
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        replication.replicate(local, host)
        (HostedCellProxy.create(local.ref, registry, SetInletProxy::class.java) as SetInletProxy)
            .inlet.call.add("catch-up")
        controller.runToIdle()

        val remote = CellRef(logicalId, 0)
        val failedSends = AtomicInteger()
        lateinit var dead: InvocationSink
        dead = InvocationSink { invocation ->
            failedSends.incrementAndGet()
            registry.unpublishRemotes(via = dead)
            throw IntakeClosedException(invocation.cellRef)
        }

        registry.publishFromPeer(remote, dead, peer = REMOTE) shouldBe null
        controller.runToIdle()

        failedSends.get() shouldBe 1
        registry.location(remote) shouldBe null
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 0
    }

    @Test
    fun `re-announced catch-up does not block concurrent retirement`() {
        val fixture = Fixture()
        fixture.registry.publishFromPeer(fixture.remote, fixture.sink, peer = REMOTE) shouldBe null

        val catchUpEntered = CountDownLatch(1)
        val releaseCatchUp = CountDownLatch(1)
        val blockedSink = InvocationSink {
            catchUpEntered.countDown()
            check(releaseCatchUp.await(20, TimeUnit.SECONDS)) { "the retirement path never released catch-up" }
        }
        val reannouncing = FutureTask<Unit> {
            fixture.registry.publish(fixture.remote, blockedSink, peer = REMOTE)
            Unit
        }
        Thread.ofVirtual().start(reannouncing)
        check(catchUpEntered.await(20, TimeUnit.SECONDS)) { "re-announcement never re-fired catch-up" }

        val retiring = FutureTask<Unit> {
            fixture.registry.unpublish(fixture.remote)
            Unit
        }
        Thread.ofVirtual().start(retiring)
        try {
            retiring.get(2, TimeUnit.SECONDS)
        } finally {
            releaseCatchUp.countDown()
        }
        reannouncing.get(20, TimeUnit.SECONDS)

        fixture.registry.location(fixture.remote) shouldBe null
        fixture.replication.linkCountAmong(setOf(fixture.local.ref, fixture.remote)) shouldBe 0
    }

    @Test
    fun `formation paused before eviction cannot resurrect the evicted side`() {
        val fixture = Fixture()
        val paused = fixture.pauseFormation()

        fixture.replication.evict(fixture.local, fixture.host) shouldBe true
        paused.finish()
        fixture.controller.runToIdle()

        fixture.replication.linkCountAmong(setOf(fixture.local.ref, fixture.remote)) shouldBe 0
    }

    @Test
    fun `formation paused before rebind keeps the replacement as the one linked source`() {
        val fixture = Fixture()
        val paused = fixture.pauseFormation()
        val replacement = SetCell<String>(fixture.local.ref)

        fixture.replication.rebind(fixture.local, replacement, fixture.host)
        paused.finish()
        fixture.controller.runToIdle()
        fixture.ops(replacement).add("replacement")
        fixture.controller.runToIdle()
        fixture.reannounce()

        fixture.replication.linkCountAmong(setOf(replacement.ref, fixture.remote)) shouldBe 1
        fixture.receivedAdds() shouldBe setOf("incumbent", "replacement")
    }

    @Test
    fun `formation paused before crash supersede keeps the rebuilt instance as the one linked source`() {
        val fixture = Fixture()
        val paused = fixture.pauseFormation()
        val rebuiltHost = ManagedHost(scheduler = fixture.controller.scheduler(), registry = fixture.registry)
        val rebuilt = SetCell<String>(fixture.local.ref)

        fixture.replication.replicate(rebuilt, rebuiltHost)
        paused.finish()
        fixture.controller.runToIdle()
        fixture.ops(rebuilt).add("rebuilt")
        fixture.controller.runToIdle()
        fixture.reannounce()

        fixture.replication.linkCountAmong(setOf(rebuilt.ref, fixture.remote)) shouldBe 1
        fixture.receivedAdds() shouldBe setOf("rebuilt")
    }

    @Test
    fun `formation paused across failed crash rebuild cannot outlive replicate rollback`() {
        val fixture = Fixture()
        val rebuiltHost = ManagedHost(scheduler = fixture.controller.scheduler(), registry = fixture.registry)
        rebuiltHost.managementInlet.call.spawn(SetCell<String>(authorityAdapterRef(fixture.local.ref)))
        fixture.controller.runToIdle()
        val paused = fixture.pauseFormation()
        val signing = StubWriteSigning(AUTHOR)
        val rebuilt = SetCell<String>(fixture.local.ref)

        val failure = shouldThrow<IllegalArgumentException> {
            fixture.replication.replicate(
                rebuilt,
                rebuiltHost,
                WriteAuthority.Principal(AUTHOR),
                signing.signer(AUTHOR),
                signing.verifier,
            )
        }
        failure.message.orEmpty() shouldContain "Cell already spawned"
        paused.finish()
        fixture.controller.runToIdle()

        fixture.replication.linkCountAmong(setOf(rebuilt.ref, fixture.remote)) shouldBe 0
    }
}
