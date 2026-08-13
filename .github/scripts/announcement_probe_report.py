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

It also RECORDS A NUMERATOR AND A DENOMINATOR (computenet-dqy.52). Comparing
two conditions — the probe alone versus the probe under concurrent suite load —
is only meaningful if each condition's counts are kept apart and both are
reported, so this script parses the counts out of the probe's own report line
(`announcement path: N failure(s) in M awaits over K iterations` for the stress
probe, `catch-up burst: N failed iteration(s) in K iterations (R refs
announced)` for the burst probe) and publishes them through
`$PROBE_LOSSES_KEY` / `$PROBE_UNITS_KEY`. On a clean arm the probe prints
nothing, so the denominator comes from `$PROBE_EXPECTED_UNITS` (the dispatched
size) and the numerator is 0. On an `incomplete` or `vacuous` arm BOTH are
`unknown` and neither may be counted: an unfinished sample has no denominator,
which is exactly the trap computenet-dqy.12 and the excluded run 31668681959
came from.

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
import re
import sys
import xml.etree.ElementTree as ET


TIMEOUT_MARKER = "java.util.concurrent.TimeoutException"

# The first line of each probe's own report. Group 1 is the numerator, group 2
# the denominator. Kept as two explicit patterns rather than one clever one:
# the two probes count different things (lost awaits vs failed burst
# iterations) and conflating them would produce a ratio of nothing.
COUNT_PATTERNS = (
    re.compile(r"announcement path: (\d+) failure\(s\) in (\d+) awaits"),
    re.compile(r"catch-up burst: (\d+) failed iteration\(s\) in (\d+) iterations"),
)


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


def parse_counts(message):
    """(numerator, denominator) out of a probe's own report line, or None."""
    for pattern in COUNT_PATTERNS:
        found = pattern.search(message or "")
        if found:
            return int(found.group(1)), int(found.group(2))
    return None


def publish(key_env, value):
    key = os.environ.get(key_env)
    env_path = os.environ.get("GITHUB_ENV")
    if key and env_path:
        with open(env_path, "a", encoding="utf-8") as handle:
            handle.write("%s=%s\n" % (key, value))


def record_outcome(outcome, losses="unknown", units="unknown"):
    """Publish the classification and the arm's own counts to the job.

    `losses`/`units` are the numerator and denominator for THIS arm, kept
    separate from any other arm's (computenet-dqy.52). Both stay `unknown`
    unless the arm actually produced a countable sample.
    """
    publish("PROBE_OUTCOME_KEY", outcome)
    publish("PROBE_LOSSES_KEY", losses)
    publish("PROBE_UNITS_KEY", units)
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
        counted = None  # (numerator, denominator) read off a loss report
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
                    else:
                        found = parse_counts(problem.get("message") or "")
                        if found:
                            counted = found
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

        # The arm's own numerator/denominator. A clean arm did not print one,
        # so its denominator is the dispatched size; a loss prints both; an
        # unfinished arm has neither and must contribute to no rate.
        expected_units = os.environ.get("PROBE_EXPECTED_UNITS", "").strip()
        if outcome == "clean":
            losses, units = "0", expected_units or "unknown"
        elif outcome == "loss" and counted:
            losses, units = str(counted[0]), str(counted[1])
        elif outcome == "loss":
            losses, units = "unparsed", expected_units or "unknown"
        else:
            losses, units = "unknown", "unknown"
        record_outcome(outcome, losses, units)

        emit("", summary)
        emit(
            "LEDGER %s: losses=%s of %s (outcome %s)" % (label, losses, units, outcome),
            summary,
        )
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
