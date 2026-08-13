#!/usr/bin/env python3
"""Print WHO shared the probe's test JVM, and in which order (computenet-dqy.53).

Used by .github/workflows/announcement-probe.yml for `cotenancy=suite-jvm`.

Why this exists. That arm's whole claim is that WsAnnouncementStressTest ran in
the same test JVM as the rest of the `:wire:` suite, the way it does inside
build-test-fast. Without a record read back off the run's own artefacts, that
claim rests on a Gradle command line having no `--tests` filter — which is an
argument, not an observation. This is the counterpart of `loadavg.log` for
`load=suite`: it turns the condition into something a reader can check.

It also refuses to guess about ORDER. The bead behind this arm says the probe
runs "after" the other classes inside build-test-fast; JUnit's default class
order is neither alphabetical nor configured in this build, so where the probe
actually lands is a fact about the run, not about the workflow. The roster is
sorted by each class's JUnit XML `timestamp` — the class's start — and the
probe's position is printed as a rank. If it comes out first, this arm still
measured a shared JVM but NOT the "after them" shape, and the reader can see
that instead of assuming either way.

WHAT IT DELIBERATELY DOES NOT DO. It does not classify, count losses, or set any
outcome; announcement_probe_report.py owns the numerator, the denominator and
the verdict, and this script must not become a second opinion on them. It never
exits non-zero on the strength of the roster alone — a roster is a description
of the condition, and a run whose condition surprises the reader is a run to
read, not automatically a run to fail. The one exception is the case where there
is nothing to describe at all (no XML), which the report script also catches.

TIMESTAMP CAVEAT, stated because it bounds what the ordering means: Gradle
writes one TEST-<class>.xml per class with a second-resolution `timestamp`, so
two classes that start inside the same second tie, and the tie is broken here by
name for stability. That is enough to say where the probe fell among 13 classes;
it is not enough to reconstruct a fine-grained interleaving, and nothing should
be built on it that needs one.

Usage: announcement_probe_cotenancy.py <results-dir> <probe-class-simple-name>
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
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    results_dir, probe = argv[1], argv[2]

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    summary = open(summary_path, "a", encoding="utf-8") if summary_path else None
    try:
        emit("## Co-tenancy roster — who shared the probe's test JVM", summary)
        files = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))
        if not files:
            emit("", summary)
            emit("NO JUnit XML in %s — nothing ran, so nothing was co-tenant." % results_dir, summary)
            print("::error::no JUnit XML in %s; the co-tenancy condition cannot be evidenced" % results_dir)
            return 1

        rows = []
        for path in files:
            root = ET.parse(path).getroot()
            rows.append(
                (
                    root.get("timestamp") or "",
                    root.get("name") or os.path.basename(path),
                    root.get("tests") or "?",
                    root.get("failures") or "?",
                    root.get("errors") or "?",
                    root.get("time") or "?",
                )
            )
        # Second-resolution timestamps tie; break by class name so the roster is
        # stable across runs rather than filesystem-order.
        rows.sort(key=lambda row: (row[0], row[1]))

        emit("", summary)
        emit("| # | class | started | tests | failures | errors | wall time (s) |", summary)
        emit("| --- | --- | --- | --- | --- | --- | --- |", summary)
        position = None
        for index, row in enumerate(rows, start=1):
            timestamp, name, tests, failures, errors, seconds = row
            if probe in name:
                position = index
                name = "**%s**" % name
            emit(
                "| %d | %s | %s | %s | %s | %s | %s |"
                % (index, name, timestamp, tests, failures, errors, seconds),
                summary,
            )

        emit("", summary)
        total = len(rows)
        if position is None:
            emit(
                "%s DID NOT RUN in this invocation — %d other class(es) did. The "
                "co-tenancy condition was not what was asked for; the report step's "
                "own executed-size assertion is the authority on whether this run "
                "measured anything." % (probe, total),
                summary,
            )
        elif total == 1:
            emit(
                "%s ran ALONE — no co-tenant classes in this test JVM. This is the "
                "`filtered` condition, whatever was dispatched." % probe,
                summary,
            )
        else:
            emit(
                "%s ran as class %d of %d in ONE test JVM (`:wire` has far fewer "
                "classes than `forkEvery(80)` and `maxParallelForks` is raised for "
                "`:kernel` only, so this suite is never split across JVMs). %d "
                "class(es) started before it and %d after."
                % (probe, position, total, position - 1, total - position),
                summary,
            )
            if position == 1:
                emit(
                    "NOTE: it ran FIRST. The JVM was shared, but the 'after them' "
                    "shape build-test-fast was observed in is NOT what this run "
                    "measured — read the result accordingly.",
                    summary,
                )
        emit("", summary)
        return 0
    finally:
        if summary:
            summary.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
