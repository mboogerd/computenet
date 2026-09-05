package civictech.testkit.dst

import civictech.cell.CellRef
import civictech.cell.host.DeadLetter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Registers the fixture graph and check under stable ids.
 *
 * A top-level `object` rather than a companion because [DstReplayCli] resolves a registrar by
 * `Class.forName(name, true, loader)`, which runs *this* class's initialiser — a companion's
 * initialiser is not run by loading its outer class, so a companion would register nothing in
 * the replay JVM and the [CHA1-51] test would prove the opposite of what it claims.
 */
object ReportFixtures {

    const val GRAPH_ID: String = "dst-selftest-report"
    const val CHECK_ID: String = "dst-selftest-report-check"
    const val SUITE: String = "dst-selftest-report"

    /**
     * The failing check. Its message is a constant: no counts, no step, no ids.
     *
     * That is the AMENDS obligation from computenet-umx.3.7's review made concrete —
     * `FailurePredicate.sameFailingCheck` compares this string, so anything run-varying in it
     * would make a legitimately shrunk plan read as a different failure.
     */
    const val CHECK_MESSAGE: String = "fixture check fails by construction"

    init {
        GraphRegistry.register(GRAPH_ID, SelfTestGraphs.crossTalk(chains = 2, rounds = 2).builder)
        CheckRegistry.register(CHECK_ID) { throw AssertionError(CHECK_MESSAGE) }
    }

    fun plan(seed: Long): FaultPlan = FaultPlan.of(
        seed,
        // Fires: the window opens at step 0 on an edge the graph uses from the first step.
        PartitionFault.drop("drop-ab", "a->b", from = 0, until = 3),
        // Never fires: the window opens long after this graph has quiesced (BS-13's inert half).
        PartitionFault.drop("drop-ba-late", "b->a", from = 100_000),
    )

    fun run(seed: Long): DstRun = DstRun(GraphRegistry.require(GRAPH_ID), plan(seed), 500, CheckRegistry.require(CHECK_ID))
}

/**
 * [CHA1-50], [CHA1-51], [CHA1-52] and BS-13's rendering half — the human-readable failure
 * report, its replay command line, and dead-letter accounting by reason.
 *
 * The standard this suite holds the report to is the epic's own: **every line is traceable to
 * something the run observed**. The rig's history is why — it once labelled a drop-mode
 * partition as parking and replaying traffic that it in fact destroyed (computenet-cstu). So
 * the assertions here are about provenance, not prose: the fault line quotes the fault's own
 * `describe()`, the inert marker comes from the measured firing count, and the replay command
 * is checked by *executing it in a fresh JVM* rather than by matching its text.
 */
class ReportFormatTest {

    // ------------------------------------------------------------------ [CHA1-50] the report

    /** Every field epic §2.3 names, from one real failing run. */
    @Test
    fun failureReportNamesSuiteSeedStepPlanCheckDeadLettersAndArtifact_CHA1_50() {
        val run = ReportFixtures.run(seed = 11)
        val report = run.execute()
        assertEquals(DstOutcome.FAILED, report.outcome, "the fixture must fail for this test to mean anything")

        val artifact = DstArtifacts.write(
            DstArtifact.of(run, report, suite = ReportFixtures.SUITE, checkId = ReportFixtures.CHECK_ID),
            root,
        )
        val rendered = FailureReport.of(
            report,
            suite = ReportFixtures.SUITE,
            artifact = artifact,
            registrars = listOf(ReportFixtures::class.java.name),
        ).render()

        assertTrue("DST FAILED — ${ReportFixtures.SUITE}" in rendered, rendered)
        assertTrue("seed            11" in rendered, rendered)
        assertTrue("graph           ${ReportFixtures.GRAPH_ID}" in rendered, rendered)
        assertTrue("steps           ${report.steps} of 500 (budget)" in rendered, rendered)
        assertTrue("step at failure ${report.failingCheck!!.step}" in rendered, rendered)
        assertTrue("failing check   ${ReportFixtures.CHECK_MESSAGE}" in rendered, rendered)
        assertTrue("dead letters    0" in rendered, rendered)
        assertTrue("artifact        ${artifact.absolutePath}" in rendered, rendered)
        assertTrue("replay:" in rendered, rendered)
    }

