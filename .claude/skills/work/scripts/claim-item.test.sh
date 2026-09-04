#!/usr/bin/env bash
# Tests for claim-item.sh. Stubs `bd` and `session-holder.sh` on a throwaway
# PATH/dir, so no real bead is claimed. Expect "7 passed, 0 failed".
#
# The suite exists because the script's whole reason for being — the holder
# stamp — is invisible to every other check. computenet-yvdl's reviewer removed
# the stamp as a mutation and NOTHING went red: the sweep's own suite stubs the
# holder verdict, so it proves the read half and says nothing about the write.
#
#   .claude/skills/work/scripts/claim-item.test.sh
#   .claude/skills/work/scripts/claim-item.test.sh /path/to/other.sh
set -uo pipefail

SRC=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/claim-item.sh"}
[ -x "$SRC" ] || { echo "not executable: $SRC" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/claim-item-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }

CASE=0
# fixture <bd-claim-exit> <holder-output-or-FAIL> — a copy of the script beside
# a stubbed session-holder.sh, with `bd` on PATH logging every call.
fixture() {
  CASE=$((CASE+1))
  D="$ROOT/c$CASE"; mkdir -p "$D/bin"
  cp "$SRC" "$D/claim-item.sh"; chmod +x "$D/claim-item.sh"
  if [ "$2" = FAIL ]; then
    printf '#!/usr/bin/env bash\nexit 1\n' > "$D/session-holder.sh"
  else
    printf '#!/usr/bin/env bash\nprintf "%%s\\n" "%s"\n' "$2" > "$D/session-holder.sh"
  fi
  chmod +x "$D/session-holder.sh"
  cat > "$D/bin/bd" <<EOS
#!/usr/bin/env bash
echo "\$@" >> "$D/bd.log"
case " \$* " in *" --claim "*) exit $1 ;; esac
exit 0
EOS
  chmod +x "$D/bin/bd"
  : > "$D/bd.log"
}
run() { PATH="$D/bin:$PATH" "$D/claim-item.sh" "$@" 2>"$D/err"; }

# A realistic token: the real one carries SPACES (`host/actor:pid:Fri Sep 4
# 16:02:09 2026`), so a fixture without them would not prove the stamp survives
# word splitting — which is the one way this script can silently mangle it.
TOK='NL-MGD6FQJW91/MacBoo:93376:Fri Sep 4 16:02:09 2026'

# 1/2/3. the happy path: claim first, then the stamp, with the token intact
fixture 0 "$TOK"
run i-1; rc=$?
[ "$rc" = 0 ] && ok "a successful claim exits 0" || bad "a successful claim exited $rc"
grep -q -- 'i-1 --claim' "$D/bd.log" && ok "the claim is made" || bad "no --claim in $(cat "$D/bd.log")"
grep -qF -- "--set-metadata holder=$TOK" "$D/bd.log" \
  && ok "the holder token is stamped whole, spaces and all" \
  || bad "holder not stamped intact: $(cat "$D/bd.log")"

# 4. order matters: a stamp on a bead this session does not hold is a lie
fixture 0 "$TOK"
run i-2 >/dev/null
[ "$(head -1 "$D/bd.log" | grep -c -- --claim)" = 1 ] \
  && ok "the claim precedes the stamp" \
  || bad "first bd call was not the claim: $(head -1 "$D/bd.log")"

# 5/6/7. a refused claim must propagate AND must not stamp: a holder on a bead
# another machine holds would make its live claim look like ours.
fixture 1 "$TOK"
run i-3 >/dev/null; rc=$?
[ "$rc" = 1 ] && ok "a refused claim propagates bd's exit code" || bad "refused claim exited $rc"
grep -q -- '--set-metadata' "$D/bd.log" \
  && bad "a refused claim still stamped a holder: $(cat "$D/bd.log")" \
  || ok "a refused claim stamps nothing"

# 8. a holder that cannot be minted is a WARNING, never a failure: the claim is
# the point, and liveness degrades to the recency test exactly as before.
fixture 0 FAIL
run i-4 >/dev/null; rc=$?
if [ "$rc" = 0 ] && grep -q 'metadata.holder' "$D/err" \
   && grep -q -- 'i-4 --claim' "$D/bd.log"; then
  ok "an unmintable holder warns, keeps the claim, and still exits 0"
else
  bad "unmintable holder: rc=$rc err=$(cat "$D/err") log=$(cat "$D/bd.log")"
fi

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
