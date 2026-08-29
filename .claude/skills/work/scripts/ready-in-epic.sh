#!/usr/bin/env bash
# Ready work anywhere beneath an epic, at any depth (computenet-28vn).
#
# WHY THIS EXISTS. `bd ready --parent=<epic>` reaches DIRECT CHILDREN ONLY.
# SKILL.md's `bd traps` called it "descendant-scoped"; that is measurably
# wrong. Measured 2026-08-17 against live data:
#
#   bd ready --parent=computenet-dqy.37  ->  finds computenet-dqy.37.2   (child)
#   bd ready --parent=computenet-dqy     ->  does NOT find it            (grandchild)
#   epic-of.sh computenet-dqy.37.2       ->  computenet-dqy
#
# The consequence is silent and expensive: an epic with ready work three
# levels down reports EMPTY, so step 3 can `bd defer` a live epic — hiding it
# on both machines — and 5f can declare a productive epic dry. One session
# came one step from doing exactly that and caught it only by luck
# (computenet-28vn).
#
# HOW. Take the repo-wide `bd ready`, which has no depth to lose, and keep the
# rows whose effective epic is this one. Membership follows the epic-of.sh rule:
# an explicit `.parent` when set, the dotted-id prefix otherwise. Both matter —
# a bare prefix match is not a substitute, because computenet-f8tf is a child
# of computenet-wpvy with no dot in its id.
#
# The walk is done HERE rather than by shelling out to epic-of.sh per row, and
# that is a deliberate duplication of ~10 lines of its logic. epic-of.sh runs
# one `bd show` per hop, and `bd` calls are ~1.4s: measured, the shell-out
# version of this script took 86 SECONDS on 60 ready rows. This one takes a
# single `bd list --all` and resolves every chain in-process. A script that
# slow gets skipped, and the whole point is that it is run rather than
# `bd ready --parent`. epic-of.sh remains the authority for a SINGLE id; if
# its rule changes, this must change with it.
#
# Usage: ready-in-epic.sh <epic-id> [--ids-only]
# Prints one row per ready item: "<id>\t<type>\t<priority>\t<title>", or bare
# ids under --ids-only. The TYPE column is load-bearing — step 5 selects the
# first `feature` row and falls through to the no-feature-layer shape when
# there is none, which it cannot do from an untyped listing.
# EMPTY OUTPUT WITH EXIT 0 IS A REAL ANSWER: nothing ready here.
# Exit 3 = the query itself failed and NOTHING was checked — never read that as
# an empty epic, because deferring on it hides the epic from both machines.
set -uo pipefail

EPIC=${1:-}
IDS_ONLY=0
[ "${2:-}" = "--ids-only" ] && IDS_ONLY=1
[ -n "$EPIC" ] || { echo "usage: ready-in-epic.sh <epic-id> [--ids-only]" >&2; exit 2; }

command -v jq >/dev/null || { echo "ready-in-epic: jq unusable; NOTHING was checked" >&2; exit 3; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EPIC_OF="$HERE/epic-of.sh"
[ -x "$EPIC_OF" ] || { echo "ready-in-epic: $EPIC_OF missing; NOTHING was checked" >&2; exit 3; }

raw=$(bd ready --json --limit 0 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p') \
  || { echo "ready-in-epic: bd ready failed; NOTHING was checked" >&2; exit 3; }
[ -n "$raw" ] || { echo "ready-in-epic: bd ready returned nothing parseable; NOTHING was checked" >&2; exit 3; }

# Every bead's id and explicit parent, in ONE call — the parent map the walk
# needs. `--all` so a closed intermediate ancestor is still resolvable.
allj=$(bd list --all --limit 0 --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p') \
  || { echo "ready-in-epic: bd list failed; NOTHING was checked" >&2; exit 3; }
[ -n "$allj" ] || { echo "ready-in-epic: bd list returned nothing parseable; NOTHING was checked" >&2; exit 3; }

# `bd list/ready --json` is a bare array, except under --skip-labels where it is
# {"issues":[...]} — the unwrap below accepts either (computenet-kr18).
# Both payloads go through files: the ready listing alone overruns the
# environment's argument limit ("Argument list too long", measured).
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ready-in-epic.XXXXXX") || exit 3
trap 'rm -rf "$tmp"' EXIT
printf '%s' "$allj" > "$tmp/all.json"
printf '%s' "$raw"  > "$tmp/ready.json"

EPIC="$EPIC" IDS_ONLY="$IDS_ONLY" ALLF="$tmp/all.json" READYF="$tmp/ready.json" python3 -c '
import json, os, sys

def unwrap(raw):
    i = min(x for x in (raw.find("["), raw.find("{")) if x >= 0)
    d = json.loads(raw[i:])
    return d if isinstance(d, list) else d.get("issues", [])

epic = os.environ["EPIC"]
ids_only = os.environ["IDS_ONLY"] == "1"
allrows = unwrap(open(os.environ["ALLF"]).read())
ready = unwrap(open(os.environ["READYF"]).read())

parent = {}
kind = {}
for r in allrows:
    parent[r["id"]] = r.get("parent") or None
    kind[r["id"]] = r.get("issue_type") or r.get("type") or ""

def epic_of(bid):
    """The epic-of.sh rule: explicit .parent when set, else the dotted-id
    prefix, until an epic is reached.

    Returns "(unparented)" for a chain that legitimately reaches no epic — a
    positive answer, same as epic-of.sh — and None when the walk is UNRESOLVED,
    which is NOT the same as not-a-member and is reported to stderr. Unresolved
    means an id absent from the listing AT ANY HOP (the epic-of.sh
    "(no such id: ...)", exit 1 — a vanished ancestor must never fall through
    to "(unparented)", i.e. to "silently not your epic"), or a cycle
    ("(cycle? ...)", exit 1).

    ONE DELIBERATE DIVERGENCE from epic-of.sh, at the start id only: epic-of.sh
    answers X for an epic X, because it asks "which epic owns X". This asks
    "which epic is X ready work BENEATH", so an epic row resolves to its
    CONTAINING epic — a ready sub-epic (live: computenet-t6b.3 under
    computenet-t6b) is real workable surface of its parent and must not vanish
    from it. The epic being queried is dropped from its own listing by the
    caller loop below, not here."""
    seen = set()
    cur = bid
    while cur not in seen:
        seen.add(cur)
        if cur not in parent:
            return None                  # unknown id at any hop: unresolved
        if cur != bid and kind.get(cur) == "epic":
            return cur
        nxt = parent.get(cur)
        if not nxt:
            # epic-of.sh uses ${id%.*} — strip the last dotted segment whatever
            # it looks like; do not narrow that to digits.
            nxt = cur.rsplit(".", 1)[0] if "." in cur else None
        if nxt is None:
            return cur if kind.get(cur) == "epic" else "(unparented)"
        cur = nxt
    return None                          # cycle

for r in ready:
    bid = r["id"]
    if bid == epic:
        continue        # the epic is not ready work *beneath* itself
    owner = epic_of(bid)
    if owner is None:
        print("ready-in-epic: could not resolve the epic of %s — check it by hand" % bid, file=sys.stderr)
        continue
    if owner != epic:
        continue
    if ids_only:
        print(bid)
    else:
        print("%s\t%s\t%s\t%s" % (bid, kind.get(bid, ""), r.get("priority", ""), r.get("title", "")))
' || { echo "ready-in-epic: the epic walk failed; NOTHING was checked" >&2; exit 3; }
exit 0
