package civictech.testkit.dst

import java.io.File

/**
 * How a replay was graded ([CHA1-32], [CHA1-34]).
 *
 * **There is no `PASSED`.** [CHA1-34] requires that a replay which does not reproduce the
 * recorded failure never be reported as a passing run, so the enum simply has nowhere to say
 * it: a replay either reproduced the recorded observation, contradicted it, or could not be
 * graded at all.
 */
enum class ReplayVerdict {
    /** Same outcome, same failing check, same step, equal trace digest ([CHA1-32], BS-1). */
    REPLAYED,

    /** The re-run contradicted the artifact. Loud, named, and never a pass ([CHA1-34]). */
    DIVERGED,

    /**
     * The comparison is meaningless, so no verdict is claimed: a different rig version or
     * commit (epic §9 risk 6), or a run driven across JVMs ([CHA1-40]).
     */
    INDETERMINATE,
}

/**
 * The graded outcome of replaying one artifact.
 *
 * [report] is the re-run's own [DstReport] when a re-run happened; it is `null` for an
 * `INDETERMINATE` verdict decided *before* re-running (wrong rig, multi-JVM), because
 * running would have produced a report that invites exactly the comparison the verdict says
 * cannot be made.
 */
data class ReplayResult(
    val verdict: ReplayVerdict,
    val artifact: DstArtifact,
    val report: DstReport?,
    val message: String,
) {
    val replayed: Boolean get() = verdict == ReplayVerdict.REPLAYED

    override fun toString(): String = "$verdict: $message"
}

/**
 * Re-runs a [DstArtifact] and grades it against what the artifact recorded ([CHA1-32],
 * [CHA1-34], BS-1).
 *
 * ## What a replay does and does not prove
 *
 * [from] takes a [File] and nothing else, on purpose. Every input to the re-run — seed, plan,
 * budget, graph, check — is resolved from the file's bytes or by a *name* read out of them,
 * so a replay that agrees with the artifact is evidence the artifact is sufficient, and
 * changing a byte in the file changes the run. That is the property the rig's own replay tests
 * assert, because "re-ran the same objects and got the same answer" would prove nothing.
 *
 * What it does **not** prove is reproducibility across processes or across commits. Across
 * processes: an in-JVM replay of an [DstDriver.MULTI_JVM] run is not the same experiment, and
 * is graded `INDETERMINATE` ([CHA1-40]). Across commits: a trace digest is a function of what
 * the kernel scheduler did, so it is valid *within* a commit (epic §9 risk 6) — a replay
 * against a different [RigStamp] is `INDETERMINATE`, not `DIVERGED`, and when neither side
 * pinned a commit the caveat is printed on the divergence rather than assumed away.
 *
 * ## Localising a divergence
 *
 * The artifact stores a digest and a trace *length*, not the trace (see [ObservedRun]), so a
 * divergence is named by the step at which the recorded and replayed failures parted, the two
 * step counts, the two trace lengths and both digests — not by the first differing trace
 * event, which an artifact cannot carry. When you need that, run both plans in one process and
 * use [TraceDigests.divergence].
 */
object DstReplay {

    /** Read, re-run and grade the artifact at [file]. */
    fun from(file: File): ReplayResult = of(DstArtifacts.read(file), file.absolutePath)

    /** Re-run and grade [artifact]. */
    fun of(artifact: DstArtifact, source: String = "<artifact>"): ReplayResult {
        val current = DstRig.stamp()
        if (!artifact.driver.claimsReplayReproducibility) {
            return ReplayResult(
                ReplayVerdict.INDETERMINATE,
                artifact,
                null,
                "$source was recorded from a ${artifact.driver} run, which the rig marks non-deterministic and " +
                    "makes no replay-reproducibility claim for ([CHA1-40]): the interleaving of a multi-JVM run " +
                    "is the OS scheduler's and is not recoverable from seed=${artifact.seed}. Not re-run.",
            )
        }
        if (!artifact.rig.comparableTo(current)) {
            return ReplayResult(
                ReplayVerdict.INDETERMINATE,
                artifact,
                null,
                "$source was recorded by ${artifact.rig}, this is ${current}. A trace digest is valid within a " +
                    "commit, not across them (epic risk 6), so no verdict is claimed — this is INDETERMINATE, " +
                    "not FAILED. Not re-run.",
            )
        }

        val report = artifact.run().execute()
        return grade(artifact, report, source)
    }

