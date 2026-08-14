#!/usr/bin/env bash
# Attach a worktree at PATH on BRANCH, whatever state things are already in.
#
# Why this exists: `git worktree add` refuses when the path is already a
# worktree, and `-b` refuses when the branch already exists — so a *resumed*
# item fails both naive forms, which is precisely the case resumption needs to
# work. The states have to be told apart, and getting it wrong silently points
# an agent at a directory that isn't there — or, worse, at the right directory
# holding the wrong commits.
#
# The states, and what each does:
#   attached           worktree already at this path        reconcile, verify
#   local only         branch exists here, no worktree      attach it
#   remote only        origin/<branch> exists, nothing local  attach TRACKING it,
#                                                            at the remote tip
#   both, equal        attach local
#   both, local behind fast-forward local to the remote tip, then attach
#   both, local ahead  attach local (the remote tip is already contained)
#   both, diverged     FAIL LOUDLY — never silently pick one
#   neither            create from <base-ref>
#
# The remote-only state is the *normal* state of a resumed feature: features
# are pushed and carry a PR by design, so the branch outlives any local
# worktree and outlives the machine. Creating it fresh from the base ref there
# orphans the PR's commits while looking perfectly clean (computenet-aeg).
#
# Usage: ensure-worktree.sh <path> <branch> [base-ref]
#   base-ref  only used when the branch exists neither locally nor on origin
#             (default origin/main)
#
# Prints the resolved absolute path on stdout on success — and *only* that, so
# callers can capture it directly; narrates which state it took on stderr, and
# ends that narration with the branch's resolved BASE commit, which a dispatch
# can quote rather than describing the base from memory. Exits non-zero,
# loudly, if the worktree can't be produced on the requested branch containing
# the remote's commits — never fail silently here.
set -euo pipefail

WORKTREE="${1:?usage: ensure-worktree.sh <path> <branch> [base-ref]}"
BRANCH="${2:?usage: ensure-worktree.sh <path> <branch> [base-ref]}"
BASE="${3:-origin/main}"

note() { echo "ensure-worktree: $*" >&2; }

mkdir -p "$(dirname "$WORKTREE")"
# Absolute path: a relative one resolves differently inside a subagent's own
# worktree, which is how an agent ends up working in the main checkout.
# `pwd -P`, not `pwd`: git reports worktree paths with symlinks resolved, so a
# logical path fails the "already attached" comparison below and the script
# then tries to re-add an existing worktree. (No-op under the real
# computenet-worktrees path; it bites under a symlinked TMPDIR.)
abs=$(cd "$(dirname "$WORKTREE")" && pwd -P)/$(basename "$WORKTREE")

# Refresh this branch's remote-tracking ref before deciding anything. Offline,
# or no such branch on origin, is a legitimate answer — not an error.
remote_ref="refs/remotes/origin/$BRANCH"
git fetch origin "+refs/heads/$BRANCH:$remote_ref" --quiet 2>/dev/null || true

local_sha=$(git rev-parse --verify --quiet "refs/heads/$BRANCH" || true)
remote_sha=$(git rev-parse --verify --quiet "$remote_ref" || true)

# Divergence is decided before anything is touched, so a diverged branch is
# never half-attached.
if [ -n "$local_sha" ] && [ -n "$remote_sha" ] && [ "$local_sha" != "$remote_sha" ]; then
  if git merge-base --is-ancestor "$local_sha" "$remote_sha"; then
    note "local $BRANCH ($(git rev-parse --short "$local_sha")) is behind origin/$BRANCH ($(git rev-parse --short "$remote_sha")); fast-forwarding"
  elif git merge-base --is-ancestor "$remote_sha" "$local_sha"; then
    note "local $BRANCH ($(git rev-parse --short "$local_sha")) is ahead of origin/$BRANCH ($(git rev-parse --short "$remote_sha")); keeping local"
  else
    cat >&2 <<EOF
ensure-worktree: '$BRANCH' has DIVERGED from 'origin/$BRANCH' — refusing to guess.
  local  $local_sha
  remote $remote_sha
  merge base $(git merge-base "$local_sha" "$remote_sha" 2>/dev/null || echo '(none)')
