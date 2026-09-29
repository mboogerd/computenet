#!/usr/bin/env bash
# Shared "swept file" writer. Sourced by sweep-stale-claims.sh (which
# releases stale claims) and sweep-merged-prs.sh (which closes beads behind a
# merged PR) — the two step-3 sweeps whose writes bump a bead's updated_at in
# the LOCAL Dolt DB before claim-epic.sh's hot-subtree test reads that same
# field to guess whether ANOTHER machine is inside the epic (x3f5a).
# Recording what THIS session touched, with the time, is what lets
# claim-epic.sh discount its own sweep instead of reading it as foreign
# activity (computenet-x3f5a, recurred for the merged-PR sweep as
# computenet-r2knf: sweep-merged-prs.sh closed a bead under the top resumable
# epic and claim-epic.sh then refused that same epic as "hot").
#
# Format is exactly what claim-epic.sh's `awk -v c="$cutoff" '$1 >= c
# {print $2}'` parses: one line per record, "<epoch> <id>". Keep both writers
# on this one function so the format can never drift between them.
#
# Usage:
#   SWEPT_FILE=${CLAIM_SWEPT_FILE:-"${TMPDIR:-/tmp}/work-swept-${BEADS_ACTOR}"}
#   record_swept "$id"
#
# A dry run must never call this — recording a release/close that did not
# happen would make claim-epic.sh discount a real foreign touch on the same
# id. Callers gate the call on their own --dry-run flag, not this function.
record_swept() {
  local id=$1
  : "${SWEPT_FILE:?record_swept: SWEPT_FILE must be set by the caller}"
  echo "$(date +%s) $id" >> "$SWEPT_FILE" 2>/dev/null \
    || echo "warning: could not record $id in $SWEPT_FILE — claim-epic.sh may read this as another machine's activity" >&2
}
