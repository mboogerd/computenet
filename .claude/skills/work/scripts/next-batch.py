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
              "skipped": [{id, reason}], "verdict": str, "parked": [id],
              "capacity": {"cores": int, "max_parallel": int}}

The batch is bounded on two axes, not one. Disjoint `files` claims prove it
will not merge into a conflict; `capacity_limit()` bounds how many of those
agents this machine can actually run without their Gradle builds starving each
other into wall-clock timeouts (computenet-k9d.2). `capacity` reports the
machine's core count and the cap that was applied, so the caller can quote a
number it did not have to guess.

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
import re
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


class ClaimError(Exception):
    """A metadata.files claim that is present but unreadable.

    Named rather than raised as a bare AttributeError: the traceback for
    `'list' object has no attribute 'split'` names split() on a list, not
    WHICH bead is malformed, so an orchestrator with several candidates in
    the batch cannot tell which one to fix without bisecting
    (computenet-tbzg).
    """


def claim_of(task):
    """Files a task expects to touch. Empty set means 'unknown', not 'none'.

    BOTH shapes are accepted, because both are in circulation from sanctioned
    paths: breakdowns write the comma-separated string
    ("a/b.kt,c/d.kt") and reviewers filing residuals through
    create-ticket.sh write a JSON list (["a/b.kt", "c/d.kt"]). Only the
    string form parsed until 2026-08-19, so a batch containing a
    reviewer-filed residual aborted with a traceback at the one step that
    decides what may run in parallel (computenet-tbzg).

    Paths are normalised (no leading "./", no trailing "/") so that containment
    below compares like with like.
    """
    raw = (task.get("metadata") or {}).get("files") or ""
    if isinstance(raw, str):
        parts = raw.split(",")
    elif isinstance(raw, (list, tuple)):
        parts = []
        for p in raw:
            if not isinstance(p, str):
                raise ClaimError(
                    "%s: metadata.files list holds a %s, expected strings"
                    % (task.get("id", "<unknown bead>"), type(p).__name__)
                )
            parts.extend(p.split(","))
    else:
        raise ClaimError(
            "%s: metadata.files is a %s, expected a comma-separated string "
            "or a list of strings" % (task.get("id", "<unknown bead>"),
                                      type(raw).__name__)
        )
    return {_norm(p) for p in parts if p.strip()}


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


# Cores one dispatched agent needs to itself. See capacity_limit().
LANE_CORES = 5


