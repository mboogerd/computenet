package civictech.demo.beadsmirror.projector

import civictech.demo.beadsmirror.feed.FeedPosition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.2.1: dot minting is a pure function of feed position, and the
 * packing that makes it one is bounded loudly rather than truncated.
 *
 * Pure JVM — no `bd`, no `dolt`, no assumptions — so this is a real CI gate.
 */
class DotMinterTest {

    private val minter = DotMinter("beads-scratch-42")

    private fun pos(height: Long, ordinal: Int = 0) = FeedPosition(height, ordinal)

    @Test
    fun `the dot source is derived from the workspace identity, so a restart reproduces it`() {
        DotMinter("beads-scratch-42").sourceId shouldBe minter.sourceId
        DotMinter("some-other-workspace").sourceId shouldNotBe minter.sourceId
    }

    @Test
    fun `re-minting at the same position and key index yields the identical dot`() {
        val first = minter.dot(pos(7, 3), 2)
        val second = DotMinter("beads-scratch-42").dot(pos(7, 3), 2)

        second shouldBe first
    }

    @Test
    fun `each component of a position gets its own dot`() {
        val dots = setOf(
            minter.dot(pos(7, 3), 2),
            minter.dot(pos(7, 3), 1), // sibling key in the same record
            minter.dot(pos(7, 4), 2), // sibling issue in the same commit
            minter.dot(pos(8, 3), 2), // next commit
        )

        dots.size shouldBe 4
    }

    @Test
    fun `the packed counter is monotone in feed order`() {
        // feed order: commit height, then ordinal within the commit, then the
        // key's index within the record. DOT_ORDER compares counter first, so
        // this is exactly what makes last-writer-wins agree with commit order.
        val ascending = listOf(
            DotMinter.counter(pos(0, 0), 0),
            DotMinter.counter(pos(0, 0), 1),
            DotMinter.counter(pos(0, 0), DotMinter.MAX_KEY_INDEX),
            DotMinter.counter(pos(0, 1), 0),
            DotMinter.counter(pos(0, DotMinter.MAX_ORDINAL), DotMinter.MAX_KEY_INDEX),
            DotMinter.counter(pos(1, 0), 0),
            DotMinter.counter(pos(2, 0), 0),
            DotMinter.counter(pos(DotMinter.MAX_COMMIT_HEIGHT, 0), 0),
        )

        ascending.sorted() shouldBe ascending
        ascending.distinct().size shouldBe ascending.size
        // the widest legal counter still fits a positive Long (32+20+11 = 63)
        DotMinter.counter(
            pos(DotMinter.MAX_COMMIT_HEIGHT, DotMinter.MAX_ORDINAL),
            DotMinter.MAX_KEY_INDEX,
        ) shouldBe Long.MAX_VALUE
    }

    @Test
    fun `each component's bit budget is guarded rather than truncated`() {
        // A silent overflow would alias two puts onto one dot, which the OR-map
        // cannot detect: it would converge on a value no commit ever wrote.
        shouldThrow<IllegalArgumentException> {
            DotMinter.counter(pos(DotMinter.MAX_COMMIT_HEIGHT + 1, 0), 0)
        }.message shouldBe
            "DotMinter: commitHeight ${DotMinter.MAX_COMMIT_HEIGHT + 1} outside 0..${DotMinter.MAX_COMMIT_HEIGHT} (32 bits)"

        shouldThrow<IllegalArgumentException> { DotMinter.counter(pos(1, DotMinter.MAX_ORDINAL + 1), 0) }
        shouldThrow<IllegalArgumentException> { DotMinter.counter(pos(1, 0), DotMinter.MAX_KEY_INDEX + 1) }
        shouldThrow<IllegalArgumentException> { DotMinter.counter(pos(-1, 0), 0) }
        shouldThrow<IllegalArgumentException> { DotMinter.counter(pos(1, -1), 0) }
        shouldThrow<IllegalArgumentException> { DotMinter.counter(pos(1, 0), -1) }
    }

    /**
     * computenet-dqj.9. The ordinal is the baseline's per-issue slot at ONE
     * synthetic height, so its budget is the ceiling on the size of workspace
     * the mirror can rebuild — not on per-commit churn. It was 10 bits (1023),
     * against a mirrored tracker measured at 565 issues on 2026-08-16; the 10
     * bits it gained came out of the commit height, which at one commit per
     * `bd` mutation has orders of magnitude to spare.
     *
     * These are literals on purpose: asserting them against the constants they
     * come from would pin nothing, and the split is the thing a future change
     * should have to state deliberately.
     */
    @Test
    fun `the ordinal budget clears a whole tracker, and the split still fills a Long`() {
        DotMinter.MAX_ORDINAL shouldBe 1_048_575
        DotMinter.MAX_COMMIT_HEIGHT shouldBe 4_294_967_295L
        DotMinter.MAX_KEY_INDEX shouldBe 2047

        DotMinter.COMMIT_HEIGHT_BITS + DotMinter.ORDINAL_BITS + DotMinter.KEY_INDEX_BITS shouldBe 63
    }

    @Test
    fun `a blank workspace identity is refused`() {
        shouldThrow<IllegalArgumentException> { DotMinter("  ") }
    }
}
