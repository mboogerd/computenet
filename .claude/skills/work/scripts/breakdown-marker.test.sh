#!/usr/bin/env bash
# Tests for breakdown-marker.sh. Stubs `bd` on PATH and asserts on a call log,
# the same shape as create-ticket.test.sh. Exits 0 if all cases pass.
# Expect "22 passed, 0 failed".
#
# Everything here is stub-`bd`: no case touches a real beads workspace, and no
# case writes a comment on a live epic. What a stub CANNOT show is that the
# marker comment replicates to another machine — that is the epic's content
# plane (bd dolt push/pull against DoltHub), asserted by feature
# computenet-6wc.5 clause 2 and not exercised here.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/breakdown-marker.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/breakdown-marker-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

EPIC=computenet-6wc
ACTOR=MacBoo
# 6wc.5-D3's fixed vector: first 16 hex of sha256 over "computenet-6wc\nMacBoo\n".
VECTOR=fb2c82a6d9740283
OTHER=00aabbccddeeff00          # byte-order LOWER than $VECTOR, so it survives
HIGHER=ff00000000000000         # byte-order HIGHER, so it loses

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1 ${2:-}" in
  "dolt pull") [ -f "$CTRL/pull-fail" ] && exit 1; echo "pulled"; exit 0 ;;
  "dolt push")
    n=$(cat "$CTRL/push-n" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$CTRL/push-n"
    case "$(sed -n "${n}p" "$CTRL/push-plan" 2>/dev/null)" in
      reject) echo "error: push rejected: non-fast-forward"; exit 1 ;;
      *)      echo "Everything up-to-date"; exit 0 ;;
    esac ;;
esac
case "$1" in
  comments)
    [ -f "$CTRL/comments-fail" ] && { echo "bd: no such issue" >&2; exit 1; }
    echo "Comments for $2:"          # the human prefix the sed slice must skip
    cat "$CTRL/comments.json"; exit 0 ;;
  comment)                            # bd comment <id> --file <path>
    cp "$4" "$CTRL/comment-body"
    tmp=$(mktemp "$CTRL/c.XXXXXX")
    jq --arg t "$(cat "$4")" --arg a "${BEADS_ACTOR:-unknown}" \
       '. + [{id:"c-new",issue_id:"e",author:$a,text:$t,created_at:"2026-09-18T12:00:00Z"}]' \
       "$CTRL/comments.json" > "$tmp" && mv "$tmp" "$CTRL/comments.json"
    echo "Added comment"; exit 0 ;;
  list)
    cat "$CTRL/children.json"; exit 0 ;;
esac
exit 0
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"
export BEADS_ACTOR="$ACTOR"

