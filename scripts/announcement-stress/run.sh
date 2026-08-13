#!/usr/bin/env bash
# Accumulate announcement-stress awaits across BOUNDED processes.
#
# Why this exists (computenet-h6a, measured 2026-08-13). computenet-dqy.40 asks
# for ">= 100000 stress awaits via the WsAnnouncementStressTest
# -Dwire.stress.iterations entry point, with per-failure artifacts retained".
# One process cannot do that: the harness retains ~176-210 KB per iteration, so
# on a container-default heap (1.94GiB measured in groovy:4.0-jdk21) it dies at
# ~11250 iterations = ~22500 awaits, roughly a quarter of the way. Four Linux
# arms died there, within 1% of each other, and a two-heap fit (OOM at 11250 on
# 1.94GiB, at 5525 on 1.00GiB) says the retention is linear in iterations. That
# leak is a separate bug; it is NOT PR #83's announceTo registration leak, which
# was A/B'd and moved the OOM point by 1%.
#
# So the measurement is accumulated instead. Each process runs until
# WsAnnouncementStressTest's heap ceiling stops it (exit 3), which happens
# *before* the OutOfMemoryError rather than after, and this script starts the
# next one and adds up the awaits. Every process's progress is read off disk, so
# a process that dies for any reason still contributes what it had reached, and
# any failure it observed is already in its own artifact file.
#
# Usage:
#   scripts/announcement-stress/run.sh [options]
#     --awaits N                 target total awaits to accumulate (default 100000)
#     --out DIR                  artifacts root (default wire/build/announcement-stress/accumulated-<ts>)
#     --heap SIZE                -Xmx for each process (default: the JVM's own default max heap)
#     --iterations-per-process N cap per process (default 1000000: the heap ceiling is the real bound)
#     --heap-ceiling F           retained-heap fraction at which a process stops (default 0.80)
#     --deadline-seconds N       per-process wall-clock halt (default 1800)
#     --max-processes N          refuse to start more than this many (default 64)
#     --inject-failure-at LIST   forced synthetic failures, per process, for testing the artifact path
#     --classpath FILE           reuse a previously captured :wire test runtime classpath
#
# Exit codes: 0 target reached with no observed failure; 2 at least one observed
# failure (its artifact is on disk); 1 the target could not be reached.
set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
target_awaits=100000
out=""
heap=""
iters_per_process=1000000
heap_ceiling=0.80
deadline=1800
max_processes=64
inject=""
classpath_file=""

while [ $# -gt 0 ]; do
  case "$1" in
    --awaits) target_awaits=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --heap) heap=$2; shift 2 ;;
    --iterations-per-process) iters_per_process=$2; shift 2 ;;
    --heap-ceiling) heap_ceiling=$2; shift 2 ;;
    --deadline-seconds) deadline=$2; shift 2 ;;
    --max-processes) max_processes=$2; shift 2 ;;
    --inject-failure-at) inject=$2; shift 2 ;;
    --classpath) classpath_file=$2; shift 2 ;;
    -h|--help) sed -n '1,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

[ -n "$out" ] || out="$repo_root/wire/build/announcement-stress/accumulated-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"

if [ -z "$classpath_file" ]; then
  classpath_file="$out/classpath.txt"
  echo "[run] resolving :wire test runtime classpath" >&2
  "$repo_root/gradlew" -p "$repo_root" \
    -I "$repo_root/scripts/announcement-stress/classpath.init.gradle.kts" \
    -q :wire:printTestRuntimeClasspath 2>/dev/null | tail -1 > "$classpath_file"
fi
cp=$(cat "$classpath_file")
if [ -z "$cp" ]; then
  echo "[run] could not resolve the :wire test runtime classpath" >&2
  exit 1
fi

summary="$out/SUMMARY.txt"
: > "$summary"
log() { echo "$@" | tee -a "$summary"; }

log "target_awaits=$target_awaits heap=${heap:-jvm-default} heap_ceiling=$heap_ceiling deadline=${deadline}s out=$out"

total_awaits=0
total_observed=0
total_injected=0
proc=0
started=$(date +%s)

while [ "$total_awaits" -lt "$target_awaits" ]; do
  proc=$((proc + 1))
  if [ "$proc" -gt "$max_processes" ]; then
    log "REFUSED to start process $proc: --max-processes=$max_processes reached with $total_awaits/$target_awaits awaits"
    break
  fi
  remaining=$(( (target_awaits - total_awaits + 1) / 2 ))
  iters=$(( remaining < iters_per_process ? remaining : iters_per_process ))
  dir="$out/proc-$(printf '%03d' "$proc")"

  args=( --iterations "$iters" --progress-every 250 --heap-ceiling "$heap_ceiling"
         --deadline-seconds "$deadline" --artifacts "$dir" )
  [ -n "$inject" ] && args+=( --inject-failure-at "$inject" )
  jvm=()
  [ -n "$heap" ] && jvm+=( "-Xmx$heap" )
  # Belt and braces on top of the in-process handler: if an OutOfMemoryError
  # arrives somewhere the handler cannot run, the JVM still dies instead of
  # hanging on its non-daemon WebSocket threads.
  jvm+=( -XX:+ExitOnOutOfMemoryError )

  java "${jvm[@]}" -cp "$cp" civictech.wire.WsAnnouncementStressTestKt "${args[@]}" \
    > "$dir.log" 2>&1
  rc=$?
  mkdir -p "$dir"

  # Read this process's reach off disk, so a process that died still counts.
  awaits=0; observed=0; injected=0
  if [ -s "$dir/progress.tsv" ]; then
    read -r _ awaits observed injected <<<"$(tail -1 "$dir/progress.tsv" | awk '{print $1, $2, $3, $4}')"
  fi
  stop="died-or-unknown"
  if [ -s "$dir/result.tsv" ]; then
    stop=$(awk -F'\t' '$1=="stop_reason"{print $2}' "$dir/result.tsv")
    awaits=$(awk -F'\t' '$1=="awaits"{print $2}' "$dir/result.tsv")
    observed=$(awk -F'\t' '$1=="failures_observed"{print $2}' "$dir/result.tsv")
    injected=$(awk -F'\t' '$1=="failures_injected"{print $2}' "$dir/result.tsv")
  fi
  total_awaits=$((total_awaits + awaits))
  total_observed=$((total_observed + observed))
  total_injected=$((total_injected + injected))
  log "proc $proc exit=$rc awaits=$awaits observed=$observed injected=$injected cumulative_awaits=$total_awaits stop=[$stop]"

  if [ "$awaits" -eq 0 ]; then
    log "ABORTING: process $proc contributed no awaits at all (exit $rc); see $dir.log"
    break
  fi
done

elapsed=$(( $(date +%s) - started ))
log "SUMMARY processes=$proc awaits=$total_awaits target=$target_awaits observed_failures=$total_observed injected_failures=$total_injected wall_seconds=$elapsed"
log "SUMMARY artifacts=$out"
if [ "$total_observed" -gt 0 ]; then
  log "SUMMARY failure artifacts:"
  find "$out" -name 'failure-*.txt' | sort | tee -a "$summary"
fi

if [ "$total_observed" -gt 0 ]; then exit 2; fi
if [ "$total_awaits" -lt "$target_awaits" ]; then exit 1; fi
exit 0
