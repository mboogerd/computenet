#!/usr/bin/env python3
"""Regression test for next-batch.py's claim-overlap rule (computenet-9eb).

Run: python3 .claude/skills/work/scripts/next-batch.test.py   (expect "6 passed")
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
    ({nb._norm("./" + DIR + "/")}, {DIR}, True, "normalised ./dir/ form"),
]

failed = 0
for files, taken, expected, what in cases:
    got = bool(nb.overlaps(files, taken))
    if got != expected:
        failed += 1
        print(f"FAIL: {what} — expected overlap={expected}, got {got}")

print(f"{len(cases) - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
