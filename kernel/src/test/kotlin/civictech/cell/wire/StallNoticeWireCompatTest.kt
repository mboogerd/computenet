package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [KE3-39] (epic BS-19's Stall half, decision 9sm.5-D5): a `Stall`/`Resume`
 * encoded before `StallNotice.Stall.slot` existed still decodes on the
 * current codec, and encoding a `slot == null` `Stall` today reproduces that
 * same pre-KE3 encoding byte-for-byte — the "additive on the wire" claim in
 * [StallNotice.Stall.slot]'s KDoc, pinned rather than merely asserted.
 *
 * ## Golden bytes
 *
 * Captured at `ea84150f5` (the commit `570a704a9` — the predecessor task
 * that added `slot`/`STABILITY_FROZEN` — forked from; this branch's
 * `git merge-base feature/computenet-9sm.5 HEAD` lands one merge further, at
 * `4ab711b0d`, which already contains `570a704a9`). At `ea84150f5`,
 * `StallNotice.Stall` was `data class Stall(reason: StallReason, timestamp:
 * Timestamp? = null)` — no `slot` field at all, so its wire encoding is by
 * construction the pre-KE3 shape.
 *
 * Reproduction: a disposable `git worktree add --detach <dir> ea84150f5`,
 * with a throwaway JUnit test in that worktree's `:kernel` module encoding
 * `Stall(SUSPENDED)`, `Stall(DEAD_LETTERED, Timestamp(UUID(0L, 1L), 7L))` and
 * `Resume` through the exact `frame`/`WireCodec.encode` path this test uses
 * below (same `CellRef`, same `Propagate::propagate` method, so `contractId`/
 * `methodId` match), run via
 * `./gradlew :kernel:test --tests '<throwaway>' --rerun`, printing the
 * result. The worktree was removed after capture; it contributed no commit.
 */

/**
 * Stand-in for [StallReason] at `ea84150f5` (pre-KE3): exactly `SUSPENDED` /
 * `RESTARTING` / `DEAD_LETTERED`, missing `STABILITY_FROZEN`. Used only by
 * the "unknown enum constant" test below — kept top-level because Kotlin
 * does not allow a local `enum class`.
 */
@Serializable
private enum class LegacyStallReason { SUSPENDED, RESTARTING, DEAD_LETTERED }

/** Stand-in for pre-KE3 `StallNotice.Stall`, paired with [LegacyStallReason]. */
@Serializable
private data class LegacyStallWithLegacyReason(val reason: LegacyStallReason, val timestamp: Timestamp? = null)

class StallNoticeWireCompatTest {

    private val fixedCellRef = CellRef(UUID.fromString("00000000-0000-0000-0000-000000000042"))

    private fun frame(vararg args: Any?): HostedPortInvocation {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        return HostedPortInvocation(
            cellRef = fixedCellRef,
            portName = "inlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(propagate, args, null),
        )
    }

    private fun encodeStall(notice: StallNotice): String = WireCodec.encode(frame(notice)).decodeToString()

    private fun decodeStall(json: String): StallNotice =
        WireCodec.decode(json.toByteArray()).invocation.args.single() as StallNotice

    // -- golden literals, captured at ea84150f5 per the class KDoc --

    private val goldenSuspended =
        """{"contractId":-996426215734216040,"methodId":-134175827537617903,"cellRef":{"id":"00000000-0000-0000-0000-000000000042"},"portName":"inlet","type":"PORT_API","args":[["Stall",{"reason":"SUSPENDED"}]]}"""

    private val goldenDeadLettered =
        """{"contractId":-996426215734216040,"methodId":-134175827537617903,"cellRef":{"id":"00000000-0000-0000-0000-000000000042"},"portName":"inlet","type":"PORT_API","args":[["Stall",{"reason":"DEAD_LETTERED","timestamp":{"sourceId":"00000000-0000-0000-0000-000000000001","counter":7}}]]}"""

    private val goldenResume =
        """{"contractId":-996426215734216040,"methodId":-134175827537617903,"cellRef":{"id":"00000000-0000-0000-0000-000000000042"},"portName":"inlet","type":"PORT_API","args":[["Resume",{}]]}"""

    @Test
    fun `pre-KE3 Stall and Resume encodings decode unchanged with slot null`() {
        val decodedSuspended = decodeStall(goldenSuspended) as StallNotice.Stall
        decodedSuspended shouldBe StallNotice.Stall(StallReason.SUSPENDED)
        decodedSuspended.slot shouldBe null

        val decodedDeadLettered = decodeStall(goldenDeadLettered) as StallNotice.Stall
        decodedDeadLettered shouldBe StallNotice.Stall(StallReason.DEAD_LETTERED, Timestamp(UUID(0L, 1L), 7L))
        decodedDeadLettered.slot shouldBe null

        val decodedResume = decodeStall(goldenResume)
        decodedResume shouldBe StallNotice.Resume
    }

    @Test
    fun `a null slot adds zero bytes - encoding today reproduces the pre-KE3 golden byte-for-byte`() {
        encodeStall(StallNotice.Stall(StallReason.SUSPENDED)) shouldBe goldenSuspended
        encodeStall(StallNotice.Stall(StallReason.DEAD_LETTERED, Timestamp(UUID(0L, 1L), 7L))) shouldBe
            goldenDeadLettered
        encodeStall(StallNotice.Resume) shouldBe goldenResume
    }

    @Test
    fun `STABILITY_FROZEN with a slot round-trips with slot intact`() {
        val slot = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val original = StallNotice.Stall(
            reason = StallReason.STABILITY_FROZEN,
            timestamp = Timestamp(UUID(0L, 2L), 3L),
            slot = slot,
        )

        val encoded = encodeStall(original)
        val decoded = decodeStall(encoded) as StallNotice.Stall

        decoded shouldBe original
        decoded.slot shouldBe slot
    }

    /**
     * `unverified:` reverse direction — an old peer's `Stall` (pre-KE3 shape,
     * no `slot` field) decoding a frame a KE3 peer wrote with `slot` set.
     * This repo cannot literally run the old JVM class, so the probe
     * reconstructs that shape locally (`LegacyStall`, structurally identical
     * to `Stall` at `ea84150f5`) and decodes an otherwise-valid `Stall`
     * fragment that carries a `slot` key into it, using a plain `Json` at
     * its default `ignoreUnknownKeys = false` — the same setting
     * `WireCodec.build` uses (it sets `allowStructuredMapKeys` and
     * `useArrayPolymorphism` but never touches `ignoreUnknownKeys`, so it
     * stands on the kotlinx default).
     *
     * Result, recorded rather than assumed: it **throws**. `slot` is
     * additive for a KE3 reader decoding a pre-KE3 writer's bytes (asserted
     * above), but **not** safely additive in the other direction — a pre-KE3
     * reader receiving a KE3 writer's `slot` field fails closed with a
     * [SerializationException] rather than silently ignoring the field it
     * doesn't know. [KE3-39] only promises the forward direction (a pre-KE3
     * peer's bytes are read), so this is not a violation of it — but it is a
     * real constraint on any future mixed-version rollout this test makes
     * visible rather than leaving implicit.
     */
    @Test
    fun `unverified - an old peer's Stall shape rejects a spliced-in slot field, unknown keys are not ignored`() {
        @Serializable
        data class LegacyStall(val reason: StallReason, val timestamp: Timestamp? = null)

        val legacyJson = Json // default: ignoreUnknownKeys = false, same as WireCodec.build's un-set default
        val staleWithSlotSpliced =
            """{"reason":"STABILITY_FROZEN","slot":"00000000-0000-0000-0000-0000000000aa"}"""

        shouldThrow<SerializationException> {
            legacyJson.decodeFromString(LegacyStall.serializer(), staleWithSlotSpliced)
        }
    }

    /**
     * Pins the constraint recorded in `WireCodec.build`'s KDoc and in
     * `doc/spec/40-distribution/42-replication.md` §"Wire compatibility of
     * additive fields (KE3-39)": **a mixed-version mesh must not populate a
     * newly-added optional field until every peer has upgraded**, because
     * doing so breaks the older peer's decode of the whole payload, not just
     * the new field.
     *
     * Unlike the `unverified` test above — which hand-splices a `slot` key
     * into a JSON literal to demonstrate the mechanism — this one drives the
     * real [WireCodec.encode] path: it encodes an actual
     * `STABILITY_FROZEN` `Stall` with `slot` set, exactly as a KE3-upgraded
     * peer would emit it onto the wire, then reconstructs only the fragment a
     * pre-KE3 peer's `Stall` payload class would see (the `["Stall", {...}]`
     * args entry's object half) and decodes that fragment with the pre-KE3
     * shape. The failure is therefore reproduced from real wire bytes this
     * codec actually produces, not asserted from kotlinx.serialization's
     * documented default.
     */
    @Test
    fun `KE3-39 pinned - a real KE3 frame with slot set breaks a pre-KE3 reader's decode`() {
        @Serializable
        data class LegacyStall(val reason: StallReason, val timestamp: Timestamp? = null)

        val slot = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val upgradedPeerFrame = encodeStall(
            StallNotice.Stall(
                reason = StallReason.STABILITY_FROZEN,
                timestamp = Timestamp(UUID(0L, 2L), 3L),
                slot = slot,
            ),
        )

        // The pre-KE3 peer's Stall payload object, as it appears inside the
        // real frame's ["Stall", {...}] args entry.
        val stallPayload = Json.parseToJsonElement(upgradedPeerFrame)
            .jsonObject["args"]!!.jsonArray[0].jsonArray[1]

        shouldThrow<SerializationException> {
            Json.decodeFromJsonElement(LegacyStall.serializer(), stallPayload)
        }
    }

    /**
     * Second, distinct mixed-version hazard (computenet-b2i0, filed from this
     * item's review of KE3-39): an unknown ENUM CONSTANT, independent of any
     * unknown KEY. `STABILITY_FROZEN` was added to [StallReason] by the same
     * predecessor task (`570a704a9`) that added `slot` — at `ea84150f5`,
     * `StallReason` held exactly `SUSPENDED` / `RESTARTING` / `DEAD_LETTERED`.
     * So a genuinely pre-KE3 peer throws on `"reason":"STABILITY_FROZEN"`
     * before it ever reaches the `slot` key — and it does so even when `slot`
     * is `null` and therefore *absent* from the encoded object entirely,
     * which is what isolates this from the `slot`-key hazard pinned above.
     *
     * The tests above all reconstruct their "pre-KE3" reader with TODAY's
     * [StallReason], which already contains `STABILITY_FROZEN` — a fair
     * bound on what they pin (the additive-*field* claim), but it means none
     * of them exercises the enum half at all. This test uses a
     * `LegacyStallReason` missing `STABILITY_FROZEN`, standing in for the
     * pre-KE3 enum shape, so the hazard is actually exercised.
     *
     * Two cases, both driven through the real [WireCodec.encode] path (never
     * a hand-spliced JSON literal):
     *
     * 1. Encoding `Stall(STABILITY_FROZEN)` (no `slot`) and decoding the
     *    lifted payload fragment against the legacy shape throws — and the
     *    control below rules out every other explanation Gradle's own
     *    `mutation-check` guidance warns a green run can silently be
     *    passing for (a missing `slot` key, a missing serializers module
     *    entry, the `Timestamp` shape): the exception message names the
     *    unrecognised enum value, not a missing/unknown key.
     * 2. The **control**: the same real encode/decode path with a pre-KE3
     *    reason (`SUSPENDED`) — same method, same payload shape, same
     *    legacy reader — decodes cleanly. That rules out the legacy reader
     *    itself being broken (wrong serializers module, wrong `Timestamp`
     *    shape, etc.): only the unrecognised constant fails, not the
     *    plumbing around it.
     */
    @Test
    fun `unknown enum constant hazard - a pre-KE3 reader rejects STABILITY_FROZEN even with slot absent, but decodes a pre-KE3 reason cleanly`() {

        fun legacyPayloadFragment(notice: StallNotice): kotlinx.serialization.json.JsonElement {
            val encoded = encodeStall(notice)
            return Json.parseToJsonElement(encoded).jsonObject["args"]!!.jsonArray[0].jsonArray[1]
        }

        // Case 1: STABILITY_FROZEN, slot left null/absent — isolates the enum
        // hazard from the slot-key hazard pinned in the test above.
        val frozenPayload = legacyPayloadFragment(StallNotice.Stall(StallReason.STABILITY_FROZEN))
        frozenPayload.jsonObject.containsKey("slot") shouldBe false

        val thrown = shouldThrow<SerializationException> {
            Json.decodeFromJsonElement(LegacyStallWithLegacyReason.serializer(), frozenPayload)
        }
        // The message names the unrecognised enum value, not a key — proof
        // this is the enum hazard and not a mis-set-up control.
        thrown.message.orEmpty() shouldContainIgnoringCase "STABILITY_FROZEN"

        // Case 2 (control): a pre-KE3 reason, same real encode/decode path,
        // same legacy reader — decodes cleanly. If this failed too, the test
        // would be pinning something about the legacy reader's plumbing
        // (serializers module, Timestamp shape) rather than the enum
        // constant, and would prove nothing about the hazard.
        val suspendedPayload = legacyPayloadFragment(StallNotice.Stall(StallReason.SUSPENDED))
        val decodedControl = Json.decodeFromJsonElement(LegacyStallWithLegacyReason.serializer(), suspendedPayload)
        decodedControl shouldBe LegacyStallWithLegacyReason(LegacyStallReason.SUSPENDED)
    }
}
