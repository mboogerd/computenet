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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
}
