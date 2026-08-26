package civictech.bench.micro

import civictech.bench.TriggerClaim
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The `@Tag("bench")` entry point that renders an interleaved, per-arm host-gated A/B of
 * MEASUREMENT-ITERATION LENGTH over `OperatorThroughputBenchmark`'s set-shaped subjects,
 * and states a verdict on `[BEN1-28]`'s **time** channel (computenet-bzwx).
 *
 * `computenet-i61m` retired `[BEN1-28]` on the **allocation** channel and said, next to
 * the number, that the verdict did not reach wall clock: a `TagState` map that grows
 * without allocating — longer rehash probes, cache misses over a larger table, a larger
 * old generation to trace — costs time that `gc.alloc.rate.norm` cannot see. It then
 * spent its remaining slot on a cheap direct probe of that channel and reported, honestly,
 * that the probe was underpowered (one fork, four iterations), not at the annotation
 * config, sequential rather than interleaved, un-re-gated between arms, and internally
 * inconsistent. This test is the instrument that probe was standing in for.
 *
 * ## What the two arms are
 *
 * Both arms are `OperatorThroughputBenchmark.real`, `direction=INSERT`, over
 * `Subject.setShaped()`, at the class's own declared fork and iteration counts. They
 * differ in ONE knob: JMH's `-r`, the wall-clock length of a measurement iteration. The
 * graph is rebuilt at `@Setup(Level.Iteration)`, so a longer iteration accumulates more
 * tag-map state before it resets and nothing else about the measured work changes.
 *
 * [IterationLengthCriterion] holds the decision; see its KDoc for the thresholds, the
 * aggregation, and the one-sidedness a reader has to carry.
 *
 * ## Where the two arms' artifacts live, and why there is only one system property
 *
 * `bench/build.gradle.kts` forwards exactly four `civictech.bench.*` properties to the
 * test JVM, and this test deliberately does not ask for a fifth: a new forwarded property
 * is a build-script change on a file every other benchmark render shares, and the module
 * already has a convention for finding a second artifact — `ThroughputReport.runLogFor`
 * locates a run's log *beside* its results file by name. This follows it one step
 * further:
 *
 * - `-Dcivictech.bench.jmhResults=<dir>/<stem>.csv` names the **LONG** arm (the
 *   treatment), so `ThroughputReport.renderRun` reads it and its `<stem>.log` directly.
 * - The **SHORT** arm (the control) sits beside it as `<dir>/<stem>.short.csv`, with its
 *   own `<stem>.short.log`.
 *
 * A missing short arm is refused by name rather than rendered as a one-armed entry.
 *
 * ## Interleaving is the RUN's property, not this renderer's
 *
 * Nothing here can verify that the two arms were interleaved or that each was gated on a
 * polled trough — a CSV records neither. The renderer's contribution is that both arms'
 * numbers pass through one committed criterion; the interleaving and the host readings
 * are the measuring session's to perform and to record in the findings entry. Each arm's
 * CSV is the concatenation (headers de-duplicated) of the per-subject invocations that
 * arm ran, and its `.log` the concatenation of their logs — which `MeasuringJvm`,
 * `RunKnobs` and `HostFacts` all collapse with `distinct()`, and all three REFUSE if the
 * concatenated invocations disagree about the JVM, the knobs or the host. Concatenating
 * the two arms together instead would be caught by `RunKnobs`: their `# Measurement:`
 * banners differ in the per-iteration time.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * # per subject, per arm, each launch gated on a polled load trough, arms alternating:
 * <toolchain-21>/bin/java -jar bench/build/libs/bench-jmh.jar \
 *      'OperatorThroughputBenchmark.real' -p subject=<one> -p direction=INSERT \
 *      -r <1|10>s -rf csv -rff /abs/path/<arm>-<subject>.csv > ...log 2>&1
 * # concatenated per arm into <stem>.csv/.log and <stem>.short.csv/.log, then:
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.IterationLengthAbRenderTest' \
 *   -Dcivictech.bench.jmhResults=/abs/path/<stem>.csv \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD) \
 *   -Dcivictech.bench.date=<date>
 * ```
 *
 * **Invoke the JMH jar through the toolchain's own JDK 21 by absolute path** — a bare
 * `java` on the pinned host is JBR 25 (`computenet-dbqt`), and the run's own
 * `# VM version:` banner, retained in the `.log`, is the check.
 */
