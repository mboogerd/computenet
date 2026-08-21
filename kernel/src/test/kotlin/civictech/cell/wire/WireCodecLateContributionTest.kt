package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.data.delta.CounterDelta
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * JAR1 [JAR1-REG-08], arm (1) — the *rebuildable* arm: a module whose
 * serializers arrive AFTER `WireCodec` has already encoded frames must still
 * be able to put its delta types on the wire, and before it registers them the
 * failure must be loud rather than silent.
 *
 * The seam under test is [WireCodec.contribute]/[WireCodec.withdraw]. These
 * tests stand in for a dynamically loaded jar (features .1/.3 supply the
 * classloader machinery; nothing here needs it — the codec only ever sees a
 * [WireSerializers] instance, whatever loaded it).
 */
class WireCodecLateContributionTest {

    /** A module's own delta type: unknown to the kernel's baseline module. */
    @Serializable
    @SerialName("LateModuleDelta")
    private data class LateModuleDelta(val payload: String, val revision: Long)

    /** What a dynamically loaded module contributes for [LateModuleDelta]. */
    private class LateModuleSerializers : WireSerializers {
        override val module: SerializersModule = SerializersModule {
            polymorphic(Any::class) { subclass(LateModuleDelta::class, LateModuleDelta.serializer()) }
        }
    }

    /** A *second*, different serializer for a kernel type — a genuine collision. */
    private object RivalCounterDeltaSerializer : KSerializer<CounterDelta> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("RivalCounterDelta", PrimitiveKind.LONG)

        override fun serialize(encoder: Encoder, value: CounterDelta) = encoder.encodeLong(value.amount)
        override fun deserialize(decoder: Decoder): CounterDelta = CounterDelta(decoder.decodeLong())
    }

    private class CollidingSerializers : WireSerializers {
        override val module: SerializersModule = SerializersModule {
            polymorphic(Any::class) { subclass(CounterDelta::class, RivalCounterDeltaSerializer) }
        }
    }

    private val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)

    private fun frame(vararg args: Any?, context: MessageContext? = null) =
        HostedPortInvocation(
            cellRef = CellRef(UUID.randomUUID()),
            portName = "inlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(propagate, args, context),
        )

    /** Every kernel payload this suite touches still crosses unchanged. */
    private fun kernelPayloadsStillRoundTrip() {
        val delta = CounterDelta(7)
        WireCodec.decode(WireCodec.encode(frame(delta))).invocation.args shouldBe listOf(delta)
        WireCodec.decode(WireCodec.encode(frame("milk"))).invocation.args shouldBe listOf("milk")
    }

    @Test
    fun `a delta type contributed after the codec has already encoded round-trips`() {
        // the codec is already built and in use before the module shows up
        kernelPayloadsStillRoundTrip()

        val late = LateModuleDelta("late", 3)
        val contribution = LateModuleSerializers()

        // …and before contribution the type is not encodable — LOUDLY.
        shouldThrow<SerializationException> { WireCodec.encode(frame(late)) }

        WireCodec.contribute(contribution)
        try {
            WireCodec.decode(WireCodec.encode(frame(late))).invocation.args shouldBe listOf(late)
            // the baseline is untouched by the rebuild
            kernelPayloadsStillRoundTrip()
        } finally {
            WireCodec.withdraw(contribution)
        }
    }

    @Test
    fun `withdraw removes exactly that contribution and leaves the kernel payloads intact`() {
        val late = LateModuleDelta("withdrawn", 9)
        val contribution = LateModuleSerializers()

        WireCodec.contribute(contribution)
        try {
            WireCodec.decode(WireCodec.encode(frame(late))).invocation.args shouldBe listOf(late)
        } finally {
            WireCodec.withdraw(contribution)
        }

        shouldThrow<SerializationException> { WireCodec.encode(frame(late)) }
        kernelPayloadsStillRoundTrip()

        // withdrawing something never contributed is a no-op, not a rebuild
        WireCodec.withdraw(LateModuleSerializers())
        kernelPayloadsStillRoundTrip()
    }

    @Test
    fun `a colliding contribution fails fast and leaves the codec on its previous module`() {
        val late = LateModuleDelta("survivor", 1)
        val good = LateModuleSerializers()

        WireCodec.contribute(good)
        try {
            shouldThrow<IllegalArgumentException> { WireCodec.contribute(CollidingSerializers()) }

            // previous module intact: the earlier contribution still works…
            WireCodec.decode(WireCodec.encode(frame(late))).invocation.args shouldBe listOf(late)
            // …and CounterDelta still encodes with the KERNEL serializer, not the rival's
            kernelPayloadsStillRoundTrip()
        } finally {
            WireCodec.withdraw(good)
        }

        // the failed contribution left nothing behind to withdraw
        shouldThrow<SerializationException> { WireCodec.encode(frame(late)) }
        kernelPayloadsStillRoundTrip()
    }
}
