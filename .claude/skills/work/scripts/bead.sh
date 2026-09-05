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
# Exit: jq's — 1 when the id does not exist. The output is then a fully
# null-valued object, NOT nothing: `bead.sh <typo> -r '.status'` prints the
# string `null`. Read the exit code, not the text.
#
# Output over BEAD_SPILL_BYTES (default 25000) is written to a FILE and the
# path printed instead — see the SPILL note below. A caller that PIPES this
# into another command sets BEAD_SPILL_BYTES high enough to disable it: a
# scalar filter (`-r '.status'`) never spills, but `-r '.description'` will.
set -uo pipefail

id=${1:?usage: bead.sh <id> [-r] [jq-filter]}
shift
raw=""
[ "${1:-}" = "-r" ] && { raw="-r"; shift; }
filter=${1:-.}

# sed slices to the first JSON token: bd prefixes advisory lines on stderr AND
# stdout (bd-traps.md's "malformed mid-document" note is about the tail, which
# jq surfaces as a parse error rather than silence).
out=$(bd show "$id" --json 2>/dev/null \
  | sed -n '/^[[{]/,/^[]}]/p' \
  | jq $raw '(if type=="array" then .[0] else . end)
             | { id, title, issue_type, status, priority, assignee, parent,
                 labels, metadata, description, acceptance_criteria, design,
                 comment_count, created_at, updated_at,
                 dependency_ids: [ (.dependencies // [])[]
                                   | if type=="object" then (.id // .issue_id) else . end ] }
             | '"$filter")
rc=$?

# SPILL. The projection is small for a normal bead and still too big for one
# tool result on a large epic: computenet-9sm's own description is ~36KB, its
# plain `bd show` 114KB and its projection 51KB. A tool result over the
# harness's cap is elided IN THE MIDDLE with a `... [N characters truncated]
# ...` marker that sits inside prose, so the reader sees well-formed text
# before and after it and works from a partial spec (computenet-cjfd, and the
# rram -> zwju -> o5oz chain before it). Above the cap this writes the output
# to a file and prints the path instead: a Read call pages it, and nothing is
# silently missing. A SCALAR field filter (`.status`, `.parent`) is bytes and
# never spills; `-r '.description'` is description-sized and does, so a caller
# that pipes bead.sh into another command raises BEAD_SPILL_BYTES rather than
# assuming a filter is small. The count is CHARACTERS, so a non-ASCII bead
# spills a little later than its byte size suggests.
if [ "${#out}" -gt "${BEAD_SPILL_BYTES:-25000}" ]; then
  # mktemp, not a fixed name: two agents reading the same bead in a shared
  # TMPDIR would otherwise race on one path, and a `-r` spill holds raw prose
  # rather than JSON.
  f=$(mktemp "${SCRATCH:-${TMPDIR:-/tmp}}/bead-$id.XXXXXX")
  printf '%s\n' "$out" > "$f"
  echo "bead.sh: ${#out} characters exceeds one tool result; wrote $f — read it with the Read tool (it will NOT fit in a single Bash output either)."
elif [ -n "$out" ]; then
  printf '%s\n' "$out"
fi
exit $rc
