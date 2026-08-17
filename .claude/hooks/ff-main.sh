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
#
# Not decided yet, only recorded. A dirty tree is USUALLY a human mid-edit and
# this hook must leave it alone — but it is also what THIS HOOK leaves behind
# when its own fast-forward is killed mid-checkout, and that state latches:
# the tree never gets cleaner on its own, so every later session reads the
# wreckage as human work and skips the fast-forward forever. Observed
# 2026-08-15: the checkout sat 8 commits behind for a day, with 29 modified
# files and 7 untracked ones that were all byte-identical to origin/main.
# Telling the two apart needs origin/main, which needs the fetch below, so the
# verdict is deferred to heal_or_bail() after it.
dirty=$(git -C "$repo" status --porcelain --untracked-files=no 2>/dev/null)

# Why a fast-forward can be cut in the first place: `git merge --ff-only`
# writes the working tree, THEN the index, THEN moves HEAD. The SessionStart
# harness timeout is 30s and the fetch watchdog below may legitimately spend
# 16s of it, so a large checkout on a loaded machine can be killed after the
# files are on disk but before HEAD moves. What is left is exactly: tracked
# files whose content already equals origin/main, untracked files that already
# exist in origin/main byte-for-byte, and a HEAD that never moved.
#
# heal_or_bail() recognises precisely that state and nothing else. Every dirty
# path must be provably redundant with origin/main before a single one is
# touched; one path that is not sends the whole thing down the bail branch
# with the tree untouched. Deleting work is far worse than skipping a
# fast-forward, so the check is per-path and the failure mode is "do nothing".
heal_or_bail() {
  local unsafe="" path status_line
  # `git status --porcelain -z` and NUL-splitting, because a path may contain
  # spaces or quotes; the human-readable form would mangle it and the mangled
  # name would then miss its own safety check.
  while IFS= read -r -d '' status_line; do
    path=${status_line:3}
    case ${status_line:0:2} in
      ' M')
        # Modified in the worktree only (index clean). Safe iff the worktree
        # content is already what origin/main holds — i.e. the "modification"
        # is the merge's own output, not an edit.
        git -C "$repo" diff --quiet origin/main -- "$path" 2>/dev/null || unsafe="$unsafe $path" ;;
      ' D')
        # Deleted in the worktree only. Safe iff origin/main still carries the
        # file: restoring it from HEAD then cannot lose anything. (A checkout
        # cut mid-flight leaves these when it had started removing a path.)
        git -C "$repo" cat-file -e "origin/main:$path" 2>/dev/null || unsafe="$unsafe $path" ;;
      *)
        # Anything staged, renamed, conflicted or unmerged is real work or a
        # real problem. Never guess.
        unsafe="$unsafe $path" ;;
    esac
  done < <(git -C "$repo" status --porcelain -z --untracked-files=no 2>/dev/null)

  if [ -n "$unsafe" ]; then
    echo "ff-main: main checkout has staged/modified tracked files — left alone:"
    printf '%s\n' "$dirty" | sed 's/^/  /'
    echo "ff-main: commit or stash them and main will fast-forward on the next session."
    return 1
  fi

  # Every dirty tracked path is redundant with origin/main. Restore them from
  # HEAD; the fast-forward re-applies the same content immediately after.
  if ! git -C "$repo" checkout -- . 2>/dev/null; then
    echo "ff-main: tried to clear a half-applied fast-forward and could not — left alone."
    return 1
  fi

  # Untracked leftovers block the fast-forward ("would be overwritten by
  # merge"). Remove ONLY those already byte-identical to origin/main's copy,
  # which the merge is about to write back anyway. An untracked file that is
  # NOT in origin/main, or differs from it, is somebody's scratch work and is
  # left where it is — git will then refuse the merge and say so, which is the
  # correct outcome.
  local u
  while IFS= read -r -d '' u; do
    if git -C "$repo" cat-file -p "origin/main:$u" 2>/dev/null | cmp -s - "$repo/$u"; then
      rm -f "$repo/$u"
    fi
  done < <(git -C "$repo" ls-files --others --exclude-standard -z 2>/dev/null)

  echo "ff-main: cleared a half-applied fast-forward (every dirty path already matched origin/main)."
  return 0
}

# Bound the fetch with a watchdog. This is the wake-from-sleep case: a laptop
# that fires a scheduled slot with no network. GIT_HTTP_LOW_SPEED_* is NOT
# enough — it bounds transfer rate on an established connection, not the TCP
# connect, which is where the whole delay lives. Measured against a blackholed
# remote: low-speed vars 76s, `git -c http.connectTimeout=8` 75s (not a real
# config key, silently ignored), `perl -e alarm ... exec` 75s. Only killing the
# process works. macOS has no timeout(1). The low-speed vars stay because
# they still cover a connected-but-stalled peer, and they are HTTP-only whereas
# the watchdog also bounds SSH.
#
# FUSE, and why 15. Only two fuse lengths have ever been measured, under 16
# concurrent busy loops against a fast local remote (computenet-wpvy.39):
#
#   8s  -> 2 of 3 HEALTHY fetches cut
#   15s -> 1 of 3 HEALTHY fetches cut
#
# 12s was never measured; it was a midpoint picked in PR #144 when the fuse was
# walked back from 8s. Cutting a healthy fetch is the failure this fuse trades
# against, and 15s is the better of the two points actually measured, so the
# fuse moves to the measured value rather than staying on the inherited guess.
#
# The budget allows it. The SessionStart hook's harness timeout is 30s; worst
# case here is the fuse plus the 1s TERM->KILL grace, so 16s, leaving 14s. The
# cost is 3s more before a genuinely offline laptop gives up, once per session
# start — cheap against a wrongly cut fetch, which skips a fast-forward and is
# exactly what saturation produces.
# Overridable ONLY so the test harness can align the fuse with a stubbed
# fetch's duration (computenet-od2q's cut-after-ref-update case is otherwise
# untestable in under 15s). Nothing in the real flow sets it.
fuse=${FF_MAIN_FUSE:-15}