    /**
     * [CHA1-50]'s "applied plan with activation steps": each line is the fault's own
     * `describe()` plus its **measured** firing count and steps — never the configured window.
     *
     * The distinction is the point. `drop-ab` is configured for steps 0..<3; what the line
     * asserts is the steps at which the rig recorded it firing, which is what
     * [AppliedFault.activationSteps] holds.
     */
    @Test
    fun planLinesCarryEachFaultsOwnDescriptionAndItsMeasuredActivationSteps_CHA1_50() {
        val report = ReportFixtures.run(seed = 12).execute()
        val rendered = FailureReport.of(report, suite = ReportFixtures.SUITE).render()

        val fired = report.appliedFaults.single { it.id == "drop-ab" }
        assertTrue(fired.fired > 0, "the fixture's drop fault must fire: ${report.summary()}")
        assertTrue(
            "[fired=${fired.fired} @ steps ${fired.activationSteps.joinToString(",")}] drop-ab: ${fired.description}"
                in rendered,
            rendered,
        )
        // Provenance: the description in the report IS the fault's describe(), not a paraphrase.
        assertEquals(PartitionFault.drop("drop-ab", "a->b", from = 0, until = 3).describe(), fired.description)
    }

    /** BS-13's rendering half: a never-fired fault renders as inert with `fired=0`. */
    @Test
    fun aNeverFiredFaultRendersAsInertWithFiredZero_BS13() {
        val report = ReportFixtures.run(seed = 13).execute()
        val inert = report.appliedFaults.single { it.id == "drop-ba-late" }
        assertEquals(0, inert.fired, "the late-window fault must not fire for BS-13 to mean anything")

        val rendered = FailureReport.of(report, suite = ReportFixtures.SUITE).render()
        assertTrue("[INERT fired=0] drop-ba-late:" in rendered, rendered)
        assertTrue("1 INERT" in rendered, "the plan header must not hide an inert fault:\n$rendered")
    }

    /** A shrunk plan renders as `N -> M`, and says it is only locally minimal. */
    @Test
    fun aShrunkPlanRendersItsBeforeAndAfterFaultCount_CHA1_50() {
        val run = ReportFixtures.run(seed = 14)
        val report = run.execute()
        val artifact = DstArtifact.of(run, report, suite = ReportFixtures.SUITE, checkId = ReportFixtures.CHECK_ID)
        val shrunk = artifact.plan().without("drop-ba-late")
        val result = ShrinkResult(
            artifact = artifact,
            plan = shrunk,
            record = ShrinkRecord(attempts = 2, reductionsAccepted = 1, stoppedEarly = false, stopReason = null),
            trail = emptyList(),
        )

        val rendered = FailureReport.ofShrunk(report, result).render()
        assertTrue("shrunk 2 -> 1" in rendered, rendered)
        assertTrue("locally minimal under the strategy" in rendered, rendered)
    }

    /** Expected/actual are rendered when — and only when — the failure carries them structurally. */
    @Test
    fun expectedAndActualComeFromTheAssertionNotFromItsText_CHA1_50() {
        val structured = reportWithFailure(AssertionFailedError("counts differ", 30, 18))
        val renderedStructured = FailureReport.of(structured, suite = ReportFixtures.SUITE).render()
        assertTrue("expected  30" in renderedStructured, renderedStructured)
        assertTrue("actual    18" in renderedStructured, renderedStructured)

        val bare = reportWithFailure(AssertionError("counts differ"))
        val renderedBare = FailureReport.of(bare, suite = ReportFixtures.SUITE).render()
        assertFalse("expected" in renderedBare, "a bare AssertionError has no expected/actual to render:\n$renderedBare")
    }