def capacity_limit(cores, siblings=0):
    """How many agents THIS session may dispatch at once, given `siblings` other
    live /work sessions sharing the same `cores`.

    Disjoint `files` claims prove a batch will not merge into a conflict. They
    say nothing about whether the machine can *run* it: every task in this repo
    drives Gradle, and a batch of eight provably-disjoint items contends for
    cores, the Gradle cache locks and the Kotlin daemon's memory
    (computenet-k9d.2). That is not merely slow. The timeouts it produces land
    on bounded waits — `awaitUntil`/`awaitDrained` raise `AssertionFailedError`
    on a starved host — and those same suites are already filed as intermittent,
    so contention noise is indistinguishable from the flakes those epics exist
    to characterise. The parallelism corrupts the evidence.

    So the cap is a second axis on the batch, and it is expressed **per core**,
    because the two machines running this skill have different core counts and
    a bare integer measured on one is wrong on the other.

    MEASURED, 2026-08-14, on the 16-core machine (`sysctl -n hw.ncpu` = 16),
    otherwise idle, no other agent dispatched. N cold worktrees each running
    `./gradlew :wire:test --rerun` concurrently against a primed shared build
    cache (every run: `BUILD SUCCESSFUL`, `24 actionable tasks: 10 executed,
    14 from cache`), Kotlin and Gradle daemons killed between arms:

        N=1   20.5s                                     1.00x
        N=2   24.7s, 25.4s                              1.22x
        N=3   34.0s, 34.8s, 35.0s                       1.70x
        N=6   89.9s 93.1s 95.6s 96.6s 97.2s 97.8s      4.4x-4.8x

    The criterion is per-run inflation, not throughput: a bounded wait sized on
    a quiet box is what breaks. Under 2x it holds; the recorded catastrophes are
    ~90x, not 2x. On 16 cores the largest arm that stayed under 2x was 3.

    Load average is deliberately NOT the instrument. Over a ~25s workload the
    1-minute average is still climbing when the build ends and still decaying
    when the next arm starts (N=3's pre-arm reading was 17.31, inherited from
    N=2), so it lags in both directions. Wall clock per run is measured
    directly.

    WHY cores/5 AND NOT cores/3. `cores/3` was the value floated in
    computenet-avs's thread, and this bead's own evidence contradicts it: on the
    10-core machine `cores/3` is 3, and three concurrent implementers is the
    configuration recorded there as catastrophic — load 112, one
    `:wire:test --rerun` inflating from 6-10s to 14m30s, roughly 90x. The same
    thread records "2 on a 10-core machine looked survivable, 3 did not".
    `cores/5` reproduces both anchors: 2 on 10 cores (that thread's judgement —
    every 10-core datum in it is N=3; N=2 was never run there) and 3 on 16
    cores (the largest arm measured under 2x here). The arithmetic agrees to
    within a rounding step — 10/2 = 5.0, 16/3 = 5.3 — so a Gradle lane in this
    repo costs about five cores, not the one core a raw agent count implies and
    not the three `cores/3` assumes.

    HONEST LIMITS, so a later reader does not over-trust the table above.
    One workload (`:wire:test`, ~20s quiet, 10 of 24 tasks executed); a
    full-lane `./gradlew build check` is heavier and would knee earlier, so
    `cores/5` is if anything optimistic. Single trial per arm: the 1.22x at
    N=2 is within noise of 1.0, the 1.70x and 4.4x-4.8x are not, and N=4/N=5
    were never run — "3 is the largest arm under 2x" is a statement about the
    arms measured. Sixteen cores is the only machine measured, so LANE_CORES
    is pinned by one point (16/3) and corroborated by an unmeasured one; it
    errs conservatively on the smaller box, where 3 is the only configuration
    ever recorded and it went both ways: computenet-avs logs N=3 on 10 cores
    three times — load 14.75 with memory comfortable, a whole session green
    with no contention failure, and the load-112 catastrophe. The cap is set
    against the worst of those three, not against a uniform result, so on the
    10-core record alone `cores/5` is tighter than the evidence compels. The
    2x line is itself a chosen
    round number — the actual headroom in the bounded waits is unmeasured, and
    2x is defended by being far from the ~90x that produced the damage, not by
    a measurement of when a wait first trips. What would settle it: the same
    arms with `./gradlew build check` on both machines, three repeats each.

    Floor of 1: a batch is never emptied by the cap, which would turn "ok" into
    a verdict the caller routes on. One agent always runs.

    THE CAP IS PER SESSION, AND THE MACHINE IS SHARED. `siblings` is how many
    OTHER /work sessions are live on this box; the budget is split between
    them, because the contention above is a property of the MACHINE and this
    function has no other way to know. Measured on Anva@A0030 2026-08-17:
    hw.ncpu = 10 so the cap is 2, and at that moment `ps` showed FOUR live
    Claude Code CLI processes with four sibling session worktrees beside them
    — each computing 2 independently, each of the four looking correct by its
    own accounting, for 4x the measured safe parallelism on one box
    (computenet-arow). The 8-agent figure there is arithmetic from the observed
    session count, not an observed run; what was observed is the session count,
    the core count and the formula.

    Floor of 1 again on the division: a session that knows it has siblings
    still gets one agent, so concurrency degrades to serial rather than to
    deadlock.
    """
    return max(1, (cores // LANE_CORES) // max(1, 1 + siblings))


def cap_batch(batch, skipped, cap):
    """Trim a file-disjoint batch to what the machine can run, in place order.

    Order is load-bearing, and it is the order main() already built: resumable
    tasks first. A resumed task holds a worktree and a branch with commits on
    it; deferring it behind a fresh one leaves that work stranded for another
    round. So the trim falls on the newly-ready tail first. A resumable is
    held back only when the resumables alone exceed the cap — never in favour
    of a fresh task — and it keeps its worktree and branch either way.

    The trimmed items are reported in `skipped` with a reason naming the cap,
    not dropped silently — the caller has to be able to tell "held back for
    capacity" (dispatch it next round, nothing is wrong) from "overlaps"
    (a claim problem) or "human-gated" (a decision).
    """
    if cap >= len(batch):
        return batch, skipped
    held = batch[cap:]
    skipped = skipped + [
        {"id": e["id"], "reason": f"over machine capacity (max {cap} at once)"}
        for e in held]
    return batch[:cap], skipped


def plan_batch(candidates, feature=None):
    """(batch, skipped) from `[(task, resumed)]` — the claim-disjointness rule.

    A task with no `files` claim is returned ALONE and everything else is
    deferred behind it. That is deliberate for both shapes feature.md allows
    an empty claim (diagnosis-first, and a zero-diff measurement whose
    deliverable is a comment or a run id): neither can be proven disjoint from
    a sibling, and serialising a task that writes nothing costs nothing
    (computenet-wpvy.30). Note what does NOT reach that branch — a descriptive
    string in `files` ("none (tracker mutations only)") is a non-empty claim
    over a path that does not exist, and batches like any other.
    """
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
            batch.append(_entry(task, resumed, sorted(files), feature))
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
        batch.append(_entry(task, resumed, sorted(files), feature))
    return batch, skipped


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: next-batch.py <feature-id> [--actor NAME] [--siblings N]")
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

    try:
        batch, skipped = plan_batch(candidates, feature)
    except ClaimError as exc:
        # Named and actionable: the bead id, what its claim looked like, and
        # the one fix. A bare traceback here names split() on a list and not
        # WHICH of several candidates is malformed (computenet-tbzg).
        sys.exit("next-batch: unreadable file claim\n  %s\n"
                 "Fix that bead's metadata.files -- a comma-separated string "
                 "(\"a/b.kt,c/d.kt\") or a JSON list of strings -- then re-run:\n"
                 "  bd update <id> --set-metadata files=a/b.kt,c/d.kt" % exc)

    cores = os.cpu_count() or 1
    # Siblings are discovered by the orchestrator (step 3's liveness check) and
    # passed in; this script cannot see them. Default 0 = "I am alone", which
    # is the pre-2026-08-19 behaviour.
    siblings = 0
    if "--siblings" in sys.argv:
        try:
            siblings = max(0, int(sys.argv[sys.argv.index("--siblings") + 1]))
        except (IndexError, ValueError):
            sys.exit("next-batch: --siblings takes a non-negative integer")
    elif os.environ.get("WORK_SIBLINGS"):
        try:
            siblings = max(0, int(os.environ["WORK_SIBLINGS"]))
        except ValueError:
            sys.exit("next-batch: WORK_SIBLINGS must be a non-negative integer")
    cap = capacity_limit(cores, siblings)
    batch, skipped = cap_batch(batch, skipped, cap)

    verdict, parked = _assess(feature, batch)
    print(json.dumps({"batch": batch, "skipped": skipped,
                      "verdict": verdict, "parked": parked,
                      "capacity": {"cores": cores, "siblings": siblings,
                                   "max_parallel": cap}},
                     indent=2))


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

    Split out from _assess() so it is testable without a beads database.

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


def branch_has_commits(branch):
    """Does a local ref `branch` exist and carry commits of its own?

    `resumed` was derived purely from status == in_progress AND assignee ==
    actor, so a task released by sweep-stale-claims.sh reads as NEVER TOUCHED
    — status reset to open, indistinguishable in the JSON from fresh work.
    Measured on computenet-4ru.5.1: a previous session had implemented it
    fully (two commits, 11 files, +1871 lines) and written a completion
    comment, then died before review; the sweep correctly released the claim
    (nothing had reviewed it), and this script then reported
    `"resumed": false` while handing back a worktree and branch that already
    held the deliverable. The documented next step for a non-resumed entry is
    to dispatch an implementer — a second one, onto a finished branch
    (computenet-jw9x).

    The branch is the durable witness the bead status is not. Answers False on
    any error: git absent, not a repo, no such ref. A wrong False is the old
    behaviour, so this can only improve the reading, never degrade it.
    """
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", f"refs/heads/{branch}"],
            capture_output=True, text=True, timeout=10)
        if out.returncode != 0:
            return False
        merge_base = subprocess.run(
            ["git", "rev-list", "--count", f"origin/main..refs/heads/{branch}"],
            capture_output=True, text=True, timeout=10)
        return merge_base.returncode == 0 and merge_base.stdout.strip() not in ("", "0")
    except (OSError, subprocess.SubprocessError):
        return False


def merged_into_feature(tid, feature):
    """Is this task's work already on the FEATURE branch, by commit message?

    The cross-machine twin of branch_has_commits: a session on ANOTHER machine
    implemented the task, merged it into the feature branch, pushed, and died
    before review or close. Here the task branch and worktree do not exist, so
    branch_has_commits is False, `resumed` is False, and the entry reads as
    fresh work — an orchestrator following it would put a second implementer
    onto merged work (computenet-kklt, computenet-ssa.5.1: commit 907be6ae
    plus merge ca71e162 already on origin/feature/computenet-ssa.5). Every
    commit and merge in this skill carries the task id in its subject, so the
    feature branch's log is the witness. Looks at the local ref first, then
    origin's (the common case: the branch was never fetched into a local ref
    on this machine). False on any error, same contract as branch_has_commits.
    """
    for ref in (f"refs/heads/feature/{feature}", f"refs/remotes/origin/feature/{feature}"):
        try:
            out = subprocess.run(
                # Anchored: `--grep` is an unanchored substring regex, so a bare
                # id for task .5.1 would match a sibling's `.5.10` commit and
                # route fresh work to review (the inverse failure).
                ["git", "log", "--oneline", "-1", "-E",
                 f"--grep={re.escape(tid)}([^0-9.]|$)", ref],
                capture_output=True, text=True, timeout=10)
            if out.returncode == 0 and out.stdout.strip():
                return True
        except (OSError, subprocess.SubprocessError):
            return False
    return False


def _entry(task, resumed, files, feature=None):
    meta = task.get("metadata") or {}
    tid = task["id"]
    branch = meta.get("branch") or f"task/{tid}"
    # A branch carrying commits means resumed, whatever the bead status says.
    has_work = branch_has_commits(branch)
    on_feature = merged_into_feature(tid, feature) if feature else False
    return {
        "id": tid,
        "model": meta.get("model") or "",     # empty => breakdown omitted it
        "files": files,
        # Authorized writes to OTHER beads (computenet-eetn). Absent => none;
        # the orchestrator must relay this verbatim into the dispatch prompt,
        # so it is surfaced here rather than hand-grepped out of the prose.
        "cross_bead": meta.get("cross_bead") or "",
        "worktree": meta.get("worktree") or f"../computenet-worktrees/{tid}",
        "branch": branch,
        "resumed": bool(resumed) or has_work,
        # Set when the BRANCH says resumed and the bead status did not. The
        # orchestrator must inspect before dispatching: `git log` in the
        # worktree and `bd comments` on the bead, then route to 5c (review and
        # merge) rather than to an implementer, if the work is already done.
        "branch_has_commits": has_work,
        # The work is on the FEATURE branch already (a dead session on another
        # machine merged it). Route to 5c review against the merged range,
        # with the feature worktree standing in for the absent task worktree.
        "merged_into_feature": on_feature,
    }


if __name__ == "__main__":
    main()