# One private directory per invocation, holding both the captured fetch output
# and the watchdog's kill flag. `mktemp -d` is what makes the flag path unique:
# a fixed path would let a flag left behind by an earlier (or a concurrent)
# invocation make a perfectly successful fetch report as a timeout. The trap
# removes it on every exit path, of which this script has many.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fetch_out="$work/fetch.out"
killed_flag="$work/killed"
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
# The flag is written BEFORE the signal, and it is what classifies the outcome
# below — never the exit code. A group-kill leaves git with whatever status the
# race between its own processes produces (143 and 137 are only the tidy
# cases), so rc cannot tell "we cut it" from "the remote is broken". The flag
# can, because only this watchdog writes it. Ordering matters: write first, so
# the parent's `wait` can never return and read an absent flag for a kill that
# has already been decided.
#
# `sleep "$fuse" || exit 0` is the other half, and it is not decoration. On the
# HEALTHY path the parent tears this watchdog down with `pkill -P` (below),
# which kills the sleep — and a bare `sleep "$fuse";` would then simply fall
# through to the next command and write the flag, making every successful fetch
# report as a timeout. Guarding on the sleep's own status distinguishes "the
# fuse burned down" from "we were dismissed", independently of teardown order.
( trap - EXIT; sleep "$fuse" || exit 0
  : >"$killed_flag"; kill -TERM -$gp 2>/dev/null; sleep 1; kill -KILL -$gp 2>/dev/null ) >/dev/null 2>&1 &
wp=$!
wait $gp 2>/dev/null; rc=$?
pkill -P $wp 2>/dev/null        # the watchdog's own sleep children
# disown BEFORE kill, or bash prints "Terminated: 15" to ITS stderr on every
# run — the subshell's own redirect cannot suppress that, it is the parent
# reporting a killed job. Both are needed: the redirect for the pipe block,
# this for the noise.
disown $wp 2>/dev/null || true
kill $wp 2>/dev/null
fetch_err=$(cat "$fetch_out" 2>/dev/null || true)

# BOTH conditions, and the rc one is not redundant. The watchdog can fire in the
# window between git's SUCCESSFUL exit and the parent's teardown below, so the
# flag alone would report a timeout for a fetch that finished — skipping the
# fast-forward this hook exists to do, and saying something false about it while
# doing so. rc == 0 is the one status that is unambiguous: git only exits 0 when
# it completed, whatever we signalled afterwards. So a zero rc always wins and
# the fast-forward proceeds; the flag arbitrates only among the failures, which
# is the ambiguity it was introduced to resolve.
if [ -e "$killed_flag" ] && [ "$rc" -ne 0 ]; then
  # WE cut it. Whatever git printed on the way down is an artifact of our own
  # signal — "died of signal 15", "possible repository corruption on the remote
  # side" — and repeating it would hand the operator a scary diagnosis we
  # manufactured. Under load this is often a HEALTHY fetch that was merely slow.
  echo "ff-main: fetch exceeded ${fuse}s and was stopped (offline or heavily loaded?)."
  # ...but DO NOT give up here. The cut can land in the window AFTER the ref
  # update and before git exits, so origin/main may already have advanced —
  # and treating a stopped fetch as "produced nothing" discards a
  # fast-forward that was available, leaving the checkout stale for the whole
  # slot for no reason (computenet-od2q, observed once on a real-git harness).
  # Falling through is safe rather than optimistic: every guard below is
  # unchanged and each one independently refuses to act on a ref that is not
  # there, not an ancestor, or not fast-forwardable. If nothing advanced, they
  # simply report "already current".
  #
  # Distinct from computenet-wpvy.39, which was a cut fetch MISCLASSIFIED as
  # remote corruption. That classification is correct and stays; this is the
  # other half — a correctly-classified cut still throwing away a completed
  # ref update.
  echo "ff-main: checking whether the ref advanced before the cut anyway."
elif [ "$rc" -ne 0 ]; then
  # git failed on its own account. This text is genuinely git's.
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

# The deferred verdict from the dirty check at the top. It runs HERE because it
# needs origin/main (fetched above) and because there is no point healing a
# checkout that is already current — the `behind` guard above has exited by
# then, leaving a human's dirty tree untouched, which is what we want.
if [ -n "$dirty" ] && ! heal_or_bail; then
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
