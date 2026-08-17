#!/usr/bin/env bash
# Reclaim worktrees whose bead is already closed (computenet-8l4r).
#
# WHY A SECOND SWEEP. sweep-merged-prs.sh joins from the BEAD side: it lists
# non-closed beads and removes a worktree only as a consequence of a close it
# just performed. So the instant a bead reaches `closed` with its worktree
# still on disk, nothing ever reclaims that directory again — not the sweep,
# not Finalize, not the resume path. That happens on every close route that is
# not the sweep: a human `bd close`, a session closing its own work, this
# lane's supersede-closes. Four such directories were measured stranded on one
# machine (computenet-oxv.10, computenet-wpvy.3, .4, .9), and the fix for the
# leak could not reach them because it only looks forward.
#
# This inverts the join: enumerate `git worktree list`, and for each
# computenet-worktrees/<id> whose BEAD IS CLOSED, remove it. No metadata is
# consulted, so a worktree stranded by any route is reachable.
#
# THREE GUARDS, because removing a live session's worktree destroys pushed,
# unreviewed work:
#  * The bead must be CLOSED. An open or in_progress bead is somebody's state.
#  * The tree must be CLEAN (`git status --short` empty). A dirty tree is an
#    agent mid-edit, or a crash that left work worth looking at, and a closed
#    bead does not make uncommitted changes disposable — report, never remove.
#  * The worktree must be QUIET for --min-age-minutes (default 15, the same
#    floor the liveness checks in SKILL.md step 3 and 5f use). A worktree
#    touched moments ago belongs to a concurrent session even if its bead was
#    just closed; the close and the last commit race by seconds.
#
# Directory name -> bead id, so a path that is not <id>-shaped is skipped
# rather than guessed at.
#
# Usage: reclaim-worktrees.sh [--dry-run] [--min-age-minutes N]
# Exit: 0 = nothing stranded, or all reclaimed; 1 = a removal failed, or a
#       candidate was skipped as dirty (both need a human eye); 2 = bad usage;
#       3 = a precondition failed (bd or jq unusable) — nothing was checked.
set -uo pipefail

DRY_RUN=0
MIN_AGE=15
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --min-age-minutes) MIN_AGE=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null || { echo "reclaim-worktrees: jq unusable; NOTHING was checked" >&2; exit 3; }
command -v bd >/dev/null || { echo "reclaim-worktrees: bd unusable; NOTHING was checked" >&2; exit 3; }

rc=0
found=0
while IFS= read -r line; do
  case "$line" in worktree\ *) path=${line#worktree } ;; *) continue ;; esac
  case "$path" in *"/computenet-worktrees/"*) ;; *) continue ;; esac
  id=$(basename "$path")
  # Bead ids look like computenet-<slug> or computenet-<slug>.<n>...
  case "$id" in computenet-*) ;; *) continue ;; esac

  status=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p' | jq -r '.[0].status // ""' 2>/dev/null)
  [ "$status" = "closed" ] || continue
  found=1

  dirty=$(git -C "$path" status --short 2>/dev/null)
  if [ -n "$dirty" ]; then
    echo "SKIP $id: bead closed but tree is DIRTY — look before removing:" >&2
    printf '%s\n' "$dirty" >&2
    rc=1
    continue
  fi

  # Quiet for long enough? Newest mtime anywhere under the worktree, excluding
  # .git bookkeeping that a bare `git worktree list` elsewhere can touch.
  if [ -n "$(find "$path" -mmin "-$MIN_AGE" -not -path '*/.git/*' -print -quit 2>/dev/null)" ]; then
    echo "SKIP $id: touched within ${MIN_AGE}m — treating as a live session" >&2
    continue
  fi

  if [ "$DRY_RUN" = 1 ]; then
    echo "would remove $path (bead $id closed, tree clean, quiet ${MIN_AGE}m+)"
    continue
  fi
  if git worktree remove "$path" 2>/dev/null; then
    echo "removed $path (bead $id closed)"
  else
    echo "FAILED to remove $path — remove by hand" >&2
    rc=1
  fi
done < <(git worktree list --porcelain)

[ "$found" = 1 ] || echo "no worktrees stranded behind a closed bead"
exit $rc
