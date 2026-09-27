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
rm -rf "$t"; exit $fail