pass=0; fail=0
ok()  { pass=$((pass + 1)); echo "  PASS $*"; }
bad() { fail=$((fail + 1)); echo "  FAIL $*"; }
CASE=0
fixture() {
  CASE=$((CASE + 1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log"
  mkdir -p "$CTRL"; : > "$BD_LOG"
  echo '[]' > "$CTRL/comments.json"; echo '[]' > "$CTRL/children.json"
}
# A marker comment whose first line is the D2 format.
marker() { printf '{"id":"c%s","issue_id":"%s","author":"%s","text":"BREAKDOWN-MARKER v1 token=%s actor=%s epic=%s\\nprose.","created_at":"%s"}' \
  "$RANDOM" "$EPIC" "$2" "$1" "$2" "$EPIC" "${3:-2026-09-18T10:00:00Z}"; }
comments() { printf '[%s]\n' "$(IFS=,; echo "$*")" > "$CTRL/comments.json"; }

# --- token (6wc.5-D3) ------------------------------------------------------

# 1. the fixed vector, and both hashers agree on it
a=$(printf '%s\n%s\n' "$EPIC" "$ACTOR" | shasum -a 256 | cut -c1-16)
b=$(printf '%s\n%s\n' "$EPIC" "$ACTOR" | sha256sum | cut -c1-16)
[ "$a" = "$VECTOR" ] && [ "$b" = "$VECTOR" ] \
  && ok "token vector $VECTOR from both shasum and sha256sum" \
  || bad "vector: shasum=$a sha256sum=$b expected=$VECTOR"

# 2. the script prints that token (it prints TOKEN only when a marker is OWN)
fixture; comments "$(marker "$VECTOR" "$ACTOR")"
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 10 ] && grep -qx "TOKEN $VECTOR" <<<"$out" \
  && ok "check derives the vector token" || bad "vector-in-script: exit=$st out=$out"

# 3. the sha256sum fallback: same token with shasum off PATH entirely.
#    Linux CI has sha256sum and may not have shasum; this is that path.
mkdir -p "$ROOT/nosha"
for b in bash env jq sed cut date mktemp rm mv cp sort awk grep head tail cat dirname sleep git sha256sum; do
  p=$(command -v "$b" 2>/dev/null) && ln -sf "$p" "$ROOT/nosha/$b"
done
fixture; comments "$(marker "$VECTOR" "$ACTOR")"
out=$(PATH="$ROOT/bin:$ROOT/nosha" "$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 10 ] && grep -qx "TOKEN $VECTOR" <<<"$out" \
  && [ ! -e "$ROOT/nosha/shasum" ] \
  && ok "same token via sha256sum with shasum off PATH" \
  || bad "fallback: exit=$st out=$out"

# --- check: classification -------------------------------------------------

# 4. NONE
fixture
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 0 ] && [ -z "$out" ] && ok "no markers -> exit 0, nothing printed" \
  || bad "none: exit=$st out=$out"

# 5. OWN prints the MARKER line and the token
fixture; comments "$(marker "$VECTOR" "$ACTOR")"
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 10 ] && grep -q "^MARKER $VECTOR $ACTOR 2026-09-18T10:00:00Z$" <<<"$out" \
  && grep -qx "TOKEN $VECTOR" <<<"$out" \
  && ok "OWN -> 10 with MARKER and TOKEN" || bad "own: exit=$st out=$out"

# 6. FOREIGN only -> 11, and no TOKEN (there is none to hand a breakdown agent)
fixture; comments "$(marker "$OTHER" Anva)"
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 11 ] && grep -q "^MARKER $OTHER Anva" <<<"$out" && ! grep -q TOKEN <<<"$out" \
  && ok "FOREIGN -> 11, no TOKEN" || bad "foreign: exit=$st out=$out"

# 7. BOTH -> 12
fixture; comments "$(marker "$VECTOR" "$ACTOR")" "$(marker "$OTHER" Anva)"
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 12 ] && grep -qx "TOKEN $VECTOR" <<<"$out" && [ "$(grep -c '^MARKER ' <<<"$out")" = 2 ] \
  && ok "BOTH -> 12 with both MARKER lines" || bad "both: exit=$st out=$out"

# 8. the format on a LATER line is not a marker (6wc.5-D2: first lines only),
#    so quoting the format in a discussion comment mints nothing
fixture
printf '[{"id":"c1","issue_id":"%s","author":"Anva","text":"discussing the format:\\nBREAKDOWN-MARKER v1 token=%s actor=Anva epic=%s","created_at":"t"}]\n' \
  "$EPIC" "$OTHER" "$EPIC" > "$CTRL/comments.json"
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 0 ] && [ -z "$out" ] && ok "marker line below the first is not a marker" \
  || bad "not-first-line: exit=$st out=$out"

# 9. a marker naming a DIFFERENT epic is not this epic's
fixture; printf '[{"id":"c1","issue_id":"%s","author":"Anva","text":"BREAKDOWN-MARKER v1 token=%s actor=Anva epic=computenet-other","created_at":"t"}]\n' \
  "$EPIC" "$OTHER" > "$CTRL/comments.json"
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 0 ] && ok "a marker for another epic is ignored" || bad "other-epic: exit=$st out=$out"

# 10. UNCHECKED: bd failed. Exit 3 and nothing on stdout that reads as an answer.
fixture; touch "$CTRL/comments-fail"
out=$("$SCRIPT" check "$EPIC" 2>/dev/null); st=$?
[ "$st" = 3 ] && [ -z "$out" ] && ok "bd failure -> 3 UNCHECKED, silent stdout" \
  || bad "unchecked: exit=$st out=$out"

