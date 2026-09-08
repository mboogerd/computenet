#!/usr/bin/env bash
# Sample ONE test method many times, a FRESH JVM per iteration, recording every
# iteration's full stdout. Built for computenet-r13k: measuring the per-sweep
# failure rate of GcSafetySweepTest's STABLE arm, whose evidence lives in the
# test's own println block (the `attribution=` clause) rather than in the
# assertion message, so the stdout is the measurement and must be kept.
#
# Why a fresh JVM per iteration rather than SuiteLoop's --runs N. SuiteLoop reuses
# one JVM, which is what makes a 400-run :wire sample affordable. This sweep is
# different on two counts: one iteration already costs ~4s (the sweep itself runs
# 200 seeds in-process), so JVM startup is noise rather than the dominant cost;
# and the reclaim diagnostics keep process-wide per-tagSource maps
# (SetCell.incarnations, mintedHere) that a reused JVM would carry across
# iterations. Those maps are diagnostic-only and read by no protocol path, so
# reuse would probably be sound — but "probably sound" is not what you want
# underneath a rate that is the deliverable, and the cost of removing the doubt
# is ~1.5s per iteration.
#
# Usage:
#   scripts/flake-loop/run-method-loop.sh <runs> <label> '<fqcn#method name>'
#
# Environment:
#   MODULE  Gradle module whose test runtime classpath is used (default :kernel).
#   OUT     evidence directory (default <repo>/build/method-loop/<label>).
#   CP      pre-resolved classpath; set it to skip the Gradle call.
#
# Output, per iteration: OUT/iters/<n>.log holding that JVM's whole stdout+stderr.
# On stdout: one line per iteration, then a SUMMARY line. Load average is sampled
# at the start and end of the sample and printed with it, because a flake rate
# measured under contention is not comparable with one measured idle — every rate
# recorded for this class in doc/kernel-lane-findings.md carries its load range
# for that reason.
set -uo pipefail

RUNS="${1:?usage: run-method-loop.sh <runs> <label> '<fqcn#method>'}"
LABEL="${2:?usage: run-method-loop.sh <runs> <label> '<fqcn#method>'}"
METHOD="${3:?usage: run-method-loop.sh <runs> <label> '<fqcn#method>'}"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="${MODULE:-:kernel}"
OUT="${OUT:-${REPO}/build/method-loop/${LABEL}}"
mkdir -p "${OUT}/iters"

if [ -z "${CP:-}" ]; then
  CP=$("${REPO}/gradlew" -q -p "${REPO}" --no-configuration-cache \
        -I "${REPO}/scripts/flake-loop/print-test-classpath.init.gradle.kts" \
        "${MODULE}:printTestClasspath" | grep -v '^WARNING' | grep -v '^$' | tr '\n' ':')
fi
if [ -z "${CP}" ]; then echo "FATAL: empty classpath for ${MODULE}" >&2; exit 2; fi

LOAD_START=$(uptime | sed 's/.*load averages*: //')
echo "method-loop label=${LABEL} runs=${RUNS} method=${METHOD}"
echo "method-loop out=${OUT} loadStart=[${LOAD_START}] started=$(date -u +%FT%TZ)"

red=0
for i in $(seq 1 "${RUNS}"); do
  log="${OUT}/iters/${i}.log"
  # Each iteration is its own SuiteLoop --runs 1. --expect-tests 1 is passed
  # explicitly and is load-bearing: a JVM whose selector matched nothing would
  # otherwise report failures=0, and a zero-test iteration read as a green one is
  # precisely how a sample comes to measure nothing while still looking like a
  # measurement (SuiteLoop's own header, computenet-dqy.56).
  ( cd "${REPO}" && java -cp "${CP}" scripts/flake-loop/SuiteLoop.java \
      --method "${METHOD}" --runs 1 --expect-tests 1 \
      --out "${OUT}/suiteloop" --label "${LABEL}-${i}" ) > "${log}" 2>&1
  line=$(grep -m1 'iter=1 tests=' "${log}" || true)
  if [ -z "${line}" ]; then
    echo "${LABEL} iter=${i} NO-SUITELOOP-LINE — see ${log}"
    continue
  fi
  seeds=$(grep -m1 -o 'FENCE-ATTRIBUTED diverging seeds=\[[^]]*\]' "${log}" || true)
  case "${line}" in
    *"failures=0"*) verdict=GREEN ;;
    *) verdict=RED; red=$((red + 1)) ;;
  esac
  echo "${LABEL} iter=${i} ${verdict} ${line#* } ${seeds}"
done

LOAD_END=$(uptime | sed 's/.*load averages*: //')
echo "SUMMARY label=${LABEL} runs=${RUNS} red=${red} loadStart=[${LOAD_START}] loadEnd=[${LOAD_END}] finished=$(date -u +%FT%TZ)"
