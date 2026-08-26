#!/usr/bin/env bash
# Tests for derive-class-floor.sh's argument parsing and refusal arms (computenet-ktbw).
#
# Mirrors run-series.test.sh's structure and stubbing conventions: builds a throwaway
# git repo under a temp dir and copies derive-class-floor.sh into it at
# scripts/bench-series/derive-class-floor.sh, so the script's own REPO_ROOT resolution
# (dirname of BASH_SOURCE, two levels up) lands on the fixture repo rather than the real
# one. Stubs `uptime`, `getconf` and `java` on PATH so the quiescence gate and the
# DERIVE_FLOOR_JAVA pin are deterministic regardless of the host this runs on.
#
# None of the arms below reach `floor_tool` (the `./gradlew :bench:floorTool` wrapper):
# every case either fails before the ledger-file existence check, or is set up with a
# ledger.txt PRE-SEEDED in its own --ledger directory so the "no ledger yet -> plan from
# the jar" branch (the only branch that shells out to Gradle) is skipped. That is what
# lets this run without a JMH measurement, without a quiesced host and without a real
# `bench-jmh.jar`.
#
#   scripts/bench-series/derive-class-floor.test.sh
#
# Exits 0 if all cases pass, 1 otherwise, printing a per-case verdict and a final
# "N passed, M failed" line.
set -uo pipefail

