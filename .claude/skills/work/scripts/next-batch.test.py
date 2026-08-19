#!/usr/bin/env python3
"""Regression tests for next-batch.py: the claim-overlap rule (computenet-9eb),
the empty-batch verdict (computenet-eic), the parked-children ids that verdict
hands to the 5e review (computenet-k9d.4), and the claimless-runs-alone rule
that feature.md's zero-diff convention leans on (computenet-wpvy.30), and the
`cross_bead` field 5b relays verbatim into dispatch prompts (computenet-eetn).

Run: python3 .claude/skills/work/scripts/next-batch.test.py
"""
import importlib.util
import pathlib
import sys

spec = importlib.util.spec_from_file_location(
    "next_batch", pathlib.Path(__file__).with_name("next-batch.py"))
nb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nb)

DIR = "wire/src/test/kotlin/civictech/wire"
FILE = DIR + "/WsConnectRaceTest.kt"

cases = [
    # (files, taken, expect_overlap, what)
    ({FILE}, {DIR}, True, "file inside an already-taken directory"),
    ({DIR}, {FILE}, True, "directory containing an already-taken file"),
    ({DIR}, {DIR}, True, "identical claims"),
    ({"wire/src/main"}, {"wire/src/test"}, False, "sibling directories"),
    ({"wire/src/testkit/X.kt"}, {"wire/src/test"}, False,
     "prefix match that is not a path boundary"),
    ({"./" + DIR + "/"}, {DIR}, True, "overlaps() normalises its own inputs"),
    ({"."}, {DIR}, True, "whole-repo claim contains everything"),
    ({DIR}, {"./"}, True, "whole-repo claim on the taken side"),
]

failed = 0
for files, taken, expected, what in cases:
    got = bool(nb.overlaps(files, taken))
    if got != expected:
        failed += 1
        print(f"FAIL: {what} — expected overlap={expected}, got {got}")


# --- plan_batch(): a claimless task runs ALONE (computenet-wpvy.30) ---------
# feature.md now sends two legitimate shapes through this branch — a
# diagnosis-first task and a zero-diff measurement — and SKILL.md 5b tells the
# orchestrator the script "batches a claimless task alone either way". That
# invariant was prose-only until here.

def t(id, files=None, labels=None):
    task = {"id": id, "issue_type": "task", "status": "open",
            "labels": labels or []}
    if files is not None:
        task["metadata"] = {"files": files}
    return task


def ids(entries):
    return [e["id"] for e in entries]


plan_batch = getattr(nb, "plan_batch", None)
plan_cases = [
    # (candidates, expected batch ids, expected skipped ids, what)
    ([t("a", "src/A.kt"), t("b", "src/B.kt")], ["a", "b"], [],
     "disjoint claims batch together"),
    ([t("z")], ["z"], [], "a task with NO files key at all is dispatched, alone"),
    ([t("z", "")], ["z"], [], "an empty files string is the same as no key"),
    ([t("z", "  ,  ")], ["z"], [], "whitespace-only claim is still claimless"),
    ([t("z"), t("a", "src/A.kt")], ["z"], ["a"],
     "a claimless task first defers every sibling behind it"),
    ([t("a", "src/A.kt"), t("z")], ["a"], ["z"],
     "a claimless task arriving after a batch is held, never co-scheduled"),
    ([t("z"), t("y")], ["z"], ["y"], "two claimless tasks never share a batch"),
    ([t("h", labels=["human"]), t("z")], ["z"], ["h"],
     "a human-gated skip does not count as a batch that blocks the alone-task"),
    # The observed defect this convention exists to prevent: a descriptive
    # string is NOT claimless, so it batches like a path and the alone-rule
    # never protects it. Pinned so the divergence stays visible.
    ([t("d", "none (tracker mutations only; no repository files)"),
      t("a", "src/A.kt")], ["d", "a"], [],
     "a descriptive string in files reads as a path and batches normally"),
]
for candidates, want_batch, want_skipped, what in plan_cases:
    if plan_batch is None:
        failed += 1
        print(f"FAIL: {what} — next-batch.py has no plan_batch()")
        continue
    got_b, got_s = plan_batch([(c, False) for c in candidates])
    if ids(got_b) != want_batch or sorted(ids(got_s)) != sorted(want_skipped):
        failed += 1
        print(f"FAIL: {what} — expected batch={want_batch!r} skipped={want_skipped!r}, "
              f"got batch={ids(got_b)!r} skipped={ids(got_s)!r}")

