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

found=0
for id in "$@"; do
  json=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p') || continue
  [ -n "$json" ] || { echo "check-files-claim: $id unreadable, skipping" >&2; continue; }

  # `bd show --json` returns a LIST; metadata.files is a comma-separated string.
  text=$(printf '%s' "$json" | jq -r '.[0] | ((.description // "") + " " + (.acceptance_criteria // ""))')
  claim=$(printf '%s' "$json" | jq -r '.[0].metadata.files // ""')

  # Path-shaped: a slash-bearing token ending in a known source/doc extension.
  # Anchored on the extension so prose like "kernel/src" or a bare module name
  # does not flood the output.
  mentioned=$(printf '%s' "$text" \
    | grep -oE '[A-Za-z0-9_./-]+/[A-Za-z0-9_.-]+\.(kt|kts|java|py|rb|sh|md|yaml|yml|json|gradle)' \
    | sort -u)
  [ -n "$mentioned" ] || continue

  while IFS= read -r path; do
    case ",$claim," in
      *"$path"*) ;;
      *) echo "$id: names $path, not in metadata.files"; found=1 ;;
    esac
  done <<<"$mentioned"
done
exit $found
