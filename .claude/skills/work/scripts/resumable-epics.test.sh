#!/usr/bin/env bash
# Tests for resumable-epics.sh. Stubs `bd` and `epic-of.sh` on PATH.
# Exits 0 if all cases pass.
set -uo pipefail
SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/resumable-epics.sh"}
ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/resumable-epics-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
PASS=0 FAIL=0
ok()  { echo "  PASS $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL $1"; FAIL=$((FAIL+1)); }

# The script calls epic-of.sh by its own dirname, so both stubs live beside a
# copy of the script rather than on PATH.
BIN=$ROOT/bin; mkdir -p "$BIN"; cp "$SCRIPT" "$BIN/resumable-epics.sh"
cat > "$BIN/bd" <<'EOF'
#!/usr/bin/env bash
cat "$FEATURES"
EOF
cat > "$BIN/epic-of.sh" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  unresolvable) echo "(no such id: $1)"; exit 1 ;;
  orphan)       echo "(unparented)"; exit 0 ;;
  *)            echo "epic-of-$1"; exit 0 ;;
esac
EOF
chmod +x "$BIN/bd" "$BIN/epic-of.sh"
run() { PATH="$BIN:$PATH" FEATURES=$1 "$BIN/resumable-epics.sh" 2>"$ROOT/err"; }

# 1. two features under distinct epics
echo '[{"id":"a"},{"id":"b"}]' > "$ROOT/f1"
[ "$(run "$ROOT/f1" | jq -c .)" = '["epic-of-a","epic-of-b"]' ] \
  && ok "one epic per feature" || bad "distinct: $(run "$ROOT/f1")"

# 2. two features under ONE epic dedupe to a single entry
cat > "$BIN/epic-of.sh" <<'EOF'
#!/usr/bin/env bash
echo same
EOF
chmod +x "$BIN/epic-of.sh"
[ "$(run "$ROOT/f1" | jq -c .)" = '["same"]' ] \
  && ok "same epic twice dedupes" || bad "dedupe: $(run "$ROOT/f1")"

# 3. no in-progress features -> empty array, not an error
cat > "$BIN/epic-of.sh" <<'EOF'
#!/usr/bin/env bash
echo "epic-of-$1"
EOF
chmod +x "$BIN/epic-of.sh"
echo '[]' > "$ROOT/f2"
out=$(run "$ROOT/f2"); st=$?
[ "$st" = 0 ] && [ "$(jq -c . <<<"$out")" = '[]' ] \
  && ok "no resumables is an empty array, exit 0" || bad "empty: exit=$st out=$out"

# 4. an unresolvable epic is SKIPPED and noted, never guessed
cat > "$BIN/epic-of.sh" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  unresolvable) echo "(no such id: $1)"; exit 1 ;;
  orphan)       echo "(unparented)"; exit 0 ;;
  *)            echo "epic-of-$1"; exit 0 ;;
esac
EOF
chmod +x "$BIN/epic-of.sh"
echo '[{"id":"a"},{"id":"unresolvable"},{"id":"orphan"}]' > "$ROOT/f3"
out=$(run "$ROOT/f3")
[ "$(jq -c . <<<"$out")" = '["epic-of-a"]' ] \
  && grep -q "skipped unresolvable" "$ROOT/err" && grep -q "skipped orphan" "$ROOT/err" \
  && ok "unresolved and unparented are skipped with a note" \
  || bad "skip: out=$out err=$(cat "$ROOT/err")"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
