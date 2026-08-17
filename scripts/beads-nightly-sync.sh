#!/usr/bin/env bash
# beads-nightly-sync.sh — nightly/manual Dolt round-trip for the beads tracker.
#
# Runs `bd dolt pull` then `bd dolt push` against the native Dolt remote
# configured as sync.remote in .beads/config.yaml — currently DoltHub,
# https://doltremoteapi.dolthub.com/mrboo/computenet (moved off the old
# refs/dolt/data ref on the GitHub remote on 2026-08-12, PR #64). This is the
# ONLY place outside a live work session that should perform the round-trip
# once computenet-o97.5.1 lands (see doc/ops/beads-sync-runbook.md's
# known-callers inventory).
#
# Cost, measured against DoltHub 2026-08-15 (computenet-cyd; raw readings and
# spread in doc/ops/beads-sync-cost.md, per-session model in the runbook §2):
# a full round-trip is ~19.7s when both directions carry a real delta
# (pull ~7.0s, push ~12.6s) and ~9.3s when neither does (pull ~3.3s, push
# ~6.0s). Budget well above that anyway — a push following a remote merge was
# measured over 120s on the old transport and has not been re-measured here.
#
# The push check below tests BOTH signals — nonzero exit and a rejection in
# the output — matching .claude/skills/work/scripts/publish-beads.sh, which
# records having seen `bd dolt push` exit 0 while printing a non-fast-forward
# rejection (Error 1105). A rejection exited 1 when re-checked on bd 1.1.2
# (2026-08-15), so the exit-0 mode may be version-specific, but it has not
# been refuted, so neither signal is trusted alone (computenet-kbk0). This
# script's exit status is now safe to trust for the push step. A
# non-fast-forward is the expected outcome of two machines pushing on a
# schedule; unlike publish-beads.sh this script does NOT recover it — the
# recovery is pull, then push again, i.e. re-run this script.
#
# Fail-loudly, never degraded-continue (computenet-kg7 lesson): any failure
# in either step exits nonzero immediately, naming the command that failed.
# This script does not retry and does not attempt conflict resolution itself
# — a pull that aborts on a merge conflict is an operator problem, not a
# script problem; see doc/ops/beads-sync-runbook.md's conflict-resolution
# section (transcribed from doc/ops/beads-sync-cost.md's 2026-08-12 ~12:20 UTC
# incident) for the manual `dolt` CLI sequence.
#
# Usage:
#   scripts/beads-nightly-sync.sh
#
# Exit codes:
#   0  both pull and push succeeded
#   1  bd dolt pull failed
#   2  bd dolt push failed
#   3  bd not found on PATH, or not run from a directory with a .beads/ tree
#
# Installation (launchd/cron) is a machine-side step this script does not
# perform — see doc/ops/beads-sync-runbook.md for the exact commands.

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || {
    echo "beads-nightly-sync: FAILED to cd to repo root '$REPO_ROOT'" >&2
    exit 3
}

if ! command -v bd >/dev/null 2>&1; then
    echo "beads-nightly-sync: FAILED — 'bd' not found on PATH" >&2
    exit 3
fi

if [ ! -d ".beads" ]; then
    echo "beads-nightly-sync: FAILED — no .beads/ directory under '$REPO_ROOT'" >&2
    exit 3
fi

echo "beads-nightly-sync: $(date -u +%Y-%m-%dT%H:%M:%SZ) starting: bd dolt pull"
if ! bd dolt pull; then
    cat >&2 <<'EOF'
beads-nightly-sync: FAILED — 'bd dolt pull' exited nonzero.

If the failure is a merge conflict ("merge conflicts in issues require
operator resolution"), do NOT retry this script. See the conflict-resolution
section of doc/ops/beads-sync-runbook.md for the operator sequence.
EOF
    exit 1
fi

echo "beads-nightly-sync: $(date -u +%Y-%m-%dT%H:%M:%SZ) starting: bd dolt push"
# Fail on EITHER signal — nonzero exit OR a rejection in the output. `dolt
# push` against a real non-fast-forward exits 1 and prints
# `! [rejected] ... (non-fast-forward)` (measured 2026-08-17), but `bd dolt
# push` was once observed exiting 0 while printing a rejection, and that
# propagation cannot be re-measured safely from one machine. The exit-code-only
# test used here disagreed with publish-beads.sh's output-only test; both now
# require the same pair (computenet-kbk0).
push_out=$(bd dolt push 2>&1); push_rc=$?
printf '%s\n' "$push_out"
if [ "$push_rc" -ne 0 ] || grep -qiE "rejected|error" <<<"$push_out"; then
    echo "beads-nightly-sync: FAILED — 'bd dolt push' rejected (exit $push_rc). See doc/ops/beads-sync-runbook.md." >&2
    exit 2
fi

echo "beads-nightly-sync: $(date -u +%Y-%m-%dT%H:%M:%SZ) complete: pull and push both succeeded"
exit 0
