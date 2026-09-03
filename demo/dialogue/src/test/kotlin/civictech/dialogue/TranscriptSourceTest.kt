package civictech.dialogue

import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.SetView
import civictech.cell.graph.lookupOrThrow
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.dialogue.extract.RuleExtractor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transcript drive: ingress admission, turn ordering, range replay,
 * step/reset and pacing. Tests are named for the BS/requirement ids they
 * realize (epic computenet-2aw §3.1, §4).
 *
 * Every assertion is made against what the *graph* observed — the ingress
 * outlet's delta stream folded through [SetView] — not merely against the
 * driver's own bookkeeping, so a driver that reported an admission it never
 * made would fail here.
 */
class TranscriptSourceTest {

    /**
     * One host, one pipeline, and an observation sink on the ingress outlet.
     *
     * The scheduler is a [SimulationController]: admissions are driven
     * deterministically and [quiesce] runs the graph to idle, so no test
     * here depends on scheduling timing.
     */
    private class Rig(seed: Long = 1L) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        // F2 made the extractor an explicit build parameter; this suite
        // exercises the ingress alone, so it runs under the pure,
        // zero-dependency RuleExtractor.
        val refs = DialoguePipeline.build(host, RuleExtractor).refs

        /** Membership as the graph's consumers see it. */
        val view = SetView<Utterance>()

        /** Deltas the ingress outlet propagated, in order. */
        val deltas = mutableListOf<SetDelta<Utterance>>()

        /** Deltas that effectively changed membership — the effective adds/removes. */
        var effectiveChanges = 0
            private set

        init {
            host.lookupOrThrow(refs.utterances).outlet.subscribe(
                Use.fixed(
                    Propagate<SetDelta<Utterance>> { delta ->
                        deltas += delta
                        if (view.apply(delta)) effectiveChanges++
                    },
                    PortRef.generate(),
                ),
            )
        }

        val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(host, refs)

        fun source(transcript: List<Utterance> = emptyList(), sleeper: (Long) -> Unit = { }) =
            TranscriptSource(ops, transcript, sleeper)

        fun quiesce() = controller.runToIdle()

        /** Admitted membership, in turn order, as ids. */
        fun observedIds(): List<String> = view.current().sortedBy { it.turn }.map { it.id }

