package civictech.testkit.dst

import java.io.File
import kotlin.system.exitProcess

/**
 * A failure that has more to say than its (deliberately stable) message.
 *
 * The split exists because two consumers read a failure and want opposite things:
 *
 *  - the **shrinker** compares `failingCheck.message` and nothing else
 *    (`FailurePredicate.sameFailingCheck`), so a message containing "12 of 30 arrived" makes a
 *    legitimately reduced plan look like a different failure and be discarded — measured on
 *    computenet-umx.3.7;
 *  - a **human** reading the failure report wants exactly those numbers.
 *
 * So: run-varying detail goes here, the message stays stable, and [FailureReport] renders both.
 * A check that has nothing run-varying to say does not need this interface at all.
 */
interface DstFailureDetail {
    fun detail(): String
}

/**
 * A copy-pasteable command line that replays one artifact ([CHA1-51]).
 *
 * ## Why it is a `java -cp` line and not a Gradle line
 *
 * `./gradlew :<module>:test --tests '<class>'` re-runs a *test*, not an artifact: it cannot be
 * told which seed to replay, because nothing in this repo forwards a `-D` system property into
 * `:testkit`'s test JVM (`testkit/build.gradle.kts` declares no `systemProperty` forwarding,
 * unlike `:wire`, `:oracle` and `:concord`). A Gradle line would therefore be pasteable and
 * *wrong* — it would re-run the whole sweep and report on some other seed. So the command names
 * [DstReplayCli] directly, on the classpath the failing run itself was using.
 *
 * ## What the reader has to know about it
 *
 * - **The classpath is this JVM's**, captured at render time. It is valid until the next build
 *   rewrites those directories; it is not a portable artifact and does not belong in a bead.
 * - **Registrars are required.** A replay resolves its graph from [GraphRegistry] and its check
 *   from [CheckRegistry], both of which are populated by the *consumer suite's* code — a fresh
 *   JVM has neither. Each `--register` argument names a class whose initialisation performs
 *   that registration (a Kotlin `object` with an `init` block is the intended shape). Rendering
 *   a command with no registrars is allowed, because a suite may not have one, but the command
 *   then carries the warning rather than pretending it will work.
 */
data class ReplayCommand(val commandLine: String, val caveat: String? = null) {
    override fun toString(): String = commandLine + (caveat?.let { "   # $it" } ?: "")
}

/** Renders [ReplayCommand]s. See that type for why the command takes the shape it does. */
object ReplayCommands {

    /** The class a rendered command invokes. */
    val MAIN_CLASS: String = DstReplayCli::class.java.name

    /**
     * A command that replays [artifact] in a fresh JVM.
     *
     * @param registrars fully-qualified names of classes whose initialisation registers the
     *   graph and check the artifact names. Empty renders a command that will fail loudly on an
     *   unknown graph id, and says so in the caveat rather than reading as if it would work.
     * @param classpath defaults to the running JVM's, which is what makes the line pasteable
     *   from the log of the run that produced it.
     */
    fun forArtifact(
        artifact: File,
        registrars: List<String> = emptyList(),
        classpath: String = System.getProperty("java.class.path") ?: "",
    ): ReplayCommand {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val line = buildString {
            append("\"").append(java).append("\" -cp \"").append(classpath).append("\" ").append(MAIN_CLASS)
            append(" \"").append(artifact.absolutePath).append("\"")
            registrars.forEach { append(" --register ").append(it) }
        }
        val caveat = when {
            registrars.isEmpty() ->
                "no registrar given: this will fail on an unknown graph id until you add " +
                    "--register <class that registers the graph and check>"

            else -> "classpath is this run's; rebuild invalidates it"
        }
        return ReplayCommand(line, caveat)
    }

    /** No artifact, so no replay. The report prints [reason] where the command would be. */
    fun unavailable(reason: String): ReplayCommand = ReplayCommand("(no replay command: $reason)")
}

/**
 * The command line [ReplayCommands] renders: replay one artifact, grade it, and exit with the
 * verdict ([CHA1-51], [CHA1-32], [CHA1-34]).
 *
 * Exit codes are the verdict, so the command composes in a shell:
 * `0` REPLAYED, `1` DIVERGED, `2` INDETERMINATE, `3` the artifact could not be read or its
 * graph/check/fault kind is not registered in this JVM.
 *
 * **`2` and `3` are not failures of the system under test** and must not be read as one: an
 * INDETERMINATE verdict is the rig refusing to compare across rig versions or across JVMs
 * (epic §9 risk 6, [CHA1-40]), and `3` is a missing `--register`.
 */
