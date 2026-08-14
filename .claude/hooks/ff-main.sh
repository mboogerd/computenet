#!/usr/bin/env bash
# Keep the MAIN checkout on the latest origin/main, so that every worktree cut
# from it — including Claude Code's own session worktrees under
# .claude/worktrees/, which are branched from local `main`'s HEAD, not from
# origin/main — starts from current code and a current .claude/skills/.
#
# Why this exists (computenet-kcu, and one level above it): nothing in the
# /work flow fast-forwards the main checkout. Step 3's `git fetch origin main`
# updates the remote-tracking ref, not the branch and not the files. Measured
# 2026-08-14: the checkout sat 44 commits behind at one point, and a routine
# firing at that moment would have run a stale `work` skill for a whole 5h
# slot, because its session worktree is cut from local main.
#
# Wired as a SessionStart hook in .claude/settings.json.
#
# SAFETY — this must never surprise a human working in that checkout, and must
# never fail a session start. It no-ops unless ALL of these hold:
#   * the main checkout is on branch `main`
#   * its working tree and index are completely clean
#   * the update is a true fast-forward (--ff-only; never a merge or rebase)
# Anything else: report and leave it alone. Always exits 0.
set -uo pipefail

# Resolve the MAIN checkout even when invoked from a worktree: --git-common-dir
# is the shared .git for every worktree, and its parent is the main checkout.
common=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
repo=$(dirname "$common")
[ -d "$repo" ] || exit 0

branch=$(git -C "$repo" symbolic-ref --short HEAD 2>/dev/null || echo "")
if [ "$branch" != "main" ]; then
  echo "ff-main: main checkout is on ${branch:-a detached HEAD}, not main — left alone."
  exit 0
fi

# Tracked changes only. An untracked scratch file is not work-in-progress the
# way a staged or modified tracked file is, and git refuses a fast-forward on
# its own if one would be clobbered — so it does not need a guard here.
dirty=$(git -C "$repo" status --porcelain --untracked-files=no 2>/dev/null)
if [ -n "$dirty" ]; then
  echo "ff-main: main checkout has staged/modified tracked files — left alone:"
  printf '%s\n' "$dirty" | sed 's/^/  /'
  echo "ff-main: commit or stash them and main will fast-forward on the next session."
  exit 0
fi

# Bound the fetch with a watchdog. This is the wake-from-sleep case: a laptop
# that fires a scheduled slot with no network. GIT_HTTP_LOW_SPEED_* is NOT
# enough — it bounds transfer rate on an established connection, not the TCP
# connect, which is where the whole delay lives. Measured against a blackholed
# remote: low-speed vars 76s, `git -c http.connectTimeout=8` 75s (not a real
# config key, silently ignored), `perl -e alarm ... exec` 75s. Only killing the
# process works: 12s. macOS has no timeout(1). The low-speed vars stay because
# they still cover a connected-but-stalled peer, and they are HTTP-only whereas
# the watchdog also bounds SSH.
fetch_out=$(mktemp)
# `set -m` puts the fetch in its own process group, so the watchdog can signal
# the WHOLE group. Without it, kill reaches `git fetch` but not the
# `git-remote-https` child it spawned, which then survives the hook until
# curl's own ~75s connect timeout.
set -m
GIT_HTTP_LOW_SPEED_LIMIT=1000 GIT_HTTP_LOW_SPEED_TIME=10 \
  git -C "$repo" fetch --quiet origin main >"$fetch_out" 2>&1 &
gp=$!
set +m
# The redirect on this subshell is LOAD-BEARING, not tidiness. Without it the
# watchdog's `sleep` inherits and holds stdout open; to a terminal or a file
# that is invisible, but to a PIPE — which is how the harness captures hook
# output — the reader blocks until the sleep expires. Measured: every session
# start, including the healthy nothing-to-do case, cost 13s piped and <1s to a
# file. That is a far larger aggregate cost than the offline case it bounds.
( sleep 8; kill -TERM -$gp 2>/dev/null; sleep 1; kill -KILL -$gp 2>/dev/null ) >/dev/null 2>&1 &
wp=$!
wait $gp 2>/dev/null; rc=$?
pkill -P $wp 2>/dev/null        # the watchdog's own sleep children
# disown BEFORE kill, or bash prints "Terminated: 15" to ITS stderr on every
# run — the subshell's own redirect cannot suppress that, it is the parent
# reporting a killed job. Both are needed: the redirect for the pipe block,
# this for the noise.
disown $wp 2>/dev/null || true
kill $wp 2>/dev/null
fetch_err=$(cat "$fetch_out"); rm -f "$fetch_out"

if [ "$rc" -eq 143 ] || [ "$rc" -eq 137 ]; then
  echo "ff-main: fetch exceeded 8s and was stopped — left alone (offline?)."
  exit 0
elif [ "$rc" -ne 0 ]; then
  echo "ff-main: fetch did not complete — left alone. git said:"
  printf '%s\n' "$fetch_err" | sed 's/^/  /'
  exit 0
fi

# "Could not determine" must not read as "already current". Without this, an
# unborn main or a remote.origin.fetch that never creates origin/main makes the
# hook exit 0 in total silence — permanently useless in exactly the way it was
# built to prevent.
if ! git -C "$repo" rev-parse --verify --quiet origin/main >/dev/null; then
  echo "ff-main: origin/main not present locally after fetch — left alone."
  exit 0
fi

# ...and the local side too. On an unborn main, `rev-list main..origin/main`
# fails and the `|| echo 0` below reads as "already current", which is the same
# silent-and-useless exit one guard up.
if ! git -C "$repo" rev-parse --verify --quiet main >/dev/null; then
  echo "ff-main: local main does not exist yet (unborn) — left alone."
  exit 0
fi

behind=$(git -C "$repo" rev-list --count main..origin/main 2>/dev/null || echo 0)
[ "${behind:-0}" -eq 0 ] && exit 0        # already current: say nothing

# Discriminate before naming a cause. A failed --ff-only means EITHER a genuine
# divergence OR something else: an untracked file the merge would clobber, a
# skip-worktree override, another agent holding index.lock. Asserting
# "diverged" for all of them sends the operator after a problem that does not
# exist and hides git's own actionable message.
if ! git -C "$repo" merge-base --is-ancestor main origin/main 2>/dev/null; then
  echo "ff-main: main has diverged from origin/main ($behind behind) — left alone, resolve by hand."
  exit 0
fi

if merge_err=$(git -C "$repo" merge --ff-only --quiet origin/main 2>&1); then
  echo "ff-main: fast-forwarded main $behind commit(s) to $(git -C "$repo" rev-parse --short main)."
else
  # A fast-forward WAS possible and still failed. Report what git said, verbatim.
  echo "ff-main: fast-forward was possible but did not apply — left alone. git said:"
  printf '%s\n' "$merge_err" | sed 's/^/  /'
fi
exit 0