# 11. UNCHECKED: BEADS_ACTOR unset, one line on stderr, nothing on stdout
fixture; comments "$(marker "$VECTOR" "$ACTOR")"
out=$(env -u BEADS_ACTOR "$SCRIPT" check "$EPIC" 2>"$CTRL/err"); st=$?
[ "$st" = 3 ] && [ -z "$out" ] && [ "$(wc -l < "$CTRL/err")" -eq 1 ] \
  && grep -q BEADS_ACTOR "$CTRL/err" \
  && ok "BEADS_ACTOR unset -> 3 with one stderr line" \
  || bad "no-actor: exit=$st out=$out err=$(cat "$CTRL/err")"

# 12. `check` never writes: no bd comment, no push
fixture; comments "$(marker "$VECTOR" "$ACTOR")"
"$SCRIPT" check "$EPIC" >/dev/null 2>&1
! grep -qE '^(comment |dolt push)' "$BD_LOG" \
  && ok "check is read-only (no comment, no push)" || bad "check-writes: $(cat "$BD_LOG")"

# --- acquire ---------------------------------------------------------------

# 13. NONE: pull, exactly one comment whose FIRST line is the D2 format, push
fixture
out=$("$SCRIPT" acquire "$EPIC" 2>/dev/null); st=$?
first=$(head -1 "$CTRL/comment-body" 2>/dev/null)
[ "$st" = 0 ] && grep -qx "TOKEN $VECTOR" <<<"$out" \
  && [ "$(grep -c '^comment ' "$BD_LOG")" = 1 ] \
  && [ "$first" = "BREAKDOWN-MARKER v1 token=$VECTOR actor=$ACTOR epic=$EPIC" ] \
  && [ "$(wc -l < "$CTRL/comment-body")" -ge 2 ] \
  && grep -q "^dolt pull" "$BD_LOG" && grep -q "^dolt push" "$BD_LOG" \
  && ok "acquire on NONE writes one D2 marker and pushes" \
  || bad "acquire-none: exit=$st out=$out first=$first log=$(cat "$BD_LOG")"

# 14. the pull happens BEFORE the check (the whole point of the bracket)
fixture
"$SCRIPT" acquire "$EPIC" >/dev/null 2>&1
[ "$(grep -n -E '^(dolt pull|comments)' "$BD_LOG" | head -1 | cut -d: -f2-)" = "dolt pull" ] \
  && ok "acquire pulls before it reads" || bad "acquire-order: $(cat "$BD_LOG")"

# 15. OWN: resume — writes nothing, exits 0, hands back the token
fixture; comments "$(marker "$VECTOR" "$ACTOR")"
out=$("$SCRIPT" acquire "$EPIC" 2>/dev/null); st=$?
[ "$st" = 0 ] && grep -qx "TOKEN $VECTOR" <<<"$out" && ! grep -q '^comment ' "$BD_LOG" \
  && ! grep -q '^dolt push' "$BD_LOG" \
  && ok "acquire on OWN writes nothing and resumes" || bad "acquire-own: exit=$st out=$out log=$(cat "$BD_LOG")"

# 16. FOREIGN: writes nothing, exits 11
fixture; comments "$(marker "$OTHER" Anva)"
out=$("$SCRIPT" acquire "$EPIC" 2>/dev/null); st=$?
[ "$st" = 11 ] && ! grep -q '^comment ' "$BD_LOG" && ! grep -q '^dolt push' "$BD_LOG" \
  && ok "acquire on FOREIGN writes nothing, exits 11" || bad "acquire-foreign: exit=$st out=$out log=$(cat "$BD_LOG")"

# 17. BOTH: writes nothing, exits 12
fixture; comments "$(marker "$VECTOR" "$ACTOR")" "$(marker "$OTHER" Anva)"
out=$("$SCRIPT" acquire "$EPIC" 2>/dev/null); st=$?
[ "$st" = 12 ] && ! grep -q '^comment ' "$BD_LOG" \
  && ok "acquire on BOTH writes nothing, exits 12" || bad "acquire-both: exit=$st out=$out log=$(cat "$BD_LOG")"

