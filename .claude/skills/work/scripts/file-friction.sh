#!/usr/bin/env bash
# File one friction item under the SDLC epic without the cross-machine id
# collision. `bd create --parent=X` allocates the child id from
# child_counters, a per-database table reconciled only at sync — two machines
# filing under the same parent between syncs mint THE SAME id for different
# beads (measured 2026-08-14: wpvy.40/.41/.42 each named two unrelated items;
# the pull then aborts on child_counters, and last-write-wins resolution would
# destroy real beads). Creating UNPARENTED yields a hash id and leaves the
# counter untouched; re-parenting afterwards keeps that id. This applies to a
# SHARED parent only — breakdown children under a claimed epic/feature are
# exclusive by that claim and keep their dotted ids.
#
# Also: stamps skill_version (which skill revision produced the report),
# applies the skill-friction label explicitly (labels are NOT inherited when
# created unparented), and claims the item for this machine so exactly one
# orchestrator lane drains it.
#
# Dedup is the CALLER's job, first: bd search "<words>" --status all --json
# (--status all, or an already-fixed-and-closed item is invisible and gets
# re-filed). Upvote an existing item with a comment instead of filing a twin.
#
# Usage:
#   file-friction.sh --type bug|feature --title "<one line>" \
#     --desc "<what the skill says / what happened / what it cost>" \
#     --accept "<what must change in the skill for this not to recur>" \
#     [--parent computenet-wpvy] [--priority 2] [--skill-version <sha>]
#
# Prints the new bead id. Exit 1 on a failed step, saying which. A crash
# between create and re-parent leaves an unparented hash-id bead, not a lost
# one — recover with:
#   bd list --status=open --label=skill-friction --json   # find it, then
#   bd update <id> --parent=computenet-wpvy
set -uo pipefail

TYPE= TITLE= DESC= ACCEPT= PARENT=computenet-wpvy PRIO=2 SKILL_V=
while [ $# -gt 0 ]; do
  case "$1" in
    --type)          TYPE=$2; shift 2 ;;
    --title)         TITLE=$2; shift 2 ;;
    --desc)          DESC=$2; shift 2 ;;
    --accept)        ACCEPT=$2; shift 2 ;;
    --parent)        PARENT=$2; shift 2 ;;
    --priority)      PRIO=$2; shift 2 ;;
    --skill-version) SKILL_V=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$TYPE" in bug|feature) ;; *) echo "--type must be bug or feature (bug = the skill misbehaved; feature = a missing capability)" >&2; exit 2 ;; esac
[ -n "$TITLE" ] && [ -n "$DESC" ] && [ -n "$ACCEPT" ] \
  || { echo "--title, --desc and --accept are all required" >&2; exit 2; }

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
[ -n "$SKILL_V" ] || SKILL_V=$(git hash-object "$SCRIPT_DIR/../SKILL.md")

# 1. create with NO --parent: hash id, child_counters untouched.
#    bd CREATE returns an object (bd SHOW returns a list).
NEW=$(bd create "work skill: $TITLE" --type="$TYPE" --priority="$PRIO" \
        --label=skill-friction --metadata "{\"skill_version\":\"$SKILL_V\"}" \
        --description="$DESC" --acceptance="$ACCEPT" --json \
      | jq -r '.id // empty')
[ -n "$NEW" ] || { echo "bd create failed or returned no id" >&2; exit 1; }

# 2. attach, then claim; the id does not change.
bd update "$NEW" --parent="$PARENT" \
  || { echo "$NEW created but NOT parented — recover: bd update $NEW --parent=$PARENT" >&2; exit 1; }
bd update "$NEW" --claim \
  || echo "note: claim on $NEW failed; it is filed and parented but unclaimed" >&2
echo "$NEW"
