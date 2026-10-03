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
# EPIC-BREAKDOWN children come through here too, even under a claimed epic
# (6wc.5-D5): two machines can both hold an epic across a partition, and dotted
# ids would then collide on child_counters — unrecoverable, where a duplicate
# hash-id feature set is merely closable. Only feature -> task breakdown
# children keep `bd create --parent=`.
#
# Usage:
#   create-ticket.sh --type <bug|feature|task|chore> --title "<one line>" \
#     (--parent <id> | --top-level) [--desc D | --desc-file F] \
#     [--accept A | --accept-file F] [--priority N] \
#     [--label L]... [--metadata '<json>'] [--model M] [--breakdown T] [--claim]
#     [--no-dup-check]
#
# Duplicate pre-check (computenet-x28lp): before creating, searches open beads
# (`bd search`) and open PRs (`gh pr list --search`) for the title and prints
# each candidate to stderr as `POSSIBLE-DUPLICATE <id> <title>`. It never
# blocks: the bead is still created, and a failed search is a warning. Read
# the candidates; a real duplicate gets a comment, not a second bead.
# --no-dup-check skips it.
# --model: sets metadata.model (merged into --metadata), so a ticket the
#   orchestrator files reaches next-batch.py with a dispatch model rather than
#   tripping 5b's empty-model rule against a breakdown that never ran
#   (computenet-q1jc3, computenet-ci7k).
# --breakdown: sets metadata.breakdown (merged the same way), the token from
#   breakdown-marker.sh that says WHICH breakdown of the epic minted this
#   feature. Without it a duplicate feature set raced in under partition is
#   unattributable, and the survivor rule has nothing to close (6wc.5-D5;
#   `breakdown-marker.sh survivor <epic>` reads exactly this key).
# --top-level: the one sanctioned unparented create (recovery.md, red check;
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

TYPE= TITLE= PARENT= DESC= ACCEPT= PRIO=2 META= MODEL= BREAKDOWN= CLAIM=0 TOP=0 DUPCHECK=1
LABELS=()
# --help prints the comment header's own Usage block rather than a second copy
# that can drift from it. A reviewer guessed `--description-file` for
# `--desc-file` and burned two failed calls before resorting to sed-ing this
# file; the flag names have to be reachable from the tool (computenet-axxl).
usage() {
  sed -n '/^# Usage:/,/^#$/p' "$0" | sed 's/^#[[:space:]]\{0,1\}//'
}

while [ $# -gt 0 ]; do
  # --flag=value is the bd create spelling; split it so both forms work (computenet-322dl).
  case "$1" in --*=*) set -- "${1%%=*}" "${1#*=}" "${@:2}" ;; esac
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
    --model)    MODEL=$2; shift 2 ;;
    --breakdown) BREAKDOWN=$2; shift 2 ;;
    --claim)    CLAIM=1; shift ;;
    --no-dup-check) DUPCHECK=0; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) usage >&2; echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$TYPE" in bug|feature|task|chore) ;; *) echo "--type must be bug, feature, task or chore" >&2; exit 2 ;; esac
[ -n "$TITLE" ]  || { echo "--title is required" >&2; exit 2; }
if [ -z "$PARENT" ] && [ "$TOP" != 1 ]; then
  echo "--parent is required, or --top-level for a deliberately unparented bead (a first-sighting red-check bug, recovery.md § A red required check)" >&2; exit 2
fi
if [ -n "$PARENT" ] && [ "$TOP" = 1 ]; then echo "--parent and --top-level are exclusive" >&2; exit 2; fi

if [ -n "$MODEL" ]; then
  META=$(jq -cn --argjson m "${META:-null}" --arg v "$MODEL" '($m // {}) + {model: $v}') \
    || { echo "--metadata is not a JSON object" >&2; exit 2; }
fi

if [ -n "$BREAKDOWN" ]; then
  META=$(jq -cn --argjson m "${META:-null}" --arg v "$BREAKDOWN" '($m // {}) + {breakdown: $v}') \
    || { echo "--metadata is not a JSON object" >&2; exit 2; }
fi

args=(create "$TITLE" --type="$TYPE" --priority="$PRIO" --json)
for l in ${LABELS+"${LABELS[@]}"}; do args+=(--label="$l"); done
[ -n "$META" ]   && args+=(--metadata "$META")
[ -n "$DESC" ]   && args+=(--description="$DESC")
[ -n "$ACCEPT" ] && args+=(--acceptance="$ACCEPT")

# 0. duplicate pre-check: advisory only, every failure path is a warning.
#    bd search matches the query as a title substring, so a whole-title search
#    finds only near-verbatim refilings; the title's longest word widens it.
#    gh search ANDs its terms, so it gets the three longest words.
dup_check() {
  local words longest out
  words=$(tr -cs '[:alnum:]_.-' '\n' <<<"$TITLE" | awk 'length($0) >= 5 { print length($0), $0 }' \
          | sort -rn | awk '!seen[$2]++ { print $2 }' | head -3)
  longest=$(head -1 <<<"$words")
  for q in "$TITLE" ${longest:+"$longest"}; do
    if out=$(bd search "$q" --json --limit 5 2>/dev/null) \
       && out=$(jq -r 'if type=="array" then .[] else (.issues // [])[] end
                       | "POSSIBLE-DUPLICATE \(.id) \(.title)"' <<<"$out" 2>/dev/null); then
      [ -n "$out" ] && printf '%s\n' "$out"
    else
      echo "warning: dup-check: bd search failed for '$q'; creating anyway" >&2
    fi
  done | awk '!seen[$0]++' >&2
  [ -n "$words" ] || return 0
  if ! command -v gh >/dev/null 2>&1; then
    echo "warning: dup-check: gh not found; open PRs not searched" >&2; return 0
  fi
  if out=$(gh pr list --search "$(tr '\n' ' ' <<<"$words")" --state open --json number,title 2>/dev/null) \
     && out=$(jq -r '.[] | "POSSIBLE-DUPLICATE PR#\(.number) \(.title)"' <<<"$out" 2>/dev/null); then
    [ -n "$out" ] && printf '%s\n' "$out" >&2
  else
    echo "warning: dup-check: gh pr list failed; open PRs not searched" >&2
  fi
  return 0
}
[ "$DUPCHECK" = 1 ] && dup_check

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
