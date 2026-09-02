#!/usr/bin/env bash
# Tests for slot-elapsed.sh. Fabricates slot-start/slot-seconds so every rung
# and every gap case runs deterministically. Expect "31 passed, 0 failed".
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

# rung boundaries, exactly on the edge
d=$(slot 210 300); says "$d" "rung: T-90m"   "left == 90 is T-90m, not OPEN"
d=$(slot 255 300); says "$d" "rung: T-45m"   "left == 45 is T-45m, not T-90m"
d=$(slot 300 300); says "$d" "rung: EXPIRED" "left == 0 is EXPIRED"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
