package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.replication.Stamped
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-f7h.3.1, decision f7h.3-D1: `Stamped.baseline` is **additive on
 * the wire**. A `Stamped` encoded before the field existed still decodes on
 * today's codec (with `baseline == false`), and encoding a `baseline == false`
 * `Stamped` today reproduces that same pre-change encoding byte-for-byte —
 * because `WireCodec`'s `Json` never sets `encodeDefaults`, so a
 * default-valued field costs zero bytes. `WireCodec.VERSION` is unchanged
 * (still 2) and `WireCodec.kt` is not touched by this change: `Stamped`'s
 * `polymorphic(Any)` registration is by `Stamped.serializer(polyAny)` and does
 * not name the field list.
 *
 * ## Golden bytes
 *
 * Captured at `a3493440b` — this task branch's own base commit, which is
 * pre-change by construction: the fixtures were committed before
 * `Stamped` was edited (commit `794e31ad5`, "pin pre-change Stamped wire
 * bytes as golden fixtures"). At `a3493440b`, `Stamped` was
 * `data class Stamped<D>(val epoch: Long, val delta: D)` — no `baseline`
 * field at all.
 *
 * That sha is tied to the epic's base commit `c491bd6a1` by an emptiness
 * check run at capture time:
 *
 * ```
 * git diff c491bd6a1 a3493440b -- \
 *   kernel/src/main/kotlin/civictech/cell/replication/SingleWriterReplication.kt \
 *   | grep Stamped        # -> no output (grep exit 1)
 * ```
 *
 * `Stamped` is byte-identical across that range (the file's 47/25 line churn
 * is F1's `LeaderMark` typealias relocation), so a capture at `a3493440b`
 * IS a capture at the epic base commit.
 *
 * Reproduction: a throwaway JUnit test in this same package, using the
 * `frame(...)` construction below verbatim (the same fixed
 * `CellRef 00000000-0000-0000-0000-000000000042`, the same `inlet` port name,
 * the same `Propagate::propagate` method — so `contractId`/`methodId` match),
 * encoding `Stamped(3L, 42L)` and
 * `Stamped(0L, SetDelta(adds = mapOf("a" to emptySet<Timestamp>())))` through
 * `WireCodec.encode` and writing the bytes to `kernel/build/stamped-fixtures/`.
 * Run with `./gradlew :kernel:test --tests '<throwaway>' --rerun`; the two
 * outputs were copied to `kernel/src/test/resources/civictech/cell/wire/` and
 * the throwaway deleted. It contributed no commit. This is
 * `wire/src/test/kotlin/civictech/wire/PreKe3WireFixtureTest.kt`'s recipe with `:kernel`'s own
 * checked-in-`.bin` convention (as
 * `kernel/src/test/resources/civictech/cell/durability/prechange-journal.bin`
 * already uses).
 */
class StampedWireCompatTest {

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

    private fun golden(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/civictech/cell/wire/$name")) {
            "missing checked-in fixture $name"
        }.use { it.readBytes() }

    private fun decodeStamped(bytes: ByteArray): Stamped<*> =
        WireCodec.decode(bytes).invocation.args.single() as Stamped<*>

    private val goldenLong = golden("stamped-pre-f7h3-long.bin")
    private val goldenSetDelta = golden("stamped-pre-f7h3-setdelta.bin")

    private val setDeltaPayload = SetDelta(adds = mapOf("a" to emptySet<Timestamp>()))

    @Test
    fun `a pre-baseline Stamped decodes on today's codec with baseline false`() {
        val decodedLong = decodeStamped(goldenLong)
        decodedLong shouldBe Stamped(3L, 42L)
        decodedLong.epoch shouldBe 3L
        decodedLong.delta shouldBe 42L
        decodedLong.baseline shouldBe false

        val decodedSetDelta = decodeStamped(goldenSetDelta)
        decodedSetDelta shouldBe Stamped(0L, setDeltaPayload)
        decodedSetDelta.baseline shouldBe false
    }

    @Test
    fun `a false baseline adds zero bytes - encoding today reproduces the pre-change golden byte-for-byte`() {
        WireCodec.encode(frame(Stamped(3L, 42L, baseline = false))).decodeToString() shouldBe
            goldenLong.decodeToString()
        WireCodec.encode(frame(Stamped(0L, setDeltaPayload, baseline = false))).decodeToString() shouldBe
            goldenSetDelta.decodeToString()

        // and the two-argument form — every pre-existing call site — is the
        // same frame, since `baseline` defaults to false
        WireCodec.encode(frame(Stamped(3L, 42L))).decodeToString() shouldBe goldenLong.decodeToString()
    }

    @Test
    fun `a true baseline round-trips and is distinguishable from the golden`() {
        val encoded = WireCodec.encode(frame(Stamped(3L, 42L, baseline = true)))
        encoded.decodeToString() shouldNotBe goldenLong.decodeToString()

        val decoded = decodeStamped(encoded)
        decoded shouldBe Stamped(3L, 42L, baseline = true)
        decoded.baseline shouldBe true
        decoded.epoch shouldBe 3L
        decoded.delta shouldBe 42L
    }

    @Test
    fun `the wire version is unchanged by this additive field`() {
        WireCodec.VERSION shouldBe 2
    }
}
