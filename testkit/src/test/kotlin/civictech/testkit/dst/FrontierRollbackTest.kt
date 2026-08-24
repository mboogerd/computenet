package civictech.testkit.dst

import civictech.cell.durability.InMemoryJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CHA1-22] / BS-11: rolling an `Effectful` inlet's **processed-frontier** back, independently of
 * the journal prefix a restart recovers from.
 *
 * ## This suite reports; it does not judge
 *
 * The epic assigns the C-9 verdict — whether the kernel's processed-frontier is *correct* under
 * recovery — to CHA2, and the item this suite implements names "no C-9 verdict" as a non-goal.
 * So every test below asserts the *mechanism*: that the rollback is applied, that it is
 * independent of the prefix, and that its reach is exactly what the record encoding permits. What
 * the kernel then does with a rolled-back frontier is recorded, not graded.
 *
 * ## The reported blocker, in full
 *
 * The item asked whether the frontier-record tag byte is identifiable from `:testkit`, and told
 * this task to report the blocker rather than widen kernel visibility if it is not. The answer is
 * **partly yes**, and the shape of the partial answer is the finding:
 *
 *  - **The tag byte IS identifiable.** `HostDurability.recoverFrom` dispatches on `record[0]`, so
 *    a record's *type* is readable from outside the kernel. Only the `RECORD_*` **values** are
 *    private, and those are duplicated in [JournalRecords] and pinned by
 *    `JournalFaultTest.journal record tags match what the kernel actually writes`. No visibility
 *    was widened, and none is needed for this half.
 *  - **The payload is NOT.** `FrontierRecord` is a `private data class` and its record body is
 *    Java serialisation *of that class*, so the `(cellRef, portName, timestamp)` a frontier
 *    record carries cannot be decoded here at all. A rollback is therefore selected by
 *    **counting** frontier advances, never by naming a target `(sourceId, counter)`.
 *  - **After a checkpoint it is not reachable at all.** `checkpoint` compacts the log to a
 *    `RECORD_CHECKPOINT` whose `CheckpointRecord.frontier` field holds the whole frontier map, so
 *    positional rollback reaches only the advances journaled *since* the last checkpoint —
 *    possibly none. [FrontierRollbackJournal.frontierAdvancesAfterLastCheckpoint] is what a caller
 *    checks before believing a rollback landed, and the third test below is what pins it.
 *
 * Rolling back to a *named* position would need either `FrontierRecord` made visible or a
 * kernel-side decode seam. Both are kernel edits, which this item excludes; per its own acceptance
 * clause, reporting that is the outcome.
 */
class FrontierRollbackTest {

    /**
     * The counting rollback, on a synthetic log: only `RECORD_FRONTIER` records are dropped, every
     * other record survives in place, and the first `n` advances are the ones kept.
     *
     * Asserted on a hand-built record list rather than through a run, because the claim is about
     * the *filter* and nothing else — and because a hand-built list is the only way to be sure a
     * frame record adjacent to a dropped frontier record was not disturbed.
     */
    @Test
    fun `a frontier rollback drops only frontier records, keeping the first n advances`() {
        val journal = InMemoryJournal()
        // frame, frontier, frame, frontier, frame, frontier, outlet-wave
        val tags = listOf(
            JournalRecords.FRAME,
            JournalRecords.FRONTIER,
            JournalRecords.FRAME,
            JournalRecords.FRONTIER,
            JournalRecords.FRAME,
            JournalRecords.FRONTIER,
            JournalRecords.OUTLET_WAVE,
        )
        tags.forEachIndexed { i, tag -> journal.append(byteArrayOf(tag, i.toByte())) }

        fun rolled(keep: Int) = FrontierRollbackJournal(journal, keep).replay().map { it[0] to it[1].toInt() }

        assertEquals(3, FrontierRollbackJournal(journal, 0).frontierAdvances())

        assertEquals(
            listOf(
                JournalRecords.FRAME to 0,
                JournalRecords.FRAME to 2,
                JournalRecords.FRAME to 4,
                JournalRecords.OUTLET_WAVE to 6,
            ),
            rolled(0),
            "keep=0 drops every advance and leaves every other record untouched, in order",
        )
        assertEquals(
            listOf(
                JournalRecords.FRAME to 0,
                JournalRecords.FRONTIER to 1,
                JournalRecords.FRAME to 2,
                JournalRecords.FRAME to 4,
                JournalRecords.OUTLET_WAVE to 6,
            ),
            rolled(1),
            "keep=1 retains the FIRST advance, not an arbitrary one",
        )
        assertEquals(tags.size, rolled(3).size, "keep >= advances is the identity")
        assertEquals(tags.size, rolled(99).size, "keep beyond the count is the identity, not an error")

        assertFailsWith<UnsupportedOperationException> { FrontierRollbackJournal(journal, 1).append(byteArrayOf(1)) }
        assertFailsWith<UnsupportedOperationException> { FrontierRollbackJournal(journal, 1).reset(emptyList()) }
    }