        /** How many propagated deltas carried an add for [id]. */
        fun addsFor(id: String): Int = deltas.count { delta -> delta.adds.keys.any { it.id == id } }
    }

    private fun fixture(name: String): List<Utterance> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/$name")) {
            "test resource not found: $name"
        }
        val result = TranscriptLoader.load(stream.bufferedReader())
        assertEquals(0, result.report.rejectedCount, "fixture $name should parse cleanly")
        return result.utterances
    }

    private fun forty(): List<Utterance> = fixture("forty-utterances.jsonl").also {
        assertEquals(40, it.size, "the SRC-06 fixture is 40 utterances")
    }

    @Test
    fun `AGO1-SRC-02 - a second admission of the same utterance produces exactly one effective add`() {
        val rig = Rig()
        val source = rig.source()
        val u = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1000, text = "Good morning.")

        assertTrue(source.offer(u), "first admission is admitted")
        rig.quiesce()

        assertFalse(source.offer(u), "an identical re-admission is a no-op, not an admission")
        rig.quiesce()

        assertEquals(1, rig.addsFor("u1"), "exactly one delta carried an add for u1")
        assertEquals(1, rig.effectiveChanges, "exactly one effective membership change reached the graph")
        assertEquals(setOf(u), rig.view.current())
        assertEquals(listOf(u), source.admitted)
    }

    @Test
    fun `computenet-gkol - an offer that reuses an admitted id with different content is rejected with a named error`() {
        val rig = Rig()
        val source = rig.source()
        val first = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1000, text = "first")

        assertTrue(source.offer(first), "first admission is admitted")
        rig.quiesce()

        val before = rig.view.current()
        val deltasBefore = rig.deltas.size
        val admittedBefore = source.admitted

        // Same id, different content, strictly greater turn: passes the
        // SRC-02 identical-content dedup and the SRC-03/04 turn-order rule,
        // so only an id-uniqueness check can catch it.
        val conflicting = Utterance(id = "u1", turn = 2, speaker = "bob", tsMillis = 1500, text = "DIFFERENT")
        val failure = assertFailsWith<DuplicateUtteranceIdException> { source.offer(conflicting) }

        assertEquals("u1", failure.utteranceId)
        assertEquals(first, failure.admitted)
        assertEquals(conflicting, failure.offered)

        rig.quiesce()
        assertEquals(before, rig.view.current(), "the admitted set is unchanged by the rejection")
        assertEquals(1, before.size)
        assertEquals(deltasBefore, rig.deltas.size, "the rejection propagated no delta at all")
        assertEquals(admittedBefore, source.admitted)
    }

    @Test
    fun `AGO1-SRC-03 - the source admits from an incremental feed without the full transcript up front`() {
        val rig = Rig()
        // No transcript given to the driver at all: utterances arrive one at
        // a time, as they would from a stream that has not ended.
        val source = rig.source()

        val fed = (1..5).map {
            Utterance(id = "u$it", turn = it, speaker = "alice", tsMillis = 1000L + 100 * it, text = "turn $it")
        }
        fed.forEach { utterance ->
            assertTrue(source.offer(utterance), "${utterance.id} admitted")
            rig.quiesce()
            // Observable after each single offer — nothing waits on the rest.
            assertEquals(utterance.turn, rig.view.current().size)
        }

        assertEquals(fed.map { it.id }, rig.observedIds())
        assertEquals(5, source.lastAdmittedTurn)
    }

    @Test
    fun `BS-17 AGO1-SRC-04 - a turn that does not advance is rejected with a named error and the admitted set is unchanged`() {
        val rig = Rig()
        val transcript = forty()
        val source = rig.source(transcript)

        source.replay(from = 1, to = 7)
        rig.quiesce()

        val before = rig.view.current()
        val deltasBefore = rig.deltas.size
        val admittedBefore = source.admitted
        assertEquals(7, source.lastAdmittedTurn)

        val turnFive = transcript.single { it.turn == 5 }
        // Offered with fresh content so this is not the SRC-02 duplicate case:
        // it is a genuinely out-of-order turn.
        val offered = turnFive.copy(id = "late-5", text = "an utterance arriving out of order")
        val failure = assertFailsWith<OutOfOrderTurnException> { source.offer(offered) }

        assertEquals("late-5", failure.utteranceId)
        assertEquals(5, failure.offeredTurn)
        assertEquals(7, failure.lastAdmittedTurn)
        assertContains(failure.message!!, "turn 5")
        assertContains(failure.message!!, "7")

        rig.quiesce()
        assertEquals(before, rig.view.current(), "the admitted set is unchanged by the rejection")
        assertEquals(deltasBefore, rig.deltas.size, "the rejection propagated no delta at all")
        assertEquals(admittedBefore, source.admitted)
        assertEquals(7, source.lastAdmittedTurn)
    }

    @Test
    fun `AGO1-SRC-06 - replay(from=10, to=19) admits exactly turns 10 to 19, in order`() {
        val rig = Rig()
        val source = rig.source(forty())

        source.replay(from = 10, to = 19)
        rig.quiesce()

        val expected = (10..19).map { "u$it" }
        assertEquals(expected, source.admitted.map { it.id }, "admitted in ascending turn order")
        assertEquals(expected, rig.observedIds(), "the graph observed exactly those ten utterances")
        assertEquals(10, rig.effectiveChanges)
    }

    @Test
    fun `BS-21 AGO1-SRC-07 - a max-pace replay and a wall-clock replay admit identical sets`() {
        val transcript = forty()

        val fast = Rig(seed = 7L)
        val fastSource = fast.source(transcript)
        fastSource.replay(from = 1, to = 20, pace = Pace.AsFastAsPossible)
        fast.quiesce()

        // A real sleeping driver, at a factor that keeps the wait bounded:
        // the fixture's utterances are 500ms apart in event time, so factor
        // 500 spends ~1ms per admission of genuine wall-clock time.
        val paced = Rig(seed = 7L)
        val pacedSource = TranscriptSource(paced.ops, transcript)
        pacedSource.replay(from = 1, to = 20, pace = Pace.Wallclock(factor = 500.0))
        paced.quiesce()

        assertEquals(fast.view.current(), paced.view.current(), "pacing changed the admitted set")
        assertEquals(fastSource.admitted, pacedSource.admitted, "pacing changed the admission order")
        assertEquals((1..20).map { "u$it" }, paced.observedIds())
        assertEquals(fast.effectiveChanges, paced.effectiveChanges)
    }

    @Test
    fun `Pace_Wallclock waits in proportion to event-time gaps divided by the factor`() {
        val rig = Rig()
        val waits = mutableListOf<Long>()
        val source = TranscriptSource(rig.ops, forty(), sleeper = { waits += it })

        source.replay(from = 1, to = 5, pace = Pace.Wallclock(factor = 10.0))
        rig.quiesce()

        // Four gaps between five admissions; the fixture spaces tsMillis by
        // 500, so each wait is 500/10 = 50. The first admission never waits.
        assertEquals(listOf(50L, 50L, 50L, 50L), waits)
        assertEquals(5, rig.view.current().size)
    }

    @Test
    fun `step admits the transcript one utterance at a time and stops at the end`() {
        val rig = Rig()
        val source = rig.source(forty().take(3))

        assertEquals("u1", source.step()?.id)
        assertEquals("u2", source.step()?.id)
        assertEquals("u3", source.step()?.id)
        assertEquals(null, source.step(), "the transcript is exhausted")
        rig.quiesce()

        assertEquals(listOf("u1", "u2", "u3"), rig.observedIds())
    }

    @Test
    fun `reset retracts every admitted utterance and lets a replay restart from any turn`() {
        val rig = Rig()
        val source = rig.source(forty())

        source.replay(from = 10, to = 19)
        rig.quiesce()
        assertEquals(10, rig.view.current().size)

        source.reset()
        rig.quiesce()
        assertEquals(emptySet(), rig.view.current(), "reset empties the admitted set")
        assertEquals(emptyList(), source.admitted)
        assertEquals(null, source.lastAdmittedTurn)

        // A turn already used before the reset is admissible again.
        source.replay(from = 5, to = 8)
        rig.quiesce()
        assertEquals((5..8).map { "u$it" }, rig.observedIds())
    }

    // ------------------------------------------------------------------
    // computenet-2aw.4.1 — TranscriptSource(recovered = ...) seeding
    // ([AGO1-DUR-01] "admitted-utterance set recovers")
    // ------------------------------------------------------------------

    /** Counts calls so a seeded ledger can be shown to make ZERO of them. */
    private class CountingSetOps<E> : SetOps<E> {
        var addCalls = 0
            private set
        var removeCalls = 0
            private set
        val added = mutableListOf<E>()
        val removed = mutableListOf<E>()

        override fun add(element: E) {
            addCalls++
            added += element
        }

        override fun remove(element: E) {
            removeCalls++
            removed += element
        }
    }

    @Test
    fun `computenet-2aw_4_1 - a source constructed with recovered utterances seeds its ledger with zero ops calls`() {
        val ops = CountingSetOps<Utterance>()
        val u1 = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1000, text = "first")
        val u2 = Utterance(id = "u2", turn = 2, speaker = "bob", tsMillis = 2000, text = "second")

        val source = TranscriptSource(ops, recovered = listOf(u2, u1))

        assertEquals(listOf(u1, u2), source.admitted, "recovered utterances are sorted into turn order")
        assertEquals(2, source.lastAdmittedTurn)
        assertEquals(0, ops.addCalls, "seeding must not call SetOps.add — the cell already holds these")
        assertEquals(0, ops.removeCalls)
    }

    @Test
    fun `computenet-2aw_4_1 - offer of an already-recovered utterance is a no-op with zero ops calls`() {
        val ops = CountingSetOps<Utterance>()
        val u1 = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1000, text = "first")
        val u2 = Utterance(id = "u2", turn = 2, speaker = "bob", tsMillis = 2000, text = "second")
        val source = TranscriptSource(ops, recovered = listOf(u1, u2))

        assertFalse(source.offer(u2), "re-offering an already-recovered utterance is a no-op")
        assertEquals(0, ops.addCalls)
        assertEquals(0, ops.removeCalls)
        assertEquals(listOf(u1, u2), source.admitted, "the ledger is unchanged by the no-op")
    }

    @Test
    fun `computenet-2aw_4_1 - offer of a new id at a recovered turn throws OutOfOrderTurnException`() {
        val ops = CountingSetOps<Utterance>()
        val u1 = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1000, text = "first")
        val u2 = Utterance(id = "u2", turn = 2, speaker = "bob", tsMillis = 2000, text = "second")
        val source = TranscriptSource(ops, recovered = listOf(u1, u2))

        val newAtTurn2 = Utterance(id = "u3", turn = 2, speaker = "carol", tsMillis = 2500, text = "late")
        val failure = assertFailsWith<OutOfOrderTurnException> { source.offer(newAtTurn2) }

        assertEquals("u3", failure.utteranceId)
        assertEquals(2, failure.offeredTurn)
        assertEquals(2, failure.lastAdmittedTurn)
        assertEquals(0, ops.addCalls, "the rejection made no ops call")
        assertEquals(0, ops.removeCalls)
    }

    @Test
    fun `computenet-2aw_4_1 - reset on a recovered source retracts every recovered utterance, latest first`() {
        val ops = CountingSetOps<Utterance>()
        val u1 = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1000, text = "first")
        val u2 = Utterance(id = "u2", turn = 2, speaker = "bob", tsMillis = 2000, text = "second")
        val source = TranscriptSource(ops, recovered = listOf(u1, u2))

        source.reset()

        assertEquals(listOf(u2, u1), ops.removed, "reset retracts in reverse admission order: u2 then u1")
        assertEquals(0, ops.addCalls)
        assertEquals(2, ops.removeCalls)
        assertEquals(emptyList(), source.admitted)
        assertEquals(null, source.lastAdmittedTurn)
    }

    @Test
    fun `computenet-2aw_4_1 - the cursor resumes past recovered turns present in the transcript`() {
        val transcript = forty()
        val recoveredThroughTurn7 = transcript.filter { it.turn <= 7 }
        val ops = CountingSetOps<Utterance>()
        val source = TranscriptSource(ops, transcript, recovered = recoveredThroughTurn7)

        assertEquals(7, source.lastAdmittedTurn)
        assertEquals(0, ops.addCalls, "recovery seeded the ledger without any ops call")

        // step() reads from the cursor, which must sit past turn 7.
        assertEquals("u8", source.step()?.id)
        assertEquals(1, ops.addCalls, "the first live step after recovery makes exactly one ops call")
    }
}
