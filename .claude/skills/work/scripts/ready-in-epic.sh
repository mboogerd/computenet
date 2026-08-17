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
# rows whose effective epic is this one. Membership follows epic-of.sh's rule:
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
# Prints one row per ready item: "<id>\t<priority>\t<title>", or bare ids under
# --ids-only. EMPTY OUTPUT WITH EXIT 0 IS A REAL ANSWER: nothing ready here.
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

raw=$(bd ready --json --limit 0 2>/dev/null | sed -n '/^[[{]/,$p') \
  || { echo "ready-in-epic: bd ready failed; NOTHING was checked" >&2; exit 3; }
[ -n "$raw" ] || { echo "ready-in-epic: bd ready returned nothing parseable; NOTHING was checked" >&2; exit 3; }

# Every bead's id and explicit parent, in ONE call — the parent map the walk
# needs. `--all` so a closed intermediate ancestor is still resolvable.
allj=$(bd list --all --limit 0 --json 2>/dev/null | sed -n '/^[[{]/,$p') \
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
import json, os, sys, re

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
    """epic-of.sh: explicit .parent when set, else the dotted-id prefix; stop at
    an epic. Returns "(unparented)" for a chain that legitimately reaches no
    epic — a positive answer, same as epic-of.sh — and None only when the walk
    is UNRESOLVED (unknown id, or a cycle), which is not the same as
    not-a-member and is reported to stderr."""
    seen = set()
    cur = bid
    if bid not in parent:
        return None                      # unknown id: unresolved, not unparented
    while cur and cur not in seen:
        seen.add(cur)
        if cur != bid and kind.get(cur) == "epic":
            return cur
        nxt = parent.get(cur)
        if not nxt:
            m = re.match(r"^(.*)\.[0-9]+$", cur)
            nxt = m.group(1) if m else None
        if nxt is None:
            return cur if kind.get(cur) == "epic" else "(unparented)"
        cur = nxt
    return None

rc = 0
for r in ready:
    bid = r["id"]
    owner = epic_of(bid)
    if owner is None:
        print("ready-in-epic: could not resolve the epic of %s — check it by hand" % bid, file=sys.stderr)
        continue
    if owner != epic:
        continue
    if ids_only:
        print(bid)
    else:
        print("%s\t%s\t%s" % (bid, r.get("priority", ""), r.get("title", "")))
'
exit 0
