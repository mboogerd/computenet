#!/usr/bin/env bash
# Are this bead's acceptance criteria in the acceptance_criteria FIELD, or
# only in its description prose? (computenet-k1vd)
#
# WHY. Every reviewer scores against the field — `bd show`'s ACCEPTANCE
# CRITERIA block. When the field is empty that block is not printed AT ALL, so
# a bead whose criteria live in a "## Acceptance" section of its description
# is indistinguishable, at a glance, from a bead that has no criteria. The two
# demand opposite responses: MOVE the existing text, or WRITE criteria from
# scratch. 5f route 4 prescribes the second, so an orchestrator applying it to
# the first RE-AUTHORS criteria that already exist — and the reviewer then
# certifies against a bar the orchestrator invented, marking its own paper.
# computenet-w8ee was filed exactly this way by a dispatched implementer and
# was caught only because an unrelated reviewer happened to read it.
#
# Usage:
#   acceptance-placement.sh <bead-id>...
#
# Prints one line per bead that needs attention:
#   MISPLACED <id>  — field empty, description carries acceptance prose: MOVE it
#   ABSENT    <id>  — field empty, no such prose: route 4's case, WRITE criteria
# Silent for a bead whose field is populated.
# Exit: 0 = every bead checked has its criteria in the field, 1 = at least one
# MISPLACED or ABSENT, 2 = usage, 3 = nothing could be checked (never read a 3
# as a clean bead).
set -uo pipefail

command -v jq >/dev/null || { echo "acceptance-placement: jq unusable; NOTHING was checked" >&2; exit 3; }
[ $# -gt 0 ] || { echo "usage: acceptance-placement.sh <bead-id>..." >&2; exit 2; }

# Acceptance-ish prose. Four arms, because both error directions cost: a false
# MISPLACED sends the orchestrator hunting for a section that is not there, and
# a false ABSENT re-authors criteria that already exist — the failure this
# script exists to prevent.
#
#  A  a HEADING or BOLD lead-in, trailing words allowed:
#     `## Acceptance Criteria (what to check)` is the common real shape, and
#     requiring end-of-line after the keyword missed it (found in review).
#  B  a bare lead-in with a colon: `Done when: ...`
#  C  the same mid-paragraph, after a sentence end: `... . Acceptance: ...`
#  D  a line that is only the keyword.
#
# Synonyms cover the headings live beads actually use. `Evidence that ...` is
# DELIBERATELY not one: two beads in this workspace use it for diagnosis prose,
# not criteria, and route 4 is the right answer for those.
KW='acceptance([[:space:]]+criteria)?|done[[:space:]]+when|fixed[[:space:]]+when|success[[:space:]]+criteria|definition[[:space:]]+of[[:space:]]+done'
PROSE_RE="^[[:space:]]*(#{1,6}[[:space:]]*|\*\*)($KW)|^[[:space:]]*($KW)[[:space:]]*:|[.;][[:space:]]+($KW)[[:space:]]*:|^[[:space:]]*($KW)[[:space:]]*$"

found=0
for id in "$@"; do
  row=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p') || row=
  # An unreadable bead is NOT a clean bead: say so and keep the exit honest.
  if [ -z "$(printf '%s' "$row" | jq -r '.[0].id // empty' 2>/dev/null)" ]; then
    echo "acceptance-placement: $id unreadable; NOTHING was checked for it" >&2
    found=3; continue
  fi
  # Score the bead you ASKED for. `bd show` takes several ids, and a payload
  # whose .[0] is a different bead looks like a complete read of the wrong one.
  if [ "$(printf '%s' "$row" | jq -r '.[0].id')" != "$id" ]; then
    echo "acceptance-placement: $id — payload names a different bead; NOTHING was checked for it" >&2
    found=3; continue
  fi
  acc=$(printf '%s' "$row" | jq -r '.[0].acceptance_criteria // ""' | tr -d '[:space:]')
  [ -n "$acc" ] && continue
  desc=$(printf '%s' "$row" | jq -r '.[0].description // ""')
  if printf '%s' "$desc" | grep -qiE "$PROSE_RE"; then
    echo "MISPLACED $id  — acceptance prose is in the description; MOVE it into acceptance_criteria before dispatch"
  else
    echo "ABSENT    $id  — no criteria anywhere; 5f route 4: write them, and say you did"
  fi
  [ "$found" -eq 3 ] || found=1
done
exit $found
