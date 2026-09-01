#!/usr/bin/env bash
# Run the sibling test of every script this branch changes under
# .claude/skills/*/scripts/. Step 4 of remediate-friction gates the PROSE
# (validate-skills.rb, the line-budget ratchet, reachability.py) and gated no
# script at all, so a script edit shipped with its suite unrun — twice in the
# 2026-08-29 drain, both caught by a dispatched reviewer rather than by the
# lane, once with the lane's OWN discrimination suite left red
# (computenet-hkjo; computenet-z9tu and computenet-y6zv are the instances).
#
# z9tu's remedy named ONE suite literally — `feedback.test.sh` — which does not
# reach a script under work/scripts/. This is the generalisation: the set of
# suites to run is DERIVED from the diff, so it cannot go stale as scripts are
# added, renamed, or moved between skills.
#
# Coverage is resolved in two steps, because the two are not the same question:
#   1. the NAME sibling — foo.sh -> foo.test.sh, foo.py -> foo.test.py.
#   2. failing that, any *.test.* in the same scripts/ directory that MENTIONS
#      the script by basename. reachability.py and recurrence-audit.py have no
#      name sibling and are both covered by feedback.test.sh; a strict name
#      rule would call the lane's own tooling untested.
# Neither found: NO-TEST, reported and non-fatal — some scripts are one-liners
# and the ratchet here is "do not ship a RED suite", not "write a suite now".
#
# Usage: sibling-tests.sh [base-ref]        (default origin/main)
# Exit 0: every suite that ran passed (or nothing changed).
#      1: at least one suite FAILED — read the output above the summary.
#      2: bad usage / not a git repo.
set -uo pipefail

base=${1:-origin/main}
root=$(git rev-parse --show-toplevel 2>/dev/null) || {
  echo "sibling-tests: not a git repository" >&2; exit 2; }
git rev-parse --verify -q "$base" >/dev/null || {
  echo "sibling-tests: no such ref: $base" >&2; exit 2; }

# Trailing /* matters: a pathspec ending at a directory name matches a FILE by
# that name, not the tree under it (AGENTS.md, computenet-fd9d). Quoted, or zsh
# expands it before git sees it (computenet-l5rc).
# bash 3.2 (the macOS system bash this repo runs under) has no `mapfile`, and
# `${#arr[@]}` on an empty array is an unbound-variable error under `set -u`.
# So: newline-delimited strings, not arrays.
# Diff the MERGE BASE against the WORKING TREE, not `$base...HEAD`: step 4 runs
# this BEFORE the commit, and a three-dot diff ignores uncommitted and staged
# edits entirely — the gate would pass on exactly the change it is meant to
# test. Untracked files need `--others` and are picked up separately below.
mb=$(git -C "$root" merge-base "$base" HEAD 2>/dev/null) || mb=$base
changed=$(
  { git -C "$root" diff --name-only --diff-filter=d "$mb" -- '.claude/skills/*/scripts/*'
    git -C "$root" ls-files --others --exclude-standard -- '.claude/skills/*/scripts/*'
  } | grep -vE '\.test\.[a-z]+$' | sort -u)

if [ -z "$changed" ]; then
  echo "sibling-tests: no script changed against $base — nothing to run"
  exit 0
fi

run_suite() {                      # <path> -> 0 pass, 1 fail
  case "$1" in
    *.py) python3 "$1" >/dev/null 2>&1 ;;
    *)    bash "$1"   >/dev/null 2>&1 ;;
  esac
}

nchanged=0; ran=0; failed=0; untested=0
while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  nchanged=$((nchanged + 1))
  f="$root/$rel"
  dir=$(dirname "$f"); basefile=$(basename "$rel"); stem=${basefile%.*}
  suites=""
  for ext in sh py; do
    [ -f "$dir/$stem.test.$ext" ] && suites="$suites$dir/$stem.test.$ext"$'\n'
  done
  if [ -z "$suites" ]; then
    # A mention in a COMMENT is not coverage. `sweep-stale-claims.sh` is named
    # in a comment by two suites that never invoke it, and a bare `grep -l`
    # reported it covered — over-reporting coverage is the one way this
    # fallback can lie (computenet-hkjo). Strip comment lines before matching.
    for cand in "$dir"/*.test.*; do
      [ -f "$cand" ] || continue
      sed 's/[[:space:]]*#.*$//' "$cand" | grep -qF "$basefile" \
        && suites="$suites$cand"$'\n'
    done
  fi
  if [ -z "$suites" ]; then
    echo "NO-TEST   $rel — no sibling suite and none mentions it"
    untested=$((untested + 1)); continue
  fi
  while IFS= read -r s; do
    [ -n "$s" ] || continue
    ran=$((ran + 1))
    if run_suite "$s"; then
      echo "PASS      $rel -> $(basename "$s")"
    else
      echo "FAIL      $rel -> $(basename "$s")  (re-run it directly to read the failures)"
      failed=$((failed + 1))
    fi
  done <<< "$suites"
done <<< "$changed"

echo "sibling-tests: $nchanged script(s) changed, $ran suite(s) run, $failed failing, $untested with no suite"
[ "$failed" -eq 0 ]