@Tag("bench")
class IterationLengthAbRenderTest {

    @Test
    fun `renders the interleaved iteration-length A-B of the set-shaped subjects`() {
        val path = System.getProperty("civictech.bench.jmhResults")
        requireNotNull(path?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.jmhResults=<path to the LONG arm's `-rf csv` results " +
                "file>; the SHORT arm is read from '<stem>.short.csv' beside it"
        }
        val sha = System.getProperty("civictech.bench.harnessSha")
        requireNotNull(sha?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse --short HEAD>; the results " +
                "file does not record which harness commit produced it, and " +
                "RunEnvironment refuses to exist without it"
        }
        val longFile = File(path!!)
        require(longFile.isFile) { "no LONG-arm JMH results file at ${longFile.absolutePath}" }
        val shortFile = shortArmFor(longFile)
        require(shortFile.isFile) {
            "no SHORT-arm JMH results file at ${shortFile.absolutePath}. This entry point " +
                "renders an A/B and cannot render one arm: the short arm is the control " +
                "that says what the long arm's number is being compared to. Name the LONG " +
                "arm in -Dcivictech.bench.jmhResults and place the short arm beside it as " +
                "'<stem>.short.csv' with its own '<stem>.short.log'"
        }

        val date = System.getProperty("civictech.bench.date")
            ?: java.time.LocalDate.now().toString()

        val rows = pairArms(
            shortRows = ThroughputReport.parseCsv(shortFile.readText()),
            longRows = ThroughputReport.parseCsv(longFile.readText()),
        )
        val verdict = IterationLengthCriterion.verdictOf(rows)
        val split = IterationLengthCriterion.splitOf(rows)

        val longReport = ThroughputReport.renderRun(
            results = longFile,
            harnessCommitSha = sha!!,
            date = date,
            subject = System.getProperty("civictech.bench.subject") ?: LONG_ARM_SUBJECT,
            trigger = TriggerClaim.Cited(
                gapId = GAP_ID,
                statement = "$verdict: ${IterationLengthCriterion.CRITERION}; measured, " +
                    IterationLengthCriterion.measuredClause(rows),
            ),
        )
        val shortReport = ThroughputReport.renderRun(
            results = shortFile,
            harnessCommitSha = sha,
            date = date,
            subject = SHORT_ARM_SUBJECT,
            // The control arm answers no trigger question on its own — the A/B does, and
            // the long arm's block above carries it. TriggerClaim.None renders as MARKED
            // INCOMPLETE, which is the accurate label for a table that is half of a
            // comparison.
        )

        // Printed, never written: appending an entry to doc/bench/findings.md is the
        // measurement task's hand step, performed by whoever can vouch for the run.
        println(longReport.text())
        println()
        println(shortReport.text())

        println()
        println(
            "A/B criterion inputs (ops/s = DELTAS per second; ratio = long-arm score / " +
                "short-arm score, interval propagated from both arms' 99.9% error bars):"
        )
        println(
            "| subject | short arm (ops/s) | short rel. err | long arm (ops/s) | " +
                "long rel. err | ratio | ratio low | ratio high | row |"
        )
        println("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        rows.sortedBy { it.subject }.forEach { row ->
            println(
                "| ${row.subject} | ${row.short.score} | ${row.short.relativeError} | " +
                    "${row.long.score} | ${row.long.relativeError} | ${row.ratio} | " +
                    "${row.ratioLow} | ${row.ratioHigh} | ${row.verdict} |"
            )
        }
        println()
        println(
            "MATERIAL_RATIO=${IterationLengthCriterion.MATERIAL_RATIO} " +
                "RESOLVABLE_RELATIVE_ERROR=" +
                "${IterationLengthCriterion.RESOLVABLE_RELATIVE_ERROR}"
        )
        println("verdict=$verdict")
        println("computenet-i61m subject split: $split")
    }

    /**
     * Where the short arm sits: beside the long arm, same stem, `.short.csv`.
     *
     * The same shape of convention as `ThroughputReport.runLogFor` and adopted for the
     * same reason — the pairing becomes a property of the artifacts on disk, and an arm
     * that was lost or never captured is visibly missing rather than silently absent.
     */
    private fun shortArmFor(longArm: File): File = File(
        longArm.absoluteFile.parentFile,
        longArm.nameWithoutExtension + ".short.csv",
    )

