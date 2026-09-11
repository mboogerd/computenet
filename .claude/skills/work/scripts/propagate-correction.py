#!/usr/bin/env python3
"""Which OTHER beads under this epic repeat a claim that has just been proven wrong?

WHY THIS EXISTS. A correction an implementer or reviewer discovers about a
bead's PREDICTION is written onto that agent's own bead and nowhere else.
Unstarted siblings keep the wrong prediction and each one re-derives the
correction by running into it. Measured on computenet-f7h, 2026-09-10: the
same wrong fact ("the winner is delivery-order dependent") was worked out
THREE times by three agents in one session, the third ~40 minutes after the
first two had measured and pinned the truth. Five further predictions from
the same epic were each corrected once, by an agent walking into them
(computenet-9vvu6).

The orchestrator cannot fix that by re-reading every bead — it is the one
thing a session reliably will not do. So this is one command: give it the
distinctive words of the WRONG claim and it names every non-closed bead
beneath the epic whose own text still repeats them, including the epic and
its features (the feature text is a propagation target too — a feature-level
review scores against it).

Usage:
    propagate-correction.py <epic-id> [--exclude <bead-id>]... <needle> [<needle>...]

Needles are case-insensitive substrings; a bead matches on ANY of them.
Pass two or three distinctive words of the wrong claim, not a sentence — the
sibling that repeats a prediction rarely repeats its wording.

Output, one line per match:
  <id>  <status>  <assignee>  <field>  …context…
EMPTY OUTPUT WITH EXIT 0 IS A REAL ANSWER: nothing else repeats the claim.
Exit 2 = usage. Exit 3 = the query itself failed and NOTHING was checked —
never read that as "nothing to propagate".

Membership follows the epic-of.sh rule (explicit .parent when set, else the
dotted-id prefix), for the reason ready-in-epic.sh spells out: `bd list
--parent` is NOT transitive, so a direct-children scan misses exactly the
task-level grandchildren that carry the predictions.
"""
import json
import os
import subprocess
import sys

FIELDS = ("title", "description", "acceptance_criteria", "design", "notes")
CONTEXT = 60


def unwrap(raw):
    i = min((x for x in (raw.find("["), raw.find("{")) if x >= 0), default=-1)
    if i < 0:
        raise ValueError("no JSON payload")
    d = json.loads(raw[i:])
    return d if isinstance(d, list) else d.get("issues", [])


def bd(*args):
    p = subprocess.run(("bd",) + args, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError("bd %s failed: %s" % (" ".join(args), p.stderr.strip()))
    return unwrap(p.stdout)


def descendants(rows, epic):
    """Ids with `epic` anywhere on their ancestor chain, plus `epic` itself.

    Deliberately TRANSITIVE, unlike epic-of.sh, which stops at the first
    containing epic: a task under a sub-epic beneath this one still repeats
    this epic's predictions and still has to be amended.
    """
    parent = {r["id"]: (r.get("parent") or None) for r in rows}
    out = set()
    for bid in parent:
        cur, seen = bid, set()
        while cur is not None and cur not in seen:
            seen.add(cur)
            if cur == epic:
                out.add(bid)
                break
            nxt = parent.get(cur)
            if not nxt and "." in cur:
                nxt = cur.rsplit(".", 1)[0]
            cur = nxt or None
    return out


def main(argv):
    epic, excludes, needles = None, set(), []
    it = iter(argv)
    for a in it:
        if a == "--exclude":
            excludes.add(next(it, ""))
        elif epic is None:
            epic = a
        else:
            needles.append(a.lower())
    if not epic or not needles:
        sys.stderr.write(__doc__.split("Usage:")[1].split("\n\n")[0].strip() + "\n")
        return 2

    try:
        rows = bd("list", "--all", "--limit", "0", "--json")
    except Exception as e:
        sys.stderr.write("propagate-correction: %s; NOTHING was checked\n" % e)
        return 3

    ids = sorted(
        bid
        for bid in descendants(rows, epic)
        if bid not in excludes
        and next((r for r in rows if r["id"] == bid), {}).get("status") != "closed"
    )
    if not ids:
        return 0

    try:
        full = bd("show", *(ids + ["--json"]))
    except Exception as e:
        sys.stderr.write("propagate-correction: %s; NOTHING was checked\n" % e)
        return 3

    for r in full:
        for f in FIELDS:
            body = r.get(f) or ""
            if not isinstance(body, str):
                continue
            low = body.lower()
            hit = next(((n, low.find(n)) for n in needles if low.find(n) >= 0), None)
            if not hit:
                continue
            at = hit[1]
            ctx = " ".join(body[max(0, at - CONTEXT):at + CONTEXT].split())
            print("%s\t%s\t%s\t%s\t…%s…" % (
                r["id"], r.get("status", "?"), r.get("assignee") or "-", f, ctx))
            break
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
