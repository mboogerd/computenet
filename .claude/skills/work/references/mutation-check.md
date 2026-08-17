# The mutation check

A test that passes both before and after you break the code it covers proves
nothing, and no cache can fake a red run — so this is the strongest evidence
available that a test constrains what it claims to. It is prescribed in four
places (task.md step 3, review-task.md §2, review-feature.md §5, and SKILL.md
5a reads its marker); **this file is the procedure, and those four cite it
rather than restating it.** Three independent sessions each rediscovered a
different way to get it wrong (computenet-9ytv, computenet-pi3h,
computenet-qsfu).

## The order is the safety

**1. Commit your deliverable FIRST.** Everything below depends on it. With the
work committed, `git checkout -- <path>` is well-defined, "did the revert
work" is answerable by `git diff`, and neither failure direction below can
happen. Do not skip this to save a commit.

**2. Leave the marker before you touch anything:**

```bash
echo "<the file and call site you mutated, and what you removed>" \
  > <your-worktree>/.mutation-in-progress
```

An agent that inherits your worktree after a crash cannot otherwise tell a
live mutation from finished work — they are identical in the diff, and
committing a mutation is a silently broken change (SKILL.md 5a acts on this
marker). It covers a mutation to a **test** as well as to production code
(computenet-wpvy.34).

**3. Mutate.** Note what actually applies it, because the obvious tool often
will not:

- **The Edit tool's classifier refuses some mutations outright** — removing a
  security check, a sanitizer, a bounds test — and those are exactly the
  strongest mutations (computenet-pi3h). Go through Bash instead:
  `perl -pi -e 's/OLD/NEW/' <file>` or `sed -i '' 's/OLD/NEW/' <file>` (BSD
  `sed` needs the empty `''` argument).
- **If the strongest mutation is unavailable at all, say so in your report and
  name it.** Substituting a weaker one silently turns "I proved the test
  constrains this" into "I proved it constrains something".

**4. Run, and read the BUILD LOG as well as the result.** A mutation that
fails to *compile* leaves the previous run's JUnit XML on disk, which parses as
a plausible result — a verdict for a run that never happened:

```bash
./gradlew :<module>:test --tests '<TestName>' --rerun --no-build-cache \
  > "$SCRATCH/mut.log" 2>&1
grep -E '^e:|BUILD' "$SCRATCH/mut.log"     # 'e:' lines = it never compiled
```

**`--rerun` alone is not enough here.** It can print an unmarked task line
while restoring the previous run's JUnit XML from the build cache, so an
unmarked line is **not** proof of execution (computenet-qsfu). For a
load-bearing run — any mutation check, any before/after comparison — pass
`--no-build-cache` too, as above. And read the **XML's content**: the test
count and the `timestamp` *attribute inside the file*, not the file's mtime,
which a cache restore also freshens.

**5. Revert — and verify it, do not assume it.** Two ways `git checkout` lies:

- **On an untracked file it exits 0 and does nothing.** If the mutation
  *created* the file, `rm` is the revert.
- **On a file you also edited for real it reverts the whole file**, eating
  your own work along with the mutation. Step 1 is what makes this safe; if
  you edited after committing, `git stash push -- <file>` first (never bare
  `git stash` — `refs/stash` is shared by every worktree of this repo, and a
  pop can take another worktree's entry).

Then prove it:

```bash
git -C <your-worktree> diff -- <file>      # expect EMPTY for a tracked file
rm -f <your-worktree>/.mutation-in-progress
ls <your-worktree>/.mutation-in-progress   # expect: No such file
```

**The marker is gitignored, so a clean `git status --short` does NOT mean the
marker is gone.** Removing it needs its own step and its own check — SKILL.md
5a licenses a later session to discard the working tree when it finds one.

**6. Only now run the confirming test.** A green run against content you have
not verified is restored is meaningless.

## What to report

The mutation you made (file and call site), the test name that went red, and
its **assertion message**. "I did the mutation check and it failed as
expected" is the unfalsifiable sentence this whole procedure exists to
replace.