object DstReplayCli {

    /** The body, without exiting — so a test can drive it in-process. Returns the exit code. */
    fun run(args: Array<String>, out: Appendable = System.out, err: Appendable = System.err): Int {
        val positional = mutableListOf<String>()
        val registrars = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--register" -> {
                    val fqcn = args.getOrNull(i + 1)
                        ?: return usage(err, "--register needs a fully-qualified class name")
                    registrars += fqcn
                    i += 2
                }

                else -> {
                    positional += arg
                    i++
                }
            }
        }
        val path = positional.singleOrNull()
            ?: return usage(err, "expected exactly one artifact path, got ${positional.size}")

        return try {
            // Initialisation is the registration: `Class.forName(name, true, loader)` runs the
            // class initialiser, which for a Kotlin `object` is where its `init` block lives.
            registrars.forEach { Class.forName(it, true, DstReplayCli::class.java.classLoader) }
            val result = DstReplay.from(File(path))
            out.appendLine(result.toString())
            when (result.verdict) {
                ReplayVerdict.REPLAYED -> 0
                ReplayVerdict.DIVERGED -> 1
                ReplayVerdict.INDETERMINATE -> 2
            }
        } catch (e: Exception) {
            err.appendLine("replay of $path could not run: ${e.message}")
            3
        }
    }

    @JvmStatic
    fun main(args: Array<String>): Unit = exitProcess(run(args))

    private fun usage(err: Appendable, problem: String): Int {
        err.appendLine("$problem\nusage: ${ReplayCommands.MAIN_CLASS} <artifact.json> [--register <fqcn>]...")
        return 3
    }
}

/**
 * The human-readable failure report of epic §2.3 ([CHA1-50], [CHA1-51]).
 *
 * ## The one rule this type is written to
 *
 * **Every line is traceable to something the run actually observed.** The rig's own history is
 * the reason: it once labelled a drop-mode partition "traffic parks and replays on heal" for a
 * control that replays nothing (computenet-cstu). So the fault lines are each fault's own
 * `describe()` and its measured firing count, not a restatement of what the plan asked for; the
 * dead-letter counts come from the letters the run captured; and where the accounting cannot
 * see something, the limit is stated in the KDoc of the thing that cannot see it
 * ([ExclusiveLedger], [DeadLetterAccounting]) rather than papered over by a confident line here.
 *
 * ## BS-13's rendering half
 *
 * A fault that never fired renders as `INERT fired=0`, on its own line, in the plan block. The
 * count is the rig core's ([AppliedFault.fired]); what this type adds is that it is impossible
 * to read the plan block without seeing it — a plan that looked adversarial and did nothing is
 * the failure mode the marker exists for.
 */
