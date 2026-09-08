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
# that morning (computenet-1cuq). The 09:37 re-park was itself written by a
# session that had not seen the 09:35 answer, so read before you park, too.
#
# It also sidesteps `bd comments`' default view, which TRUNCATES bodies
# mid-word (computenet-wq14) — so the cheap read is precisely the one that
# shows a re-park's opening line and hides the answer's.
#
# AUTHORSHIP CANNOT DECIDE THIS. Every comment on a parked bead is written by
# the machine actor, the human answers included: in 3sua's thread all ten
# comments read `MacBoo`, the maintainer's decision among them. So the
# classification is textual, ANCHORED at the start of the comment's first line,
# and ADVISORY — the verdict says which comments to read, never what to do.
#
# The markers are drawn from a sweep of this tracker's real answers, not from
# one bead: `ANSWERED by the maintainer (…)`, `ANSWER to the … QUESTION`,
# `Decided 2026-08-31 (…)`, `Human decision 2026-08-25:`, `HUMAN ANSWER
# (Merlijn, …)`, `HUMAN CLARIFICATION (…)`, `MAINTAINER DECISION (mlboogerd,
# …)`, `Maintainer confirmation (…)`, `AMENDMENT TO THE DECISION (…)`,
# `APPROVED 2026-08-19 by the maintainer (…)`. A first cut fitted to ONE bead's
# wording reported four of five real answers as "the park stands" — the failure
# this tool exists to prevent, wearing its own uniform — and the second cut
# still missed computenet-em9i, the bead the maintainer cites as the four-times
# recurrence.
#
# COVERAGE IS NOT COMPLETE AND CANNOT BE. A date-prefixed answer —
# `2026-08-13: user approved the Linux re-run` (computenet-dqy.44, dqy.31) —
# is out of reach of any start-anchored matcher, and unanchoring is what made
# a QUESTION its own answer. That is why exit 1 says "no comment matched the
# markers", never "no answer": the miss is a known, structural residue.
#
# ANCHORED, AND PARK IS TESTED FIRST, because a question legitimately contains
# the answer vocabulary: ask-human.md's own template invites "not a call I
# should make unilaterally", and an unanchored match made a pure QUESTION its
# own ANSWER — with the reassuring verdict.
#
# Usage: park-thread.sh <bead-id>
# Exit: 0 = an answer-shaped comment exists (read it; the verdict says whether
#           something re-parked AFTER it, which is the dangerous shape);
#       1 = no comment matched the answer markers — this is NOT "no answer";
#       2 = nothing could be read or parsed. Never a statement about the park.
set -uo pipefail
id=${1:?usage: park-thread.sh <bead-id>}

raw=$(bd comments "$id" --json 2>/dev/null | sed -n '/^[[{]/,$p')
[ -n "$raw" ] || { echo "park-thread: no comments read for $id — this says nothing about the park" >&2; exit 2; }
# Raw control characters inside bd JSON bodies make jq fail (computenet-9n60).
# Strip them rather than letting the parse error fall through to a verdict.
json=$(printf '%s' "$raw" | tr -d '\000-\010\013\014\016-\037')

CLASSIFY='
  def first_line: (.text // .body // .content // "") | split("\n")[0];
  def kind: (first_line | ascii_downcase) as $l
    | if   ($l | test("^(question|parked|re-park)"))                             then "PARK"
      elif ($l | test("^(answered|answer to |human answer|human clarification|human decision|maintainer|decided |decision |amendment to the decision|approved |human respond)")) then "ANSWER"
      else "note" end;
  (if type=="array" then . else (.comments // []) end)
  | sort_by(.created_at // "")'

# FAIL CLOSED. Without checking jq here, an unparseable payload left the verdict
# variables empty and the script fell through to `ANSWERED at , …` and exit 0 —
# a thread it could not read at all, reported as answered (computenet-9n60).
body=$(printf '%s' "$json" | jq -r "$CLASSIFY"'
  | to_entries[]
  | "[\(.key + 1)] \(.value.created_at // "NO-TIMESTAMP")  \(.value.author // "?")  \(.value | kind)\n\((.value.text // .value.body // .value.content // "") | rtrimstr("\n"))\n"') || {
  echo "park-thread: the comment payload for $id did not parse — nothing is known about this thread" >&2
  exit 2
}
[ -n "$body" ] || { echo "park-thread: $id has no comments — this says nothing about the park" >&2; exit 2; }
printf '%s\n' "$body"

# The verdict reads the SAME classified lines, so it cannot drift from what was
# printed. A matched ANSWER with no timestamp is still an answer: dropping it
# for a missing field is how a real answer became "the park stands".
answer=$(printf '%s\n' "$body" | grep -E '^\[[0-9]+\] .* ANSWER$' | tail -1)
park=$(printf   '%s\n' "$body" | grep -E '^\[[0-9]+\] .* PARK$'   | tail -1)
ts() { printf '%s' "$1" | awk '{print $2}'; }

echo "----"
if [ -z "$answer" ]; then
  echo "park-thread: NO COMMENT MATCHED THE ANSWER MARKERS. That is not the same as" \
       "'no answer' — an answer worded unlike the six known forms is invisible here." \
       "Read the thread above before you park or re-park."
  exit 1
fi
a=$(ts "$answer"); p=$(ts "$park")
if [ -n "$park" ] && [ "$a" != "NO-TIMESTAMP" ] && [ "$p" != "NO-TIMESTAMP" ] && [ "$p" \> "$a" ]; then
  echo "park-thread: ANSWERED at $a, then RE-PARKED at $p."
  echo "park-thread: THE NEWEST COMMENT IS NOT THE STATE. A re-park is written by an" \
       "agent; an answer is not. Read [$a] before you believe the tail of this" \
       "thread, and if you unpark, say so on the bead naming BOTH timestamps."
  exit 0
fi
echo "park-thread: ANSWER-SHAPED COMMENT at $a, nothing re-parked after it. Read it."
exit 0
