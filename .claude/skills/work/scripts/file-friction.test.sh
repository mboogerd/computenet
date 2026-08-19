#!/usr/bin/env bash
# Tests for file-friction.sh. Stubs `bd` on PATH. Exits 0 if all cases pass.
# Expect "10 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/file-friction.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/file-friction-test.XXXXXX")" && pwd -P)
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
run() { "$SCRIPT" --type bug --title "t" --desc "d" --accept "a" --skill-version abc123 2>&1; }

# 1. happy path: unparented create, then re-parent, then claim
fixture
out=$(run); st=$?
[ "$st" = 0 ] && [ "$(tail -1 <<<"$out")" = computenet-h4sh ] \
  && grep -q "^create" "$BD_LOG" && grep -q -- "--parent=computenet-wpvy" "$BD_LOG" \
  && grep -q -- "--claim" "$BD_LOG" && grep -q "skill_version.*abc123" "$BD_LOG" \
  && ok "create -> parent -> claim, id printed" || bad "happy: exit=$st out=$out log=$(cat "$BD_LOG")"

# 2. create returns non-JSON: exit 1, nothing parented
fixture; touch "$CTRL/create-garbage"
out=$(run); st=$?
[ "$st" = 1 ] && ! grep -q -- "--parent" "$BD_LOG" \
  && ok "garbage create output exits 1" || bad "garbage: exit=$st out=$out"

# 3. re-parent fails: exit 1 with the recovery command
fixture; touch "$CTRL/parent-fail"
out=$(run); st=$?
[ "$st" = 1 ] && grep -q "bd update computenet-h4sh --parent=" <<<"$out" \
  && ok "failed re-parent names the recovery" || bad "parent: exit=$st out=$out"

# 4. claim fails: filed anyway, exit 0 with a note
fixture; touch "$CTRL/claim-fail"
out=$(run); st=$?
[ "$st" = 0 ] && grep -q "unclaimed" <<<"$out" \
  && ok "failed claim is a note, not a failure" || bad "claim: exit=$st out=$out"

# 5. missing required arg
fixture
out=$("$SCRIPT" --type bug --title t --desc d 2>&1); st=$?
[ "$st" = 2 ] && ok "missing --accept exits 2" || bad "args: exit=$st out=$out"

# 6. invalid type
fixture
out=$("$SCRIPT" --type chore --title t --desc d --accept a 2>&1); st=$?
[ "$st" = 2 ] && ok "type other than bug|feature exits 2" || bad "type: exit=$st out=$out"

# 7-9. the title prefix is added here and is idempotent. SKILL.md step 7's
# template reads as the complete title, so a caller that writes the prefix
# itself got it twice — 21 of 235 friction beads carry the doubled form, and
# the TITLE is the only field bd search reads (computenet-rtoo).
title_case() { # given-title expected-stored-title label
  fixture
  "$SCRIPT" --type bug --title "$1" --desc d --accept a --skill-version abc >/dev/null 2>&1
  # create-ticket.sh passes the title POSITIONALLY, so the stored title is
  # everything between `create ` and the first flag.
  if grep -q -- "^create work skill: $2 --" "$BD_LOG"; then
    ok "$3"
  else
    bad "$3 — bd log: $(grep create "$BD_LOG" | head -1)"
  fi
}
title_case "the thing"                    "the thing" "bare title gets one prefix"
title_case "work skill: the thing"        "the thing" "caller-written prefix is not doubled"
title_case "work skill: work skill: x"    "x"         "a doubled prefix collapses to one"

# A title that is nothing but the prefix is a caller error, not an empty bead.
fixture
out=$("$SCRIPT" --type bug --title "work skill: " --desc d --accept a 2>&1); st=$?
[ "$st" = 2 ] && ok "a title that is only the prefix exits 2" || bad "prefix-only: exit=$st out=$out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
