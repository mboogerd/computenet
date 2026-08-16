package civictech.cell.repro

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.junit.platform.commons.support.AnnotationSupport
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method

/**
 * The JUnit 5 extension behind [ExpectedFailure]. It **inverts the verdict** of the
 * annotated test.
 *
 * Realized as an [InvocationInterceptor] rather than a bare
 * `TestExecutionExceptionHandler`, because the handler sees only the failing direction. The
 * direction that makes the discipline load-bearing is the other one — an expected failure
 * that starts *passing* has to break the build (`[CHA2-44]`, BS-14) — and only an
 * interceptor observes a test body that completed normally.
 *
 * The three verdict paths, in the order they are decided:
 *
 * 1. **The annotation is malformed** (any of `signature`, `reason`, `owner`, `filedAs`
 *    blank): fail, without running the body. `[CHA2-41]` requires all four to be
 *    machine-readable, and the compiler only enforces that they are *present*.
 * 2. **The body throws.** If the throwable, or something on its cause chain, is an
 *    [ExpectedFailureSignal] whose [ExpectedFailureSignal.signature] equals the recorded
 *    one, the failure is swallowed — the test reports **passed** (`[CHA2-42]`) and the
 *    reproduction is recorded in [ExpectedFailureLedger] (`[CHA2-45]`). Any other
 *    throwable is re-thrown wrapped in a diagnostic [AssertionError]: the test **fails**
 *    (`[CHA2-43]`).
 * 3. **The body returns normally.** The test **fails** with the remove-the-annotation
 *    message (`[CHA2-44]`).
 *
 * Two matching decisions worth naming, because both could have gone the other way:
 *
 * - **The cause chain is searched; suppressed throwables are not.** JUnit's same-thread
 *   timeout demotes a real failure to a *suppressed* exception under a `TimeoutException`
 *   (see the long note in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`). Searching
 *   suppressed slots would therefore report "expected failure, as recorded" for a
 *   reproduction that actually hung — and `[CHA2-43]` names a timeout explicitly as a
 *   signature that must fail the build. So a timed-out reproduction fails, even if it
 *   emitted its token before the deadline.
 * - **An aborted assumption fails too.** `TestAbortedException` is not an error, but
 *   `[CHA2-40]` forbids a reproduction being skipped into oblivion, and an assumption is a
 *   skip. It reaches path 2, carries no signal, and fails.
 */
class ExpectedFailureExtension : InvocationInterceptor {

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = intercept(invocation, extensionContext)

    /**
     * `@ParameterizedTest`/`@RepeatedTest` reproductions go through the same inversion,
     * per invocation. Without this override the annotation would be a **silent no-op** on a
     * test template — the one outcome an expected-failure mechanism must never have.
     */
    override fun interceptTestTemplateMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = intercept(invocation, extensionContext)

    private fun intercept(
        invocation: InvocationInterceptor.Invocation<Void>,
        context: ExtensionContext,
    ) {
        val expected = findAnnotation(context)
        if (expected == null) {
            invocation.proceed()
            return
        }
        val testId = testIdOf(context)
        val malformed = malformedAttributes(expected)
        if (malformed.isNotEmpty()) {
            // Skip rather than proceed: the invocation contract requires one or the other,
            // and a body whose declaration is unreadable should not be run at all.
            invocation.skip()
            throw AssertionError(
                "@ExpectedFailure on $testId is incomplete: ${malformed.joinToString()} " +
                    "${if (malformed.size == 1) "is" else "are"} blank. [CHA2-41] requires a " +
                    "stable signature, a one-line reason, an owning work item and a " +
                    "findings/DISPUTES anchor, all machine-readable."
            )
        }

        try {
            invocation.proceed()
        } catch (failure: Throwable) {
            if (carriesSignature(failure, expected.signature)) {
                ExpectedFailureLedger.record(StandingExpectedFailure(testId, expected))
                return
            }
            throw AssertionError(
                "expected failure $testId changed signature — a reproduction failing for a " +
                    "new reason is a new defect [CHA2-43]. Recorded signature: " +
                    "'${expected.signature}' (owner ${expected.owner}, filed as " +
                    "${expected.filedAs}). Observed instead: " +
                    "${failure::class.java.name}: ${failure.message}. Either the recorded " +
                    "defect moved — re-record the signature and update the findings entry — " +
                    "or this is a second, unfiled defect.",
                failure,
            )
        }

        throw AssertionError(
            "expected failure now passes — the fix landed; remove the annotation, keep the " +
                "test, and update the findings entry [CHA2-44]. Test: $testId. Recorded " +
                "signature: '${expected.signature}'. Reason it was expected to fail: " +
                "${expected.reason}. Owner: ${expected.owner}. Filed as: ${expected.filedAs}."
        )
    }

    private fun malformedAttributes(expected: ExpectedFailure): List<String> = buildList {
        if (expected.signature.isBlank()) add("signature")
        if (expected.reason.isBlank()) add("reason")
        if (expected.owner.isBlank()) add("owner")
        if (expected.filedAs.isBlank()) add("filedAs")
    }