# A claimless entry must carry an EMPTY files list to the caller — not a
# guessed one — since 5b writes the real claim from the diff afterwards.
if plan_batch is not None:
    solo, _ = plan_batch([(t("z"), False)])
    if solo[0].get("files") != []:
        failed += 1
        print(f"FAIL: a claimless entry must report files=[] — got {solo[0].get('files')!r}")
    plan_entry_cases = 1
else:
    failed += 1
    plan_entry_cases = 1
    print("FAIL: claimless entry files — next-batch.py has no plan_batch()")


# --- cross_bead reaches the entry (computenet-eetn) -------------------------
# 5b reads authorized cross-bead writes off the batch entry instead of
# hand-grepping the description, so the key has to be present on EVERY entry:
# absent metadata must read as the empty string ("none authorized"), not as a
# missing key the orchestrator silently skips over.

def t_meta(id, **meta):
    task = {"id": id, "issue_type": "task", "status": "open", "labels": []}
    task["metadata"] = meta
    return task


entry = getattr(nb, "_entry", None)
cross_bead_cases = [
    (t_meta("a", files="src/A.kt"), "",
     "no cross_bead key => empty string, the 'none authorized' value"),
    (t_meta("b", files="src/B.kt", cross_bead=""),
     "", "an explicitly empty cross_bead stays empty"),
    (t_meta("c", files="src/C.kt",
            cross_bead="computenet-iyi.4: add a bd comment recording the OQ-1 decision"),
     "computenet-iyi.4: add a bd comment recording the OQ-1 decision",
     "a written cross_bead reaches the entry verbatim, for verbatim relay"),
    ({"id": "d", "issue_type": "task", "status": "open", "labels": []}, "",
     "a task with NO metadata at all still carries the key"),
]
for task, want, what in cross_bead_cases:
    if entry is None:
        failed += 1
        print(f"FAIL: {what} — next-batch.py has no _entry()")
        continue
    got = entry(task, False, []).get("cross_bead", "<<missing>>")
    if got != want:
        failed += 1
        print(f"FAIL: {what} — expected {want!r}, got {got!r}")


# --- classify(): the verdict for an EMPTY batch (computenet-eic) -------------
# The orchestrator routes on this string, so each of the four verdicts is
# pinned, not just the new one.

def child(status, assignee=None, labels=None, id="t"):
    return {"id": id, "issue_type": "task", "status": status,
            "assignee": assignee, "labels": labels or []}


PARK = child("blocked", assignee="human", labels=["human"])
CLOSED = child("closed", assignee="MacBoo")

verdict_cases = [
    # (children, expected verdict, what)
    ([], "no-tasks", "no children at all"),
    ([CLOSED, CLOSED], "all-closed", "every child closed"),
    ([CLOSED, child("open")], "blocked", "a plain open child"),
    ([CLOSED, child("blocked")], "blocked",
     "blocked with no human marker is a real dependency block"),
    ([CLOSED, child("in_progress", assignee="OtherMachine")], "blocked",
     "in flight on another machine is not a park"),
    ([CLOSED, child("open", labels=["human"])], "blocked",
     "the human label alone, without blocked, is not a park"),
    # The bead's case: tasks all closed and reviewed, the only open children
    # are follow-ups this feature's own implementation filed and parked.
    ([CLOSED, CLOSED, PARK, PARK, PARK], "parked-residue",
     "closed tasks plus parked human follow-ups"),
    ([PARK], "parked-residue", "a lone park"),
    ([CLOSED, child("blocked", labels=["human"])], "parked-residue",
     "park keyed on the label alone (assignee not set)"),
    ([CLOSED, child("blocked", assignee="human")], "parked-residue",
     "park keyed on the assignee alone (label not set)"),
    ([CLOSED, PARK, child("open")], "blocked",
     "one genuinely unmet child outvotes every park"),
]

