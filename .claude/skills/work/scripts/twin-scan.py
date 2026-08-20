#!/usr/bin/env python3
"""Find children of one parent that are the SAME BEAD FILED TWICE.

WHY THIS EXISTS. A double breakdown -- one epic decomposed twice, by a
re-dispatch or by two concurrent sessions reading the same epic state -- leaves
near-identical children in pairs. `computenet-4ru` carried SIX such pairs
(.2/.3, .4/.5, .6/.7, .8/.9, .10/.11, .12/.13), and inheriting the aftermath
cost a session real time reading two ~1500-word bodies side by side before any
work could start (computenet-f434, computenet-t9d5).

SKILL.md step 4 already carries the rule -- "scan the `--all` listing you
already have for same-titled or same-scoped children created within minutes of
each other" -- and it is prose asking an agent to eyeball a listing. This is
the same rule as a command, because every recorded recurrence in the SDLC log
is a prose fix that someone did not read at the moment it mattered.

WHAT IT KEYS ON. Two children of one parent, created within a short window,
whose titles are similar. Time is the sharp signal and title is the loose one:
all six 4ru twins were created 2-14 SECONDS apart, with title ratios of only
0.59-0.70 -- the two breakdowns described the same task in different words, so
a strict title match misses every one of them.

MEASURED against the six known pairs (2026-08-20, whole export): at the
defaults below the scan flags 10 pairs and finds ALL SIX. The four others are
deliberately-parallel beads that a reader dismisses at a glance -- "PR115 cycle
probe A"/"probe B", "ZZ A"/"ZZ B", and a SIM-drive/REAL-drive task pair. For
comparison on the same data, `bv --robot-suggest` reported two potential
duplicates, neither of them one of the six, one matching on the bare numeric
tokens `2026, 218, 268, 272`.

Loosening the title threshold to 0.50 flags 18 for the same six; tightening to
0.62 drops one real pair. 0.55 is the knee and is where the default sits.

Usage: twin-scan.py <parent-id> [--window-min N] [--threshold R] [--jsonl P]
       twin-scan.py --all        [...]        (every parent in the export)
Exit: 0 = no twins flagged; 1 = twins flagged (READ THEM — a flag is a
      question, not a verdict); 2 = bad usage; 3 = the export is missing or
      unreadable — NOTHING was checked, which is not the same as a clean scan.
"""
import argparse
import difflib
import itertools
import json
import os
import subprocess
import sys
from datetime import datetime

DEFAULT_WINDOW_MIN = 15
DEFAULT_THRESHOLD = 0.55


def find_export(rel):
    """`rel`, or the same path under the main checkout (worktrees have no .beads)."""
    if os.path.exists(rel):
        return rel
    try:
        common = subprocess.run(["git", "rev-parse", "--git-common-dir"],
                                capture_output=True, text=True,
                                check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None
    cand = os.path.join(os.path.dirname(os.path.abspath(common)), rel)
    return cand if os.path.exists(cand) else None


def parent_of(r):
    """`.parent` is OMITTED when unset, so fall back to the edge (bd-traps.md)."""
    if r.get("parent"):
        return r["parent"]
    for d in (r.get("dependencies") or []):
        if d.get("type") == "parent-child":
            return d.get("depends_on_id")
    return None


def when(s):
    try:
        return datetime.strptime((s or "")[:19], "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return None


def main(argv):
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("parent", nargs="?")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--window-min", type=float, default=DEFAULT_WINDOW_MIN)
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    ap.add_argument("--jsonl", default=".beads/issues.jsonl")
    try:
        a = ap.parse_args(argv[1:])
    except SystemExit:
        return 2
    if not a.parent and not a.all:
        print("usage: twin-scan.py <parent-id> | --all", file=sys.stderr)
        return 2

    path = find_export(a.jsonl)
    if not path:
        print(f"twin-scan: {a.jsonl} not found here or in the main checkout; "
              "NOTHING was checked", file=sys.stderr)
        return 3
    recs = []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if line:
                try:
                    recs.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    if not recs:
        print(f"twin-scan: {path} held no parseable records; NOTHING was "
              "checked", file=sys.stderr)
        return 3

    groups = {}
    for r in recs:
        p = parent_of(r)
        if p and (a.all or p == a.parent):
            groups.setdefault(p, []).append(r)
    if not a.all and a.parent not in groups:
        print(f"twin-scan: {a.parent} has no children in the export "
              "(no twins possible, but also nothing checked)")
        return 0

    flagged = []
    for p, kids in groups.items():
        for x, y in itertools.combinations(kids, 2):
            tx, ty = when(x.get("created_at")), when(y.get("created_at"))
            if not tx or not ty:
                continue
            gap = abs((tx - ty).total_seconds())
            if gap > a.window_min * 60:
                continue
            ratio = difflib.SequenceMatcher(
                None, (x.get("title") or "").lower(),
                (y.get("title") or "").lower()).ratio()
            if ratio >= a.threshold:
                flagged.append((ratio, gap, p, x, y))

    for ratio, gap, p, x, y in sorted(flagged, reverse=True,
                                      key=lambda t: (t[0], -t[1])):
        print(f"TWIN? {ratio:.2f} similar, {gap:.0f}s apart, under {p}")
        for b in (x, y):
            print(f"    {b['id']:<22} {b.get('status','?'):<12} "
                  f"{(b.get('title') or '')[:70]}")
    if flagged:
        print(f"\n{len(flagged)} candidate pair(s). A flag is a QUESTION: "
              "deliberately-parallel beads (probe A / probe B, SIM / REAL) look "
              "exactly like this.\nFor a real twin, SKILL.md step 4 says what "
              "to do — one closed with comment_count 0 is a clean supersede; "
              "both open is not yours to guess.")
    else:
        print(f"no twin candidates (window {a.window_min:g}m, "
              f"threshold {a.threshold:g})")
    return 1 if flagged else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
