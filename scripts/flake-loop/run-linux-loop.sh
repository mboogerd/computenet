#!/usr/bin/env bash
# Run the :wire suite repeatedly inside a Linux container and record every failure.
#
# Built for computenet-dqy.38: computenet-dqy.34 measured the
# "timed out awaiting: collector announced" flake only on macOS (3/1140 suite runs,
# 2/240 fresh-JVM = 0.83%) and pinned a mechanism — java-websocket's unguarded
# setTcpNoDelay in doAccept — that provably cannot fire on Linux, where that
# setsockopt succeeds on a reset socket instead of throwing EINVAL. What was never
# measured is the END-TO-END rate on Linux. This script measures it.
#
# Bytecode is portable, so the classes are compiled once on the host with Gradle and
# the container only runs them: the mounts reproduce the host paths exactly, so the
# classpath string needs no rewriting.
#
# Usage:
#   scripts/flake-loop/run-linux-loop.sh [runs] [label]
#
# Environment:
#   IMAGE   container image carrying a JDK 21 (default groovy:4.0-jdk21, chosen
#           because it is usually already local; eclipse-temurin:21 costs a ~10
#           minute pull).
#   OUT     evidence directory (default <repo>/build/flake-loop).
#   CP      pre-resolved test runtime classpath; set it to skip the Gradle calls
#           when launching several containers in parallel from one shell.
#   EXPECT_TESTS  tests expected to execute per iteration. Optional (computenet-dqy.56):
#           SuiteLoop derives this from iteration 1's own executed-test count when it
#           isn't set, so there is nothing here to go stale as civictech.wire gains
#           tests. Set it only to pin a known value, e.g. to compare this run's count
#           against another platform's in the same report.
#
# THE HOST CONTROL ARM. A Linux zero is only worth something next to a positive
# control on a platform where the flake does fire, so run this alongside it — same
# instrument, same classes, no container:
#
#   CP=$(./gradlew -q --no-configuration-cache \
#          -I scripts/flake-loop/print-test-classpath.init.gradle.kts \
#          :wire:printTestClasspath | grep -v '^WARNING' | tr '\n' ':')
#   java -cp "$CP" scripts/flake-loop/SuiteLoop.java --package civictech.wire \
#     --runs 260 --out build/flake-loop-macos --label macos-control
#
# (Leave --expect-tests off: SuiteLoop derives it from this run's own iteration 1,
# so the Linux and macOS baselines never need hand-syncing — they differ by one
# test anyway, since WsListenerAcceptRstTest is @EnabledOnOs(MAC). The 2026-08-12
# control below predates that and passed --expect-tests 15 explicitly.)
#
# WHAT IT MEASURED, 2026-08-12 (computenet-dqy.38). 780 Linux suite runs
# (3 containers x 260, groovy:4.0-jdk21, Linux aarch64, JDK 21.0.11) against a
# same-instrument, same-wall-clock macOS control of 260 runs (JDK 26, host):
#
#   dqy.34's signature, "timed out awaiting: collector announced":
#     Linux 0/780   macOS 1/260 (WsPeerIdentityTest)
#   an announcement that never arrived at all, caught by WsAnnouncementStressTest's
#   own 15s lost-vs-slow instrument:
#     Linux 1/780 runs = 1 loss in ~39000 awaits (catch-up shape)   macOS 0/260
#
# Read both lines together. The macOS failure is the positive control: the harness
# does catch the flake this whole family is named after. The Linux failure is a
# DIFFERENT event in a test dqy.34 used to rule the announcement path OUT, and it
# is the finding — see the bead filed from it, and evidence/.
#
# 0/780 bounds the Linux rate of dqy.34's signature at 0.39% (rule of three, 95%),
# which excludes the macOS fresh-JVM 0.83% but NOT the macOS in-process 0.26%
# (3/1140). 0/780 vs 1/260 is Fisher p = 0.25: these two samples do not establish
# that Linux is cleaner, they only bound it.
set -euo pipefail

RUNS="${1:-400}"
LABEL="${2:-linux}"
IMAGE="${IMAGE:-groovy:4.0-jdk21}"
# Left unset by default: SuiteLoop derives the expected count from iteration 1's own
# executed-test count when --expect-tests is omitted (computenet-dqy.56), so a "0
# failures" result still can't quietly mean "0 tests reached" without a literal here
# that goes stale every time :wire gains a test. Set EXPECT_TESTS to pin a value.
EXPECT_TESTS="${EXPECT_TESTS:-}"

command -v docker >/dev/null || { echo "docker is required and is not on PATH" >&2; exit 1; }

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${OUT:-$REPO/build/flake-loop}"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

mkdir -p "$OUT"

if [[ -z "${CP:-}" ]]; then
  echo "== building :wire test classes on the host =="
  "$REPO/gradlew" -p "$REPO" --no-configuration-cache \
    -I "$REPO/scripts/flake-loop/print-test-classpath.init.gradle.kts" \
    :wire:testClasses -q >/dev/null

  CP="$("$REPO/gradlew" -p "$REPO" --no-configuration-cache \
    -I "$REPO/scripts/flake-loop/print-test-classpath.init.gradle.kts" \
    :wire:printTestClasspath -q 2>/dev/null \
    | grep -v '^WARNING' | grep -v '^$' | tr '\n' ':' | sed 's/:$//')"
fi

if [[ -z "$CP" ]]; then
  echo "could not resolve the :wire test runtime classpath" >&2
  exit 1
fi

EXPECT_ARGS=()
if [[ -n "$EXPECT_TESTS" ]]; then
  EXPECT_ARGS=(--expect-tests "$EXPECT_TESTS")
fi

echo "== running $RUNS iterations of the civictech.wire suite in $IMAGE =="
# Only the dependency cache is mounted, not all of $GRADLE_HOME: gradle.properties there
# can hold credentials, and the container needs nothing but the jars on $CP.
exec docker run --rm \
  -v "$REPO:$REPO:ro" \
  -v "$GRADLE_HOME/caches:$GRADLE_HOME/caches:ro" \
  -v "$OUT:/evidence" \
  -w "$REPO" \
  "$IMAGE" \
  java -cp "$CP" "$REPO/scripts/flake-loop/SuiteLoop.java" \
    --package civictech.wire --runs "$RUNS" --out /evidence --label "$LABEL" \
    "${EXPECT_ARGS[@]+"${EXPECT_ARGS[@]}"}"
