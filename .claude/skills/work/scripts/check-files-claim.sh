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

# KNOWN COUPLINGS: a repo guardrail test that makes file A require file B.
# The claim check greps the bead's TEXT, so an implied file appears nowhere it
# can see — the requirement comes from a test, not from anything the bead says.
# No text-grep can ever know that, so the couplings are listed here.
#
# First entry: kernel's ModuleInventoryTest parses every include(...) line in
# settings.gradle.kts and fails :kernel:test unless each module appears as a
# literal backticked `:x` in doc/ARCHITECTURE.md. Two sessions added a Gradle
# module on 2026-08-17 (:oracle and :identity) and BOTH got a green
# :<newmodule>:test followed by :kernel:test with exactly one failure naming a
# file their bead never mentioned. Both caught it locally only because their
# dispatch prompts happened to ask for :kernel:test — luck, not structure
# (computenet-d7qn, computenet-m9px). Adding an entry costs one line.
#
# The rest are the SET shape (computenet-y6zv, computenet-os91): a file
# elsewhere that enumerates a package, or a test that asserts a registry is
# exhaustively covered. Neither closes over the thing being added — they close
# over the SET — so no grep for the new symbol reaches them, and a task-scoped
# gate in the module being edited structurally cannot fail on them. Measured:
# PR #544 added UntagCell.kt to civictech.cell.data.op, ran :kernel:test green
# at 1273 tests, and build-test-fast went red in :inspect and :oracle.
#
# Not listed, deliberately: the civictech.cell.data source-cell gate
# (oracle/src/test/resources/source-cell-inventory.txt). Its trigger directory
# CONTAINS civictech/cell/data/op, so it would fire on every operator change —
# a different package, a guaranteed false positive. SKILL.md 5b's enumerator
# walk is what reaches it: `git grep -F 'civictech/cell/data'` returns it, and
# a human reading the hits can tell a source-cell add from an operator add
# where this table cannot. Do not add it here (computenet-y6zv).
COUPLINGS='settings.gradle.kts=>doc/ARCHITECTURE.md
+civictech/cell/data/op=>oracle/src/test/resources/operator-inventory.txt
+civictech/cell/data/op=>inspect/src/main/kotlin/civictech/inspect/Observations.kt
+civictech/cell/data/op=>oracle/src/main/kotlin/civictech/oracle/bind/TaggedOperators.kt
oracle/src/main/kotlin/civictech/oracle/bind/OperatorCatalog.kt=>oracle/src/test/kotlin/civictech/oracle/model/ReferenceModelPurityTest.kt'

# The add-only arm below asks "does this claimed path exist yet", and a claim
# entry is repo-root-relative. Resolve the root rather than trusting the CWD:
# measured, running the script from `<worktree>/kernel` fired all three census
# rows on a bead whose files all exist (bd itself walks up to the workspace, so
# nothing else in the run gives the mistake away).
ROOT_PREFIX=$(git rev-parse --show-toplevel 2>/dev/null)
[ -n "$ROOT_PREFIX" ] && ROOT_PREFIX="$ROOT_PREFIX/"

found=0
for id in "$@"; do
  # No `|| continue` here: with pipefail a failing `bd` would take the whole
  # pipeline down silently, and a mistyped id would read as a clean pass.
  json=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p')
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

  # Not `continue` on an empty $mentioned: the coupling check below keys on
  # tokens the path regex deliberately never matches (settings.gradle.kts has
  # no slash), so an early exit here would make it unreachable.
  if [ -n "$mentioned" ]; then
    while IFS= read -r path; do
      covered "$path" || { echo "$id: names $path, not in metadata.files (a MENTION, possibly read-only — verify against the intended edits, not a violation)"; found=1; }
    done <<<"$mentioned"
  fi

  # Implied by a guardrail, not named by the bead.
  while IFS= read -r rule; do
    [ -n "$rule" ] || continue
    trigger=${rule%%=>*}; implied=${rule#*=>}
    # A `+` prefix means ADD-ONLY: fire only when a CLAIM ENTRY under the
    # trigger names a path that does not exist on disk yet — i.e. the bead
    # adds a file to that directory. Without it the census couplings fire on
    # every bead that merely edits something in the package: measured 11 of 12
    # sampled beads, ~9 of them false, because a tag-semantics fix to an
    # existing cell changes no class census. The guardrail line asserts
    # REQUIRES with none of the MENTION line's hedge, so an all-false stream
    # here is worse than for those — computenet-0pd6's lesson, applied to the
    # rows added by computenet-y6zv.
    #
    # This reads the working tree, so run it BEFORE dispatch, where "the file
    # is not there yet" is true. Re-run after the work lands and the rows fall
    # silent, correctly.
    addonly=0
    case "$trigger" in +*) addonly=1; trigger=${trigger#+} ;; esac

    if [ "$addonly" = 1 ]; then
      inplay=0
      while IFS= read -r entry; do
        case "$entry" in *"$trigger"*) ;; *) continue ;; esac
        # Normalise exactly as covered() does. A DIRECTORY or glob entry
        # (`.../op/**`, `.../op/`) names no new file, and the raw string never
        # exists on disk — unstripped it fired all three census rows on every
        # glob claim, a guaranteed false positive (6 live beads claim globs).
        entry=${entry%/}; entry=${entry%/\*\*}; entry=${entry%/\*}; entry=${entry%/}
        [ -n "$entry" ] || continue
        [ -e "$ROOT_PREFIX$entry" ] || inplay=1
      done <<<"$claim_entries"
      [ "$inplay" = 1 ] || continue
    else
      # The trigger has to be in play at all: either the bead names it, or the
      # claim already covers it. Otherwise the coupling is irrelevant here.
      case "$mentioned"$'\n'"$claim_entries" in
        *"$trigger"*) ;;
        *) continue ;;
      esac
    fi
    covered "$implied" || {
      echo "$id: names $trigger, which REQUIRES $implied (guardrail), not in metadata.files"
      found=1
    }
  done <<<"$COUPLINGS"

  # NOT DONE HERE: resolving a TYPE named in the acceptance to its declaring
  # file. A criterion usually talks about code by type — "matchable by kind
  # from RunOutcome" names the one file a kind can be added to without looking
  # like a path to any grep, so a task can be unsatisfiable from the moment it
  # is filed and this check still passes it clean (computenet-hws5). A
  # CamelCase-to-declaration lookup was written and REJECTED on measurement: it
  # fires on every type a bead merely mentions, including the code under test
  # that a non-goal explicitly forbids editing, which is the exact false
  # positive computenet-0pd6 removed. A check whose output is reliably
  # all-false trains the reader to skim it. The rule lives in feature.md's
  # breakdown step instead, where the author knows which types it will EDIT.
done
exit $found
