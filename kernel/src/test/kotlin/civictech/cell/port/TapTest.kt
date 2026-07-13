package civictech.cell.port

import civictech.cell.Owned
import civictech.cell.verify.InvariantCell
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@Contract
interface TapOwnedPush {
    fun push(@Key buffer: Owned<String>)
}

/**
 * W2.4 (G-47, spec 20/23 §Taps): an uncounted, read-only Observe-role link
 * that fires a Borrowed projection before the sole Consume-role subscriber,
 * without perturbing the SPSC consume-once contract.
 */
class TapTest {

    @Test
    fun `a tap does not count toward the SPSC consumer limit`() {
        val outlet = FanOutlet.create<TapOwnedPush>()
        outlet.subscribe(Use.fixed(object : TapOwnedPush {
            override fun push(buffer: Owned<String>) {}
        }, PortRef.generate()))

        // A second Consume-role subscriber is still refused (SPSC intact).
        shouldThrow<IllegalStateException> {
            outlet.subscribe(Use.fixed(object : TapOwnedPush {
                override fun push(buffer: Owned<String>) {}
            }, PortRef.generate()))
        }

        // But an Observe-role tap is always admitted, exclusive bit or not.
        outlet.tap(Use.fixed(object : TapOwnedPush {
            override fun push(buffer: Owned<String>) {}
        }, PortRef.generate()))
    }

    @Test
    fun `taps fire first, in emission order, before the sole consumer takes the payload`() {
        val outlet = FanOutlet.create<TapOwnedPush>()
        val order = mutableListOf<String>()
        val consumed = mutableListOf<String>()
        val observedByTap1 = mutableListOf<String>()
        val observedByTap2 = mutableListOf<String>()

        outlet.tap(Use.fixed(object : TapOwnedPush {
            override fun push(buffer: Owned<String>) {
                order += "tap1"
                observedByTap1 += buffer.borrow().value
            }
        }, PortRef.generate()))
        outlet.tap(Use.fixed(object : TapOwnedPush {
            override fun push(buffer: Owned<String>) {
                order += "tap2"
                observedByTap2 += buffer.borrow().value
            }
        }, PortRef.generate()))
        outlet.subscribe(Use.fixed(object : TapOwnedPush {
            override fun push(buffer: Owned<String>) {
                order += "consumer"
                consumed += buffer.take()
            }
        }, PortRef.generate()))

        val owned = Owned("payload")
        outlet.call.push(owned)

        order shouldBe listOf("tap1", "tap2", "consumer")
        observedByTap1 shouldBe listOf("payload")
        observedByTap2 shouldBe listOf("payload")
        consumed shouldBe listOf("payload")
    }

    @Test
    fun `an invariant observes an Owned pipeline as a tap without perturbing consume-once`() {
        val outlet = FanOutlet.create<TapOwnedPush>()
        val consumed = mutableListOf<String>()

        outlet.subscribe(Use.fixed(object : TapOwnedPush {
            override fun push(buffer: Owned<String>) {
                consumed += buffer.take()
            }
        }, PortRef.generate()))

        var lastObserved: String? = null
        val watcher = InvariantCell<Owned<String>, Unit>(
            "owned payloads stay non-empty",
            Unit,
            fold = { _, _ -> },
            check = { _, value ->
                // read-only borrow: the invariant never consumes the payload
                lastObserved = value.borrow().value
                if (lastObserved!!.isEmpty()) "empty payload observed" else null
            },
        )
        val tapAdapter = object : TapOwnedPush {
            override fun push(buffer: Owned<String>) {
                watcher.inlet.call.propagate(buffer)
            }
        }
        outlet.tap(Use.fixed(tapAdapter, PortRef.generate()))

        val owned = Owned("payload")
        outlet.call.push(owned)

        lastObserved shouldBe "payload"
        // the sole consumer's take() succeeded exactly once, unperturbed by the tap
        consumed shouldBe listOf("payload")
    }
}
