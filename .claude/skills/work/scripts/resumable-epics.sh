#!/usr/bin/env bash
# Epics that hold a half-built feature — a feature left `in_progress` by a
# session that stopped mid-flight. Step 3's epic selection ranks these above
# priority: the branch, worktree and (usually) green draft PR beneath such a
# feature are the most expensive work in the queue and the only work in it
# that decays, and the feature itself is invisible to `bd ready`, which lists
# ready work rather than work already in flight.
#
# Usage: resumable-epics.sh
# Prints a JSON array of epic ids (possibly empty) on stdout.
#
# Resolution is epic-of.sh's walk, not the dotted prefix: a feature can carry
# an explicit `parent` that overrides its id (see that script's header). A
# feature whose epic will not resolve is SKIPPED with a note on stderr — it
# cannot be ranked, and guessing its epic would rank the wrong one.
set -uo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

features=$(bd list --type=feature --status=in_progress --limit 0 --json 2>/dev/null \
             | sed -n '/^[[{]/,/^[]}]/p' \
             | jq -r '(if type=="array" then . else (.issues // []) end)[].id' 2>/dev/null)

epics=""
for f in $features; do
  if e=$("$DIR/epic-of.sh" "$f" 2>/dev/null) && [ "$e" != "(unparented)" ]; then
    epics="$epics$e
"
  else
    echo "resumable-epics: skipped $f — epic unresolved ($e)" >&2
  fi
done

printf '%s' "$epics" | jq -R -s 'split("\n") | map(select(length > 0)) | unique'
