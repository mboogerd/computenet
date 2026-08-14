#!/usr/bin/env python3
"""Regression tests for next-batch.py: the claim-overlap rule (computenet-9eb),
the empty-batch verdict (computenet-eic), and the parked-children ids that
verdict hands to the 5e review (computenet-k9d.4).

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

total = (len(cases) + len(verdict_cases) + len(parked_cases) + len(agreement_cases)
         + len(capacity_cases) + len(cap_cases) + capacity_reason_cases)
print(f"{total - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
