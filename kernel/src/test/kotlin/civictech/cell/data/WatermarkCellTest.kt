package civictech.cell.data

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.random.Random
import civictech.cell.data.delta.WatermarkDelta

/**
 * E3.2 lattice laws for the delivered-watermark [WatermarkDelta].
 *
 * The merge is pointwise max per (replica, source) with a grow-only `closed`
 * union — a join-semilattice, so it must be commutative, idempotent, and
 * monotone (the result dominates both operands). Idempotence is what makes the
 * substrate gossip-safe by construction: a re-delivered, already-absorbed delta
 * can never lower the watermark. All four laws are checked under 100 seeded,
 * generatively-built merge orders (house style: `Random(seed)`, kotest matchers).
 */
class WatermarkCellTest {

    // A small fixed pool of replica/source ids so random deltas OVERLAP — that
    // is what actually exercises pointwise max rather than plain map union.
    private val replicas = (0 until 4).map { UUID(0, it.toLong()) }
    private val sources = (0 until 4).map { UUID(1, it.toLong()) }

    private fun randomDelta(rnd: Random): WatermarkDelta {
        val rows = replicas
            .filter { rnd.nextBoolean() }
            .associateWith { _ ->
                sources.filter { rnd.nextBoolean() }.associateWith { _ -> rnd.nextLong(0, 20) }
            }
            .filterValues { it.isNotEmpty() }
        val closed = replicas.filter { rnd.nextInt(4) == 0 }.toSet()
        return WatermarkDelta(rows, closed)
    }

    @Test
    fun `merge is commutative under 100 seeds`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            a.merge(b) shouldBe b.merge(a)
        }
    }

    @Test
    fun `merge is idempotent under 100 seeds`() {
        for (seed in 0L until 100L) {
            val a = randomDelta(Random(seed))
            a.merge(a) shouldBe a
        }
    }

    @Test
    fun `merge is monotone - the result dominates both operands under 100 seeds`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            val m = a.merge(b)
            m.dominates(a).shouldBeTrue()
            m.dominates(b).shouldBeTrue()
        }
    }

    @Test
    fun `merge is associative under 100 seeds`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            val c = randomDelta(rnd)
            a.merge(b).merge(c) shouldBe a.merge(b.merge(c))
        }
    }

    @Test
    fun `gossip echo produces no regression - re-merging an absorbed delta never lowers the watermark`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            val merged = a.merge(b)
            // b is already fully absorbed into `merged`; any redelivery of b, in
            // any multiplicity, is a fixpoint — the defining gossip-safety property.
            merged.merge(b) shouldBe merged
            merged.merge(b).merge(b) shouldBe merged
            // and the result still dominates the original absorbed operand
            merged.merge(b).dominates(b).shouldBeTrue()
        }
    }

    @Test
    fun `closed is grow-only - a departed replica row stays closed across merges`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            val m = a.merge(b)
            m.closed shouldBe (a.closed + b.closed)
        }
    }
}