    /**
     * Joins the two arms by subject, refusing anything that would make the pairing a
     * different comparison from the one the criterion is stated over.
     */
    private fun pairArms(
        shortRows: List<JmhRow>,
        longRows: List<JmhRow>,
    ): List<IterationLengthCriterion.SubjectAb> {
        val setShaped = Subject.setShaped().map { it.name }.toSet()

        fun index(rows: List<JmhRow>, arm: String): Map<String, JmhRow> {
            val bySubject = LinkedHashMap<String, JmhRow>()
            rows.forEach { row ->
                val subject = row.params[ThroughputReport.SUBJECT_PARAM]
                    ?: throw ThroughputReportException(
                        "a $arm-arm row carries no '${ThroughputReport.SUBJECT_PARAM}' " +
                            "parameter (found ${row.params}); the criterion is stated " +
                            "per set-shaped subject and cannot pair a row that does not " +
                            "say which subject it measured"
                    )
                val direction = row.params[ThroughputReport.DIRECTION_PARAM]
                    ?: throw ThroughputReportException(
                        "a $arm-arm row for subject '$subject' carries no " +
                            "'${ThroughputReport.DIRECTION_PARAM}' parameter (found " +
                            "${row.params}); this A/B is stated over INSERT only and a " +
                            "row that does not say its direction cannot enter it"
                    )
                if (!direction.equals(INSERT, ignoreCase = true)) {
                    throw ThroughputReportException(
                        "$arm-arm row '$subject $direction' is not an $INSERT row. Both " +
                            "arms of this A/B are direction=$INSERT; a retract row would " +
                            "be silently averaged into a ratio stated over insert"
                    )
                }
                if (subject !in setShaped) {
                    throw ThroughputReportException(
                        "$arm-arm row '$subject' is not a set-shaped subject (set-shaped " +
                            "is ${setShaped.sorted()}); this criterion is stated over the " +
                            "set-shaped family only, and a join, grouped or combine " +
                            "subject in it would answer a different question"
                    )
                }
                if (bySubject.put(subject, row) != null) {
                    throw ThroughputReportException(
                        "the $arm arm holds two rows for subject '$subject'. One arm is " +
                            "one measurement per subject; two would make the ratio depend " +
                            "on which was read first"
                    )
                }
            }
            return bySubject
        }

        val short = index(shortRows, "short")
        val long = index(longRows, "long")
        if (short.keys != long.keys) {
            throw ThroughputReportException(
                "the two arms do not cover the same subjects — short arm has " +
                    "${short.keys.sorted()}, long arm has ${long.keys.sorted()}. A ratio " +
                    "needs both arms of the same subject, and a subject present in only " +
                    "one arm would be dropped from a table that claims to be an A/B"
            )
        }
        if (short.isEmpty()) {
            throw ThroughputReportException(
                "neither arm holds any set-shaped INSERT row; there is nothing to compare"
            )
        }
        return short.keys.sorted().map { subject ->
            val shortRow = short.getValue(subject)
            val longRow = long.getValue(subject)
            IterationLengthCriterion.SubjectAb(
                subject = subject,
                short = IterationLengthCriterion.Arm(shortRow.score, shortRow.scoreError),
                long = IterationLengthCriterion.Arm(longRow.score, longRow.scoreError),
            )
        }
    }

    private companion object {

        const val GAP_ID: String = "[BEN1-28]"

        const val INSERT: String = "INSERT"

        const val LONG_ARM_SUBJECT: String =
            "iteration-length A/B, LONG arm (10 s measurement iterations): REAL-drive " +
                "INSERT throughput over the eight set-shaped subjects — the treatment " +
                "arm of computenet-bzwx's test of [BEN1-28]'s TIME channel"

        const val SHORT_ARM_SUBJECT: String =
            "iteration-length A/B, SHORT arm (1 s measurement iterations): REAL-drive " +
                "INSERT throughput over the eight set-shaped subjects — the control arm " +
                "of computenet-bzwx's test of [BEN1-28]'s TIME channel"
    }
}
