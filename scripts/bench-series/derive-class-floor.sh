#!/usr/bin/env bash
#
# One gate-checked, JDK-pinned invocation of the checkpointed per-class floor derivation
# (computenet-3omz). It runs and ingests exactly ONE `floorTool next`-sized unit, then
# exits — the command a person or a scheduled job actually invokes, once per short quiet
# window, until a class's derivation is complete.
#
# It generalises $HOME/computenet-runs/computenet-7v7m/derive-run.sh — the ad-hoc script
# that ran the real CellFootprintBenchmark derivation — into a committed driver over
# `:bench:floorTool`'s ledger CLI (computenet-3omz.2). The load-gate arithmetic and the
# JDK pin below are that script's, verbatim, mirroring scripts/bench-series/run-series.sh's
# house style; this script adds no policy beyond them. Every other refusal — an incomplete
# row set, a second measuring JVM, a second harness sha, a fourth observation, a
# plan/enumeration mismatch — is `floorTool`'s / the ledger's own; this script only
# surfaces it.
#
# USAGE
#   scripts/bench-series/derive-class-floor.sh --class <SimpleName> [--status-only]
#                                               [--ledger <dir>]
#
#   --class          Benchmark class's simple name, e.g. OperatorThroughputBenchmark.
#                     Required.
#   --status-only    Print the ledger's status (and, once complete, the `render`
#                     invocation to run next) without gating, building, or measuring
#                     anything. Safe to run any time, on any host state — this is the
#                     "what's left" command for an off-window check.
#   --ledger <dir>   Ledger directory. Default:
#                     $HOME/computenet-runs/floor-derivations/<class>/
#
#   DERIVE_FLOOR_JAVA   Absolute path to a JDK 21 `java` launcher. REQUIRED for a
#                       measuring invocation (not read for --status-only). There is no
#                       bare-`java` fallback: on this host bare `java` is JBR 25.0.2 with
#                       no JAVA_HOME set, and a derivation measured under it had to be
#                       redone entirely after review caught it post hoc (computenet-ahn0,
#                       the floor moved 0.593 -> 1.044). The launcher is verified by its
#                       own `-version` output before anything runs, and the unit's own
#                       JMH log banner is re-checked after, so a JDK that changes between
#                       those two points is still caught.
#
# PROVENANCE IS RECORDED PER UNIT, NOT AT RENDER TIME. Each unit is ingested with the
# working tree's HEAD sha read immediately before its measurement (computenet-tdby), so a
# derivation assembled across two checkouts is REFUSED at render naming both shas instead
# of being published under whichever checkout happened to be current last. The rendered
# findings block states the units' gathering window for the same reason. What is still not
# witnessed: the jar's own build provenance (computenet-7doz) — see step 4c.
#
# ONE INVOCATION, ONE UNIT, THEN EXIT. This script never loops waiting for the load gate
# and never retries on your behalf: a gate refusal prints the reading and exits non-zero.
# The retry procedure is re-running this exact command in a few minutes — deliberately
# the easy path, since a finished JMH run holds its own 1-minute load average up for
# 2-5 minutes afterwards (computenet-akfa's measured note; today's real derivation waited
# out refusals at 5.13, 4.41 and 4.21 this way). There is no --force / --allow-incomplete
# / --skip-gate escape from either the load gate or the JDK pin, and none from any ledger
# refusal.
#
# THE SCRIPT PASSES SELECTION ONLY. The only JMH arguments it ever adds beyond the
# benchmark regex and `-p` flags `floorTool next` itself prints are `-rf csv -rff <file>`
# (the results sink `floorTool ingest` reads). It never adds `-f`, `-wi`, `-i`, `-w`,
# `-r`, `-t`, `-bm`, or shrinks a unit to fit a window — the class's own annotation
# configuration is the configuration for all three runs of every row (ClassNoiseFloor.kt,
# `ClassFloorDerivation`'s "Decomposition" section).

set -euo pipefail

# Absolute, deliberately: `:bench:floorTool`'s JavaExec runs with `bench/` as its
# working directory, not the directory `./gradlew` is invoked from, so a RELATIVE
# `--jar bench/build/libs/bench-jmh.jar` (this path, typed at the repo root) used to
# resolve into a doubled `bench/bench/build/libs/bench-jmh.jar` that does not exist.
# `FloorTool.kt`'s `JarPath.resolve` now falls back to the repo root when a relative
# path misses the working directory (`computenet-x9e.15`), but this script still
# passes an absolute path — the fallback exists for a human copying commands by hand,
# not to make this script's own invocation ambiguous.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${REPO_ROOT}/bench/build/libs/bench-jmh.jar"
PINNED_JDK_MAJOR=21

