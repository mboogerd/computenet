#!/usr/bin/env bash
# Claim an epic for this session — or take over a stale released claim — and
# push the acquisition, so the claim is a LOCK rather than a local record.
# Without the push, two machines starting slots between each other's Finalize
# pushes could both claim the epic and neither would find out all session
# (the computenet-kg7 class).
#
# Encodes step 3's rules:
#   - refuses computenet-wpvy: the SDLC epic is never /work's to claim;
#   - refuses an epic labelled `tracking-umbrella`: it carries intent above
#     sub-epics that are claimed independently, so a claim on it would
#     serialize them all behind one session;
#   - refuses an epic with an in_progress descendant (any depth) whose
#     metadata.holder checks LIVE or FOREIGN: a session is working beneath it
#     without holding the epic, and a claim would put two sessions in one
#     subtree. The hot-subtree guard below only sees writes in the last
#     STALE_MIN; this sees a live session however quiet it has been;
#   - refuses an epic whose BODY states a sequencing constraint its dependency
#     edges may not express — the names there are often milestone codenames no
#     query resolves. CLAIM_BLOCKERS_CHECKED=1 once each named predecessor is
#     resolved and closed (computenet-ci6c5);
#   - `bd update --claim` refuses any issue that carries an assignee. On an
#     OPEN epic that assignee is residue, not a live claim (Finalize clears it
#     now; older releases and crashes did not): take the epic over IF its
#     updated_at is older than --stale-min (default 15, CLAIM_STALE_MIN
#     overrides). Fresher, or an unparseable timestamp, reads as possibly
#     live: refuse. On an in_progress epic the refusal is correct (this
#     machine's crash leftover — released earlier in step 3 — or the other
#     machine's live run): refuse;
#   - stamps the owner label, skill_version, and metadata.holder — a
#     SESSION-unique identity (session-holder.sh), because `assignee` is
#     BEADS_ACTOR and therefore per-MACHINE: two sessions on one box are
#     indistinguishable in the tracker, so a live sibling's claim and a crash
#     leftover are the same row (computenet-83ay). With a holder the liveness
#     test is exact ("this row's holder is not me, and its process is alive")
#     instead of a 15-minute recency guess;
#   - RE-RUNS the liveness test immediately before it writes, so the window is
#     anchored to the CLAIM rather than to the top of step 3. On a slow host
#     those are not the same moment: one session's step 3 ran from 04:05 to
#     07:13 UTC — a single `bd update` exceeded 400s — and it claimed an epic
#     a live same-actor session was working the whole time (computenet-yurq);
#   - pushes, reading the OUTPUT rather than exit codes (bd dolt push can
#     exit 0 while printing a rejection). A rejected push pulls, re-verifies
#     the epic is still ours, and pushes once more. A push that fails on the
#     TRANSPORT instead (DNS, dial, TLS) is retried with backoff first — it
#     says nothing about the remote's state (computenet-ckvu).
#
# Run with a generous timeout (>=300s): a dolt push after a remote merge has
# been measured over 120s.
#
# Usage: claim-epic.sh <epic-id>
#        claim-epic.sh --release <epic-id> [--observed <holder> <answer>]
#
# SCRATCH=<scratch-dir> (env) puts the claim behind the slot clock: when
# slot-elapsed.sh reads EXPIRED for that scratch dir, the claim is refused with
# `EXPIRED: not claimed` (exit 1) BEFORE any bd call. A startup pull stuck for
# hours behind a hung Dolt process, or a host frozen in DarkWake, otherwise
# reaches the claim with the slot long gone and claims an epic it can only
# release (computenet-dfsgn, computenet-fqvhz). Unset → not checked, said so.
# Exit 0: claimed and pushed (took over or fresh — output says which).
# Exit 1: not claimed (reason on stderr) — select another epic, or stop.
#         Includes NOT CHECKED: the epic or its descendants could not be read,
#         so the refusals below could not run — the claim fails closed.
# Exit 2: claimed LOCALLY but not published — stop the session and report;
#         an unpushed epic claim is exactly the race this script closes.
#
# --release reopens a dead run's epic (status open, no assignee, no holder;
# local, not pushed) — step 3's startup release. Before the clearing write it
# comments on the epic "released by <this session's holder token>: observed
# holder <h>, classified <answer>" — the clearing destroys the only evidence of
# whose claim it was, so the comment keeps it (computenet-60f8). <h> defaults
# to the epic's current metadata.holder; --observed supplies what step 3 saw.
# A failed comment is a warning, never a reason to keep a dead claim. It applies the live-descendant
# test first. Exit 0 released; exit 1 KEPT, a LIVE or FOREIGN session works
# beneath it, or a descendant was touched within STALE_MIN (named on stderr) — leave it claimed and do not select it; exit 3
# NOT CHECKED, the descendants could not be listed, nothing written; exit 4
# the release write itself failed.
#
# A FOREIGN descendant holder cannot be pid-tested from here, so it blocks
# only while its bead was written within HOLDER_MAX_AGE_S (session-holder.sh's
# slot bound, default 21600s): older than any slot, it is residue.
set -uo pipefail

