#!/usr/bin/env python3
"""Pick the next batch of tasks under a feature that can safely run in parallel.

Why this exists: the batch rule is pure deterministic set logic over each task's
`files` claim, and it decides whether two agents end up editing the same file on
sibling branches. Re-deriving it in prose every round is how it drifts.

Resumable tasks (in_progress, mine) come first — `bd ready` cannot see them, so
without this they would never be picked back up and their feature could never
finish.

Usage: next-batch.py <feature-id> [--actor NAME]
       next-batch.py --capacity      # the capacity block alone, no feature id
Prints JSON: {"batch": [{id, model, files, worktree, branch, resumed}],
              "skipped": [{id, reason}], "warnings": [str],
              "running_elsewhere": [{id, files}],
              "verdict": str, "parked": [id],
              "capacity": {"cores": int, "max_parallel": int,
                           "load1": float|null, "advice": str|null}}

The batch is bounded on two axes, not one. Disjoint `files` claims prove it
will not merge into a conflict; `capacity_limit()` bounds how many of those
agents this machine can actually run without their Gradle builds starving each
other into wall-clock timeouts (computenet-k9d.2). `capacity` reports the
machine's core count and the cap that was applied, so the caller can quote a
number it did not have to guess — plus `load1`/`advice` from `load_advice()`,
which is what the machine is doing RIGHT NOW. `max_parallel` is an upper bound
the orchestrator may go under, not a target to fill.

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

`running_elsewhere` is every unit this actor has `in_progress` outside this
feature, with its claim. Those claims seed the disjointness test, so a unit
dispatched through SKILL.md 5f route 0 — a direct child of the epic, invisible
to a query about one feature — can no longer have its files handed to a second
agent (computenet-z6q2). It is emitted so the caller can see what a short batch
was held behind.
"""
import json
import os
import re
import subprocess
import sys

# A build process below this %CPU is idle (a parked IDE daemon), not a gate.
CPU_BUSY_PCT = 10.0


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


def _repo_root():
    """The worktree root, falling back to the cwd if git cannot say."""
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=True)
        return out.stdout.strip() or os.getcwd()
    except (OSError, subprocess.CalledProcessError):
        return os.getcwd()


def dir_claims(files, root=None):
    """Entries in a claim that name a DIRECTORY rather than files.

    Containment (below) is deliberate and correct — a directory claim and a
    file inside it are the same surface. What is NOT correct is writing the
    directory in the first place: it collides with every sibling beneath it, so
    claim-based batching stops discriminating and the epic is permanently
    over-serialised. computenet-ciz9 claimed ["doc/bench/", "bench/src/"] and
    every bench task in its epic nominally collided with it; the breakdown that
    hit this ran the documented collision check, got a useless answer, and
    handed the batching decision back to the orchestrator by hand
    (computenet-i5zr).

    The failure is quiet in the dangerous direction: not a wrong batch, a lost
    one, with nothing reporting that parallelism was given up. So this is
    ADVISORY — it never changes a batching decision, it says which claim to
    narrow.

    A path is a directory claim if it exists on disk as one. Non-existent
    paths are NOT flagged: a claim naming a file the task is about to CREATE is
    ordinary and must not be nagged about.

    A claim entry is REPO-ROOT-RELATIVE, so the root is resolved rather than
    taken from the cwd — check-files-claim.sh learned the same lesson the hard
    way, firing every census row on a bead whose files all exist when run from
    a subdirectory. The direction of failure here is worse than that one: a
    wrong root makes every isdir() false, so the detector reports nothing at
    all and reads exactly like "no directory claims". A linked worktree is a
    full tree, so its own root is the right answer inside one.
    """
    root = root or _repo_root()
    return sorted(f for f in {_norm(x) for x in files}
                  if f and f != "." and os.path.isdir(os.path.join(root, f)))


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


