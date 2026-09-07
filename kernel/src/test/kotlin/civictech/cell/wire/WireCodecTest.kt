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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.Base64
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

    /**
     * BS-18 ([DSC1-WIRE-01..03], computenet-ssa.4.1): a frame encoded with
     * the announcement-signing fields populated is decoded by the
     * pre-existing decode path — decode succeeds, the resulting invocation
     * equals the unsigned equivalent, and VERSION stays 2. WireCodec.encode
     * has no signing caller yet (that's the emit-side successor), so this
     * builds the signed wire bytes by injecting the fields into an
     * otherwise-ordinary encoded frame's JSON, the same technique
     * `unknown ids are rejected at decode` above uses to corrupt a frame.
     */
    @Test
    fun `BS-18 - additive signing fields decode without changing the invocation, VERSION unchanged`() {
        val add = SetOps::class.java.getMethod("add", Any::class.java)
        val original = frame(add, "milk")
        val unsignedBytes = WireCodec.encode(original)
        val unsignedDecoded = WireCodec.decode(unsignedBytes)

        val rawSignature = "signature-bytes-not-a-real-signature".toByteArray()
        val signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rawSignature)
        val obj = Json.parseToJsonElement(unsignedBytes.decodeToString()).jsonObject
        val signedObj = JsonObject(
            obj + mapOf(
                "signature" to JsonPrimitive(signatureB64),
                "signerKeyId" to JsonPrimitive("key-1"),
                "sigCounter" to JsonPrimitive(7L),
                "notAfter" to JsonPrimitive(1_700_000_000_000L),
            ),
        )
        val signedBytes = Json.encodeToString(JsonObject.serializer(), signedObj).toByteArray()

        // decode: succeeds, and the resulting invocation equals the unsigned equivalent
        val signedDecoded = WireCodec.decode(signedBytes)
        signedDecoded shouldBe unsignedDecoded

        // decodeFrame: the frame the successor ingress gate needs is reachable,
        // and the four fields round-trip byte-exact (base64 string equality
        // implies the decoded bytes are identical; verified explicitly below too)
        val decodedFrame = WireCodec.decodeFrame(signedBytes)
        decodedFrame.invocation shouldBe unsignedDecoded
        decodedFrame.frame.signature shouldBe signatureB64
        Base64.getUrlDecoder().decode(decodedFrame.frame.signature) shouldBe rawSignature
        decodedFrame.frame.signerKeyId shouldBe "key-1"
        decodedFrame.frame.sigCounter shouldBe 7L
        decodedFrame.frame.notAfter shouldBe 1_700_000_000_000L

        // VERSION unchanged — the additive-field non-goal, checked directly
        WireCodec.VERSION shouldBe 2
        decodedFrame.frame.version shouldBe 2
    }

    // ------------------------------------------------------------------
    // KE3-39 decision evidence (computenet-5zba)
    //
    // These three tests are the measurements the decision recorded in
    // `doc/spec/40-distribution/42-replication.md` §"Wire compatibility of
    // additive fields (KE3-39)" § "Decision: no mechanism" rests on. They are
    // deliberately here rather than in prose only: the decision turns on what
    // the two candidate cheap fixes and the candidate VERSION mechanism
    // actually do, and a prose measurement decays silently while a test does
    // not. Each carries a CONTROL that fails if the setting under test were
    // inert, so a green run cannot be green for the wrong reason.
    // ------------------------------------------------------------------

    /** The `["Stall", {...}]` payload object a peer actually puts on the wire. */
    private fun stallPayloadOnTheWire(notice: StallNotice): kotlinx.serialization.json.JsonElement {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        val encoded = WireCodec.encode(frame(propagate, notice)).decodeToString()
        return Json.parseToJsonElement(encoded).jsonObject["args"]!!.jsonArray[0].jsonArray[1]
    }

    /**
     * `ignoreUnknownKeys = true` is not the fix, re-measured on this branch:
     * it rescues the unknown *key* hazard and does nothing at all for the
     * unknown *constant* hazard, which is a value of a key the reader already
     * knows. The first half is the control — it proves the setting is live on
     * this decoder, so the throw in the second half is the constant and not a
     * mis-set-up probe.
     */
    @Test
    fun `KE3-39 - ignoreUnknownKeys rescues an unknown KEY but never an unknown enum CONSTANT`() {
        val lenient = Json { ignoreUnknownKeys = true }
        val ts = Timestamp(UUID(0L, 2L), 3L)

        // Control: an unknown `slot` key, on a reason the pre-KE3 reader knows.
        // With the setting on, the decode SUCCEEDS — so the setting is applied.
        val knownReasonWithSlot = stallPayloadOnTheWire(
            StallNotice.Stall(StallReason.SUSPENDED, ts, UUID.fromString("00000000-0000-0000-0000-0000000000aa")),
        )
        knownReasonWithSlot.jsonObject.containsKey("slot") shouldBe true
        lenient.decodeFromJsonElement(Ke339LegacyStall.serializer(), knownReasonWithSlot) shouldBe
            Ke339LegacyStall(Ke339LegacyReason.SUSPENDED, ts)

        // The measurement: same setting, no unknown key at all, and it throws
        // on the unrecognised constant.
        val frozen = stallPayloadOnTheWire(StallNotice.Stall(StallReason.STABILITY_FROZEN))
        frozen.jsonObject.containsKey("slot") shouldBe false
        val thrown = shouldThrow<kotlinx.serialization.SerializationException> {
            lenient.decodeFromJsonElement(Ke339LegacyStall.serializer(), frozen)
        }
        thrown.message.orEmpty() shouldContain "STABILITY_FROZEN"
    }

    /**
     * `coerceInputValues = true` is not the fix either, re-measured on this
     * branch: it substitutes the **declaring property's** default (or `null`,
     * for a nullable property), never anything belonging to the enum, and
     * `Stall.reason` is neither default-valued nor nullable. The control is a
     * legacy shape whose `reason` *does* carry a default: the identical bytes
     * coerce there, which is what proves the setting is live and pins WHY it
     * is inert for `Stall`.
     */
    @Test
    fun `KE3-39 - coerceInputValues cannot rescue Stall reason, which has neither a default nor a nullable type`() {
        val coercing = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val frozen = stallPayloadOnTheWire(StallNotice.Stall(StallReason.STABILITY_FROZEN))

        shouldThrow<kotlinx.serialization.SerializationException> {
            coercing.decodeFromJsonElement(Ke339LegacyStall.serializer(), frozen)
        }

        // Control: same decoder, same bytes, a `reason` that carries a default.
        coercing.decodeFromJsonElement(Ke339LegacyStallDefaultedReason.serializer(), frozen) shouldBe
            Ke339LegacyStallDefaultedReason(Ke339LegacyReason.SUSPENDED)
    }

    /**
     * The candidate mechanism — "bump [WireCodec.VERSION] when a wire-facing
     * enum gains a constant" — cannot work, and this is why: **`version`
     * never appears on the wire**. [WireFrame.version] defaults to
     * [WireCodec.VERSION] and `WireCodec.build` never sets `encodeDefaults`,
     * so kotlinx's default (`false`) omits the field from every frame this
     * codec produces. A reader therefore decodes the *absent* key as its OWN
     * default, and `decodeFrame`'s `check(frame.version == VERSION)` compares
     * `VERSION` against itself — it passes no matter what the writer's
     * `VERSION` was.
     *
     * The check is not dead code (the second half fires it), but it is
     * unreachable from any frame this encoder emits, so a `VERSION` bump is
     * invisible to a peer rather than protective. That measurement is what
     * removes the VERSION-bump discipline from the candidate list in the
     * KE3-39 decision.
     */
    @Test
    fun `KE3-39 - VERSION is omitted from every encoded frame, so a bump cannot gate a mixed-version mesh`() {
        val add = SetOps::class.java.getMethod("add", Any::class.java)
        val bytes = WireCodec.encode(frame(add, "milk"))

        // Omitted entirely: nothing on the wire carries the writer's VERSION.
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject.containsKey("version") shouldBe false
        // So the reader supplies its own, and the equality check is a tautology.
        WireCodec.decodeFrame(bytes).frame.version shouldBe WireCodec.VERSION

        // The check itself is real — it fires only for a frame that EXPLICITLY
        // carries a differing version, which no encoder in this repo produces.
        val explicitlyVersioned = JsonObject(
            mapOf("version" to JsonPrimitive(WireCodec.VERSION + 1)) +
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject,
        )
        val thrown = shouldThrow<IllegalStateException> {
            WireCodec.decode(Json.encodeToString(JsonObject.serializer(), explicitlyVersioned).toByteArray())
        }
        thrown.message.orEmpty() shouldContain "unsupported wire version"
    }

    /**
     * computenet-u5gb: the omission above is a DECISION, not an accident, and
     * this pins what `decodeFrame`'s `check(frame.version == VERSION)` is
     * therefore for. Its domain is exactly one case — a frame that
     * EXPLICITLY carries a differing `version` — so it is a guard on foreign
     * frames (hand-built, corrupted, or from a future encoder that does emit
     * the field), not a mixed-version gate between peers.
     *
     * All three cases are fixed together because the claim is about the
     * boundary, not about any one of them: absent key accepted (so two peers
     * at different `VERSION`s interoperate), explicit MATCHING version
     * accepted (so the guard does not reject a frame that merely names this
     * build's version — which is what makes "unreachable-by-design, retained
     * as a guard" coherent rather than dead code), explicit DIFFERING version
     * refused. Decided in `doc/spec/40-distribution/42-replication.md`
     * §"Wire compatibility of additive fields (KE3-39)" → "Decision: the
     * frame version stays unemitted, and the check stays a foreign-frame
     * guard".
     */
    @Test
    fun `u5gb - the version check's domain is exactly an explicitly differing version`() {
        val add = SetOps::class.java.getMethod("add", Any::class.java)
        val bytes = WireCodec.encode(frame(add, "milk"))
        val encoded = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

        fun withVersion(v: Int?): ByteArray {
            val fields = if (v == null) encoded else JsonObject(mapOf("version" to JsonPrimitive(v)) + encoded)
            return Json.encodeToString(JsonObject.serializer(), fields).toByteArray()
        }

        // 1. Absent — every frame this codec emits. Accepted, whatever the
        //    writer's VERSION was, because the key carries none of it.
        encoded.containsKey("version") shouldBe false
        WireCodec.decodeFrame(withVersion(null)).frame.version shouldBe WireCodec.VERSION

        // 2. Explicitly this build's version — accepted. The guard is not a
        //    blanket refusal of any frame that names a version.
        WireCodec.decodeFrame(withVersion(WireCodec.VERSION)).frame.version shouldBe WireCodec.VERSION

        // 3. Explicitly differing — the one case that is refused, in either
        //    direction (a "newer" and an "older" foreign frame alike).
        for (foreign in listOf(WireCodec.VERSION + 1, WireCodec.VERSION - 1)) {
            val thrown = shouldThrow<IllegalStateException> { WireCodec.decode(withVersion(foreign)) }
            thrown.message.orEmpty() shouldContain "unsupported wire version $foreign"
        }
    }
}

/**
 * Stand-in for [StallReason] as it stood at `ea84150f5`, one commit before
 * `STABILITY_FROZEN` was added (see `StallNoticeWireCompatTest`'s KDoc for
 * the capture provenance). Top-level because Kotlin has no local `enum class`;
 * named distinctly from that file's equivalent so the two private
 * declarations in this package never read as the same type.
 */
@kotlinx.serialization.Serializable
private enum class Ke339LegacyReason { SUSPENDED, RESTARTING, DEAD_LETTERED }

/**
 * Pre-KE3 `StallNotice.Stall` shape: `reason` is neither nullable nor
 * default-valued, which is exactly the property that makes
 * `coerceInputValues` inert for it.
 */
@kotlinx.serialization.Serializable
private data class Ke339LegacyStall(val reason: Ke339LegacyReason, val timestamp: Timestamp? = null)

/**
 * The `coerceInputValues` control: identical but for a DEFAULT-VALUED
 * `reason`, the one shape that setting can actually rescue.
 */
@kotlinx.serialization.Serializable
private data class Ke339LegacyStallDefaultedReason(
    val reason: Ke339LegacyReason = Ke339LegacyReason.SUSPENDED,
    val timestamp: Timestamp? = null,
)
