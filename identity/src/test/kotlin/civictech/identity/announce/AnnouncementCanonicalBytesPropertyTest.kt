package civictech.identity.announce

import civictech.cell.CellRef
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **BS-17** — the seeded property test for the canonical announcement encoding
 * ([DSC1-ANN-03]).
 *
 * Three properties, over inputs drawn from a fixed seed so a failure is
 * reproducible by re-running the test rather than by re-rolling the dice:
 *
 * 1. **Determinism** — encoding the same input twice is byte-identical.
 * 2. **Round-trip stability** — encoding an input that has been through a Java
 *    serialize/deserialize cycle gives the same bytes. This is the property
 *    that catches an encoding leaking identity hashes, iteration order, or any
 *    other per-process incident into the signed region.
 * 3. **Injectivity** — distinct inputs give distinct bytes, checked both
 *    pairwise across the whole sample and by single-field mutation, which is
 *    the sharper of the two: random samples differ in many fields at once and
 *    so would still look injective under an encoding that ignored a field
 *    entirely.
 *
 * Injectivity is the security property. Two announcements sharing an encoding
 * share a signature, so a signature honestly minted for one verifies the other
 * — which is a forgery with no broken crypto anywhere in it.
 */
class AnnouncementCanonicalBytesPropertyTest {

    companion object {
        /**
         * The seed. **Keep it.** If a run ever discovers an input that violates
         * one of these properties, the seed that found it is evidence and gets
         * pinned here — never replaced with a friendlier one that happens to
         * pass (repo rule; AGENTS.md "Preserve deterministic simulation tests").
         */
        private const val SEED: Long = 0x5A17_C0DE_17L

        private const val SAMPLES: Int = 400
    }

    @Test
    fun `encoding is deterministic and survives a serialize-deserialize round trip`() {
        val rnd = Random(SEED)
        repeat(SAMPLES) {
            val input = randomInput(rnd)
            val once = canonicalBytes(input)

            assertEquals(once.toHex(), canonicalBytes(input).toHex(), "not deterministic for $input")

            val revived = javaRoundTrip(input)
            assertEquals(input, revived, "round trip changed the input")
            assertEquals(once.toHex(), canonicalBytes(revived).toHex(), "round trip changed the bytes for $input")
        }
    }

    @Test
    fun `distinct inputs encode to distinct bytes, pairwise across the sample`() {
        val rnd = Random(SEED)
        val byInput = LinkedHashMap<AnnouncementSigningInput, String>()
        repeat(SAMPLES) {
            val input = randomInput(rnd)
            byInput[input] = canonicalBytes(input).toHex()
        }

        // Injectivity, stated as counting: as many distinct encodings as distinct
        // inputs. Any collision collapses the second count below the first, and
        // the message names the colliding pair.
        val byBytes = LinkedHashMap<String, AnnouncementSigningInput>()
        for ((input, hex) in byInput) {
            val previous = byBytes.put(hex, input)
            assertEquals(null, previous, "collision: $previous and $input both encode to $hex")
        }
        assertEquals(byInput.size, byBytes.size)
        assertTrue(byInput.size > SAMPLES / 2, "sample degenerated to ${byInput.size} distinct inputs")
    }

    @Test
    fun `every single-field mutation changes the bytes`() {
        val rnd = Random(SEED)
        var swaps = 0
        var cellToggles = 0

        repeat(SAMPLES) {
            val input = structuredInput(rnd)
            val base = canonicalBytes(input).toHex()

            fun assertDiffers(what: String, mutated: AnnouncementSigningInput) {
                assertNotEquals(input, mutated, "$what did not actually mutate the input")
                assertNotEquals(base, canonicalBytes(mutated).toHex(), "$what left the bytes unchanged: $input")
            }

            assertDiffers("bumping the counter", input.copy(counter = input.counter + 1))
            assertDiffers("bumping notAfter", input.copy(notAfter = input.notAfter + 1))
            assertDiffers("bumping the contractId", input.copy(contractId = input.contractId + 1))
            assertDiffers("bumping the methodId", input.copy(methodId = input.methodId + 1))
            assertDiffers("renaming the minting peer", input.copy(mintingPeerId = PeerId(input.mintingPeerId.name + "x")))
            assertDiffers("renaming the port", input.copy(portName = input.portName + "x"))
            assertDiffers("bumping the cellRef instanceId", input.copy(cellRef = input.cellRef.copy(instanceId = input.cellRef.instanceId + 1)))
            assertDiffers("flipping a bit of the cellRef UUID", input.copy(cellRef = input.cellRef.copy(id = input.cellRef.id.flipLowBit())))
            assertDiffers("dropping the last argument", input.copy(args = input.args.dropLast(1)))

            // Swapping two distinct arguments: same multiset, different order.
            // An order-insensitive encoding (a set, a sum, a sorted digest)
            // would pass everything above and fail exactly here.
            val i = rnd.nextInt(input.args.size)
            val j = rnd.nextInt(input.args.size)
            if (i != j && input.args[i] != input.args[j]) {
                val swapped = input.args.toMutableList()
                swapped[i] = input.args[j]
                swapped[j] = input.args[i]
                assertDiffers("swapping args $i and $j", input.copy(args = swapped))
                swaps++
            }

            // Toggling PortRef.cell null-ness on the first TopologyLink argument:
            // the presence marker is what stops an absent cell colliding with
            // whatever bytes would otherwise follow.
            val linkIndex = input.args.indexOfFirst { it is TopologyLink }
            if (linkIndex >= 0) {
                val link = input.args[linkIndex] as TopologyLink
                val toggled = link.copy(from = link.from.copy(cell = if (link.from.cell == null) SOME_CELL else null))
                val args = input.args.toMutableList().also { it[linkIndex] = toggled }
                assertDiffers("toggling PortRef.cell null-ness in arg $linkIndex", input.copy(args = args))
                cellToggles++
            }
        }

        assertTrue(swaps > SAMPLES / 4, "argument swap barely exercised ($swaps)")
        assertTrue(cellToggles > SAMPLES / 4, "PortRef.cell toggle barely exercised ($cellToggles)")
    }