classify = getattr(nb, "classify", None)
for children, expected, what in verdict_cases:
    if classify is None:
        failed += 1
        print(f"FAIL: {what} — next-batch.py has no classify()")
        continue
    got = classify(children)
    if got != expected:
        failed += 1
        print(f"FAIL: {what} — expected verdict={expected!r}, got {got!r}")


# --- parked_ids(): the ids behind a parked-residue verdict (computenet-k9d.4)
# 5e's ${parkedChildren} slot is filled from this. It must agree with
# classify() child-for-child, or the reviewer is handed a list that does not
# match the verdict that dispatched it.

parked_cases = [
    # (children, expected ids, what)
    ([], [], "no children at all"),
    ([CLOSED, CLOSED], [], "every child closed — nothing to name"),
    ([CLOSED, child("open", id="a")], [], "a plain open child is not a park"),
    ([CLOSED, child("blocked", id="a")], [],
     "blocked with no human marker is a real dependency block"),
    ([child("blocked", assignee="human", labels=["human"], id="b"),
      child("blocked", assignee="human", labels=["human"], id="a")],
     ["a", "b"], "sorted, so the handoff string is stable"),
    ([CLOSED, child("blocked", labels=["human"], id="a")], ["a"],
     "park keyed on the label alone (assignee not set)"),
    ([CLOSED, child("blocked", assignee="human", id="a")], ["a"],
     "park keyed on the assignee alone (label not set)"),
    # Reported even when outvoted: honest, and 5f simply does not read it.
    ([child("blocked", assignee="human", labels=["human"], id="p"),
      child("open", id="o")], ["p"],
     "parks are still named when one open child holds the verdict at blocked"),
    ([child("closed", assignee="human", labels=["human"], id="c")], [],
     "a closed child is never remaining work, whatever its markers"),
]

parked_ids = getattr(nb, "parked_ids", None)
for children, expected, what in parked_cases:
    if parked_ids is None:
        failed += 1
        print(f"FAIL: {what} — next-batch.py has no parked_ids()")
        continue
    got = parked_ids(children)
    if got != expected:
        failed += 1
        print(f"FAIL: {what} — expected parked={expected!r}, got {got!r}")

# The two must not disagree: whenever classify() says parked-residue, the ids
# it says that about are exactly the unfinished children, and parked_ids()
# must return all of them. This is the invariant the second implementation
# route (a hand-written bd query in SKILL.md) could not have held.
agreement_cases = [c for c in verdict_cases if c[1] == "parked-residue"]
for children, _, what in agreement_cases:
    unfinished = sorted(t["id"] for t in children if t.get("status") != "closed")
    got = parked_ids(children) if parked_ids else None
    if got != unfinished:
        failed += 1
        print(f"FAIL: parked-residue agreement ({what}) — "
              f"expected {unfinished!r}, got {got!r}")

# --- capacity_limit(): the second axis on the batch (computenet-k9d.2) ------
# Measured on a 16-core machine: 1 agent 20.5s, 2 agents 24.7/25.4s (1.22x),
# 3 agents 34.0/34.8/35.0s (1.70x), 6 agents past the knee. The rule is
# per-core because the two machines running this skill differ (10 and 16).

