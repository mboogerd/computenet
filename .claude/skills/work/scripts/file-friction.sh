#!/usr/bin/env bash
# File one friction item under the SDLC epic. A thin wrapper over
# create-ticket.sh, which owns the unparented-then-reparent idiom that avoids
# the cross-machine id collision (see its header, and computenet-azt).
#
# What this adds on top: the `work skill:` title prefix — added HERE, so pass
# --title WITHOUT it; a leading one is stripped so the call is idempotent
# either way (21 of 235 friction beads carried it twice, computenet-rtoo) —
# the skill_version
# stamp (which skill revision produced the report), and the skill-friction
# label — labels are NOT inherited when created unparented.
#
# It does NOT claim. Filing is not picking up: a claim belongs to the session
# that starts draining the item, not to the one that reported it. Claiming at
# file time made every filed item `in_progress`, which is what broke
# remediate-friction's `--status=open` drain listing (computenet-oxbv). That
# was first patched by widening the listing to everything non-closed; this is
# the other half, and the one that lets a listing mean what it says —
# `in_progress` under the SDLC epic is a session draining that item, not a
# session that once reported it.
#
# Dedup is the CALLER's job, first: bd search "<words>" --status all --json
# (--status all, or an already-fixed-and-closed item is invisible and gets
# re-filed). Upvote an existing item with a comment instead of filing a twin.
#
# Usage:
#   file-friction.sh --type bug|feature --title "<one line, NO 'work skill:' prefix>" \
#     (--desc "<what the skill says / what happened / what it cost>" | --desc-file F) \
#     (--accept "<what must change in the skill for this not to recur>" | --accept-file F) \
#     [--parent computenet-wpvy] [--priority 2] [--skill-version <sha>]
#   --desc-file/--accept-file are PREFERRED: a body passed as --desc "$(cat f)"
#   goes through a shell word, so backticks and $(...) in it are executed
#   (computenet-s5dh).
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
    --desc-file)     DESC_FILE=$2; shift 2 ;;      # preferred: body never passes through a shell word (computenet-s5dh)
    --accept-file)   ACCEPT_FILE=$2; shift 2 ;;
    --parent)        PARENT=$2; shift 2 ;;
    --priority)      PRIO=$2; shift 2 ;;
    --skill-version) SKILL_V=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$TYPE" in bug|feature) ;; *) echo "--type must be bug or feature (bug = the skill misbehaved; feature = a missing capability)" >&2; exit 2 ;; esac
[ -n "${DESC_FILE:-}" ] && { DESC=$(cat "$DESC_FILE") || { echo "cannot read --desc-file $DESC_FILE" >&2; exit 2; }; }
[ -n "${ACCEPT_FILE:-}" ] && { ACCEPT=$(cat "$ACCEPT_FILE") || { echo "cannot read --accept-file $ACCEPT_FILE" >&2; exit 2; }; }
[ -n "$TITLE" ] && [ -n "$DESC" ] && [ -n "$ACCEPT" ] \
  || { echo "--title, --desc[-file] and --accept[-file] are all required" >&2; exit 2; }

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
  --desc-file <(printf '%s' "$DESC") --accept-file <(printf '%s' "$ACCEPT") --priority "$PRIO" \
  --label skill-friction --metadata "{\"skill_version\":\"$SKILL_V\"}"
