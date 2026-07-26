package civictech.cell.data

import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.RetainedFrontiers
import civictech.cell.protocol.StateRequest
import civictech.cell.link.Interest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta

/**
 * PN-3c (spec 42 §Interest-scoped instance sets): [StateRequest] carries a
 * `scope: Interest?` and [SetCell] filters its baseline reply by it — a
 * partial-interest requester pulls exactly its slice, while a scope-absent
 * (⇒ [Interest.Total]) requester gets the whole state, byte-identical to the
 * pre-scope reply. Consumer-side pull currency is retained **per instance**
 * ([RetainedFrontiers]): merging one scalar `since` across non-contiguous shard
 * holdings silently loses tags.
 */
class InterestScopedCatchUpTest {

    @Suppress("UNCHECKED_CAST")
    private val propagateSetDelta = (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private data class Reply(val delta: SetDelta<String>, val frontier: TagFrontier?)

    /** Pull [scope]/[since] state from [producer] via a fresh probe link; return the single baseline reply (or null). */
    private fun pull(producer: SetCell<String>, scope: Interest?, since: TagFrontier? = null): Reply? {
        producer.outlet.linking.onLinkedListeners.clear() // isolate the StateRequest pull path from the onLinked push
        val replies = mutableListOf<Reply>()
        val probe = FanInlet(propagateSetDelta)
        probe.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                replies += Reply(value, CurrentContext.get()!!.baseline)
            }
        })
        val link = (producer.outlet.linkTo(probe as LinkFrom<Propagate<SetDelta<String>>>) as LinkResult.Connected).link
        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, since, scope))
        return replies.singleOrNull()
    }

    // ----- part (b): scope filters the reply to the requester's slice --------

    @Test
    fun `partial-interest requester receives exactly its slice while scope-absent over-delivers`() {
        val total = 3
        val producer = SetCell<String>()
        val universe = listOf("apple", "banana", "cherry", "date", "elder", "fig", "grape", "kiwi")
        universe.forEach { producer.inlet.call.add(it) }

        val slot0 = universe.filterTo(mutableSetOf()) { Interest.Slots.slotOf(it, total) == 0 }

        // scope = slot 0 only ⇒ exactly the slot-0 elements come back
        val scoped = pull(producer, Interest.Slots(setOf(0), total))!!
        scoped.delta.adds.keys shouldBe slot0

        // CONTROL (b): scope omitted ⇒ Total ⇒ the WHOLE universe rides back —
        // the over-delivery the scope filter prevents, made observable.
        val unscoped = pull(producer, scope = null)!!
        unscoped.delta.adds.keys shouldBe universe.toSet()
        (unscoped.delta.adds.keys != slot0) shouldBe true // strictly more than the slice
    }

    @Test
    fun `scope-absent reply is byte-identical to an explicit Total reply and to full state`() {
        val producer = SetCell<String>()
        listOf("a", "b", "c", "d").forEach { producer.inlet.call.add(it) }

        val absent = pull(producer, scope = null)!!
        val total = pull(producer, scope = Interest.Total)!!

        absent.delta shouldBe total.delta            // same state-as-delta
        absent.frontier shouldBe total.frontier      // same reported currency
        absent.delta.adds.keys shouldBe setOf("a", "b", "c", "d")
        absent.frontier shouldBe producerFrontier(producer) // the full frontier, unchanged
    }

    @Test
    fun `100 seeds — the scoped reply is exactly the admitted elements, never more`() {
        val total = 4
        repeat(100) { seed ->
            val rnd = Random(seed.toLong())
            val producer = SetCell<String>()
            val universe = (0 until 30).map { "e$it-${rnd.nextInt(1000)}" }.distinct()
            universe.forEach { producer.inlet.call.add(it) }
            val admitted = (0 until total).filterTo(mutableSetOf()) { rnd.nextBoolean() }
            val scope = Interest.Slots(admitted, total)
            val expected = universe.filterTo(mutableSetOf()) { Interest.Slots.slotOf(it, total) in admitted }

            val reply = pull(producer, scope)
            (reply?.delta?.adds?.keys ?: emptySet()) shouldBe expected
        }
    }

    // ----- part (c): per-instance retained `since`, never merged ------------

    @Test
    fun `control (c) — a merged scalar since across instances loses non-contiguous tags`() {
        // Two shard instances holding non-contiguous counters of ONE shared
        // upstream source W (shard holdings are non-contiguous by construction).
        val w = UUID.randomUUID()
        val shardA = shardHolding(mapOf("kA1" to 1L, "kA3" to 3L, "kA5" to 5L), w)
        val shardB = shardHolding(mapOf("kB2" to 2L, "kB4" to 4L), w)

        // --- correct path: per-instance retained frontier -------------------
        val retained = RetainedFrontiers()
        val got = mutableSetOf<String>()
        // pull A first, record ITS frontier under A's ref
        pull(shardA, scope = null, since = retained.sinceFor(shardA.ref))!!.also {
            got += it.delta.adds.keys; retained.record(shardA.ref, it.frontier!!)
        }
        // pull B with B's OWN retained since (null — never pulled) ⇒ full slice
        pull(shardB, scope = null, since = retained.sinceFor(shardB.ref))!!.also {
            got += it.delta.adds.keys; retained.record(shardB.ref, it.frontier!!)
        }
        got shouldBe setOf("kA1", "kA3", "kA5", "kB2", "kB4") // nothing lost

        // --- control: ONE merged scalar `since` shared across instances ------
        val lost = mutableSetOf<String>()
        val aReply = pull(shardA, scope = null, since = null)!!
        lost += aReply.delta.adds.keys
        val merged = aReply.frontier!! // {W:5} — A's max, wrongly reused for B
        // B's holdings (counters 2,4) are all <= the merged max 5 ⇒ every tag
        // reads as "already seen": B produces NO reply at all (the handler
        // early-returns on an empty slice). That silent nothing is the tag loss
        // the per-instance form prevents.
        val bReply = pull(shardB, scope = null, since = merged)
        lost += bReply?.delta?.adds?.keys ?: emptySet()

        bReply shouldBe null                       // B answers nothing under the merged since
        lost shouldBe setOf("kA1", "kA3", "kA5")   // kB2, kB4 silently lost
        (got != lost) shouldBe true                // the two disciplines genuinely diverge
    }

    /** A shard SetCell whose state is exactly [holdings] (element → counter) over shared source [source]. */
    private fun shardHolding(holdings: Map<String, Long>, source: UUID): SetCell<String> {
        val cell = SetCell<String>(civictech.cell.CellRef(UUID.randomUUID()))
        val adds = holdings.mapValues { (_, ctr) -> setOf(Timestamp(source, ctr)) }
        cell.deltaInlet.call.propagate(SetDelta(adds = adds))
        return cell
    }

    private fun producerFrontier(producer: SetCell<String>): TagFrontier =
        pull(producer, scope = Interest.Total)!!.frontier!!

    // ----- PN-3b: MapDelta (aggregate) is Scoped too ------------------------

    @Test
    fun `MapDelta aggregate is Scoped — a partial-interest peer receives only admitted keys`() {
        // an aggregate keyed by a numeric group key; interest in [0,5).
        val agg = MapDelta(
            puts = mapOf(1L to "a", 2L to "b", 5L to "c", 9L to "d"),
            removals = setOf(3L, 7L),
        )
        val scope = Interest.Ranges(listOf(Interest.Ranges.Range(0, 5)))

        val sliced = agg.within(scope) { it }!!
        sliced.puts shouldBe mapOf(1L to "a", 2L to "b")   // 5, 9 excluded
        sliced.removals shouldBe setOf(3L)                 // 7 excluded

        // CONTROL: pre-Scoped, the whole aggregate rides to the partial peer —
        // Total admits everything, over-delivering keys 5, 9, 7 the scope drops.
        agg.within(Interest.Total) { it } shouldBe agg
        (agg.puts.keys != sliced.puts.keys) shouldBe true  // strictly more than the slice

        // empty slice ⇒ the emission is dropped, never rides the link.
        agg.within(Interest.Ranges(listOf(Interest.Ranges.Range(100, 200)))) { it } shouldBe null
    }
}
