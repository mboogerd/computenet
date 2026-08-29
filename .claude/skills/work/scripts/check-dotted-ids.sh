#!/usr/bin/env bash
# Backstop for computenet-azt: report recently created NUMERIC dotted beads
# whose parent this machine does not hold — the signature of a hand-typed
# `bd create --parent=<shared epic>` that bypassed create-ticket.sh.
#
# WHY THIS SHAPE.
# * WARNS, never blocks (always exits 0). By the time any hook runs the bead
#   already exists in the local Dolt DB with its dotted id, and an id is a
#   primary key — blocking the git push cannot un-mint it, it would only
#   punish unrelated code. The value here is DETECTION SPEED: computenet-wpvy.47
#   sat unnoticed for two days; this surfaces the next one on the first push.
# * PRE-PUSH, not pre-commit. Roughly one run per session instead of one per
#   commit, at the point state leaves the machine, and it never delays the
#   inner edit loop.
# * Reports only beads THIS machine created (created_by == BEADS_ACTOR) under
#   a parent it does not own. A dotted child under a parent we HAVE claimed is
#   legitimate and common — exclusive by that claim, cannot collide — and the
#   other machine's dotted children are neither our mistake nor ours to fix.
#   Measured while building this: without the created_by filter a 5-day window
#   printed ~20 historical wpvy.N lines, which is how a hook gets disabled.
# * Skips (exit 0) when BEADS_ACTOR or jq is missing rather than guessing: a
#   check that cannot tell ours from theirs would flag every breakdown child,
#   and a hook that cries wolf gets disabled. The BEADS_ACTOR skip says so in
#   one line rather than returning silently: a backstop that is quietly dead
#   on a machine reads exactly like a backstop that found nothing.
#
# Cost: two `bd list` calls plus one `bd show` per flagged parent — ~3s quiet,
# ~6s when it reports. Once per push, never in the edit loop.
#
# Usage: check-dotted-ids.sh [--days N]   (default 2)
set -uo pipefail

DAYS=2
[ "${1:-}" = --days ] && DAYS=$2

command -v jq >/dev/null 2>&1 || exit 0
if [ -z "${BEADS_ACTOR:-}" ]; then
  echo >&2 "beads id-collision check skipped: BEADS_ACTOR is unset (set it per machine to enable)"
  exit 0
fi

SINCE=$(date -u -v-"${DAYS}"d +%Y-%m-%d 2>/dev/null) \
  || SINCE=$(date -u -d "${DAYS} days ago" +%Y-%m-%d 2>/dev/null) \
  || exit 0

# `bd list --json` returns a bare ARRAY, except with --skip-labels, where it
# returns {"issues":[...]}. Accept either rather than depending on that.
ROWS='(if type=="array" then . else (.issues // []) end)[]'

dotted=$(bd list --all --created-after "$SINCE" --limit 0 --skip-labels --json 2>/dev/null \
         | jq -r "$ROWS | select(.created_by == \"$BEADS_ACTOR\")
                       | select(.id | test(\"\\\\.[0-9]+$\")) | .id" 2>/dev/null) || exit 0
[ -n "$dotted" ] || exit 0

# Parents this machine owns. `assignee` is the live claim and is absent once a
# session releases it, so the durable marker matters too: claim-epic.sh stamps
# owner:$BEADS_ACTOR as a label and never removes it. Without this, every
# breakdown child under a finished epic would flag for two days.
mine=$(bd list --all --label="owner:$BEADS_ACTOR" --limit 0 --json 2>/dev/null \
       | sed -n '/^[[{]/,/^[]}]/p' \
       | jq -r "$ROWS | .id" 2>/dev/null)

# Resolve ownership once per PARENT, not once per child: `bd show` is ~0.5s and
# a wide window can hold dozens of children of one epic.
#
# Ownership is the EFFECTIVE EPIC's, not the direct parent's (AGENTS.md:
# "breakdown children under an epic or feature this session has claimed are
# exclusive by that claim"). A feature under a held epic carries no assignee
# by design, so reading only the direct parent warned on every legitimate
# .N.M breakdown — four times per push, all session — and trained sessions to
# scroll past the one warning that was right (computenet-qbp2). Walk up as
# epic-of.sh does: explicit .parent, else the dotted prefix, up to 12 hops;
# held at ANY hop is held.
held_by_us() {  # $1 = bead id; sets $owner to the last assignee seen
  local id=$1 n=0 row p
  owner=
  while [ -n "$id" ] && [ "$n" -lt 12 ]; do
    n=$((n+1))
    # -Fx: whole-line match, or computenet-7em.1 would match the line
    # computenet-7em.1.6 and silently skip a real finding.
    printf '%s\n' "$mine" | grep -Fxq "$id" && return 0
    row=$(bd show "$id" --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p')
    owner=$(printf '%s' "$row" | jq -r '.[0].assignee // ""' 2>/dev/null)
    [ "$owner" = "$BEADS_ACTOR" ] && return 0
    p=$(printf '%s' "$row" | jq -r '.[0].parent // empty' 2>/dev/null)
    if [ -z "$p" ]; then case "$id" in *.*) p="${id%.*}";; esac; fi
    id=$p
  done
  return 1
}

flagged=
for parent in $(for id in $dotted; do echo "${id%.*}"; done | sort -u); do
  held_by_us "$parent" && continue
  owner=$(bd show "$parent" --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p' \
          | jq -r '.[0].assignee // ""' 2>/dev/null)
  for id in $dotted; do
    [ "${id%.*}" = "$parent" ] || continue
    flagged="$flagged  $id  (parent $parent held by ${owner:-nobody})
"
  done
done
[ -n "$flagged" ] || exit 0

cat >&2 <<EOF

  ⚠  beads id-collision risk (computenet-azt) — created in the last ${DAYS} day(s):

$flagged
  These carry a numeric dotted id minted from child_counters, a PER-DATABASE
  table reconciled only at sync. Under a parent this machine does not hold,
  the other machine can mint the SAME id for a different bead; the pull then
  aborts on child_counters and last-write-wins resolution DESTROYS one of them.

  Create under a shared parent with the sanctioned path, which yields a hash
  id and leaves the counter untouched:
    .claude/skills/work/scripts/create-ticket.sh --type <t> --title "<t>" --parent <id>

  Nothing is blocked: the id is already minted and cannot be changed. Treat
  this as "do not do that again", and check the other machine for a twin.

EOF
exit 0
