#!/usr/bin/env bash
# Start the alignment demo DURABLY: always with --journal, on the latest usable journal.
#
#   scripts/alignment-durable.sh [port]          (default port 18431)
#
# Journals live outside the repo so they survive worktree cleanup:
#   ${ALIGNMENT_JOURNALS:-~/.local/share/computenet/alignment}/journal-<YYYYmmdd-HHMMSS>/
# The newest one without a FAILED marker is reused. If replaying it fails at startup — a corrupt
# journal, or one a code change made unreadable (renamed/removed record types, changed serializers) —
# the error is saved to <journal>/FAILED, the journal is kept untouched, and the demo starts on a
# NEW empty journal with a loud warning. Any other startup failure (busy port, build error) just fails.
set -uo pipefail
PORT="${1:-18431}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JOURNALS="${ALIGNMENT_JOURNALS:-$HOME/.local/share/computenet/alignment}"
BIN="$ROOT/demo/alignment/build/install/alignment/bin/alignment"

"$ROOT/gradlew" -p "$ROOT" :demo:alignment:installDist -q --console=plain 2>&1 | grep -v '^WARNING' || true
[ -x "$BIN" ] || { echo "alignment-durable: build failed, no $BIN" >&2; exit 1; }
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "alignment-durable: port $PORT is already in use; pass another port" >&2
  exit 1
fi

mkdir -p "$JOURNALS"
fresh() { echo "$JOURNALS/journal-$(date +%Y%m%d-%H%M%S)"; }
journal=""
for d in "$JOURNALS"/journal-*; do
  [ -d "$d" ] && [ ! -e "$d/FAILED" ] && journal="$d"   # glob order is sorted, so the last one wins
done
[ -n "$journal" ] || journal="$(fresh)"

echo "alignment-durable: journal $journal"
log="$(mktemp -t alignment-durable)"
"$BIN" "$PORT" --journal "$journal" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}

# Started fine and was stopped later: nothing to recover from.
grep -q 'computenet alignment: http' "$log" && exit "$status"
# Died during journal recovery (frames from the replay path): retire this journal, start a new one.
if grep -qE 'AlignmentRuntime|rebuildMirrors|FileJournal|\.recover\(' "$log"; then
  cp "$log" "$journal/FAILED"
  next="$(fresh)"
  cat >&2 <<EOF

!!! alignment-durable: the journal could not be replayed — it is corrupt, or a code change made it
!!! incompatible. It is kept as-is (error saved to $journal/FAILED).
!!! Starting on a NEW, EMPTY journal: $next
!!! Topics and ratings from the old journal are NOT loaded.

EOF
  exec "$BIN" "$PORT" --journal "$next"
fi
exit "$status"