capacity_cases = [
    # (cores, expected cap, what)
    (16, 3, "this machine: 16 cores, largest arm measured under 2x inflation"),
    (10, 2, "the machine the disaster was recorded on — 2 survivable, 3 not"),
    (10, 2, "cores/3 would say 3 here, which is the load-112 configuration"),
    (5, 1, "a small box still gets one agent"),
    (1, 1, "never zero: an empty batch would change the verdict"),
    (2, 1, "floor holds below one lane's worth of cores"),
    (32, 6, "scales up with the machine rather than saturating at a constant"),
]

capacity_limit = getattr(nb, "capacity_limit", None)
for cores, expected, what in capacity_cases:
    if capacity_limit is None:
        failed += 1
        print(f"FAIL: {what} — next-batch.py has no capacity_limit()")
        continue
    got = capacity_limit(cores)
    if got != expected:
        failed += 1
        print(f"FAIL: {what} — capacity_limit({cores}) expected {expected}, got {got}")


# --- cap_batch(): trimming falls on the newly-ready tail, and is reported ---

def entry(id, resumed=False):
    return {"id": id, "resumed": resumed}


R1, R2 = entry("r1", resumed=True), entry("r2", resumed=True)
N1, N2, N3 = entry("n1"), entry("n2"), entry("n3")

cap_cases = [
    # (batch, skipped, cap, expected batch ids, expected skipped ids, what)
    ([N1, N2], [], 3, ["n1", "n2"], [], "batch under the cap is untouched"),
    ([N1, N2, N3], [], 3, ["n1", "n2", "n3"], [], "batch exactly at the cap"),
    ([N1, N2, N3], [], 2, ["n1", "n2"], ["n3"], "the tail is held back"),
    ([R1, R2, N1], [], 2, ["r1", "r2"], ["n1"],
     "resumable tasks survive the trim — they hold worktrees with commits"),
    ([N1, N2], [{"id": "x", "reason": "human-gated"}], 1, ["n1"], ["x", "n2"],
     "capacity skips are appended to the existing skip list, not replacing it"),
    ([N1, N2, N3], [], 1, ["n1"], ["n2", "n3"],
     "a cap of 1 still yields a non-empty batch, so the verdict stays 'ok'"),
]

cap_batch = getattr(nb, "cap_batch", None)
for batch_in, skipped_in, cap, want_batch, want_skipped, what in cap_cases:
    if cap_batch is None:
        failed += 1
        print(f"FAIL: {what} — next-batch.py has no cap_batch()")
        continue
    got_batch, got_skipped = cap_batch(list(batch_in), list(skipped_in), cap)
    got_b = [e["id"] for e in got_batch]
    got_s = [e["id"] for e in got_skipped]
    if got_b != want_batch or got_s != want_skipped:
        failed += 1
        print(f"FAIL: {what} — expected batch={want_batch!r} skipped={want_skipped!r}, "
              f"got batch={got_b!r} skipped={got_s!r}")

# A held-back item must say WHY in terms the caller can act on: "hold it for
# the next round" is a different instruction from "its claim overlaps" or "a
# human reserved it", and an unreasoned skip reads as the latter two.
if cap_batch is not None:
    _, reasons = cap_batch([N1, N2], [], 1)
    if len(reasons) != 1 or "capacity" not in reasons[0].get("reason", ""):
        failed += 1
        print(f"FAIL: a capacity skip must name capacity in its reason — got {reasons!r}")
    capacity_reason_cases = 1
else:
    failed += 1
    capacity_reason_cases = 1
    print("FAIL: capacity skip reason — next-batch.py has no cap_batch()")

