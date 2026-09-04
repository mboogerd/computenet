package civictech.dialogue.mint

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.MapDelta
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import civictech.dialogue.ClaimKey
import civictech.dialogue.Utterance
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * computenet-zxt5: the executable half of the recorded decision that the
 * dialogue mint vocabulary stays **non**-wire-capable and the derived
 * pipeline stays volatile ([civictech.dialogue.DialogueRuntime]'s `isDurable`
 * KDoc, [civictech.dialogue.DialogueWireSerializers]).
 *
 * The decision rests on two factual claims that prose alone cannot keep
 * honest, so both are pinned here:
 *
 * 1. **Today the derived stages are unencodable**, and specifically because
 *    of their `GroupByCell` *keys* — `projectedStances` is keyed by
 *    `Pair<String, ClaimKey>` and the two provenance folds by the
 *    `@JvmInline value class`es `ClaimKey`/`RelationKey`, none of which has a
 *    polymorphic registration under `WireCodec`'s `Any`-rooted scope. This is
 *    what makes "journaling them is impossible without first making the mint
 *    vocabulary wire-capable" a measured statement rather than a guess.
 * 2. **The gap is registration policy, not a serialization impossibility.**
 *    An ordinary `WireSerializers` contribution — `PairSerializer` over the
 *    polymorphic `Any` serializer for both components, and the generated
 *    serializer for the value class — makes both key shapes encode. That is
 *    the answer to "how would a compound `GroupByCell` key obtain a
 *    polymorphic registration"; see `GroupByCell`'s KDoc for why it is
 *    deliberately not applied in production.
 *
 * The second arm is why this file is worth its runtime: if anyone later needs
 * a compound-keyed `GroupByCell` on the wire, the mechanism is here, already
 * demonstrated, and the first arm is the tripwire that says the recorded
 * decision has been overtaken and must be revisited rather than silently
 * contradicted.
 */
class MintWireCapabilityTest {

    private val propagate = Propagate::class.java.methods.single { it.name == "propagate" }

    /** The agora `DurabilityTest` idiom: encode a payload as a journal/wire frame would. */
    private fun encode(payload: Any): ByteArray = WireCodec.encode(
        HostedPortInvocation(
            cellRef = CellRef(UUID.randomUUID()),
            portName = "inlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(propagate, arrayOf(payload)),
        ),
    )

    /** `projectedStances`' outlet payload shape: `MapDelta<Pair<String, ClaimKey>, _>`. */
    private fun pairKeyedDelta(): Any =
        MapDelta(mapOf(("ada" to ClaimKey("the sky is blue")) as Any to 1L as Any), emptySet())

    /** The two provenance folds' key shape: a `@JvmInline value class`. */
    private fun valueClassKeyedDelta(): Any =
        MapDelta(mapOf(ClaimKey("the sky is blue") as Any to 1L as Any), emptySet())

    @Test
    fun `the ingress payload is wire-capable and a String-keyed MapDelta encodes`() {
        // Positive control for the instrument, and the one registration
        // DialogueWireSerializers actually makes.
        encode(Utterance(id = "u1", turn = 0, speaker = "ada", tsMillis = 0L, text = "the sky is blue"))
        // …and MapDelta itself is registered, so the failures below are about
        // the KEY type and nothing else.
        encode(MapDelta(mapOf("ada" as Any to 1L as Any), emptySet()))
    }

    @Test
    fun `a compound GroupByCell key has no polymorphic registration under WireCodec`() {
        val failure = assertFailsWith<SerializationException> { encode(pairKeyedDelta()) }
        assertContains(
            failure.message ?: "",
            "Serializer for subclass 'Pair' is not found in the polymorphic scope of 'Any'",
            message = "projectedStances' Pair key must still be unregistered (computenet-zxt5's decision)",
        )
    }

    @Test
    fun `a value-class GroupByCell key has no polymorphic registration either`() {
        val failure = assertFailsWith<SerializationException> { encode(valueClassKeyedDelta()) }
        assertContains(
            failure.message ?: "",
            "Serializer for subclass 'ClaimKey' is not found in the polymorphic scope of 'Any'",
            message = "the provenance folds' ClaimKey key must still be unregistered (computenet-zxt5's decision)",
        )
    }

    @Test
    fun `both key shapes become encodable through an ordinary WireSerializers contribution`() {
        val polyAny = PolymorphicSerializer(Any::class)
        val contribution = object : WireSerializers {
            override val module: SerializersModule = SerializersModule {
                polymorphic(Any::class) {
                    @Suppress("UNCHECKED_CAST")
                    subclass(Pair::class, PairSerializer(polyAny, polyAny) as KSerializer<Pair<*, *>>)
                    // A @JvmInline value class DOES take a polymorphic
                    // registration here (measured) — the obstacle was never
                    // the inline representation.
                    subclass(ClaimKey::class, ClaimKey.serializer())
                }
            }
        }
        // WireCodec's registration seam is process-global, so this contribution
        // is withdrawn in a finally and the withdrawal is itself asserted: the
        // three arms above must not depend on execution order.
        try {
            WireCodec.contribute(contribution)
            assertTrue(encode(pairKeyedDelta()).isNotEmpty(), "Pair-keyed MapDelta encodes once Pair is registered")
            assertTrue(encode(valueClassKeyedDelta()).isNotEmpty(), "ClaimKey-keyed MapDelta encodes once registered")
        } finally {
            WireCodec.withdraw(contribution)
        }
        assertFailsWith<SerializationException>("withdraw must restore the unregistered baseline") {
            encode(pairKeyedDelta())
        }
    }
}