CLASS=""
STATUS_ONLY=0
LEDGER_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --class)
      [[ $# -ge 2 ]] || { echo "--class requires a value" >&2; exit 2; }
      CLASS="$2"; shift 2 ;;
    --status-only)
      STATUS_ONLY=1; shift ;;
    --ledger)
      [[ $# -ge 2 ]] || { echo "--ledger requires a value" >&2; exit 2; }
      LEDGER_DIR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,61p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)
      echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${CLASS}" ]]; then
  echo "REFUSED: --class is required, e.g. --class CellFootprintBenchmark" >&2
  exit 2
fi

if [[ -z "${LEDGER_DIR}" ]]; then
  LEDGER_DIR="${HOME}/computenet-runs/floor-derivations/${CLASS}"
fi
mkdir -p "${LEDGER_DIR}"
LOADWATCH="${LEDGER_DIR}/loadwatch.txt"
LEDGER_FILE="${LEDGER_DIR}/ledger.txt"   # FloorDerivationLedger.LEDGER_FILE_NAME

# --------------------------------------------------------------------------------------
# floorTool wrapper: a clean refusal line instead of Gradle's BUILD FAILED stack-trace
# wall.
#
# `:bench:floorTool` exits non-zero (correctly) on every refusal, via a JavaExec task
# whose failure Gradle reports as its own "Execution failed for task ... FAILURE: Build
# failed with an exception" wall, on top of the tool's own "REFUSED: ..." line — both on
# stderr, both after `--quiet` (verified: `--quiet` only suppresses Gradle's *own*
# logging, not a failed task's report). Grepping the tool's own "REFUSED:" line out of
# that combined stderr, rather than teaching this script the ledger's refusal reasons, is
# what keeps this script's policy at zero: floorTool's message is reprinted verbatim, and
# an unrecognised (non-"REFUSED:") failure — a real Gradle/compile problem this script
# has no business interpreting — falls back to dumping the whole wall so nothing is
# hidden.
floor_tool() {
  local stdout_file stderr_file code arg quoted sq bs esc
  # Single-quote every argument before joining. `-PfloorArgs` is ONE Gradle property, so
  # this join is the only place an argument's boundaries can be recorded; passing "$*"
  # instead lost them, and a --jmh-config value containing spaces — the shape every
  # existing doc/bench/findings.md entry uses for that field — arrived at floorTool as
  # several arguments and was refused with "expected a --flag, found 'warmup'"
  # (computenet-71hu). floorTool tokenises the property itself, honouring exactly this
  # quoting, so the boundaries survive.
  # sq/bs/esc are built from escaped characters rather than written inline: a literal
  # '\'' sequence inside the double-quoted replacement below is NOT unescaped by bash and
  # silently over-escapes (verified).
  sq=\' ; bs=\\ ; esc="${sq}${bs}${sq}${sq}"
  quoted=""
  for arg in "$@"; do
    quoted="${quoted}${quoted:+ }${sq}${arg//${sq}/${esc}}${sq}"
  done
  stdout_file="$(mktemp)"
  stderr_file="$(mktemp)"
  set +e
  ( cd "${REPO_ROOT}" && ./gradlew --quiet :bench:floorTool -PfloorArgs="${quoted}" \
      >"${stdout_file}" 2>"${stderr_file}" )
  code=$?
  set -e
  cat "${stdout_file}"
  if [[ ${code} -ne 0 ]]; then
    if grep -q '^REFUSED:' "${stderr_file}"; then
      grep '^REFUSED:' "${stderr_file}" >&2
    else
      echo "floorTool failed without a REFUSED: line; full output:" >&2
      cat "${stderr_file}" >&2
    fi
  fi
  rm -f "${stdout_file}" "${stderr_file}"
  return "${code}"
}

# --------------------------------------------------------------------------------------
# The "it's complete, here's how to render it" hint, printed from the three places that
# detect completeness. One function rather than three copies because the three copies
# drifted apart once already, and because what it says changed materially with
# computenet-tdby: --harness-sha is no longer an argument the operator invents at render
# time. Each unit records the checkout it measured under (step 6 below), `render`
# publishes that sha and REFUSES a set spanning two, and the rendered findings block
# carries the units' own gathering window — so the caveat this used to print, telling a
# human to go read timestamps out of the ledger file by hand, is now a check.
print_render_hint() {
  echo "'${CLASS}' is complete. Render its findings block with:"
  echo "  ./gradlew -p ${REPO_ROOT} :bench:floorTool -PfloorArgs=\"render --ledger ${LEDGER_DIR} --derived-on <iso-date> --jmh-config '<text — spaces allowed, keep the quotes>'\""
  echo "  --harness-sha is NOT passed: the units attest the sha each measured at, render"
  echo "  publishes it, and a set spanning two shas is refused naming both. --derived-on"
  echo "  is still ONE date you choose; the block's own 'Gathering window:' line states"
  echo "  the span, so paste the block whole."
}

# --------------------------------------------------------------------------------------
# --status-only: report and exit. No gate, no build, no jar, no JAVA pin.
if [[ ${STATUS_ONLY} -eq 1 ]]; then
  if [[ ! -f "${LEDGER_FILE}" ]]; then
    echo "No ledger yet at ${LEDGER_DIR} for '${CLASS}'. Run without --status-only in a" \
      "quiesced window to plan and start it (needs ${JAR})."
    exit 0
  fi
  floor_tool status --ledger "${LEDGER_DIR}"
  code=$?
  if [[ ${code} -ne 0 ]]; then
    exit "${code}"
  fi
  if floor_tool render --ledger "${LEDGER_DIR}" \
      --derived-on "$(date -u '+%Y-%m-%d')" \
      --jmh-config "(placeholder — see below)" >/dev/null 2>&1; then
    echo
    print_render_hint
  fi
  exit 0
fi

# --------------------------------------------------------------------------------------
# 1. The load gate, attested TWICE per unit: here first and unconditionally, and again in
# step 4b immediately before the measurement itself (this script's own ledger builds sit
# between the two, and it is step 4b's reading that `ingest` records). Here it is checked
# before planning, before the jar,
# before the JDK pin — so a busy host is caught before this script does any other work,
# and a re-run after a refusal always re-attests the gate rather than skipping it because
# some other prerequisite (a plan, a jar) happened to already be satisfied. run-series.sh's
# arithmetic, verbatim (1-minute load average <= 0.25 x cores), and derive-run.sh's
# per-run mirror of it. Every reading — pass or refuse — is appended to the ledger's own
# loadwatch file.
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)"
LOAD_1M="$(uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/')"
THRESHOLD="$(awk -v c="${CORES}" 'BEGIN { printf "%.2f", c * 0.25 }')"
echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') class=${CLASS} cores=${CORES} 1min=${LOAD_1M} threshold=${THRESHOLD}" \
  | tee -a "${LOADWATCH}"

