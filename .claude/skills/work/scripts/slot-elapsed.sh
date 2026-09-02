#!/bin/sh
# slot-elapsed.sh [scratch-dir] — the slot's elapsed reading, the rung it puts
# you on, and THE AGE OF THE PREVIOUS READING.
#
# The last field is the point. A same-turn reading is accurate at the instant
# of the tool call and then decays at a rate the session cannot perceive: the
# turn after a 50-minute stall looks identical from inside to the turn after a
# 30-second one. Measured 2026-08-29/30 on MacBoo: correct readings at 117m and
# at 144m, real elapsed 195m minutes later — ~78m and ~50m of between-turn wall
# clock, perceived as ~2m, each of which moved the session across a budget rung
# while it was reasoning about which side of it it was on (computenet-1lbs,
# recurrence of computenet-hs90 / computenet-776 — both prose fixes that made
# each reading true and did nothing about how fast a true reading goes stale).
# Printing the gap is what makes it perceivable: it lands in the same line the
# session copies into its report.
set -eu
SCRATCH="${1:-${SCRATCH:-}}"
[ -n "$SCRATCH" ] || { echo "slot-elapsed: no scratch dir (arg or \$SCRATCH)" >&2; exit 2; }
start=$(cat "$SCRATCH/slot-start") || exit 2
total=$(cat "$SCRATCH/slot-seconds") || exit 2
now=$(date -u +%s)

el=$(( (now - start) / 60 ))
tot=$(( total / 60 ))
left=$(( tot - el ))

# The last 15m of a slot is Finalize, so the WORK time left is 15m less than
# the wall-clock time left, and the rungs are measured against that. This is
# the one place the reserve is written down for the reading; the monitor's
# sleeps (SKILL.md step 2) and resume.md's re-arm offsets are the same three
# boundaries from the other end — slot-105m, slot-60m, slot-15m — and they
# already carried the reserve while this script did not. That 15m gap made the
# tier and the rung disagree at exactly 195m of a 300m slot, twice, with
# SKILL.md's tie-breaker handing the window to the permissive side
# (computenet-v8kg). Change one and change all three.
RESERVE=15
usable=$(( left - RESERVE ))

if   [ "$usable" -le 0  ]; then rung="EXPIRED — go to Finalize now"
elif [ "$usable" -le 45 ]; then rung="T-45m — no new dispatches; review and merge what is in flight"
elif [ "$usable" -le 90 ]; then rung="T-90m — finish the current feature; start no new one"
else                            rung="OPEN — new units allowed"
fi

last="$SCRATCH/slot-last-reading"
prev=$(cat "$last" 2>/dev/null || true)
case "$prev" in (*[!0-9]*|"") prev="" ;; esac
if [ -n "$prev" ]; then
  gap=$(( (now - prev) / 60 ))
  # The same kill that truncates the file can leave a DIGIT PREFIX, which the
  # guard above accepts: a truncated epoch yields a gap of ~29,800,000 minutes.
  # The discriminator is ABSURDITY, not the slot length — a gap exceeding the
  # slot is the documented host-suspension case (834m against a 300m slot,
  # computenet-3gf5), i.e. the very thing this field exists to show, and an
  # earlier draft of this line discarded it as a partial write.
  if [ "$gap" -lt 0 ] || [ "$gap" -gt 100000 ]; then gap=""; fi
  if [ -z "$gap" ]; then
    age="previous reading unusable (not a plausible time; treat as no reading)"
  elif [ "$gap" -ge 10 ]; then
    age="previous reading ${gap}m ago — ${gap}m of wall clock passed between turns"
  else
    age="previous reading ${gap}m ago"
  fi
else
  # Empty or non-numeric, not just absent: `echo "$now" > "$last"` truncates
  # before it writes, so a kill in that window leaves the file EMPTY — and an
  # empty `cat` SUCCEEDS, so a bare $(cat) would make the arithmetic a fatal
  # `operand expected` and suppress the elapsed line entirely. That is the very
  # failure this script exists to prevent, and the one SKILL.md already forbids
  # for the monitor: a fix that can silence the reading is worse than no fix.
  age="no previous reading (first this slot, or the file was unreadable)"
fi
# `|| true`: an unwritable last-reading (read-only dir, mode 000, disk full)
# must not take the elapsed line down with it under `set -e`. Same doctrine as
# the guard above — the auxiliary field never suppresses the primary reading.
{ echo "$now" > "$last"; } 2>/dev/null || true

echo "${el}m of ${tot}m elapsed, ${left}m left — rung: ${rung} — ${age}"
