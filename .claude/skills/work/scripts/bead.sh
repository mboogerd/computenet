#!/usr/bin/env bash
# Projected `bd show` — one bead's OWN fields, with the dependency payload
# dropped before it can reach a tool result.
#
# WHY THIS EXISTS. `bd show <id> --json` inlines the parent epic's ENTIRE
# description once PER DEPENDENCY ENTRY, so a child of a large epic is bigger
# than the epic and a bead with several dependencies is a multiple of it.
# Measured: 57KB for computenet-x9e.3 against 43KB for the epic itself
# (computenet-rram, 2026-08-19); a task read that overran a 35KB tool result
# and a feature read of ~149KB from ONE call (computenet-zwju, 2026-08-24).
# The mitigation shipped for rram was "redirect to a file", carried by hand in
# each dispatch prompt — so it held for every agent that was warned and failed
# for the two whose prompts did not carry it for the call they made. A
# projection at the call site does not depend on anyone remembering.
#
# Usage: bead.sh <id> [jq-filter]
#   Default filter is '.' — the projected object, pretty-printed.
#   bead.sh <id> '.metadata.files[]'      # any field of the projection
#   bead.sh <id> -r '.status'             # -r before the filter for raw output
#
# Emits the bead as a single OBJECT, not `bd show`'s list of one, so no `.[0]`
# unwrap is needed. Dependencies survive as bare ids under `.dependency_ids`,
# which is what callers actually use them for; if you genuinely need a
# dependency's body, read that bead.
#
# Exit: jq's — 1 when the id does not exist (the projection is empty).
set -uo pipefail

id=${1:?usage: bead.sh <id> [-r] [jq-filter]}
shift
raw=""
[ "${1:-}" = "-r" ] && { raw="-r"; shift; }
filter=${1:-.}

# sed slices to the first JSON token: bd prefixes advisory lines on stderr AND
# stdout (bd-traps.md's "malformed mid-document" note is about the tail, which
# jq surfaces as a parse error rather than silence).
bd show "$id" --json 2>/dev/null \
  | sed -n '/^[[{]/,$p' \
  | jq $raw '(if type=="array" then .[0] else . end)
             | { id, title, issue_type, status, priority, assignee, parent,
                 labels, metadata, description, acceptance_criteria, design,
                 comment_count, created_at, updated_at,
                 dependency_ids: [ (.dependencies // [])[]
                                   | if type=="object" then (.id // .issue_id) else . end ] }
             | '"$filter"