    /** A run with no artifact says so, and renders no command that would not work. */
    @Test
    fun withoutAnArtifactTheReportSaysThereIsNoReplayCommand_CHA1_51() {
        val report = ReportFixtures.run(seed = 15).execute()
        val rendered = FailureReport.of(report, suite = ReportFixtures.SUITE).render()
        assertTrue("artifact        (none written)" in rendered, rendered)
        assertTrue("(no replay command: no artifact was written for this run)" in rendered, rendered)
    }

    // ------------------------------------------------------------------ [CHA1-51] the command

    /**
     * [CHA1-51]: the command line in the report is **executed**, in a fresh JVM, through
     * `sh -c` on exactly the text the report printed — the only check that distinguishes a
     * pasteable command from a plausible-looking string.
     *
     * A replay of this artifact must exit 0 (REPLAYED). It is a real replay: the fresh JVM
     * has no `GraphRegistry` or `CheckRegistry` entries until the `--register` argument's
     * class initialises, and no fault codec until the CLI warms them.
     */
    @Test
    fun theRenderedReplayCommandActuallyReplaysTheArtifact_CHA1_51() {
        val run = ReportFixtures.run(seed = 16)
        val report = run.execute()
        val artifact = DstArtifacts.write(
            DstArtifact.of(run, report, suite = ReportFixtures.SUITE, checkId = ReportFixtures.CHECK_ID),
            root,
        )
        val command = FailureReport.of(
            report,
            suite = ReportFixtures.SUITE,
            artifact = artifact,
            registrars = listOf(ReportFixtures::class.java.name),
        ).replay.commandLine

        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()

        assertEquals(0, exit, "the pasted command must replay the artifact and exit 0. Output:\n$output")
        assertTrue("REPLAYED" in output, output)
    }

    /**
     * The anti-fake half: the same command with the registrar removed cannot resolve the graph,
     * and exits 3 rather than reporting anything about the system under test.
     *
     * Without this, the test above would pass just as well if the CLI ignored its arguments and
     * printed `REPLAYED` unconditionally.
     */
    @Test
    fun aReplayThatCannotResolveItsGraphFailsLoudlyRatherThanReportingAVerdict_CHA1_51() {
        val out = StringBuilder()
        val err = StringBuilder()
        val run = ReportFixtures.run(seed = 17)
        val report = run.execute()
        val artifact = DstArtifacts.write(
            DstArtifact.of(run, report, suite = "dst-selftest-report-unregistered", checkId = ReportFixtures.CHECK_ID),
            root,
        )
        GraphRegistry.unregister(ReportFixtures.GRAPH_ID)
        try {
            val exit = DstReplayCli.run(arrayOf(artifact.absolutePath), out, err)
            assertEquals(3, exit, "an unresolvable graph is an unrunnable replay, not a verdict: $out$err")
            assertTrue(ReportFixtures.GRAPH_ID in err.toString(), err.toString())
        } finally {
            GraphRegistry.register(ReportFixtures.GRAPH_ID, SelfTestGraphs.crossTalk(chains = 2, rounds = 2).builder)
        }
    }

