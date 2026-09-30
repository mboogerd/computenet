#!/usr/bin/env bash
# Tests for open-revision-pr.sh. Stubs `gh` and `bd` on PATH with a per-case
# file-backed store; touches no real bead and makes no network call.
#
#   .claude/skills/remediate-friction/scripts/open-revision-pr.test.sh [script]
#
# The case that matters is the silent drop: a `bd update` that exits 0 but
# does not take. Without the re-read the script reports every item linked, and
# the item stays open after the merge — the computenet-6nzf5 failure.
#
# Exits 0 if all cases pass, 1 otherwise.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/open-revision-pr.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }
ROOT=$(mktemp -d "${TMPDIR:-/tmp}/open-revision-pr-test.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT

URL=https://github.com/mboogerd/computenet/pull/1131
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has() { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }

CASE=0
fixture() {                 # fresh stubs + store; sets D, STUB
  CASE=$((CASE+1)); D="$ROOT/c$CASE"; STUB="$D/bin"
  mkdir -p "$STUB" "$D/pr"; : > "$D/bd.log"
  cat > "$STUB/gh" <<EOF
#!/usr/bin/env bash
[ "\${GH_MODE:-ok}" = fail ] && { echo "gh: HTTP 502" >&2; exit 1; }
[ "\$1 \$2" = "pr view" ] || exit 9
echo "$URL"
EOF
  # BD_DROP: ids whose update exits 0 but stores nothing.
  cat > "$STUB/bd" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$D/bd.log"
case "\$1" in
  update) id=\$2; v=\${4#pr=}
          case " \${BD_DROP:-} " in *" \$id "*) exit 0 ;; esac
          printf '%s' "\$v" > "$D/pr/\$id" ;;
  show)   v=\$(cat "$D/pr/\$2" 2>/dev/null)
          echo "warning: noise before json"
          printf '[{"id":"%s","metadata":{"pr":"%s"}}]\n' "\$2" "\$v" ;;
  list)   case "\$*" in
            *"--label revise"*"section=work/SKILL.md#Five"*)
              echo '[{"id":"t-a"},{"id":"t-b"},{"id":"t-c"}]' ;;
            *) echo '[]' ;;
          esac ;;
esac
EOF
  chmod +x "$STUB/gh" "$STUB/bd"
}
run() { PATH="$STUB:$PATH" "$SCRIPT" "$@" 2>&1; }
stamped() { cat "$D/pr/$1" 2>/dev/null; }

echo "explicit ids: every item stamped with the canonical url"
fixture
out=$(run 1131 t-a t-b); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0 -- $out"
[ "$(stamped t-a)" = "$URL" ] && [ "$(stamped t-b)" = "$URL" ] \
  && ok "both carry the full url, not the bare number the sweep ignores" \
  || bad "stamped: '$(stamped t-a)' '$(stamped t-b)'"
has "$out" "linked 2 item(s)" "summary counts both"
# The same anchored join sweep-merged-prs.sh applies for this repo.
[[ $(stamped t-a) =~ ^https?://[^/]+/mboogerd/computenet/pull/[0-9]+$ ]] \
  && ok "stamped value matches the sweep's join pattern" || bad "sweep would ignore it"
[ "$(grep -c '^update' "$D/bd.log")" -eq 2 ] && ok "one bd write per item" \
  || bad "update calls: $(grep -c '^update' "$D/bd.log")"

echo
echo "--section: resolves the queued revise items"
fixture
out=$(run 1131 --section 'work/SKILL.md#Five'); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0 -- $out"
for id in t-a t-b t-c; do
  [ "$(stamped $id)" = "$URL" ] && ok "$id stamped" || bad "$id not stamped"
done
fixture
out=$(run 1131 --section 'work/SKILL.md#Five' t-park t-a); rc=$?
[ "$rc" -eq 0 ] && ok "section + park id: exits 0" || bad "section + park id: exits $rc -- $out"
[ "$(stamped t-park)" = "$URL" ] && ok "the park item is stamped too" || bad "park item not stamped"
[ "$(grep -c '^update t-a ' "$D/bd.log")" -eq 1 ] && ok "an id named twice is written once" \
  || bad "t-a written $(grep -c '^update t-a ' "$D/bd.log") times"
has "$out" "linked 4 item(s)" "section + park id: counts 4"
fixture
out=$(run 1131 --section 'nowhere.md#X'); rc=$?
[ "$rc" -eq 3 ] && ok "empty section: exits 3" || bad "empty section: exits $rc, wanted 3"
has "$out" "no open item labelled revise" "empty section: says so"

echo
echo "silent drop: a write that did not take fails loudly"
fixture
out=$(BD_DROP=t-b run 1131 t-a t-b t-c); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1 -- $out"
has "$out" "NOT linked to $URL: t-b" "names exactly the unlinked id"
grep -qF "linked 3 item(s)" <<<"$out" \
  && bad "still printed the all-linked summary" || ok "no all-linked summary"

echo
echo "preconditions"
fixture
out=$(GH_MODE=fail run 1131 t-a); rc=$?
[ "$rc" -eq 3 ] && ok "gh failure: exits 3" || bad "gh failure: exits $rc, wanted 3"
grep -q '^update' "$D/bd.log" && bad "gh failure: still wrote to bd" \
  || ok "gh failure: no bd write"
fixture
out=$(run 1131); rc=$?
[ "$rc" -eq 2 ] && ok "no ids: exits 2" || bad "no ids: exits $rc, wanted 2"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
