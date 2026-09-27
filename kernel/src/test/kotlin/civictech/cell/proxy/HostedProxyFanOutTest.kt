package civictech.cell.proxy

import civictech.cell.Consumer
import civictech.cell.MapperCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * computenet-mt306: one outlet fanned out to two hosted-proxy inlets on another
 * host. A hosted proxy's `inlet.ref` is null (the real ref lives on the remote
 * side), so both proxies used to land on `FanOutlet`'s single null-ref key and
 * the second silently replaced the first.
 */
class HostedProxyFanOutTest {

    interface IntInlet {
        val inlet: Use<Consumer<Int>>
    }

    private class Fixture {
        val host1 = ManagedHost()
        val host2 = ManagedHost()
        val source = MapperCell<Int, Int>(f = { it })
        val seenB = LinkedBlockingQueue<Int>()
        val seenC = LinkedBlockingQueue<Int>()
        val b = MapperCell<Int, Int>(f = { seenB.put(it); it })
        val c = MapperCell<Int, Int>(f = { seenC.put(it); it })

        init {
            host1.managementInlet.call.spawn(source)
            host2.managementInlet.call.spawn(b)
            host2.managementInlet.call.spawn(c)
        }

        fun proxyInlet(ref: civictech.cell.CellRef): Use<Consumer<Int>> =
            host2.lookup<IntInlet>(ref)!!.inlet

        fun push(v: Int) = host1.lookup<IntInlet>(source.ref)!!.inlet.call.provide(v)
    }

    /**
     * The report reproduced on `090a7d4c`: the second `linkTo` replaced the
     * first on `FanOutlet`'s shared null-ref key and `b` received nothing
     * (`Expected 7 but actual was null`). The fix takes the acceptance's
     * "fails loudly" branch — the second attachment is refused, naming the null
     * port ref — and the first link is left intact.
     */
    @Test
    fun `a second linkTo onto a null-ref hosted-proxy inlet fails loudly and keeps the first link`() {
        val f = Fixture()
        f.source.outlet.linkTo(f.proxyInlet(f.b.ref))

        val refused = shouldThrow<IllegalStateException> {
            f.source.outlet.linkTo(f.proxyInlet(f.c.ref))
        }
        refused.message shouldContain "null port ref"
        refused.message shouldContain "Use.fixed"

        f.push(7)
        f.seenB.poll(5, TimeUnit.SECONDS) shouldBe 7
        f.seenC.poll(200, TimeUnit.MILLISECONDS).shouldBeNull()
    }

    @Test
    fun `re-linking the same null-ref proxy object is idempotent`() {
        val f = Fixture()
        val inlet = f.proxyInlet(f.b.ref)
        f.source.outlet.linkTo(inlet)
        f.source.outlet.linkTo(inlet)

        f.push(7)
        f.seenB.poll(5, TimeUnit.SECONDS) shouldBe 7
        f.seenB.poll(200, TimeUnit.MILLISECONDS).shouldBeNull()
    }

    /** The management-plane spelling of the same link reaches the same guard. */
    @Test
    fun `host connect to a second null-ref proxy Use fails loudly`() {
        val f = Fixture()
        f.host1.managementInlet.call.connect(f.source.ref, "outlet", f.proxyInlet(f.b.ref))
        shouldThrow<IllegalStateException> {
            f.host1.managementInlet.call.connect(f.source.ref, "outlet", f.proxyInlet(f.c.ref))
        }.message shouldContain "null port ref"
    }

    /**
     * The report's second observation, documented as INTENDED: the ref-to-ref
     * `connect(from, outlet, to, inlet)` resolves both cells on the host it is
     * called on (`LinkAdmission.connect`), so a target hosted elsewhere is
     * "Target cell not found". The cross-host form is
     * `connect(from, outlet, to: Use)` with a remote `Use` (as above), which is
     * what `BatchedDispatchTest` does.
     */
    @Test
    fun `ref-to-ref connect across hosts is refused as target not found by design`() {
        val f = Fixture()
        shouldThrow<IllegalArgumentException> {
            f.host1.managementInlet.call.connect(f.source.ref, "outlet", f.b.ref, "inlet")
        }.message shouldContain "Target cell not found"
    }

    @Test
    fun `Use fixed with generated port refs delivers to both`() {
        val f = Fixture()
        f.source.outlet.linkTo(Use.fixed(f.proxyInlet(f.b.ref).call, PortRef.generate()))
        f.source.outlet.linkTo(Use.fixed(f.proxyInlet(f.c.ref).call, PortRef.generate()))

        f.push(7)

        f.seenB.poll(5, TimeUnit.SECONDS) shouldBe 7
        f.seenC.poll(5, TimeUnit.SECONDS) shouldBe 7
    }
}
