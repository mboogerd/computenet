#!/usr/bin/env bash
#
# One run of the BEN1 regression-tracking series (computenet-b7k4).
#
# Builds the JMH jar, runs each pinned selector under pinned knobs, stores the raw
# results and log under bench/series/runs/<runId>/, compares every row against that
# benchmark's own historical band, and appends the run to bench/series/series.csv.
#
# The full design — the storage decision, the pinned environment, the band, and why
# this is a local lane rather than a GitHub Actions workflow — is in
# doc/bench/regression-series.md. Read that before changing anything here.
#
# USAGE
#   scripts/bench-series/run-series.sh [--host-state quiesced|shared] [--dry-run]
#                                      [--selector <name>] [--no-append]
#
#   --host-state   Your attestation about the host (default: refuses, see below).
#   --selector     Run only the named selector from SELECTORS. Repeatable.
#   --no-append    Compare and print, but do not write to the series file.
#   --dry-run      Print what would run and exit. Runs no benchmark.
#
#   BENCH_SERIES_JAVA   Launcher for the MEASURING JVM. Must be JDK 21 (the module's
#                       toolchain); defaults to `java` and refuses if that is not 21.
#
# THE HOST-STATE ATTESTATION IS NOT OPTIONAL AND IS NOT GUESSED.
# Nothing here can prove a machine was idle. The script performs a cheap sanity check
# (load average vs core count) and REFUSES to record a run as quiesced when that check
# disagrees with you, but it cannot do the converse: a low load average is not proof of
# quiescence, so you must still state it. A run recorded as SHARED is kept as an
# observation and excluded from every tolerance band — see HostState in
# bench/src/main/kotlin/civictech/bench/series/Series.kt.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERIES_FILE="${REPO_ROOT}/bench/series/series.csv"
RUNS_DIR="${REPO_ROOT}/bench/series/runs"
JMH_JAR="${REPO_ROOT}/bench/build/libs/bench-jmh.jar"

# --------------------------------------------------------------------------------------
# THE PINNED SELECTOR SET.
#
# One JMH invocation per entry, because RunKnobs.fromJmhLog and MeasuringJvm.fromJmhLog
# both REFUSE a log that states a knob two different ways — and this module's benchmark
# classes declare three different modes (AverageTime, Throughput, SingleShotTime). A
# single invocation covering all of them would produce a log describing no one
# configuration, which is exactly the refusal those readers exist for. So the series is
# organised per run unit: one selector, one results file, one log, one ingest.
#
# Format: <name>|<JMH regex>|<extra JMH flags>
#
# Start small and grow deliberately. Every selector added here costs its full JMH time on
# every scheduled run, forever, and a series is only worth having for quantities somebody
# will actually act on. SmokeBenchmark.baseline is first because it is the drift check
# NOISE_FLOOR was derived for and never wired to (see Dispersion.kt's DEMOTED section).
SELECTORS=(
  "smoke|civictech\\.bench\\.micro\\.SmokeBenchmark\\.baseline|"
)

# --------------------------------------------------------------------------------------
# THE PINNED KNOBS.
#
# Stated on the command line rather than left to each benchmark's annotations, because
# -f/-wi/-i override annotations and a series whose knobs drift with a source edit is not
# a series. These are JMH 1.37's own defaults, which is what doc/bench/findings.md's
# 2026-08-18 NOISE_FLOOR derivation used; the point is that they are now WRITTEN DOWN.
# Changing any of them changes the EnvironmentFingerprint, which starts a fresh
# population rather than corrupting the existing one — the comparator will report
# InsufficientHistory until three runs accumulate under the new knobs. That is the
# intended behaviour, not a bug to work around.
FORKS=5
WARMUP_ITERATIONS=5
MEASUREMENT_ITERATIONS=5

# The major version of the JDK the sweep must MEASURE under, matching the module's
# Gradle toolchain. See the pin check below for why this is not left to `java`.
PINNED_JDK_MAJOR=21

