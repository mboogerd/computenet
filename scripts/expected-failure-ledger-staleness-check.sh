#!/usr/bin/env bash
#
# Regression check for computenet-0gnm: the expected-failure ledger [CHA2-45] must not
# render a report file left behind by an EARLIER build as the current run's standing
# failures when `:kernel:test` does not execute in this build.
#
# `kernel/build/reports/expected-failures/**` is deliberately NOT a declared output of the
# `Test` task (an absolute machine-specific path would cost cross-machine build-cache hits),
# so a FROM-CACHE or UP-TO-DATE `:kernel:test` neither truncates the file via its `doFirst`
# nor restores it from the cache. Before the fix, whatever happened to be on disk was printed
# with a count and no caveat — indistinguishable from a genuine result.
#
# Structure. One populating run, then two FROM-CACHE runs, each with a different flavour of
# leftover file:
#
#   A. A file carrying THE PREVIOUS BUILD'S OWN STAMP, with an extra entry appended — the
#      realistic trigger: one worktree, branch A's ledger still on disk while branch B's
#      `:kernel:test` comes back from the cache.
#   B. A file with no stamp line at all — a ledger written before this mechanism existed, or
#      by a harness that appended without `doFirst` ever running.
#
# Ahead of those, the two populating runs check the property the whole fix rests on: that the
# stamp is per BUILD and not per CONFIGURATION. `org.gradle.configuration-cache=true` means a
# value computed while configuring this script is computed once and replayed for every later
# build; a nonce held there would match a stale file, and the defect would be back with no
# outward sign. Two identical runs (identical command lines, so the configuration cache entry
# is reused) must therefore write two DIFFERENT stamps.
#
# Each scenario deletes the Test task's DECLARED outputs (test-results, the HTML report)
# first: without that the re-run is UP-TO-DATE rather than FROM-CACHE. That the ledger
# survives the deletion is the whole hazard — it is not a declared output, so nothing
# restores or clears it.
#
# The populating run also checks the positive direction: a stamped ledger must still render
# normally, and its stamp line must not be counted as an entry.
#
# Exit codes: 0 pass, 1 regression (a leftover ledger was rendered as this run's result),
# 2 precondition not met (a run failed, or was not FROM-CACHE, so nothing was exercised).
#
# Costs two filtered `:kernel:test` executions plus two cache hits. Not wired into any Gradle
# task or CI lane: it mutates `kernel/build/` and forces cache round-trips, which is not
# something an ordinary build should do to itself.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FILTER='civictech.cell.repro.ExpectedFailureSelfTest'
LEDGER="kernel/build/reports/expected-failures/standing.tsv"
STALE_LINE=$'STALE.FakeTest.from-an-earlier-run\tFAKE-SIG\tfake-owner\tthis line was never produced by this build\tnowhere'
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
rc=0

run_gradle() { # $1 = log name, $2.. = extra gradle args
    local log="$1"; shift
    if ! ./gradlew :kernel:test --tests "$FILTER" --build-cache --console=plain "$@" \
            > "$WORK/$log.log" 2>&1; then
        echo "PRECONDITION FAILED: run '$log' did not succeed; tail follows" >&2
        tail -40 "$WORK/$log.log" >&2
        exit 2
    fi
}

echo "== populating the build cache for :kernel:test --tests $FILTER (run 1 of 2)"
run_gradle populate1 --rerun
if ! grep -q 'Standing expected failures (@ExpectedFailure): 0$' "$WORK/populate1.log"; then
    echo "PRECONDITION FAILED: the populating run, whose ledger this build itself wrote," >&2
    echo "did not render as an ordinary result. Finalizer output:" >&2
    grep -A2 'Standing expected failures' "$WORK/populate1.log" >&2 || true
    exit 2
fi
echo "   positive path OK: a ledger this build wrote renders as a count, stamp line excluded"
stamp1="$(head -1 "$LEDGER")"

echo "== the same run again, to prove the stamp is per build and not per configuration"
run_gradle populate2 --rerun
stamp2="$(head -1 "$LEDGER")"
if grep -q 'Configuration cache entry reused' "$WORK/populate2.log"; then
    echo "   configuration cache entry was reused, so this comparison is the sharp one"
else
    echo "   NOTE: the configuration cache entry was NOT reused between the two runs, so this" >&2
    echo "   comparison is weaker than intended — it no longer exercises replay." >&2
fi
if [ "$stamp1" = "$stamp2" ]; then
    echo "FAIL (computenet-0gnm): two separate builds wrote the SAME ledger stamp" >&2
    echo "($stamp1), so the stamp identifies a configuration rather than a build and cannot" >&2
    echo "tell a leftover ledger from this run's." >&2
    exit 1
fi
echo "   OK: two builds, two stamps"
cp "$LEDGER" "$WORK/genuine.tsv"

check_cached_run() { # $1 = scenario label, $2 = log name
    rm -rf kernel/build/test-results/test kernel/build/reports/tests/test
    run_gradle "$2"
    if ! grep -qE '^> Task :kernel:test (FROM-CACHE|UP-TO-DATE)$' "$WORK/$2.log"; then
        echo "PRECONDITION FAILED [$1]: :kernel:test executed, so the leftover-ledger path" >&2
        echo "was never exercised. Task line(s) seen:" >&2
        grep -E '^> Task :kernel:test' "$WORK/$2.log" >&2 || echo "  (none)" >&2
        exit 2
    fi
    grep -E '^> Task :kernel:test ' "$WORK/$2.log"
    echo "   -- finalizer output --"
    sed -n '/Standing expected failures/,/^$/p' "$WORK/$2.log" | sed 's/^/   /'
    if grep -q 'STALE.FakeTest.from-an-earlier-run' "$WORK/$2.log"; then
        echo "FAIL [$1] (computenet-0gnm): a line from an earlier build was rendered as this" >&2
        echo "run's standing failures, although :kernel:test did not execute in this build." >&2
        rc=1
        return
    fi
    if ! grep -q 'Standing expected failures (@ExpectedFailure): NOT REPORTED' "$WORK/$2.log"; then
        echo "FAIL [$1] (computenet-0gnm): the stale line was suppressed, but the finalizer" >&2
        echo "did not say that :kernel:test failed to execute in this build. Silence is the" >&2
        echo "other half of the same defect: a reader cannot tell 'nothing ran' from" >&2
        echo "'nothing stands'." >&2
        rc=1
    fi
}

echo "== scenario A: leftover ledger carrying the PREVIOUS build's own stamp"
cp "$WORK/genuine.tsv" "$LEDGER"
printf '%s\n' "$STALE_LINE" >> "$LEDGER"
check_cached_run "A: previous build's stamp" cached-stamped

echo "== scenario B: leftover ledger with no stamp line at all"
mkdir -p "$(dirname "$LEDGER")"
printf '%s\n' "$STALE_LINE" > "$LEDGER"
check_cached_run "B: unstamped ledger" cached-unstamped

if [ "$rc" -eq 0 ]; then
    echo "PASS: a ledger left by an earlier build is not rendered as this run's result."
fi
exit "$rc"