data class FailureReport(
    val report: DstReport,
    val suite: String,
    val artifact: File? = null,
    val replay: ReplayCommand,
    val deadLetters: DeadLetterAccounting,
    val shrink: String? = null,
    val exclusives: ExclusiveLedger? = null,
    val driver: DstDriver = DstDriver.IN_PROCESS,
) {

    /** The whole report, as printed. */
    fun render(): String = buildString {
        appendLine("DST ${report.outcome} — $suite")
        appendLine(field("seed", report.seed.toString()))
        appendLine(field("graph", report.graphId))
        appendLine(field("steps", "${report.steps} of ${report.budget} (budget)"))
        report.failingCheck?.let { appendLine(field("step at failure", it.step.toString())) }
        appendLine(field("driver", driver.toString() + if (driver.deterministic) "" else " — NON-DETERMINISTIC, no replay reproducibility claimed ([CHA1-40])"))
        appendLine(field("plan", planHeader()))
        planLines().forEach { appendLine("      $it") }
        report.failingCheck?.let { check ->
            appendLine(field("failing check", check.message))
            expectedActual(check).forEach { appendLine("      $it") }
        }
        appendLine(field("dead letters", deadLetters.renderCounts()))
        deadLetters.unexplained.forEach { appendLine("      ${it.render()}") }
        exclusives?.let { appendLine(field("exclusives", it.renderSummary())) }
        appendLine(field("artifact", artifact?.absolutePath ?: "(none written)"))
        appendLine(field("replay", replay.commandLine))
        replay.caveat?.let { appendLine("      # $it") }
    }.trimEnd()

    private fun planHeader(): String {
        val faults = "${report.appliedFaults.size} fault(s)"
        val inert = report.inertFaults.size.takeIf { it > 0 }?.let { ", $it INERT" } ?: ""
        return faults + inert + (shrink?.let { "; $it" } ?: "")
    }

    /**
     * One line per applied fault: its firing count, the steps it activated at, and its own
     * `describe()` ([CHA1-50]'s "applied plan with activation steps", BS-13's inert marker).
     */
    private fun planLines(): List<String> = report.appliedFaults.map { fault ->
        val activation = if (fault.inert) {
            "INERT fired=0"
        } else {
            "fired=${fault.fired} @ steps ${fault.activationSteps.joinToString(",")}"
        }
        "[$activation] ${fault.id}: ${fault.description}"
    }

    /**
     * `expected`/`actual` when the failure carries them structurally ([CHA1-50]).
     *
     * `org.opentest4j.AssertionFailedError` is what `kotlin.test`'s `assertEquals` and JUnit 5
     * throw, and it is the only structured source: a bare `AssertionError` has a message and
     * nothing else, and this renders no expected/actual rather than parsing one out of prose.
     * A [DstFailureDetail] adds its run-varying detail here too — the place it was kept out of
     * the message to reach.
     */
    private fun expectedActual(check: FailingCheck): List<String> = buildList {
        val error = check.error
        if (error is org.opentest4j.AssertionFailedError) {
            if (error.isExpectedDefined) add("expected  ${error.expected.stringRepresentation}")
            if (error.isActualDefined) add("actual    ${error.actual.stringRepresentation}")
        }
        if (error is DstFailureDetail) error.detail().lines().forEach { add(it) }
    }

    private fun field(name: String, value: String): String = "  " + name.padEnd(16) + value

    companion object {

        /**
         * Build the report for [report].
         *
         * @param artifact the replay artifact this failure wrote ([CHA1-31]); null renders an
         *   explicit "(none written)" and a replay line saying why there is no command, rather
         *   than a command that would not work.
         * @param registrars passed through to [ReplayCommands.forArtifact].
         * @param policy the dead-letter policy the run was graded under; its allowances are what
         *   the report prints beside an expected letter ([CHA1-52]).
         */
        fun of(
            report: DstReport,
            suite: String = report.graphId,
            artifact: File? = null,
            registrars: List<String> = emptyList(),
            policy: DeadLetterPolicy = DeadLetterPolicy.strict,
            shrink: String? = null,
            exclusives: ExclusiveLedger? = null,
            driver: DstDriver = DstDriver.IN_PROCESS,
        ): FailureReport = FailureReport(
            report = report,
            suite = suite,
            artifact = artifact,
            replay = artifact?.let { ReplayCommands.forArtifact(it, registrars) }
                ?: ReplayCommands.unavailable("no artifact was written for this run"),
            deadLetters = DeadLetterAccounting.of(report, policy),
            shrink = shrink,
            exclusives = exclusives,
            driver = driver,
        )

        /** The same, for a [ShrinkResult]: renders the shrunk `N -> M` line ([CHA1-50]). */
        fun ofShrunk(
            report: DstReport,
            shrink: ShrinkResult,
            suite: String = shrink.artifact.suite,
            artifact: File? = null,
            registrars: List<String> = emptyList(),
            policy: DeadLetterPolicy = DeadLetterPolicy.strict,
            exclusives: ExclusiveLedger? = null,
        ): FailureReport = of(
            report = report,
            suite = suite,
            artifact = artifact,
            registrars = registrars,
            policy = policy,
            shrink = "shrunk ${shrink.artifact.plan.faults.size} -> ${shrink.plan.faults.size}" +
                (if (shrink.stoppedEarly) " (STOPPED EARLY, not a minimum)" else " (locally minimal under the strategy)"),
            exclusives = exclusives,
        )
    }
}
