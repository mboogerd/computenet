package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.data.CounterOps
import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.port.PortRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.ListDelta

/**
 * M5.2 (G-15): the wire envelope round-trips every M4 payload type — ids
 * only, no reflection artifacts, context surviving the wire (G-4 on the wire).
 */
class WireCodecTest {

    private fun frame(method: java.lang.reflect.Method, vararg args: Any?, context: MessageContext? = null) =
        HostedPortInvocation(
            cellRef = CellRef(UUID.randomUUID()),
            portName = "inlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(method, args, context),
        )

    private fun roundTrip(hpi: HostedPortInvocation): HostedPortInvocation =
        WireCodec.decode(WireCodec.encode(hpi))

    @Test
    fun `set op with string element round-trips and dispatches`() {
        val add = SetOps::class.java.getMethod("add", Any::class.java)
        val original = frame(add, "milk")
        val decoded = roundTrip(original)

        decoded.cellRef shouldBe original.cellRef
        decoded.portName shouldBe "inlet"
        decoded.invocation.methodName shouldBe "add"
        decoded.invocation.args shouldBe listOf("milk")

        val received = mutableListOf<Any?>()
        decoded.invocation.invoke(object : SetOps<String> {
            override fun add(element: String) { received += element }
            override fun remove(element: String) = error("wrong method")
        })
        received shouldBe listOf("milk")
    }

    @Test
    fun `primitive long arg round-trips as long`() {
        val increment = CounterOps::class.java.getMethod("increment", Long::class.javaPrimitiveType)
        val decoded = roundTrip(frame(increment, 42L))
        decoded.invocation.args shouldBe listOf(42L)

        var total = 0L
        decoded.invocation.invoke(object : CounterOps {
            override fun increment(amount: Long) { total += amount }
            override fun decrement(amount: Long) { increment(-amount) }
        })
        total shouldBe 42L
    }

    @Test
    fun `tag-bearing set delta round-trips exactly`() {
        val delta = SetDelta<Any?>(
            adds = mapOf("milk" to setOf(Timestamp(UUID.randomUUID(), 1), Timestamp(UUID.randomUUID(), 7))),
            dels = mapOf("eggs" to setOf(Timestamp(UUID.randomUUID(), 3))),
        )
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        roundTrip(frame(propagate, delta)).invocation.args shouldBe listOf(delta)
    }

    @Test
    fun `counter, map and list deltas round-trip`() {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        roundTrip(frame(propagate, CounterDelta(-3))).invocation.args shouldBe listOf(CounterDelta(-3))

        val mapDelta = MapDelta<Any?, Any?>(puts = mapOf("votes" to 2L), removals = setOf("stale"))
        roundTrip(frame(propagate, mapDelta)).invocation.args shouldBe listOf(mapDelta)

        val listDelta = ListDelta<Any?>(
            adds = listOf(IndexedValue(0, "first")),
            updates = listOf(IndexedValue(1, "second")),
            removals = listOf(2),
        )
        roundTrip(frame(propagate, listDelta)).invocation.args shouldBe listOf(listDelta)
    }

    @Test
    fun `message context survives the wire`() {
        val context = MessageContext(
            timestamp = Timestamp(UUID.randomUUID(), 99),
            sourcePort = PortRef(UUID.randomUUID(), CellRef(UUID.randomUUID())),
        )
        val add = SetOps::class.java.getMethod("add", Any::class.java)
        roundTrip(frame(add, "x", context = context)).invocation.context shouldBe context
    }

    @Test
    fun `wire bytes carry no reflection artifacts`() {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        val delta = SetDelta<Any?>(adds = mapOf("milk" to setOf(Timestamp(UUID.randomUUID(), 1))))
        val context = MessageContext(Timestamp(UUID.randomUUID(), 1), PortRef(UUID.randomUUID()))
        val bytes = WireCodec.encode(frame(propagate, delta, context = context)).decodeToString()

        bytes shouldNotContain "civictech" // stable @SerialName discriminators, not class names
        bytes shouldNotContain "propagate" // method identity is ids-only
    }

    @Test
    fun `non-contract capture is rejected at encode`() {
        val method = Runnable::class.java.getMethod("run")
        shouldThrow<IllegalStateException> { WireCodec.encode(frame(method)) }
    }

    @Test
    fun `unknown ids are rejected at decode`() {
        val add = SetOps::class.java.getMethod("add", Any::class.java)
        val bytes = WireCodec.encode(frame(add, "x"))
        val corrupted = bytes.decodeToString()
            .replace(Regex("\"methodId\":-?\\d+"), "\"methodId\":1")
            .toByteArray()
        shouldThrow<IllegalStateException> { WireCodec.decode(corrupted) }
    }
}
