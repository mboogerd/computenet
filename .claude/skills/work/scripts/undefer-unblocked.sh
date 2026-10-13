#!/usr/bin/env bash
# Reopen deferred epics once what they were deferred for no longer holds them
# back. Two kinds of deferral, two passes:
#
# HUMAN-GATED (the state pass, computenet-2ubdj). Step 3 labels an epic that is
# dry only because of parks INSIDE it `deferred:human-gated`. Nothing about
# answering a park touches the epic — the answer may be a close, an unpark, a
# council comment, on either machine — so this pass reads current state, not
# answer events: a labelled deferred epic is un-deferred as soon as one
# descendant is workable. Workable = open or in_progress, not `human`-labelled,
# no live blocking edge, no non-closed child, no parked ancestor inside the
# epic, and READY per verify-ready.sh. One `bd list --all` serves every epic.
# A deferred epic WITHOUT the label is never touched; if it has parked
# descendants and no outstanding `undefers:` marker it is reported UNLABELLED,
# because nothing would ever reopen it. Postponed epics hit this too: the line
# is advice, and label only what was deferred for a person's answer. So is one
# with no park left but workable work (its park was answered before anyone
# labelled it), unless it is `replan:postpone`/`replan:pause`.
# A labelled epic with NO parked descendant and no candidate is un-deferred
# too (computenet-2o0z4): answering a park closes it, nothing human gates the
# epic any more, and while deferred no session reaches its close gate or
# breakdown. One that is blocked elsewhere gets re-deferred with markers.
# ponytail: a session that defers for a reason this predicate cannot see gets
# its epic reopened at the next sweep; the defer comment is the only brake.
#
# BLOCKED ELSEWHERE (the marker pass), once that work has closed.
#
# THE MARKER. Step 3 defers an epic whose open children are all blocked by
# items outside it — and before deferring, labels each such blocker
# `undefers:<epic-id>`. A deferred epic is hidden from `bd ready` on every
# machine, and nothing about closing the blocker touches it, so without the
# marker "blocked until X lands" silently becomes "blocked until a person
# notices". An epic gated by parks inside it gets the label above instead.
#
# THIS SCRIPT reads only markers, never the deferred epics themselves. For each
# epic named by a marker on a CLOSED item:
#   - no non-closed item still carries its marker -> UNDEFERRED;
#   - markers remain, but an open child of the epic reads READY per
#     verify-ready.sh -> UNDEFERRED anyway (the closed blocker was the one
#     that mattered to that child);
#   - otherwise -> WAITING, nothing written.
# An UNDEFERRED epic gets `bd undefer` and a comment; a no-longer-deferred one
# is CLEARED. Either way the spent markers come off the closed items; markers
# on still-open items stay. Writes are local and ride out on the session's
# publication push.
#
# LIMIT. A blocker DELETED rather than closed never appears here, so its epic
# stays deferred until a person notices. The defer comment step 3 writes
# names the blockers, which is how that person finds the way back.
#
# Usage: undefer-unblocked.sh [--dry-run]
# Prints one line per epic: UNDEFERRED <epic> (...) | WAITING <epic> on <ids>
# | CLEARED <epic> (<status>) | UNLABELLED <epic> (...).
# Exit 0: done (possibly nothing to do). Exit 3: a query failed (bd, or
#   verify-ready.sh's own exit 3) — nothing was established; writes already
#   made for earlier epics stand.
set -uo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
VERIFY_READY=${UNDEFER_VERIFY_READY:-"$DIR/verify-ready.sh"}

DRY=0
case "${1:-}" in
  --dry-run) DRY=1 ;;
  "") ;;
  *) echo "usage: undefer-unblocked.sh [--dry-run]" >&2; exit 2 ;;
esac

rows() { # <bd list args...> -> JSON array, or exit 3
  local out
  out=$(bd list "$@" --limit 0 --json 2>/dev/null) || { echo "undefer-unblocked: bd list $* failed" >&2; exit 3; }
  out=$(printf '%s\n' "$out" | sed -n '/^[[{]/,/^[]}]/p')
  [ -n "$out" ] || out='[]'
  jq -c 'if type=="array" then . else (.issues // []) end' <<<"$out" 2>/dev/null \
    || { echo "undefer-unblocked: unreadable bd list $* output" >&2; exit 3; }
}