def load_advice(cores, cap):
    """Advisory only: is this box ALREADY too busy to fill `cap`?

    capacity_limit() sizes a lane from a quiet box, and deliberately does not
    use load average to do it (see its docstring: over a ~25s workload the
    1-minute figure lags in both directions). This is a different question,
    asked at a different moment. The cap answers "how many lanes fit on an idle
    machine"; this answers "what is on the machine right now, before I
    dispatch" — the one signal the orchestrator lacked at the moment of the
    decision, and the reason the cap must be read as an UPPER BOUND it may go
    under rather than a target to fill.

    Why the cap alone is not enough (computenet-2r22, recurrence of
    computenet-qmjd): qmjd's remedy assumed the repo-wide `./gradlew test` was
    the contention unit, so scoping each agent's gate would remove it. Scoping
    the TASK LIST does not scope the WORKERS — two scoped Gradle invocations
    still share one daemon pool, one build cache and one buildLogic.lock, and
    each spawns its own test-worker fan-out. Measured 2026-08-30 on MacBoo,
    16 cores, cap 3: two implementers, file-disjoint, BOTH gates scoped, load
    average 204.71 / 92.73 / 44.00 — a 1-minute figure ~13x core count. Nothing
    timed out, so it was a near miss, not a loss; the margin was luck.

    ONE REPO-WIDE GATE IS ENOUGH ON ITS OWN, so agent COUNT is not the load
    model (computenet-lx7t, recurrence of 2r22/qmjd). Measured 2026-09-03,
    MacBoo, 16 cores: TWO agents of which exactly one ran the repo-wide
    `./gradlew test` — the other's gate was scoped to two demo modules — read
    391/426/353 across three samples, ~25x core count. qmjd got 338-724 from
    THREE repo-wide gates and 2r22 got ~204 from two scoped ones, so the
    ordering is by repo-wide gates, not by agents: cap 3 was respected the
    whole session and was never the binding constraint. What that costs at the
    top rung is not slowness but lost agents — a `ps` and every `bd` write
    auto-backgrounded past their tool timeouts, and a reviewer dispatched into
    the ~400 window STALLED with no side effects, then completed normally once
    load fell to ~8.

    WHAT THIS IS NOT. It is a higher THRESHOLD on the same gate-blind reading,
    not a gate-aware model: `load1` cannot tell one repo-wide gate from three,
    and `capacity_limit()` still counts agents uniformly. qmjd's 338 (three
    wide gates) and lx7t's 426 (one) land on the same rung. The gate-aware half
    of lx7t is prose, in the two places a dispatch is written — SKILL.md 5b
    scopes a batch's gates, and 5e/merge-task.md now hold or scope a REVIEWER's
    — because scope is a property of the prompt, which this script does not
    write. Sizing lanes by declared gate scope would be the real model; it is
    not attempted here and lx7t's bead says so.

    Reading is one syscall and it is advisory, never subtractive: it does not
    lower `cap`, because a lagging instrument must not silently serialize a
    slot. The pathological rung says "dispatch NOTHING", which is not a
    contradiction of that: it is an EXPLICIT stop the caller can see and
    overrule, not a silent one, and an expiring slot outranks it (SKILL.md
    step 2). It puts a number in front of the orchestrator at dispatch time.
    `--capacity` prints this block alone, with no feature id, so a REVIEWER
    dispatch can consult it too — reviewer dispatches have no batch call and
    so never saw this advice, which is how the agent that caused the spike was
    the one dispatched without reading it.
    """
    try:
        load1 = os.getloadavg()[0]
    except (OSError, AttributeError):      # not available on this platform
        return None, None
    load1 = round(load1, 2)
    # The pathological rung is NOT gated on `cap`: it is advice about
    # dispatching ANYTHING, including the single reviewer that has no cap.
    if load1 >= 5 * cores:
        busy = busy_builds()
        if busy is None:
            return load1, (f"load1 {load1} is >=5x the {cores} cores: PATHOLOGICAL, "
                           f"and `ps` could not be read, so whether the load is "
                           f"OURS is UNKNOWN. Hold — a wrong hold costs one "
                           f"deferred dispatch, a wrong dispatch stalls an agent "
                           f"outright. Check by hand: "
                           f"`ps -eo pid,pcpu,comm | sort -k2 -rn | head`.")
        if busy:
            return load1, (f"load1 {load1} is >=5x the {cores} cores: PATHOLOGICAL, "
                           f"and it is OURS ({busy}). Dispatch NOTHING — an agent "
                           f"dispatched into this window stalls, and a timeout in a "
                           f"module the diff does not touch is contention, not a "
                           f"finding. Wait for it to finish; recovery is abrupt "
                           f"(426 -> 8.57 in one measured case). Confirm with "
                           f"`ps -eo pid,pcpu,comm | sort -k2 -rn | head`.")
        return load1, (f"load1 {load1} is >=5x the {cores} cores, but NO build of "
                       f"ours is running: this is HOST load (endpoint-security "
                       f"scanning our build tree is the measured cause, and it stays "
                       f"high long after the build exits). There is nothing to wait "
                       f"for, so do not idle. Dispatch ONE agent with a SCOPED gate "
                       f"and expect it to be slow, not wrong. Confirm with "
                       f"`ps -eo pid,pcpu,comm | sort -k2 -rn | head` before "
                       f"overriding either way.")
    if cap <= 1:
        return load1, None
    if load1 >= 2 * cores:
        return load1, (f"load1 {load1} is >=2x the {cores} cores: dispatch ONE "
                       f"agent, not {cap}, and wait for it")
    if load1 >= cores:
        return load1, (f"load1 {load1} already meets the {cores} cores: go "
                       f"under the cap of {cap}")
    return load1, None


