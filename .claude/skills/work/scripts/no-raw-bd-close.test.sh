#!/usr/bin/env bash
# Lint: no new raw `bd close` call site outside close-bead.sh. Every close in
# this skill (computenet-85pvz) routes through close-bead.sh so the undefer
# sweep runs at close time, not only at step 3's next startup check; a
# hand-written `bd close` call site bypasses that silently.
#
# Scoped to exactly scripts/*.sh (excluding *.test.sh and close-bead.sh
# itself, which legitimately invokes `bd close`) and SKILL.md's prose — never
# the wider repo. references/recovery.md's collision-handling text and
# references/review.md's "never ... bd close" prohibition are deliberately
# out of scope: they mention the phrase without being a close call site, and
# are not among the files this ticket converted.
#
# Two kinds of line are excluded as non-sites, not matches of the lint:
#   - a `#`-comment line (reclaim-worktrees.sh, sweep-merged-prs.sh both
#     mention "bd close" only in prose comments);
#   - a line where "bd close" appears inside an `echo "..."` MESSAGE a script
#     prints for a human (merge-task.sh's "bd close $task FAILED" wording) —
#     that is text about a call site, not a call site.
#
# Usage: no-raw-bd-close.test.sh
# Exits 0 if no raw site is found, 1 otherwise, naming each offending line.
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SKILL="$HERE/../SKILL.md"

fails=0
check() {
  local f="$1" out
  out=$(grep -nE 'bd close' "$f" 2>/dev/null \
          | grep -vE '^[0-9]+:[[:space:]]*#' \
          | grep -vE 'echo "')
  [ -z "$out" ] && return 0
  while IFS= read -r hit; do
    echo "  FAIL $f:$hit -- raw \`bd close\`, not routed through close-bead.sh"
    fails=$((fails + 1))
  done <<<"$out"
}

for f in "$HERE"/*.sh; do
  base=$(basename "$f")
  case "$base" in
    *.test.sh|close-bead.sh) continue ;;
  esac
  check "$f"
done
check "$SKILL"

if [ "$fails" -eq 0 ]; then
  echo "  PASS no raw bd close sites outside close-bead.sh"
  echo "1 passed, 0 failed"
  exit 0
fi
echo "0 passed, $fails failed"
exit 1
