#!/usr/bin/env bash
# The one place every close in this skill goes through, so marker processing
# happens at close time instead of waiting for step 3's next startup sweep.
# Closes the bead, then — only on success — runs undefer-unblocked.sh so an
# epic blocked on this bead reopens immediately, not at the next session's
# startup check. Step 3's own startup call to undefer-unblocked.sh stays as
# the FALLBACK for closes that never go through here: a human typing `bd
# close` by hand, or a close that synced in from another machine via Dolt.
#
# Usage: close-bead.sh <bead-id> [bd-close-args...]
# Exit: whatever `bd close` returns, unchanged — the undefer sweep is
#   best-effort and never turns a successful close into a reported failure.
#   A sweep failure is a warning on stderr, not a different exit code.
set -uo pipefail

[ $# -ge 1 ] || { echo "usage: close-bead.sh <bead-id> [bd-close-args...]" >&2; exit 2; }

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CLOSE_BEAD_UNDEFER=${CLOSE_BEAD_UNDEFER:-"$DIR/undefer-unblocked.sh"}

bd close "$@"
rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

if ! sweep_out=$("$CLOSE_BEAD_UNDEFER" 2>&1); then
  echo "close-bead: the close IS durable, but the undefer sweep FAILED — run $CLOSE_BEAD_UNDEFER by hand: ${sweep_out:-no output}" >&2
fi
exit 0