if awk -v l="${LOAD_1M}" -v t="${THRESHOLD}" 'BEGIN { exit !(l > t) }'; then
  cat >&2 <<MSG

REFUSED: 1-minute load average ${LOAD_1M} is above the quiesced threshold ${THRESHOLD}
(${CORES} cores x 0.25). The host is not quiesced.

This is not adjustable and there is no --force. A finished JMH run holds this reading up
on its own for a few minutes afterwards, so a refusal right after another unit ran is
likely that unit decaying, not new interference — wait a few minutes and re-run this
exact command:

  $0 --class ${CLASS} $([[ -n "${LEDGER_DIR}" ]] && echo "--ledger ${LEDGER_DIR}")
MSG
  echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') class=${CLASS} REFUSED 1min=${LOAD_1M}" >> "${LOADWATCH}"
  exit 1
fi
echo "Gate open: ${LOAD_1M} <= ${THRESHOLD}."

# --------------------------------------------------------------------------------------
# 2. First run for this class: the jar and a plan.
if [[ ! -f "${LEDGER_FILE}" ]]; then
  if [[ ! -f "${JAR}" ]]; then
    cat >&2 <<MSG
REFUSED: no ledger yet for '${CLASS}' and no jar at ${JAR}.

Planning a derivation needs the built JMH jar to enumerate the class's rows. Build it
OUTSIDE any quiesced window this script is meant to protect — a build on the measuring
host is exactly the load a gated derivation is supposed to exclude:

  ./gradlew :bench:jmhJar

Then re-run this command.
MSG
    exit 1
  fi
  echo "No ledger yet for '${CLASS}'; planning from ${JAR}..."
  floor_tool plan --ledger "${LEDGER_DIR}" --class "${CLASS}" --jar "${JAR}"
