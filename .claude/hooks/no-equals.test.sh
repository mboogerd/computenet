#!/usr/bin/env bash
# Tests for no-equals.sh. Exits 0 if all pass, 1 otherwise.
set -uo pipefail
HOOK=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/no-equals.sh"}
[ -r "$HOOK" ] || { echo "not readable: $HOOK" >&2; exit 1; }
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
run() { printf '%s' "$1" | bash "$HOOK"; }

out=$(run '{"tool_input":{"command":"echo before; echo ===; echo after","timeout":5000,"description":"d"}}')
[ "$(jq -r .hookSpecificOutput.updatedInput.command <<<"$out")" = 'setopt noequals 2>/dev/null; echo before; echo ===; echo after' ] && ok "rewrites =-initial word" || bad "rewrite: $out"
[ "$(jq -r .hookSpecificOutput.updatedInput.timeout <<<"$out")" = 5000 ] && [ "$(jq -r .hookSpecificOutput.updatedInput.description <<<"$out")" = d ] && ok "merges into tool_input" || bad "tool_input not merged: $out"
[ "$(jq -r .hookSpecificOutput.hookEventName <<<"$out")" = PreToolUse ] && ok "event name" || bad "event name"

out=$(run '{"tool_input":{"command":"git log --oneline; FOO=bar env | grep a=b"}}')
[ -z "$out" ] && ok "pass-through emits nothing" || bad "pass-through emitted: $out"
out=$(run '{"tool_input":{}}'); [ -z "$out" ] && ok "no command passes through" || bad "empty: $out"
out=$(run 'not json'); [ -z "$out" ] && ok "malformed payload passes through" || bad "malformed: $out"

echo "$pass passed, $fail failed"; [ "$fail" -eq 0 ]
