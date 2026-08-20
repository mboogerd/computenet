package civictech.cell.membrane

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins [AuthLevel]'s declaration order as the *authority ordering* (epic
 * `computenet-ssa` §9.7).
 *
 * Why this test exists: [ProtocolAuthority.minAuth] is a floor, and the site
 * that enforces it — the `minAuth` branch of `BoundaryPolicy.protocolAuthority`
 * as an inbound filter, `CompositeCell.kt`'s `asProtocolFilter` — compares two
 * `AuthLevel`s with `<`, i.e. with the `compareTo`/`ordinal` ordering Kotlin
 * derives from the declaration. That comparison was harmless while
 * [AuthLevel.Authenticated] was unreachable; it becomes load-bearing the moment
 * a crossing can actually achieve it. A reorder or an out-of-strength insertion
 * would then silently re-grade which crossings a floor admits, with no compile
 * error anywhere. These assertions are the loud failure that replaces it.
 */
class AuthLevelOrderingTest {

    @Test
    fun `TransportVouched is weaker than Authenticated`() {
        (AuthLevel.TransportVouched < AuthLevel.Authenticated).shouldBeTrue()
        AuthLevel.TransportVouched.ordinal shouldBeLessThan AuthLevel.Authenticated.ordinal
    }

    @Test
    fun `the entries list is exactly the levels in weakest-first order`() {
        // Pins insertion too, not only reordering: a new level appended here
        // fails this assertion, forcing whoever adds it to place it at the
        // position its strength dictates and to re-read the floor semantics.
        AuthLevel.entries shouldContainExactly listOf(
            AuthLevel.TransportVouched,
            AuthLevel.Authenticated,
        )
    }

    @Test
    fun `the default authority floor is the weakest level, so it admits every principal`() {
        // "All default open (P7)": the default floor must be the minimum of the
        // ordering, or an unconfigured ProtocolAuthority would start refusing.
        val default = ProtocolAuthority()
        default.minAuth shouldBe AuthLevel.entries.first()
        AuthLevel.entries.forEach { level ->
            (level < default.minAuth).shouldBe(false)
        }
    }

    @Test
    fun `every level meets a floor at or below itself and fails one above it`() {
        // The floor predicate as the filter spells it (`peer.auth < minAuth` is
        // a refusal), checked over the whole cross product rather than the one
        // pair the filter happens to exercise today.
        AuthLevel.entries.forEach { achieved ->
            AuthLevel.entries.forEach { floor ->
                val refused = achieved < floor
                refused shouldBe (achieved.ordinal < floor.ordinal)
            }
        }
        (AuthLevel.TransportVouched < AuthLevel.Authenticated).shouldBeTrue()
        (AuthLevel.Authenticated < AuthLevel.TransportVouched).shouldBe(false)
    }
}
