#!/usr/bin/env bash
# Close beads whose PR already merged, and take their worktrees off disk.
#
# Why this exists: auto-merge lands a PR minutes AFTER the session that opened
# it has ended, so no session ever observes its own merge. Every reconciliation
# site in SKILL.md is preconditioned — Finalize's watch covers only PRs marked
# ready THIS session, and 5a's resume re-check covers only features inside the
# claimed epic. A feature under an epic nobody re-claims is reconciled by
# nobody, forever; `bd ready` hides in_progress, so it is invisible to
# selection too, and the worktree stays because removal is gated on the close.
# Measured 2026-08-14: four features in_progress behind PRs merged 12-20h
# earlier, all four worktrees still on disk (computenet-wpvy.25).
#
# So this sweep is deliberately UNCONDITIONAL: every non-closed bead carrying
# metadata.pr, whatever epic it sits under and whatever else its metadata says.
# Cost is one `gh` call per session.
#
# THIS SCRIPT NEVER REPORTS CLEAN ON A FAILURE. It closes beads and deletes
# worktrees, and its whole purpose is catching silent drift — so a tracker it
# cannot reach, a bead it cannot close, or a worktree it cannot remove is
# announced and reflected in the exit code, never swallowed. An earlier draft
# used `bd list ... || echo '[]'` and printed "no beads behind a merged PR /
# exit 0" against a broken `bd`, which is the exact failure mode it exists to
# prevent (review of PR #158).
#
# Usage: sweep-merged-prs.sh [--dry-run] [--limit N]
#   --dry-run  print what would be closed/removed, change nothing
#   --limit    how many merged PRs to fetch (default 500; the repo passed 150
#              on 2026-08-14, and gh's own default of 30 would silently miss
#              every older one). gh returns newest first, so truncation drops
#              the oldest merges.
# Exit: 0 = swept cleanly; 1 = an item failed (details on stderr); 2 = bad
#       usage; 3 = a precondition failed (gh, bd or jq unusable).
set -uo pipefail

DRY_RUN=0
LIMIT=500
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --limit) LIMIT="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "sweep-merged-prs: $*" >&2; exit 3; }

# Which repo's PR numbers are ours. A bead's metadata.pr is a full url, and an
# unanchored /pull/<n> match will happily join someone else's #106 to our
# merged #106 and close the bead. Observed in review of PR #158 with a fixture
# carrying https://github.com/someoneelse/otherproj/pull/106. Derive the
# owner/repo from the checkout rather than hardcoding, so a fork stays correct.
origin=$(git remote get-url origin 2>/dev/null) || die "no git remote 'origin' here"
slug=$(printf '%s' "$origin" \
  | sed -E 's#^git@[^:]+:#https://host/#; s#\.git$##' \
  | sed -E 's#^.*://[^/]+/##')
[ -n "$slug" ] || die "cannot derive owner/repo from origin url: $origin"

# The one network call.  number+headRefName covers both joins below.
merged=$(gh pr list --state merged --limit "$LIMIT" --json number,headRefName) \
  || die "gh pr list failed — cannot tell which PRs merged; refusing to report a clean sweep"

# --limit 0 = unlimited; bd's default of 50 would silently strand the rest.
# No --status filter: `bd list` already excludes closed, and open / in_progress
# / blocked / deferred all need looking at. --has-metadata-key does the join
# key selection server-side. NO `|| echo '[]'` here, deliberately — see header.
withpr=$(bd list --has-metadata-key pr --limit 0 --json) \
  || die "bd list (pr) failed — the tracker is unreachable; refusing to report a clean sweep"
withbranch=$(bd list --has-metadata-key branch --limit 0 --json) \
  || die "bd list (branch) failed — the tracker is unreachable; refusing to report a clean sweep"