HOST_STATE=""
DRY_RUN=0
APPEND=1
ONLY_SELECTORS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host-state) [[ $# -ge 2 ]] || { echo "--host-state requires a value" >&2; exit 2; }
                  HOST_STATE="$2"; shift 2 ;;
    --selector)   [[ $# -ge 2 ]] || { echo "--selector requires a value" >&2; exit 2; }
                  ONLY_SELECTORS+=("$2"); shift 2 ;;
    --no-append)  APPEND=0; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    -h|--help)    sed -n '2,31p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${HOST_STATE}" ]]; then
  cat >&2 <<'MSG'
--host-state is required and has no default.

It is your attestation that the machine was, or was not, doing anything else while the
sweep ran. It is not inferable after the fact, and it decides whether this run may seed
the tolerance band every later run is judged against. A first entry measured under
interference is worse than no entry, because it poisons the band silently.

  --host-state quiesced   no other interactive session, no build, no scheduled scan
  --host-state shared     retained as an observation; excluded from band formation
MSG
  exit 2
fi

case "${HOST_STATE}" in
  quiesced|shared) ;;
  *) echo "--host-state must be 'quiesced' or 'shared', was '${HOST_STATE}'" >&2; exit 2 ;;
esac

# --------------------------------------------------------------------------------------
# The quiescence sanity check. One-directional on purpose: it can catch you claiming
# quiesced on a busy machine, and it can NEVER confirm quiescence. A machine can be idle
# by load average while a scheduled scan is about to start, or while another agent
# session is between builds.
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)"
LOAD_1M="$(uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/')"
LOAD_THRESHOLD="$(awk -v c="${CORES}" 'BEGIN { printf "%.2f", c * 0.25 }')"

echo "Host: ${CORES} cores, 1-minute load average ${LOAD_1M} (quiesced threshold ${LOAD_THRESHOLD})"

if [[ "${HOST_STATE}" == "quiesced" ]]; then
  if awk -v l="${LOAD_1M}" -v t="${LOAD_THRESHOLD}" 'BEGIN { exit !(l > t) }'; then
    cat >&2 <<MSG

REFUSED: you attested 'quiesced', but the 1-minute load average is ${LOAD_1M} on
${CORES} cores, above the ${LOAD_THRESHOLD} threshold. Something is running.

This check is one-directional: it can catch a wrong 'quiesced' claim, and it can never
confirm a right one. Either quiesce the machine and re-run, or re-run with
--host-state shared to keep the observation out of the tolerance bands.
MSG
    exit 1
  fi
fi

# --------------------------------------------------------------------------------------
# THE MEASURING JVM IS PINNED, NOT INHERITED (review repair on computenet-b7k4).
#
# doc/bench/regression-series.md §3 pins `jvmVendor`/`jvmVersion` to the module's
# toolchain JDK. That pin has to hold for the JVM that RUNS the sweep — the one this
# script launches the JMH jar with — and not merely for the Gradle task that ingests the
# results afterwards, which measures nothing. Bare `java` on a developer machine is
# routinely something else: measured on this repository's own host, 2026-08-22, `java`
# was JDK 25.0.2 while the toolchain is 21, and a sweep launched with it would have been
# recorded, correctly, as a JDK 25 run.
#
# The failure that guard prevents is the reassuring one. A wrong-JDK run does not corrupt
# an existing band — `EnvironmentFingerprint` puts it in a fresh population — it produces
# a series that quietly never accumulates three comparable entries and answers
# `InsufficientHistory` forever, which reads as "young series" rather than "misconfigured
# lane". It matters most under a scheduler: §6 already notes that a `launchd` agent does
# not inherit a login shell's PATH, so the unattended run is exactly the one whose `java`
# nobody is watching.
#
# Set BENCH_SERIES_JAVA to a JDK ${PINNED_JDK_MAJOR} launcher when `java` is not one.
JAVA_CMD="${BENCH_SERIES_JAVA:-java}"
if ! JAVA_VERSION_LINE="$("${JAVA_CMD}" -version 2>&1 | head -1)"; then
  echo "REFUSED: could not run '${JAVA_CMD} -version'. Set BENCH_SERIES_JAVA to a JDK ${PINNED_JDK_MAJOR} launcher." >&2
  exit 1
fi
JAVA_MAJOR="$(printf '%s\n' "${JAVA_VERSION_LINE}" | sed -nE 's/.*version "([0-9]+).*/\1/p')"

echo "Measuring JVM: ${JAVA_CMD} -> ${JAVA_VERSION_LINE}"

if [[ "${JAVA_MAJOR}" != "${PINNED_JDK_MAJOR}" ]]; then
  cat >&2 <<MSG

REFUSED: the series measures under JDK ${PINNED_JDK_MAJOR}, but '${JAVA_CMD}' is
${JAVA_VERSION_LINE}.

This is not a formality. The JDK is part of EnvironmentFingerprint, so a run under a
different one is not compared against the existing rows at all — it silently starts a
fresh population and reports InsufficientHistory, forever, if the JDK keeps changing.

  BENCH_SERIES_JAVA=/path/to/jdk${PINNED_JDK_MAJOR}/bin/java scripts/bench-series/run-series.sh ...

