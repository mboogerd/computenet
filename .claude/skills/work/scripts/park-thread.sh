#!/usr/bin/env bash
# park-thread.sh <bead-id> — a human-parked bead's comment thread, in full, with
# a verdict on whether it has been ANSWERED.
#
# WHY THIS EXISTS: THE NEWEST COMMENT IS NOT THE STATE. computenet-3sua carried
# the maintainer's answer at 09:35:16Z and a full RE-PARK of the same question
# at 09:37:48Z — two minutes later, restating the question as open, listing the
# options as unresolved. The bead's status, assignee and `human` label all
# agreed with the newer comment. A session reading the thread tail-first, or
# reading the newest comment as the current state — both entirely reasonable —
# concludes the item is still waiting on a human and moves on. Here that would
# have stranded ~4 hours of gated benchmark work behind a question answered
# that morning (computenet-1cuq).
#
# The 09:37 re-park was itself written by a session that did not see the 09:35
# answer, so this is a defect at both ends: read before you park, too.
#
# It also sidesteps `bd comments`' default view, which TRUNCATES bodies
# mid-word (computenet-wq14) — so the cheap read is precisely the one that
# shows a re-park's opening line and hides the answer's.
#
# AUTHORSHIP CANNOT DECIDE THIS. Every comment on a parked bead is written by
# the machine actor, the human answers included: in 3sua's thread all ten
# comments read `MacBoo`, the maintainer's decision among them. So the
# classification is textual, and it is ADVISORY — the verdict says which
# comments to read, never what to do.
#
# Usage: park-thread.sh <bead-id>
# Exit: 0 = an answer-shaped comment exists (read it; the verdict says whether
#           something re-parked AFTER it, which is the dangerous shape);
#       1 = no answer-shaped comment — the park stands;
#       2 = usage, or the bead has no readable comments.
set -uo pipefail
id=${1:?usage: park-thread.sh <bead-id>}

json=$(bd comments "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p')
[ -n "$json" ] || { echo "park-thread: no comments read for $id" >&2; exit 2; }

# ANSWER-shaped and PARK-shaped markers, matched on the comment's FIRST LINE
# only: a re-park quotes the question and an answer quotes the options, so a
# whole-body match makes every comment look like both.
printf '%s' "$json" | ANSWER_RE='maintainer decision|human respond|^answer[: ]|human answered|decision \(' \
  PARK_RE='^question|^parked|re-park|parked by' jq -r --arg id "$id" '
  def first_line: (.text // .body // .content // "") | split("\n")[0];
  def kind: (first_line | ascii_downcase) as $l
    | if   ($l | test("^unparked"))  then "note"
      elif ($l | test(env.ANSWER_RE)) then "ANSWER"
      elif ($l | test(env.PARK_RE))   then "PARK"
      else "note" end;
  (if type=="array" then . else (.comments // []) end)
  | sort_by(.created_at)
  | to_entries[]
  | "[\(.key + 1)] \(.value.created_at)  \(.value.author // "?")  \(.value | kind)\n\((.value.text // .value.body // .value.content // "") | rtrimstr("\n"))\n"'

# The verdict is computed from the same classification, over the same order.
counts=$(printf '%s' "$json" | ANSWER_RE='maintainer decision|human respond|^answer[: ]|human answered|decision \(' \
  PARK_RE='^question|^parked|re-park|parked by' jq -r '
  def first_line: (.text // .body // .content // "") | split("\n")[0];
  def kind: (first_line | ascii_downcase) as $l
    | if   ($l | test("^unparked"))  then "note"
      elif ($l | test(env.ANSWER_RE)) then "ANSWER"
      elif ($l | test(env.PARK_RE))   then "PARK"
      else "note" end;
  [ (if type=="array" then . else (.comments // []) end) | sort_by(.created_at)[]
    | {t: .created_at, k: kind} ] as $c
  | ($c | map(select(.k=="ANSWER")) | last) as $a
  | ($c | map(select(.k=="PARK"))   | last) as $p
  | "\($a.t // "-")\t\($p.t // "-")"')
answer=${counts%%	*}; park=${counts##*	}

echo "----"
if [ "$answer" = "-" ]; then
  echo "park-thread: NO ANSWER-SHAPED COMMENT — the park stands on this thread."
  echo "park-thread: read it anyway before re-parking; a marker this misses is still an answer."
  exit 1
fi
if [ "$park" != "-" ] && [ "$park" \> "$answer" ]; then
  echo "park-thread: ANSWERED at $answer, then RE-PARKED at $park."
  echo "park-thread: THE NEWEST COMMENT IS NOT THE STATE. A re-park is written by an" \
       "agent; an answer is not. Read [$answer] before you believe the tail of this" \
       "thread, and if you unpark, say so on the bead naming BOTH timestamps."
  exit 0
fi
echo "park-thread: ANSWERED at $answer, nothing re-parked after it."
exit 0
