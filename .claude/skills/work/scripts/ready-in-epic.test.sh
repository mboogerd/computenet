#!/usr/bin/env bash
# Tests for ready-in-epic.sh. Stubs `bd`. Expect "9 passed, 0 failed".
#
# The load-bearing case is DEPTH: bd ready --parent reaches direct children
# only, and this script exists because of that (computenet-28vn). Case 3 is the
# regression that matters — a great-grandchild must be found.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ready-in-epic.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/ready-epic-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "Warning: beads.role not set"        # bd prints warnings BEFORE the JSON
case "$1" in
  ready) cat "$CTRL/ready.json" ;;
  list)  cat "$CTRL/all.json" ;;
esac
[ -n "${BD_FAIL:-}" ] && exit 1 || exit 0
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH" CTRL="$ROOT"

# e (epic) -> e.1 (feature) -> e.1.2 (task) -> e.1.2.3 (task, READY)
# plus e-flat: an explicit-parent child of e with no dot in its id
# plus loose: genuinely unparented
cat > "$ROOT/all.json" <<'EOF'
[{"id":"computenet-e","issue_type":"epic","parent":null},
 {"id":"computenet-e.1","issue_type":"feature","parent":null},
 {"id":"computenet-e.1.2","issue_type":"task","parent":null},
 {"id":"computenet-e.1.2.3","issue_type":"task","parent":null},
 {"id":"computenet-eflat","issue_type":"task","parent":"computenet-e"},
 {"id":"computenet-other","issue_type":"epic","parent":null},
 {"id":"computenet-other.1","issue_type":"task","parent":null},
 {"id":"computenet-loose","issue_type":"task","parent":null}]
EOF
cat > "$ROOT/ready.json" <<'EOF'
[{"id":"computenet-e.1.2.3","priority":2,"title":"great-grandchild"},
 {"id":"computenet-eflat","priority":1,"title":"explicit parent, no dot"},
 {"id":"computenet-other.1","priority":2,"title":"another epic"},
 {"id":"computenet-loose","priority":3,"title":"unparented"},
 {"id":"computenet-ghost","priority":3,"title":"not in the listing at all"}]
EOF

pass=0; fail=0
ck() { local n=$1 want=$2 got=$3; if [[ "$got" == *"$want"* ]]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $n — wanted <$want> in <$got>"; fi; }
nk() { local n=$1 bad=$2 got=$3; if [[ "$got" != *"$bad"* ]]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $n — did not want <$bad> in <$got>"; fi; }

out=$("$SCRIPT" computenet-e --ids-only 2>/dev/null); rc=$?
ck "great-grandchild is found (the whole point)" "computenet-e.1.2.3" "$out"
ck "explicit-parent child with no dot is found" "computenet-eflat" "$out"
nk "another epic's item is excluded" "computenet-other.1" "$out"
nk "unparented item is excluded" "computenet-loose" "$out"
ck "exit 0" "0" "$rc"

err=$("$SCRIPT" computenet-e --ids-only 2>&1 >/dev/null)
ck "an id absent from the listing is reported, not silently dropped" "computenet-ghost" "$err"
nk "a genuinely unparented item is NOT reported as unresolved" "computenet-loose" "$err"

out=$("$SCRIPT" computenet-other --ids-only 2>/dev/null)
ck "scopes to the epic asked for" "computenet-other.1" "$out"

BD_FAIL=1 out=$(BD_FAIL=1 "$SCRIPT" computenet-e 2>&1); rc=$?
ck "a failed query exits 3, never an empty epic" "3" "$rc"

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
