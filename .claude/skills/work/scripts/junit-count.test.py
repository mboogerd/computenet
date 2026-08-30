#!/usr/bin/env python3
"""Regression tests for junit-count.py: the zero-files guard (a glob matching
nothing is indistinguishable from a passing empty suite — computenet-wpvy.41)
and the double-depth glob (single depth reported 496 tests for a tree that
ran 586, silently — measured 2026-08-14).

Run via subprocess, not import: the exit codes ARE the contract the caller
reads, so the tests exercise them the way a shell would.

Run: python3 .claude/skills/work/scripts/junit-count.test.py
"""
import os
import pathlib
import subprocess
import sys
import tempfile

SCRIPT = pathlib.Path(__file__).with_name("junit-count.py")

failed = 0
count = 0


def check(name, cond, detail=""):
    global failed, count
    count += 1
    if not cond:
        failed += 1
        print(f"FAIL: {name}{' — ' + detail if detail else ''}")


def xml(path, tests=1, failures=0, errors=0, skipped=0, ts=""):
    path.parent.mkdir(parents=True, exist_ok=True)
    ts_attr = f' timestamp="{ts}"' if ts else ""
    path.write_text(
        f'<?xml version="1.0"?>\n'
        f'<testsuite name="t" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}"{ts_attr}></testsuite>\n')


def run(*args, cwd=None):
    r = subprocess.run([sys.executable, str(SCRIPT), *map(str, args)],
                       capture_output=True, text=True, cwd=cwd)
    return r.returncode, r.stdout, r.stderr


