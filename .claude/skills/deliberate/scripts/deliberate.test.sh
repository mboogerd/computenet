#!/usr/bin/env bash
# Plumbing test for deliberate.py: canned members, no network.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd); t=$(mktemp -d); fail=0
printf '# Brief\nShould we keep it?\n' > "$t/brief.md"
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }

"$here/deliberate.py" --brief "$t/brief.md" --out "$t/agree" --dry-run >/dev/null 2>&1
check "default fixture decides A" "head -1 '$t/agree/verdict.md' | grep -q '^DECIDED: A'"
check "deciding factors kept"    "grep -q '^- f' '$t/agree/verdict.md'"
check "case and attack merged"   "jq -e '.options[0].case==\"text A\" and .options[0].attack==\"text A\"' '$t/agree/verdict.json' >/dev/null"
check "jev saw case and attack"  "jq -e '.state.options.A.case_against==\"text A\"' '$t/agree/vote.jev.request.json' >/dev/null"
check "every prompt kept"        "[ \$(ls '$t/agree'/*.prompt.md | wc -l) -ge 7 ]"

dry() { "$here/deliberate.py" --brief "$t/brief.md" --out "$t/$1" --dry-run >/dev/null 2>&1; }
# Acceptability fixtures (computenet-hngr6): scores per member per option, Jev in 0-100.
DELIBERATE_DRY_SCORES='{"opus":{"A":75,"B":90},"sol":{"A":80,"B":85},"jev":{"A":70,"B":80}}' dry pass
check "pass: highest passing mean decides"  "head -1 '$t/pass/verdict.md' | grep -q '^DECIDED: B'"
check "pass: no objections recorded"        "! grep -q '^Objections' '$t/pass/verdict.md'"
DELIBERATE_DRY_SCORES='{"opus":{"A":95,"B":72},"sol":{"A":95,"B":75},"jev":{"A":55,"B":70}}' dry veto
check "veto: a score <60 blocks the top mean" "head -1 '$t/veto/verdict.md' | grep -q '^DECIDED: B'"
check "veto: jev's objection to A recorded"  "grep -q '^- A, jev (55): ' '$t/veto/verdict.md'"
check "veto: A marked not passing"           "jq -e '.tally[0].passes==false and .tally[0].mean>=70' '$t/veto/verdict.json' >/dev/null"
DELIBERATE_DRY_SCORES='{"opus":{"A":90,"B":80},"sol":{"A":90,"B":80},"jev":{"A":60,"B":80}}' dry tie
check "tie: equal means go to higher minimum" "head -1 '$t/tie/verdict.md' | grep -q '^DECIDED: B'"
DELIBERATE_DRY_SCORES='{"opus":{"A":90,"B":40},"sol":{"A":50,"B":90},"jev":{"A":80,"B":80}}' dry nopass
check "no pass: NO-CONSENSUS"                "head -1 '$t/nopass/verdict.md' | grep -q '^NO-CONSENSUS'"
check "no pass: no choice"                   "jq -e '.choice==null' '$t/nopass/verdict.json' >/dev/null"
check "no pass: every objection listed"      "grep -q '^- A, sol (50): sol objects to A' '$t/nopass/verdict.md' && grep -q '^- B, opus (40): opus objects to B' '$t/nopass/verdict.md'"
check "jev asked one noul per option"        "jq -e '.questions.A.type==\"noul\" and .questions.B.type==\"noul\"' '$t/nopass/vote.jev.request.json' >/dev/null"
DELIBERATE_DRY_MISKEY=1 "$here/deliberate.py" --brief "$t/brief.md" --out "$t/miskey" --dry-run >/dev/null 2>&1; st=$?
check "option without a case aborts" "[ $st -ne 0 ] && [ ! -e '$t/miskey/verdict.md' ]"
DELIBERATE_DRY_UNSCORED=1 "$here/deliberate.py" --brief "$t/brief.md" --out "$t/unscored" --dry-run >/dev/null 2>&1; st=$?
check "an unscored option aborts"    "[ $st -ne 0 ] && [ ! -e '$t/unscored/verdict.md' ]"
DELIBERATE_DRY_MESSY=1 "$here/deliberate.py" --brief "$t/brief.md" --out "$t/messy" --dry-run >/dev/null 2>&1
check "'Option a' / '80%' / '80-90' normalise" "jq -e '.votes.sol.scores.A.score==80 and .votes.sol.scores.B.score==80' '$t/messy/verdict.json' >/dev/null"
nid() { python3 -B -c "import sys; sys.path.insert(0, '$here'); import deliberate as d; print(d.norm_id(sys.argv[1], ['A','B','C']))" "$1"; }
check "'B: Change' is B"                "[ \"\$(nid 'B: Change')\" = 'B' ]"
check "'A or B' maps to no option"      "[ \"\$(nid 'A or B')\" = 'None' ]"
check "article 'a' is not option A"     "[ \"\$(nid 'go with a staged rollout')\" = 'None' ]"
rm -rf "$t"; exit $fail
