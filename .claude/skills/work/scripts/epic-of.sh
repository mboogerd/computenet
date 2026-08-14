#!/usr/bin/env bash
# Resolve a bead's effective epic: follow .parent when set, else the dotted-id
# prefix, until an epic is reached. Both overrides matter — computenet-f8tf is
# a child of computenet-wpvy with no dot, and computenet-oxv.6 has an explicit
# parent (a feature) overriding its prefix. `bd list --parent` is NOT
# transitive, so a membership scan silently answers "unparented" for exactly
# the grandchildren this walk exists to protect.
#
# Usage: epic-of.sh <bead-id>
#
# Prints the epic id, or "(unparented)". Exit 0 only for those two answers.
# Exit 1 — "(no such id: …)" or "(cycle? …)" — means UNRESOLVED, never "no
# check needed": key on the exit status, not the string.
set -uo pipefail

id=${1:?usage: epic-of.sh <bead-id>}
start=$id
n=0
while [ "$n" -lt 12 ]; do
  n=$((n+1))
  row=$(bd show "$id" --json 2>/dev/null)
  # guard EVERY hop, not just the first: a vanished ancestor must not fall
  # through to "(unparented)", i.e. to "no check needed"
  if [ -z "$(printf '%s' "$row" | jq -r '.[0].id // empty' 2>/dev/null)" ]; then
    echo "(no such id: $id)"; exit 1
  fi
  if [ "$(printf '%s' "$row" | jq -r '.[0].issue_type // empty')" = epic ]; then
    echo "$id"; exit 0
  fi
  p=$(printf '%s' "$row" | jq -r '.[0].parent // empty')
  [ -z "$p" ] && case "$id" in *.*) p="${id%.*}";; esac
  [ -z "$p" ] && { echo "(unparented)"; exit 0; }
  id="$p"
done
echo "(cycle? $start)"; exit 1
