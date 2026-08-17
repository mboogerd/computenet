#!/usr/bin/env bash
# Tests for reclaim-worktrees.sh. Stubs `bd` and builds real git worktrees.
# Expect "7 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/reclaim-worktrees.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/reclaim-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin" "$ROOT/computenet-worktrees"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "Warning: beads.role not set"      # bd prints warnings BEFORE the JSON
printf '[{"id":"%s","status":"%s"}]\n' "$2" "$(cat "$CTRL/status.$2" 2>/dev/null || echo open)"
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH" CTRL="$ROOT"

REPO="$ROOT/repo"
git init -q -b main "$REPO"
git -C "$REPO" -c user.email=t@e -c user.name=t commit -q --allow-empty -m base

mk() { # id status [dirty]
  local id=$1 st=$2 dirty=${3:-}
  echo "$st" > "$ROOT/status.$id"
  git -C "$REPO" worktree add -q -b "b/$id" "$ROOT/computenet-worktrees/$id" >/dev/null 2>&1
  [ -n "$dirty" ] && echo scratch > "$ROOT/computenet-worktrees/$id/dirty.txt"
  # age it past the --min-age-minutes floor
  find "$ROOT/computenet-worktrees/$id" -exec touch -t 202001010000 {} + 2>/dev/null
  true
}
run() { (cd "$REPO" && "$SCRIPT" "$@" 2>&1); }

pass=0; fail=0
check() { local name=$1 want=$2 got=$3; if [[ "$got" == *"$want"* ]]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL: $name — wanted <$want> in <$got>"; fi; }

out=$(run --dry-run); rc=$?
check "no worktrees -> says so" "no worktrees stranded" "$out"
check "no worktrees -> exit 0" "0" "$rc"

mk computenet-open open
out=$(run --dry-run)
check "open bead is left alone" "no worktrees stranded" "$out"

mk computenet-closed closed
out=$(run --dry-run)
check "closed bead is a candidate" "would remove" "$out"
[ -d "$ROOT/computenet-worktrees/computenet-closed" ] && pass=$((pass+1)) || { fail=$((fail+1)); echo "FAIL: --dry-run removed it"; }

mk computenet-dirty closed dirty
out=$(run --dry-run); rc=$?
check "closed but dirty -> SKIP, not removed" "DIRTY" "$out"
check "dirty candidate -> exit 1" "1" "$rc"

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
