#!/usr/bin/env bash
# SessionEnd hook: deterministically release this machine's own beads item
# claims for the terminating session, so a /work-session that never reaches
# its own Finalize step (crash, hard timeout, overrun into the next slot)
# can't leave a claim stuck in_progress forever.
#
# Scope is deliberately narrow:
#   - status=in_progress AND assignee=<this actor> AND metadata.session=<this
#     session's id> only. A different overlapping session run by the same
#     actor (e.g. this slot overran into the next cron tick) keeps its own
#     claims untouched, since its session id differs.
#   - epic/feature types are excluded. Their in_progress status + owner:
#     label is a deliberate cross-session lock (see work/references/epic.md,
#     feature.md), not a per-session claim, and must survive session end.
set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}"

payload="$(cat)"
session_id="$(printf '%s' "$payload" | jq -r '.session_id // empty' 2>/dev/null || true)"
session_id="${session_id:-${CLAUDE_SESSION_ID:-}}"

actor="${BEADS_ACTOR:-$(git config user.name 2>/dev/null || true)}"
actor="${actor:-$USER}"

if [ -z "$session_id" ] || [ -z "$actor" ]; then
  echo "beads-release-session-claims: missing session id or actor, skipping" >&2
  exit 0
fi

if ! command -v bd >/dev/null 2>&1; then
  exit 0
fi

ids="$(bd list --status=in_progress --assignee="$actor" --exclude-type=epic,feature \
  --metadata-field="session=$session_id" --json 2>/dev/null | jq -r '.[].id' 2>/dev/null || true)"

if [ -z "$ids" ]; then
  exit 0
fi

for id in $ids; do
  bd update "$id" --status=open >/dev/null 2>&1 || true
done

bd dolt push >/dev/null 2>&1 || true

echo "beads-release-session-claims: released $(echo "$ids" | wc -l | tr -d ' ') claim(s) for session $session_id" >&2
exit 0
