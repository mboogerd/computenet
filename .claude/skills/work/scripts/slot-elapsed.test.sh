#!/usr/bin/env bash
# Tests for slot-elapsed.sh. Fabricates slot-start/slot-seconds so every rung
# and every gap case runs deterministically. Expect "37 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/slot-elapsed.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/slot-elapsed-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }

# slot <elapsed-minutes> <slot-minutes> — a fresh scratch dir at that offset
slot() {
  local d; d=$(mktemp -d "$ROOT/s.XXXXXX")
  echo $(( $(date -u +%s) - $1 * 60 )) > "$d/slot-start"
  echo $(( $2 * 60 ))                  > "$d/slot-seconds"
  echo "$d"
}
says() { # scratch expected-substring label
  local out; out=$("$SCRIPT" "$1" 2>&1)
  case "$out" in *"$2"*) ok "$3" ;; *) bad "$3 — got: $out" ;; esac
}

d=$(slot 10 300);  says "$d" "10m of 300m elapsed, 290m left" "reports elapsed, total and remaining"
d=$(slot 10 300);  says "$d" "rung: OPEN"     "far from the end is OPEN"
d=$(slot 215 300); says "$d" "rung: T-90m"    "85m left is the T-90m rung"
d=$(slot 260 300); says "$d" "rung: T-45m"    "40m left is the T-45m rung"
d=$(slot 305 300); says "$d" "rung: EXPIRED"  "past the slot is EXPIRED"

# v8kg: the rung boundaries are the monitor's tiers, exactly. The monitor
# sleeps 11700/2700/2700 = fires at 195m/240m/285m of a 300m slot, and
# resume.md re-arms at slot-105m/-60m/-15m; this grid pins slot-elapsed.sh to
# the same three instants, one minute either side of each. They are ABSOLUTE
# offsets from the slot end, not fractions of the slot: T-90m names 90 minutes
# of work time, which does not shrink because the slot is shorter. Hence the
# 180m rows at 75/120/165, not at the same fractions as the 300m rows.
d=$(slot 194 300); says "$d" "rung: OPEN"     "194m of 300m is still OPEN"
d=$(slot 195 300); says "$d" "rung: T-90m"    "195m of 300m is T-90m — the monitor's first tier"
d=$(slot 196 300); says "$d" "rung: T-90m"    "196m of 300m is T-90m"
d=$(slot 239 300); says "$d" "rung: T-90m"    "239m of 300m is still T-90m"
d=$(slot 240 300); says "$d" "rung: T-45m"    "240m of 300m is T-45m — the monitor's second tier"
d=$(slot 241 300); says "$d" "rung: T-45m"    "241m of 300m is T-45m"
d=$(slot 284 300); says "$d" "rung: T-45m"    "284m of 300m is still T-45m"
d=$(slot 285 300); says "$d" "rung: EXPIRED"  "285m of 300m is EXPIRED — the monitor's third tier"
d=$(slot 286 300); says "$d" "rung: EXPIRED"  "286m of 300m is EXPIRED"
d=$(slot 74 180);  says "$d" "rung: OPEN"     "74m of 180m is still OPEN"
d=$(slot 75 180);  says "$d" "rung: T-90m"    "75m of 180m is T-90m"
d=$(slot 120 180); says "$d" "rung: T-45m"    "120m of 180m is T-45m"
d=$(slot 165 180); says "$d" "rung: EXPIRED"  "165m of 180m is EXPIRED"

# the point of the script: the gap between readings
d=$(slot 10 300)
says "$d" "no previous reading" "the first call has no previous reading"
"$SCRIPT" "$d" >/dev/null                       # second call, no time passed
says "$d" "previous reading 0m ago" "a back-to-back call reports a 0m gap"

d=$(slot 200 300)
echo $(( $(date -u +%s) - 50 * 60 )) > "$d/slot-last-reading"
says "$d" "50m of wall clock passed between turns" "a 50m between-turn gap is called out"

