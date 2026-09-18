#!/usr/bin/env bash
# The breakdown marker: a replica-visible record that ONE machine has already
# broken an epic down, checked at write time, plus the deterministic rule that
# picks a survivor when two machines nonetheless raced.
#
# WHY. /work step 4 creates features under a claimed epic with plain creates,
# which are not idempotent: two machines breaking down one epic under partition
# mint two parallel feature sets, gossip faithfully converges BOTH, and the
# tracker ends with duplicate features that both look real — downstream
# scheduling then implements one feature twice (epic computenet-6wc §3.1,
# feature computenet-6wc.5). A lease does not cover it: the partition case is
# exactly where a lease is invisible. So the identity rides the CONTENT plane
# instead — a comment on the epic (6wc.5-D1: `bd comment` leaves the issues row
# untouched, and the runbook's last-write-wins resolution of a diverged issues
# row would silently drop one side's metadata cell, while comments are separate
# rows that both survive a merge). The check narrows the window; it cannot
# close it, which is why `survivor` exists.
#
# The marker is one comment whose FIRST line is exactly (6wc.5-D2):
#   BREAKDOWN-MARKER v1 token=<t> actor=<BEADS_ACTOR> epic=<epic-id>
# followed by prose for humans. Only first lines are parsed, so quoting the
# format in a discussion comment does not mint a marker.
#
# The token is the first 16 hex chars of sha256 over the two newline-terminated
# lines <epic-id> and <BEADS_ACTOR> (6wc.5-D3). No wall clock: the same machine
# re-derives the same token and so recognises its own marker with no local
# state (that is what makes a resumed breakdown a resume rather than a
# duplicate), and two machines derive different ones.
#
# The survivor rule is the LOWEST token in byte order (6wc.5-D4) — identical on
# every replica after convergence, independent of clocks and of which breakdown
# finished first. Losers are the epic's children stamped
# metadata.breakdown=<a losing token> (create-ticket.sh --breakdown, 6wc.5-D5).
# Children with no stamp are legacy or human-filed and are listed as
# UNATTRIBUTED, never as losers. This script LISTS; it closes nothing.
#
# Scope: epic -> feature breakdowns (6wc.5-D7). It is parent-agnostic, so a
# follow-up can wire feature -> task, which today keeps `bd create --parent=`.
#
# Placement (6wc.5-D6): the ORCHESTRATOR runs `acquire` before dispatching the
# breakdown agent, because it is a pull -> check -> write -> push bracket and
# agents never push. The AGENT re-runs `check` (read-only, local DB) right
# before its first create and stops unless the answer is OWN.
#
# Usage:
#   breakdown-marker.sh <subcommand> <epic-id>
#     check    <epic-id>   read-only, local DB; classify the epic's markers
#     acquire  <epic-id>   pull, check, and write+push a marker if there is none
#     survivor <epic-id>   read-only; adjudicate markers and list ids to close
#
# Exit codes:
#   0   check: NONE, no marker on the epic.
#       acquire: this machine holds the marker — freshly written and PUSHED, or
#                already present (a resume). `TOKEN <t>` is on stdout.
#       survivor: at most one marker; nothing to adjudicate.
#   1   survivor: losers were listed. A reading, not a failure.
#   2   acquire: the marker is written LOCALLY but not published (push rejected
#       twice, or a transport fault that survived every retry). Stop and report,
#       as claim-epic.sh exit 2 does: an unpublished marker is the race itself.
#   3   UNCHECKED — BEADS_ACTOR unset, or bd failed / returned no JSON. Never an
#       answer: the caller must not read it as NONE.
#   10  check: OWN — a marker by $BEADS_ACTOR and no foreign one. `TOKEN <t>`.
#   11  FOREIGN — foreign markers only. acquire writes nothing.
#   12  BOTH — own and foreign markers. acquire writes nothing (the race already
#       happened; run `survivor`). `TOKEN <t>` is still printed.
#
# Run `acquire` with a generous timeout (>=300s): a dolt push after a remote
# merge has been measured over 120s.
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

usage() {
  sed -n '/^# Usage:/,/^#$/p' "$0" | sed 's/^#[[:space:]]\{0,1\}//'
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac

SUB=${1:-}
EPIC=${2:-}
if [ -z "$SUB" ] || [ -z "$EPIC" ]; then
  echo "usage: breakdown-marker.sh <subcommand> <epic-id>" >&2
  usage >&2
  exit 3
fi
case "$SUB" in
  check|acquire|survivor) ;;
  *) echo "unknown subcommand: $SUB" >&2; usage >&2; exit 3 ;;
esac

# UNCHECKED, not a refusal: a missing actor cannot be answered, and a caller
# that reads 3 as NONE is exactly the bug this script exists to prevent.
if [ -z "${BEADS_ACTOR:-}" ]; then
  echo "UNCHECKED: BEADS_ACTOR is unset — cannot tell an own marker from a foreign one" >&2
  exit 3