    /**
     * computenet-12tq: [DstReplayCli] decodes an artifact naming every shipped fault kind, over
     * the real CLI subprocess — not the in-process check [ReplayTest] already has for a
     * decode-only JVM (`everyShippedFaultKindDecodesInAJvmThatNeverConstructedTheFault_CHA1_31`),
     * which never touches [DstReplayCli] at all.
     *
     * ## Why this proves decode without a successful replay
     *
     * Every target below names an edge/host/journal/peer [ReportFixtures.GRAPH_ID] does not
     * declare, on purpose. [DstArtifact.plan] decodes *every* fault in the record list eagerly
     * (`plan.faults.map(FaultCodecs::decode)`) before [DstRun.execute] ever validates a single
     * target ([CHA1-23]), so by the time the first [UnknownFaultTargetException] can fire, all
     * ten have already gone through [FaultCodecs.decode] in the replay JVM. A process that
     * exits naming an *unknown target* is therefore proof every kind decoded; a process that
     * exits naming an *unknown fault kind* (`FaultCodecs.decode`'s own message) would mean
     * decode itself failed — the two messages are asserted to tell them apart, so this test
     * cannot pass for the wrong reason.
     *
     * ## Why the kind set comes from [ShippedFaults]/[FaultCodecs], not a list here
     *
     * The fixture values are necessarily hand-written — only each fault class's own
     * constructor knows what a valid instance is (same reasoning as
     * [ReplayTest.SHIPPED_FAULT_FIXTURES]) — but the *set of kinds* they must cover is asserted
     * against [FaultCodecs.kinds]. Rewriting the six-of-ten list computenet-12tq deleted, one
     * file over, is exactly the defect this item exists to close; this assertion is what keeps
     * a future eleventh fault class from silently making the coverage stale rather than red.
     */
    @Test
    fun theCliSubprocessDecodesAnArtifactNamingEveryShippedFaultKind_computenet_12tq() {
        val registeredKinds = FaultCodecs.kinds()
        assertEquals(
            ShippedFaults.CLASSES.size,
            registeredKinds.size,
            "ShippedFaults.ensureRegistered() must register exactly one kind per shipped class",
        )

        val faults: List<Fault> = listOf(
            CrashFault.atQuiescence("crash", host = "no-such-host", atStep = 9_000, journal = null),
            PartitionFault.drop("partition", edge = "no-such-edge", from = 9_000),
            JournalFault(
                "journal",
                journal = "no-such-journal",
                mutation = JournalMutation.TruncateTail(1),
                window = StepWindow(9_000),
            ),
            RestartAtFrontierFault(
                "restart",
                host = "no-such-host",
                journal = "no-such-journal",
                atStep = 9_000,
                prefix = 0,
            ),
            ReorderFault.crossLink("reorder", edge = "no-such-edge", window = 2, from = 9_000, until = 9_100),
            DuplicateFault.frames("duplicate", edge = "no-such-edge", copies = 2, from = 9_000, until = 9_100),
            JoinEvent("join", "no-such-peer", 9_000),
            RejoinEvent("rejoin", "no-such-peer", 9_000),
            DepartEvent("depart", "no-such-peer", 9_000, DepartureMode.EVICT_NO_CLOSE),
            ReassignEvent("reassign", "no-such-peer", 9_000, "no-such-interest", 5L),
        )
        assertEquals(
            registeredKinds.sorted(),
            faults.map { FaultCodecs.encode(it).kind }.sorted(),
            "the fixtures above must cover exactly what FaultCodecs.kinds() registers - a new fault " +
                "class means a new fixture here, not a quietly narrower test",
        )

        val artifact = DstArtifacts.write(
            DstArtifact(
                rig = DstRig.stamp(),
                suite = "dst-selftest-cli-decode-all-kinds",
                seed = 1L,
                graphId = ReportFixtures.GRAPH_ID,
                checkId = ReportFixtures.CHECK_ID,
                budget = 16,
                plan = PlanRecord(faults.map(FaultCodecs::encode)),
                observed = ObservedRun(
                    outcome = DstOutcome.FAILED,
                    steps = 0,
                    failingCheck = "decode-all-kinds fixture: never actually executed",
                    failingStep = 0,
                    traceDigest = "0",
                    traceEvents = 0,
                ),
            ),
            root,
        )
        val command = ReplayCommands.forArtifact(artifact, registrars = listOf(ReportFixtures::class.java.name)).commandLine

        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()

        assertEquals(
            3,
            exit,
            "every target above is nonsense on purpose, so the run must fail validation after decode, " +
                "not report a verdict:\n$output",
        )
        assertFalse(
            "unknown fault kind" in output,
            "an unknown-fault-kind failure would mean decode itself failed for one of the ten shipped " +
                "kinds, not (as intended) target validation failing after every kind decoded:\n$output",
        )
        assertTrue(
            "targets unknown" in output,
            "expected an UnknownFaultTargetException naming a bad target - the proof that every " +
                "shipped kind decoded through the real CLI subprocess before validation ran:\n$output",
        )
    }

