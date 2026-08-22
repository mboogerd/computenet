#!/usr/bin/env bash
# Tests for run-series.sh's argument parsing and refusal arms (computenet-uk6m).
#
# Builds a throwaway git repo under a temp dir and copies run-series.sh into it at
# scripts/bench-series/run-series.sh, so the script's own REPO_ROOT resolution
# (dirname of BASH_SOURCE, two levels up) lands on the fixture repo rather than the
# real one. Stubs `uptime`, `getconf` and `java` on PATH so the quiescence check and
# the measuring-JVM pin are deterministic regardless of the host this runs on. Every
# case that must reach the dry-run print passes --dry-run, so the fixture never has to
# provide a working `gradlew` or `tee`d JMH log.
#
#   scripts/bench-series/run-series.test.sh
#
# Exits 0 if all cases pass, 1 otherwise, printing a per-case verdict and a final
# "N passed, M failed" line.
set -uo pipefail

SCRIPT_SRC=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/run-series.sh"}
[ -f "$SCRIPT_SRC" ] || { echo "not found: $SCRIPT_SRC" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/run-series-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

# --- fixture repo: REPO_ROOT for the copied script, isolated from the real repo. ----
REPO="$ROOT/repo"
mkdir -p "$REPO/scripts/bench-series"
cp "$SCRIPT_SRC" "$REPO/scripts/bench-series/run-series.sh"
chmod +x "$REPO/scripts/bench-series/run-series.sh"
git init --quiet "$REPO"
( cd "$REPO"
  git config user.email t@t
  git config user.name t
  git symbolic-ref HEAD refs/heads/main
  echo base > f
  git add f
  git commit --quiet -m base
) >/dev/null 2>&1

# --- stubbed dependencies on PATH: uptime, getconf, java. ---------------------------
mkdir -p "$ROOT/bin"

# getconf is only ever called for _NPROCESSORS_ONLN here; pin it so the load
# threshold (cores * 0.25) is deterministic across machines.
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

set_java_version() { # version_string, e.g. 21.0.2 or 17.0.9
  cat > "$ROOT/bin/java" <<EOF
#!/usr/bin/env bash
echo 'openjdk version "$1" 2024-01-16'
EOF
  chmod +x "$ROOT/bin/java"
}

# Defaults: below-threshold load, JDK 21 — the "everything is fine" baseline that
# individual cases override.
set_uptime "0.10"
set_java_version "21.0.2"

export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
check() { # name expected_rc want_substr [env_assignments...] -- args...
  local name=$1 want_rc=$2 want=$3; shift 3
  local -a env_assigns=()
  while [ "$1" != "--" ]; do env_assigns+=("$1"); shift; done
  shift # consume --
  local out rc
  out=$(cd "$REPO" && env "${env_assigns[@]+"${env_assigns[@]}"}" "$REPO/scripts/bench-series/run-series.sh" "$@" 2>&1)
  rc=$?
  if [ "$rc" = "$want_rc" ] && { [ -z "$want" ] || [[ "$out" == *"$want"* ]]; }; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    echo "FAIL: $name (rc=$rc want=$want_rc) out=<$out>"
  fi
}

# 1. No --host-state at all.
check "no --host-state -> exit 2" 2 "is required and has no default" \
  -- --dry-run

# 2. An unrecognised --host-state value.
check "invalid --host-state -> exit 2" 2 "must be 'quiesced' or 'shared'" \
  -- --host-state bogus --dry-run

# 3. An unknown argument.
check "unknown argument -> exit 2" 2 "unknown argument: --frobnicate" \
  -- --frobnicate

# 3b. --host-state as the last argument, with no value following it.
check "--host-state with no value -> exit 2" 2 "--host-state requires a value" \
  -- --host-state

# 3c. --selector as the last argument, with no value following it.
check "--selector with no value -> exit 2" 2 "--selector requires a value" \
  -- --selector

# 4. --host-state quiesced, load average above the threshold -> refused.
set_uptime "9.99"
check "quiesced above threshold -> REFUSED, exit 1" 1 "REFUSED: you attested 'quiesced'" \
  -- --host-state quiesced --dry-run
set_uptime "0.10"

# 5. --host-state quiesced, load average below the threshold -> proceeds past the
#    quiescence check (and, with the default JDK 21 stub, all the way to --dry-run).
check "quiesced below threshold -> proceeds" 0 "dry-run: nothing was executed" \
  -- --host-state quiesced --dry-run

# 6. A non-21 measuring JVM -> refused. --host-state shared skips the quiescence
#    check entirely so this exercises the JDK pin in isolation.
set_java_version "17.0.9"
check "non-21 measuring JVM -> REFUSED, exit 1" 1 "the series measures under JDK 21" \
  -- --host-state shared --dry-run
set_java_version "21.0.2"

# 7. BENCH_SERIES_JAVA points at a launcher that cannot run at all.
check "unrunnable BENCH_SERIES_JAVA -> REFUSED, exit 1" 1 "could not run" \
  "BENCH_SERIES_JAVA=$ROOT/no-such-java" -- --host-state shared --dry-run

# 8. A JDK-21 launcher -> proceeds all the way to --dry-run.
check "JDK-21 launcher -> proceeds" 0 "dry-run: nothing was executed" \
  -- --host-state shared --dry-run

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