MSG
  exit 1
fi

RUN_ID="$(date -u '+%Y-%m-%dT%H-%M-%SZ')"
RUN_TIMESTAMP="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
# The working-tree HEAD, read before the jar below is (re)built. This catches a dirty
# bench/src (the warning just below) but NOT a stale jar left over from a prior checkout
# that :bench:jmhJar's own up-to-date check declined to rebuild -- SeriesCli's --jar check
# (computenet-0ado) is what closes that gap, by comparing this value against the jar's OWN
# stamped provenance and refusing on disagreement rather than recording this value
# unconditionally.
HARNESS_SHA="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"

if ! git -C "${REPO_ROOT}" diff --quiet HEAD -- bench/src; then
  echo
  echo "WARNING: bench/src has uncommitted changes. The harness commit recorded for this"
  echo "run (${HARNESS_SHA}) will not describe what actually ran. Commit first if this"
  echo "run is meant to enter the series."
fi

RUN_DIR="${RUNS_DIR}/${RUN_ID}"

echo
echo "Run id:        ${RUN_ID}"
echo "Harness:       ${HARNESS_SHA}"
echo "Host state:    ${HOST_STATE}"
echo "Knobs:         -f ${FORKS} -wi ${WARMUP_ITERATIONS} -i ${MEASUREMENT_ITERATIONS}"
echo "Artifacts:     ${RUN_DIR}"
echo "Series file:   ${SERIES_FILE}"
echo "Append:        $([[ ${APPEND} -eq 1 ]] && echo yes || echo 'no (--no-append)')"
echo

for entry in "${SELECTORS[@]}"; do
  IFS='|' read -r name regex extra <<<"${entry}"
  if [[ ${#ONLY_SELECTORS[@]} -gt 0 ]] && ! printf '%s\n' "${ONLY_SELECTORS[@]}" | grep -qx "${name}"; then
    continue
  fi
  echo "  selector '${name}': ${regex} ${extra}"
done

if [[ ${DRY_RUN} -eq 1 ]]; then
  echo
  echo "--dry-run: nothing was executed."
  exit 0
fi

mkdir -p "${RUN_DIR}"

# The jar, not `./gradlew :bench:jmh` — the same procedure doc/bench/findings.md's
# entries used. Running the jar directly keeps the Gradle daemon, the build's own JVM and
# any configuration work off the measuring host during the sweep.
echo
echo "Building the JMH jar..."
(cd "${REPO_ROOT}" && ./gradlew :bench:jmhJar)

for entry in "${SELECTORS[@]}"; do
  IFS='|' read -r name regex extra <<<"${entry}"
  if [[ ${#ONLY_SELECTORS[@]} -gt 0 ]] && ! printf '%s\n' "${ONLY_SELECTORS[@]}" | grep -qx "${name}"; then
    continue
  fi

  results="${RUN_DIR}/${name}.csv"
  log="${RUN_DIR}/${name}.log"

  echo
  echo "=== ${name} ==="
  # 2>&1 | tee: the log is not a convenience. It is the ONLY record of which JVM, which
  # knobs and which host produced these numbers — the results file carries no such
  # columns — and SeriesIngest refuses a run whose log cannot answer.
  # shellcheck disable=SC2086
  "${JAVA_CMD}" -jar "${JMH_JAR}" \
    "${regex}" \
    -f "${FORKS}" -wi "${WARMUP_ITERATIONS}" -i "${MEASUREMENT_ITERATIONS}" \
    -rf csv -rff "${results}" \
    ${extra} 2>&1 | tee "${log}"

  command="compare"
  [[ ${APPEND} -eq 1 ]] && command="append"

  echo
  (cd "${REPO_ROOT}" && ./gradlew --quiet :bench:benchSeries -PseriesArgs="\
${command} \
--results ${results} \
--series ${SERIES_FILE} \
--run-id ${RUN_ID} \
--timestamp ${RUN_TIMESTAMP} \
--host-state ${HOST_STATE} \
--harness-sha ${HARNESS_SHA} \
--jar ${JMH_JAR}")
done

echo
echo "Done. Raw artifacts: ${RUN_DIR}"
if [[ ${APPEND} -eq 1 ]]; then
  echo "The series file and the run directory are both tracked — commit them together,"
  echo "so a series row and the raw artifacts it was derived from land in one commit."
fi
