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
              "skipped": [{id, reason}], "verdict": str, "parked": [id]}

`verdict` explains an *empty* batch, which the caller cannot infer from
`batch`/`skipped` alone and must not guess: "all-closed" (feature is ready for
review), "parked-residue" (every task that is not closed is a human park, so
the feature is finished and its residue is a deliverable — also ready for
review), "blocked" (tasks remain but none can run), "no-tasks" (the breakdown
produced nothing). It is "ok" whenever the batch is non-empty.

`parked` names the human parks behind that verdict — the ids the 5e handoff
has to list so the reviewer knows they are deferred by design, not missed
(computenet-k9d.4). It is emitted rather than left to the caller because the
caller's alternative is a hand-written `bd list` filter, i.e. a second
implementation of is_human_park() that can drift from this one. Like
`verdict`, it is meaningful only when `batch` is empty: a non-empty batch does
not query the children at all, so `parked` is [] there and means "not looked
at", not "none exist".

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
    """Files a task expects to touch. Empty set means 'unknown', not 'none'.

    Paths are normalised (no leading "./", no trailing "/") so that containment
    below compares like with like.
    """
    raw = (task.get("metadata") or {}).get("files") or ""
    return {_norm(p) for p in raw.split(",") if p.strip()}


def _norm(path):
    """Repo-relative form with no leading './' and no trailing '/'."""
    p = path.strip().strip("/")
    while p.startswith("./"):
        p = p[2:]
    return p


def overlaps(files, taken):
    """Claims that collide with `files` — by containment, not string equality.

    A directory claim and a file inside it are the same surface: two agents
    handed 'wire/src/test/kotlin/civictech/wire' and
    'wire/src/test/kotlin/civictech/wire/WsConnectRaceTest.kt' edit the same file
    on sibling branches, which is exactly the merge conflict this batching rule
    exists to prevent. A plain set intersection reports them disjoint because the
    strings differ (computenet-9eb). Containment is checked in BOTH directions,
    since either claim may be the broader one. Inputs are normalised here too,
    so the function is safe for a caller that did not go through claim_of().
    """
    hits = set()
    for f in {_norm(x) for x in files}:
        for t in {_norm(x) for x in taken}:
            # "." is the whole repo: it contains every claim, including itself.
            if "." in (f, t) or f == t or f.startswith(t + "/") or t.startswith(f + "/"):
                hits.add(t)
    return hits


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
        # Human-gated beads sit in bd ready like ordinary work, but they are
        # decision gates: dispatching one hands an agent a call a human
        # explicitly reserved. Surface them so the caller can park/defer.
        if "human" in (task.get("labels") or []):
            skipped.append({"id": tid, "reason": "human-gated"})
            continue
        files = claim_of(task)
        if not files:
            if batch:
                skipped.append({"id": tid, "reason": "no files claim; must run alone"})
                continue
            batch.append(_entry(task, resumed, sorted(files)))
            already = {s["id"] for s in skipped}
            skipped.extend({"id": t["id"], "reason": "deferred behind unclaimed-files task"}
                           for t, _ in candidates if t["id"] != tid and t["id"] not in already)
            break
        collisions = overlaps(files, taken)
        if collisions:
            skipped.append({"id": tid,
                            "reason": "overlaps " + ",".join(sorted(collisions))})
            continue
        taken |= files
        batch.append(_entry(task, resumed, sorted(files)))

    verdict, parked = _assess(feature, batch)
    print(json.dumps({"batch": batch, "skipped": skipped,
                      "verdict": verdict, "parked": parked}, indent=2))


def _assess(feature, batch):
    """Why the batch is empty, and which children are parks behind that.

    The caller routes on the verdict and hands the ids to the 5e reviewer, so
    never guess either. One `bd list` serves both — they are two readings of
    the same child set, and re-querying would let them disagree.
    """
    if batch:
        return "ok", []
    # --all: closed tasks are hidden otherwise, which would read as "no tasks"
    # for a finished feature and send it round the breakdown again.
    children = [t for t in bd("list", "--parent", feature, "--all")
                if t.get("issue_type") not in ("epic", "feature")]
    return classify(children), parked_ids(children)


def is_human_park(task):
    """Is this child an `ask-human.md` park rather than remaining work?

    That park is `--status=blocked --add-label=human --assignee=human`. We
    require `blocked` — that is the load-bearing half, the thing that says the
    item cannot proceed on its own — plus EITHER human marker, because items
    parked before all three flags settled carry only one of them and keying on
    all three would silently under-match them back into "blocked".

    The cost of accepting either marker: a child blocked by a real dependency
    edge that inherited the `human` label from a parked parent (`bd create`
    inherits labels — see ask-human.md) reads as a park here. See classify()
    for what stops that becoming damage.
    """
    if task.get("status") != "blocked":
        return False
    return task.get("assignee") == "human" or "human" in (task.get("labels") or [])


def classify(children):
    """Verdict for an empty batch, from the feature's children alone.

    Split out from _verdict() so it is testable without a beads database.

    "blocked" used to swallow the case that matters most: a feature whose tasks
    are all closed and reviewed, whose only open children are follow-up beads
    its own implementation filed and parked for a human (computenet-eic,
    observed on computenet-yh6.1.3). Parking that feature strands finished,
    CI-green work in a draft PR with no path to main. Those children are
    deliverables, not remaining work, so the feature is ready for review —
    "parked-residue", distinct from "all-closed" so the caller can still see
    that residue exists.

    A child that is open, in_progress (including on another machine), or
    blocked on a real dependency keeps the verdict at "blocked": one genuinely
    unmet child is enough. The residual false positive is the inherited-label
    case in is_human_park(); it is bounded because "parked-residue" routes to
    the 5e feature review, which re-reads the acceptance criteria against the
    diff and can return a draft verdict that sends the feature back to 5b — it
    does not merge anything on its own.
    """
    if not children:
        return "no-tasks"
    unfinished = [t for t in children if t.get("status") != "closed"]
    if not unfinished:
        return "all-closed"
    if all(is_human_park(t) for t in unfinished):
        return "parked-residue"
    return "blocked"


def parked_ids(children):
    """Ids of the unfinished children that read as `ask-human.md` parks.

    Split out alongside classify() so it is testable without a beads database,
    and so the set the 5e handoff names is produced by the SAME predicate the
    verdict is decided by. On a "parked-residue" verdict this is exactly the
    unfinished set; on "blocked" it is the parks that were outvoted, which is
    honest but unused — 5f does not dispatch a reviewer.

    Sorted, so the handoff string is stable across runs and two orchestrators
    reading the same feature quote the same list. Ids are the whole payload:
    the reviewer is told to re-check each one against the bead itself, because
    is_human_park() cannot distinguish a park from a child that inherited the
    `human` label from a parked parent.
    """
    return sorted(t.get("id") for t in children
                  if t.get("id") and t.get("status") != "closed"
                  and is_human_park(t))


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
