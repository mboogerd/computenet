#!/usr/bin/env bash
# Tests for merge-task.sh. Self-contained: builds a throwaway main checkout,
# bare origin, feature worktree and task branch per case, stubs `bd` and `gh`
# on PATH, and deletes everything. Git is real — the gates classify real
# fetch/ls-remote/merge-base behaviour, so stubbing git would test nothing.
# Push failure is injected through a pre-receive hook on the bare origin.
#
# EVERY CASE GETS A FRESH FIXTURE (the sweep-merged-prs suite's lesson): the
# happy path consumes the merge the failure cases need to prove did NOT
# happen.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/merge-task.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/merge-task-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }

FBR=feature/computenet-f1
TBR=task/computenet-t1
TASK=computenet-t1

CASE=0
# fixture -> main checkout CO with bare ORIGIN, feature worktree FWT on $FBR
# (pushed, upstream set), task branch $TBR with one commit on top of the
# feature tip, and bd/gh stubs. Sets CO, FWT, TASKWT, ORIGIN, CTRL, BD_LOG.
fixture() {
  CASE=$((CASE+1))
  local d="$ROOT/c$CASE"
  CO="$d/repo"; ORIGIN="$d/origin.git"; STUB="$d/bin"; CTRL="$d/ctrl"
  FWT="$d/computenet-worktrees/computenet-f1"; TASKWT="$d/taskwt"
  BD_LOG="$d/bd.log"
  mkdir -p "$STUB" "$CTRL"; : > "$BD_LOG"
  git init --quiet --bare "$ORIGIN"
  git init --quiet "$CO"
  (
    cd "$CO"
    git config user.email t@t; git config user.name t
    git symbolic-ref HEAD refs/heads/main
    echo base > f; git add f; git commit --quiet -m base
    git remote add origin "$ORIGIN"
    git push --quiet -u origin main
  ) >/dev/null 2>&1
  git -C "$CO" worktree add --quiet -b "$FBR" "$FWT" main >/dev/null 2>&1
  git -C "$FWT" push --quiet -u origin "$FBR" >/dev/null 2>&1
  git -C "$CO" worktree add --quiet -b "$TBR" "$TASKWT" "$FBR" >/dev/null 2>&1
  (cd "$TASKWT"; echo task > t.txt; git add t.txt; git commit --quiet -m "task work") >/dev/null 2>&1
  cat > "$ORIGIN/hooks/pre-receive" <<EOF
#!/bin/sh
[ -f "$CTRL/push-fail" ] && { echo "rejected by test hook" >&2; exit 1; }
exit 0
EOF
  chmod +x "$ORIGIN/hooks/pre-receive"
  echo '[]' > "$CTRL/prs.json"
  # bd show output deliberately carries a warning line BEFORE the JSON, and
  # the JSON is a LIST — pinning the sed-slice + .[0] unwrap.
  printf 'Warning: dolt noise before the JSON\n[{"id":"%s","metadata":{"branch":"%s","worktree":"%s"}}]\n' \
    "$TASK" "$TBR" "$TASKWT" > "$CTRL/show.json"
  cat > "$STUB/bd" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1" in
  show)  [ -f "$CTRL/show-fail" ] && { echo "dolt: connection refused" >&2; exit 1; }
         cat "$CTRL/show.json" ;;
  close) [ -f "$CTRL/close-fail" ] && { echo boom >&2; exit 1; }
         exit 0 ;;
esac
EOF
  cat > "$STUB/gh" <<'EOF'
#!/usr/bin/env bash
[ -f "$CTRL/gh-fail" ] && { echo "dial tcp: can't assign requested address" >&2; exit 1; }
case "$1" in
  pr)  cat "$CTRL/prs.json" ;;
  api) echo me ;;
esac
EOF
  chmod +x "$STUB/bd" "$STUB/gh"
}

run() { (cd "$CO" && PATH="$STUB:$PATH" CTRL="$CTRL" BD_LOG="$BD_LOG" "$SCRIPT" "$@" 2>&1); }
closed()      { grep -qx "close $TASK" "$BD_LOG"; }
merged_local() { git -C "$FWT" log --format=%s -1 | grep -qF "Merge $TASK"; }
tip_on_origin() {
  git -C "$FWT" fetch --quiet origin "$FBR" 2>/dev/null \
    && git -C "$FWT" merge-base --is-ancestor "$(git -C "$FWT" rev-parse "$TBR")" FETCH_HEAD
}

# 1. happy path: gates pass, merge lands, push proven, close follows
echo "happy path"
fixture
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
has "$out" "GATE branch-identity: PASS" "gate 1 printed"
has "$out" "GATE origin-state: PASS" "gate 2 printed"
has "$out" "GATE competing-pr: PASS" "gate 3 printed"
has "$out" "GATE task-branch: PASS" "gate 4 printed"
has "$out" "incoming ($FBR..$TBR):" "the two-dot --stat is shown before the merge"
has "$out" "t.txt" "the stat names the incoming file"
merged_local && ok "merge commit on the feature branch" || bad "no merge commit"
tip_on_origin && ok "task tip is on origin/$FBR" || bad "task tip not durable"
has "$out" "durable" "durability is stated before the close"
closed && ok "bd close ran" || bad "bd close never ran"
has "$out" "closed: $TASK" "the close is announced"

