#!/usr/bin/env bash
# Tests for park-thread.sh. Stubs `bd` on PATH so the thread shapes are
# fixtures, not live tracker state — the real computenet-3sua thread has since
# been unparked, so pinning against it would test history, not the classifier.
#
# The load-bearing case is that thread's shape at the moment it was dangerous:
# a maintainer ANSWER at 09:35 and a full RE-PARK of the same question at
# 09:37. The verdict must name the answer, not the tail (computenet-1cuq).
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/park-thread.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/park-thread-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"
cat > "$ROOT/bin/bd" <<'STUB'
#!/usr/bin/env bash
[ -f "$CTRL/comments.json" ] || exit 1
cat "$CTRL/comments.json"
STUB
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }

CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE"; mkdir -p "$CTRL"; }
run() { "$SCRIPT" some-bead 2>&1; }

# One comment per line: created_at, then the first line of the body.
thread() {
  : > "$CTRL/comments.json"; local first=1
  { echo '['
    while IFS='|' read -r ts body; do
      [ -n "$ts" ] || continue
      [ "$first" = 1 ] || echo ','
      first=0
      printf '{"created_at":"%s","author":"MacBoo","text":%s}' \
        "$ts" "$(printf '%s' "$body" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')"
    done
    echo ']'
  } > "$CTRL/comments.json"
}

echo "the dangerous shape: an answer, then a re-park two minutes later"
fixture
thread <<'T'
2026-08-26T11:04:38Z|QUESTION for a human, raised by the feature review
2026-08-27T09:35:16Z|Maintainer decision (mlboogerd, 2026-08-27): OPTION 3
2026-08-27T09:37:48Z|QUESTION: Does step 2's pre-registered rule still hold?
T
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "an answered thread exits 0" || bad "exits $rc, wanted 0"
has "$out" "ANSWERED at 2026-08-27T09:35:16Z, then RE-PARKED at 2026-08-27T09:37:48Z" \
    "the verdict names the answer AND the later re-park"
has "$out" "THE NEWEST COMMENT IS NOT THE STATE" "it says why the tail is wrong"
has "$out" "naming BOTH timestamps" "it says to record the unpark"

echo
echo "an unanswered park is left alone"
fixture
thread <<'T'
2026-08-26T11:04:38Z|QUESTION for a human, raised by the feature review
2026-08-26T17:26:14Z|STATUS CHANGE, recorded by the orchestrator. No decision is made here
T
out=$(run); rc=$?
[ "$rc" -eq 1 ] && ok "no marker match exits 1" || bad "exits $rc, wanted 1"
has "$out" "NO COMMENT MATCHED THE ANSWER MARKERS" "it reports a miss, not an absence"
has "$out" "not the same as" "exit 1 does not assert that no answer exists"
hasnt "$out" "ANSWERED at" "it does not manufacture an answer"

echo
echo "an answer with nothing after it is not the dangerous shape"
fixture
thread <<'T'
2026-08-26T11:04:38Z|QUESTION for a human
2026-08-27T09:35:16Z|Maintainer decision (mlboogerd, 2026-08-27): OPTION 3
T
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "answered exits 0" || bad "exits $rc"
has "$out" "nothing re-parked after it" "it distinguishes the safe shape"

echo
echo "EVERY answer wording this tracker actually uses is recognised"
# Fitting the markers to ONE bead's wording reported four of five real answers
# as "the park stands" — the tool's own failure mode, wearing its uniform.
while IFS= read -r wording; do
  [ -n "$wording" ] || continue
  fixture
  { printf '['
    printf '{"created_at":"2026-01-01T00:00:00Z","author":"m","text":"QUESTION: which option?"},'
    printf '{"created_at":"2026-01-02T00:00:00Z","author":"m","text":%s}' \
      "$(printf '%s' "$wording" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().rstrip("\n")))')"
    printf ']\n'; } > "$CTRL/comments.json"
  out=$(run); rc=$?
  [ "$rc" -eq 0 ] && ok "recognised: ${wording:0:44}" || bad "MISSED: $wording"
