#!/usr/bin/env bash
# Warn when a bead's description or acceptance names a file that its
# `metadata.files` claim does not cover (computenet-hpco).
#
# WHY. The claim is what bounds an implementer: "stay inside your
# metadata.files claim" is in every dispatch prompt. A bead whose own text
# demands a file the claim omits is unsatisfiable from the moment it is
# written, and the implementer discovers it in its first ten minutes — then
# has to choose between stalling and working outside the claim. Neither is a
# choice it should be making. computenet-yh6.1.12 shipped with five files
# outside its claim, each forced by the bead's own acceptance.
#
# WHY A WARNING. Path-shaped strings in prose are a heuristic: a bead may
# legitimately mention a file it only READS, and the claim covers files the
# text never names. Blocking on that would train people to skip it. This runs
# in a second and prints what to look at.
#
# Usage:
#   check-files-claim.sh <bead-id>...     # one or more ids
# Exit: 0 = nothing to report (or nothing checkable), 1 = at least one bead
# names a path its claim omits. Prints each (bead, path) pair.
set -uo pipefail

command -v jq >/dev/null || { echo "check-files-claim: jq missing, skipping" >&2; exit 0; }
[ $# -gt 0 ] || { echo "usage: check-files-claim.sh <bead-id>..." >&2; exit 2; }

# Does the claim cover this path? A claim entry is a file, a DIRECTORY
# (`.github/workflows`) or a glob (`kernel/src/.../evolve/**`) — all three are
# in live beads, so an entry covers every path beneath it. The substring arm
# keeps the check lenient the other way: prose abbreviates paths, and an entry
# that spells one out in full still counts as naming it.
covered() { # path (reads $claim_entries)
  local path=$1 entry
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    entry=${entry%/}; entry=${entry%/\*\*}; entry=${entry%/\*}; entry=${entry%/}
    [ -n "$entry" ] || continue
    case "$path" in "$entry"|"$entry"/*) return 0 ;; esac
    case "$entry" in *"$path"*) return 0 ;; esac
  done <<<"$claim_entries"
  return 1
}

found=0
for id in "$@"; do
  # No `|| continue` here: with pipefail a failing `bd` would take the whole
  # pipeline down silently, and a mistyped id would read as a clean pass.
  json=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p')
  [ -n "$json" ] || { echo "check-files-claim: $id unreadable, skipping" >&2; continue; }

  # `bd show --json` returns a LIST; metadata.files is a comma-separated string
  # (a few beads store a JSON array — jq -r renders it one quoted path a line,
  # which the split below handles as well).
  text=$(printf '%s' "$json" | jq -r '.[0] | ((.description // "") + " " + (.acceptance_criteria // ""))')
  claim=$(printf '%s' "$json" | jq -r '.[0].metadata.files // ""')
  claim_entries=$(printf '%s' "$claim" | tr ',' '\n' | tr -d '"[] ' | sed '/^$/d')

  # Path-shaped: a slash-bearing token ending in a known source/doc extension.
  # Anchored on the extension so prose like "kernel/src" or a bare module name
  # does not flood the output.
  #
  # A token whose DIRECTORY component carries a source-file extension is prose
  # shorthand, not a path: `Graphs.kt/Deltas.kt` is how a breakdown writes
  # "Graphs.kt and Deltas.kt", and no directory in this repo is named
  # `Graphs.kt`. Dropping those cannot suppress a genuine claim gap, because a
  # real path's parent directories do not carry source-file extensions — and
  # it removed half the false positives on the feature that prompted this
  # (0 of 4 warnings were true; a check whose output is reliably all-false
  # trains the reader to skim it — computenet-0pd6).
  mentioned=$(printf '%s' "$text" \
    | grep -oE '[A-Za-z0-9_./-]+/[A-Za-z0-9_.-]+\.(kt|kts|java|py|rb|sh|md|yaml|yml|json|gradle)' \
    | awk -F/ '{
        for (i = 1; i < NF; i++)
          if ($i ~ /\.(kt|kts|java|py|rb|sh|md|yaml|yml|json|gradle)$/) next
        print
      }' \
    | sort -u)
  [ -n "$mentioned" ] || continue

  while IFS= read -r path; do
    covered "$path" || { echo "$id: names $path, not in metadata.files"; found=1; }
  done <<<"$mentioned"
done
exit $found