# computenet-gsm6: the gap alone is not the signal — WHICH RUNG IT CROSSED is.
# The 2026-09-04 case: a 180m boundary landing at 271m of a 300m slot, with a
# silent monitor, so nothing else could have said it: OPEN -> T-45m in one turn.
d=$(slot 271 300)
echo $(( $(date -u +%s) - 180 * 60 )) > "$d/slot-last-reading"
says "$d" "crossed a rung UNOBSERVED (OPEN -> T-45m)" "a gap that crosses rungs says which ones"
d=$(slot 271 300)
echo $(( $(date -u +%s) - 180 * 60 )) > "$d/slot-last-reading"
says "$d" "WARNING" "an unobserved crossing is loud, not a field to compute from"
# a long gap that stays on ONE rung is not a crossing and must not cry wolf
d=$(slot 60 300)
echo $(( $(date -u +%s) - 40 * 60 )) > "$d/slot-last-reading"
out=$("$SCRIPT" "$d" 2>&1)
case "$out" in *WARNING*) bad "a 40m gap inside OPEN must not warn — got: $out" ;;
                       *) ok "a long gap that crosses no rung does not warn" ;; esac
# a gap of an hour or more warns even when it crosses NO rung: it spends a
# rung's worth of budget either way (10m -> 190m of a 300m slot leaves 95m of
# work time, five minutes short of T-90m, and the rung name never changed).
d=$(slot 190 300)
echo $(( $(date -u +%s) - 180 * 60 )) > "$d/slot-last-reading"
says "$d" "an hour or more passed UNOBSERVED" "a 180m gap inside one rung still warns"
# the rounding SHAPE must match `usable`'s, or the warning goes silent within a
# minute of every boundary: prev-start = 194m30s is OPEN (usable 91), and a
# single-floor-over-the-difference computation calls it T-90m and says nothing.
d=$(slot 205 300)
echo $(( $(date -u +%s) - 10 * 60 - 30 )) > "$d/slot-last-reading"
says "$d" "crossed a rung UNOBSERVED (OPEN -> T-90m)" "a non-whole-minute previous reading still detects the crossing"
# ...and a gap under the 10m reporting floor never reaches the crossing check
d=$(slot 195 300)
echo $(( $(date -u +%s) - 2 * 60 )) > "$d/slot-last-reading"
out=$("$SCRIPT" "$d" 2>&1)
case "$out" in *WARNING*) bad "a 2m gap must not warn — got: $out" ;;
                       *) ok "a sub-10m gap does not warn even at a rung edge" ;; esac

# The auxiliary age field must never suppress the primary elapsed reading: an
# empty last-reading file is reachable (the write truncates first) and an empty
# `cat` succeeds, so a bare $(cat) is a fatal operand-expected there.
# (a fresh dir per assertion: the script REWRITES last-reading on every call)
for junk in "" "junk"; do
  d=$(slot 10 300); printf '%s' "$junk" > "$d/slot-last-reading"
  says "$d" "10m of 300m elapsed" "an unreadable last-reading ('$junk') still prints the elapsed line"
  d=$(slot 10 300); printf '%s' "$junk" > "$d/slot-last-reading"
  says "$d" "no previous reading" "an unreadable last-reading ('$junk') is reported, not computed on"
done

# an unwritable last-reading must not take the elapsed line down with it
d=$(slot 10 300); : > "$d/slot-last-reading"; chmod 000 "$d/slot-last-reading"
says "$d" "10m of 300m elapsed" "an unwritable last-reading still prints the elapsed line"
chmod 644 "$d/slot-last-reading"

# a partial write leaves a DIGIT PREFIX, which is numeric and absurd
d=$(slot 10 300); echo 1787 > "$d/slot-last-reading"
says "$d" "previous reading unusable" "a truncated epoch is not reported as a gap"

# ...but a gap LARGER THAN THE SLOT is the documented host-suspension case
# (834m against a 300m slot, computenet-3gf5) — the very thing this field
# exists to show. It must be reported, not discarded.
d=$(slot 834 300); echo $(( $(date -u +%s) - 800 * 60 )) > "$d/slot-last-reading"
says "$d" "800m of wall clock passed between turns" "a suspension gap beyond the slot is still reported"

# rung boundaries, exactly on the edge — the edges are the WORK time left, so
# with the 15m Finalize reserve they sit at left == 105 / 60 / 15, not at
# 90 / 45 / 0. The grid above pins them; these three are the interior rows.
d=$(slot 210 300); says "$d" "rung: T-90m"   "left == 90 is inside T-90m"
d=$(slot 255 300); says "$d" "rung: T-45m"   "left == 45 is inside T-45m"
d=$(slot 300 300); says "$d" "rung: EXPIRED" "left == 0 is EXPIRED"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
