#!/usr/bin/env bash
# Create a ticket under a SHARED parent without the cross-machine id collision.
# This is the sanctioned create path: no skill may hand-type
# `bd create --parent=<shared epic>`.
#
# WHY. `bd create --parent=X` allocates the child id from `child_counters`, a
# PER-DATABASE table reconciled only at sync. Two machines filing under the
# same parent between syncs read the same `last_child` and mint THE SAME id for
# different beads (measured 2026-08-14: wpvy.40/.41/.42 each named two
# unrelated items; the pull then aborts on child_counters, and last-write-wins
# resolution would destroy real beads — computenet-azt). Creating UNPARENTED
# yields a hash id and leaves the counter untouched; re-parenting afterwards
# keeps that id. Verified: counter 46 before, 46 after.
#
# SCOPE. Shared parents only. Breakdown children under an epic or feature THIS
# session has claimed are exclusive by that claim, cannot collide, and keep
# their readable dotted ids — those may use `bd create --parent=` directly.
# `computenet-wpvy.47` (2026-08-15) is what a hand-typed create under a shared
# epic looks like after the fact: harmless that time, unrecoverable the time
# the other machine mints the same id.
#
# Usage:
#   create-ticket.sh --type <bug|feature|task|chore> --title "<one line>" \
#     (--parent <id> | --top-level) [--desc D | --desc-file F] \
#     [--accept A | --accept-file F] [--priority N] \
#     [--label L]... [--metadata '<json>'] [--claim]
#
# --top-level: the one sanctioned unparented create (red-check-attribution.md
#   artifact 3's first-sighting bug). This was refused with "use bd create
#   directly", which meant re-typing a composed heredoc body under a different
#   tool mid-attribution, twice in one session (computenet-7xeh).
# --desc-file / --accept-file: the body is read from a file and never passes
#   through a shell word, so backticks and $(...) in it are inert. `--desc
#   "$(cat f)"` expands them before the script runs (computenet-s5dh;
#   issue-quality.md "Backticks…"). Prefer the -file forms for any multi-line
#   body; file-friction.sh exposes the same flags.
#
# Prints the new bead id on stdout, and NOTHING else — every diagnostic,
# including the delegated `bd update` confirmation lines, goes to stderr, so
# `T=$(create-ticket.sh ...)` is a usable id. It was not: bd's "Updated issue"
# line landed on stdout ahead of the id, and callers using the documented
# capture got a two-line string that bd then refused (computenet-5ari, two
# agents in one session). Exit 2 on bad arguments, 1 on a failed
# step, saying which. A crash between create and re-parent leaves an
# unparented hash-id bead, not a lost one — recover with:
#   bd update <id> --parent=<parent>
set -uo pipefail

TYPE= TITLE= PARENT= DESC= ACCEPT= PRIO=2 META= CLAIM=0 TOP=0
LABELS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --type)     TYPE=$2; shift 2 ;;
    --title)    TITLE=$2; shift 2 ;;
    --parent)   PARENT=$2; shift 2 ;;
    --desc)     DESC=$2; shift 2 ;;
    --accept)   ACCEPT=$2; shift 2 ;;
    --desc-file)   DESC=$(cat "$2")   || { echo "cannot read --desc-file $2" >&2; exit 2; }; shift 2 ;;
    --accept-file) ACCEPT=$(cat "$2") || { echo "cannot read --accept-file $2" >&2; exit 2; }; shift 2 ;;
    --top-level) TOP=1; shift ;;
    --priority) PRIO=$2; shift 2 ;;
    --label)    LABELS+=("$2"); shift 2 ;;
    --metadata) META=$2; shift 2 ;;
    --claim)    CLAIM=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$TYPE" in bug|feature|task|chore) ;; *) echo "--type must be bug, feature, task or chore" >&2; exit 2 ;; esac
[ -n "$TITLE" ]  || { echo "--title is required" >&2; exit 2; }
if [ -z "$PARENT" ] && [ "$TOP" != 1 ]; then
  echo "--parent is required, or --top-level for a deliberately unparented bead (a first-sighting red-check bug, red-check-attribution.md artifact 3)" >&2; exit 2
fi
if [ -n "$PARENT" ] && [ "$TOP" = 1 ]; then echo "--parent and --top-level are exclusive" >&2; exit 2; fi

args=(create "$TITLE" --type="$TYPE" --priority="$PRIO" --json)
for l in ${LABELS+"${LABELS[@]}"}; do args+=(--label="$l"); done
[ -n "$META" ]   && args+=(--metadata "$META")
[ -n "$DESC" ]   && args+=(--description="$DESC")
[ -n "$ACCEPT" ] && args+=(--acceptance="$ACCEPT")

# 1. create with NO --parent: hash id, child_counters untouched.
#    bd CREATE returns an object (bd SHOW returns a list), so `.id` — not
#    `.[0].id // .id`, whose `//` catches null but not the error an object
#    raises, leaving $NEW empty and the re-parent a silent no-op.
NEW=$(bd "${args[@]}" | jq -r '.id // empty')
[ -n "$NEW" ] || { echo "bd create failed or returned no id" >&2; exit 1; }

# 2. attach; the id does not change. (--top-level: nothing to attach.)
if [ "$TOP" != 1 ]; then
  bd update "$NEW" --parent="$PARENT" >&2 \
    || { echo "$NEW created but NOT parented — recover: bd update $NEW --parent=$PARENT" >&2; exit 1; }
fi

# 3. optionally claim, so exactly one lane drains it. A failed claim is a
#    note: the bead is filed and attached, which is the part that matters.
if [ "$CLAIM" = 1 ]; then
  bd update "$NEW" --claim >&2 \
    || echo "note: claim on $NEW failed; it is filed and parented but unclaimed" >&2
fi
echo "$NEW"
