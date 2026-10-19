#!/usr/bin/env bash
# Tests for file-retro.sh. Stubs `bd` on PATH. Exits 0 if all cases pass.
set -uo pipefail
SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/file-retro.sh"}
ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/file-retro-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"
cat > "$ROOT/bin/bd" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1 $2" in
  "config get") cat "$ROOT/types" 2>/dev/null || echo "types.custom (not set)" ;;
  "config set") echo "$4" > "$ROOT/types" ;;
  create*) echo "computenet-r3tro"; [ -n "${BD_WARN:-}" ] && echo "Warning: export skipped" >&2; true ;;
esac
STUB
chmod +x "$ROOT/bin/bd"; export PATH="$ROOT/bin:$PATH" ROOT BD_LOG="$ROOT/bd.log"
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
good() { printf '## Summary\n- s\n\n## Outcomes\n- none\n\n## Issues\n%s\n\n## Judgment calls\n- none\n\n## Time\n- t\n\n## Handoff\n- none\n' "$1" > "$ROOT/r.md"; }

SHA=$(git -C "$(dirname "$SCRIPT")" rev-parse origin/main); BLOB=$(git -C "$(dirname "$SCRIPT")" hash-object "$SCRIPT")
good '- [flake] X flaked (evidence: seed 7)'
out=$("$SCRIPT" --skill work --file "$ROOT/r.md" --skill-version "${SHA:0:8}" 2>&1); st=$?
[ $st -eq 0 ] && [ "$out" = computenet-r3tro ] && ok "valid record files and prints the id" || bad "valid record: $st $out"
grep -q -- '--type=retro' "$BD_LOG" && ok "created as type retro" || bad "type"
grep -q "\"skill_version\":\"$SHA\"" "$BD_LOG" && ok "an abbreviated sha is stored in full" || bad "metadata"
: > "$BD_LOG"; "$SCRIPT" --skill work --file "$ROOT/r.md" >/dev/null 2>&1
grep -q "\"skill_version\":\"$SHA\"" "$BD_LOG" && ok "skill_version defaults to origin/main's sha" || bad "default sha"
: > "$BD_LOG"; "$SCRIPT" --skill work --file "$ROOT/r.md" --skill-version "$BLOB" >/dev/null 2>&1; st=$?
[ $st -eq 2 ] && ! grep -q create "$BD_LOG" && ok "a blob hash is refused, nothing filed" || bad "blob hash: $st"
grep -q recurs_of "$BD_LOG" && bad "empty recurs_of written" || ok "no recurs_of without the flag"
[ "$(cat "$ROOT/types")" = retro ] && ok "registers the retro type when missing" || bad "type registration"

: > "$BD_LOG"; echo "bug,retro" > "$ROOT/types"
"$SCRIPT" --skill work --file "$ROOT/r.md" >/dev/null 2>&1
grep -q 'config set' "$BD_LOG" && bad "re-registered an existing type" || ok "leaves an existing registration alone"

out=$(BD_WARN=1 "$SCRIPT" --skill work --file "$ROOT/r.md" 2>/dev/null); st=$?
[ $st -eq 0 ] && [ "$out" = computenet-r3tro ] && ok "a stderr warning after create is not a failure" || bad "warning after create: $st $out"

: > "$BD_LOG"; "$SCRIPT" --skill work --file "$ROOT/r.md" --recurs-of computenet-cz5c1 >/dev/null 2>&1
grep -q '"recurs_of":"computenet-cz5c1"' "$BD_LOG" && ok "--recurs-of reaches metadata" || bad "recurs-of"

good '- [other:dns] resolver down'
"$SCRIPT" --skill work --file "$ROOT/r.md" >/dev/null 2>&1 && ok "other:<word> accepted" || bad "other tag"

good '- flaky thing without a tag'
"$SCRIPT" --skill work --file "$ROOT/r.md" >/dev/null 2>&1; [ $? -eq 2 ] && ok "untagged issue refused" || bad "untagged issue"

printf '## Summary\n- s\n' > "$ROOT/r.md"; : > "$BD_LOG"
"$SCRIPT" --skill work --file "$ROOT/r.md" >/dev/null 2>&1; st=$?
[ $st -eq 2 ] && ! grep -q create "$BD_LOG" && ok "missing heading refused before any create" || bad "missing heading: $st"

echo "$pass passed, $fail failed"; [ $fail -eq 0 ]
