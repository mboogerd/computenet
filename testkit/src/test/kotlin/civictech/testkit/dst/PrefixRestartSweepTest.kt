package civictech.testkit.dst

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CHA1-21] / BS-10: a host restarts from **any** journal prefix `k in 0..R`, and a sweep walks
 * the whole range.
 *
 * ## What "any prefix" is actually testing
 *
 * `k` is where a crash landed in the log. `k = R` is the ordinary restart every durability test
 * already does; `k = 0` is a host that comes back with nothing; every value between is a crash
 * that interrupted the write-ahead log mid-sequence — a frame journaled but its frontier advance
 * not, or the reverse. Those interior prefixes are the ones no hand-written test covers, because
 * writing one per `k` is not something anybody does by hand, and they are where record-ordering
 * assumptions break.
 *
 * ## Why the sweep is over prefixes and not over seeds
 *
 * `dstSweep` already walks seeds ([CHA1-38]). A prefix sweep walks a *different* axis on one
 * seed: the run is byte-identical apart from where recovery was cut off, so a `k` that fails is
 * a statement about the log's structure rather than about an interleaving. Mixing the two axes
 * into one sweep would make a failure ambiguous between them.
 */
class PrefixRestartSweepTest {

    /**
     * The census the sweep's range comes from, and the check that it is worth sweeping.
     *
     * A log with fewer than a handful of records makes "any prefix in `0..R`" a claim about three
     * cases, which is not a sweep; a log with no frontier advance makes [FrontierRollbackTest]
     * vacuous. Both are asserted here so a later change to the fixture that quietly shrinks the
     * log fails *this* test rather than silently weakening every test downstream of it.
     */
    @Test
    fun `the durable fixture writes a log worth sweeping`() {
        val graph = DurableEffectGraph("prefix-census")
        val census = journalRecordCount(graph.spec(), seed = 21L, journal = DurableEffectGraph.JOURNAL)

        assertTrue(census.records >= 8, "a prefix sweep needs a log with interior prefixes, got $census")
        assertTrue(census.frameRecords > 0, "expected journaled frames, got $census")
        assertTrue(census.frontierAdvances > 0, "expected Effectful frontier advances, got $census")
        assertTrue(census.allTagsKnown, "unknown record tag: $census")
    }

    /**
     * BS-10: the restart runs at **every** `k in 0..R`, and the sweep reports what each one did.
     *
     * The property under test is deliberately weak — that the restart *executes* and the run
     * quiesces at every prefix — because [CHA1-21] is about the rig's reach, not about the
     * kernel's recovery semantics. A stronger property (no effect lost, no effect doubled) is
     * exactly the [CHA1-22]/C-9 question the epic assigns to CHA2, and asserting it here would be
     * this task rendering the verdict its own non-goals forbid.
     *
     * What is asserted, then: every prefix has an entry, no prefix crashed the experiment, no
     * prefix exhausted the budget, and each `k`'s report names the prefix it restarted from.
     */
    @Test
    fun `BS-10 - a restart executes from every journal prefix k in 0 to R`() {
        val graph = DurableEffectGraph("prefix-restart-sweep")
        val census = journalRecordCount(graph.spec(), seed = 21L, journal = DurableEffectGraph.JOURNAL)

        val sweep = prefixRestartSweep(
            graph = graph.spec(),
            seed = 21L,
            host = DurableEffectGraph.HOST,
            journal = DurableEffectGraph.JOURNAL,
            records = census.records,
            atStep = graph.restartStep,
        )

        assertEquals(census.records + 1, sweep.total, "every k in 0..R inclusive: ${sweep.summary()}")
        assertEquals((0..census.records).toList(), sweep.entries.map { it.k })

        val broken = sweep.entries.filter { it.error != null }
        assertTrue(
            broken.isEmpty(),
            "a restart must be executable at every prefix; broken experiments at " +
                "k=${broken.map { it.k }}: ${broken.firstOrNull()?.message}",
        )
        assertTrue(
            sweep.exhausted.isEmpty(),
            "no prefix may leave the run unquiesced (no verdict would be claimable): " +
                "k=${sweep.exhausted.map { it.k }}",
        )

        // The restart fired at every k, and each report names its own prefix — which is how the
        // failing k reaches an artifact without DstReport or DstArtifact gaining a field.
        sweep.entries.forEach { entry ->
            val report = requireNotNull(entry.report) { "k=${entry.k} produced no report" }
            val applied = report.appliedFaults.single()
            assertTrue(applied.fired > 0, "the restart was inert at k=${entry.k}: ${report.summary()}")
            assertTrue(
                applied.description.contains("prefix=${entry.k} record(s)"),
                "k=${entry.k} is not named in its own report: ${applied.description}",
            )
            assertTrue(
                entry.recoveryTag != null,
                "k=${entry.k} traced no recovery outcome: ${report.trace.takeLast(3)}",
            )
        }
    }