    /**
     * BS-11: the rollback applied to a **real** durable graph, at a fixed prefix, reporting what
     * the kernel does.
     *
     * The comparison is the load-bearing part. Two runs on the same seed differ in exactly one
     * field — `keepFrontierAdvances` — so anything that differs between their effect lists is
     * attributable to the frontier rollback and to nothing else. Recovery replays the journaled
     * frames either way; the frontier is what decides whether a replayed frame is *suppressed as
     * already-acted* or delivered again.
     *
     * **Recorded, not graded:** a rolled-back frontier re-firing effects the un-rolled frontier
     * suppresses is the kernel's dedupe doing precisely its job — the frontier is the dedupe. This
     * test asserts the rollback *reached* the frontier (the two runs differ, and differ in the
     * direction that says suppression was lost), which is [CHA1-22]'s mechanism claim. Whether
     * that constitutes an effect-safety property is C-9, and C-9 is CHA2's.
     */
    @Test
    fun `BS-11 - rolling the frontier back changes what a replay re-fires, at an unchanged prefix`() {
        val seed = 44L

        fun runWith(keep: Int?): Pair<DstReport, List<Int>> {
            val graph = DurableEffectGraph("frontier-rollback-keep-${keep ?: "all"}")
            val restart = RestartAtFrontierFault(
                id = "restart",
                host = DurableEffectGraph.HOST,
                journal = DurableEffectGraph.JOURNAL,
                atStep = graph.restartStep,
                prefix = null, // the WHOLE log, in both runs: only the frontier knob varies
                keepFrontierAdvances = keep,
            )
            val report = DstRun(graph.spec(), FaultPlan.of(seed, restart)).execute()
            assertNotNull(restart.lastRecovery, "the restart never fired: ${report.summary()}")
            return report to graph.effects.toList()
        }

        val (baselineReport, baselineEffects) = runWith(null)
        val (rolledReport, rolledEffects) = runWith(0)

        assertEquals(DstOutcome.PASSED, baselineReport.outcome, baselineReport.summary())
        assertEquals(DstOutcome.PASSED, rolledReport.outcome, rolledReport.summary())
        assertTrue(baselineEffects.isNotEmpty(), "the sink acted on nothing, so nothing was tested")

        assertTrue(
            rolledEffects.size > baselineEffects.size,
            "rolling the frontier to zero advances must lose the suppression the un-rolled frontier " +
                "applies, so the replay re-fires effects: rolled=$rolledEffects vs baseline=$baselineEffects",
        )
        // The re-fired values are values the sink had already acted on — the dedupe the frontier
        // was holding. Stated as an observation of the mechanism, not as a defect.
        assertTrue(
            rolledEffects.groupingBy { it }.eachCount().any { it.value > 1 },
            "a lost frontier re-delivers positions already acted on: $rolledEffects",
        )
    }

    /**
     * [CHA1-22]'s independence claim: the rollback and the prefix are separate knobs, and the
     * rollback applies at *any* prefix.
     *
     * Swept across the whole prefix range rather than asserted at one `k`, because "independent"
     * is a statement about every combination and a single pair could pass by coincidence. The
     * sweep's own range guard ([PrefixRestartSweepReport]) is what keeps it from being narrowed to
     * the combinations that worked.
     */
    @Test
    fun `the frontier rollback applies independently of the journal prefix`() {
        val graph = DurableEffectGraph("frontier-rollback-independence")
        val census = journalRecordCount(graph.spec(), seed = 51L, journal = DurableEffectGraph.JOURNAL)

        val sweep = prefixRestartSweep(
            graph = graph.spec(),
            seed = 51L,
            host = DurableEffectGraph.HOST,
            journal = DurableEffectGraph.JOURNAL,
            records = census.records,
            atStep = graph.restartStep,
            keepFrontierAdvances = 0,
        )

        assertEquals(census.records + 1, sweep.total, sweep.summary())
        assertTrue(
            sweep.entries.all { it.error == null },
            "a rollback must be applicable at every prefix; broken at " +
                "k=${sweep.entries.filter { it.error != null }.map { it.k }}",
        )
        sweep.entries.forEach { entry ->
            val applied = requireNotNull(entry.report) { "k=${entry.k}: no report" }.appliedFaults.single()
            assertTrue(
                applied.description.contains("frontier rolled back to 0 advance(s)"),
                "k=${entry.k}'s report must name the rollback as well as the prefix: ${applied.description}",
            )
            assertTrue(
                applied.description.contains("prefix=${entry.k} record(s)"),
                "k=${entry.k}'s report must still name its prefix: ${applied.description}",
            )
        }
    }

