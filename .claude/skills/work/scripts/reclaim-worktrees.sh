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
# FIVE GUARDS, because removing a live session's worktree destroys pushed,
# unreviewed work:
#  * The bead must be CLOSED. An open or in_progress bead is somebody's state.
#  * The tree must be CLEAN (`git status --short` empty). A dirty tree is an
#    agent mid-edit, or a crash that left work worth looking at, and a closed
#    bead does not make uncommitted changes disposable — report, never remove.
#  * No OPERATION IN PROGRESS. A paused rebase, merge, cherry-pick or revert
#    leaves `git status --short` empty between conflicts, so it has to be
#    asked about separately or it reads as a pristine tree.
#  * HEAD must be ON ORIGIN. This is the guard a clean tree does NOT give you:
#    `git status --short` says nothing about *commits*. A worktree whose bead
#    was closed but whose branch carries commits that were never pushed passes
#    every other guard, and `git worktree remove` then deletes the directory
#    without a word — rc=0, "removed". The branch ref survives, so the commits
#    are technically recoverable, but nothing on the operator's screen says
#    work was there, and ignored-but-wanted files in the directory are gone
#    for good. So: resolve HEAD's branch, ask origin for it with a read-only
#    ANY origin/* ref (`git branch -r --contains`), not the same-named branch:
#    a task branch is never pushed, so a by-name test is false for every task
#    worktree (computenet-13kh). Its commits live in origin/<feature-branch>.
#    Detached HEAD, a branch origin has never heard of, an unreachable origin,
#    and local commits ahead of the remote tip are each a SKIP, never a
#    removal — the check has to *prove* the commits are elsewhere, and
#    "couldn't tell" is not a proof.
#  * The worktree must be QUIET for --min-age-minutes (default 15, the same
#    floor the liveness checks in SKILL.md step 3 and 5f use). This is the
#    WEAKEST guard and is not a liveness test: an agent that has an idle
#    worktree open reads as quiet after 15 minutes. It is a cheap filter
#    against the narrow race where a bead is closed and its worktree written
#    seconds apart, nothing more — the load-bearing guards are the four above.
#
# Directory name -> bead id, so a path that is not <id>-shaped is skipped
# rather than guessed at.
#
# Usage: reclaim-worktrees.sh [--dry-run] [--min-age-minutes N]
# Exit: 0 = nothing stranded, or all reclaimed; 1 = a removal failed, or a
#       candidate was skipped as dirty, mid-operation, or not provably pushed
#       (all need a human eye); 2 = bad usage; 3 = a precondition failed (bd
#       or jq unusable) — nothing was checked.
#
# NOT guarded: files matched by .gitignore are invisible to every check here
# and go with the directory. That is build output by construction; nothing
# worth keeping should live in a worktree under an ignore rule.
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

# One fetch, before the loop: `branch -r --contains` reads REMOTE-TRACKING refs,
# so a stale set would report commits as unheld and skip everything. A failed
# fetch means we cannot prove anything — say so and remove nothing.
if ! git fetch origin --quiet 2>/dev/null; then
  echo "reclaim-worktrees: cannot fetch origin — containment is unprovable, NOTHING was checked" >&2
  exit 3
fi

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

  # An in-progress rebase/merge/cherry-pick/revert shows nothing in
  # `git status --short` while it sits between conflicts.
  gitdir=$(git -C "$path" rev-parse --absolute-git-dir 2>/dev/null || true)
  busy=""
  for marker in rebase-merge rebase-apply MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD BISECT_LOG; do
    [ -n "$gitdir" ] && [ -e "$gitdir/$marker" ] && busy="$marker"
  done
  if [ -n "$busy" ]; then
    echo "SKIP $id: bead closed but an operation is IN PROGRESS ($busy) — finish or abort it first" >&2
    rc=1
    continue
  fi

  # THE guard a clean tree does not give you: are these commits anywhere else?
  branch=$(git -C "$path" symbolic-ref --quiet --short HEAD 2>/dev/null || true)
  if [ -z "$branch" ]; then
    echo "SKIP $id: bead closed but HEAD is DETACHED — no branch holds these commits:" >&2
    git -C "$path" log --oneline -3 HEAD >&2 2>/dev/null
    rc=1
    continue
  fi
  # Containment is proven against ANY remote ref, not the same-named one.
  # A task branch is never pushed (computenet-zmso): 5c merges it into the
  # feature branch from the local ref, so `origin/task/<id>` does not exist
  # and a by-name test reports "these commits exist only here" for every task
  # worktree — false, and permanently (computenet-13kh). What is true is that
  # the commits live in origin/<feature-branch>. `branch -r --contains` asks
  # exactly the right question and subsumes the by-name case.
  head_sha=$(git -C "$path" rev-parse HEAD 2>/dev/null || true)
  if [ -z "$head_sha" ]; then
    echo "SKIP $id: cannot resolve HEAD — nothing removed" >&2
    rc=1
    continue
  fi
  holders=$(git -C "$path" branch -r --contains "$head_sha" 2>/dev/null | sed 's/^[ *]*//' | grep -v '\->' || true)
  if [ -z "$holders" ]; then
    echo "SKIP $id: bead closed but these commits are on NO remote ref — they exist only here:" >&2
    git -C "$path" log --oneline -5 HEAD >&2 2>/dev/null
    echo "  (checked every origin/* ref, not just origin/$branch)" >&2
    rc=1
    continue
  fi

  # Quiet for long enough? Newest mtime anywhere under the worktree, excluding
  # .git bookkeeping that a bare `git worktree list` elsewhere can touch.
  # Weakest guard — see the header: quiet is not the same as unattended.
  if [ -n "$(find "$path" -mmin "-$MIN_AGE" -not -path '*/.git/*' -print -quit 2>/dev/null)" ]; then
    echo "SKIP $id: touched within ${MIN_AGE}m — treating as a live session" >&2
    continue
  fi

  if [ "$DRY_RUN" = 1 ]; then
    echo "would remove $path (bead $id closed, tree clean, $branch on origin, quiet ${MIN_AGE}m+)"
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