    /**
     * [CHA1-21]'s structural half: a sweep report that does **not** cover its whole declared range
     * cannot be constructed.
     *
     * `DstSweepReport` enforces the same thing over seeds, for the reason the epic's own honesty
     * clause (§9 risk 8) gives: "never narrow a failing range" is a review rule, and the rig's job
     * is to make a narrowing *detectable*. Here it is not merely detectable, it is unconstructible
     * — which is the strongest form available without a verdict.
     */
    @Test
    fun `a prefix sweep report cannot be narrowed to the prefixes that passed`() {
        val full = (0..4).map { PrefixRestartEntry(it, null, null) }

        // The honest report constructs.
        PrefixRestartSweepReport("g", "h", "j", 1L, 4, full)

        val narrowed = full.filter { it.k != 2 }
        val failure = assertFailsWith<IllegalArgumentException> {
            PrefixRestartSweepReport("g", "h", "j", 1L, 4, narrowed)
        }
        assertTrue(
            failure.message!!.contains("[CHA1-21]"),
            "the refusal must name the requirement it protects: ${failure.message}",
        )

        // Out of order is a narrowing too: a sweep that re-mapped its range is not the range.
        assertFailsWith<IllegalArgumentException> {
            PrefixRestartSweepReport("g", "h", "j", 1L, 4, full.reversed())
        }
    }

    /**
     * BS-10's reporting half: when a prefix *does* fail its check, the failing `k` is in the
     * report — as a list of prefixes, in each failing entry's own [DstReport], and in the summary
     * line.
     *
     * The check here is deliberately one that a short prefix cannot satisfy: "the sink acted on
     * every value the source emitted". A restart from `k = 0` throws away the whole log, so the
     * frames journaled before the crash are never replayed and the effects are missing. That
     * makes the failure a property of `k` and nothing else, which is what lets this test assert
     * on *which* `k`s failed rather than merely that some did.
     */
    @Test
    fun `the failing prefix k is recorded in the sweep report`() {
        val graph = DurableEffectGraph("prefix-restart-failing-k", emits = 8)
        val census = journalRecordCount(graph.spec(), seed = 34L, journal = DurableEffectGraph.JOURNAL)

        val everyEmissionActedOn = DstCheck {
            val acted = graph.effects.toList()
            check(acted.size >= graph.emits) {
                "the sink acted on ${acted.size} of ${graph.emits} emissions: $acted"
            }
        }

        val sweep = prefixRestartSweep(
            graph = graph.spec(),
            seed = 34L,
            host = DurableEffectGraph.HOST,
            journal = DurableEffectGraph.JOURNAL,
            records = census.records,
            atStep = graph.restartStep,
            check = everyEmissionActedOn,
        )

        assertTrue(
            sweep.failingPrefixes.isNotEmpty(),
            "an empty-journal restart must lose effects, so some k must fail: ${sweep.summary()}",
        )
        assertTrue(
            0 in sweep.failingPrefixes,
            "k=0 discards the whole log and cannot satisfy the check: ${sweep.summary()}",
        )
        assertTrue(
            sweep.summary().contains("failing k="),
            "the summary line must name the failing prefixes: ${sweep.summary()}",
        )

        val firstFailure = sweep.failures.first()
        assertEquals(DstOutcome.FAILED, firstFailure.report?.outcome)
        assertTrue(
            firstFailure.report!!.appliedFaults.single().description.contains("prefix=${firstFailure.k}"),
            "the failing run's own report names its prefix: ${firstFailure.report!!.appliedFaults}",
        )

        val thrown = assertFailsWith<SweepFailure> { sweep.assertAllPassed() }
        assertTrue(
            thrown.detail.contains("k=${sweep.failingPrefixes.first()}"),
            "assertAllPassed must name the first failing prefix: ${thrown.detail}",
        )
        assertTrue(
            "k=" !in thrown.message!!,
            "…in the detail, not in the check's identity — see SweepFailure (computenet-umx.4): ${thrown.message}",
        )
    }

    /**
     * The two boundaries of the range, asserted for what they *are* rather than through the sweep:
     * `k = 0` offers recovery nothing, `k >= R` offers it the whole log.
     *
     * [PrefixJournal] tolerating `k > R` is load-bearing for the sweep: the census counts records
     * at the end of a fault-free run while the restart fires *inside* the traffic window, so the
     * top of the swept range is legitimately larger than the log at the moment of the restart.
     * Clamping would have been the wrong fix — it would have made the sweep's own range a lie.
     */
    @Test
    fun `a prefix view offers 0 records at k=0 and the whole log beyond R`() {
        val journal = civictech.cell.durability.InMemoryJournal()
        repeat(5) { journal.append(byteArrayOf(it.toByte())) }

        assertEquals(0, PrefixJournal(journal, 0).replay().size)
        assertEquals(3, PrefixJournal(journal, 3).replay().size)
        assertEquals(5, PrefixJournal(journal, 5).replay().size)
        assertEquals(5, PrefixJournal(journal, 99).replay().size, "k beyond R is the whole log, not an error")
        assertEquals(5, PrefixJournal(journal, 99).total(), "total() always reports the real log length")

        // Read-only: a restart view is handed to recoverFrom and to nothing else.
        assertFailsWith<UnsupportedOperationException> { PrefixJournal(journal, 2).append(byteArrayOf(9)) }
        assertFailsWith<UnsupportedOperationException> { PrefixJournal(journal, 2).reset(emptyList()) }
    }
}