fi

# 6wc.5-D3. One function, so the test can pin a vector. `shasum` is macOS's,
# `sha256sum` is coreutils' (Linux CI); both were observed on the dev host and
# agree byte for byte on this input. Probed by name BEFORE the pipe, because
# `shasum ... || sha256sum` would consume stdin on the failing branch.
derive_token() {
  local epic=$1 actor=$2
  if command -v shasum >/dev/null 2>&1; then
    printf '%s\n%s\n' "$epic" "$actor" | shasum -a 256 | cut -c1-16
  elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s\n%s\n' "$epic" "$actor" | sha256sum | cut -c1-16
  else
    return 1
  fi
}

TOKEN=$(derive_token "$EPIC" "$BEADS_ACTOR") || {
  echo "UNCHECKED: neither shasum nor sha256sum is on PATH — cannot derive the token" >&2
  exit 3
}
[ -n "$TOKEN" ] || { echo "UNCHECKED: token derivation produced nothing" >&2; exit 3; }

# --- reading the epic's markers -------------------------------------------
# MARKERS becomes one "<token> <actor> <created_at>" line per marker comment.
# Returns 3 (UNCHECKED) when bd fails or hands back something that is not JSON;
# an EMPTY marker set is a legitimate answer and returns 0.
MARKERS=
collect_markers() {
  local raw rows
  raw=$(bd comments "$EPIC" --json 2>/dev/null) || return 3
  # traps.md "bd": bd prefixes JSON with human lines, so slice from the first
  # bracket. A bare array and a {comments:[...]} object are both accepted.
  rows=$(printf '%s\n' "$raw" | sed -n '/^[[{]/,$p')
  [ -n "$rows" ] || return 3
  MARKERS=$(printf '%s\n' "$rows" | jq -r --arg epic "$EPIC" '
      (if type=="array" then . else (.comments // []) end)
      | .[]
      | . as $c
      | (($c.text // "") | split("\n")[0]) as $first
      | select($first | test("^BREAKDOWN-MARKER v1 token=[0-9a-f]+ actor=[^ ]+ epic=\($epic)$"))
      | ($first | capture("token=(?<t>[0-9a-f]+) actor=(?<a>[^ ]+) epic=")) as $m
      | "\($m.t) \($m.a) \($c.created_at // "")"
    ' 2>/dev/null) || return 3
  return 0
}

HAS_OWN=0
HAS_FOREIGN=0
classify() {                       # reads MARKERS; sets HAS_OWN / HAS_FOREIGN
  local token actor rest
  HAS_OWN=0; HAS_FOREIGN=0
  while IFS=' ' read -r token actor rest; do
    [ -n "$token" ] || continue
    if [ "$actor" = "$BEADS_ACTOR" ]; then HAS_OWN=1; else HAS_FOREIGN=1; fi
  done <<EOF
$MARKERS
EOF
}

state_code() {                     # 0 NONE / 10 OWN / 11 FOREIGN / 12 BOTH
  if [ "$HAS_OWN" = 1 ] && [ "$HAS_FOREIGN" = 1 ]; then echo 12
  elif [ "$HAS_OWN" = 1 ]; then echo 10
  elif [ "$HAS_FOREIGN" = 1 ]; then echo 11
  else echo 0; fi
}

print_markers() {
  local token actor rest
  while IFS=' ' read -r token actor rest; do
    [ -n "$token" ] || continue
    echo "MARKER $token $actor $rest"
  done <<EOF
$MARKERS
EOF
}

read_state() {                     # collect + classify, or exit 3
  collect_markers || {
    echo "UNCHECKED: could not read comments on $EPIC (bd failed or returned no JSON)" >&2
    exit 3
  }
  classify
}

case "$SUB" in

check)
  read_state
  print_markers
  code=$(state_code)
  [ "$code" = 10 ] || [ "$code" = 12 ] && echo "TOKEN $TOKEN"
  exit "$code"
  ;;

acquire)
  # The bracket claim-epic.sh:194-216 established: pull -> verify -> write ->
  # push, reading push OUTPUT rather than exit codes.
  bd dolt pull >/dev/null 2>&1 \
    || echo "note: bd dolt pull failed; the check below reads possibly stale local state" >&2
  read_state
  code=$(state_code)
  case "$code" in
    10) echo "TOKEN $TOKEN"; echo "marker for $EPIC already held by $BEADS_ACTOR (resume)" >&2; exit 0 ;;
    11) print_markers; echo "FOREIGN marker on $EPIC — another machine broke it down; create nothing" >&2; exit 11 ;;
    12) print_markers; echo "TOKEN $TOKEN"
        echo "BOTH own and foreign markers on $EPIC — the race already happened; run: breakdown-marker.sh survivor $EPIC" >&2
        exit 12 ;;
  esac

  # NONE: write exactly one marker comment. --file, so nothing in the body is
  # re-expanded by a shell (create-ticket.sh --desc-file, computenet-s5dh).
  body=$(mktemp "${TMPDIR:-/tmp}/breakdown-marker.XXXXXX") || {
    echo "UNCHECKED: cannot create a temp file for the marker body" >&2; exit 3; }
  base=$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)
  {
    printf 'BREAKDOWN-MARKER v1 token=%s actor=%s epic=%s\n' "$TOKEN" "$BEADS_ACTOR" "$EPIC"
    printf 'Breakdown of %s claimed by %s at %s (base %s); see .claude/skills/work/scripts/breakdown-marker.sh.\n' \
      "$EPIC" "$BEADS_ACTOR" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$base"
  } > "$body"
  bd comment "$EPIC" --file "$body" >&2 || {
    rm -f "$body"; echo "could not write the marker comment on $EPIC" >&2; exit 3; }
  rm -f "$body"

  . "$SCRIPT_DIR/dolt-push-lib.sh"    # push_with_backoff (computenet-ckvu)
  push_out=$(push_with_backoff); push_rc=$?
  if [ "$push_rc" = 2 ]; then
    printf '%s\n' "$push_out" >&2
    echo "ESCALATE: push failed 3x with a transport fault, not a rejection; the marker on $EPIC is LOCAL-ONLY" >&2
    exit 2
  fi
  if [ "$push_rc" != 0 ]; then
    echo "-- push rejected; recovering: pull, re-verify, push --" >&2
    bd dolt pull >/dev/null 2>&1
    read_state
    code=$(state_code)
    if [ "$code" = 0 ] || [ "$code" = 11 ]; then
      # Our own comment is not there after the pull: nothing here can be
      # reported as an answer.
      print_markers
      echo "UNCHECKED: after the recovery pull the marker written for $EPIC is absent (state $code)" >&2
      exit 3
    fi
    push_out=$(push_with_backoff); push_rc=$?
    if [ "$push_rc" != 0 ]; then
      printf '%s\n' "$push_out" >&2
      echo "ESCALATE: push failed on both attempts; the marker on $EPIC is LOCAL-ONLY — stop and report" >&2
      exit 2
    fi
    if [ "$code" = 12 ]; then
      # Published, so both replicas can adjudicate — but a foreign breakdown
      # exists and this one must not create.
      print_markers; echo "TOKEN $TOKEN"
      echo "BOTH own and foreign markers on $EPIC after the recovery pull; run: breakdown-marker.sh survivor $EPIC" >&2
      exit 12
    fi
  fi
  echo "TOKEN $TOKEN"
  echo "marker for $EPIC written and pushed by $BEADS_ACTOR" >&2
  exit 0
  ;;

