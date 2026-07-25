package civictech.cell.replication

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.NotSerializableException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * PN-3a (plan §2 F6, spec 42 §Interest-scoped instance sets): the interest
 * algebra closes. Every combinator is a `data class`/`object` with a structural,
 * **symmetric** [Interest.overlaps] and a Java-serialization round-trip to an
 * `equals` value — the two properties the old anonymous `object : Interest`
 * combinators (blanket `overlaps = true`, a captured non-serializable lambda)
 * could not provide.
 */
class InterestAlgebraTest {

    private val slots = Interest.Slots(setOf(0, 1), totalSlots = 4)
    private val slotsDisjoint = Interest.Slots(setOf(2, 3), totalSlots = 4)
    private val slotsShared = Interest.Slots(setOf(1, 2), totalSlots = 4)
    private val ranges = Interest.Ranges(listOf(Interest.Ranges.Range(0, 10)))
    private val rangesDisjoint = Interest.Ranges(listOf(Interest.Ranges.Range(10, 20)))
    private val rangesShared = Interest.Ranges(listOf(Interest.Ranges.Range(5, 15)))

    /** Every arm of the algebra — one representative value each. */
    private val arms: List<Interest> = listOf(
        Interest.Empty,
        Interest.Total,
        slots,
        slotsDisjoint,
        ranges,
        rangesDisjoint,
        Interest.Union(listOf(slots, ranges)),
        Interest.Intersect(listOf(slots, slotsShared)),
        Interest.Complement(slots),
    )

    @Test
    fun `overlaps is symmetric across every pair of arms`() {
        for (a in arms) for (b in arms) {
            a.overlaps(b) shouldBe b.overlaps(a)
            // the free decision the arms delegate to is itself symmetric
            Interest.overlap(a, b) shouldBe Interest.overlap(b, a)
        }
    }

    @Test
    fun `overlaps is honest — provably-disjoint pairs return false`() {
        // Empty overlaps nothing (and nothing overlaps Empty)
        for (a in arms) a.overlaps(Interest.Empty) shouldBe false
        // disjoint slot sets / disjoint ranges do not overlap
        slots.overlaps(slotsDisjoint) shouldBe false
        ranges.overlaps(rangesDisjoint) shouldBe false
        // ... but a genuine shared slot / shared range does
        slots.overlaps(slotsShared) shouldBe true
        ranges.overlaps(rangesShared) shouldBe true
    }

    @Test
    fun `Union Intersect Complement — algebraic overlaps distributes over members`() {
        // Union overlaps iff SOME member overlaps
        Interest.Union(listOf(slotsDisjoint, ranges)).overlaps(slots) shouldBe true // via ranges? no — via nothing; check both members vs slots
        Interest.Union(listOf(slotsDisjoint, slots)).overlaps(slots) shouldBe true  // via the slots member
        Interest.Union(listOf(slotsDisjoint)).overlaps(slots) shouldBe false        // no member overlaps
        // Intersect overlaps iff EVERY member overlaps (conservative but distributed)
        Interest.Intersect(listOf(slots, slotsShared)).overlaps(slotsShared) shouldBe true
        Interest.Intersect(listOf(slots, slotsDisjoint)).overlaps(slotsDisjoint) shouldBe false // slots ∌ any of {2,3}
    }

    @Test
    fun `admits equals the boolean algebra of member predicates over a key sample`() {
        // build interests over integer keys (Ranges reads Number; Slots reads hashCode)
        val a = Interest.Ranges(listOf(Interest.Ranges.Range(0, 5)))
        val b = Interest.Ranges(listOf(Interest.Ranges.Range(3, 8)))
        val union = Interest.Union(listOf(a, b))
        val intersect = Interest.Intersect(listOf(a, b))
        val complement = Interest.Complement(a)
        for (k in -2L..10L) {
            union.admits(k) shouldBe (a.admits(k) || b.admits(k))
            intersect.admits(k) shouldBe (a.admits(k) && b.admits(k))
            complement.admits(k) shouldBe !a.admits(k)
        }
    }

    @Test
    fun `every arm round-trips through Java serialization to an equal value`() {
        for (arm in arms) {
            roundTrip(arm) shouldBe arm
        }
        // nested composites too
        val nested = Interest.Union(
            listOf(Interest.Intersect(listOf(slots, Interest.Complement(ranges))), Interest.Total),
        )
        roundTrip(nested) shouldBe nested
    }

    @Test
    fun `control (a) — an anonymous predicate interest fails the serialization round-trip`() {
        // The retired combinator shape: an object : Interest capturing a lambda,
        // returning overlaps = true unconditionally. Non-serializable (the
        // captured Function is not Serializable) — it can never ride the
        // versioned interest-assignment table across the wire. This is exactly
        // why the algebra had to close (CP-G4's blocker).
        val admit: (Any?) -> Boolean = { it == 1 }
        val anonymous: Interest = object : Interest {
            override fun overlaps(other: Interest): Boolean = true // the lie
            override fun admits(key: Any?): Boolean = admit(key)
        }
        assertThrows<NotSerializableException> { roundTrip(anonymous) }
        // the honest data-class arm with the same predicate DOES round-trip
        val honest = Interest.Ranges(listOf(Interest.Ranges.Range(1, 2)))
        roundTrip(honest) shouldBe honest
    }

    @Test
    fun `Total and Slots stay bit-identical for every pre-existing interaction`() {
        // Total: overlaps everything that existed before (Total, Slots), admits all
        Interest.Total.overlaps(Interest.Total) shouldBe true
        Interest.Total.overlaps(slots) shouldBe true
        slots.overlaps(Interest.Total) shouldBe true
        Interest.Total.admits("anything") shouldBe true
        Interest.Total.admits(null) shouldBe true
        // Slots vs Slots: exact slot-set intersection, unchanged
        slots.overlaps(slotsShared) shouldBe true    // share slot 1
        slots.overlaps(slotsDisjoint) shouldBe false // {0,1} ∩ {2,3} = ∅
        // Slots.admits: floorMod(hashCode) membership, unchanged
        val e = "key"
        slots.admits(e) shouldBe (Interest.Slots.slotOf(e, 4) in setOf(0, 1))
        // Total / Empty survive serialization as their singletons (readResolve)
        (roundTrip(Interest.Total) === Interest.Total) shouldBe true
        (roundTrip(Interest.Empty) === Interest.Empty) shouldBe true
    }

    private fun <T : Serializable> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(value) } }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as T
    }
}
