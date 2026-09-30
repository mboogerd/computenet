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
[ "\${GH_MODE:-ok}" = notpr ] && { echo "#1131"; exit 0; }
echo "$URL"
EOF
  # BD_DROP: ids whose update exits 0 but stores nothing. BD_MODE=listfail:
  # `bd list` fails. The list stub filters on the label only and returns the
  # sections in the JSON, both spellings, so the script's own match is tested.
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
  list)   [ "\${BD_MODE:-ok}" = listfail ] && { echo "dolt: connection refused" >&2; exit 1; }
          case "\$*" in *"--label revise"*) ;; *) echo '[]'; exit 0 ;; esac
          # Honour --metadata-field as real bd does, exactly, so a script
          # that leans on it is tested against bd's real (exact) behaviour.
          exact=""; prev=""
          for a in "\$@"; do
            [ "\$prev" = "--metadata-field" ] && exact=\${a#section=}; prev=\$a
          done
          echo '[{"id":"t-a","metadata":{"section":"work/SKILL.md#Five"}},
                 {"id":"t-b","metadata":{"section":".claude/skills/work/SKILL.md#Five"}},
                 {"id":"t-c","metadata":{"section":"work/SKILL.md#Five"}},
                 {"id":"t-six","metadata":{"section":"work/SKILL.md#Six"}},
                 {"id":"t-none","metadata":{}}]' \
            | jq --arg e "\$exact" '[.[] | select(\$e == "" or .metadata.section == \$e)]' ;;
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
[ -z "$(stamped t-six)" ] && ok "another section's item is not stamped" || bad "t-six stamped"
# Items are queued under both spellings of a section; an exact match skipped
# the other one silently (review of #1207, F1).
fixture
out=$(run 1131 --section '.claude/skills/work/SKILL.md#Five'); rc=$?
[ "$rc" -eq 0 ] && ok "prefixed spelling: exits 0" || bad "prefixed spelling: exits $rc -- $out"
for id in t-a t-b t-c; do
  [ "$(stamped $id)" = "$URL" ] && ok "prefixed spelling: $id stamped" \
    || bad "prefixed spelling: $id skipped"
done
fixture
out=$(BD_MODE=listfail run 1131 --section 'work/SKILL.md#Five'); rc=$?
[ "$rc" -eq 3 ] && ok "failing bd list: exits 3" || bad "failing bd list: exits $rc, wanted 3"
grep -q '^update' "$D/bd.log" && bad "failing bd list: still wrote to bd" \
  || ok "failing bd list: no bd write"
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
out=$(GH_MODE=notpr run 1131 t-a); rc=$?
[ "$rc" -eq 3 ] && ok "non-url from gh: exits 3" || bad "non-url from gh: exits $rc, wanted 3"
grep -q '^update' "$D/bd.log" && bad "non-url from gh: stamped anyway" \
  || ok "non-url from gh: no bd write"
fixture
out=$(run 1131); rc=$?
[ "$rc" -eq 2 ] && ok "no ids: exits 2" || bad "no ids: exits $rc, wanted 2"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