# --- the state pass ---
GATE=deferred:human-gated
tmp=$(mktemp -d "${TMPDIR:-/tmp}/undefer.XXXXXX") || exit 3
trap 'rm -rf "$tmp"' EXIT
rows --all > "$tmp/all.json" || exit 3   # a file: the listing overruns the argument limit
plan=$(GATE=$GATE python3 - "$tmp/all.json" <<'PY'
import json, os, sys
gate = os.environ["GATE"]
rows = {r["id"]: r for r in json.load(open(sys.argv[1]))}
labels = lambda r: r.get("labels") or []
parked = lambda r: r.get("status") in ("blocked", "deferred") or "human" in labels(r)
def up(bid):
    p = rows[bid].get("parent")
    return p or (bid.rsplit(".", 1)[0] if "." in bid else None)
has_live_child = set()
for bid, r in rows.items():
    if r.get("status") != "closed" and up(bid):
        has_live_child.add(up(bid))
def edge_blocked(r):
    for d in r.get("dependencies") or []:
        if d.get("issue_id") == r["id"] and d.get("type") in ("blocks", "conditional-blocks"):
            if rows.get(d.get("depends_on_id"), {}).get("status") not in ("closed", "pinned"):
                return True
    return False
marked = {l for r in rows.values() if r.get("status") != "closed" for l in labels(r) if l.startswith("undefers:")}
is_epic = lambda r: (r.get("issue_type") or r.get("type")) == "epic"
deferred = {i for i, r in rows.items() if is_epic(r) and r.get("status") == "deferred"
            and "tracking-umbrella" not in labels(r)}
cands = {e: [] for e in deferred}
waits = {e: [] for e in deferred}
for bid, r in sorted(rows.items()):
    if r.get("status") == "closed":
        continue
    workable = (not parked(r) and r.get("status") in ("open", "in_progress")
                and bid not in has_live_child and not edge_blocked(r))
    # One walk up per row; `clear` = no parked ancestor between it and the epic.
    seen, cur, clear = set(), up(bid), True
    while cur and cur in rows and cur not in seen:
        if cur in deferred:
            if parked(r):
                waits[cur].append(bid)
            elif workable and clear:
                cands[cur].append(bid)
        seen.add(cur)
        clear = clear and not parked(rows[cur])
        cur = up(cur)
for eid, e in sorted(rows.items()):
    if eid in deferred and gate in labels(e):
        if cands[eid]:   # first field: what it would still wait on, for the veto line
            print("CAND", eid, ",".join(waits[eid]) or "-", *cands[eid])
        elif waits[eid]:
            print("GATED", eid, *waits[eid])
        else:
            print("FREE", eid)
    elif eid in deferred and "undefers:" + eid in marked:
        pass
    elif eid in deferred and waits[eid]:
        print("UNLABELLED", eid, *waits[eid])
    elif eid in deferred and cands[eid] and not {"replan:postpone", "replan:pause"} & set(labels(e)):
        print("OPENWORK", eid, *cands[eid])
    elif is_epic(e) and e.get("status") != "deferred" and gate in labels(e):
        print("STALE", eid, e.get("status"))
PY
) || { echo "undefer-unblocked: the state pass could not read the listing" >&2; exit 3; }