: "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
mode=claim
if [ "${1:-}" = --release ]; then mode=release; shift; fi
id=${1:?usage: claim-epic.sh [--release] <epic-id> [--observed <holder> <answer>]}
obs_holder=; obs_answer=
if [ "$mode" = release ] && [ "${2:-}" = --observed ]; then
  obs_holder=${3:-}; obs_answer=${4:-}
fi
STALE_MIN=${CLAIM_STALE_MIN:-15}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# Every bead beneath the epic, at any depth: by explicit parent, or by dotted
# id prefix. Reads a bd list --all array on stdin and emits the descendant rows.
DESC_JQ='(if type=="array" then . else (.issues // []) end) as $all
  | reduce range(0;6) as $_ ([$e]; . as $set | $set + [$all[] | select((.parent // "") as $p
      | ($set | index($p)) != null or (.id | startswith($e + "."))) | .id] | unique)
  | (. - [$e]) as $kids | $all[] | select(.id as $i | $kids | index($i))'

load_rows() {
  all_rows=$(bd list --all --limit 0 --json 2>/dev/null); list_rc=$?
  all_rows=$(printf '%s\n' "$all_rows" | sed -n '/^[[{]/,/^[]}]/p')
}

# The newest updated_at among descendants, of any status, stamped with holder
# $1. A session writes its epic's CHILDREN, never the epic row, so this — not
# the held row's own updated_at — is its "residue does not write" evidence
# (computenet-2jx46).
newest_by_holder() {
  jq -r --arg e "$id" --arg h "$1" "[$DESC_JQ"' | select((.metadata.holder // "") == $h)
      | .updated_at // empty] | max // ""' <<<"$all_rows" 2>/dev/null
}

# Prints "<id> held by <holder> (<verdict>)" per in_progress descendant whose
# holder is someone else's live session.
live_descendants() {
  jq -r --arg e "$id" "$DESC_JQ"' | select(.status == "in_progress")
      | select((.metadata.holder // "") != "")
      | "\(.id)\t\(.metadata.holder)"' <<<"$all_rows" 2>/dev/null \
  | while IFS=$'\t' read -r d h; do
      u=$(newest_by_holder "$h")
      v=$("$SCRIPT_DIR/session-holder.sh" --check "$h" "$u" 2>/dev/null)
      case "$v" in
        LIVE) echo "$d held by $h (LIVE)" ;;
        FOREIGN)
          ue=$(jq -rn --arg u "$u" '$u | sub("\\.[0-9]+"; "") | try fromdateiso8601 catch empty')
          if [ -z "$ue" ] || [ $(( $(date +%s) - ue )) -le "${HOLDER_MAX_AGE_S:-21600}" ]; then
            echo "$d held by $h (FOREIGN, written $u)"
          fi ;;
      esac
    done
}

# Prints "<id> updated <ts>" for up to 3 descendants touched within STALE_MIN —
# the assignee-blind HOT signal (see the claim path's comment below). Ids this
# machine's own sweep recorded within the window are discounted (x3f5a).
hot_descendants() {
  local cutoff swept
  cutoff=$(( $(date +%s) - STALE_MIN * 60 ))
  SWEPT_FILE=${CLAIM_SWEPT_FILE:-"${TMPDIR:-/tmp}/work-swept-${BEADS_ACTOR}"}
  swept=$(awk -v c="$cutoff" '$1 >= c {print $2}' "$SWEPT_FILE" 2>/dev/null \
    | jq -Rn '[inputs | select(length > 0)]')
  [ -n "$swept" ] || swept='[]'
  jq -r --arg e "$id" --argjson c "$cutoff" --argjson swept "$swept" "$DESC_JQ"'
        | select(.id as $i | ($swept | index($i)) == null)
        | select(((.updated_at // "") | sub("\\.[0-9]+"; "") | try fromdateiso8601 catch 0) >= $c)
        | "\(.id) updated \(.updated_at)"' <<<"$all_rows" 2>/dev/null | head -3
}

rows_ok() { [ "$list_rc" = 0 ] && jq -e 'type == "array" or type == "object"' >/dev/null 2>&1 <<<"$all_rows"; }

if [ "$mode" = release ]; then
  load_rows
  if ! rows_ok; then
    echo "NOT CHECKED: could not list $id's descendants — nothing released" >&2
    exit 3
  fi
  live=$(live_descendants)
  if [ -z "$obs_holder" ]; then
    obs_holder=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p' \
                 | jq -r '.[0].metadata.holder // "none"' 2>/dev/null)
  fi
  # The epic's own holder, re-judged on its subtree writes: step 3 judged it on
  # the epic row's updated_at, which a long-running session never bumps.
  if [ -n "$obs_holder" ] && [ "$obs_holder" != none ]; then
    wrote=$(newest_by_holder "$obs_holder")
    if [ "$("$SCRIPT_DIR/session-holder.sh" --check "$obs_holder" "$wrote" 2>/dev/null)" = LIVE ]; then
      live="${live:+$live
}$id held by $obs_holder (LIVE, wrote beneath it at $wrote)"
    fi
  fi
  if [ -n "$live" ]; then
    echo "KEPT: $id — a live session works beneath it; leave it claimed and do not select it:" >&2
    printf '  %s\n' "$live" >&2
    exit 1
  fi
  # m090n: a session that stamps no holder (interactive, or one whose holder
  # could not be minted) is invisible to the holder test; recency is all it
  # leaves. computenet-6aj8h was released under such a session's live child.
  hot=$(hot_descendants)
  if [ -n "$hot" ]; then
    echo "KEPT: $id — a descendant was touched within ${STALE_MIN}m (a session without a holder may be in it); leave it claimed and do not select it:" >&2
    printf '  %s\n' "$hot" >&2
    exit 1
  fi
  me=$("$SCRIPT_DIR/session-holder.sh" 2>/dev/null) || me="$BEADS_ACTOR (no holder token)"
  bd comment "$id" "released by $me: observed holder ${obs_holder:-none}, classified ${obs_answer:-unstated}" >/dev/null 2>&1 \
    || echo "note: could not comment the release evidence on $id — releasing anyway" >&2
  bd update "$id" --status=open --assignee="" --unset-metadata holder >/dev/null \
    || { echo "release write failed on $id" >&2; exit 4; }
  echo "released $id"
  exit 0
fi

if [ "$id" = computenet-wpvy ]; then
  echo "REFUSED: $id is the SDLC epic and never /work's to claim" >&2
  exit 1
fi

# dfsgn/fqvhz: the slot clock, read by the clock itself, before any bd call.
if [ -n "${SCRATCH:-}" ]; then
  reading=$("$SCRIPT_DIR/slot-elapsed.sh" "$SCRATCH" 2>&1); src=$?
  if [ "$src" = 0 ] && grep -q 'rung: EXPIRED' <<<"$reading"; then
    printf '%s\n' "$reading" | head -1 >&2
    echo "EXPIRED: not claimed" >&2
    exit 1
  fi
  [ "$src" = 0 ] || echo "note: slot-elapsed.sh could not read $SCRATCH (rc=$src) — budget not checked" >&2
else
  echo "note: SCRATCH unset — slot budget not checked" >&2
fi

show_json=$(bd show "$id" --json 2>/dev/null); show_rc=$?
show_json=$(printf '%s\n' "$show_json" | sed -n '/^[[{]/,/^[]}]/p')
if [ "$show_rc" != 0 ] || ! jq -e '.[0].id' >/dev/null 2>&1 <<<"$show_json"; then
  echo "NOT CHECKED: could not read $id — not claimed; select another epic" >&2
  exit 1
fi
if jq -e '(.[0].labels // []) | index("tracking-umbrella")' >/dev/null 2>&1 <<<"$show_json"; then
  echo "REFUSED: $id is a tracking umbrella — never claimed or broken down; its sub-epics are candidates in their own right" >&2
  exit 1
fi

load_rows
if ! rows_ok; then
  echo "NOT CHECKED: could not list $id's descendants — not claimed; select another epic" >&2
  exit 1
fi

live=$(live_descendants)
if [ -n "$live" ]; then
  echo "REFUSED: a live session works beneath $id without holding it:" >&2
  printf '  %s\n' "$live" >&2
  exit 1
fi

# ci6c5: a sequencing constraint stated in the epic's BODY but absent from its
# dependency EDGES is invisible to every readiness query — verify-ready.sh
# reads the real edges and correctly answers READY, because the edges that
# exist genuinely are satisfied. One session claimed and PUSHED computenet-yk6
# before reading its section 4: "queues behind KX -> KE1 -> KE3 -> MEM1 ->
# MEM2 and cannot be worked autonomously in parallel with them" — five names,
# two of them expressed as edges, two of them open. Cost: a claim, a push, a
# release, a second push, and a window in which the other machine saw an epic
# claimed that nobody was working. 5b already applies exactly this test to a
# TASK's stated blocker (computenet-rjyl); epic selection is one layer up,
# where the names are most likely to be milestone codenames no query resolves
# and where the claim is PUBLISHED before any body text has been read.
#
# So it runs here, before any write. The phrase set is deliberately narrow —
# measured against all 44 non-closed epics when it was chosen, it matched 2,
# one of them yk6 itself; the broad candidates ("depends on", "prerequisite",
# "only after") each matched 9-27 and would have made the refusal noise.
# Narrow means it misses phrasings, which is the right failure: this is a
# backstop, not the only reading of the body.
if [ "${CLAIM_BLOCKERS_CHECKED:-}" != 1 ]; then
  stated=$(jq -r '.[0].description // ""' <<<"$show_json" 2>/dev/null \
    | grep -inE 'queues behind|cannot be worked|in parallel with|sequenced after|must land after|must be admitted alone|blocked by ' \
    | cut -c1-200 | head -5)
  if [ -n "$stated" ]; then
    echo "REFUSED: $id's body states a sequencing constraint, and its edges may not express it:" >&2
    printf '  %s\n' "$stated" >&2
    echo "Resolve each predecessor NAMED there (milestone codenames resolve to beads; edges may not exist for them)." >&2
    echo "  any open  -> select another epic;" >&2
    echo "  all closed -> add the missing blocking edges so the graph and the prose agree, then re-run with CLAIM_BLOCKERS_CHECKED=1." >&2
    exit 1
  fi
fi

# hl8x: the tracker cannot show the OTHER machine inside this subtree — its
# epic claim is released at Finalize while an in-flight child continues, and
# every other step-3 guard is this-machine-only. So before claiming, test
# whether the subtree is HOT by two assignee-blind signals: any descendant
# bead touched within STALE_MIN, or any origin/feature/<epic>* tip pushed
# within STALE_MIN. A hit means SKIP this candidate (exit 1, signal named),
# not park: the epic is fine, someone is simply still in it. One machine
# claimed computenet-ssa this way 2 minutes after the other merged a task
# under it and sat in feature review; the reversal cost ~12 minutes and a
# hand-resolved Dolt conflict. CLAIM_SKIP_HOT=1 bypasses (resume of your own
# subtree after a crash is the honest case).
if [ "${CLAIM_SKIP_HOT:-}" != 1 ]; then
  cutoff=$(( $(date +%s) - STALE_MIN * 60 ))
  # x3f5a: the signal is assignee-blind because the other machine's writes are
  # all we have. But this machine's own writes land in the same field, and step
  # 3 runs sweep-stale-claims.sh IMMEDIATELY BEFORE this — so every task it
  # released reads as another machine's activity, for STALE_MIN minutes, under
  # exactly the epics the resume preference is for. Neither the sweep's writes
  # nor these are published, so a local release cannot be evidence about a
  # remote session. Discount the ids the sweep recorded within the window.
  hot=$(hot_descendants)
  if [ -n "$hot" ]; then
    echo "SKIP: $id's subtree is hot — a child was touched within ${STALE_MIN}m (the other machine may be in it):" >&2
    printf '  %s\n' $hot >&2 2>/dev/null || printf '%s\n' "$hot" >&2
    exit 1
  fi
  git fetch -q origin "refs/heads/feature/$id*:refs/remotes/origin/feature/$id*" 2>/dev/null || true
  hotref=$(git for-each-ref --format='%(refname:short) %(committerdate:unix)' "refs/remotes/origin/feature/$id*" 2>/dev/null \
    | awk -v c="$cutoff" '$2 >= c {print $1}' | head -3)
  if [ -n "$hotref" ]; then
    echo "SKIP: $id's subtree is hot — a feature branch tip was pushed within ${STALE_MIN}m: $hotref" >&2
    exit 1
  fi
fi

# yurq: re-verify AT THE CLAIM, not at the top of step 3 — and BEFORE the
# write, so a LIVE/FOREIGN refusal leaves the bead exactly as found; the
# post-write ordering claimed-then-disowned two epics per run (computenet-hdow).
recheck=$(bd show "$id" --json | sed -n '/^[[{]/,/^[]}]/p')
held=$(jq -r '.[0].metadata.holder // ""' <<<"$recheck")
if [ -n "$held" ]; then
  # The epic's OWN updated_at, which the hot-subtree guard above excludes
  # (it tests descendants): a holder that wrote moments ago is a long-running
  # session, not residue, and taking it over puts two sessions on one epic
  # (computenet-jqxqk).
  verdict=$("$SCRIPT_DIR/session-holder.sh" --check "$held" \
            "$(jq -r '.[0].updated_at // ""' <<<"$recheck")"); hrc=$?
  case "$verdict" in
    MINE) : ;;                      # already ours, this session — idempotent
    LIVE)
      re_status=$(jq -r '.[0].status // ""' <<<"$recheck")
      re_assignee=$(jq -r '.[0].assignee // ""' <<<"$recheck")
      if [ "$re_status" = open ] && [ -z "$re_assignee" ]; then
        # A released epic (open, no assignee) still carrying a holder: the
        # holder is residue from the releasing session, not a live claim
        # (computenet-nkz3) — proceed and overwrite it below.
        echo "note: $id's holder ($held) is residue on a released epic — proceeding"
      else
        echo "REFUSED: $id is held by a LIVE session ($held) — not a crash leftover" >&2
        exit 1
      fi ;;
    DEAD) echo "note: $id's previous holder ($held) is dead — taking it over" ;;
    STALE) echo "note: $id's holder ($held) is a host process older than any slot — residue, taking over" ;;
    FOREIGN)
      if [ "$(jq -r '.[0].status // ""' <<<"$recheck")" = open ] \
         && [ -z "$(jq -r '.[0].assignee // ""' <<<"$recheck")" ]; then
        # Same residue test as the LIVE arm: a released epic's stale holder.
        echo "note: $id's foreign holder ($held) is residue on a released epic — proceeding"
      else
      echo "REFUSED: $id is held by a session on ANOTHER machine ($held) — liveness cannot be tested here; it is not this box's leftover (computenet-bz5c)" >&2
      exit 1
      fi ;;
    *)    echo "note: $id's holder ($held) could not be evaluated (rc=$hrc) — proceeding on the recency test above" ;;
  esac
