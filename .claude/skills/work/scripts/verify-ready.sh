#!/bin/sh
# verify-ready.sh <issue-id> [<issue-id>...]
#
# WHY THIS EXISTS. `bd ready`, `bd blocked`, ready-in-epic.sh and
# next-batch.py all derive blockedness from bd's DENORMALIZED `is_blocked`
# column, and that column goes stale against the live edge set the moment a
# blocker closes — which is the event a work session generates constantly.
# Measured seven times across two sessions on 2026-08-19 (computenet-r79z,
# computenet-38ze): beads whose only blocking edge pointed at a CLOSED bead,
# and beads created minutes earlier carrying NO blocking edge at all, were
# reported blocked. Neither `bd dolt pull` nor `bd dolt push` cleared it, and
# re-running in a separate Bash call returns the same wrong answer — so the
# computenet-2mou same-invocation guard does not cover this.
#
# WHY IT MATTERS BEYOND LOST TIME. SKILL.md step 3 treats an empty readiness
# answer as grounds for `bd defer <epic>`, which hides the epic from BOTH
# machines until a human notices, and 5b's `blocked` verdict parks the
# feature. One session would have deferred an epic holding four ready
# children.
#
# WHAT THIS DOES. Reads each id's ACTUAL edges with `bd dep list`, which
# prints the target's status and the edge type on every line, and applies
# READY-COVERAGE.md section 2's test directly: an edge blocks only if its
# type is blocks/conditional-blocks AND its target is neither closed nor
# pinned. No denormalized column is consulted.
#
# A CLOSED blocker still blocks when it is a task under a DIFFERENT feature that
# is not itself closed: a task closes when it merges into ITS feature branch, so
# its code is on that branch, not on main and not on this task's feature branch
# (computenet-frxh6: cab.6.5 read READY while QueryCompiler existed only on
# feature/computenet-cab.5). Such lines are suffixed `[unmerged on <feature>]`.
#
# ALSO CHECKS metadata.base_branch, which goes stale within MINUTES of being
# written. A review-filed residual names the branch under review, and that
# branch is normally about to merge — that is what the review was for. Five
# instances (computenet-osax): computenet-4jpd and computenet-dmkp on
# 2026-08-25, the second stale ~15 minutes after it was written; computenet-
# 5yavt (PR #870 merged ~2 minutes later); computenet-tlb83 (merged in the same
# orchestrator turn); and a feature carrying another feature's branch. The
# failure is SILENT, not loud: a merged branch's ref still exists on origin, so
# a session that trusts the field gets a plausible worktree cut from spent code
# and re-derives what main already has. Checked here rather than left to prose
# because the check has to happen at selection, which is when this runs.
#
# Output: one line per id, `READY <id>` or `BLOCKED <id> by: <lines>`, plus a
# `STALE-BASE`/`MISSING-BASE`/`UNCHECKED-BASE` note under either when
# metadata.base_branch is set and no longer usable. The notes are advisory and
# do not change the exit code: an id whose base is stale is still ready, it just
# has to be cut from origin/main with the field cleared.
# Exit: 0 = at least one READY; 1 = none ready; 2 = bad usage;
#       3 = a `bd dep list` (or this id's `bd show`) call failed — NOTHING was checked, do not route
#           on this (the ready-in-epic.sh exit-3 class).
set -eu

[ $# -ge 1 ] || { echo "usage: verify-ready.sh <issue-id> [<issue-id>...]" >&2; exit 2; }

any_ready=1
for id in "$@"; do
  deps=$(bd dep list "$id" 2>/dev/null) \
    || { echo "verify-ready: 'bd dep list $id' failed; NOTHING was checked" >&2; exit 3; }
  # Blocking edges whose target is not closed and not pinned.
  live=$(printf '%s\n' "$deps" \
         | grep -E 'via (blocks|conditional-blocks)$' \
         | grep -vE '\((closed|pinned)\) via ' || true)
  own_parent=
  for dep in $(printf '%s\n' "$deps" | grep -E 'via (blocks|conditional-blocks)$' \
               | grep -E '\(closed\) via ' | sed -E 's/^ *([^: ]+):.*/\1/'); do
    if [ -z "$own_parent" ]; then
      own_parent=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p' | jq -r '.[0].parent // "" | if . == "" then "-" else . end')
      # Empty = the lookup failed; comparing against "" would flag a blocker
      # under this task's OWN open feature as unmerged.
      [ -n "$own_parent" ] || { echo "verify-ready: 'bd show $id' failed; NOTHING was checked" >&2; exit 3; }
    fi
    dep_parent=$(bd show "$dep" --json 2>/dev/null | sed -n '/^[[{]/,$p' | jq -r '.[0].parent // empty')
    [ -n "$dep_parent" ] && [ "$dep_parent" != "$own_parent" ] || continue
    fp=$(bd show "$dep_parent" --json 2>/dev/null | sed -n '/^[[{]/,$p' \
         | jq -r '.[0] | select(.issue_type == "feature" and .status != "closed") | .id')
    [ -n "$fp" ] || continue
    live="${live:+$live
}  $dep (closed) [unmerged on $fp]"
  done
  if [ -n "$live" ]; then
    echo "BLOCKED $id by:"
    printf '%s\n' "$live" | sed 's/^ */    /'
  else
    echo "READY $id"
    any_ready=0
  fi

  base=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p' \
         | jq -r '.[0].metadata.base_branch // empty' 2>/dev/null || true)
  [ -n "$base" ] || continue
  if ! git rev-parse --verify -q "refs/remotes/origin/$base" >/dev/null 2>&1; then
    echo "    UNCHECKED-BASE base_branch=$base: no origin/$base here; fetch, or check its PR state by hand"
  elif git merge-base --is-ancestor "origin/$base" origin/main 2>/dev/null; then
    echo "    STALE-BASE base_branch=$base is already on origin/main: clear the field, cut from origin/main, and say so on the bead"
  else
    echo "    LIVE-BASE base_branch=$base is not yet on origin/main: cut from it and target the PR at it"
  fi
done
exit $any_ready
