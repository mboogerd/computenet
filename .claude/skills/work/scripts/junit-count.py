#!/usr/bin/env python3
"""Count JUnit XML results under one or more results directories.

Merges the two inline counters in review-task.md §2 and review-feature.md §3
into one script, keeping the guard each had that the other lacked:

- review-feature.md's zero-files guard: a glob matching nothing is
  indistinguishable from a passing empty suite, so NO xml files at all prints
  NO-RESULTS and exits 4 (computenet-wpvy.41). A green build with zero
  results is exactly the trap this guard exists for — never report it as a
  pass.
- review-task.md's double depth: xml files are matched both directly under
  each directory (``<dir>/*.xml``) and one level down (``<dir>/*/*.xml``),
  because results nest — Gradle writes per-task subdirectories, and the
  demo/* modules sit one level deeper than the rest. A single-depth glob
  reported 496 tests for a tree that ran 586, with no visible sign of the 90
  tests and 8 modules it dropped (measured 2026-08-14).

The newest internal ``timestamp`` attribute is reported alongside the counts
because the timestamp, not the count, is what separates a run from a cached
replay: a build-cache restore leaves the previous run's XML under fresh file
mtimes with identical counts (computenet-qsfu).

Usage: junit-count.py <results-dir | result-file.xml> [<results-dir | result-file.xml>...]
Prints one line per directory and a TOTAL line, each with
files/tests/failures/errors/skipped and the newest timestamp seen.
Exit: 0 = counted (failures included — read the numbers); 2 = bad usage;
      3 = an xml file would not parse (an unreadable result is not a pass);
      4 = NO xml files matched at all (NO-RESULTS).
"""
import glob
import os
import sys
from xml.etree import ElementTree as ET


class Unparseable(Exception):
    pass


def _patterns(d):
    """Every glob tried for one argument, in the order reported on NO-RESULTS.

    Callers pass a MODULE directory as often as a results directory — SKILL.md's
    surrounding prose talks about modules — and results sit three levels below
    one. `junit-count.py <worktree>/demo/beadsmirror` printed NO-RESULTS against
    a tree holding 46 TEST-*.xml files with 296 results, which reads as "the
    tests did not run": the wrong direction of error, manufacturing doubt about
    a run that really happened (computenet-lpp6).
    """
    return [os.path.join(d, "*.xml"),
            os.path.join(d, "*", "*.xml"),
            os.path.join(d, "build", "test-results", "*", "*.xml"),
            os.path.join(d, "build", "test-results", "*.xml")]


def count_dir(d):
    """(files, tests, failures, errors, skipped, newest-timestamp) for one dir.

    Both depths, deliberately — see the module docstring. sorted(set(...)) so
    a file cannot be counted twice and the order is stable.
    """
    if os.path.isfile(d):
        # An XML result file passed directly — accept it rather than globbing
        # under it, which can never match and reads as "no tests ran"
        # (computenet-be43, third wrong-path variant after lpp6/v38r).
        paths = [d]
    else:
        paths = sorted(set(p for pat in _patterns(d) for p in glob.glob(pat)))
    t = f = e = s = 0
    newest = ""
    for p in paths:
        try:
            r = ET.parse(p).getroot()
        except ET.ParseError as exc:
            raise Unparseable(f"{p}: {exc}")
        t += int(r.get("tests", 0))
        f += int(r.get("failures", 0))
        e += int(r.get("errors", 0))
        s += int(r.get("skipped", 0))
        newest = max(newest, r.get("timestamp", ""))
    return len(paths), t, f, e, s, newest


def line(label, files, t, f, e, s, newest):
    tail = f", newest {newest}" if newest else ""
    return (f"{label}: {files} files: {t} tests, {f} failures, "
            f"{e} errors, {s} skipped{tail}")


def main(argv):
    dirs = argv[1:]
    if not dirs:
        print("usage: junit-count.py <results-dir | result-file.xml> [<results-dir | result-file.xml>...]",
              file=sys.stderr)
        return 2
    total = [0, 0, 0, 0, 0]
    newest_all = ""
    for d in dirs:
        try:
            files, t, f, e, s, newest = count_dir(d)
        except Unparseable as exc:
            # An unreadable result file is not a pass, and skipping it would
            # undercount silently — the same shape as the single-depth glob.
            print(f"UNPARSEABLE: {exc}", file=sys.stderr)
            return 3
        for i, v in enumerate((files, t, f, e, s)):
            total[i] += v
        newest_all = max(newest_all, newest)
        print(line(d, files, t, f, e, s, newest))
    if total[0] == 0:
        # Zero is not a pass (computenet-wpvy.41): wrong glob, wrong module
        # path, or a green build that genuinely ran nothing. NAME the globs
        # tried, so a caller who pointed at the wrong level can see that
        # rather than read NO-RESULTS as "the tests did not run"
        # (computenet-lpp6).
        for d in dirs:
            for pat in _patterns(d):
                print(f"  tried: {pat}", file=sys.stderr)
        print("NO-RESULTS")
        return 4
    print(line("TOTAL", *total, newest_all))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
