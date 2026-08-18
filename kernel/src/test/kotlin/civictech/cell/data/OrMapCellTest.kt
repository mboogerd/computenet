package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * `OrMapCell` / `TaggedMapDelta` — the four normative laws of spec 20/24
 * §Tagged maps, each exercised by id (96 §E1.2, E1-CORE):
 *
 * - `[24-TMAP-01]` merge is commutative, associative, idempotent — checked
 *   algebraically and over seeded interleave/duplicate/reorder schedules.
 * - `[24-TMAP-02]` add-wins presence.
 * - `[24-TMAP-03]` value is the greatest `(counter, sourceId)` live dot,
 *   never wall clock and never arrival order.
 * - `[24-TMAP-04]` reset-remove: a remove tombstones exactly the dots it
 *   observed live, so a concurrent put's dot survives the merge.
 *
 * plus re-put atomicity, catch-up, snapshot/restore counter continuity, the
 * wire round-trip with its compact grouped-by-source encoding, and the
 * divergence control that proves the harness distinguishes the tagged map
 * from `MapCell`'s untagged arrival-order one.
 */
class OrMapCellTest {

    // -----------------------------------------------------------------
    // scaffolding
    // -----------------------------------------------------------------

    /** Records tagged-map deltas in arrival order (the catch-up subscriber). */
    class TaggedMapCollectorCell(
        val arrivals: MutableList<TaggedMapDelta<String, String>> = mutableListOf(),
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort(
            "inlet",
            FanInlet(Propagate::class.java as Class<Propagate<TaggedMapDelta<String, String>>>),
        )

        init {
            inlet.serve(object : Propagate<TaggedMapDelta<String, String>> {
                override fun propagate(value: TaggedMapDelta<String, String>) {
                    arrivals += value
                }
            })
        }
    }

    /** Subscribe a plain recorder to a cell's outlet (no handshake ⇒ no catch-up). */
    private fun record(cell: OrMapCell<String, String>): MutableList<TaggedMapDelta<String, String>> {
        val out = mutableListOf<TaggedMapDelta<String, String>>()
        cell.outlet.subscribe(
            Use.fixed(
                object : Propagate<TaggedMapDelta<String, String>> {
                    override fun propagate(value: TaggedMapDelta<String, String>) {
                        out += value
                    }
                },
                PortRef.generate(),
            )
        )
        return out
    }

    private fun record(cell: MapCell<String, String>): MutableList<MapDelta<String, String>> {
        val out = mutableListOf<MapDelta<String, String>>()
        cell.outlet.subscribe(
            Use.fixed(
                object : Propagate<MapDelta<String, String>> {
                    override fun propagate(value: MapDelta<String, String>) {
                        out += value
                    }
                },
                PortRef.generate(),
            )
        )
        return out
    }