with tempfile.TemporaryDirectory() as tmp:
    tmp = pathlib.Path(tmp)

    # --- counting, both depths -------------------------------------------
    # THE 496-vs-586 GUARD: files at <dir>/*.xml AND <dir>/*/*.xml must both
    # count; a single-depth implementation silently drops the nested half.
    d1 = tmp / "results1"
    xml(d1 / "TEST-Top.xml", tests=4, failures=1, ts="2026-08-14T12:40:26.685Z")
    xml(d1 / "test" / "TEST-Nested.xml", tests=6, errors=2, skipped=1,
        ts="2026-08-14T12:40:45.016Z")
    rc, out, err = run(d1)
    check("both depths: exit 0", rc == 0, f"rc={rc} err={err}")
    check("both depths: totals merge nested files",
          "2 files: 10 tests, 1 failures, 2 errors, 1 skipped" in out, out)
    check("newest timestamp wins",
          "newest 2026-08-14T12:40:45.016Z" in out, out)

    # depth THREE is deliberately not counted: the contract is *.xml and
    # */*.xml under each given dir, nothing deeper.
    d2 = tmp / "results2"
    xml(d2 / "a" / "b" / "TEST-TooDeep.xml", tests=99)
    xml(d2 / "TEST-Here.xml", tests=1)
    rc, out, err = run(d2)
    check("two depths only", "1 files: 1 tests" in out, out)

    # --- per-dir and total lines -----------------------------------------
    d3 = tmp / "results3"
    xml(d3 / "TEST-Other.xml", tests=3, failures=2)
    rc, out, err = run(d1, d3)
    check("multi-dir: exit 0", rc == 0, f"rc={rc}")
    check("multi-dir: per-dir line for each dir",
          f"{d1}: 2 files" in out and f"{d3}: 1 files" in out, out)
    check("multi-dir: TOTAL line sums everything",
          "TOTAL: 3 files: 13 tests, 3 failures, 2 errors, 1 skipped" in out,
          out)

    # a directory with no xml alongside one with results is reported, not
    # fatal — only ZERO files overall is the trap
    empty = tmp / "results-empty"
    empty.mkdir()
    rc, out, err = run(empty, d3)
    check("one empty dir: still exit 0", rc == 0, f"rc={rc}")
    check("one empty dir: named with 0 files", f"{empty}: 0 files" in out, out)
    check("one empty dir: no NO-RESULTS", "NO-RESULTS" not in out, out)

    # failures do not change the exit code — the caller reads the numbers
    rc, out, err = run(d3)
    check("failures still exit 0", rc == 0 and "2 failures" in out,
          f"rc={rc} out={out}")

    # --- THE ZERO-FILES GUARD (computenet-wpvy.41) ------------------------
    # No xml anywhere: NO-RESULTS and exit 4. A green build with zero results
    # is the trap this exists for.
    rc, out, err = run(empty)
    check("no results: exit 4", rc == 4, f"rc={rc}")
    check("no results: prints NO-RESULTS", "NO-RESULTS" in out, out)

    # A directory that does not exist is NOT the same zero. This pair asserted
    # the opposite until computenet-dh5x: NO-RESULTS reads as "the suite did
    # not run", and for a path that never resolved that is a claim the tool
    # cannot make. Kept as an inversion rather than deleted, so the old
    # contract cannot quietly come back.
    rc, out, err = run(tmp / "never-created")
    check("missing dir: exit 2, not NO-RESULTS' 4", rc == 2, f"rc={rc}")
    check("missing dir: does not print NO-RESULTS",
          "NO-RESULTS" not in out + err, f"out={out} err={err}")

    # non-xml files do not count as results
    junk = tmp / "results-junk"
    junk.mkdir()
    (junk / "output.bin").write_text("not xml")
    rc, out, err = run(junk)
    check("non-xml files: exit 4", rc == 4, f"rc={rc}")

    # --- unparseable xml is not a pass ------------------------------------
    broken = tmp / "results-broken"
    broken.mkdir()
    (broken / "TEST-Truncated.xml").write_text("<testsuite tests=")
    rc, out, err = run(broken)
    check("unparseable: exit 3", rc == 3, f"rc={rc} out={out} err={err}")
    check("unparseable: names the file", "TEST-Truncated.xml" in err, err)

    # ...even when a healthy dir was already counted — never a silent skip
    rc, out, err = run(d3, broken)
    check("unparseable after a healthy dir: still exit 3", rc == 3,
          f"rc={rc}")

    # --- a result FILE passed directly (computenet-be43) -------------------
    # Globbing under a file path can never match; the file itself must count.
    rc, out, err = run(d1 / "TEST-Top.xml")
    check("direct xml file: exit 0", rc == 0, f"rc={rc} err={err}")
    check("direct xml file: counts that file's tests",
          "1 files: 4 tests, 1 failures" in out, out)

    # ...while directory inputs keep working: a module root (results under
    # build/test-results/<task>/) and a results dir both still count.
    mod = tmp / "module"
    xml(mod / "build" / "test-results" / "test" / "TEST-Mod.xml", tests=5)
    rc, out, err = run(mod)
    check("module root: exit 0", rc == 0, f"rc={rc} err={err}")
    check("module root: counts nested results", "1 files: 5 tests" in out, out)
    rc, out, err = run(d1)
    check("results dir still counts after file support",
          rc == 0 and "2 files: 10 tests" in out, f"rc={rc} out={out}")

    # --- a path that does not resolve (computenet-dh5x) --------------------
    # NO-RESULTS must mean "this resolved and holds nothing", never "this did
    # not resolve" — the second reads as "the suite did not run".
    rc, out, err = run(os.path.join(d1, "..", "..", "no-such-worktree", "test"))
    check("missing path: not NO-RESULTS", "NO-RESULTS" not in out + err,
          f"out={out} err={err}")
    check("missing path: exit 2, distinct from NO-RESULTS' 4", rc == 2, f"rc={rc}")
    check("missing path: names the path and the cwd",
          "NO-SUCH-PATH" in err and os.getcwd() in err, err)
    # one bad path among good ones must not be counted past
    rc, out, err = run(d1, os.path.join(d1, "nope"))
    check("one missing path poisons the whole count", rc == 2, f"rc={rc} out={out}")

    # an unreadable directory resolves but cannot be read — also not NO-RESULTS
    locked = tmp / "locked"
    locked.mkdir()
    os.chmod(locked, 0o000)
    try:
        rc, out, err = run(locked)
        check("unreadable dir: not NO-RESULTS", "NO-RESULTS" not in out + err,
              f"out={out} err={err}")
        check("unreadable dir: exit 2 and says which", rc == 2 and "not readable" in err,
              f"rc={rc} err={err}")
    finally:
        os.chmod(locked, 0o755)

    # POSITIVE: a relative path traversing OUT of the cwd into a sibling tree
    # still counts. This is the shape computenet-dh5x reported as broken; it
    # was never broken, and nothing pinned that until now.
    sib = tmp / "sib" / "build" / "test-results" / "test"
    sib.mkdir(parents=True)
    xml(sib / "TEST-a.xml", tests=7)
    here = tmp / "here"
    here.mkdir()
    rc, out, err = run(os.path.join("..", "sib", "build", "test-results", "test"),
                       cwd=here)
    check("sibling-tree relative path still counts",
          rc == 0 and "1 files: 7 tests" in out, f"rc={rc} out={out}")

    # --- usage ------------------------------------------------------------
    rc, out, err = run()
    check("no args: exit 2", rc == 2, f"rc={rc}")
    check("no args: usage on stderr", "usage:" in err, err)

print(f"{count - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