SCRIPT_SRC=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/derive-class-floor.sh"}
[ -f "$SCRIPT_SRC" ] || { echo "not found: $SCRIPT_SRC" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/derive-class-floor-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

# --- fixture repo: REPO_ROOT for the copied script, isolated from the real repo. ----
REPO="$ROOT/repo"
mkdir -p "$REPO/scripts/bench-series"
cp "$SCRIPT_SRC" "$REPO/scripts/bench-series/derive-class-floor.sh"
chmod +x "$REPO/scripts/bench-series/derive-class-floor.sh"
git init --quiet "$REPO"
( cd "$REPO"
  git config user.email t@t
  git config user.name t
  git symbolic-ref HEAD refs/heads/main
  echo base > f
  git add f
  git commit --quiet -m base
) >/dev/null 2>&1

# --- stubbed dependencies on PATH: uptime, getconf. ---------------------------------
mkdir -p "$ROOT/bin"

# getconf is only ever called for _NPROCESSORS_ONLN here; pin it so the load threshold
# (cores * 0.25) is deterministic across machines.
cat > "$ROOT/bin/getconf" <<'EOF'
#!/usr/bin/env bash
echo 4
EOF
chmod +x "$ROOT/bin/getconf"

set_uptime() { # load_1m
  cat > "$ROOT/bin/uptime" <<EOF
#!/usr/bin/env bash
echo "up 1 day, 2:34, 3 users, load averages: $1 1.10 1.05"
EOF
  chmod +x "$ROOT/bin/uptime"
}

# Default: below-threshold load — the "quiesced" baseline every case overrides when it
# needs the gate to refuse.
set_uptime "0.10"

export PATH="$ROOT/bin:$PATH"

# --- DERIVE_FLOOR_JAVA launchers, at their own absolute paths (never on PATH: the
# script requires an explicit absolute-path env var, never a bare-`java` fallback). ---
make_java_launcher() { # path version_string
  cat > "$1" <<EOF
#!/usr/bin/env bash
echo 'openjdk version "$2" 2024-01-16'
EOF
  chmod +x "$1"
}

JAVA21="$ROOT/jdk21-java"
make_java_launcher "$JAVA21" "21.0.2"

JAVA17="$ROOT/jdk17-java"
make_java_launcher "$JAVA17" "17.0.9"

# --- ledger pre-seeding: a ledger.txt already present skips the "no ledger yet -> plan
# from the jar" branch, which is the only branch that shells out to Gradle. Every arm
# past the quiescence gate uses its own seeded ledger dir so cases can't see each
# other's state. -----------------------------------------------------------------------
seed_ledger() { # dir
  mkdir -p "$1"
  echo "dummy ledger — not read by any arm exercised here" > "$1/ledger.txt"
}

pass=0; fail=0
check() { # name expected_rc want_substr [env_assignments...] -- args...
  local name=$1 want_rc=$2 want=$3; shift 3
  local -a env_assigns=()
  while [ "$1" != "--" ]; do env_assigns+=("$1"); shift; done
  shift # consume --
  local out rc
  out=$(cd "$REPO" && env "${env_assigns[@]+"${env_assigns[@]}"}" "$REPO/scripts/bench-series/derive-class-floor.sh" "$@" 2>&1)
  rc=$?
  if [ "$rc" = "$want_rc" ] && { [ -z "$want" ] || [[ "$out" == *"$want"* ]]; }; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    echo "FAIL: $name (rc=$rc want=$want_rc) out=<$out>"
  fi
}

# 1. Missing --class.
check "missing --class -> exit 2" 2 "REFUSED: --class is required" \
  --

# 2. --status-only with no ledger yet: reports and exits 0, no gate, no build, no JMH.
check "--status-only, no ledger -> reports, exit 0" 0 "No ledger yet" \
  -- --class Foo --status-only --ledger "$ROOT/ledger-status-only"

# 3. The quiescence gate refusing (uptime stubbed above threshold).
set_uptime "9.99"
check "quiescence gate refuses -> REFUSED, exit 1" 1 "REFUSED: 1-minute load average" \
  -- --class Foo --ledger "$ROOT/ledger-gate-refuse"
set_uptime "0.10"

# 4 & 5. The quiescence gate accepting, then the run proceeding to fail on jar-absence.
# Ledger pre-seeded (skips the plan-from-jar branch) and DERIVE_FLOOR_JAVA a valid JDK 21
# launcher, so the ONLY thing left to fail on is the missing bench-jmh.jar — the
# "accepted-then-jar-absent" arm. Checked twice against the one run: once for the gate's
# own acceptance message, once for the final refusal.
LEDGER_JAR_ABSENT="$ROOT/ledger-jar-absent"
seed_ledger "$LEDGER_JAR_ABSENT"
check "quiescence gate accepts -> proceeds past it" 1 "Gate open:" \
  "DERIVE_FLOOR_JAVA=$JAVA21" -- --class Foo --ledger "$LEDGER_JAR_ABSENT"
check "accepted, then jar absent -> REFUSED, exit 1" 1 "REFUSED: jar not found at" \
  "DERIVE_FLOOR_JAVA=$JAVA21" -- --class Foo --ledger "$LEDGER_JAR_ABSENT"

# 6. DERIVE_FLOOR_JAVA unset.
LEDGER_JAVA_UNSET="$ROOT/ledger-java-unset"
seed_ledger "$LEDGER_JAVA_UNSET"
check "DERIVE_FLOOR_JAVA unset -> REFUSED, exit 1" 1 "REFUSED: DERIVE_FLOOR_JAVA is not set." \
  -- --class Foo --ledger "$LEDGER_JAVA_UNSET"

# 7. DERIVE_FLOOR_JAVA set to a relative path.
LEDGER_RELATIVE="$ROOT/ledger-relative-path"
seed_ledger "$LEDGER_RELATIVE"
check "DERIVE_FLOOR_JAVA relative path -> REFUSED, exit 1" 1 "must be an absolute path" \
  "DERIVE_FLOOR_JAVA=relative/jdk/bin/java" -- --class Foo --ledger "$LEDGER_RELATIVE"

# 8. DERIVE_FLOOR_JAVA set to a wrong-major-version launcher.
LEDGER_WRONG_MAJOR="$ROOT/ledger-wrong-major"
seed_ledger "$LEDGER_WRONG_MAJOR"
check "DERIVE_FLOOR_JAVA wrong major -> REFUSED, exit 1" 1 "No fallback, no override." \
  "DERIVE_FLOOR_JAVA=$JAVA17" -- --class Foo --ledger "$LEDGER_WRONG_MAJOR"

# 9. DERIVE_FLOOR_JAVA set to a launcher that cannot run at all (mirrors run-series's
# "unrunnable BENCH_SERIES_JAVA" case; not one of the seven named arms but the same
# failure shape, cheap to cover).
LEDGER_UNRUNNABLE="$ROOT/ledger-unrunnable"
seed_ledger "$LEDGER_UNRUNNABLE"
check "unrunnable DERIVE_FLOOR_JAVA -> REFUSED, exit 1" 1 "could not run" \
  "DERIVE_FLOOR_JAVA=$ROOT/no-such-java" -- --class Foo --ledger "$LEDGER_UNRUNNABLE"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