    private fun carriesSignature(failure: Throwable, signature: String): Boolean {
        var current: Throwable? = failure
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is ExpectedFailureSignal && current.signature == signature) return true
            current = current.cause
        }
        return false
    }

    /** Method-level declaration wins; a class-level one covers every test it encloses. */
    private fun findAnnotation(context: ExtensionContext): ExpectedFailure? {
        val onMethod = AnnotationSupport
            .findAnnotation(context.testMethod, ExpectedFailure::class.java)
            .orElse(null)
        if (onMethod != null) return onMethod
        var enclosing: Class<*>? = context.testClass.orElse(null)
        while (enclosing != null) {
            val onClass = AnnotationSupport
                .findAnnotation(enclosing, ExpectedFailure::class.java)
                .orElse(null)
            if (onClass != null) return onClass
            enclosing = enclosing.enclosingClass
        }
        return null
    }

    private fun testIdOf(context: ExtensionContext): String {
        val className = context.testClass.map { it.name }.orElse("<unknown class>")
        val methodName = context.testMethod.map { it.name }.orElse(context.displayName)
        return "$className.$methodName"
    }
}

/** One reproduction that failed exactly as recorded, in this run. */
data class StandingExpectedFailure(
    val testId: String,
    val signature: String,
    val reason: String,
    val owner: String,
    val filedAs: String,
) {
    constructor(testId: String, expected: ExpectedFailure) : this(
        testId = testId,
        signature = expected.signature,
        reason = expected.reason,
        owner = expected.owner,
        filedAs = expected.filedAs,
    )

    /** One TSV record for the per-run report file. Field separators are stripped, not escaped. */
    fun toReportLine(): String =
        listOf(testId, signature, owner, reason, filedAs).joinToString("\t") { it.flatten() }

    private fun String.flatten(): String = replace('\t', ' ').replace('\n', ' ').trim()
}

/**
 * The per-run ledger of expected failures still standing — `[CHA2-45]`, BS-16: *the residual
 * ledger is an artifact rather than folklore*.
 *
 * Every reproduction that failed as recorded appends one line to a run report file, which
 * `kernel/build.gradle.kts` truncates before the run and prints, with its count, after it.
 * The file, not an end-of-JVM hook, is what makes the report a **per-run** one: `:kernel`
 * runs its suite across several test JVMs (`maxParallelForks = 2`, `forkEvery(80)` in
 * `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`), so anything printed at the end of one
 * JVM reports a fraction of the run and calls it the total.
 *
 * Concurrency: appends are serialized by a JVM monitor and an exclusive file lock, so the
 * several forks of one run can share the file. [capturing] swaps the destination
 * process-wide and is therefore only safe while nothing else in the same JVM is running an
 * expected-failure test — true here because `:kernel` runs classes sequentially within a
 * fork (no `junit.jupiter.execution.parallel.enabled`).
 */
object ExpectedFailureLedger {

    /**
     * Where the run report is written, relative to the test JVM's working directory —
     * which for a Gradle `Test` task is the project directory, so this resolves to the same
     * `kernel/build/reports/...` file that `kernel/build.gradle.kts` reads.
     */
    const val DEFAULT_REPORT_PATH: String = "build/reports/expected-failures/standing.tsv"

    /** Overrides [DEFAULT_REPORT_PATH] for a harness that does not run from the project dir. */
    const val REPORT_PATH_PROPERTY: String = "computenet.expectedFailures.report"

    private val lock = Any()
    private val standing = mutableListOf<StandingExpectedFailure>()
    private var sink: MutableList<StandingExpectedFailure>? = null

    /** Called by [ExpectedFailureExtension] for every reproduction that failed as recorded. */
    fun record(entry: StandingExpectedFailure) {
        val diverted = synchronized(lock) {
            val captured = sink
            if (captured != null) {
                captured.add(entry)
                true
            } else {
                standing.add(entry)
                false
            }
        }
        if (!diverted) {
            println("[expected-failure] ${entry.testId} (owner ${entry.owner}): ${entry.reason}")
            appendToRunReport(entry)
        }
    }

    /** Everything this JVM recorded so far, outside a [capturing] block. */
    fun standing(): List<StandingExpectedFailure> = synchronized(lock) { standing.toList() }

    /**
     * Divert recording into a private list for the duration of [block], so a self-test may
     * drive reproductions through the JUnit Platform without polluting the real run's ledger
     * or its report file.
     */
    fun <T> capturing(block: () -> T): Captured<T> {
        val diverted = mutableListOf<StandingExpectedFailure>()
        synchronized(lock) {
            check(sink == null) { "ExpectedFailureLedger.capturing is already active" }
            sink = diverted
        }
        try {
            val value = block()
            return Captured(synchronized(lock) { diverted.toList() }, value)
        } finally {
            synchronized(lock) { sink = null }
        }
    }

    /** The result of [capturing]: what was recorded, plus whatever the block returned. */
    data class Captured<T>(val entries: List<StandingExpectedFailure>, val value: T)

    /**
     * The human-readable form of the ledger — the same shape `kernel/build.gradle.kts`
     * prints from the report file, so BS-16 can be asserted on rendered text rather than on
     * a data structure a reader never sees.
     */
    fun render(entries: List<StandingExpectedFailure>): String = buildString {
        appendLine("Standing expected failures (@ExpectedFailure): ${entries.size}")
        entries.forEach { entry ->
            appendLine("  - ${entry.testId}  [owner ${entry.owner}, signature ${entry.signature}]")
            appendLine("      reason:  ${entry.reason}")
            appendLine("      filedAs: ${entry.filedAs}")
        }
    }

    private fun appendToRunReport(entry: StandingExpectedFailure) {
        val file = File(System.getProperty(REPORT_PATH_PROPERTY) ?: DEFAULT_REPORT_PATH)
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            synchronized(lock) {
                FileOutputStream(file, true).use { out ->
                    out.channel.lock().use { out.write((entry.toReportLine() + "\n").toByteArray()) }
                }
            }
        }.onFailure {
            // The report is a convenience; losing it must never change a verdict.
            System.err.println("[expected-failure] could not append to ${file.absolutePath}: $it")
        }
    }
}
