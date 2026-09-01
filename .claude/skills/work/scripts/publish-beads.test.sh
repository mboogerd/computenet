#!/usr/bin/env bash
# Tests for publish-beads.sh. Stubs `bd` on PATH. Exits 0 if all cases pass.
# Expect "11 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/publish-beads.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/publish-beads-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
case "$1 $2" in
  "dolt push")
    n=$(cat "$CTRL/pushn" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$CTRL/pushn"
    # Both signals are controllable per call: push$n.out is the output,
    # push$n.rc the exit status (default 0 — the historical bug is a
    # rejection printed at exit 0).
    cat "$CTRL/push$n.out" 2>/dev/null || echo "push complete"
    exit "$(cat "$CTRL/push$n.rc" 2>/dev/null || echo 0)" ;;
  "dolt pull")
    cat "$CTRL/pull.out" 2>/dev/null || echo "pull complete" ;;
esac
EOF
cat > "$ROOT/bin/sleep" <<'EOF'
#!/usr/bin/env bash
echo "$1" >> "$CTRL/sleeps"
exit 0
EOF
chmod +x "$ROOT/bin/bd" "$ROOT/bin/sleep"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE"; mkdir -p "$CTRL"; }

# 1. clean push
fixture
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ "$(cat "$CTRL/pushn")" = 1 ] && ok "clean push exits 0" \
  || bad "clean: exit=$st out=$out"

# 2. rejection with EXIT 0 is caught from the output, recovered via pull+push
fixture; echo 'Error 1105: ! [rejected]  main -> main (non-fast-forward)' > "$CTRL/push1.out"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ "$(cat "$CTRL/pushn")" = 2 ] && grep -q "recovering" <<<"$out" \
  && ok "exit-0 rejection is caught and recovered" || bad "recover: exit=$st out=$out"

# 3. pull reports a merge conflict: escalate, exit 2
fixture; echo '! [rejected]' > "$CTRL/push1.out"
echo 'merge conflicts in beads require operator resolution' > "$CTRL/pull.out"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 2 ] && grep -q "ESCALATE" <<<"$out" && [ "$(cat "$CTRL/pushn")" = 1 ] \
  && ok "merge conflict escalates without a second push" || bad "conflict: exit=$st out=$out"

# 4. second push also rejected: exit 2, LOCAL-ONLY named
fixture; echo '! [rejected]' > "$CTRL/push1.out"; echo '! [rejected]' > "$CTRL/push2.out"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 2 ] && grep -q "LOCAL-ONLY" <<<"$out" \
  && ok "double rejection exits 2 naming local-only state" || bad "double: exit=$st out=$out"

# 5. NONZERO EXIT with CLEAN output is a failure too — the signal the
#    output-only test used to miss (computenet-kbk0). Recovered by pull+push.
fixture; echo 'push complete' > "$CTRL/push1.out"; echo 1 > "$CTRL/push1.rc"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ "$(cat "$CTRL/pushn")" = 2 ] && grep -q "recovering" <<<"$out" \
  && ok "nonzero exit with clean output is caught and recovered" \
  || bad "rc-only: exit=$st out=$out"

# 6. …and a second nonzero exit with clean output still escalates.
fixture; echo 'push complete' > "$CTRL/push1.out"; echo 1 > "$CTRL/push1.rc"
echo 'push complete' > "$CTRL/push2.out"; echo 1 > "$CTRL/push2.rc"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 2 ] && grep -q "LOCAL-ONLY" <<<"$out" \
  && ok "double nonzero exit exits 2 naming local-only state" \
  || bad "rc-only double: exit=$st out=$out"

# --- transport faults are retried, not escalated (computenet-ckvu) ----------
# This script had the same back-to-back two-attempt shape that ended a session
# in claim-epic.sh: a DNS failure says nothing about the remote's state.
DNS='Error 1105: failed to get remote db; dial tcp: lookup doltremoteapi.dolthub.com: no such host'

fixture; printf '%s\n' "$DNS" > "$CTRL/push1.out"; echo 1 > "$CTRL/push1.rc"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && [ "$(cat "$CTRL/pushn")" = 2 ] && ok "a transient transport fault is retried" \
  || bad "dns retry: exit=$st pushes=$(cat "$CTRL/pushn" 2>/dev/null) out=$out"
grep -q "recovering" <<<"$out" && bad "a transport fault must not trigger the pull recovery" \
  || ok "no pull: nothing was said about the remote's state"

fixture
for n in 1 2 3; do printf '%s\n' "$DNS" > "$CTRL/push$n.out"; echo 1 > "$CTRL/push$n.rc"; done
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 2 ] && [ "$(cat "$CTRL/pushn")" = 3 ] && ok "a persistent transport fault escalates after 3" \
  || bad "dns persist: exit=$st pushes=$(cat "$CTRL/pushn" 2>/dev/null) out=$out"
grep -q "^ESCALATE: push failed 3x with a transport fault" <<<"$out" \
  && ok "the escalation line names the fault class" || bad "generic escalation: $out"

fixture; echo '! [rejected] main -> main (non-fast-forward); uploaded 502 chunks' > "$CTRL/push1.out"
out=$("$SCRIPT" 2>&1); st=$?
[ "$st" = 0 ] && grep -q "recovering" <<<"$out" \
  && ok "a rejection carrying '502' still takes the pull path" || bad "502 misrouted: $out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]