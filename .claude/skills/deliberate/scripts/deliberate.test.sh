#!/usr/bin/env bash
# Plumbing test for deliberate.py: canned members, no network.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd); t=$(mktemp -d); fail=0
printf '# Brief\nShould we keep it?\n' > "$t/brief.md"
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }

"$here/deliberate.py" --brief "$t/brief.md" --out "$t/agree" --dry-run >/dev/null 2>&1
check "agreement decides"        "head -1 '$t/agree/verdict.md' | grep -q '^DECIDED: A'"
check "deciding factors kept"    "grep -q '^- f' '$t/agree/verdict.md'"
check "case and attack merged"   "jq -e '.options[0].case==\"text A\" and .options[0].attack==\"text A\"' '$t/agree/verdict.json' >/dev/null"
check "jev saw case and attack"  "jq -e '.questions.vote.criteria.A.case_against==\"text A\"' '$t/agree/vote.jev.request.json' >/dev/null"
check "every prompt kept"        "[ \$(ls '$t/agree'/*.prompt.md | wc -l) -ge 7 ]"

DELIBERATE_DRY_SPLIT=1 "$here/deliberate.py" --brief "$t/brief.md" --out "$t/split" --dry-run >/dev/null 2>&1
check "split vote is no-consensus" "head -1 '$t/split/verdict.md' | grep -q '^NO-CONSENSUS'"
check "no-consensus has no choice" "jq -e '.choice==null' '$t/split/verdict.json' >/dev/null"
DELIBERATE_DRY_MISKEY=1 "$here/deliberate.py" --brief "$t/brief.md" --out "$t/miskey" --dry-run >/dev/null 2>&1; st=$?
check "option without a case aborts" "[ $st -ne 0 ] && [ ! -e '$t/miskey/verdict.md' ]"
DELIBERATE_DRY_MESSY=1 "$here/deliberate.py" --brief "$t/brief.md" --out "$t/messy" --dry-run >/dev/null 2>&1
check "'Option a' / '80%' normalise to A / 80" "head -1 '$t/messy/verdict.md' | grep -q '^DECIDED: A'"
norm() { python3 -B -c "import sys; sys.path.insert(0, '$here'); import deliberate as d; x={'choice': sys.argv[1], 'confidence': sys.argv[2]}; d.norm_vote(x, ['A','B','C']); print(x['choice'], x['confidence'])" "$1" "$2"; }
check "'B: Change' is B"                "[ \"\$(norm 'B: Change' 70)\" = 'B 70' ]"
check "'A or B' stays unmapped"         "[ \"\$(norm 'A or B' 70)\" = 'A or B 70' ]"
check "article 'a' is not option A"     "[ \"\$(norm 'go with a staged rollout' 70)\" = 'go with a staged rollout 70' ]"
check "'80-90' is 80, not 8090"         "[ \"\$(norm A 80-90)\" = 'A 80' ]"
rm -rf "$t"; exit $fail