fi

out=$(bd update "$id" --claim 2>&1); st=$?
if [ $st -ne 0 ] || grep -qi "already claimed" <<<"$out"; then
  grep -qi "already claimed" <<<"$out" || { echo "claim failed: $out" >&2; exit 1; }

  json=$(bd show "$id" --json)
  status=$(jq -r '.[0].status // empty' <<<"$json")
  assignee=$(jq -r '.[0].assignee // ""' <<<"$json")
  cutoff=$(( $(date +%s) - STALE_MIN * 60 ))
  # tolerate fractional seconds; an unparseable date reads as "possibly live"
  epoch=$(jq -r '.[0].updated_at // empty
                 | sub("\\.[0-9]+"; "") | try fromdateiso8601 catch empty' <<<"$json")

  if [ "$status" != open ]; then
    echo "REFUSED: $id is $status, assignee=$assignee — a crash leftover or a live run, not a takeover case" >&2
    exit 1
  fi
  if [ -z "$epoch" ] || [ "$epoch" -ge "$cutoff" ]; then
    echo "REFUSED: $id is open but touched within ${STALE_MIN}m (assignee=$assignee) — possibly a live run" >&2
    exit 1
  fi

  bd update "$id" --assignee="$BEADS_ACTOR" --status=in_progress \
    || { echo "takeover write failed on $id" >&2; exit 1; }
  echo "took over $id from stale assignee '$assignee' (open, idle > ${STALE_MIN}m)"
fi

bd update "$id" --add-label="owner:$BEADS_ACTOR" >/dev/null
bd update "$id" --set-metadata "skill_version=$(git hash-object "$SCRIPT_DIR/../SKILL.md")" >/dev/null
holder=$("$SCRIPT_DIR/session-holder.sh" 2>/dev/null) \
  && bd update "$id" --set-metadata "holder=$holder" >/dev/null \
  || echo "note: could not stamp metadata.holder — liveness falls back to the recency test" >&2

. "$SCRIPT_DIR/dolt-push-lib.sh"    # push_with_backoff (computenet-ckvu)

push_out=$(push_with_backoff); push_rc=$?
if [ "$push_rc" = 2 ]; then
  printf '%s\n' "$push_out" >&2
  echo "ESCALATE: push failed 3x with a transport fault (network/DNS), not a rejection;" \
       "claim is LOCAL-ONLY — stop the session and report" >&2
  exit 2
fi
if [ "$push_rc" != 0 ]; then
  echo "-- push rejected; recovering: pull, re-verify, push --"
  pull_out=$(bd dolt pull 2>&1)
  if grep -qi "conflict" <<<"$pull_out"; then
    printf '%s\n' "$pull_out" >&2
    echo "ESCALATE: pull hit a merge conflict — see .claude/skills/work/references/recovery.md § Dolt pull conflicts (an issues-only modify/modify conflict is resolvable here; anything else needs an operator); claim is LOCAL-ONLY" >&2
    exit 2
  fi
  now_assignee=$(bd show "$id" --json | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.[0].assignee // ""')
  if [ "$now_assignee" != "$BEADS_ACTOR" ]; then
    echo "LOST RACE: after the pull, $id is assigned to '$now_assignee' — select another epic" >&2
    exit 1
  fi
  push_out=$(push_with_backoff); push_rc=$?
  if [ "$push_rc" != 0 ]; then
    printf '%s\n' "$push_out" >&2
    echo "ESCALATE: push failed on both attempts; claim is LOCAL-ONLY — stop the session and report" >&2
    exit 2
  fi
fi
echo "claimed $id (pushed)"