fi

# --------------------------------------------------------------------------------------
# 3. The JDK pin. No bare-`java` fallback — see this script's header.
if [[ -z "${DERIVE_FLOOR_JAVA:-}" ]]; then
  cat >&2 <<MSG
REFUSED: DERIVE_FLOOR_JAVA is not set.

There is no bare-'java' fallback: on this host bare 'java' is JBR 25.0.2 and JAVA_HOME is
unset, and a derivation measured under the wrong JDK has already had to be redone once in
full (computenet-ahn0). Set it to an absolute path to a JDK ${PINNED_JDK_MAJOR} launcher:

  DERIVE_FLOOR_JAVA=/path/to/jdk${PINNED_JDK_MAJOR}/bin/java $0 --class ${CLASS}
MSG
  exit 1
fi
case "${DERIVE_FLOOR_JAVA}" in
  /*) ;;
  *)
    echo "REFUSED: DERIVE_FLOOR_JAVA must be an absolute path, was '${DERIVE_FLOOR_JAVA}'" >&2
    exit 1
    ;;
esac
if ! JAVA_VERSION_LINE="$("${DERIVE_FLOOR_JAVA}" -version 2>&1 | head -1)"; then
  echo "REFUSED: could not run '${DERIVE_FLOOR_JAVA} -version'." >&2
  exit 1
fi
JAVA_MAJOR="$(printf '%s\n' "${JAVA_VERSION_LINE}" | sed -nE 's/.*version "([0-9]+).*/\1/p')"
echo "Measuring JVM: ${DERIVE_FLOOR_JAVA} -> ${JAVA_VERSION_LINE}"
if [[ "${JAVA_MAJOR}" != "${PINNED_JDK_MAJOR}" ]]; then
  cat >&2 <<MSG

REFUSED: DERIVE_FLOOR_JAVA (${DERIVE_FLOOR_JAVA}) reports '${JAVA_VERSION_LINE}', not JDK
${PINNED_JDK_MAJOR}. No fallback, no override.
MSG
  exit 1
fi

if [[ ! -f "${JAR}" ]]; then
  cat >&2 <<MSG
REFUSED: jar not found at ${JAR}.

Build it OUTSIDE the quiesced window: ./gradlew :bench:jmhJar
MSG
  exit 1
fi

# --------------------------------------------------------------------------------------
# 4. Ask the ledger what to run next, and run exactly that — selection only.
NEXT_OUTPUT="$(floor_tool next --ledger "${LEDGER_DIR}" --jar "${JAR}")"
echo "${NEXT_OUTPUT}"

if printf '%s\n' "${NEXT_OUTPUT}" | grep -q "^'${CLASS}' is complete"; then
  echo
  print_render_hint
  exit 0
fi

INVOCATION_LINE="$(printf '%s\n' "${NEXT_OUTPUT}" | grep "^next unit for '${CLASS}':")"
# Everything after "next unit for '<class>': " is the regex plus -p flags, verbatim from
# floorTool. Split on whitespace outside quotes is safe here: floorTool's own
# NextUnit.describeInvocation() never emits a value containing a space (row keys, method
# names, and @Param values in this repo's benchmark classes do not).
SELECTION_ARGS_LINE="${INVOCATION_LINE#next unit for \'${CLASS}\': }"

UNIT_N=1
while [[ -f "${LEDGER_DIR}/unit-${UNIT_N}.csv" ]]; do
  UNIT_N=$((UNIT_N + 1))
done
RESULTS="${LEDGER_DIR}/unit-${UNIT_N}.csv"
LOG="${LEDGER_DIR}/unit-${UNIT_N}.log"

# --------------------------------------------------------------------------------------
# 4b. Re-attest the gate IMMEDIATELY before the measurement (review repair on
# computenet-3omz.3). Step 1's reading is taken before this script does any work of its
# own, which is what makes a busy host cheap to detect — but `floorTool plan`/`next` are
# full Gradle invocations, so between step 1 and here this script has itself put load on
# the measuring host, every invocation, in the same direction each time. Unlike random
# interference that shows up as variance, a systematic pre-run build biases every unit
# the same way, so a single reading taken before it is not the reading that describes the
# measurement. derive-run.sh had nothing between its gate and its jar run; the ledger CLI
# puts a build there, so the gate is attested twice and it is THIS reading that goes to
# `ingest` below.
LOAD_1M="$(uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/')"
echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') class=${CLASS} unit=${UNIT_N} pre-run 1min=${LOAD_1M} threshold=${THRESHOLD}" \
  | tee -a "${LOADWATCH}"
