#!/usr/bin/env python3
"""Print an announcement probe's JUnit XML verbatim into the job log and step summary.

Used by .github/workflows/announcement-probe.yml (computenet-dqy.49).

Why this exists rather than "read the Gradle console output". The value of an
announcement-loss occurrence is the FULL two-sided report — server localRefs vs
client remoteRefs, per-ref park depths on both sides, the preHello/gate
silent-drop counters PR #83 added, and the captured stderr for the failing
iteration. That report is the message of the AssertionFailedError the probe
throws. Gradle's console rendering of a failed test is a presentation detail
with its own filtering (it drops suppressed exceptions unconditionally, for
one), so it is not a safe channel for evidence. The JUnit XML carries the whole
message plus the whole failure text, and this prints both untouched.

It also guards the sample size, for the cases it can see. A probe run that
produced no JUnit XML at all, a `--tests` filter matching no class, or a skipped
test looks exactly like a clean bulk sample in the log unless something asserts
the testcase was really there. This exits non-zero if the expected class
produced no executed testcase, so such a vacuous run fails loudly instead of
being recorded as a large negative result.

It also CLASSIFIES the outcome, because "the sample did not finish" and "an
announcement was lost" are different findings and only one of them is evidence
about the defect (computenet-dqy.50). Every test method in this build gets a
5-minute JUnit timeout (`junit.jupiter.execution.timeout.testable.method.default`
in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`); when a dispatched size does
not fit inside it, the testcase fails with a
`java.util.concurrent.TimeoutException` and no sample is produced.
computenet-dqy.12 established for this repo that such a TimeoutException proves
neither a failure of the code under test nor an in-JVM block — it is an
uninformative red. So a run whose only failure records are timeouts is reported
as `incomplete`, never as a loss, and the distinction is carried out of this
script through `$PROBE_OUTCOME_KEY` in `$GITHUB_ENV` so the job's conclusion can
say which one happened. Outcomes: `clean`, `incomplete`, `loss` (any
non-timeout failure record; a loss alongside a timeout is still a loss, since
the loss is the real evidence), `vacuous`.

IT DOES NOT CATCH A CACHED RUN, and `--rerun` in the workflow is therefore
load-bearing — do not drop it on the grounds that this script covers it.
Measured on this branch, 2026-08-13: re-invoking the probe without `--rerun`
reports `> Task :wire:test UP-TO-DATE`, and deleting the results directory first
only makes it `FROM-CACHE`. Either way the previous run's XML is present, still
carrying that run's `timestamp` and `time`, so this script sees one executed
testcase and reports a clean pass over a sample that never ran.

Usage: announcement_probe_report.py <results-dir> <label> <expected-class-simple-name>
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


TIMEOUT_MARKER = "java.util.concurrent.TimeoutException"


def emit(text, summary):
    print(text)
    if summary:
        summary.write(text + "\n")


def is_timeout(problem):
    """True when this failure record is the build's per-test JUnit timeout firing.

    That is 'the sample did not complete', not 'an announcement was lost'. The
    probes report a loss as an AssertionFailedError carrying the two-sided
    report; only the JUnit timeout arrives as a TimeoutException.
    """
    haystack = (problem.get("type") or "") + "\n" + (problem.get("message") or "")
    return TIMEOUT_MARKER in haystack


def record_outcome(outcome):
    """Publish the classification to the job, if we are running in one."""
    key = os.environ.get("PROBE_OUTCOME_KEY")
    env_path = os.environ.get("GITHUB_ENV")
    if key and env_path:
        with open(env_path, "a", encoding="utf-8") as handle:
            handle.write("%s=%s\n" % (key, outcome))
    return outcome


def main(argv):
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    results_dir, label, expected = argv[1], argv[2], argv[3]

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    summary = open(summary_path, "a", encoding="utf-8") if summary_path else None
    try:
        emit("## Announcement probe report - %s" % label, summary)
        files = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))
        if not files:
            emit("", summary)
            emit("NO JUnit XML in %s - the probe executed nothing." % results_dir, summary)
            print("::error::no JUnit XML in %s; this run measured nothing" % results_dir)
            record_outcome("vacuous")
            return 1

        executed = 0
        failures = 0
        timeouts = 0
        for path in files:
            root = ET.parse(path).getroot()
            for case in root.iter("testcase"):
                classname = case.get("classname") or ""
                if expected not in classname:
                    continue
                if case.find("skipped") is not None:
                    emit("", summary)
                    emit("SKIPPED: %s (%s)" % (case.get("name"), classname), summary)
                    continue
                executed += 1
                emit("", summary)
                emit(
                    "%s :: %s  [wall time %ss]"
                    % (classname, case.get("name"), case.get("time")),
                    summary,
                )
                problems = list(case.iter("failure")) + list(case.iter("error"))
                if not problems:
                    emit("PASSED - no announcement was lost in this sample.", summary)
                    continue
                failures += len(problems)
                for problem in problems:
                    timed_out = is_timeout(problem)
                    if timed_out:
                        timeouts += 1
                    emit("", summary)
                    emit(
                        "INCOMPLETE - the requested size did not finish inside "
                        "the build's 5-minute per-test JUnit timeout. NO SAMPLE "
                        "was produced. This is "
                        "NOT an announcement loss (computenet-dqy.12: a JUnit "
                        "TimeoutException proves neither a failure nor an in-JVM "
                        "block); dispatch a smaller size."
                        if timed_out
                        else "LOST - an announcement did not arrive. This is the "
                        "occurrence; the full two-sided report follows.",
                        summary,
                    )
                    emit("```", summary)
                    emit((problem.get("message") or "").rstrip(), summary)
                    emit("---- full failure text ----", summary)
                    emit((problem.text or "").rstrip(), summary)
                    emit("```", summary)

        if executed == 0:
            emit("", summary)
            emit("NO executed testcase for %s - this run measured nothing." % expected, summary)
            print(
                "::error::%s produced no executed testcase; the sample size is zero, "
                "not the requested one" % expected
            )
            record_outcome("vacuous")
            return 1

        if failures == 0:
            outcome = "clean"
        elif timeouts == failures:
            outcome = "incomplete"
        else:
            outcome = "loss"
        record_outcome(outcome)

        emit("", summary)
        detail = ""
        if timeouts:
            detail = (
                " (%d of them the per-test JUnit timeout, i.e. an unfinished sample "
                "rather than a lost announcement)" % timeouts
            )
        emit(
            "%d executed testcase(s), %d failure record(s)%s. Outcome: %s."
            % (executed, failures, detail, outcome),
            summary,
        )
        return 0
    finally:
        if summary:
            summary.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
