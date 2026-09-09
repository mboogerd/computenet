package civictech.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [KE3-39] (epic BS-19, feature computenet-9sm.9 clause [KE3-39], decision
 * 9sm.9-D2): four byte fixtures captured at the epic base commit `053540fd8`
 * — two [WatermarkDelta] shapes and the two [StallNotice.Stall] shapes
 * [civictech.cell.wire.StallNoticeWireCompatTest] already pins in `:kernel`
 * — still decode on today's [WireCodec] and, for the fields present at that
 * commit, re-encode byte-identically.
 *
 * This test does **not** restate the Stall half's reasoning:
 * `StallNoticeWireCompatTest` (`:kernel`) already holds the golden literals,
 * the forward-direction round-trip, the reverse-direction (pre-KE3 reader of
 * KE3 bytes) hazard, and the unknown-enum-constant hazard — see its KDoc.
 * The two `stall-notice-pre-ke3-*.bin` fixtures here are byte-identical to
 * that test's `goldenSuspended`/`goldenDeadLettered` literals (verified: same
 * `contractId`/`methodId`/`cellRef`/`portName` because both use the same
 * `frame(...)` construction), so this test corroborates that pin from a
 * checked-in file rather than an inline string literal — it does not add new
 * evidence for the Stall half.
 *
 * `WatermarkDelta` did not change between `053540fd8` and this branch's base
 * (`git diff 053540fd8..HEAD --stat -- kernel/src/main/kotlin/civictech/cell/data/delta/WatermarkDelta.kt`
 * is empty), so the WatermarkDelta half of this test is a regression pin over
 * an unchanged type: it fails on any future change to `WatermarkDelta`'s
 * field set, field names, or defaults, which is the conditional it exists to
 * catch — not evidence that a change has already happened.
 *
 * ## Capture
 *
 * Fixtures were captured, not hand-authored (9sm.9-D2), by a throwaway JUnit
 * test placed at
 * `kernel/src/test/kotlin/civictech/cell/wire/PreKe3FixtureCaptureThrowaway.kt`
 * in a disposable detached worktree at the epic base commit, using the exact
 * `frame(...)` construction [civictech.cell.wire.StallNoticeWireCompatTest]
 * uses (same fixed `CellRef` `00000000-0000-0000-0000-000000000042`, port
 * `inlet`, `Propagate::propagate`):
 *
 * ```
 * git worktree add --detach <scratch-dir> 053540fd8
 * # (throwaway test placed under <scratch-dir>/kernel/src/test/kotlin/civictech/cell/wire/)
 * ./gradlew :kernel:test --tests 'civictech.cell.wire.PreKe3FixtureCaptureThrowaway' --rerun
 * # bytes written under <scratch-dir>/kernel/build/pre-ke3-fixtures/ (one .bin per payload)
 * git worktree remove --force <scratch-dir>
 * ```
 *
 * The captured `WatermarkDelta` fixtures use fixed literal UUIDs, reproduced
 * here so the documented values are traceable to source:
 * - `slotA = 00000000-0000-0000-0000-0000000000a1`
 * - `slotB = 00000000-0000-0000-0000-0000000000a2`
 * - `srcX  = 00000000-0000-0000-0000-0000000000b1`
 * - `srcY  = 00000000-0000-0000-0000-0000000000b2`
 */
class PreKe3WireFixtureTest {

    private val fixedCellRef = CellRef(UUID.fromString("00000000-0000-0000-0000-000000000042"))

    private val slotA = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val slotB = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    private val srcX = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val srcY = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    private fun frame(vararg args: Any?): HostedPortInvocation {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        return HostedPortInvocation(
            cellRef = fixedCellRef,
            portName = "inlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(propagate, args, null),
        )
    }

    private fun encode(payload: Any?): String = WireCodec.encode(frame(payload)).decodeToString()

    private fun decodePayload(bytes: ByteArray): Any? =
        WireCodec.decode(bytes).invocation.args.single()

    private fun loadFixture(name: String): ByteArray {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) {
            "fixture not found on classpath: fixtures/$name"
        }
        return stream.use { it.readBytes() }
    }

    private val watermarkFull = WatermarkDelta(
        rows = mapOf(
            slotA to mapOf(srcX to 3L, srcY to 1L),
            slotB to mapOf(srcX to 2L),
        ),
        closed = setOf(slotB),
        suspended = mapOf(slotA to 1L),
        members = setOf(slotA, slotB),
    )

    private val watermarkMinimal = WatermarkDelta(rows = mapOf(slotA to mapOf(srcX to 1L)))

    private val stallSuspended = StallNotice.Stall(StallReason.SUSPENDED)

    private val stallDeadLettered = StallNotice.Stall(StallReason.DEAD_LETTERED, Timestamp(UUID(0L, 1L), 7L))

    @Test
    fun `pre-KE3 WatermarkDelta full fixture decodes to the documented value and re-encodes byte-identically`() {
        val bytes = loadFixture("watermark-delta-pre-ke3-full.bin")
        decodePayload(bytes) shouldBe watermarkFull
        encode(watermarkFull).toByteArray() shouldBe bytes
    }

    @Test
    fun `pre-KE3 WatermarkDelta minimal fixture (all-defaulted fields) decodes and re-encodes byte-identically`() {
        val bytes = loadFixture("watermark-delta-pre-ke3-minimal.bin")
        decodePayload(bytes) shouldBe watermarkMinimal
        encode(watermarkMinimal).toByteArray() shouldBe bytes
    }

    @Test
    fun `pre-KE3 Stall SUSPENDED fixture decodes with slot null and re-encodes byte-identically`() {
        val bytes = loadFixture("stall-notice-pre-ke3-suspended.bin")
        val decoded = decodePayload(bytes) as StallNotice.Stall
        decoded shouldBe stallSuspended
        decoded.slot shouldBe null
        encode(stallSuspended).toByteArray() shouldBe bytes
    }

    @Test
    fun `pre-KE3 Stall DEAD_LETTERED fixture decodes with slot null and re-encodes byte-identically`() {
        val bytes = loadFixture("stall-notice-pre-ke3-dead-lettered.bin")
        val decoded = decodePayload(bytes) as StallNotice.Stall
        decoded shouldBe stallDeadLettered
        decoded.slot shouldBe null
        encode(stallDeadLettered).toByteArray() shouldBe bytes
    }
}
