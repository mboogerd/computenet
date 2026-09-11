#!/usr/bin/env python3
"""Regression tests for propagate-correction.py (computenet-9vvu6).

The two things that make it worth running rather than eyeballing: membership
must reach GRANDCHILDREN (the level that actually carries a prediction, and
the level `bd list --parent` silently drops), and it must not reach beads of a
NEIGHBOURING epic whose dotted ids look similar.

Run: python3 .claude/skills/work/scripts/propagate-correction.test.py
"""
import importlib.util
import pathlib
import sys

spec = importlib.util.spec_from_file_location(
    "propcorr", pathlib.Path(__file__).with_name("propagate-correction.py"))
pc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pc)

ROWS = [
    {"id": "cn-e", "issue_type": "epic"},
    {"id": "cn-e.1", "issue_type": "feature"},
    {"id": "cn-e.1.2", "issue_type": "task"},          # grandchild by prefix
    {"id": "cn-flat", "issue_type": "task", "parent": "cn-e.1"},  # no dot
    {"id": "cn-sub", "issue_type": "epic", "parent": "cn-e"},     # sub-epic
    {"id": "cn-sub.1", "issue_type": "task"},          # under the sub-epic
    {"id": "cn-other", "issue_type": "epic"},
    {"id": "cn-other.1", "issue_type": "task"},        # NOT ours
    {"id": "cn-orphan", "issue_type": "task"},         # unparented
]

got = pc.descendants(ROWS, "cn-e")
cases = [
    ("cn-e", True, "the epic itself is a propagation target (feature text)"),
    ("cn-e.1", True, "direct child"),
    ("cn-e.1.2", True, "GRANDCHILD by dotted prefix"),
    ("cn-flat", True, "explicit .parent overrides the dotless id"),
    ("cn-sub", True, "a sub-epic beneath ours"),
    ("cn-sub.1", True, "a task under a sub-epic beneath ours"),
    ("cn-other", False, "a neighbouring epic"),
    ("cn-other.1", False, "a task of a neighbouring epic"),
    ("cn-orphan", False, "an unparented bead"),
]

failed = 0
for bid, expected, what in cases:
    if (bid in got) != expected:
        failed += 1
        print(f"FAIL: {what} — expected {bid} in={expected}, got {bid in got}")

# a needle straddling a hard-wrapped line break must still match: bead bodies
# are wrapped, and a miss here is an empty result read as "nothing to propagate"
import io, contextlib
_rows = [{"id": "cn-e", "issue_type": "epic"}]
_buf = io.StringIO()
pc.bd = lambda *a: (_rows if a[0] == "list" else
                    [{"id": "cn-e", "status": "open",
                      "description": "the winner is delivery-order\ndependent is false"}])
with contextlib.redirect_stdout(_buf):
    pc.main(["cn-e", "delivery-order dependent"])
if "delivery-order dependent" not in " ".join(_buf.getvalue().split()):
    failed += 1
    print("FAIL: a needle spanning a line break must match — got %r" % _buf.getvalue())

# an epic id that is in no listing is UNRESOLVED (exit 3), never "nothing to
# propagate" (exit 0) — the failure epic-of.sh guards for the same reason
_buf2 = io.StringIO()
with contextlib.redirect_stdout(_buf2):
    rc = pc.main(["cn-NOSUCH", "anything"])
if rc != 3:
    failed += 1
    print("FAIL: an unknown epic id must exit 3, got %s" % rc)

# a parent chain that loops must terminate rather than hang
if pc.descendants([{"id": "a", "parent": "b"}, {"id": "b", "parent": "a"}], "cn-e"):
    failed += 1
    print("FAIL: a cycle must resolve to no membership, not a match")

print(f"{len(cases) + 3 - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
