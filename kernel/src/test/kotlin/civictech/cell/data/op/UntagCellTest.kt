package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.data.view.MapView
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*

/**
 * 96 §E1.5 (adapter half): [UntagCell] — the G-23 adoption seam. The
 * behaviour specifications BS-8, BS-9, BS-10 and BS-11 of feature
 * computenet-j2x.3, plus the wave-continuity assertion [KE1-22] asks for.
 *
 * What is proven here:
 *
 * - **BS-8** (`[KE1-18]`, `[KE1-20]`, `[KE1-21]`): over a real
 *   `OrMapCell → UntagCell → MapView` chain, a put / re-put-different /
 *   re-put-SAME sequence crosses as exactly two effective puts. This pins
 *   both halves of the boundary: `OrMapCell.put` always mints a dot even for
 *   an equal value, and the adapter swallows that wave because the *exposed
 *   value* did not move. The key is never observed absent in between.
 * - **BS-9** (`[KE1-19]`): a key with two live dots emits a put with the
 *   surviving value when one dot is tombstoned, and a removal only when the
 *   last one dies.
 * - **BS-11** (`[KE1-23]`, `[KE1-20]`): snapshot/restore carries the whole
 *   diff state, so a restored instance fed an unchanged delta emits nothing
 *   rather than replaying the map as novelty.
 * - **Wave continuity** (`[KE1-22]`): the emitted [MapDelta] rides the
 *   arriving wave's [Timestamp] — the adapter originates no wave.
 * - **BS-10** (`[KE1-22]`): the same continuity, checked one hop further
 *   downstream through a real [GlitchFreeCell] wave-completeness fold rather
 *   than by comparing recorded contexts directly — a single input wave
 *   crosses the fold as a single release, never two (the CC3/E2-SUITE
 *   precedent: an ungated binary operator that emitted twice per wave).
 *   `UntagCell` is single-inlet, so there is no fan-in for that shape to hide
 *   in, but this proves it rather than assumes it. No `doc/spec/20-dataflow-
 *   semantics/21-propagation.md` id fits a single-inlet pass-through's wave
 *   continuity precisely (`[21-PROP-01]` is the general propagation law,
 *   `[21-REBASE-01]`/`[22-LIVE-01]`/`[22-OBS-02]` are shaped for
 *   re-baseline/silence/multi-source concerns `UntagCell` doesn't have) — per
 *   the task, this stays a kernel test under the epic-scoped `[KE1-22]`
 *   rather than minting a new spec id or a DISPUTES entry.
 * - **`[KE1-20]` absorb-ack** (residual from computenet-j2x.3.1's review):
 *   the no-effective-change branch does not just "emit nothing" — it actually
 *   fires [civictech.cell.control.absorbAck], proven by a fan-in diamond
 *   where an always-emitting sibling arm can only flush past a
 *   [GlitchFreeCell] if `UntagCell`'s swallowed wave really acked.
 */
class UntagCellTest {

