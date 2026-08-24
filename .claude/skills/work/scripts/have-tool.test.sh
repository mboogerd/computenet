#!/usr/bin/env bash
# Tests for have-tool.sh. Stubs the probed tools on PATH so the cases run the
# same on a box with or without docker/gh. Expect "6 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/have-tool.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/have-tool-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
check() { # tool expected-rc label
  local rc
  PATH="$ROOT/bin:/usr/bin:/bin" "$SCRIPT" "$1" >/dev/null 2>&1; rc=$?
  [ "$rc" = "$2" ] && ok "$3" || bad "$3 — rc=$rc, wanted $2"
}
stub() { printf '#!/bin/sh\n%s\n' "$2" > "$ROOT/bin/$1"; chmod +x "$ROOT/bin/$1"; }

# 1. a binary that is simply absent
check no-such-tool-xyz 1 "an absent binary fails"

# 2. an unprobed binary on PATH passes on presence alone
stub sometool 'exit 0'
check sometool 0 "a present unprobed binary passes"

# 3-4. docker: the binary alone is NOT enough — the daemon probe decides
#      (computenet-3k1l: docker binary present, daemon absent).
stub docker '[ "$1" = info ] && exit 1; exit 0'
check docker 1 "docker binary without a live daemon fails"
stub docker '[ "$1" = info ] && exit 0; exit 1'
check docker 0 "docker with a responding daemon passes"

# 5-6. gh: same shape, auth is the probe
stub gh '[ "$1 $2" = "auth status" ] && exit 1; exit 0'
check gh 1 "gh binary without auth fails"
stub gh '[ "$1 $2" = "auth status" ] && exit 0; exit 1'
check gh 0 "authenticated gh passes"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