# 2. dry run: gates + stat only, nothing mutates
echo
echo "dry run"
fixture
out=$(run --dry-run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
has "$out" "GATE task-branch: PASS" "gates still run"
has "$out" "incoming ($FBR..$TBR):" "the stat is still shown"
has "$out" "dry run: gates green, nothing merged" "says it stopped"
merged_local && bad "dry run merged" || ok "no merge happened"
closed && bad "dry run closed the task" || ok "no close happened"

# 3. GATE branch-identity: feature worktree wandered off its branch
echo
echo "gate: branch identity (computenet-wpvy.29)"
fixture
git -C "$FWT" checkout --quiet -b sidetrack
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "GATE branch-identity: FAIL" "names the gate"
has "$out" "sidetrack" "names the branch it found instead"
merged_local && bad "merged anyway" || ok "nothing merged"
closed && bad "closed anyway" || ok "nothing closed"

# 4. GATE origin-state: origin ahead — somebody pushed under you
echo
echo "gate: origin ahead"
fixture
git clone --quiet "$ORIGIN" "$ROOT/c$CASE/other" 2>/dev/null
(
  cd "$ROOT/c$CASE/other"
  git config user.email t@t; git config user.name t
  git checkout --quiet "$FBR"
  git commit --quiet --allow-empty -m "pushed under you"
  git push --quiet origin "$FBR"
) >/dev/null 2>&1
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "GATE origin-state: FAIL" "names the gate"
has "$out" "AHEAD" "names the finding"
has "$out" "ask-human.md" "routes to the park, not to a winner"
merged_local && bad "merged anyway" || ok "nothing merged"

# 5. GATE origin-state: branch absent from origin is a CHECK-shaped refusal
echo
echo "gate: origin has no feature branch"
fixture
git -C "$CO" push --quiet origin --delete "$FBR" >/dev/null 2>&1
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "GATE origin-state: FAIL" "still refuses"
has "$out" "CHECK: origin has no $FBR" "but names it as the CHECK, not a park"
has "$out" "absence is not normal" "says why it matters"

# 6. GATE origin-state: unreachable origin — nothing was checked
echo
echo "gate: origin unreachable"
fixture
git -C "$CO" remote set-url origin "$ROOT/c$CASE/nowhere-such-dir"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "GATE origin-state: FAIL" "names the gate"
has "$out" "UNREACHABLE" "names the finding"
merged_local && bad "merged anyway" || ok "nothing merged"

# 7. GATE competing-pr: an open PR under another login is a park
echo
echo "gate: competing PR"
fixture
echo '[{"number":7,"author":{"login":"stranger"}}]' > "$CTRL/prs.json"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "GATE competing-pr: FAIL" "names the gate"
has "$out" "#7 by stranger" "names the PR and its author"
merged_local && bad "merged anyway" || ok "nothing merged"

# ...but your own open PR (the 5d PR) passes
fixture
echo '[{"number":8,"author":{"login":"me"}}]' > "$CTRL/prs.json"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 0 ] && ok "own PR passes the gate" || bad "own PR: exits $rc -- $(tr '\n' '|' <<<"$out")"

# 8. GATE competing-pr: a failed gh call is not a reading
echo
echo "gate: gh failure"
fixture
touch "$CTRL/gh-fail"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "GATE competing-pr: FAIL" "names the gate"
has "$out" "not a reading" "refuses to guess"
merged_local && bad "merged anyway" || ok "nothing merged"

# 9. push rejected: merge exists locally, but the task is NOT closed
echo
echo "push failure: no close without durability"
fixture
touch "$CTRL/push-fail"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "push FAILED" "names the step"
has "$out" "task NOT closed" "says the close was withheld"
closed && bad "closed an undurable merge" || ok "bd close never ran"
tip_on_origin && bad "tip reached origin despite the reject" || ok "tip not on origin"

# 10. bd close fails AFTER a durable merge: loud, exit 1, merge stays
echo
echo "bd close failure after a durable merge"
fixture
touch "$CTRL/close-fail"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
has "$out" "bd close $TASK FAILED" "names the failure"
has "$out" "close it by hand" "hands the repair to the caller"
tip_on_origin && ok "the durable merge is not rolled back" || bad "merge lost"

# 11. metadata.branch absent: falls back to task/<id>
echo
echo "metadata fallback"
fixture
printf 'Warning: dolt noise\n[{"id":"%s","metadata":{}}]\n' "$TASK" > "$CTRL/show.json"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 0 ] && ok "defaults to task/<id> and merges" || bad "exits $rc -- $(tr '\n' '|' <<<"$out")"
merged_local && ok "merge commit present" || bad "no merge commit"

# 12. dirty task worktree: reported after the close, never fatal
echo
echo "dirty task worktree"
fixture
echo half-edit >> "$TASKWT/t.txt"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 0 ] && ok "still exits 0" || bad "exits $rc, wanted 0"
has "$out" "DIRTY" "the dirty worktree is reported"
closed && ok "the close still happened" || bad "close skipped"

# 13. bd show fails: task unresolvable, exit 3, nothing touched
echo
echo "bd show failure"
fixture
touch "$CTRL/show-fail"
out=$(run "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 3 ] && ok "exits 3" || bad "exits $rc, wanted 3"
merged_local && bad "merged anyway" || ok "nothing merged"

# 14. usage
echo
echo "usage"
fixture
out=$(run "$TASK"); rc=$?
[ "$rc" -eq 2 ] && ok "one arg exits 2" || bad "exits $rc, wanted 2"
out=$(run --frobnicate "$TASK" "$FBR"); rc=$?
[ "$rc" -eq 2 ] && ok "unknown flag exits 2" || bad "exits $rc, wanted 2"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