done <<'WORDINGS'
ANSWERED by the maintainer (2026-09-03, via sync report): ACCEPT the interface
Decided 2026-08-31 (sync-report follow-up): self-host a relay.
Human decision 2026-08-25: adopt OPTION 1 — a stable identity key
MAINTAINER DECISION (mlboogerd, 2026-08-29): OPTION 4
AMENDMENT TO THE DECISION (mlboogerd, 2026-08-29): WHY AN ANCHOR IS THE
HUMAN DECISION 2026-08-14 (sync-report): answered and closed.
WORDINGS

echo
echo "a QUESTION carrying the answer vocabulary is NOT its own answer"
# ask-human.md's own park template invites "not a call I should make
# unilaterally"; an unanchored match made a pure question read as ANSWERED,
# with the reassuring verdict.
fixture
thread <<'T'
2026-01-01T00:00:00Z|QUESTION: which of the three — this needs a maintainer decision, not a call I should make unilaterally
T
out=$(run); rc=$?
[ "$rc" -eq 1 ] && ok "a question is not its own answer" || bad "exits $rc, wanted 1"
hasnt "$out" "ANSWER-SHAPED" "no reassuring verdict on a bare question"

echo
echo "it FAILS CLOSED on a payload it cannot read"
fixture
# Raw control characters inside bd JSON bodies (computenet-9n60) used to make
# jq fail and the script fall through to `ANSWERED at , ...` and exit 0.
printf '[{"created_at":"2026-01-01T00:00:00Z","author":"m","text":"Decided 2026-01-01 (x): go\u0001ahead"}]\n' \
  | python3 -c 'import sys; sys.stdout.write(sys.stdin.read().replace("\\u0001", chr(1)))' > "$CTRL/comments.json"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "control characters are stripped, not fatal" || bad "exits $rc, wanted 0"

fixture
echo 'not json at all {' > "$CTRL/comments.json"
out=$(run); rc=$?
[ "$rc" -eq 2 ] && ok "an unparseable payload is exit 2, never a verdict" || bad "exits $rc, wanted 2"
hasnt "$out" "ANSWERED at " "it never reports an answer it could not read"

fixture
echo '[]' > "$CTRL/comments.json"
out=$(run); rc=$?
[ "$rc" -eq 2 ] && ok "an empty thread is exit 2, not a claim about the park" || bad "exits $rc, wanted 2"

echo
echo "an ANSWER with no timestamp is still an answer"
fixture
printf '[{"author":"m","text":"MAINTAINER DECISION (mlboogerd): OPTION 4"}]\n' > "$CTRL/comments.json"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "a timestamp-less answer is not dropped" || bad "exits $rc, wanted 0"

echo
echo "an UNPARKED record is bookkeeping, not an answer"
fixture
thread <<'T'
2026-08-26T11:04:38Z|QUESTION for a human
2026-08-27T16:26:20Z|UNPARKED by the orchestrator (session on MacBoo, epic computenet-x9e)
T
out=$(run); rc=$?
[ "$rc" -eq 1 ] && ok "an unpark record does not stand in for the decision" || bad "exits $rc, wanted 1"

echo
echo "bodies are printed in FULL — the default bd view truncates (computenet-wq14)"
fixture
long=$(python3 -c 'print("Maintainer decision (mlboogerd): OPTION 3\n" + "x"*400 + "\nTAIL-MARKER")')
printf '[{"created_at":"2026-08-27T09:35:16Z","author":"MacBoo","text":%s}]\n' \
  "$(printf '%s' "$long" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" \
  > "$CTRL/comments.json"
out=$(run)
has "$out" "TAIL-MARKER" "the end of a long body survives"

echo
echo "comments out of timestamp order are sorted before judging"
fixture
thread <<'T'
2026-08-27T09:37:48Z|QUESTION: restated as open
2026-08-27T09:35:16Z|Maintainer decision (mlboogerd): OPTION 3
2026-08-26T11:04:38Z|QUESTION for a human
T
out=$(run)
has "$out" "ANSWERED at 2026-08-27T09:35:16Z, then RE-PARKED at 2026-08-27T09:37:48Z" \
    "order comes from created_at, not from the array"

echo
echo "no comments at all is exit 2, never a verdict"
fixture
out=$(run); rc=$?
[ "$rc" -eq 2 ] && ok "unreadable thread exits 2" || bad "exits $rc, wanted 2"
hasnt "$out" "park stands" "it makes no claim about the park"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