    /**
     * computenet-umx.5: the replay command must be copy-pasteable straight out of the rendered
     * report — not merely present on [ReplayCommand.commandLine], which
     * [theRenderedReplayCommandActuallyReplaysTheArtifact_CHA1_51] above already executes
     * without ever going through [FailureReport.render]. This test extracts the executable line
     * from the **rendered text** itself and runs exactly that, so a regression that reunites the
     * label and the command on one line fails here even though the other test stays green.
     */
    @Test
    fun theReplayLineExtractedFromTheRenderedReportIsExecutableVerbatim_umx_5() {
        val run = ReportFixtures.run(seed = 18)
        val report = run.execute()
        val artifact = DstArtifacts.write(
            DstArtifact.of(run, report, suite = ReportFixtures.SUITE, checkId = ReportFixtures.CHECK_ID),
            root,
        )
        val rendered = FailureReport.of(
            report,
            suite = ReportFixtures.SUITE,
            artifact = artifact,
            registrars = listOf(ReportFixtures::class.java.name),
        ).render()

        val commandLine = replayCommandLineFrom(rendered)
        assertTrue(
            commandLine.startsWith("\""),
            "the line meant to be pasted must start with the executable, with no label token to " +
                "strip first:\n$commandLine",
        )

        val process = ProcessBuilder("/bin/sh", "-c", commandLine).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()

        assertEquals(0, exit, "the line extracted from the rendered report must replay verbatim. Output:\n$output")
        assertTrue("REPLAYED" in output, output)
    }

    /**
     * The `unavailable()` half of the same rendering: with no artifact, the line where the
     * command would be must stay recognisably non-executable, so the test above's extraction
     * logic cannot mistake it for a runnable command.
     */
    @Test
    fun theUnavailableReplayLineIsNotMistakenForARunnableCommand_umx_5() {
        val report = ReportFixtures.run(seed = 19).execute()
        val rendered = FailureReport.of(report, suite = ReportFixtures.SUITE).render()

        val commandLine = replayCommandLineFrom(rendered)
        assertTrue(commandLine.startsWith("(no replay command:"), commandLine)
        assertFalse(commandLine.startsWith("\""), "must not look like an executable path: $commandLine")
    }

    // ------------------------------------------------------------------ [CHA1-52] dead letters

    /** Classification is by the record's structural fields, never by its description text. */
    @Test
    fun everyDeadLetterIsClassifiedByReason_CHA1_52() {
        val accounting = DeadLetterAccounting.of(
            listOf(fault("cell threw"), undeliverable("no such target"), undeliverable("also undeliverable")),
        )
        assertEquals(
            mapOf(DeadLetterReason.CELL_FAULT to 1, DeadLetterReason.UNDELIVERABLE to 2),
            accounting.countsByReason,
        )
        assertTrue("3 total: CELL_FAULT=1, UNDELIVERABLE=2 (unexplained: 3)" == accounting.renderCounts(), accounting.renderCounts())
    }

    /** [CHA1-52]'s default: an unexplained dead letter fails the run. */
    @Test
    fun anUnexplainedDeadLetterFailsTheRunByDefault_CHA1_52() {
        val accounting = DeadLetterAccounting.of(listOf(fault("cell threw")), DeadLetterPolicy.strict)
        val failure = runCatching { accounting.verify() }.exceptionOrNull()
        assertTrue(failure is UnexplainedDeadLetters, "expected the [CHA1-52] failure, got $failure")
        assertTrue("strict" in failure.detail(), failure.detail())
    }