survivor)
  read_state
  # Byte order, not locale order (6wc.5-D4): every replica must agree.
  tokens=$(print_markers | awk '{print $2}' | LC_ALL=C sort -u)
  nmarkers=$(printf '%s\n' "$tokens" | grep -c . )
  survivor_token=$(printf '%s\n' "$tokens" | grep . | head -1)
  losers=$(printf '%s\n' "$tokens" | grep . | tail -n +2)

  if [ -n "$survivor_token" ]; then
    echo "SURVIVOR $survivor_token $(print_markers | awk -v t="$survivor_token" '$2==t {print $3; exit}')"
  fi
  for l in $losers; do
    echo "LOSER $l $(print_markers | awk -v t="$l" '$2==t {print $3; exit}')"
  done

  children=$(bd list --parent="$EPIC" --all --json 2>/dev/null | sed -n '/^[[{]/,$p')
  [ -n "$children" ] || {
    echo "UNCHECKED: could not list children of $EPIC" >&2; exit 3; }
  rows=$(printf '%s\n' "$children" | jq -r '
      (if type=="array" then . else (.issues // []) end)
      | .[]
      | . as $c
      | (($c.metadata // {}) | if type=="string" then (fromjson? // {}) else . end) as $m
      | "\($c.id) \($m.breakdown // "")"
    ' 2>/dev/null) || {
    echo "UNCHECKED: could not parse the children of $EPIC" >&2; exit 3; }

  while IFS=' ' read -r cid stamp; do
    [ -n "$cid" ] || continue
    if [ -z "$stamp" ]; then
      echo "UNATTRIBUTED $cid"
    else
      for l in $losers; do
        [ "$stamp" = "$l" ] && echo "CLOSE $cid"
      done
    fi
  done <<EOF
$rows
EOF

  [ "$nmarkers" -gt 1 ] && exit 1
  exit 0
  ;;
esac