# Two joins, deliberately unequal in authority:
#  - metadata.pr is the PR *this bead's session opened for this bead*. A MERGED
#    state there means this bead's change landed -> safe to close.
#  - metadata.branch alone is weaker: a branch can merge while its bead stays
#    legitimately open. Real case, 2026-08-14: computenet-dqy.2 is deferred to
#    2026-08-25 awaiting a CI capture, and its branch merged as PR #27 titled
#    "...the 5-minute hang stays undiagnosed". Closing that would delete live
#    work. So a branch-only match is REPORTED, never closed.
# A deferred bead is likewise reported, not closed — deferral is a deliberate
# park and a merged PR does not revoke it.
# An EMPTY metadata.pr counts as absent on both sides. SKILL.md's squash-resume
# path writes `--set-metadata pr=` on purpose, and "" is neither a joinable url
# nor `null`, so testing `== null` alone made those beads invisible to both
# joins at once.
plan=$(jq -n \
  --arg slug "$slug" \
  --argjson merged "$merged" \
  --argjson withpr "$withpr" \
  --argjson withbranch "$withbranch" '
  ($merged | map(.number | tostring)) as $nums
  | ($merged | map(.headRefName)) as $refs
  | ("^https?://[^/]+/" + ($slug | gsub("\\."; "\\.")) + "/pull/(?<n>[0-9]+)") as $pat
  | [ $withpr[]
      | select((.metadata.pr // "") != "")
      | select([.metadata.pr | capture($pat) | .n] as $n
               | ($n | length) > 0 and (($nums | index($n[0])) != null))
      | {id, worktree: (.metadata.worktree // "-"),
         branch: (.metadata.branch // "-"),
         action: (if .status == "deferred" then "report" else "close" end),
         why: (if .status == "deferred"
               then "its PR merged, but the bead is deferred on purpose"
               else "metadata.pr merged" end)} ]
  + [ $withbranch[]
      | select((.metadata.pr // "") == "")
      | select(.metadata.branch as $b | $refs | index($b))
      | {id, worktree: (.metadata.worktree // "-"),
         branch: (.metadata.branch // "-"), action: "report",
         why: "its branch merged, but the bead carries no metadata.pr"} ]
  | unique_by(.id)') || die "jq join failed"

count=$(jq length <<<"$plan") || die "jq could not read its own plan"
if [ "$count" -eq 0 ]; then
  echo "no beads behind a merged PR"
  exit 0
fi

closed=0
failed=0
while IFS=$'\t' read -r id action worktree branch why; do
  [ -n "$id" ] || continue
  if [ "$action" = "report" ]; then
    echo "review by hand (NOT closed): $id — $why"
    continue
  fi

  # Per-item failures are non-fatal, always. `set -e` here meant one bad
  # `bd close` aborted the run after the first bead and left every later
  # merged bead unreconciled, with no summary and no count (review of #158) —
  # the opposite of a backstop.
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "would close: $id — $why"
  elif bd close "$id" >/dev/null 2>&1; then
    echo "closed: $id — $why"
    closed=$((closed + 1))
  else
    echo "FAILED to close: $id — $why (left for the next sweep)" >&2
    failed=$((failed + 1))
    continue          # never remove a worktree whose bead did not close
  fi

  # Worktree removal is a consequence of the close, never independent of it.
  # Three gates, all required: the path resolves on THIS machine (metadata
  # routinely carries the other machine's paths, e.g. /Users/MerlijnB/...),
  # git still knows it as a worktree, and it is clean. Dirty means uncommitted
  # work nobody has looked at — leave it and say so.
  [ "$worktree" != "-" ] || continue
  if ! wt=$(cd "$worktree" 2>/dev/null && pwd -P); then
    echo "  worktree not on this machine: $worktree"
    continue
  fi
  if ! git worktree list --porcelain | grep -qxF "worktree $wt"; then
    echo "  not a registered worktree, left alone: $wt"
    continue
  fi
  if [ -n "$(git -C "$wt" status --short)" ]; then
    echo "  DIRTY, left in place: $wt"
    continue
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "  would remove worktree: $wt"
    [ "$branch" = "-" ] || echo "  would delete local branch: $branch"
    continue
  fi
  if git worktree remove "$wt" 2>/dev/null; then
    echo "  removed worktree: $wt"
  else
    echo "  FAILED to remove worktree: $wt" >&2
    failed=$((failed + 1))
    continue
  fi
  # SKILL.md's removal sweep says a closed feature's local branch comes off
  # too. -D not -d: the repo squash-merges, so a fully-landed branch is never
  # an ancestor of main and -d refuses it. The merge is already proven by the
  # PR state that got us here.
  if [ "$branch" != "-" ] && git show-ref --quiet --verify "refs/heads/$branch"; then
    if git branch -D "$branch" >/dev/null 2>&1; then
      echo "  deleted local branch: $branch"
    else
      echo "  FAILED to delete local branch: $branch" >&2
      failed=$((failed + 1))
    fi
  fi
done < <(jq -r '.[] | [.id, .action, .worktree, .branch, .why] | @tsv' <<<"$plan")

# Closes are local writes like everything else in this flow. They reach the
# other machine at the session's Finalize push (SKILL.md step 6); this script
# does not sync, and nothing is scheduled to do it for us
# (doc/ops/beads-sync-runbook.md section 5).
if [ "$DRY_RUN" -eq 1 ]; then
  echo "dry run: nothing changed"
else
  echo "closed $closed bead(s) behind merged PRs"
fi
if [ "$failed" -gt 0 ]; then
  echo "$failed item(s) FAILED — report them; they are not reconciled" >&2
  exit 1
fi
