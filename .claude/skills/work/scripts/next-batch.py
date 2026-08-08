#!/usr/bin/env python3
"""Pick the next batch of tasks under a feature that can safely run in parallel.

Why this exists: the batch rule is pure deterministic set logic over each task's
`files` claim, and it decides whether two agents end up editing the same file on
sibling branches. Re-deriving it in prose every round is how it drifts.

Resumable tasks (in_progress, mine) come first — `bd ready` cannot see them, so
without this they would never be picked back up and their feature could never
finish.

Usage: next-batch.py <feature-id> [--actor NAME]
Prints JSON: {"batch": [{id, model, files, worktree, branch, resumed}],
              "skipped": [{id, reason}], "verdict": str}

`verdict` explains an *empty* batch, which the caller cannot infer from
`batch`/`skipped` alone and must not guess: "all-closed" (feature is ready for
review), "blocked" (tasks remain but none can run), "no-tasks" (the breakdown
produced nothing). It is "ok" whenever the batch is non-empty.

A task with no `files` claim can't be scheduled against anything, so it is only
ever returned alone — safer than guessing a claim for it.
"""
import json
import os
import subprocess
import sys


def bd(*args):
    out = subprocess.run(["bd", *args, "--json"], capture_output=True, text=True)
    if out.returncode != 0:
        return []
    try:
        return json.loads(out.stdout or "[]")
    except json.JSONDecodeError:
        return []


def claim_of(task):
    """Files a task expects to touch. Empty set means 'unknown', not 'none'."""
    raw = (task.get("metadata") or {}).get("files") or ""
    return {p.strip() for p in raw.split(",") if p.strip()}


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: next-batch.py <feature-id> [--actor NAME]")
    feature = sys.argv[1]
    actor = os.environ.get("BEADS_ACTOR", "")
    if "--actor" in sys.argv:
        actor = sys.argv[sys.argv.index("--actor") + 1]
    if not actor:
        sys.exit("BEADS_ACTOR must be set, uniquely, per machine")

    # Resumable first, then newly ready. bd ready hides in_progress; bd list
    # needs an explicit status. Neither alone sees the whole picture.
    resumable = bd("list", "--parent", feature, "--status", "in_progress",
                   "--assignee", actor)
    ready = bd("ready", "--parent", feature)

    seen, candidates = set(), []
    for task, resumed in [(t, True) for t in resumable] + [(t, False) for t in ready]:
        tid = task.get("id")
        if not tid or tid in seen:
            continue
        if task.get("issue_type") in ("epic", "feature"):
            continue                      # --parent is transitive; skip the tree
        seen.add(tid)
        candidates.append((task, resumed))

    batch, skipped, taken = [], [], set()
    for task, resumed in candidates:
        tid = task["id"]
        files = claim_of(task)
        if not files:
            if batch:
                skipped.append({"id": tid, "reason": "no files claim; must run alone"})
                continue
            batch.append(_entry(task, resumed, sorted(files)))
            skipped.extend({"id": t["id"], "reason": "deferred behind unclaimed-files task"}
                           for t, _ in candidates if t["id"] != tid)
            break
        if files & taken:
            skipped.append({"id": tid,
                            "reason": "overlaps " + ",".join(sorted(files & taken))})
            continue
        taken |= files
        batch.append(_entry(task, resumed, sorted(files)))

    print(json.dumps({"batch": batch, "skipped": skipped,
                      "verdict": _verdict(feature, batch)}, indent=2))


def _verdict(feature, batch):
    """Why the batch is empty. The caller routes on this, so never guess it."""
    if batch:
        return "ok"
    # --all: closed tasks are hidden otherwise, which would read as "no tasks"
    # for a finished feature and send it round the breakdown again.
    children = [t for t in bd("list", "--parent", feature, "--all")
                if t.get("issue_type") not in ("epic", "feature")]
    if not children:
        return "no-tasks"
    if all(t.get("status") == "closed" for t in children):
        return "all-closed"
    return "blocked"


def _entry(task, resumed, files):
    meta = task.get("metadata") or {}
    tid = task["id"]
    return {
        "id": tid,
        "model": meta.get("model") or "",     # empty => breakdown omitted it
        "files": files,
        "worktree": meta.get("worktree") or f"../computenet-worktrees/{tid}",
        "branch": meta.get("branch") or f"task/{tid}",
        "resumed": resumed,
    }


if __name__ == "__main__":
    main()
