#!/usr/bin/env bash
# File one friction item under the SDLC epic. A thin wrapper over
# create-ticket.sh, which owns the unparented-then-reparent idiom that avoids
# the cross-machine id collision (see its header, and computenet-azt).
#
# What this adds on top: the `work skill:` title prefix — added HERE, so pass
# --title WITHOUT it; a leading one is stripped so the call is idempotent
# either way (21 of 235 friction beads carried it twice, computenet-rtoo) —
# the skill_version
# stamp (which skill revision produced the report), the skill-friction label —
# labels are NOT inherited when created unparented — and the claim, so exactly
# one orchestrator lane drains it.
#
# Dedup is the CALLER's job, first: bd search "<words>" --status all --json
# (--status all, or an already-fixed-and-closed item is invisible and gets
# re-filed). Upvote an existing item with a comment instead of filing a twin.
#
# Usage:
#   file-friction.sh --type bug|feature --title "<one line, NO 'work skill:' prefix>" \
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

# The prefix is a deliberate, useful convention, but it is invisible at the
# call site: SKILL.md step 7's template reads as the complete title, so a
# caller that wants `work skill: X` writes exactly that and gets it twice.
# Strip any number of leading occurrences (and the spacing around them) before
# prepending, so the call is idempotent whichever way it was written. The
# doubled prefix eats title width in every listing and is dead weight in the
# TITLE, which is the only field `bd search` reads (computenet-rtoo).
while [[ "$TITLE" =~ ^[[:space:]]*[Ww]ork[[:space:]]skill:[[:space:]]*(.*)$ ]]; do
  TITLE=${BASH_REMATCH[1]}
done
[ -n "$TITLE" ] || { echo "--title was only the 'work skill:' prefix" >&2; exit 2; }

exec "$SCRIPT_DIR/create-ticket.sh" \
  --type "$TYPE" --title "work skill: $TITLE" --parent "$PARENT" \
  --desc "$DESC" --accept "$ACCEPT" --priority "$PRIO" \
  --label skill-friction --metadata "{\"skill_version\":\"$SKILL_V\"}" --claim