# metadata.files arrives in TWO shapes from two sanctioned writers: the
# comma-separated string a breakdown writes, and the JSON list
# create-ticket.sh produces for a reviewer-filed residual. Only the string
# parsed until 2026-08-19, so a batch holding a residual aborted at 5b with
# `AttributeError: 'list' object has no attribute 'split'` — a crash in the
# one step that decides what may run in parallel (computenet-tbzg).
claim_shape_cases = [
    ({"files": "a/b.kt,c/d.kt"}, {"a/b.kt", "c/d.kt"}, "comma-separated string"),
    ({"files": ["a/b.kt", "c/d.kt"]}, {"a/b.kt", "c/d.kt"}, "JSON list"),
    ({"files": ["./a/b.kt/", " c/d.kt "]}, {"a/b.kt", "c/d.kt"},
     "list entries are normalised like string entries"),
    ({"files": ["a/b.kt,c/d.kt"]}, {"a/b.kt", "c/d.kt"},
     "a single list entry that is itself comma-joined"),
    ({"files": ""}, set(), "empty string is 'unknown', not a failure"),
    ({}, set(), "absent key is 'unknown', not a failure"),
]
for meta, expected, what in claim_shape_cases:
    try:
        got = nb.claim_of({"id": "computenet-shape", "metadata": meta})
    except Exception as exc:                       # noqa: BLE001 — that IS the bug
        failed += 1
        print(f"FAIL: {what} — raised {type(exc).__name__}: {exc}")
        continue
    if got != expected:
        failed += 1
        print(f"FAIL: {what} — expected {expected!r}, got {got!r}")

# A claim that is present but unreadable must NAME the bead. The bare
# AttributeError named split() on a list and not which of several candidates
# was malformed, so the orchestrator had to bisect the batch.
claim_error_cases = [
    ({"files": {"a": 1}}, "a mapping"),
    ({"files": [{"a": 1}]}, "a list holding a non-string"),
]
for meta, what in claim_error_cases:
    try:
        nb.claim_of({"id": "computenet-bad", "metadata": meta})
    except nb.ClaimError as exc:
        if "computenet-bad" not in str(exc):
            failed += 1
            print(f"FAIL: {what} — ClaimError must name the bead, got {exc!r}")
    except Exception as exc:                       # noqa: BLE001
        failed += 1
        print(f"FAIL: {what} — expected ClaimError, got {type(exc).__name__}: {exc}")
    else:
        failed += 1
        print(f"FAIL: {what} — expected ClaimError, nothing raised")

# capacity_limit is PER SESSION but the machine is shared: four live sessions
# on a 10-core box each computed 2 independently, each correct by its own
# accounting, for 4x the measured safe parallelism (computenet-arow).
sibling_cases = [
    (10, 0, 2, "alone on 10 cores keeps the measured cap"),
    (16, 0, 3, "alone on 16 cores keeps the measured cap"),
    (10, 1, 1, "one sibling halves a cap of 2"),
    (16, 1, 1, "one sibling on 16 cores"),
    (16, 2, 1, "two siblings never drop below the floor"),
    (10, 9, 1, "many siblings still leave one agent, not zero"),
]
for cores, sibs, expected, what in sibling_cases:
    got = nb.capacity_limit(cores, sibs)
    if got != expected:
        failed += 1
        print(f"FAIL: {what} — capacity_limit({cores}, {sibs}) = {got}, expected {expected}")

# The sum across sessions must not exceed the alone-cap: that is the property
# the whole change exists for.
for cores in (10, 16):
    alone = nb.capacity_limit(cores, 0)
    for n in range(1, 5):
        total_agents = n * nb.capacity_limit(cores, n - 1)
        if total_agents > alone and nb.capacity_limit(cores, n - 1) > 1:
            failed += 1
            print(f"FAIL: {n} sessions on {cores} cores dispatch {total_agents} > {alone}")
sibling_sum_cases = 2

total = (len(cases) + len(sibling_cases) + sibling_sum_cases + len(plan_cases) + plan_entry_cases + len(cross_bead_cases)
         + len(verdict_cases) + len(parked_cases) + len(agreement_cases)
         + len(capacity_cases) + len(cap_cases) + capacity_reason_cases
         + len(claim_shape_cases) + len(claim_error_cases))
print(f"{total - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
