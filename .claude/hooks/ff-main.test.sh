#!/usr/bin/env bash
# Tests for ff-main.sh's self-heal. Self-contained: builds throwaway git repos
# in a temp dir with a real (bare) origin, exercises each state, deletes them.
# Touches nothing in the real repo or the real worktrees directory.
#
#   .claude/hooks/ff-main.test.sh                  # test the sibling hook
#   .claude/hooks/ff-main.test.sh /path/to/other.sh
#
# The state under test is what a fast-forward killed mid-checkout leaves: the
# working tree already carries origin/main's content while HEAD never moved.
# Before the self-heal that state latched — the hook read its own wreckage as
# human work and skipped the fast-forward on every later session, forever.
#
# Exits 0 if all cases pass, 1 otherwise, printing a per-case verdict.
set -uo pipefail

HOOK=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ff-main.sh"}
[ -r "$HOOK" ] || { echo "not readable: $HOOK" >&2; exit 1; }

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/ff-main-test.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }

# A sandbox: bare origin on `main`, a clone acting as the machine's main
# checkout, and one further commit pushed to origin that the clone has NOT
# fetched — so the checkout is exactly 1 behind. Echoes the clone path.
sandbox() {
  local d="$ROOT/$1"
  mkdir -p "$d"
  git init --quiet --bare "$d/origin.git"
  git init --quiet "$d/seed"
  (
    cd "$d/seed"
    git config user.email t@t; git config user.name t
    git symbolic-ref HEAD refs/heads/main
    printf 'base\n' > tracked.txt
    printf 'keep\n'  > untouched.txt
    git add .; git commit --quiet -m base
    git remote add origin "$d/origin.git"; git push --quiet -u origin main
    # The commit the checkout is behind by: edits a tracked file and adds a new one.
    printf 'base\nlanded\n' > tracked.txt
    printf 'new file\n' > added.txt
    git add .; git commit --quiet -m landed
    git push --quiet origin main
  ) >/dev/null 2>&1
  git clone --quiet "$d/origin.git" "$d/checkout" >/dev/null 2>&1
  (
    cd "$d/checkout"
    git config user.email t@t; git config user.name t
    git reset --hard --quiet HEAD~1     # sit one behind, as before the ff
    git update-ref -d refs/remotes/origin/main
  ) >/dev/null 2>&1
  echo "$d/checkout"
}

# Reproduce a fast-forward killed after the working tree was written and before
# the index and HEAD were: origin/main's content on disk, HEAD unmoved.
half_apply() {
  local co=$1
  ( cd "$co"
    git fetch --quiet origin main
    printf 'base\nlanded\n' > tracked.txt   # modified, == origin/main
    printf 'new file\n'     > added.txt     # untracked, == origin/main
    git update-ref -d refs/remotes/origin/main   # unfetch, so the hook must fetch
  ) >/dev/null 2>&1
}

run_hook() { ( cd "$1" && bash "$HOOK" 2>&1 ); }
head_of()  { git -C "$1" rev-parse HEAD; }

echo "case 1: a half-applied fast-forward heals and then lands"
co=$(sandbox heal); before=$(head_of "$co"); half_apply "$co"
out=$(run_hook "$co"); after=$(head_of "$co")
[ "$before" != "$after" ] && ok "HEAD advanced" || bad "HEAD stuck at $before -- $out"
[ -z "$(git -C "$co" status --porcelain)" ] && ok "tree clean" || bad "still dirty: $(git -C "$co" status --porcelain | tr '\n' ' ')"
grep -q "cleared a half-applied fast-forward" <<<"$out" && ok "said what it did" || bad "silent about the heal -- $out"
[ "$(cat "$co/added.txt" 2>/dev/null)" = "new file" ] && ok "the merge wrote the removed file back" || bad "added.txt lost"

echo "case 2: a real human edit is never touched"
co=$(sandbox human); half_apply "$co"
printf 'base\nMY WORK IN PROGRESS\n' > "$co/tracked.txt"   # NOT origin/main's content
before=$(head_of "$co"); out=$(run_hook "$co")
[ "$(head_of "$co")" = "$before" ] && ok "HEAD left alone" || bad "fast-forwarded over a human edit"
grep -q 'MY WORK IN PROGRESS' "$co/tracked.txt" && ok "edit survived" || bad "DESTROYED a human edit"
grep -q "left alone" <<<"$out" && ok "reported" || bad "no report -- $out"

echo "case 3: one unsafe path vetoes the whole heal"
co=$(sandbox veto); half_apply "$co"
printf 'scratch\n' > "$co/untouched.txt"; git -C "$co" add untouched.txt   # staged: real work
before=$(head_of "$co"); out=$(run_hook "$co")
[ "$(head_of "$co")" = "$before" ] && ok "HEAD left alone" || bad "healed despite a staged path"
[ "$(cat "$co/untouched.txt")" = "scratch" ] && ok "staged work survived" || bad "DESTROYED staged work"
grep -q 'base' "$co/tracked.txt" && grep -q 'landed' "$co/tracked.txt" && ok "safe paths left dirty too (all-or-nothing)" || bad "partially healed"

echo "case 4: an untracked file that is NOT in origin/main is left in place"
co=$(sandbox scratch); half_apply "$co"
printf 'notes\n' > "$co/my-scratch.txt"
out=$(run_hook "$co")
[ -f "$co/my-scratch.txt" ] && ok "scratch file survived" || bad "DELETED an untracked scratch file"

echo "case 5: a clean checkout still fast-forwards (no regression)"
co=$(sandbox clean); before=$(head_of "$co"); out=$(run_hook "$co")
[ "$(head_of "$co")" != "$before" ] && ok "HEAD advanced" || bad "clean ff broke -- $out"
grep -q "cleared a half-applied" <<<"$out" && bad "claimed a heal that never happened" || ok "no spurious heal message"

echo

# computenet-od2q: the watchdog can cut a fetch that has ALREADY updated
# origin/main. Before the fix that was read as "produced nothing" and the
# available fast-forward was discarded, leaving the checkout stale for the
# whole slot. Stub `git` so `fetch` does the real update and then hangs past a
# 1s fuse; everything else passes through to the real git.
echo "case 6: a cut fetch whose ref update already landed"
c=$(sandbox od2q)
stub="$ROOT/od2q-bin"; mkdir -p "$stub"
cat > "$stub/git" <<'STUB'
#!/usr/bin/env bash
for a in "$@"; do
  if [ "$a" = "fetch" ]; then
    "$REAL_GIT" "$@"        # do the real ref update...
    sleep 30                # ...then hang, so the watchdog CUTS us (rc != 0)
  fi
done
exec "$REAL_GIT" "$@"
STUB
chmod +x "$stub/git"
out=$(cd "$c" && REAL_GIT=$(command -v git) PATH="$stub:$PATH" FF_MAIN_FUSE=1 bash "$HOOK" 2>&1)
head_now=$(git -C "$c" rev-parse main)
origin_now=$(git -C "$c" rev-parse origin/main)
if [ "$head_now" = "$origin_now" ]; then
  ok "a cut fetch that already advanced the ref still fast-forwards (computenet-od2q)"
else
  bad "a cut fetch that already advanced the ref did NOT fast-forward — out was: $out"
fi
case "$out" in
  *"was stopped"*) ok "it still reports that the fetch was stopped" ;;
  *) bad "the stopped-fetch message disappeared — out was: $out" ;;
esac

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
