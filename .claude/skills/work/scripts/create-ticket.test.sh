#!/usr/bin/env bash
# Tests for create-ticket.sh. Stubs `bd` on PATH. Exits 0 if all cases pass.
# Expect "12 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/create-ticket.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/create-ticket-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1" in
  create)
    case "$*" in *--parent*) echo "TEST-VIOLATION: create used --parent" >&2; exit 1 ;; esac
    [ -f "$CTRL/create-garbage" ] && { echo "not json"; exit 0; }
    echo '{"id":"computenet-h4sh"}' ;;
  update)
    case "$*" in
      *--parent*) [ -f "$CTRL/parent-fail" ] && exit 1 ;;
      *--claim*)  [ -f "$CTRL/claim-fail" ] && exit 1 ;;
    esac
    exit 0 ;;
esac
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log"
            mkdir -p "$CTRL"; : > "$BD_LOG"; }
run() { "$SCRIPT" --type bug --title "t" --parent computenet-wpvy "$@" 2>&1; }

# 1. the whole point: create never carries --parent, the attach is a separate update
fixture
out=$(run); st=$?
[ "$st" = 0 ] && [ "$(tail -1 <<<"$out")" = computenet-h4sh ] \
  && grep -q "^create" "$BD_LOG" && ! grep "^create" "$BD_LOG" | grep -q -- "--parent" \
  && grep -q -- "update computenet-h4sh --parent=computenet-wpvy" "$BD_LOG" \
  && ok "create is unparented, attach is a separate update" || bad "happy: exit=$st out=$out log=$(cat "$BD_LOG")"

# 2. no --claim: nothing is claimed
fixture
out=$(run); st=$?
[ "$st" = 0 ] && ! grep -q -- "--claim" "$BD_LOG" \
  && ok "claim is opt-in" || bad "no-claim: exit=$st log=$(cat "$BD_LOG")"

# 3. --claim claims after attaching
fixture
out=$(run --claim); st=$?
[ "$st" = 0 ] && grep -q -- "--claim" "$BD_LOG" \
  && ok "--claim claims" || bad "claim: exit=$st log=$(cat "$BD_LOG")"

# 4. labels and metadata reach the create
fixture
out=$(run --label skill-friction --label human --metadata '{"skill_version":"abc123"}'); st=$?
[ "$st" = 0 ] && grep -q -- "--label=skill-friction" "$BD_LOG" \
  && grep -q -- "--label=human" "$BD_LOG" && grep -q "skill_version.*abc123" "$BD_LOG" \
  && ok "labels and metadata passed through" || bad "passthrough: exit=$st log=$(cat "$BD_LOG")"

# 5. create returns non-JSON: exit 1, nothing parented
fixture; touch "$CTRL/create-garbage"
out=$(run); st=$?
[ "$st" = 1 ] && ! grep -q -- "--parent" "$BD_LOG" \
  && ok "garbage create output exits 1" || bad "garbage: exit=$st out=$out"

# 6. re-parent fails: exit 1 naming the recovery, so the hash-id bead is not lost
fixture; touch "$CTRL/parent-fail"
out=$(run); st=$?
[ "$st" = 1 ] && grep -q "bd update computenet-h4sh --parent=computenet-wpvy" <<<"$out" \
  && ok "failed re-parent names the recovery" || bad "parent: exit=$st out=$out"

# 7. claim fails: filed anyway, exit 0 with a note
fixture; touch "$CTRL/claim-fail"
out=$(run --claim); st=$?
[ "$st" = 0 ] && grep -q "unclaimed" <<<"$out" \
  && ok "failed claim is a note, not a failure" || bad "claim-fail: exit=$st out=$out"

# 8. --parent is mandatory: this script exists to attach
fixture
out=$("$SCRIPT" --type bug --title t 2>&1); st=$?
[ "$st" = 2 ] && ok "missing --parent exits 2" || bad "args: exit=$st out=$out"

# 7xeh: --top-level creates unparented and never calls update --parent
fixture
out=$("$SCRIPT" --type bug --title t --top-level 2>&1); st=$?
[ "$st" = 0 ] && [ "$out" = computenet-h4sh ] && ! grep -q -- '--parent' "$BD_LOG" \
  && ok "--top-level creates unparented" || bad "top-level: exit=$st out=$out log=$(cat "$BD_LOG")"
out=$("$SCRIPT" --type bug --title t --top-level --parent x 2>&1); st=$?
[ "$st" = 2 ] && ok "--top-level and --parent are exclusive" || bad "exclusive: exit=$st out=$out"

# s5dh: --desc-file passes backticks and $(...) through inert
printf 'body with `backticks` and $(echo NO)\n' > "$CTRL/body.txt"
out=$("$SCRIPT" --type bug --title t --parent p --desc-file "$CTRL/body.txt" 2>&1); st=$?
[ "$st" = 0 ] && grep -qF -- '`backticks` and $(echo NO)' "$BD_LOG" \
  && ok "--desc-file body reaches bd verbatim" || bad "desc-file: exit=$st log=$(cat "$BD_LOG")"
out=$("$SCRIPT" --type bug --title t --parent p --desc-file "$CTRL/missing.txt" 2>&1); st=$?
[ "$st" = 2 ] && ok "missing --desc-file is exit 2" || bad "missing file: exit=$st out=$out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