    private fun fold(deltas: List<TaggedMapDelta<String, String>>): TaggedMapDelta<String, String> =
        deltas.fold(TaggedMapDelta()) { acc, d -> acc.merge(d) }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.flatMapIndexed { i, item ->
            permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(item) + it }
        }

    // -----------------------------------------------------------------
    // [24-TMAP-01] — merge is pointwise dot union
    // -----------------------------------------------------------------

    @Test
    fun `TMAP-01 merge is commutative, associative and idempotent`() {
        val s1 = UUID(0, 1)
        val s2 = UUID(0, 2)
        val a = TaggedMapDelta(
            puts = mapOf("k" to mapOf(Timestamp(s1, 1) to "a1")),
            dels = mapOf("j" to setOf(Timestamp(s2, 4))),
        )
        val b = TaggedMapDelta(
            puts = mapOf("k" to mapOf(Timestamp(s2, 1) to "b1"), "j" to mapOf(Timestamp(s2, 4) to "b0")),
        )
        val c = TaggedMapDelta<String, String>(dels = mapOf("k" to setOf(Timestamp(s1, 1))))

        // commutative
        a.merge(b) shouldBe b.merge(a)
        b.merge(c) shouldBe c.merge(b)
        // associative
        a.merge(b).merge(c) shouldBe a.merge(b.merge(c))
        // idempotent — a dot names one put and always carries the same value
        a.merge(a) shouldBe a
        a.merge(b).merge(b) shouldBe a.merge(b)
        // and the reads that ride on it agree across every application order:
        // k keeps b's uncovered dot (c only tombstoned a's), while j's single
        // dot is covered by a tombstone that may arrive before or after it.
        permutations(listOf(a, b, c)).forEach { order ->
            val folded = fold(order)
            folded.membership() shouldBe setOf("k")
            folded.value("k") shouldBe "b1"
            folded.value("j") shouldBe null
        }
    }

    /**
     * `[24-TMAP-01]`, property form: three unlinked writers (genuinely
     * concurrent — no replication exists until E1-REPL, so no writer ever
     * observes another's dots) produce a fixed dot set; every interleaving,
     * duplication and reordering of that set merges to the same membership
     * and the same per-key values.
     *
     * The oracle is computed from the writers' own state **without**
     * [TaggedMapDelta.merge], so the property is not checked against itself:
     * a dot can only be tombstoned by the writer that minted it, so the
     * merged state's live dots are exactly the union of each writer's own
     * uncovered dots.
     */
    @Test
    fun `TMAP-01 any interleaving, duplication and reordering of a fixed dot set converges`() {
        for (seed in 0L until 100L) {
            val ops = script(seed, count = 40)
            val (writers, streams) = runTagged(seed, ops)
            val expected = oracle(writers)
            val emissions = streams.flatten()

            val rnd = Random(seed xor 0x5EED)
            repeat(4) { shuffle ->
                val schedule = ArrayList(emissions)
                Collections.shuffle(schedule, rnd)
                // duplicate deliveries: idempotence must absorb them
                val delivered = schedule.flatMap { if (rnd.nextInt(4) == 0) listOf(it, it) else listOf(it) }
                val folded = fold(delivered)
                withMessage("seed $seed shuffle $shuffle") {
                    folded.membership() shouldBe expected.keys
                    expected.forEach { (key, value) -> folded.value(key) shouldBe value }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // [24-TMAP-02] — add-wins presence
    // -----------------------------------------------------------------

    @Test
    fun `TMAP-02 a key is present iff it has at least one live dot`() {
        val s = UUID(0, 1)
        val live = TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(s, 1) to "v")))
        live.membership() shouldBe setOf("k")

        val covered = live.merge(TaggedMapDelta(dels = mapOf("k" to setOf(Timestamp(s, 1)))))
        covered.membership() shouldBe emptySet<String>()
        covered.value("k") shouldBe null

        // a tombstone whose put never arrived: still absent, and it covers the
        // put on arrival (tombstoned dels subsume deferred context ops)
        val orphanTombstone = TaggedMapDelta<String, String>(dels = mapOf("k" to setOf(Timestamp(s, 9))))
        orphanTombstone.membership() shouldBe emptySet<String>()
        orphanTombstone.merge(TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(s, 9) to "late"))))
            .membership() shouldBe emptySet<String>()

        // add-wins: one live dot beside any number of tombstoned ones keeps the key
        covered.merge(TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(s, 2) to "again"))))
            .membership() shouldBe setOf("k")
    }

    @Test
    fun `TMAP-02 add-wins - a concurrent put keeps the key alive across a remove`() {
        val (a, b) = concurrentWriters()
        val sa = record(a)
        val sb = record(b)

        a.inlet.call.put("k", "from-a")
        b.inlet.call.put("k", "from-b") // b never observed a's dot
        a.inlet.call.remove("k") // observes only a's own dot

        listOf(sa + sb, sb + sa).forEach { schedule ->
            val folded = fold(schedule)
            folded.membership() shouldBe setOf("k")
            folded.value("k") shouldBe "from-b"
        }
    }

    // -----------------------------------------------------------------
    // [24-TMAP-03] — value by dot order, never wall clock / arrival order
    // -----------------------------------------------------------------

    @Test
    fun `TMAP-03 the exposed value is the greatest (counter, sourceId) live dot in every application order`() {
        val lo = UUID(0, 1)
        val hi = UUID(0, 2)
        // dot order: (1, lo) < (1, hi) < (2, lo)
        val parts = listOf(
            TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(lo, 1) to "counter-1-lo"))),
            TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(hi, 1) to "counter-1-hi"))),
            TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(lo, 2) to "counter-2-lo"))),
        )
        // whichever order the deltas are applied in — including the one where
        // the greatest dot arrives FIRST and the least arrives LAST — the
        // exposed value is the greatest dot's. Arrival order is not consulted,
        // and nothing here reads a clock.
        permutations(parts).forEach { order -> fold(order).value("k") shouldBe "counter-2-lo" }

        // a counter tie is broken by sourceId, not by which arrived last
        val tie = parts.take(2)
        tie.let { fold(it).value("k") shouldBe "counter-1-hi" }
        fold(tie.reversed()).value("k") shouldBe "counter-1-hi"

        // tombstoning the winner exposes the next-greatest LIVE dot
        val allThree = fold(parts)
        allThree.merge(TaggedMapDelta(dels = mapOf("k" to setOf(Timestamp(lo, 2)))))
            .value("k") shouldBe "counter-1-hi"
    }

    @Test
    fun `TMAP-03 the cell's reads agree with the delta's reads`() {
        val cell = OrMapCell<String, String>()
        cell.inlet.call.put("a", "1")
        cell.inlet.call.put("b", "2")
        cell.inlet.call.put("a", "3")
        cell.inlet.call.remove("b")

        cell.membership() shouldBe setOf("a")
        cell.value("a") shouldBe "3"
        cell.value("b") shouldBe null
        cell.membership() shouldBe cell.state().membership()
        cell.value("a") shouldBe cell.state().value("a")
    }

    // -----------------------------------------------------------------
    // [24-TMAP-04] — reset-remove
    // -----------------------------------------------------------------

    @Test
    fun `TMAP-04 remove tombstones every dot observed live at the key`() {
        // two live dots at one key from two sources — the state E1-REPL's
        // applyRemote will produce; installed here through restore(), which is
        // the only shipped way to reach it before replication lands.
        val foreign = Timestamp(UUID(9, 9), 5L)
        val own = Timestamp(UUID(1, 1), 2L)
        val stale = Timestamp(UUID(9, 9), 1L)
        val cell = OrMapCell<String, String>()
        cell.restore(
            HashMap(
                mapOf(
                    "puts" to HashMap(mapOf("k" to LinkedHashMap(mapOf(foreign to "f", own to "o", stale to "s")))),
                    "dels" to HashMap(mapOf("k" to LinkedHashSet(setOf(stale)))),
                    "counter" to 2L,
                )
            ) as Serializable
        )

        val emitted = record(cell)
        cell.inlet.call.remove("k")

        // exactly the dots observed LIVE — the already-covered one is not re-shipped
        emitted.single().dels.getValue("k") shouldBe setOf(foreign, own)
        emitted.single().puts shouldBe emptyMap()
        cell.membership() shouldBe emptySet<String>()
    }

    @Test
    fun `TMAP-04 reset-remove - a concurrent put's dot survives a remove that never observed it`() {
        val (a, b) = concurrentWriters()
        val sa = record(a)
        val sb = record(b)

        a.inlet.call.put("k", "a1")
        a.inlet.call.put("k", "a2") // a now has one live dot at k
        b.inlet.call.put("k", "b1") // concurrent, unobserved by a
        a.inlet.call.remove("k")

        val aDots = sa.flatMap { it.puts["k"]?.keys ?: emptySet() }.toSet()
        val bDot = sb.single().puts.getValue("k").keys.single()
        // the remove carried only a's own live dot; b's dot is nowhere in a's dels
        sa.flatMap { it.dels["k"] ?: emptySet() }.toSet() shouldBe aDots
        (bDot in sa.flatMap { it.dels["k"] ?: emptySet() }) shouldBe false

        listOf(sa + sb, sb + sa).forEach { schedule ->
            val folded = fold(schedule)
            folded.membership() shouldBe setOf("k")
            folded.value("k") shouldBe "b1"
            folded.liveDots("k").keys shouldBe setOf(bDot)
        }
    }

    @Test
    fun `remove of a key with no live dot is an effective-only no-op`() {
        val cell = OrMapCell<String, String>()
        val emitted = record(cell)
        cell.inlet.call.remove("absent")
        emitted.isEmpty() shouldBe true

        cell.inlet.call.put("k", "v")
        cell.inlet.call.remove("k")
        cell.inlet.call.remove("k") // nothing live left to tombstone
        emitted.size shouldBe 2
    }

    // -----------------------------------------------------------------
    // re-put atomicity (the KeyedSetCell invariant, lifted to dots)
    // -----------------------------------------------------------------

    @Test
    fun `a re-put ships the tombstone and the new dot in ONE delta - never two live values, never zero`() {
        val cell = OrMapCell<String, String>()
        val emitted = record(cell)

        cell.inlet.call.put("k", "v1")
        cell.inlet.call.put("k", "v2")
        cell.inlet.call.put("k", "v3")

        emitted.size shouldBe 3 // one delta per put, never a retract+add pair
        var acc = TaggedMapDelta<String, String>()
        val observed = emitted.map { delta ->
            acc = acc.merge(delta)
            acc.liveDots("k").size to acc.value("k")
        }
        // a downstream fold sees exactly one live value after every emission
        observed shouldBe listOf(1 to "v1", 1 to "v2", 1 to "v3")

        // the atomic halves ride together
        emitted[1].puts.getValue("k").size shouldBe 1
        emitted[1].dels.getValue("k") shouldBe emitted[0].puts.getValue("k").keys
        // a re-put of the SAME value still mints: the fresh dot is the
        // last-writer-wins evidence a later [24-TMAP-03] comparison needs
        cell.inlet.call.put("k", "v3")
        emitted.size shouldBe 4
        emitted[3].puts.getValue("k").keys.single() shouldBe Timestamp(
            emitted[0].puts.getValue("k").keys.single().sourceId, 4L,
        )
    }

    // -----------------------------------------------------------------
    // catch-up, snapshot/restore
    // -----------------------------------------------------------------

    @Test
    fun `late-join catch-up ships full state including tombstones`() {
        val cell = OrMapCell<String, String>()
        cell.inlet.call.put("a", "1")
        cell.inlet.call.put("b", "2")
        cell.inlet.call.put("a", "3")
        cell.inlet.call.remove("b")

        val late = TaggedMapCollectorCell()
        @Suppress("UNCHECKED_CAST")
        val result = cell.outlet.linkTo(late.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
        (result is LinkResult.Connected).shouldBeTrue()

        val caught = late.arrivals.single()
        caught shouldBe cell.state()
        // tombstones included: the late joiner must not resurrect "b", nor
        // expose "a"'s superseded first value
        caught.dels.getValue("b").isNotEmpty() shouldBe true
        caught.membership() shouldBe setOf("a")
        caught.value("a") shouldBe "3"
        caught.value("b") shouldBe null

        // an empty cell catches nothing up
        val empty = OrMapCell<String, String>()
        val none = TaggedMapCollectorCell()
        @Suppress("UNCHECKED_CAST")
        empty.outlet.linkTo(none.inlet as LinkFrom<Propagate<TaggedMapDelta<String, String>>>)
        none.arrivals.isEmpty() shouldBe true
    }

    @Test
    fun `snapshot-restore carries the dot counter - a restored instance never re-mints a spent dot`() {
        val ref = CellRef(UUID(7, 7), instanceId = 3)
        val original = OrMapCell<String, String>(ref)
        original.inlet.call.put("k", "v1")
        original.inlet.call.put("j", "v2")
        original.inlet.call.put("k", "v3")
        val spent = original.state().puts.values.flatMap { it.keys }.toSet()
        spent.size shouldBe 3

        val restored = OrMapCell<String, String>(ref)
        restored.restore(original.snapshot())
        restored.state() shouldBe original.state()
        restored.membership() shouldBe setOf("k", "j")
        restored.value("k") shouldBe "v3"

        val emitted = record(restored)
        restored.inlet.call.put("k", "v4")
        val fresh = emitted.single().puts.getValue("k").keys.single()
        (fresh in spent) shouldBe false
        fresh.counter shouldBe 4L // the counter continued; 1..3 are not reused
        // and the derived source is replay-stable: same ref ⇒ same dot source
        fresh.sourceId shouldBe spent.first().sourceId
    }

    // -----------------------------------------------------------------
    // wire: additive registration, identity round-trip, grouped-by-source
    // -----------------------------------------------------------------

    private fun frame(delta: TaggedMapDelta<Any?, Any?>): HostedPortInvocation {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        return HostedPortInvocation(
            cellRef = CellRef(UUID.randomUUID()),
            portName = "outlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(
                propagate,
                arrayOf<Any?>(delta),
                MessageContext(Timestamp(UUID.randomUUID(), 3), PortRef.generate()),
            ),
        )
    }

    @Test
    fun `TaggedMapDelta round-trips through WireCodec as an identity`() {
        val s1 = UUID.randomUUID()
        val s2 = UUID.randomUUID()
        val delta = TaggedMapDelta<Any?, Any?>(
            puts = mapOf(
                "milk" to mapOf(Timestamp(s1, 1) to "2L", Timestamp(s2, 4) to "1L"),
                "eggs" to mapOf(Timestamp(s1, 7) to "6"),
            ),
            dels = mapOf("eggs" to setOf(Timestamp(s1, 2), Timestamp(s2, 9))),
        )

        val decoded = WireCodec.decode(WireCodec.encode(frame(delta)))
        decoded.invocation.args shouldBe listOf(delta)

        // empty and tombstone-only deltas survive too
        val empty = TaggedMapDelta<Any?, Any?>()
        WireCodec.decode(WireCodec.encode(frame(empty))).invocation.args shouldBe listOf(empty)
    }

    @Test
    fun `the encoded form groups dots by source instead of repeating the sourceId per dot`() {
        val s1 = UUID.randomUUID()
        val s2 = UUID.randomUUID()
        // 5 dots, only 2 distinct sources, spread over two keys and both maps
        val delta = TaggedMapDelta<Any?, Any?>(
            puts = mapOf(
                "milk" to mapOf(Timestamp(s1, 1) to "a", Timestamp(s1, 2) to "b", Timestamp(s2, 1) to "c"),
                "eggs" to mapOf(Timestamp(s1, 3) to "d"),
            ),
            dels = mapOf("milk" to setOf(Timestamp(s2, 5))),
        )
        val encoded = WireCodec.encode(frame(delta)).decodeToString()

        encoded.contains("TaggedMapDelta").shouldBeTrue() // the @SerialName discriminator
        encoded.contains("\"sources\"").shouldBeTrue() // the source dictionary
        // each source is written exactly once, not once per dot
        Regex(Regex.escape(s1.toString())).findAll(encoded).count() shouldBe 1
        Regex(Regex.escape(s2.toString())).findAll(encoded).count() shouldBe 1
    }

    // -----------------------------------------------------------------
    // divergence control — the untagged map under the same schedule
    // -----------------------------------------------------------------

    /**
     * The control the tagged property is meaningless without: the SAME
     * concurrent schedule driven through `MapCell` — whose `MapDelta` carries
     * no dots and whose consumers can only apply puts/removals in arrival
     * order — must produce different views under different interleavings on
     * at least one seed. If this stops flipping, the harness has stopped
     * interleaving, not the untagged map started converging.
     */
    @Test
    fun `control - the same schedule through MapCell arrival-order views diverges on at least one seed`() {
        val diverged = mutableListOf<Long>()
        for (seed in 0L until 100L) {
            val ops = script(seed, count = 40)
            val streams = runUntagged(ops)
            val emissions = streams.flatten()
            val rnd = Random(seed xor 0x5EED)
            val views = (0 until 4).map {
                val schedule = ArrayList(emissions)
                Collections.shuffle(schedule, rnd)
                mapFold(schedule)
            }
            if (views.toSet().size > 1) diverged += seed
        }
        // if this fails the harness interleaving is too weak to expose order bias
        println("MapCell arrival-order control diverged on ${diverged.size} seed(s): ${diverged.take(10)}...")
        diverged.isNotEmpty().shouldBeTrue()
    }

    // -----------------------------------------------------------------
    // schedule generation + the merge-free oracle
    // -----------------------------------------------------------------

    /** One writer operation; a null [value] is a remove. */
    private data class Op(val writer: Int, val key: String, val value: String?)

    private fun script(seed: Long, count: Int): List<Op> {
        val rnd = Random(seed)
        val keys = listOf("a", "b", "c")
        return (0 until count).map {
            val writer = rnd.nextInt(WRITERS)
            val key = keys[rnd.nextInt(keys.size)]
            Op(writer, key, if (rnd.nextInt(10) < 7) "w$writer-${rnd.nextInt(1000)}" else null)
        }
    }

    /**
     * Replay [ops] through [WRITERS] unlinked `OrMapCell`s. Their refs are
     * derived from [seed], so the dot sources — and therefore every
     * `(counter, sourceId)` tie-break — are fully deterministic per seed.
     */
    private fun runTagged(
        seed: Long,
        ops: List<Op>,
    ): Pair<List<OrMapCell<String, String>>, List<List<TaggedMapDelta<String, String>>>> {
        val writers = List(WRITERS) { i -> OrMapCell<String, String>(CellRef(UUID(seed, i.toLong()))) }
        val streams = writers.map { record(it) }
        ops.forEach { op ->
            if (op.value == null) writers[op.writer].inlet.call.remove(op.key)
            else writers[op.writer].inlet.call.put(op.key, op.value)
        }
        return writers to streams
    }

    /** The same schedule through untagged `MapCell` writers. */
    private fun runUntagged(ops: List<Op>): List<List<MapDelta<String, String>>> {
        val writers = List(WRITERS) { MapCell<String, String>() }
        val streams = writers.map { record(it) }
        ops.forEach { op ->
            if (op.value == null) writers[op.writer].inlet.call.remove(op.key)
            else writers[op.writer].inlet.call.put(op.key, op.value)
        }
        return streams
    }

    /**
     * The expected merged state, computed **without** [TaggedMapDelta.merge]:
     * these writers are unlinked, so a dot can only be tombstoned by the
     * writer that minted it, and the merged state's live dots are exactly the
     * union of each writer's own uncovered dots. `[24-TMAP-03]` then picks the
     * greatest by dot order.
     */
    private fun oracle(writers: List<OrMapCell<String, String>>): Map<String, String> {
        val live = mutableMapOf<String, MutableMap<Timestamp, String>>()
        writers.forEach { writer ->
            val state = writer.state()
            state.puts.forEach { (key, dots) ->
                val covered = state.dels[key] ?: emptySet()
                dots.forEach { (dot, value) ->
                    if (dot !in covered) live.getOrPut(key) { mutableMapOf() }[dot] = value
                }
            }
        }
        return live.filterValues { it.isNotEmpty() }.mapValues { (_, dots) ->
            dots.entries.maxWithOrNull(compareBy(TaggedMapDelta.DOT_ORDER) { it.key })!!.value
        }
    }

    // -----------------------------------------------------------------
    // read accessors under a concurrent writer (computenet-yk5r)
    // -----------------------------------------------------------------

    /**
     * A host reads the cell (`membership`/`value`/`state`/`snapshot`) from its
     * own thread while the cell's writer mutates `puts`/`dels`. Before the
     * guard those accessors iterated the shared `LinkedHashMap`s unsynchronised
     * and escaped a `ConcurrentModificationException` into the caller —
     * observed on CI as `HeadlineLivenessTest > initializationError`, thrown out
     * of `OrMapCell.membership` on an `awaitUntil` thread while the beads
     * mirror's poller wrote (computenet-yk5r).
     *
     * **This reproduction is statistical, not deterministic**, and deliberately
     * so. Forcing the interleaving deterministically needs the reader to be
     * suspended *inside* its iteration and the writer released while it holds
     * there — but under the monitor the fix takes, that writer would block on
     * the reader, so such a test deadlocks against the fixed code instead of
     * passing. Measured failure rate against the unfixed accessors: 20/20
     * rounds threw `ConcurrentModificationException`, the first within a few
     * milliseconds of the writer starting. A null result here therefore bounds
     * the defect well below the rate it had, but does not prove its absence.
     */
    @Test
    fun `read accessors do not throw ConcurrentModificationException under a concurrent writer`() {
        val failures = mutableListOf<Throwable>()
        repeat(CONCURRENT_ROUNDS) { round ->
            val cell = OrMapCell<String, String>()
            repeat(SEEDED_KEYS) { cell.inlet.call.put("k$it", "v$it") }

            val stop = AtomicBoolean(false)
            val writerFailure = AtomicReference<Throwable?>(null)
            val writer = thread(name = "or-map-writer-$round") {
                var n = SEEDED_KEYS
                try {
                    while (!stop.get()) {
                        cell.inlet.call.put("k${n++}", "v")
                        if (n % 3 == 0) cell.inlet.call.remove("k${n % SEEDED_KEYS}")
                    }
                } catch (t: Throwable) {
                    writerFailure.set(t)
                }
            }
            try {
                repeat(READS_PER_ROUND) {
                    cell.membership()
                    cell.value("k1")
                    cell.state()
                    cell.snapshot()
                }
            } catch (t: Throwable) {
                failures += t
            } finally {
                stop.set(true)
                writer.join(10_000)
            }
            writerFailure.get()?.let { failures += it }
        }
        withMessage("read accessors threw under a concurrent writer: ${failures.map { it::class.java.name }}") {
            failures.shouldBeEmpty()
        }
    }

    private inline fun withMessage(clue: String, block: () -> Unit) =
        io.kotest.assertions.withClue(clue, block)

    private fun concurrentWriters(): Pair<OrMapCell<String, String>, OrMapCell<String, String>> =
        OrMapCell<String, String>(CellRef(UUID(1, 1))) to OrMapCell(CellRef(UUID(2, 2)))

    private companion object {
        const val WRITERS = 3

        /** Rounds of the concurrent-read regression; each round is a fresh cell. */
        const val CONCURRENT_ROUNDS = 20

        /** Keys seeded before the writer starts — enough that an iteration spans a write. */
        const val SEEDED_KEYS = 400

        /** Read passes per round, each touching all four public accessors. */
        const val READS_PER_ROUND = 200
    }
}