if awk -v l="${LOAD_1M}" -v t="${THRESHOLD}" 'BEGIN { exit !(l > t) }'; then
  cat >&2 <<MSG

REFUSED: 1-minute load average ${LOAD_1M} is above the quiesced threshold ${THRESHOLD}
(${CORES} cores x 0.25) at the point of measurement. The gate was open when this
invocation started; the ledger build since then, or something else on the host, closed it.

Not adjustable, no --force. Nothing has been measured or ingested. Wait a few minutes and
re-run this exact command:

  $0 --class ${CLASS} --ledger ${LEDGER_DIR}
MSG
  echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') class=${CLASS} unit=${UNIT_N} REFUSED pre-run 1min=${LOAD_1M}" \
    >> "${LOADWATCH}"
  exit 1
fi
echo "Gate still open at measurement time: ${LOAD_1M} <= ${THRESHOLD}."

# --------------------------------------------------------------------------------------
# 4c. The harness sha THIS unit measures under, read here — immediately before the
# invocation — and not at render time (computenet-tdby). `harnessCommitSha` is published
# provenance: it is the commit a later reader checks out to re-derive the floor. Read at
# render time it is the LAST window's checkout, which for a set assembled over days is a
# commit that may have measured nothing. Per unit, for exactly the reason the banner check
# in step 5 is per unit.
#
# WHAT THIS DOES NOT WITNESS, stated here because it is the residual and not a detail: it
# is the working tree's HEAD, not the jar's provenance. ${JAR} is built once, outside the
# gate, and carries no record of the commit it was built from, so a rebuild at the same
# checkout is invisible, and a STALE jar carried across a checkout change is recorded
# under a sha its code did not come from. Closing that needs the build to stamp the jar
# (computenet-7doz).
HARNESS_SHA="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"
echo "Harness sha for unit ${UNIT_N}: ${HARNESS_SHA} (working tree HEAD; the jar's own"
echo "  build provenance is not recorded — computenet-7doz)"

echo
echo "Running unit ${UNIT_N}: ${SELECTION_ARGS_LINE}"
# shellcheck disable=SC2086
eval "\"${DERIVE_FLOOR_JAVA}\" -jar \"${JAR}\" ${SELECTION_ARGS_LINE} -rf csv -rff \"${RESULTS}\"" \
  2>&1 | tee "${LOG}"

# --------------------------------------------------------------------------------------
# 5. Banner check on THIS unit's log — per unit, not per class, because units of one
# class may be spread over days and the JDK on this host could change between them.
BANNER="$(grep -m1 -E '^# VM version' "${LOG}" || true)"
if [[ -z "${BANNER}" ]]; then
  echo "REFUSED: no '# VM version' banner line found in ${LOG}; the measuring JVM cannot" \
    "be established from this log." >&2
  exit 1
fi
echo "Unit ${UNIT_N} banner: ${BANNER}"
case "${BANNER}" in
  *"JDK ${PINNED_JDK_MAJOR}"*) ;;
  *)
    echo "REFUSED: unit ${UNIT_N}'s banner is not JDK ${PINNED_JDK_MAJOR}: ${BANNER}" >&2
    exit 1
    ;;
esac

# --------------------------------------------------------------------------------------
# 6. Ingest, then report status. --threshold is the number step 4b actually gated on
# (${THRESHOLD}, cores x 0.25 read once at step 1 and reused unchanged) — passed
# explicitly rather than left for floorTool to recompute, so a ledger unit whose runner
# gated on some other number is refused instead of silently accepted (computenet-b5xt).
echo
echo "Ingesting unit ${UNIT_N}..."
floor_tool ingest --ledger "${LEDGER_DIR}" --results "${RESULTS}" --log "${LOG}" \
  --load "${LOAD_1M}" --cores "${CORES}" --harness-sha "${HARNESS_SHA}" \
  --threshold "${THRESHOLD}"

echo
floor_tool status --ledger "${LEDGER_DIR}"

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') class=${CLASS} unit=${UNIT_N} FINISHED" \
  >> "${LOADWATCH}"

if floor_tool render --ledger "${LEDGER_DIR}" \
    --derived-on "$(date -u '+%Y-%m-%d')" \
    --jmh-config "(placeholder)" >/dev/null 2>&1; then
  echo
  print_render_hint
fi