    /**
     * Grade [report] against [artifact]'s recorded observation, without re-running anything.
     *
     * Split out from [of] because the shrinker ([CHA1-36]) needs exactly this predicate — "does
     * this run still fail the same way?" — and must not invent a second notion of "the same
     * failure" that could drift from the one replay uses.
     */
    fun grade(artifact: DstArtifact, report: DstReport, source: String = "<artifact>"): ReplayResult {
        val recorded = artifact.observed
        val divergences = buildList {
            if (report.outcome != recorded.outcome) {
                add("outcome: recorded ${recorded.outcome}, replayed ${report.outcome}")
            }
            if (report.steps != recorded.steps) {
                add("step count: recorded ${recorded.steps}, replayed ${report.steps}")
            }
            if (report.failingCheck?.message != recorded.failingCheck) {
                add("failing check: recorded ${quote(recorded.failingCheck)}, replayed ${quote(report.failingCheck?.message)}")
            }
            if (report.failingCheck?.step != recorded.failingStep) {
                add("failing step: recorded ${recorded.failingStep}, replayed ${report.failingCheck?.step}")
            }
            if (report.trace.size != recorded.traceEvents) {
                add("trace length: recorded ${recorded.traceEvents} events, replayed ${report.trace.size}")
            }
            if (report.traceDigest.hex != recorded.traceDigest) {
                add("trace digest: recorded ${recorded.traceDigest}, replayed ${report.traceDigest.hex}")
            }
        }

        if (divergences.isEmpty()) {
            return ReplayResult(
                ReplayVerdict.REPLAYED,
                artifact,
                report,
                "$source REPLAYED: suite=${artifact.suite} seed=${artifact.seed} graph=${artifact.graphId} " +
                    "reproduced ${recorded.outcome} at step ${recorded.failingStep ?: recorded.steps} with digest " +
                    "${recorded.traceDigest}" +
                    (recorded.failingCheck?.let { ", check ${quote(it)}" } ?: ""),
            )
        }

        val caveat = if (artifact.rig.commitUnknownAgainst(DstRig.stamp())) {
            " NOTE: no commit is recorded on ${if (artifact.rig.commit == null) "the artifact" else "this rig"}, " +
                "so a cross-commit digest change (epic risk 6) cannot be excluded as the cause; set -Ddst.rig.commit " +
                "to make this distinguishable."
        } else {
            ""
        }

        return ReplayResult(
            ReplayVerdict.DIVERGED,
            artifact,
            report,
            "$source DIVERGED — the replay did NOT reproduce the recorded failure and is NOT a pass ([CHA1-34]). " +
                "suite=${artifact.suite} seed=${artifact.seed} graph=${artifact.graphId} budget=${artifact.budget}; " +
                "divergence at step ${recorded.failingStep ?: recorded.steps} (recorded) vs " +
                "${report.failingCheck?.step ?: report.steps} (replayed); digests recorded=${recorded.traceDigest} " +
                "replayed=${report.traceDigest.hex}. Differences: ${divergences.joinToString("; ")}." +
                caveat,
        )
    }

    /**
     * BS-1's assertion form: replay [file] and fail the calling test unless the verdict is
     * `REPLAYED`. Returns the re-run's report.
     *
     * An `INDETERMINATE` verdict fails too, and says why — a suite that pinned a replay and
     * then silently stopped grading it is a suite that has quietly lost the assertion.
     */
    fun assertReplays(file: File): DstReport {
        val result = from(file)
        if (result.verdict != ReplayVerdict.REPLAYED) throw AssertionError(result.message)
        return result.report!!
    }

    private fun quote(s: String?): String = if (s == null) "<none>" else "\"$s\""
}
