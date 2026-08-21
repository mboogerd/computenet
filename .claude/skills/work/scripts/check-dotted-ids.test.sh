#!/usr/bin/env bash
# Tests for check-dotted-ids.sh. Stubs `bd` on PATH. Expect "12 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-dotted-ids.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/dotted-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  list)
    case "$*" in
      *--label=owner:*) cat "$CTRL/mine.json" 2>/dev/null || echo '[]' ;;
      *) cat "$CTRL/list.json" ;;
    esac ;;
  show) printf '[{"id":"%s","assignee":"%s","parent":"%s"}]\n' "$2" "$(cat "$CTRL/owner.$2" 2>/dev/null)" "$(cat "$CTRL/parent.$2" 2>/dev/null)" ;;
esac
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE"; mkdir -p "$CTRL"
            echo '[]' > "$CTRL/list.json"; echo '[]' > "$CTRL/mine.json"
            export BEADS_ACTOR=MacBoo; }

# 1. dotted child of an epic held by someone else: flagged, still exit 0
fixture
echo '[{"id":"computenet-wpvy.47","created_by":"MacBoo"}]' > "$CTRL/list.json"
echo Anva@A0030 > "$CTRL/owner.computenet-wpvy"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && grep -q "computenet-wpvy.47" <<<"$out" && grep -q "azt" <<<"$out" \
  && ok "foreign-parent dotted id is flagged, exit 0" || bad "flag: exit=$st out=$out"

# 2. unclaimed parent counts as shared
fixture
echo '[{"id":"computenet-wpvy.47","created_by":"MacBoo"}]' > "$CTRL/list.json"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && grep -q "held by nobody" <<<"$out" \
  && ok "unclaimed parent is flagged" || bad "unclaimed: exit=$st out=$out"

# 3. dotted child of a parent WE hold: silent (exclusive by claim)
fixture
echo '[{"id":"computenet-k9d.2","created_by":"MacBoo"}]' > "$CTRL/list.json"
echo MacBoo > "$CTRL/owner.computenet-k9d"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ -z "$out" ] \
  && ok "own claimed parent is silent" || bad "own: exit=$st out=$out"

# 3b. grandchild under a feature with NO assignee, whose EPIC we hold: silent
fixture
echo '[{"id":"computenet-ssa.4.1","created_by":"MacBoo"}]' > "$CTRL/list.json"
echo '[{"id":"computenet-ssa"}]' > "$CTRL/mine.json"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ -z "$out" ] \
  && ok "unassigned feature under our epic is silent" || bad "effective epic: exit=$st out=$out"

# 3c. explicit .parent overrides the dotted prefix on the walk up
fixture
echo '[{"id":"computenet-oxv.6.2","created_by":"MacBoo"}]' > "$CTRL/list.json"
echo computenet-k9d > "$CTRL/parent.computenet-oxv.6"
echo MacBoo > "$CTRL/owner.computenet-k9d"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ -z "$out" ] \
  && ok "explicit parent chain to our epic is silent" || bad "explicit parent: exit=$st out=$out"

# 4. hash ids are never flagged
fixture
echo '[{"id":"computenet-h4sh","created_by":"MacBoo"},{"id":"computenet-9opz","created_by":"MacBoo"}]' > "$CTRL/list.json"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ -z "$out" ] \
  && ok "hash ids ignored" || bad "hash: exit=$st out=$out"

# 5. no BEADS_ACTOR: skip, but SAY so — a silently dead backstop is the worst case
fixture
echo '[{"id":"computenet-wpvy.47","created_by":"MacBoo"}]' > "$CTRL/list.json"
out=$(env -u BEADS_ACTOR "$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && grep -q "BEADS_ACTOR is unset" <<<"$out" && ! grep -q "wpvy.47" <<<"$out" \
  && ok "no BEADS_ACTOR skips audibly" || bad "actor: exit=$st out=$out"

# 6. empty window: silent
fixture
out=$("$SCRIPT" --days 1 2>&1); st=$?
[ "$st" = 0 ] && [ -z "$out" ] \
  && ok "nothing recent is silent" || bad "empty: exit=$st out=$out"

# 7. the object-shaped list (--skip-labels returns {"issues":[...]}, not an array)
fixture
echo '{"issues":[{"id":"computenet-wpvy.47","created_by":"MacBoo"}],"meta":{}}' > "$CTRL/list.json"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && grep -q "computenet-wpvy.47" <<<"$out" \
  && ok "handles the {issues:[...]} shape" || bad "shape: exit=$st out=$out"

# 8. parent carries owner:<actor> but the claim was released: still ours, silent
fixture
echo '[{"id":"computenet-k9d.2","created_by":"MacBoo"}]' > "$CTRL/list.json"
echo '[{"id":"computenet-k9d"}]' > "$CTRL/mine.json"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ -z "$out" ] \
  && ok "released claim still counts as ours via owner: label" || bad "released: exit=$st out=$out"

# 9. owner: membership is a whole-line match, not a prefix
fixture
echo '[{"id":"computenet-7em.1.6","created_by":"MacBoo"}]' > "$CTRL/list.json"
echo '[{"id":"computenet-7em.1.6.2"}]' > "$CTRL/mine.json"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && grep -q "computenet-7em.1.6" <<<"$out" \
  && ok "owner: match is whole-line, not prefix" || bad "prefix: exit=$st out=$out"

# 10. the other machine's dotted creates are not ours to fix: silent
fixture
echo '[{"id":"computenet-wpvy.48","created_by":"Anva@A0030"}]' > "$CTRL/list.json"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ -z "$out" ] \
  && ok "another machine's dotted create is silent" || bad "foreign-create: exit=$st out=$out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
