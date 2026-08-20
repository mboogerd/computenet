#!/usr/bin/env python3
"""Which of this lane's own fixes did NOT stop the friction recurring.

WHY THIS EXISTS. The friction lane had no feedback loop: it filed, fixed and
closed, and nothing ever asked whether an edit changed what agents do. Measured
2026-08-20 across 265 closed children of computenet-wpvy, split at PR #301 --
the triage gate that computenet-olrv introduced *specifically* to make the lane
less eager:

    before the gate:  143 fixed /  9 superseded / 0 rejected   -> 79% fix rate
    after  the gate:   79 fixed /  2 superseded / 2 rejected    -> 94% fix rate

The gate meant to lower the fix rate coincided with it rising, and both
rejections in the epic's whole history landed in its last two days. That is the
lane applying its own standard remedy -- more instruction text -- to a problem
its own instruction text had failed to fix, with nothing measuring either pass.
This script is the missing measurement, and it needs no new process: every
signal it reads is already in the export.

WHAT A RECURRENCE IS. A bead FILED AFTER a fix closed, whose text names that
fix's id. The strong form says so outright ("RECURRENCE of computenet-84z6",
"hit again", "Third recurrence"); the weak form merely cites it, which is
usually an author distinguishing their report FROM the earlier one -- valuable
context, not evidence of failure. Both are printed, separately, because the
weak list is where a missed recurrence hides.

Deliberately retrospective and free. It spawns nothing, runs in about a second,
and answers the only question that matters about a landed fix.

MEASURED BEYOND THIS LANE (2026-08-20). Pointed at every parent in the export:
775 landed items, and exactly ONE recurrence outside computenet-wpvy. The
failure this detects -- a fix that did not take -- is a documentation-lane
pathology, not a code-lane one, because code fixes ship with a test that fails
without them and prose ships with nothing. That is the mechanical-vs-prose
finding arriving from the other direction, and it is why `--epic` defaults
here rather than scanning everything.

Usage: recurrence-audit.py [--epic computenet-wpvy] [--jsonl .beads/issues.jsonl]
Prints FAILED-FIX lines (strong), CITED lines (weak), then a summary that
breaks the rate down by metadata.fix_kind -- `mechanical` fixes (a script, a
changed default, normalised data: they work whether or not anyone reads them)
against `prose` fixes (an instruction someone must remember).
Exit: 0 = audit ran (read the numbers); 2 = bad usage; 3 = the export is
      missing or unreadable -- NOTHING was checked, do not read silence as a
      clean bill of health.
"""
import argparse
import json
import os
import re
import subprocess
import sys

# "RECURRENCE of computenet-84z6", "recurred", "hit again", "Nth recurrence".
STRONG = re.compile(r"recurr|recurs\b|hit again|same failure again|again as of",
                    re.IGNORECASE)


def main_checkout_export(rel):
    """`rel` resolved against the main checkout, or None."""
    try:
        common = subprocess.run(["git", "rev-parse", "--git-common-dir"],
                                capture_output=True, text=True,
                                check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None
    cand = os.path.join(os.path.dirname(os.path.abspath(common)), rel)
    return cand if os.path.exists(cand) else None


def load(path):
    out = []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return out


def landed(r):
    """Closed as DONE — not superseded, rejected or deduplicated.

    NOT `"fixed in" in close_reason`, which this used until 2026-08-20 and
    which is a friction-lane CONVENTION: work epics close their children with
    bd's default "Closed" or a free-text verdict, so the audit silently
    reported `0 landed fixes` for every product epic — an empty answer reading
    as a clean bill of health, which is the exact shape this lane exists to
    catch.
    """
    cr = (r.get("close_reason") or "").strip().lower()
    return (r.get("status") == "closed"
            and not cr.startswith(("superseded", "rejected", "duplicate")))


def children_of(records, epic):
    """Direct children by parent-child edge, with `parent` as the fallback.

    Not `--parent` transitivity: this epic is flat today and a feature layer
    would need epic-of.sh's walk, which is a shell script and not worth
    importing for a listing that has never been more than one level deep.
    """
    kids = []
    for r in records:
        deps = r.get("dependencies") or []
        if r.get("parent") == epic or any(
                d.get("depends_on_id") == epic and d.get("type") == "parent-child"
                for d in deps):
            kids.append(r)
    return kids


def text_of(r):
    return " ".join(str(r.get(k) or "") for k in
                    ("title", "description", "acceptance_criteria"))


def main(argv):
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--epic", default="computenet-wpvy")
    ap.add_argument("--jsonl", default=".beads/issues.jsonl")
    try:
        a = ap.parse_args(argv[1:])
    except SystemExit:
        return 2

    jsonl = a.jsonl
    if not os.path.exists(jsonl):
        # Worktrees have no .beads/ — the export lives in the main checkout.
        # git-common-dir points at the shared .git from any worktree, so its
        # parent is the main checkout. This lane runs in a worktree by design
        # (step 4), so without this the audit is unrunnable exactly where it
        # is needed.
        jsonl = main_checkout_export(a.jsonl)
    if not jsonl:
        print(f"recurrence-audit: {a.jsonl} not found here and no main "
              "checkout export located; NOTHING was checked", file=sys.stderr)
        return 3
    records = load(jsonl)
    if not records:
        print(f"recurrence-audit: {jsonl} held no parseable records; "
              "NOTHING was checked", file=sys.stderr)
        return 3

    kids = children_of(records, a.epic)
    fixes = [r for r in kids if landed(r)]

    # id -> the fix record, for the citation scan below.
    by_id = {r["id"]: r for r in fixes}
    strong, weak = [], []
    for later in kids:
        created = later.get("created_at") or ""
        body = text_of(later)
        for fid, fix in by_id.items():
            if fid == later["id"] or fid not in body:
                continue
            # Filed AFTER the fix closed — otherwise it is a sibling report,
            # not a recurrence of something already believed fixed.
            if not created or created <= (fix.get("closed_at") or ""):
                continue
            # Look at the sentence the id sits in, not the whole bead: a long
            # report can say "recurrence" about some other item entirely.
            near = " ".join(s for s in re.split(r"(?<=[.\n])", body) if fid in s)
            (strong if STRONG.search(near) else weak).append((fid, later["id"]))

    for fid, lid in sorted(strong):
        kind = (by_id[fid].get("metadata") or {}).get("fix_kind", "?")
        print(f"FAILED-FIX  {fid} [{kind}]  ->  {lid}")
    for fid, lid in sorted(weak):
        print(f"CITED       {fid}  ->  {lid}")

    failed = {f for f, _ in strong}
    print()
    print(f"{len(fixes)} landed fixes under {a.epic}; "
          f"{len(failed)} recurred ({len(strong)} reports), "
          f"{len(weak)} weaker citations to eyeball")

    # The comparison the lane exists to act on.
    for kind in ("mechanical", "prose", "?"):
        pool = [f for f in fixes
                if ((f.get("metadata") or {}).get("fix_kind") or "?") == kind]
        if not pool:
            continue
        bad = len([f for f in pool if f["id"] in failed])
        print(f"  {kind:<11} {len(pool):>4} fixes, {bad} recurred "
              f"({100.0 * bad / len(pool):.1f}%)")
    if any(((f.get("metadata") or {}).get("fix_kind") or "?") == "?"
           for f in fixes):
        print("  ('?' = closed before fix_kind was recorded; the split is only "
              "meaningful once those age out)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
