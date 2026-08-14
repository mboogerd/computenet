#!/usr/bin/env bash
# Tests for feature-branch.sh. Stubs `bd` and `gh` on PATH; runs inside a
# throwaway git repo so the worktree root resolves under the tmpdir.
# Exits 0 if all cases pass. Expect "6 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/feature-branch.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/feature-branch-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin" "$ROOT/repo"
git init --quiet "$ROOT/repo"
WT_ROOT="$ROOT/computenet-worktrees"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1" in
  show)    cat "$CTRL/show.json" ;;
  update)  case "$*" in *--parent*) : ;; esac
           [ -f "$CTRL/update-fail" ] && exit 1; exit 0 ;;
  comment) [ -f "$CTRL/comment-fail" ] && exit 1; exit 0 ;;
esac
EOF
cat > "$ROOT/bin/gh" <<'EOF'
#!/usr/bin/env bash
[ -f "$CTRL/gh-fail" ] && { echo "dial tcp: can't assign requested address" >&2; exit 1; }
cat "$CTRL/prs.json"
EOF
chmod +x "$ROOT/bin/bd" "$ROOT/bin/gh"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log"
            mkdir -p "$CTRL"; : > "$BD_LOG"; }
show_branch() { printf '[{"id":"computenet-f1","metadata":{"branch":"%s"}}]' "$1" > "$CTRL/show.json"; }
run() { (cd "$ROOT/repo" && "$SCRIPT" computenet-f1 2>&1); }

# 1. no metadata.branch: record first, then print the defaults
fixture; printf '[{"id":"computenet-f1","metadata":{}}]' > "$CTRL/show.json"
out=$(run); st=$?
[ "$st" = 0 ] && [ "$(tail -1 <<<"$out")" = "$(printf 'feature/computenet-f1\t%s/computenet-f1' "$WT_ROOT")" ] \
  && grep -q "branch=feature/computenet-f1" "$BD_LOG" \
  && ok "fresh feature records metadata and prints defaults" || bad "fresh: exit=$st out=$out"

# 2. recorded branch with an OPEN PR: reuse, no metadata write
fixture; show_branch feature/computenet-f1
echo '[{"number":5,"state":"OPEN","url":"u"}]' > "$CTRL/prs.json"
out=$(run); st=$?
[ "$st" = 0 ] && grep -q "feature/computenet-f1" <<<"$out" && ! grep -q update "$BD_LOG" \
  && ok "open PR reuses the branch untouched" || bad "open: exit=$st out=$out log=$(cat "$BD_LOG")"

# 3. PR merged (squash): mint -r2, clear pr, comment
fixture; show_branch feature/computenet-f1
echo '[{"number":9,"state":"MERGED","url":"http://pr/9"}]' > "$CTRL/prs.json"
out=$(run); st=$?
[ "$st" = 0 ] && [ "$(tail -1 <<<"$out")" = "$(printf 'feature/computenet-f1-r2\t%s/computenet-f1-r2' "$WT_ROOT")" ] \
  && grep -q -- "--unset-metadata pr" "$BD_LOG" && grep -q "^comment" "$BD_LOG" \
  && ok "merged PR mints -r2, clears pr, comments" || bad "merged: exit=$st out=$out log=$(cat "$BD_LOG")"

# 4. already on -r2, merged again: -r3, not -r2 (BSD sed regression guard)
fixture; show_branch feature/computenet-f1-r2
echo '[{"number":12,"state":"MERGED","url":"http://pr/12"}]' > "$CTRL/prs.json"
out=$(run); st=$?
grep -q "feature/computenet-f1-r3" <<<"$out" \
  && ok "-rN increments, never re-mints -r2" || bad "rN: out=$out"

# 5. gh unreachable: a failed call is not a reading — refuse to guess
fixture; show_branch feature/computenet-f1; touch "$CTRL/gh-fail"
out=$(run); st=$?
[ "$st" = 1 ] && grep -qi "unreadable" <<<"$out" \
  && ok "gh failure exits 1 without choosing" || bad "ghfail: exit=$st out=$out"

# 6. bd comment refused: falls back to --append-notes
fixture; show_branch feature/computenet-f1; touch "$CTRL/comment-fail"
echo '[{"number":9,"state":"MERGED","url":"http://pr/9"}]' > "$CTRL/prs.json"
out=$(run); st=$?
[ "$st" = 0 ] && grep -q -- "--append-notes" "$BD_LOG" \
  && ok "refused comment falls back to --append-notes" || bad "notes: exit=$st log=$(cat "$BD_LOG")"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