    /** A bare, hand-fed tagged-map source — full control over the dots in each delta. */
    private class RawTaggedSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<TaggedMapDelta<String, String>>>())
        fun send(delta: TaggedMapDelta<String, String>) = outlet.call.propagate(delta)
    }

    /** One emission with the wave context it rode. */
    private data class Emission(val delta: MapDelta<String, String>, val ctx: MessageContext?)

    private fun recordUntagged(cell: UntagCell<String, String>): MutableList<Emission> {
        val out = mutableListOf<Emission>()
        cell.outlet.subscribe(
            Use.fixed(
                Propagate<MapDelta<String, String>> { out += Emission(it, CurrentContext.get()) },
                PortRef.generate(),
            )
        )
        return out
    }

    private fun recordTagged(outlet: civictech.cell.port.Subscribe<Propagate<TaggedMapDelta<String, String>>>):
        MutableList<MessageContext?> {
        val out = mutableListOf<MessageContext?>()
        outlet.subscribe(
            Use.fixed(
                Propagate<TaggedMapDelta<String, String>> { out += CurrentContext.get() },
                PortRef.generate(),
            )
        )
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun link(source: RawTaggedSource, untag: UntagCell<String, String>) {
        source.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
    }

    // -----------------------------------------------------------------
    // BS-8 — one put per exposed-value change; always-mint below,
    // effective-only above ([KE1-18], [KE1-20], [KE1-21])
    // -----------------------------------------------------------------

    @Test
    fun `BS-8 Untag emits one put per exposed-value change and nothing for an equal-value re-put`() {
        val map = OrMapCell<String, String>()
        val untag = UntagCell<String, String>()
        @Suppress("UNCHECKED_CAST")
        map.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)

        val emitted = recordUntagged(untag)
        val view = MapView<String, String>()
        // the downstream fold, applied as each delta crosses — so "never
        // observed absent" is checked at every intermediate point, not only
        // at the end.
        val absentAtSomePoint = mutableListOf<Boolean>()

        fun drive(action: () -> Unit) {
            val before = emitted.size
            action()
            emitted.drop(before).forEach { view.apply(it.delta); absentAtSomePoint += ("k" !in view) }
        }

        drive { map.inlet.call.put("k", "v1") }
        drive { map.inlet.call.put("k", "v2") }
        drive { map.inlet.call.put("k", "v2") } // same value — OrMapCell still mints a fresh dot

        // the always-mint boundary this test exists to pin: three puts, three
        // dots, the third superseding the second.
        map.state().puts.getValue("k").size shouldBe 3
        map.value("k") shouldBe "v2"

        // ...but only TWO effective puts crossed the adapter.
        emitted.map { it.delta } shouldBe listOf(
            MapDelta(mapOf("k" to "v1"), emptySet()),
            MapDelta(mapOf("k" to "v2"), emptySet()),
        )
        // [KE1-21]: never a removal, so no downstream fold saw the key absent
        emitted.flatMap { it.delta.removals }.shouldBeEmpty()
        absentAtSomePoint shouldBe listOf(false, false)
        view.current() shouldBe mapOf("k" to "v2")
        untag.current() shouldBe mapOf("k" to "v2")
    }

    @Test
    fun `BS-8 a re-put crosses as a single MapDelta carrying the new value`() {
        val map = OrMapCell<String, String>()
        val untag = UntagCell<String, String>()
        @Suppress("UNCHECKED_CAST")
        map.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
        val emitted = recordUntagged(untag)

        map.inlet.call.put("k", "v1")
        emitted.size shouldBe 1

        map.inlet.call.put("k", "v2")
        // ONE delta for the re-put — the OrMapCell delta carries the fresh dot
        // AND the superseded dot's tombstone, and the adapter does not split
        // that into remove-then-put.
        emitted.size shouldBe 2
        emitted[1].delta shouldBe MapDelta(mapOf("k" to "v2"), emptySet())
    }

    // -----------------------------------------------------------------
    // [KE1-20] — echoes, duplicates and covered tombstones emit nothing
    // -----------------------------------------------------------------

    @Test
    fun `a re-delivered delta and an already-covered tombstone emit nothing`() {
        val source = RawTaggedSource()
        val untag = UntagCell<String, String>()
        link(source, untag)
        val emitted = recordUntagged(untag)

        val dot = Timestamp(UUID(1, 1), 1L)
        val put = TaggedMapDelta<String, String>(puts = mapOf("k" to mapOf(dot to "v")))
        source.send(put)
        emitted.map { it.delta } shouldBe listOf(MapDelta(mapOf("k" to "v"), emptySet()))

        source.send(put) // duplicate/echo: every dot already held
        emitted.size shouldBe 1

        val tombstone = TaggedMapDelta<String, String>(dels = mapOf("k" to setOf(dot)))
        source.send(tombstone)
        emitted.size shouldBe 2
        emitted[1].delta shouldBe MapDelta(emptyMap(), setOf("k"))

        source.send(tombstone) // the dot is already covered — nothing changes
        emitted.size shouldBe 2
        // an empty delta touches nothing at all
        source.send(TaggedMapDelta())
        emitted.size shouldBe 2
    }

    @Test
    fun `a null-valued put that makes a key appear is a change`() {
        // the computenet-4d8k guard: value(k) is null both for an absent key
        // and for a present key holding null, so presence is compared
        // separately from value.
        val source = RawTaggedSource()
        val untag = UntagCell<String, String?>()
        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
        val emitted = mutableListOf<MapDelta<String, String?>>()
        untag.outlet.subscribe(
            Use.fixed(Propagate<MapDelta<String, String?>> { emitted += it }, PortRef.generate())
        )

        val dot = Timestamp(UUID(2, 2), 1L)
        @Suppress("UNCHECKED_CAST")
        source.send(TaggedMapDelta(puts = mapOf("k" to mapOf(dot to null))) as TaggedMapDelta<String, String>)

        emitted shouldBe listOf(MapDelta<String, String?>(mapOf("k" to null), emptySet()))
        untag.current() shouldBe mapOf("k" to null)
    }

    // -----------------------------------------------------------------
    // BS-9 — a removal only when the LAST live dot dies ([KE1-19])
    // -----------------------------------------------------------------

    @Test
    fun `BS-9 Untag emits a removal only when the last live dot dies`() {
        val source = RawTaggedSource()
        val untag = UntagCell<String, String>()
        link(source, untag)
        val emitted = recordUntagged(untag)

        // two live dots at one key, from two sources — the state a replicated
        // OrMapCell reaches under concurrent same-key puts. `DOT_ORDER` is
        // (counter, sourceId), so `high` is the exposed value while both live.
        val low = Timestamp(UUID(1, 1), 1L)
        val high = Timestamp(UUID(2, 2), 2L)

        source.send(TaggedMapDelta(puts = mapOf("k" to mapOf(low to "low"))))
        source.send(TaggedMapDelta(puts = mapOf("k" to mapOf(high to "high"))))
        emitted.map { it.delta } shouldBe listOf(
            MapDelta(mapOf("k" to "low"), emptySet()),
            MapDelta(mapOf("k" to "high"), emptySet()),
        )

        // tombstone the greater dot: one live dot remains, so this is a PUT of
        // the surviving exposed value — not a removal.
        source.send(TaggedMapDelta(dels = mapOf("k" to setOf(high))))
        emitted.size shouldBe 3
        emitted[2].delta shouldBe MapDelta(mapOf("k" to "low"), emptySet())
        ("k" in untag.current()) shouldBe true

        // tombstone the last one: now the key goes away.
        source.send(TaggedMapDelta(dels = mapOf("k" to setOf(low))))
        emitted.size shouldBe 4
        emitted[3].delta shouldBe MapDelta(emptyMap(), setOf("k"))
        untag.current() shouldBe emptyMap()
    }

    @Test
    fun `BS-9 tombstoning a dot that was never the exposed value emits nothing`() {
        val source = RawTaggedSource()
        val untag = UntagCell<String, String>()
        link(source, untag)
        val emitted = recordUntagged(untag)

        val low = Timestamp(UUID(1, 1), 1L)
        val high = Timestamp(UUID(2, 2), 2L)
        source.send(
            TaggedMapDelta(puts = mapOf("k" to mapOf(low to "low", high to "high")))
        )
        emitted.map { it.delta } shouldBe listOf(MapDelta(mapOf("k" to "high"), emptySet()))

        // the loser dies: presence unchanged, exposed value unchanged — the
        // adapter emits nothing ([KE1-20]).
        source.send(TaggedMapDelta(dels = mapOf("k" to setOf(low))))
        emitted.size shouldBe 1
    }

    // -----------------------------------------------------------------
    // BS-11 — restore does not replay the map as novelty ([KE1-23])
    // -----------------------------------------------------------------

    @Test
    fun `BS-11 a restored instance fed an unchanged delta emits nothing`() {
        val source = RawTaggedSource()
        val original = UntagCell<String, String>()
        link(source, original)
        val firstEmissions = recordUntagged(original)

        val a = Timestamp(UUID(1, 1), 1L)
        val b = Timestamp(UUID(1, 1), 2L)
        val gone = Timestamp(UUID(1, 1), 3L)
        val full = TaggedMapDelta<String, String>(
            puts = mapOf(
                "a" to mapOf(a to "1"),
                "b" to mapOf(b to "2"),
                "c" to mapOf(gone to "3"),
            ),
            dels = mapOf("c" to setOf(gone)),
        )
        source.send(full)
        firstEmissions.single().delta shouldBe MapDelta(mapOf("a" to "1", "b" to "2"), emptySet())

        // a serialization round-trip, as the drain protocol forces on migration
        val carried = roundTrip(original.snapshot())

        val restoredSource = RawTaggedSource()
        val restored = UntagCell<String, String>()
        restored.restore(carried)
        link(restoredSource, restored)
        val secondEmissions = recordUntagged(restored)

        restored.current() shouldBe mapOf("a" to "1", "b" to "2")

        // the same delta again: every dot already held, every exposed value
        // unchanged — the restored instance replays nothing.
        restoredSource.send(full)
        secondEmissions.shouldBeEmpty()

        // and it is still live: genuinely new information still crosses.
        restoredSource.send(TaggedMapDelta(puts = mapOf("a" to mapOf(Timestamp(UUID(1, 1), 9L) to "9"))))
        secondEmissions.single().delta shouldBe MapDelta(mapOf("a" to "9"), emptySet())
    }

    @Test
    fun `a late-linked consumer is caught up with the exposed map`() {
        val source = RawTaggedSource()
        val untag = UntagCell<String, String>()
        link(source, untag)
        source.send(
            TaggedMapDelta(
                puts = mapOf("a" to mapOf(Timestamp(UUID(1, 1), 1L) to "1")),
            )
        )

        // subscribing AFTER the traffic: catchUpOnLinked fires on link install
        val late = mutableListOf<MapDelta<String, String>>()
        val collector = MapCollectorCell(late)
        @Suppress("UNCHECKED_CAST")
        untag.outlet.linkTo(collector.inlet as LinkFrom<Propagate<MapDelta<String, String>>>)
        late.single() shouldBe MapDelta(mapOf("a" to "1"), emptySet())

        // an adapter that has published nothing catches nobody up
        val emptyUntag = UntagCell<String, String>()
        val none = mutableListOf<MapDelta<String, String>>()
        val noneCollector = MapCollectorCell(none)
        @Suppress("UNCHECKED_CAST")
        emptyUntag.outlet.linkTo(noneCollector.inlet as LinkFrom<Propagate<MapDelta<String, String>>>)
        none.shouldBeEmpty()
    }

    // -----------------------------------------------------------------
    // [KE1-22] — wave continuity: the emission rides the arriving wave
    // -----------------------------------------------------------------

    @Test
    fun `KE1-22 the emitted MapDelta rides the arriving wave and originates none`() {
        val map = OrMapCell<String, String>()
        val untag = UntagCell<String, String>()
        @Suppress("UNCHECKED_CAST")
        map.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)

        // the context the tagged delta rides on its way INTO the adapter,
        // observed on the very outlet emission the adapter's inlet receives.
        val arriving = recordTagged(map.outlet)
        val emitted = recordUntagged(untag)

        map.inlet.call.put("k", "v1")
        map.inlet.call.put("k", "v2")

        arriving.size shouldBe 2
        emitted.size shouldBe 2
        arriving.forEachIndexed { i, inbound ->
            inbound.shouldNotBeNull()
            val outbound = emitted[i].ctx
            outbound.shouldNotBeNull()
            // same wave, same source: the adapter did NOT originate a new one
            outbound.timestamp shouldBe inbound.timestamp
            outbound.baseline shouldBe inbound.baseline
            outbound.reBaseline shouldBe inbound.reBaseline
        }
        // and the two puts are genuinely two distinct waves, so the assertion
        // above is not trivially satisfied by a constant.
        (arriving[0]!!.timestamp == arriving[1]!!.timestamp).shouldBeFalse()
    }

    // -----------------------------------------------------------------
    // BS-10 — a downstream GlitchFreeCell fold sees one wave, not two
    // ([KE1-22])
    // -----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private val mapDeltaApi = Propagate::class.java as Class<Propagate<MapDelta<String, String>>>

    @Test
    fun `BS-10 a downstream glitch-free fold sees one release per input wave, not two`() {
        val map = OrMapCell<String, String>()
        val untag = UntagCell<String, String>()
        @Suppress("UNCHECKED_CAST")
        map.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)

        // a real wave-completeness fold downstream of the adapter — not a bare
        // subscriber. UntagCell has a single inlet, so this fold's frontier
        // has exactly one edge and completes it trivially; what it proves is
        // that ONE input wave crosses as ONE released invocation, never two
        // (the shape the CC3/E2-SUITE double-emission defect took elsewhere).
        val gf = GlitchFreeCell(mapDeltaApi)
        @Suppress("UNCHECKED_CAST")
        untag.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, String>>>)

        data class Release(val delta: MapDelta<String, String>, val ctx: MessageContext?)
        val released = mutableListOf<Release>()
        gf.outlet.subscribe(
            Use.fixed(
                Propagate<MapDelta<String, String>> { released += Release(it, CurrentContext.get()) },
                PortRef.generate(),
            )
        )

        val arriving = recordTagged(map.outlet)

        map.inlet.call.put("k", "v1")
        map.inlet.call.put("k", "v2")

        // two input waves in, exactly two releases out through the fold — a
        // double-emitting adapter would show four here, or a single release
        // straddling both waves would show one.
        arriving.size shouldBe 2
        released.size shouldBe 2
        released.map { it.delta } shouldBe listOf(
            MapDelta(mapOf("k" to "v1"), emptySet()),
            MapDelta(mapOf("k" to "v2"), emptySet()),
        )

        // and each release still rides the ORIGINAL arriving wave's context —
        // continuity survives the extra hop through the completeness fold,
        // it is not merely "some" wave per release.
        released.forEachIndexed { i, release ->
            val inbound = arriving[i]
            inbound.shouldNotBeNull()
            val outbound = release.ctx
            outbound.shouldNotBeNull()
            outbound.timestamp shouldBe inbound.timestamp
            outbound.baseline shouldBe inbound.baseline
            outbound.reBaseline shouldBe inbound.reBaseline
        }
        (released[0].ctx!!.timestamp == released[1].ctx!!.timestamp).shouldBeFalse()
    }

    // -----------------------------------------------------------------
    // [KE1-20] residual — the swallowed-wave branch really absorb-acks, not
    // merely "emits nothing" (computenet-j2x.3.1 review residual)
    // -----------------------------------------------------------------

    /**
     * The always-real sibling arm of a fan-in diamond: fed the same
     * [TaggedMapDelta] stream as the [UntagCell] under test, it emits a fresh
     * marker [MapDelta] for every wave, so the [GlitchFreeCell] downstream
     * genuinely has two edges to settle rather than trivially completing on
     * one.
     */
    private class AlwaysEmitFromTagged(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort(
            "inlet",
            FanInlet(Propagate::class.java as Class<Propagate<TaggedMapDelta<String, String>>>),
        )
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, String>>>())
        private var n = 0

        init {
            inlet.onEach { outlet.call.propagate(MapDelta(mapOf("marker-${n++}" to "x"), emptySet())) }
        }
    }

    @Test
    fun `KE1-20 the swallowed branch absorb-acks so a downstream glitch-free join still settles`() {
        val source = RawTaggedSource()
        val untag = UntagCell<String, String>()
        val passArm = AlwaysEmitFromTagged()
        val gf = GlitchFreeCell(mapDeltaApi)
        val received = mutableListOf<MapDelta<String, String>>()
        gf.outlet.subscribe(
            Use.fixed(Propagate<MapDelta<String, String>> { received += it }, PortRef.generate())
        )

        link(source, untag)
        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
        @Suppress("UNCHECKED_CAST")
        untag.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, String>>>)
        @Suppress("UNCHECKED_CAST")
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, String>>>)

        val dot = Timestamp(UUID(9, 9), 1L)
        val put = TaggedMapDelta<String, String>(puts = mapOf("k" to mapOf(dot to "v")))
        source.send(put)
        // both edges settle: untag's real put and the pass arm's marker.
        received.size shouldBe 2

        // a re-delivered echo: untag's exposed value is unchanged, so it emits
        // nothing on this edge — but if it did not absorb-ack, the join could
        // never complete this wave and the pass arm's marker would be stranded
        // behind it, undelivered forever.
        source.send(put)
        received.size shouldBe 3
        received.last() shouldBe MapDelta(mapOf("marker-1" to "x"), emptySet())
    }

    // -----------------------------------------------------------------

    private class MapCollectorCell(
        private val sink: MutableList<MapDelta<String, String>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort(
            "inlet",
            civictech.cell.port.FanInlet(Propagate::class.java as Class<Propagate<MapDelta<String, String>>>),
        )

        init {
            inlet.serve(object : Propagate<MapDelta<String, String>> {
                override fun propagate(value: MapDelta<String, String>) {
                    sink += value
                }
            })
        }
    }

    private fun roundTrip(state: Serializable): Serializable {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(bytes).use { it.writeObject(state) }
        return java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes.toByteArray()))
            .readObject() as Serializable
    }
}
