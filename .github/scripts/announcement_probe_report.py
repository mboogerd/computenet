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

It also guards the sample size. A probe run that executed nothing — a test task
satisfied from cache, a `--tests` filter matching no class, a skipped test —
looks exactly like a clean bulk sample in the log unless something asserts the
testcase was really there. This exits non-zero if the expected class produced no
executed testcase, so a vacuous run fails loudly instead of being recorded as a
large negative result.

Usage: announcement_probe_report.py <results-dir> <label> <expected-class-simple-name>
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def emit(text, summary):
    print(text)
    if summary:
        summary.write(text + "\n")


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
            return 1

        executed = 0
        failures = 0
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
                    emit("", summary)
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
            return 1

        emit("", summary)
        emit("%d executed testcase(s), %d failure record(s)." % (executed, failures), summary)
        return 0
    finally:
        if summary:
            summary.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