    /** The per-run allow mechanism: a named allowance explains an expected letter, and only it. */
    @Test
    fun anExplicitAllowanceExplainsAnExpectedDeadLetter_CHA1_52() {
        val policy = DeadLetterPolicy.allowing(
            DeadLetterAllowance("expected: probe target is deliberately absent", descriptionContains = "no such target"),
        )
        val accounting = DeadLetterAccounting.of(
            listOf(undeliverable("no such target"), fault("cell threw")),
            policy,
        )
        accounting.classified.first().let {
            assertEquals("expected: probe target is deliberately absent", it.allowedBy)
        }
        assertEquals(1, accounting.unexplained.size, "the fault letter is not covered by that allowance")
        val failure = runCatching { accounting.verify() }.exceptionOrNull()
        assertTrue(failure is UnexplainedDeadLetters, "$failure")

        val rendered = FailureReport.of(
            reportWithFailure(AssertionError("something"), letters = accounting.classified.map { it.letter }),
            suite = ReportFixtures.SUITE,
            policy = policy,
        ).render()
        assertTrue("dead letters    2 total: CELL_FAULT=1, UNDELIVERABLE=1 (unexplained: 1)" in rendered, rendered)
        assertTrue("(allowed: expected: probe target is deliberately absent)" !in rendered, "an allowed letter is not listed as unexplained:\n" + rendered)
    }

    /**
     * The AMENDS obligation from computenet-umx.3.7's review: a check message that embeds a
     * run-varying number defeats the shrinker, which accepts a reduction only when the failing
     * check's *message* still matches. Both failures this task introduces are asserted to carry
     * no digits at all; their numbers live in [DstFailureDetail.detail].
     */
    @Test
    fun theFailureMessagesThisTaskIntroducesCarryNoRunVaryingNumbers() {
        val deadLetters = UnexplainedDeadLetters(DeadLetterAccounting.of(listOf(fault("a"), fault("b"))))
        val ledger = ExclusiveLedger("t")
        ledger.mintOwned("p0")
        ledger.mintOwned("p1")
        val lost = runCatching { ledger.verify() }.exceptionOrNull()!!

        listOf(deadLetters.message!!, lost.message!!).forEach { message ->
            // The requirement id is the one thing in the message that may carry digits; strip it
            // and nothing numeric may remain — no counts, no seeds, no step indices.
            val withoutRequirementId = message.replace(Regex("\\[CHA1-\\d+]"), "")
            assertFalse(withoutRequirementId.any { it.isDigit() }, "run-varying number in a check message: $message")
        }
        // The numbers are in the detail, where the shrinker never looks.
        assertTrue("2" in (lost as ExclusivePayloadLost).detail(), lost.detail())
        assertTrue("2 total" in deadLetters.detail(), deadLetters.detail())
    }

    // ------------------------------------------------------------------ fixtures

    private fun reportWithFailure(error: Throwable, letters: List<DeadLetter> = emptyList()): DstReport = DstReport(
        outcome = DstOutcome.FAILED,
        seed = 1,
        graphId = ReportFixtures.GRAPH_ID,
        budget = 500,
        steps = 7,
        plan = FaultPlan.empty(1),
        appliedFaults = emptyList(),
        traceDigest = TraceDigest.EMPTY,
        failingCheck = FailingCheck(error.message ?: "", 7, error),
        deadLetters = letters,
    )

    private fun fault(description: String) =
        DeadLetter(hostRef, IllegalStateException(description), description)

    private fun undeliverable(description: String) = DeadLetter(hostRef, null, description)

    /**
     * The line a human would actually select and paste: the one immediately after the `replay:`
     * header, with its own leading whitespace trimmed. Leading whitespace is not the defect —
     * shells ignore it — the defect this task fixes is a leading *label token* sharing the line.
     */
    private fun replayCommandLineFrom(rendered: String): String =
        rendered.lineSequence()
            .dropWhile { it.trim() != "replay:" }
            .drop(1)
            .first()
            .trim()

    companion object {
        private val hostRef = CellRef(java.util.UUID.randomUUID())
        private val root = File("build/dst-selftest/report")

        @JvmStatic
        @BeforeAll
        fun registerFixtures() {
            // Touch the object so its initialiser runs in this JVM too.
            ReportFixtures.GRAPH_ID
            root.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(ReportFixtures.GRAPH_ID)
            CheckRegistry.unregister(ReportFixtures.CHECK_ID)
        }
    }
}