# 18. a rejected push recovers: pull, re-verify, push once more -> 0
fixture; printf 'reject\nok\n' > "$CTRL/push-plan"
out=$("$SCRIPT" acquire "$EPIC" 2>/dev/null); st=$?
[ "$st" = 0 ] && [ "$(grep -c '^dolt push' "$BD_LOG")" = 2 ] \
  && [ "$(grep -c '^comment ' "$BD_LOG")" = 1 ] \
  && ok "rejected push recovers and exits 0, writing one marker" \
  || bad "acquire-reject-once: exit=$st out=$out log=$(cat "$BD_LOG")"

# 19. rejected twice: the marker is local-only -> exit 2 (claim-epic.sh's exit 2)
fixture; printf 'reject\nreject\n' > "$CTRL/push-plan"
out=$("$SCRIPT" acquire "$EPIC" 2>"$CTRL/err"); st=$?
[ "$st" = 2 ] && grep -qi "LOCAL-ONLY" "$CTRL/err" \
  && ok "twice-rejected push exits 2 as local-only" \
  || bad "acquire-reject-twice: exit=$st err=$(cat "$CTRL/err")"

# --- survivor (6wc.5-D4) ---------------------------------------------------

children() { printf '[%s]\n' "$(IFS=,; echo "$*")" > "$CTRL/children.json"; }
child() { printf '{"id":"%s","metadata":%s}' "$1" "$2"; }

# 20. two markers: lowest token in byte order survives; only loser-stamped
#     children are CLOSE; unstamped ones are UNATTRIBUTED; nothing is closed.
fixture; comments "$(marker "$VECTOR" "$ACTOR")" "$(marker "$OTHER" Anva)"
children "$(child f-survivor "{\"breakdown\":\"$OTHER\"}")" \
         "$(child f-loser "{\"breakdown\":\"$VECTOR\"}")" \
         "$(child f-legacy '{}')"
out=$("$SCRIPT" survivor "$EPIC" 2>/dev/null); st=$?
[ "$st" = 1 ] && grep -qx "SURVIVOR $OTHER Anva" <<<"$out" \
  && grep -qx "LOSER $VECTOR $ACTOR" <<<"$out" \
  && grep -qx "CLOSE f-loser" <<<"$out" \
  && ! grep -q "f-survivor" <<<"$out" \
  && grep -qx "UNATTRIBUTED f-legacy" <<<"$out" \
  && ! grep -qE '^(close|update)' "$BD_LOG" \
  && ok "survivor: lowest token wins, losers listed, nothing closed" \
  || bad "survivor: exit=$st out=$out log=$(cat "$BD_LOG")"

# 21. the rule is byte order, not arrival order: the HIGHER token loses even
#     when its marker is the older one
fixture; comments "$(marker "$HIGHER" Anva 2026-01-01T00:00:00Z)" \
                  "$(marker "$VECTOR" "$ACTOR" 2026-09-18T10:00:00Z)"
out=$("$SCRIPT" survivor "$EPIC" 2>/dev/null); st=$?
[ "$st" = 1 ] && grep -qx "SURVIVOR $VECTOR $ACTOR" <<<"$out" \
  && grep -qx "LOSER $HIGHER Anva" <<<"$out" \
  && ok "the older marker loses when its token is higher" || bad "byte-order: exit=$st out=$out"

# 22. one marker: nothing to adjudicate -> exit 0, still prints
fixture; comments "$(marker "$VECTOR" "$ACTOR")"
children "$(child f-1 "{\"breakdown\":\"$VECTOR\"}")" "$(child f-2 '{}')"
out=$("$SCRIPT" survivor "$EPIC" 2>/dev/null); st=$?
[ "$st" = 0 ] && grep -qx "SURVIVOR $VECTOR $ACTOR" <<<"$out" \
  && ! grep -q '^LOSER' <<<"$out" && ! grep -q '^CLOSE' <<<"$out" \
  && grep -qx "UNATTRIBUTED f-2" <<<"$out" \
  && ok "a single marker exits 0 and lists no losers" || bad "survivor-one: exit=$st out=$out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
