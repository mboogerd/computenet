#!/usr/bin/env bash
# Tests for epic-of.sh. Self-contained: stubs `bd` on PATH with JSON fixtures.
# Exits 0 if all cases pass. Expect "6 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/epic-of.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/epic-of-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
export FIXTURES="$ROOT/fx"; mkdir -p "$FIXTURES" "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
[ "$1" = show ] || { echo "unexpected: $*" >&2; exit 1; }
f="$FIXTURES/$2.json"
[ -f "$f" ] && cat "$f" || { echo "Error: issue not found: $2" >&2; exit 1; }
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"

fx() { printf '%s\n' "$2" > "$FIXTURES/$1.json"; }
fx computenet-e    '[{"id":"computenet-e","issue_type":"epic"}]'
fx computenet-e.1  '[{"id":"computenet-e.1","issue_type":"task"}]'
fx computenet-o    '[{"id":"computenet-o","issue_type":"epic"}]'
fx computenet-o.3  '[{"id":"computenet-o.3","issue_type":"feature"}]'
fx computenet-o.6  '[{"id":"computenet-o.6","issue_type":"task","parent":"computenet-o.3"}]'
fx computenet-lone '[{"id":"computenet-lone","issue_type":"bug"}]'
fx computenet-a    '[{"id":"computenet-a","issue_type":"task","parent":"computenet-b"}]'
fx computenet-b    '[{"id":"computenet-b","issue_type":"task","parent":"computenet-a"}]'

pass=0; fail=0
check() { # check <id> <expected-output> <expected-exit> <label>
  out=$("$SCRIPT" "$1" 2>&1); st=$?
  if [ "$out" = "$2" ] && [ "$st" = "$3" ]; then pass=$((pass+1)); echo "  PASS $4"
  else fail=$((fail+1)); echo "  FAIL $4 -- got '$out' (exit $st), want '$2' (exit $3)"; fi
}

check computenet-e.1  computenet-e            0 "dotted prefix resolves to the epic"
check computenet-o.6  computenet-o            0 "explicit parent (via a feature) overrides the prefix"
check computenet-lone "(unparented)"          0 "unparented non-epic is (unparented), exit 0"
check computenet-e    computenet-e            0 "an epic resolves to itself"
check computenet-nope "(no such id: computenet-nope)" 1 "missing id is unresolved, never unparented"
check computenet-a    "(cycle? computenet-a)" 1 "a parent cycle is unresolved"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
