#!/usr/bin/env bash
# `bd update <id> --claim`, plus the SESSION-unique holder token that makes the
# claim distinguishable from a crash leftover.
#
# WHY THIS EXISTS. `assignee` is BEADS_ACTOR, unique per MACHINE. Two /work
# sessions on one box are therefore the same string, and every liveness rule
# below the epic layer fell back to a recency guess. `claim-epic.sh` already
# stamps `metadata.holder` for exactly this reason; features, tasks and
# cross-epic items did not, so SKILL.md step 3's "read metadata.holder first —
# it decides this exactly" was true only for epics. Measured 2026-09-04 on
# MacBoo: a second session's step-3 sweep reopened a live session's item claim
# 73 minutes after it was made and pushed, because there was no holder to read
# and none of the age rules could tell the two sessions apart (computenet-yvdl).
#
# This is the write half; sweep-stale-claims.sh is the read half, and neither
# alone closes it.
#
# Usage: claim-item.sh <id>
# Exit: bd's on the claim (non-zero if the claim itself is refused — e.g. the
# item is already assigned elsewhere). A holder that cannot be minted is a
# WARNING, never a failure: the claim is the point and liveness degrades to the
# recency test, exactly as it behaved before this script existed.
set -uo pipefail

id=${1:?usage: claim-item.sh <id>}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

bd update "$id" --claim || exit $?

holder=$("$SCRIPT_DIR/session-holder.sh" 2>/dev/null) \
  && bd update "$id" --set-metadata "holder=$holder" >/dev/null \
  || echo "note: could not stamp metadata.holder on $id — a concurrent sibling's sweep will fall back to the recency test" >&2
