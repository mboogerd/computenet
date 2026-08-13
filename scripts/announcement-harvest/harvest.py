#!/usr/bin/env python3
"""Re-derive the announcement-loss rate from CI history (the computenet-dqy.47 method).

Reads `ci.yml`'s `build-test-fast` runs in a time window, and for each one
establishes -- from the job log, per run, never from the build result -- whether
`:wire:test` ACTUALLY EXECUTED, and whether `WsAnnouncementStressTest` reported
any announcement-path failures.

The method, and why each check is here (computenet-dqy.47, computenet-dqy.58):

  * A `:wire:test` satisfied FROM-CACHE or UP-TO-DATE exercised no announcement
    await. Counting it inflates the denominator with zero-risk trials and biases
    the rate down, so such a run is EXCLUDED, not counted as a pass.
  * `:wire:testClasses UP-TO-DATE` appears in nearly every log and is the
    COMPILE task. It is not a cache hit on the test task. The task-header regex
    below is anchored on `:wire:test` followed by end-of-line or a marker word
    precisely so it cannot match `:wire:testClasses`.
  * Independently of the task marker, a counted run must show the stress test's
    own JUnit lifecycle line (`... PASSED` / `... FAILED`). A task that ran but
    filtered the test out is not a trial either.
  * Awaits, not runs, are the unit: `WsAnnouncementStressTest.DEFAULT_ITERATIONS`
    iterations x 2 awaits each. The constant is read out of the tree AT EACH HEAD
    SHA rather than assumed, because a branch is free to change it.
  * A `TimeoutException` is not a loss (computenet-dqy.12). A non-timeout failure
    carrying no positively-recognised `announcement path: N failure(s) in M
    awaits` line is `unrecognised` (computenet-dqy.54). Only the report line
    yields a numerator, so such a run enters NO numerator -- and it enters no
    DENOMINATOR either. The test throws its report only at the end of the loop,
    so a failure without one means the loop did not finish: how many of the
    `DEFAULT_ITERATIONS x 2` awaits actually ran is unknown, and whether one was
    lost is unknown too. Crediting it with a full run of awaits and zero losses
    would bias the rate DOWN, which is the direction that manufactures a null.
    It is reported as its own excluded category so it stays visible.
  * TWO UNITS ARE REPORTED, awaits and RUNS CARRYING >= 1 LOSS, because a
    per-await rate is only a rate if awaits are independent, and the awaits
    inside one run share a JVM, a runner and a machine (computenet-dqy.64).
    Every occurrence on record is 1 loss in its run's 50 awaits, and nothing
    here can tell a per-await carrier from a per-run one -- with 2 losses in 53
    runs, landing in different runs is what both models predict (P(same run) =
    1/53 under per-await independence). The unit chosen moves the probability
    of a probe arm's null by two orders of magnitude, so both numerators are
    printed and neither is called THE rate.

POSITIVE CONTROL. Run with `--since 2026-08-13T02:06:45Z` (the merge of 38bbd71,
the two-sided instrument) and the first 1h53m of output must reproduce
computenet-dqy.47's hand harvest exactly: 9 counted runs, 450 awaits, 2 losses,
with attempt 2 of run 31661886156 excluded because it was cancelled before
`:wire:test`. A detector that cannot re-find the two known occurrences is
measuring nothing, so check this before trusting any new denominator.

Usage:
    python3 scripts/announcement-harvest/harvest.py --since 2026-08-13T04:21:53Z
    python3 scripts/announcement-harvest/harvest.py --since ... --json out.json

Requires `gh` authenticated, run from inside the repository (the head-sha
lookups are `git show`, so the PR heads must be fetched:
`git fetch origin '+refs/pull/*/head:refs/remotes/pr/*'`).
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = "mboogerd/computenet"
WORKFLOW = "ci.yml"
JOB = "build-test-fast"
STRESS_SRC = "wire/src/test/kotlin/civictech/wire/WsAnnouncementStressTest.kt"

# `> Task :wire:test` and then either nothing or an outcome marker. Anchored so
# `:wire:testClasses UP-TO-DATE` (the compile task) can never match.
TASK_RE = re.compile(r"> Task :wire:test(?:\s+(FAILED|FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE))?\s*$")
LIFECYCLE_RE = re.compile(r"WsAnnouncementStressTest > (.+?)\(\) (PASSED|FAILED|SKIPPED)")
REPORT_RE = re.compile(r"announcement path: (\d+) failure\(s\) in (\d+) awaits over (\d+) iterations")
SUMMARY_RE = re.compile(r"(\d+) actionable tasks: (.*)")
ITERATIONS_RE = re.compile(r"const val DEFAULT_ITERATIONS = (\d+)")


def sh(cmd: list[str]) -> str:
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout


def gh_json(path: str, jq: str | None = None) -> object:
    cmd = ["gh", "api", path]
    if jq:
        cmd += ["--jq", jq]
    return json.loads(sh(cmd) or "null")


RUN_LIST_LIMIT = 400


def runs_since(since: str) -> list[dict]:
    raw = sh([
        "gh", "run", "list", "--workflow", WORKFLOW, "--limit", str(RUN_LIST_LIMIT),
        "--json", "databaseId,headSha,headBranch,event,status,conclusion,createdAt",
    ])
    all_runs = json.loads(raw)
    runs = [r for r in all_runs if r["createdAt"] > since]
    # A truncated listing silently drops runs from the DENOMINATOR, which is a
    # wrong rate with no symptom. Refuse rather than under-report.
    if len(all_runs) >= RUN_LIST_LIMIT and len(runs) == len(all_runs):
        raise SystemExit(
            f"gh run list returned {len(all_runs)} runs (the --limit) and ALL of them are "
            f"after {since}: the window may be truncated. Raise RUN_LIST_LIMIT and re-run."
        )
    return sorted(runs, key=lambda r: r["createdAt"])


def awaits_per_run(sha: str) -> int | None:
    """Read DEFAULT_ITERATIONS out of the tree at `sha`; 2 awaits per iteration."""
    try:
        src = sh(["git", "show", f"{sha}:{STRESS_SRC}"])
    except subprocess.CalledProcessError:
        return None
    m = ITERATIONS_RE.search(src)
    return int(m.group(1)) * 2 if m else None


def job_log(job_id: int) -> str | None:
    try:
        return sh(["gh", "api", f"/repos/{REPO}/actions/jobs/{job_id}/logs"])
    except subprocess.CalledProcessError:
        return None  # retention expired, or log unavailable


def classify(log: str) -> dict:
    markers = {TASK_RE.search(line).group(1) for line in log.splitlines() if TASK_RE.search(line)}
    lifecycle = sorted({m.group(2) for m in LIFECYCLE_RE.finditer(log)})
    reports = [
        {"failures": int(m.group(1)), "awaits": int(m.group(2)), "iterations": int(m.group(3))}
        for m in REPORT_RE.finditer(log)
    ]
    summaries = [m.group(0) for m in SUMMARY_RE.finditer(log)]
    return {
        "wire_test_markers": sorted(x for x in markers if x),
        "wire_test_seen": bool(markers),
        "wire_test_replayed": bool({"FROM-CACHE", "UP-TO-DATE", "SKIPPED", "NO-SOURCE"} & markers),
        "stress_lifecycle": lifecycle,
        "reports": reports,
        "gradle_summaries": summaries,
    }


def harvest(since: str) -> dict:
    out = {"since": since, "repo": REPO, "workflow": WORKFLOW, "job": JOB, "runs": []}
    for r in runs_since(since):
        rec = {k: r[k] for k in ("databaseId", "headSha", "headBranch", "event", "status", "conclusion", "createdAt")}
        rec["awaits_per_run"] = awaits_per_run(r["headSha"])
        rec["attempts"] = []
        if r["status"] != "completed":
            rec["verdict"] = "excluded: run not complete"
            out["runs"].append(rec)
            continue
        n_attempts = gh_json(f"/repos/{REPO}/actions/runs/{r['databaseId']}", ".run_attempt") or 1
        for attempt in range(1, int(n_attempts) + 1):
            jobs = gh_json(
                f"/repos/{REPO}/actions/runs/{r['databaseId']}/attempts/{attempt}/jobs",
                f'[.jobs[] | select(.name == "{JOB}") | {{id, conclusion, started_at, completed_at}}]',
            ) or []
            for j in jobs:
                log = job_log(j["id"])
                a = {"attempt": attempt, **j}
                if log is None:
                    a["verdict"] = "excluded: log unavailable (retention?)"
                else:
                    a.update(classify(log))
                    if not a["wire_test_seen"]:
                        a["verdict"] = "excluded: :wire:test never reached"
                    elif a["wire_test_replayed"]:
                        a["verdict"] = "excluded: :wire:test replayed, not executed"
                    elif not a["stress_lifecycle"]:
                        a["verdict"] = "excluded: stress test did not report a lifecycle line"
                    elif a["reports"]:
                        losses = sum(x["failures"] for x in a["reports"])
                        a["verdict"] = f"counted: {losses} loss(es) reported"
                    elif "FAILED" in a["stress_lifecycle"]:
                        a["verdict"] = "excluded: stress FAILED with no recognised report -> unrecognised, awaits unknown"
                    else:
                        a["verdict"] = "counted: 0 losses"
                rec["attempts"].append(a)
        out["runs"].append(rec)
    return out


def tally(data: dict) -> dict:
    counted, awaits, losses, excluded = 0, 0, 0, []
    lossy_runs = 0
    for r in data["runs"]:
        for a in r.get("attempts", []) or [{"verdict": r.get("verdict", "excluded: no attempt")}]:
            v = a.get("verdict", "")
            if not v.startswith("counted"):
                excluded.append((r["databaseId"], a.get("attempt"), v))
                continue
            counted += 1
            awaits += r["awaits_per_run"] or 0
            here = sum(x["failures"] for x in a.get("reports", []))
            losses += here
            if here:
                lossy_runs += 1
    return {
        "counted_runs": counted,
        "awaits": awaits,
        "losses": losses,
        # The run-level numerator, alongside the await-level one: see the
        # two-units note in the module docstring (computenet-dqy.64).
        "runs_with_loss": lossy_runs,
        "excluded": excluded,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", required=True, help="ISO8601 lower bound on run createdAt, exclusive")
    ap.add_argument("--json", type=Path, help="write the full per-run record here")
    args = ap.parse_args()

    data = harvest(args.since)
    if args.json:
        args.json.write_text(json.dumps(data, indent=2))
    t = tally(data)
    for r in data["runs"]:
        for a in r.get("attempts", []) or [{"attempt": "-", "verdict": r.get("verdict", "")}]:
            print(f"{r['databaseId']} a{a.get('attempt')} {r['createdAt']} {r['headSha'][:8]:9} "
                  f"{r['headBranch']:34} awaits={r['awaits_per_run']} "
                  f"markers={a.get('wire_test_markers')} life={a.get('stress_lifecycle')} "
                  f"-> {a.get('verdict')}")
    print()
    print(f"counted runs: {t['counted_runs']}   awaits: {t['awaits']}   losses: {t['losses']}")
    print(f"per-await:  {t['losses']}/{t['awaits']}      "
          f"per-run:  {t['runs_with_loss']}/{t['counted_runs']} runs carried >= 1 loss")
    for e in t["excluded"]:
        print(f"excluded: run {e[0]} attempt {e[1]}: {e[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