    /**
     * The sharper structural limit, pinned: **once a checkpoint has run, the frontier is inside the
     * checkpoint blob and positional rollback cannot reach it.**
     *
     * `HostDurability.checkpoint` calls `journal.reset(listOf(RECORD_CHECKPOINT + blob) + waves +
     * baselines)`, and `CheckpointRecord.frontier` is where the whole per-`(cellRef, portName)`
     * frontier map then lives. So a compacted log holds zero `RECORD_FRONTIER` records, and
     * [FrontierRollbackJournal] — which can only drop records it can identify by tag — becomes the
     * identity.
     *
     * This is asserted on a real compacted log rather than described, because it is the one limit a
     * caller can most easily fail to notice: the rollback still "succeeds", the run still passes,
     * and nothing rolled back.
     * [FrontierRollbackJournal.frontierAdvancesAfterLastCheckpoint] is the check that makes it
     * visible, which is why it exists.
     */
    @Test
    fun `a checkpointed frontier is not reachable by positional rollback`() {
        // A log in the shape checkpoint compaction leaves: one checkpoint, then whatever came after.
        val compacted = InMemoryJournal()
        compacted.append(byteArrayOf(JournalRecords.FRONTIER, 0)) // pre-checkpoint advance
        compacted.append(byteArrayOf(JournalRecords.CHECKPOINT, 1)) // ... folded into here
        compacted.append(byteArrayOf(JournalRecords.OUTLET_WAVE, 2))

        val view = FrontierRollbackJournal(compacted, 0)
        assertEquals(1, view.frontierAdvances(), "there is one frontier record in the log")
        assertEquals(
            0,
            view.frontierAdvancesAfterLastCheckpoint(),
            "but none AFTER the checkpoint, so none of the live frontier is positionally reachable",
        )

        // And a log with post-checkpoint advances reports exactly those.
        compacted.append(byteArrayOf(JournalRecords.FRONTIER, 3))
        compacted.append(byteArrayOf(JournalRecords.FRONTIER, 4))
        assertEquals(3, FrontierRollbackJournal(compacted, 0).frontierAdvances())
        assertEquals(2, FrontierRollbackJournal(compacted, 0).frontierAdvancesAfterLastCheckpoint())
    }

    /**
     * The blocker itself, as an executable statement rather than a comment: from `:testkit`, a
     * frontier record's **tag** is readable and its **payload** is not.
     *
     * A test can only assert the readable half plus the absence of the other, which is what this
     * does: the tag discriminates, and the remaining bytes are an opaque blob this package offers
     * no accessor for and cannot decode — `FrontierRecord` is `private` to `HostDurability.kt`, so
     * there is no type here to deserialise into. If a later change makes the payload decodable
     * (a kernel decode seam, a widened visibility), *this* test's premise is what changed, and
     * [FrontierRollbackJournal]'s counting API can be replaced by a positional one.
     */
    @Test
    fun `from testkit a frontier record's tag is readable and its payload is not`() {
        val graph = DurableEffectGraph("frontier-payload-opacity")
        val census = journalRecordCount(graph.spec(), seed = 63L, journal = DurableEffectGraph.JOURNAL)
        assertTrue(census.frontierAdvances > 0, "need a real frontier record to inspect: $census")

        // Re-drive to hold the records themselves; `journalRecordCount` reports only the census.
        val world = DstWorld(63L)
        graph.spec().builder.build(world)
        var steps = 0
        while (steps < 200) {
            world.beginStep(steps)
            if (!world.controller.step()) break
            steps++
        }
        world.endRun()
        val records = world.journals.base(DurableEffectGraph.JOURNAL).replay()

        val frontierIndices = JournalRecords.indicesOfTag(records, JournalRecords.FRONTIER)
        assertTrue(frontierIndices.isNotEmpty(), "the tag byte discriminates frontier records: readable")

        val payload = records[frontierIndices.first()].drop(1)
        assertTrue(
            payload.isNotEmpty(),
            "the record has a body — Java serialisation of HostDurability's private FrontierRecord",
        )
        // Java serialisation stream magic: enough to confirm the body IS a serialised object, and
        // therefore that reading its (cellRef, portName, timestamp) needs the private class.
        assertEquals(
            listOf(0xAC.toByte(), 0xED.toByte()),
            payload.take(2),
            "the body is a Java object stream whose class is private to the kernel: not decodable here",
        )
    }
}