Picking either one silently discards commits. Decide deliberately, e.g.
  git log --oneline --left-right $local_sha...$remote_sha
then reconcile (merge/rebase the local branch onto origin/$BRANCH, or reset it
to the remote if the local commits are known-dead) and re-run this script.
EOF
    exit 1
  fi
fi

if git worktree list --porcelain | grep -qx "worktree $abs"; then
  note "worktree $abs already attached"
elif [ -n "$local_sha" ]; then
  note "attaching $abs on existing local branch $BRANCH"
  git worktree add "$abs" "$BRANCH" >/dev/null
elif [ -n "$remote_sha" ]; then
  # THE case this script got wrong: remote-only. Track it, at the remote tip.
  note "attaching $abs on origin/$BRANCH (remote-only branch, at its tip)"
  git worktree add --track -b "$BRANCH" "$abs" "$remote_ref" >/dev/null
else
  note "creating branch $BRANCH from $BASE (exists neither locally nor on origin)"
  git fetch origin main --quiet 2>/dev/null || true
  git worktree add "$abs" -b "$BRANCH" "$BASE" >/dev/null
fi

on=$(git -C "$abs" rev-parse --abbrev-ref HEAD)
if [ "$on" != "$BRANCH" ]; then
  echo "ensure-worktree: worktree $abs is on '$on', expected '$BRANCH'" >&2
  exit 1
fi

if [ -n "$remote_sha" ]; then
  # Covers the already-attached-but-stale case as well as the fast-forward
  # decided above. --ff-only so this can never invent a merge commit; a dirty
  # or unexpectedly-rewound worktree fails here rather than proceeding.
  if ! git -C "$abs" merge-base --is-ancestor "$remote_sha" HEAD; then
    if ! git -C "$abs" merge --ff-only "$remote_ref" >/dev/null 2>&1; then
      echo "ensure-worktree: $abs on '$BRANCH' cannot fast-forward to origin/$BRANCH ($remote_sha)" >&2
      echo "  HEAD is $(git -C "$abs" rev-parse HEAD). Resolve by hand; refusing to leave the PR's commits out of the worktree." >&2
      exit 1
    fi
  fi
  git -C "$abs" branch --set-upstream-to="origin/$BRANCH" "$BRANCH" >/dev/null 2>&1 || true

  # Final guard, and the whole point of this script: whatever origin has for
  # this branch is reachable from the worktree's HEAD.
  if ! git -C "$abs" merge-base --is-ancestor "$remote_sha" HEAD; then
    echo "ensure-worktree: $abs does not contain origin/$BRANCH ($remote_sha) — refusing to hand it over" >&2
    exit 1
  fi
fi

# Report the branch's BASE — the commit it is cut from — as an observed fact,
# so a dispatch can quote it instead of reconstructing it from the session's
# ordering (computenet-88v: an orchestrator told an agent that landed code was
# absent, having reasoned from the order it picked the features).
#
# Deliberately NOT the worktree's HEAD: on a *resumed* branch HEAD is the last
# work commit, and a work commit quoted as a base is the other half of the same
# bug — a reviewer handed one diffed against it, got an empty range, and nearly
# reported that the branch changed nothing. `merge-base <base-ref> HEAD` is
# right on every path: on the create path it *is* the base ref's commit, and on
# a resumed branch it is where the branch left its base, whatever work sits on
# top. Informational only — a base that cannot be resolved says so and never
# fails the attach.
base_sha=$(git -C "$abs" merge-base "$BASE" HEAD 2>/dev/null || true)
if [ -n "$base_sha" ]; then
  note "base commit (this branch is cut FROM it; it is NOT a diff baseline): $(git -C "$abs" log --oneline -1 "$base_sha" 2>/dev/null)"
  head_sha=$(git -C "$abs" rev-parse HEAD)
  if [ "$head_sha" != "$base_sha" ]; then
    note "worktree HEAD is ahead of that base — prior work on the branch: $(git -C "$abs" log --oneline -1 HEAD 2>/dev/null)"
  fi
else
  note "base commit: could not resolve '$BASE' from $abs — establish it from an observed ref before describing it to anyone"
fi

echo "$abs"
