#!/usr/bin/env bash
# Classify a worktree's branch against origin — SKILL.md 5a's four-outcome
# verification block AND the squash test that follows it, as one reading.
#
# The shape is deliberate. A bare `fetch origin <branch>` fails identically
# for "no such branch on origin" and "the network is down", so a single else
# once reported an unreachable origin as "first run, nothing to compare" — an
# OK on a check that never ran (computenet-dtl). And this repo squash-merges,
# so a fully-landed branch leaves a remote ref whose commits are ancestors of
# nothing — which trips the containment check exactly like genuinely unmerged
# work does, and a workable item then looks unworkable with no remediation
# offered (computenet-q8uv). So the "wrong commit" finding is never final
# until the squash test has run.
#
# Usage: verify-branch-sync.sh <worktree> <branch>
# Stdout: evidence lines, then the verdict as the FINAL line — read it, and
# only it:
#   OK-CONTAINS         exit 0  origin/<branch> is an ancestor of HEAD
#   OK-NO-REMOTE-BRANCH exit 0  origin reachable, has no such branch (first run)
#   SQUASH-LEFTOVER     exit 2  the ref outlived its squash-merged content;
#                               remediate (re-mint or delete the dead ref),
#                               nothing is orphaned
#   STOP-UNMERGED       exit 1  origin holds commits HEAD lacks — proceeding
#                               orphans reviewed work (computenet-aeg)
#   STOP-UNREACHABLE    exit 3  NOTHING WAS CHECKED
set -uo pipefail

wt=${1:?usage: verify-branch-sync.sh <worktree> <branch>}
br=${2:?usage: verify-branch-sync.sh <worktree> <branch>}

if git -C "$wt" fetch origin "$br" 2>/dev/null; then
  if git -C "$wt" merge-base --is-ancestor FETCH_HEAD HEAD; then
    echo "OK: worktree contains origin/$br"
    echo OK-CONTAINS
    exit 0
  fi

  # HEAD is missing pushed work — but see the header: run the squash test
  # before treating this as an orphaning hazard. Two cheap tests, in order.
  gh_ok=1
  merged='[]'
  if ! merged=$(gh pr list --head "$br" --state merged --json number,title 2>/dev/null); then
    # A failed gh call is not a reading (SKILL.md step 2) — it is never
    # evidence of "no merged PR". Fall back to the local grep alone.
    gh_ok=0
    merged='[]'
    echo "note: gh failed — merged-PR state of $br unreadable; relying on the origin/main grep alone"
  fi
  pr_hits=$(jq -r '.[] | "merged PR #\(.number): \(.title)"' <<<"$merged" 2>/dev/null) || pr_hits=""

  # The id in the branch name: strip any -rN re-mint suffix first
  # (feature-branch.sh mints feature/<id>-rN), then take the trailing
  # computenet-<...> segment. Dotted child ids (computenet-dqy.2) included.
  id=$(printf '%s' "$br" | sed -E 's/-r[0-9]+$//' | sed -nE 's/.*(computenet-[a-z0-9.]+)$/\1/p')
  log_hits=""
  if [ -z "$id" ]; then
    echo "note: no bead id in branch name '$br' — the origin/main grep cannot run"
  elif ! git -C "$wt" rev-parse --verify --quiet origin/main >/dev/null; then
    echo "note: origin/main is not present locally — the origin/main grep cannot run"
  else
    log_hits=$(git -C "$wt" log --oneline origin/main --grep "$id" 2>/dev/null | head -3)
  fi

  if [ -n "$pr_hits" ] || [ -n "$log_hits" ]; then
    # A squash merge leaves the source commits non-ancestral, so the ref
    # outlives its content (verified 2026-08-17 against
    # friction/computenet-kd9s / PR #262 and friction/computenet-6xm / PR
    # #276). Either finding is the answer on its own.
    echo "squash-merged leftover — the ref outlived its landed content:"
    [ -n "$pr_hits" ] && printf '%s\n' "$pr_hits"
    [ -n "$log_hits" ] && printf '%s\n' "$log_hits"
    echo SQUASH-LEFTOVER
    exit 2
  fi

  echo "STOP: on the branch at the wrong commit — origin/$br holds commits HEAD lacks"
  [ "$gh_ok" -eq 0 ] \
    && echo "note: gh could not be consulted — 'no merged PR' was NOT established; only the origin/main grep came back empty"
  echo STOP-UNMERGED
  exit 1
fi

# The fetch failed. Bare `ls-remote origin`, no --exit-code and no ref
# argument, deliberately: --exit-code exits 2 on an origin that is reachable
# but has no refs yet, which would report a reachable empty remote as
# unreachable. It succeeds only if origin actually answered (computenet-dtl).
if git -C "$wt" ls-remote origin >/dev/null 2>&1; then
  echo "OK: origin has no $br yet (nothing to compare)"
  echo OK-NO-REMOTE-BRANCH
  exit 0
fi

echo "STOP: origin is UNREACHABLE — NOTHING WAS CHECKED"
echo STOP-UNREACHABLE
exit 3