while read -r kind e rest; do
  case "$kind" in
    GATED) echo "WAITING $e on $rest" ;;
    OPENWORK) echo "UNLABELLED $e (deferred with no park, label or marker, yet $rest looks workable, and nothing will reopen it; if the deferral is spent: bd undefer $e)" ;;
    FREE)
      echo "UNDEFERRED $e (was $GATE; no parked descendant gates it any more)"
      if [ "$DRY" = 0 ]; then
        bd undefer "$e" >/dev/null || { echo "undefer-unblocked: bd undefer $e failed" >&2; exit 3; }
        bd update "$e" --remove-label "$GATE" >/dev/null
        bd comment "$e" "un-deferred: was $GATE and no parked descendant remains" >/dev/null
      fi ;;
    UNLABELLED) echo "UNLABELLED $e (deferred with parked work $rest and nothing to reopen it; if a person's answer is what it waits for: bd update $e --add-label $GATE)" ;;
    STALE)
      echo "CLEARED $e ($rest)"
      [ "$DRY" = 1 ] || bd update "$e" --remove-label "$GATE" >/dev/null ;;
    CAND)
      held=${rest%% *}; rest=${rest#* }
      [ "$held" = - ] && held="" || held=" on ${held//,/ }"
      # shellcheck disable=SC2086
      vr=$("$VERIFY_READY" $rest 2>/dev/null); vrc=$?
      case "$vrc" in
        0) ready=$(printf '%s\n' "$vr" | awk '$1 == "READY" {print $2}' | tr '\n' ' ') ;;
        1) echo "WAITING $e$held (verify-ready.sh finds none of $rest ready)"; continue ;;
        *) echo "undefer-unblocked: verify-ready.sh exited $vrc on $e's descendants — nothing checked" >&2; exit 3 ;;
      esac
      echo "UNDEFERRED $e (was $GATE; now workable: ${ready% })"
      if [ "$DRY" = 0 ]; then
        bd undefer "$e" >/dev/null || { echo "undefer-unblocked: bd undefer $e failed" >&2; exit 3; }
        bd update "$e" --remove-label "$GATE" >/dev/null
        bd comment "$e" "un-deferred: was $GATE and ${ready% } is now workable" >/dev/null
      fi ;;
  esac
done <<<"$plan"

# --- the marker pass ---
# Label filtering is client-side on purpose: bd list --label-pattern is
# silently ignored in combination with --status (it returns every closed row),
# so the marker selection cannot be delegated to bd. --label (exact) is only
# ever used below for a named marker, and its result is re-filtered too.
closed=$(jq -c '[.[] | select(.status == "closed" and any(.labels[]?; startswith("undefers:")))]' "$tmp/all.json")
epics=$(jq -r '[.[].labels[]? | select(startswith("undefers:")) | sub("^undefers:"; "")] | unique | .[]' <<<"$closed")
[ -n "$epics" ] || { echo "undefer-unblocked: no spent markers"; exit 0; }

for e in $epics; do
  spent=$(jq -r --arg l "undefers:$e" '[.[] | select((.labels // []) | index($l)) | .id] | join(" ")' <<<"$closed")
  waiting=$(rows --status=open,in_progress,blocked,deferred --label "undefers:$e") || exit 3
  waiting=$(jq -r --arg l "undefers:$e" '[.[] | select((.labels // []) | index($l)) | .id] | join(" ")' <<<"$waiting")
  why="every blocker marked undefers:$e has closed ($spent)"
  if [ -n "$waiting" ]; then
    kids=$(rows --parent "$e" --status=open) || exit 3
    kids=$(jq -r '[.[].id] | join(" ")' <<<"$kids")
    ready=""
    if [ -n "$kids" ]; then
      # shellcheck disable=SC2086
      vr=$("$VERIFY_READY" $kids 2>/dev/null); vrc=$?
      case "$vrc" in
        0) ready=$(printf '%s\n' "$vr" | awk '$1 == "READY" {print $2}' | tr '\n' ' ') ;;
        1) ;;
        *) echo "undefer-unblocked: verify-ready.sh exited $vrc on $e's children — nothing checked" >&2; exit 3 ;;
      esac
    fi
    if [ -z "$ready" ]; then
      echo "WAITING $e on $waiting"
      continue
    fi
    why="blockers closed ($spent) and children READY (${ready% }) although markers remain on $waiting"
  fi
  status=$(bd show "$e" --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.[0].status // ""' 2>/dev/null)
  [ -n "$status" ] || { echo "undefer-unblocked: could not read $e" >&2; exit 3; }
  if [ "$status" = deferred ]; then
    echo "UNDEFERRED $e ($why)"
    if [ "$DRY" = 0 ]; then
      bd undefer "$e" >/dev/null || { echo "undefer-unblocked: bd undefer $e failed" >&2; exit 3; }
      bd comment "$e" "un-deferred: $why" >/dev/null
    fi
  else
    echo "CLEARED $e ($status)"
  fi
  if [ "$DRY" = 0 ]; then
    for b in $spent; do bd update "$b" --remove-label "undefers:$e" >/dev/null; done
  fi
done
exit 0
