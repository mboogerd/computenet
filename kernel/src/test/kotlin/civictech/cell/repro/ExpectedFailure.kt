package civictech.cell.repro

import org.junit.jupiter.api.extension.ExtendWith

/**
 * CHA2's expected-failure discipline: a test that is **expected to fail until a kernel fix
 * lands**, and that breaks the build the moment it stops failing for its recorded reason.
 *
 * A reproduction annotated with this runs on every build. The [ExpectedFailureExtension]
 * that this annotation meta-registers inverts the verdict:
 *
 * - the body fails carrying [signature] -> reported **passed** (`[CHA2-42]`), and the
 *   reproduction is recorded in the per-run standing-failure ledger (`[CHA2-45]`, BS-16);
 * - the body fails **any other way** — a different signature, a plain exception, a
 *   timeout, budget exhaustion, an aborted assumption — -> reported **failed**
 *   (`[CHA2-43]`, BS-15), because a reproduction failing for a new reason is a new defect;
 * - the body **passes** -> reported **failed**, with the instruction to remove the
 *   annotation, keep the test, and update the findings entry (`[CHA2-44]`, BS-14). That
 *   flip is the point: it turns a reproduction into the fixing lane's acceptance test.
 *
 * What this deliberately is **not** (feature design D3, `[CHA2-40]`): not `@Disabled`, not
 * `@Ignore`, not a Gradle test filter, not a tag exclusion, not an opt-in property, and not
 * an inline negated assertion. Every one of those either stops the body executing — so it
 * can never detect that the defect was fixed — or keeps quietly passing after the fix
 * lands, which is the single failure mode this mechanism exists to prevent.
 *
 * ## Declaring one
 *
 * ```kotlin
 * private const val C9_JOURNALED_SOURCE_DOUBLE_FIRE = "CHA2-BS-4"
 *
 * @Test
 * @ExpectedFailure(
 *     signature = C9_JOURNALED_SOURCE_DOUBLE_FIRE,
 *     reason = "a journaled source re-fires its effect after restart at an arbitrary prefix",
 *     owner = "KFX",
 *     filedAs = "doc/evidence-lane-findings.md#c9-residual",
 * )
 * fun journaledSourceDoubleFires() = withSignature(C9_JOURNALED_SOURCE_DOUBLE_FIRE) {
 *     effects.size shouldBe 1
 * }
 * ```
 *
 * ## Why there is a [signature] and why it is a token
 *
 * `[CHA2-43]` is only load-bearing if "the recorded signature" is something the extension
 * can compare against. Matching on a *formatted assertion message* was rejected in the
 * feature's §9 risk 4: too loose and `[CHA2-43]` degrades into "any failure passes"; too
 * tight and an unrelated refactor that rewords an assertion reddens the gate for no reason.
 * So a reproduction fails through [failAsExpected]/[withSignature], which throws an
 * [ExpectedFailureSignal] carrying a **stable identifier** — the reproduction's own id —
 * and the extension matches that identifier and nothing else. The message is free to change;
 * the token is the contract.
 *
 * @property signature the stable identifier the body's failure must carry, as thrown by
 *   [failAsExpected] or [withSignature]. Never a formatted message.
 * @property reason one line, machine-readable, on why this still fails (`[CHA2-41]`).
 * @property owner the owning work item — a bead id or a lane (`KFX`, `SEC1`) (`[CHA2-41]`).
 * @property filedAs the findings / `concord/corpus/DISPUTES.md` anchor (`[CHA2-41]`).
 *
 * All four are required, and all four are checked non-blank at run time: a blank one fails
 * the test, because "required" that the compiler alone enforces is satisfied by `""`.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ExtendWith(ExpectedFailureExtension::class)
annotation class ExpectedFailure(
    val signature: String,
    val reason: String,
    val owner: String,
    val filedAs: String,
)

/**
 * The failure an expected-failure reproduction throws, carrying the stable [signature]
 * token [ExpectedFailureExtension] matches on.
 *
 * It is an [AssertionError] so that a reproduction reads like an assertion failure
 * everywhere else — in a stack trace, in the JUnit XML, in a `--rerun` transcript — and so
 * that a reproduction whose annotation is removed after the fix lands still fails honestly
 * if the defect comes back.
 */
class ExpectedFailureSignal(
    /** The stable identifier matched against [ExpectedFailure.signature]. */
    val signature: String,
    detail: String,
    cause: Throwable? = null,
) : AssertionError(if (detail.isBlank()) signature else "$signature: $detail", cause)

/**
 * Fail the current expected-failure reproduction with the stable [signature] token.
 *
 * Use this when the reproduction detects the divergence itself rather than through an
 * assertion library call.
 */
fun failAsExpected(signature: String, detail: String = ""): Nothing =
    throw ExpectedFailureSignal(signature, detail)

/**
 * Run [body] and re-throw any assertion failure it raises as an [ExpectedFailureSignal]
 * carrying [signature], preserving the original as the cause.
 *
 * This is the ordinary way to write a reproduction: assert what the spec requires, wrapped
 * in the reproduction's token. The assertion's *message* may be reworded freely by later
 * refactors without reddening the gate; only the token is matched (§9 risk 4).
 *
 * Anything that is **not** an assertion failure — an exception from the kernel, a timeout,
 * budget exhaustion — is left untouched and therefore reaches the extension unsigned, which
 * fails the build (`[CHA2-43]`). That asymmetry is deliberate: a reproduction that starts
 * throwing instead of asserting is a new defect, not the recorded one.
 *
 * Returns [Unit] rather than the block's value on purpose. A reproduction written as
 * `fun repro() = withSignature(SIG) { actual shouldBe expected }` would otherwise inherit
 * the assertion's return type, and Jupiter does not discover a `@Test` method with a
 * non-`void` return — the reproduction would vanish from the run and from the ledger with
 * no error anywhere. Measured, not theorized: the first draft of
 * `ExpectedFailureSelfTest.FailsAsRecorded` did exactly that and reported `started: 0`.
 */
inline fun withSignature(signature: String, detail: String = "", body: () -> Unit) {
    try {
        body()
    } catch (failure: AssertionError) {
        if (failure is ExpectedFailureSignal) throw failure
        throw ExpectedFailureSignal(signature, detail.ifBlank { failure.message.orEmpty() }, failure)
    }
}
