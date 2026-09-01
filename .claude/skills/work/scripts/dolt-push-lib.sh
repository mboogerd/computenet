#!/usr/bin/env bash
# Shared `bd dolt push` classifier. Sourced by claim-epic.sh and
# publish-beads.sh, the two scripts whose push can end a session.
#
# A push fails two ways that demand OPPOSITE responses, and reading the output
# is the only way to tell them apart — `bd dolt push` has been seen exiting 0
# while printing a rejection, so both signals are consulted:
#
#   REJECTION  the remote moved. Pull, re-verify, push. Retrying alone cannot
#              help, so it is answered immediately.
#   TRANSPORT  DNS, dial, TLS, a 5xx — NOTHING was said about the remote's
#              state. Retrying is the entire fix.
#
# Both used to land in one branch, so a failure to RESOLVE
# doltremoteapi.dolthub.com ended a whole session at step 3 with the epic
# claimed local-only, over a fault that cleared by itself within seconds. The
# two attempts were back-to-back, sampling one instant of DNS state
# (computenet-ckvu).
#
# ORDER MATTERS: rejection markers are tested FIRST. The 5xx codes would
# otherwise match anywhere in the output — a dolt progress count ("uploaded 502
# chunks") or a base32 commit hash containing `502` turns a routinely
# recoverable non-fast-forward into a mislabelled session-ending escalation.
REJECTED='rejected|non-fast-forward|conflict'
TRANSIENT='no such host|dial tcp|i/o timeout|timed out|connection reset|connection refused|TLS handshake|unexpected EOF|temporarily unavailable|code = Unavailable|while dialing|[^0-9]50[234][^0-9]'

# Echoes the last push output on stdout; progress notices go to stderr so they
# stay visible through a `$( )` capture.
#   0 = pushed
#   1 = rejected — recoverable by pull + re-verify
#   2 = transport fault that survived every attempt
push_with_backoff() {
  local out rc i
  for i in 1 2 3; do
    out=$(bd dolt push 2>&1); rc=$?
    if [ "$rc" = 0 ] && ! grep -qiE "rejected|error" <<<"$out"; then
      printf '%s' "$out"; return 0
    fi
    grep -qiE "$REJECTED" <<<"$out" && { printf '%s' "$out"; return 1; }
    if grep -qiE "$TRANSIENT" <<<"$out"; then
      [ "$i" = 3 ] && break
      echo "-- push hit a transport fault (attempt $i/3); retrying in $((i * 5))s --" >&2
      sleep $((i * 5))
      continue
    fi
    printf '%s' "$out"; return 1     # unrecognized: treat as a rejection, as before
  done
  printf '%s' "$out"; return 2
}
