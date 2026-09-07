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
# Usage: bead.sh [-C <dir>] <id> [-r] [jq-filter]
#   Default filter is '.' — the projected object, pretty-printed.
#   bead.sh <id> '.metadata.files[]'      # any field of the projection
#   bead.sh <id> -r '.status'             # -r before the filter for raw output
#   bead.sh -C <main-checkout> <id>       # from a worktree: only the main
#                                         # checkout holds the beads database
#
# -C IS FORWARDED TO bd, NOT TO jq. It is accepted before or after the id, so
# both orders a dispatched reviewer might type work. Without it, `bd` finds the
# database by walking up from the working directory: run from anywhere but the
# checkout, bead.sh printed NOTHING and exited 1 — which this header documents
# below as meaning the id does not exist (computenet-kzok). Before this flag
# existed, the reviewer dispatch line "run bd with -C <main-checkout>" and the
# bead.sh recommendation could not both be followed: the flag reached the jq
# filter and died as `jq: error: C/0 is not defined`, an error naming neither
# bd nor the checkout, so the lesson it taught was "bead.sh is broken". Two
# reviewers hit it within an hour (computenet-wd7n).
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

# -C is accepted in either position, so the two instructions a dispatched
# reviewer is given ("-C the main checkout" and "use bead.sh") compose whichever
# order it types them in.
dir=""
[ "${1:-}" = "-C" ] && { dir=${2:?-C needs a directory}; shift 2; }
id=${1:?usage: bead.sh [-C <dir>] <id> [-r] [jq-filter]}
shift
[ "${1:-}" = "-C" ] && { dir=${2:?-C needs a directory}; shift 2; }
raw=""
[ "${1:-}" = "-r" ] && { raw="-r"; shift; }
filter=${1:-.}

# A trailing `-C` past the filter would be silently dropped and the call would
# then fail as an empty rc=1 — the same ambiguous silence this flag exists to
# remove. Refuse it loudly instead.
[ $# -gt 1 ] && {
  echo "bead.sh: unexpected argument '$2' — -C goes before the id or right after it" >&2
  exit 2
}

# An unset dir must NOT become `bd -C ""` (bd would chdir to the empty path),
# so the flag is carried as an array that is empty when no -C was given. The
# `[@]+` guard is load-bearing: this host's /bin/bash is 3.2, where `set -u`
# treats an EMPTY array's "${a[@]}" as unbound and aborts — so the no-`-C`
# path, which is every existing caller, dies on the line added for -C.
bd_dir=()
[ -n "$dir" ] && bd_dir=(-C "$dir")

# sed slices to the first JSON token: bd prefixes advisory lines on stderr AND
# stdout (bd-traps.md's "malformed mid-document" note is about the tail, which
# jq surfaces as a parse error rather than silence).
out=$(bd ${bd_dir[@]+"${bd_dir[@]}"} show "$id" --json 2>/dev/null \
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
  spill_dir=${SCRATCH:-${TMPDIR:-/tmp}}
  # A failed mktemp must not print a success-shaped message with an empty
  # path: the output would be gone and the exit code still 0. Fall back to
  # printing it, which is at worst the old truncation.
  f=$(mktemp "${spill_dir%/}/bead-$id.XXXXXX") || { printf '%s\n' "$out"; exit $rc; }
  printf '%s\n' "$out" > "$f"
  echo "bead.sh: ${#out} characters exceeds one tool result; wrote $f — read it with the Read tool (it will NOT fit in a single Bash output either)."
elif [ -n "$out" ]; then
  printf '%s\n' "$out"
fi
exit $rc