    @Test
    fun `the generated sample actually covers all three argument shapes and both cell states`() {
        val rnd = Random(SEED)
        var cellRefs = 0
        var links = 0
        var uuids = 0
        var presentCells = 0
        var absentCells = 0

        repeat(SAMPLES) {
            for (arg in randomInput(rnd).args) when (arg) {
                is CellRef -> cellRefs++
                is UUID -> uuids++
                is TopologyLink -> {
                    links++
                    for (port in listOf(arg.from, arg.to)) if (port.cell == null) absentCells++ else presentCells++
                }
            }
        }

        // A generator that quietly stopped emitting a shape would leave the
        // properties above vacuous for it; this is the guard against that.
        assertTrue(cellRefs > 0 && links > 0 && uuids > 0, "shapes: $cellRefs/$links/$uuids")
        assertTrue(presentCells > 0 && absentCells > 0, "cell states: $presentCells present, $absentCells absent")
    }

    // --- generators -------------------------------------------------------

    // Note: these pools hold only well-formed UTF-16. Strings containing an
    // UNPAIRED surrogate do collide under this encoding (canonicalBytes KDoc,
    // computenet-9qgg); adding one here without first settling that decision
    // would make the injectivity property below fail rather than assert it.
    private val portNames = listOf("", "orders", "orders/über", "a".repeat(64), "ports.in", "🔑")
    private val peerNames = listOf("ed25519:aaa", "ed25519:aab", "peer-1", "", "ünïcode-peer")

    private fun randomInput(rnd: Random): AnnouncementSigningInput = AnnouncementSigningInput(
        mintingPeerId = PeerId(peerNames[rnd.nextInt(peerNames.size)]),
        counter = rnd.nextLong(),
        notAfter = rnd.nextLong(),
        contractId = rnd.nextLong(),
        methodId = rnd.nextLong(),
        cellRef = randomCellRef(rnd),
        portName = portNames[rnd.nextInt(portNames.size)],
        args = (0 until rnd.nextInt(5)).map { randomArg(rnd) },
    )

    /** Like [randomInput] but guaranteed to have at least two args, one a [TopologyLink]. */
    private fun structuredInput(rnd: Random): AnnouncementSigningInput {
        val args = mutableListOf<Any?>(randomLink(rnd), randomArg(rnd))
        repeat(rnd.nextInt(3)) { args.add(randomArg(rnd)) }
        java.util.Collections.shuffle(args, rnd)
        return randomInput(rnd).copy(args = args)
    }

    private fun randomArg(rnd: Random): Any = when (rnd.nextInt(3)) {
        0 -> randomCellRef(rnd)
        1 -> randomLink(rnd)
        else -> randomUuid(rnd)
    }

    private fun randomLink(rnd: Random) = TopologyLink(randomUuid(rnd), randomPort(rnd), randomPort(rnd))

    private fun randomPort(rnd: Random) =
        PortRef(randomUuid(rnd), if (rnd.nextBoolean()) randomCellRef(rnd) else null)

    /** Small instanceId pool so refs can collide on it — collisions must still not collide in bytes. */
    private fun randomCellRef(rnd: Random) = CellRef(randomUuid(rnd), rnd.nextInt(3).toLong() - 1L)

    private fun randomUuid(rnd: Random) = UUID(rnd.nextLong(), rnd.nextLong())
}

private val SOME_CELL = CellRef(UUID.fromString("00000000-0000-4000-8000-000000000099"), 3L)

private fun UUID.flipLowBit() = UUID(mostSignificantBits, leastSignificantBits xor 1L)

private fun <T : java.io.Serializable> javaRoundTrip(value: T): T {
    val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(value) } }.toByteArray()
    @Suppress("UNCHECKED_CAST")
    return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as T
}
