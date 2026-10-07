package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.MergeableGroupByCell
import civictech.cell.host.HostScheduler
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.Linked
import civictech.cell.link.PortLink
import civictech.cell.port.CycleHead
import civictech.cell.port.feedbackInlet
import civictech.cell.port.input
import civictech.cell.port.output
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AlignedAdmissionTest {

    private class Fixture(scheduler: HostScheduler? = null) {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry, scheduler = scheduler ?: controller.scheduler())
        val management = host.managementInlet.call

        fun spawn(vararg cells: Cell) {
            cells.forEach(management::spawn)
        }

        fun connect(from: Cell, outlet: String, to: Cell, inlet: String): LinkResult.Connected =
            management.connect(from.ref, outlet, to.ref, inlet).shouldBeInstanceOf<LinkResult.Connected>()

        fun attachOpaque(target: Cell, inletName: String): civictech.cell.port.PortRef {
            val inlet = host.portAt(target.ref, inletName)!!
            val linked = inlet as Linked
            val from = civictech.cell.port.PortRef.generate()
            val link = PortLink(
                from = from,
                to = inlet.ref,
                fromPort = null,
                toPort = inlet,
                role = LinkRole.Consume,
            ) { linked.linking.remove(it) }
            linked.linking.register(link)
            return from
        }
    }

    private class SetCycleHead(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, CycleHead<SetDelta<Int>> {
        val inlet by input<Propagate<SetDelta<Int>>>()
        val outlet by output<Propagate<SetDelta<Int>>>()
        override val feedbackInput by feedbackInlet<SetDelta<Int>> { }

        override fun onActivate(ctx: CellContext) {
            inlet.serve(Propagate { outlet.call.propagate(it) })
        }
    }

    private fun grouped() = MergeableGroupByCell<Int, Int, Int>(
        keyOf = { it },
        accumulate = { it },
        merge = ::maxOf,
    )

    @Test
    fun `ungated contributor directly or one hop upstream is rejected before spawn or link`() {
        val fixture = Fixture()
        val a = SetCell<Int>()
        val b = SetCell<Int>()
        val intersection = IntersectSetCell<Int>()
        fixture.spawn(a, b, intersection)
        fixture.connect(a, "outlet", intersection, "left")
        fixture.connect(b, "outlet", intersection, "right")

        val refsBefore = fixture.registry.localRefs().size
        val linksBefore = intersection.outlet.linking.links.size
        val direct = assertThrows<AlignedAdmissionException> {
            fixture.host.observeAligned { set("wanted", intersection.ref) }
        }
        val directVerdict = direct.verdict.shouldBeInstanceOf<AdmissionVerdict.Rejected.UngatedAncestor>()
        directVerdict.view shouldBe "wanted"
        directVerdict.cell shouldBe intersection.ref
        directVerdict.cellClass shouldBe IntersectSetCell::class.java
        directVerdict.outlet shouldBe intersection.outlet.ref
        direct.message!! shouldContain "IntersectSetCell"
        direct.message!! shouldContain "outlet"
        fixture.registry.localRefs().size shouldBe refsBefore
        intersection.outlet.linking.links.size shouldBe linksBefore

        val filter = FilterCell<Int> { true }
        fixture.spawn(filter)
        fixture.connect(intersection, "outlet", filter, "inlet")
        val deeperRefsBefore = fixture.registry.localRefs().size
        val deeperLinksBefore = filter.outlet.linking.links.size
        val deeper = assertThrows<AlignedAdmissionException> {
            fixture.host.observeAligned { set("wanted", filter.ref) }
        }
        val deeperVerdict = deeper.verdict.shouldBeInstanceOf<AdmissionVerdict.Rejected.UngatedAncestor>()
        deeperVerdict.cell shouldBe intersection.ref
        deeperVerdict.outlet shouldBe intersection.outlet.ref
        fixture.registry.localRefs().size shouldBe deeperRefsBefore
        filter.outlet.linking.links.size shouldBe deeperLinksBefore
    }

    @Test
    fun `frontier-gated contributor is admitted without driving a delta`() {
        val fixture = Fixture()
        val a = SetCell<Int>()
        val b = SetCell<Int>()
        val intersection = IntersectSetCell<Int>(emitOnFrontier = true)
        fixture.spawn(a, b, intersection)
        fixture.connect(a, "outlet", intersection, "left")
        fixture.connect(b, "outlet", intersection, "right")

        val sink = assertDoesNotThrow {
            fixture.host.observeAligned { set("wanted", intersection.ref) }
        }

        sink.current().keys shouldContainExactly setOf("wanted")
    }

    @Test
    fun `unchecked skips only ungated admission and rejects an unregistered name before spawn`() {
        val fixture = Fixture()
        val a = SetCell<Int>()
        val b = SetCell<Int>()
        val intersection = IntersectSetCell<Int>()
        fixture.spawn(a, b, intersection)
        fixture.connect(a, "outlet", intersection, "left")
        fixture.connect(b, "outlet", intersection, "right")

        assertDoesNotThrow {
            fixture.host.observeAligned {
                unchecked("wanted")
                set("wanted", intersection.ref)
            }
        }

        val typoRefsBefore = fixture.registry.localRefs().size
        val typo = assertThrows<IllegalArgumentException> {
            fixture.host.observeAligned {
                set("wanted", intersection.ref)
                unchecked("typo")
            }
        }
        typo.message!! shouldContain "typo"
        fixture.registry.localRefs().size shouldBe typoRefsBefore
    }

    @Test
    fun `linked replicable branch with shared provenance is rejected even when unchecked`() {
        val fixture = Fixture()
        val root = SetCell<Int>()
        val grouped = grouped()
        val peer = grouped()
        fixture.spawn(root, grouped, peer)
        fixture.connect(root, "outlet", grouped, "inlet")
        fixture.connect(peer, "outlet", grouped, "deltaInlet")

        val error = assertThrows<AlignedAdmissionException> {
            fixture.host.observeAligned {
                map("grouped", grouped.ref)
                set("raw", root.ref)
                unchecked("grouped")
            }
        }

        val verdict = error.verdict.shouldBeInstanceOf<AdmissionVerdict.Rejected.DivergentOrigination>()
        verdict.point shouldBe grouped.ref
        verdict.viewsUpstreamOfIt shouldBe setOf("grouped")
        verdict.viewsNotUpstreamOfIt shouldBe setOf("raw")
        verdict.sharedAncestor shouldBe root.ref
        error.message!! shouldContain grouped.ref.toString()
        error.message!! shouldContain "grouped"
        error.message!! shouldContain "raw"
        error.message!! shouldContain root.ref.toString()
    }

    @Test
    fun `unlinked common and independent replicable provenance controls are admitted`() {
        val unlinked = Fixture()
        val unlinkedRoot = SetCell<Int>()
        val unlinkedGrouped = grouped()
        unlinked.spawn(unlinkedRoot, unlinkedGrouped)
        unlinked.connect(unlinkedRoot, "outlet", unlinkedGrouped, "inlet")
        assertDoesNotThrow {
            unlinked.host.observeAligned {
                map("grouped", unlinkedGrouped.ref)
                set("raw", unlinkedRoot.ref)
            }
        }

        val common = Fixture()
        val commonRoot = SetCell<Int>()
        val commonPeer = SetCell<Int>()
        val commonChild = FilterCell<Int> { true }
        common.spawn(commonRoot, commonPeer, commonChild)
        common.connect(commonPeer, "outlet", commonRoot, "deltaInlet")
        common.connect(commonRoot, "outlet", commonChild, "inlet")
        assertDoesNotThrow {
            common.host.observeAligned {
                set("root", commonRoot.ref)
                set("child", commonChild.ref)
            }
        }

        val independent = Fixture()
        val left = SetCell<Int>()
        val leftPeer = SetCell<Int>()
        val right = SetCell<Int>()
        val rightPeer = SetCell<Int>()
        independent.spawn(left, leftPeer, right, rightPeer)
        independent.connect(leftPeer, "outlet", left, "deltaInlet")
        independent.connect(rightPeer, "outlet", right, "deltaInlet")
        assertDoesNotThrow {
            independent.host.observeAligned {
                set("left", left.ref)
                set("right", right.ref)
            }
        }
    }

    @Test
    fun `cycle head on one shared-provenance branch is rejected and a common head is admitted`() {
        val divergent = Fixture()
        val root = SetCell<Int>()
        val head = SetCycleHead()
        divergent.spawn(root, head)
        divergent.connect(root, "outlet", head, "inlet")

        val error = assertThrows<AlignedAdmissionException> {
            divergent.host.observeAligned {
                set("headed", head.ref)
                set("raw", root.ref)
            }
        }
        val verdict = error.verdict.shouldBeInstanceOf<AdmissionVerdict.Rejected.DivergentOrigination>()
        verdict.point shouldBe head.ref
        verdict.viewsUpstreamOfIt shouldBe setOf("headed")
        verdict.viewsNotUpstreamOfIt shouldBe setOf("raw")
        verdict.sharedAncestor shouldBe root.ref

        val common = Fixture()
        val commonRoot = SetCell<Int>()
        val commonHead = SetCycleHead()
        val child = FilterCell<Int> { true }
        common.spawn(commonRoot, commonHead, child)
        common.connect(commonRoot, "outlet", commonHead, "inlet")
        common.connect(commonHead, "outlet", child, "inlet")
        assertDoesNotThrow {
            common.host.observeAligned {
                set("head", commonHead.ref)
                set("child", child.ref)
            }
        }
    }

    @Test
    fun `opaque producer is recorded and admitted without traversal`() {
        val fixture = Fixture()
        val source = FilterCell<Int> { true }
        fixture.spawn(source)
        val opaque = fixture.attachOpaque(source, "inlet")
        val builder = AlignedObserveBuilder().apply { set("remote", source.ref) }

        val verdict = admitAligned(fixture.management, builder.specs, builder.unchecked)
            .shouldBeInstanceOf<AdmissionVerdict.Admitted>()
        verdict.opaque shouldBe setOf(opaque)

        assertDoesNotThrow { fixture.host.observeAligned { set("remote", source.ref) } }
    }

    @Test
    fun `managed host and management inlet entry points return the same rejection`() {
        val fixture = Fixture()
        val a = SetCell<Int>()
        val b = SetCell<Int>()
        val intersection = IntersectSetCell<Int>()
        fixture.spawn(a, b, intersection)
        fixture.connect(a, "outlet", intersection, "left")
        fixture.connect(b, "outlet", intersection, "right")

        val viaHost = assertThrows<AlignedAdmissionException> {
            fixture.host.observeAligned { set("wanted", intersection.ref) }
        }.verdict
        val viaManagement = assertThrows<AlignedAdmissionException> {
            fixture.host.managementInlet.observeAligned { set("wanted", intersection.ref) }
        }.verdict

        viaHost::class shouldBe viaManagement::class
        viaHost.shouldBeInstanceOf<AdmissionVerdict.Rejected.UngatedAncestor>().cell shouldBe intersection.ref
        viaManagement.shouldBeInstanceOf<AdmissionVerdict.Rejected.UngatedAncestor>().cell shouldBe intersection.ref
    }

    @Test
    fun `admission is one host turn and cannot observe a transient concurrent link`() {
        val scheduler = VirtualThreadScheduler("AlignedAdmissionTest-concurrent")
        val fixture = Fixture(scheduler)
        val root = SetCell<Int>()
        val grouped = grouped()
        val peer = grouped()
        fixture.spawn(root, grouped, peer)
        fixture.connect(root, "outlet", grouped, "inlet")

        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        grouped.deltaInlet.linking.onLinked = { link ->
            mutationEntered.countDown()
            check(releaseMutation.await(4, TimeUnit.SECONDS)) { "test did not release transient link" }
            link.unlink()
        }

        val builder = AlignedObserveBuilder().apply {
            map("grouped", grouped.ref)
            set("raw", root.ref)
        }
        val callers = Executors.newFixedThreadPool(2)
        try {
            val connect = callers.submit<LinkResult> {
                fixture.management.connect(peer.ref, "outlet", grouped.ref, "deltaInlet")
            }
            check(mutationEntered.await(2, TimeUnit.SECONDS)) { "connect never installed its transient link" }

            val admissionStarted = CountDownLatch(1)
            val admission = callers.submit<AdmissionVerdict> {
                admissionStarted.countDown()
                admitAligned(fixture.management, builder.specs, builder.unchecked)
            }
            check(admissionStarted.await(2, TimeUnit.SECONDS)) { "admission caller never started" }

            var premature: Any? = null
            try {
                premature = admission.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // Expected: admission is queued behind the in-flight topology mutation.
            } catch (e: ExecutionException) {
                premature = e.cause ?: e
            } finally {
                releaseMutation.countDown()
            }

            premature shouldBe null
            connect.get(2, TimeUnit.SECONDS).shouldBeInstanceOf<LinkResult.Connected>()
            admission.get(2, TimeUnit.SECONDS).shouldBeInstanceOf<AdmissionVerdict.Admitted>()
        } finally {
            releaseMutation.countDown()
            callers.shutdownNow()
            scheduler.shutdown()
        }
    }
}