def busy_builds(ps_output=None):
    """Names of OUR build processes actually burning CPU right now.

    Three answers, not two: a string names them, `""` means `ps` was read and
    none are running, and `None` means `ps` could not be read at all. The third
    is separate because collapsing it into `""` would make the advice assert
    "NO build of ours is running" about a fact it just failed to determine —
    the very defect this function exists to remove, one level down. Unknown
    holds, because the lx7t case genuinely stalls agents and inverts verdicts
    while a spurious hold costs one deferred dispatch.

    `load_advice()`'s pathological rung used to assert its own cause — "wait for
    whatever is in flight, typically a repo-wide gate" — without ever checking
    it. On a corporate-managed box that cause is routinely false: measured
    2026-09-04 on MacBoo, load1 sat at 316/263/198 for ~25 minutes with the top
    consumers being an app-control system extension (54%), Microsoft Defender
    (36%) and WindowServer, and `pgrep -fl java` finding only two IDLE JetBrains
    daemons. macOS load average counts uninterruptible I/O wait, so a scanner
    working through a build tree inflates load1 enormously and keeps it inflated
    long after the build that caused it exited. The prescribed remedy — wait for
    the gate — is then unreachable: there is no gate, and a session following the
    text literally idles indefinitely (computenet-91xn).

    So the rung now checks the cause it names. This is deliberately coarse: any
    java/gradle/kotlin process above CPU_BUSY_PCT counts as ours and the hold
    stands unchanged. It only has to separate "a build is running" from "nothing
    of ours is running at all", which is the case the prose got wrong.
    """
    if ps_output is None:
        try:
            ps_output = subprocess.run(["ps", "-Ao", "pcpu,comm"],
                                       capture_output=True, text=True,
                                       timeout=10).stdout
        except (OSError, subprocess.SubprocessError):
            return None                    # can't tell -> hold (see docstring)
    hits = []
    for line in ps_output.splitlines():
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        try:
            pcpu = float(parts[0])
        except ValueError:                 # the header row
            continue
        name = parts[1].strip()
        low = name.lower()
        if pcpu >= CPU_BUSY_PCT and ("java" in low or "gradle" in low
                                     or "kotlin" in low):
            hits.append(f"{name} at {pcpu:.0f}%")
    return ", ".join(hits)


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

    WHAT THE ARMS ABOVE WERE, since it decides how far the cap can be trusted
    (computenet-2r22 asked; the first two answers written here were wrong in
    turn, so read this against the HONEST LIMITS paragraph below rather than
    on its own): on the 16-core machine — the only one with arms, and the one
    that pins LANE_CORES — every arm is a SCOPED, single-module
    `:wire:test --rerun`. The cap is therefore not derived from a repo-wide
    gate, and scoping each agent's gate buys no headroom the cap has not
    already spent: the 204-on-16-cores reading in load_advice() is this
    derivation's predicted direction, not a surprise, and the "if anything
    optimistic" caveat below covers it. The 10-core record is not an arm at
    all — it is three whole IMPLEMENTER lanes, whose gates at that date were
    repo-wide (per qmjd, which postdates it) — so it cannot corroborate this
    either way.

    The criterion is per-run inflation, not throughput: a bounded wait sized on
    a quiet box is what breaks. Under 2x it holds; the recorded catastrophes are
    ~90x, not 2x. On 16 cores the largest arm that stayed under 2x was 3.

    Load average is deliberately NOT the instrument HERE (load_advice() uses it
    for a different question — what the box is doing now — and says why that is
    sound; do not delete that advisory on this paragraph's authority). Over a
    ~25s workload the 1-minute average is still climbing when the build ends and
    still decaying when the next arm starts (N=3's pre-arm reading was 17.31,
    inherited from N=2), so it lags in both directions. Wall clock per run is
    measured directly.

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

    WHY THE SPLIT FLOORS RATHER THAN ROUNDS (computenet-rjpu). On 16 cores
    with one sibling the fair share of 3 lanes is 1.5, and this returns 1,
    which serialized a slot of sub-minute :oracle:test units while the
    sibling ran no Gradle at all. That cost is real and measured — and so is
    the other side: sessions cannot coordinate who takes the extra lane, so
    two sessions each rounding up run 4 lanes against a measured-safe 3,
    which is the computenet-arow failure again. Until the build-check arms
    below are measured, the floor division stands; do not round up, and do
    not weight by what a sibling "is doing" — that is unobservable here.
    A sibling count inflated by stale holders is computenet-nkz3's defect,
    not this formula's.

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


def dir_claim_warnings(candidates, batch, skipped):
    """Advisory lines for every directory-shaped claim among the candidates.

    A function rather than inline in main() so the WIRING is testable: the
    deliverable is a message reaching a reader, and `dir_claims` being correct
    in isolation does not deliver it — deleting this loop from main() left the
    suite green (computenet-i5zr review).

    Scans `skipped` as well as `batch`: whether the over-broad claim WINS the
    surface or loses it to a narrower sibling is `bd ready` ordering, which
    nobody controls, and cap_batch demotes batched entries to skipped before
    this runs. Scanning batch alone made the report a coin flip, and an empty
    result would then not mean "no directory claims".
    """
    by_id = {task["id"]: task for task, _ in candidates}
    out = []
    for tid in [e["id"] for e in batch] + [s["id"] for s in skipped]:
        task = by_id.get(tid)
        for d in dir_claims(claim_of(task) if task else []):
            out.append(
                "%s claims the DIRECTORY %s -- it collides with every claim "
                "beneath it, so this epic batches more serially than it needs "
                "to. Narrow it to the files the item actually edits: "
                "bd update %s --set-metadata files=<paths>" % (tid, d, tid))
    return out


def plan_batch(candidates, feature=None, elsewhere=()):
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
    # Seed with what is already running outside this feature (computenet-z6q2),
    # so an overlap with a route-0 unit is skipped by the same rule that skips
    # an overlap with a sibling — no second implementation, no memory.
    outside = {}
    for unit in elsewhere:
        for f in unit["files"]:
            outside[f] = unit["id"]
    taken |= set(outside)
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
            # Name the RUNNING UNIT, not just the path: the caller's next move
            # for "overlaps a sibling in this batch" is to wait one round, and
            # for "overlaps a unit running elsewhere" it is to check whether
            # that unit is still alive.
            owners = sorted({outside[c] for c in collisions if c in outside})
            reason = "overlaps " + ",".join(sorted(collisions))
            if owners:
                reason += " — running outside this feature: " + ",".join(owners)
            skipped.append({"id": tid, "reason": reason})
            continue
        taken |= files
        batch.append(_entry(task, resumed, sorted(files), feature))
    return batch, skipped


def running_elsewhere(actor, feature, candidate_ids):
    """Units this actor has in flight OUTSIDE this feature, with their claims.

    next-batch.py is asked about ONE feature and, until computenet-z6q2, could
    only see that feature's children. A unit taken onto a free lane through
    SKILL.md 5f route 0 — a DIRECT CHILD of the epic, on its own branch and PR
    — is invisible to it by construction, and route 0's disjointness test is a
    one-time admission check that nothing re-applies on later batches. So the
    script offered a task whose files a live route-0 unit was actively editing;
    only the orchestrator's memory of a dispatch several turns earlier stopped
    it, three hours into a session. That save does not repeat.

    Everything `in_progress` and assigned to this actor counts, minus the
    candidates themselves (this feature's resumable tasks are legitimately in
    the batch) and minus epics and this feature, which claim no files of their
    own. A STALE claim from a dead session therefore blocks a batch — the
    conservative direction, and visible: every unit found is reported in the
    output, so the caller can see what it was held behind rather than
    wondering why the batch is short.
    """
    out = []
    for task in bd("list", "--status", "in_progress", "--assignee", actor):
        tid = task.get("id")
        if not tid or tid in candidate_ids or tid == feature:
            continue
        if task.get("issue_type") == "epic":
            continue
        try:
            files = claim_of(task)
        except ClaimError:
            continue                      # not ours to diagnose; 5b names it
        if files:
            out.append({"id": tid, "files": sorted(files)})
    return out


def _siblings():
    """Other live /work sessions sharing this box: --siblings N, else
    WORK_SIBLINGS, else 0. Shared by the batch path and --capacity, which must
    not disagree about the cap."""
    if "--siblings" in sys.argv:
        try:
            return max(0, int(sys.argv[sys.argv.index("--siblings") + 1]))
        except (IndexError, ValueError):
            sys.exit("next-batch: --siblings takes a non-negative integer")
    if os.environ.get("WORK_SIBLINGS"):
        try:
            return max(0, int(os.environ["WORK_SIBLINGS"]))
        except ValueError:
            sys.exit("next-batch: WORK_SIBLINGS must be a non-negative integer")
    return 0


def main():
    if "--capacity" in sys.argv:
        # Capacity alone, no feature id: for a dispatch that has no batch call
        # of its own (every reviewer dispatch). computenet-lx7t.
        rest = [a for a in sys.argv[1:] if a != "--capacity"]
        if "--siblings" in rest:
            i = rest.index("--siblings")
            rest = rest[:i] + rest[i + 2:]
        if rest:
            sys.exit("next-batch.py: --capacity takes no feature id "
                     f"(got {rest[0]!r}); it reports the box, not a batch")
        cores = os.cpu_count() or 1
        # The cap is per-session on a shared box, so honour --siblings here too
        # or --capacity would name a cap the caller may not actually have.
        cap = capacity_limit(cores, _siblings())
        load1, advice = load_advice(cores, cap)
        print(json.dumps({"capacity": {"cores": cores, "max_parallel": cap,
                                       "load1": load1, "advice": advice}},
                         indent=2))
        return
    if len(sys.argv) < 2:
        sys.exit("usage: next-batch.py <feature-id> [--actor NAME] [--siblings N]\n"
                 "       next-batch.py --capacity")
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

    elsewhere = running_elsewhere(actor, feature, {t["id"] for t, _ in candidates})
    try:
        batch, skipped = plan_batch(candidates, feature, elsewhere)
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
    siblings = _siblings()
    cap = capacity_limit(cores, siblings)
    batch, skipped = cap_batch(batch, skipped, cap)
    load1, advice = load_advice(cores, cap)

    verdict, parked = _assess(feature, batch)
    warnings = []
    warnings = dir_claim_warnings(candidates, batch, skipped)
    print(json.dumps({"batch": batch, "skipped": skipped,
                      "warnings": warnings,
                      "running_elsewhere": elsewhere,
                      "verdict": verdict, "parked": parked,
                      "capacity": {"cores": cores, "siblings": siblings,
                                   "max_parallel": cap,
                                   "load1": load1, "advice": advice}},
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
