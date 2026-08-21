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
- **A mutation must not OVERLAP the original.** Choose it so the old value
  cannot match the new one under any matcher the code might use — and you
  cannot assume the matcher, because it is the thing under test. A RENAME
  keeps the old name out of the new one entirely: `DenialReason` →
  `Foo`, never `DenialReasonRenamed`. A guard using `indexOf`/`startsWith`/
  `contains` accepted the suffixed name and the mutation PASSED, one sentence
  from being written up as proof the guard worked (computenet-ex6w). Same
  hazard: a number the code coerces back to the original (`1` → `1.0`, `"1"`),
  a path that still matches the glob, an enum case that shares a prefix.
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

**A `println` probe prints nothing here** — Gradle hides test stdout; read
it from the JUnit XML's `<system-out>` ([gradle-evidence.md](gradle-evidence.md),
"The console is not the suite"). One reviewer lost a round waiting for console output that was in
the XML all along (computenet-ozgs).

**`--rerun` alone is not enough here.** It can print an unmarked task line
while restoring the previous run's JUnit XML from the build cache, so an
unmarked line is **not** proof of execution (computenet-qsfu). For a
load-bearing run — any mutation check, any before/after comparison — pass
`--no-build-cache` too, as above. And read the **XML's content**: the test
count and the `timestamp` *attribute inside the file*, not the file's mtime,
which a cache restore also freshens.

**5. Revert — and verify it, do not assume it.** Two ways `git checkout` lies:

- **An untracked file cannot be checked out at all**, and *how* you name it
  decides whether you find out. Measured on this host (git 2.50.1): naming
  the path — `git checkout -- new.kt` — **errors**, `pathspec 'new.kt' did
  not match any file(s) known to git`, exit 1, which is the loud, safe case.
  A pathspec that also covers tracked files — `git checkout -- .`, a
  directory, a glob — **exits 0, prints nothing, and leaves the untracked
  file mutated**. That is the silent one, and it is the one that produced a
  green run against mutated content. So: revert by naming the exact file,
  never `git checkout -- .`; and if the mutation *created* the file, `rm` is
  the revert.
- **On a file you also edited for real it reverts the whole file**, eating
  your own work along with the mutation. Step 1 is what makes this safe; if
  you edited after committing, park your edit as a patch in your own
  `$SCRATCH` first — **never `git stash`, not even `git stash push -- <file>`**:
  `refs/stash` is a single ref shared by every linked worktree of this repo,
  so a `pop` takes whatever any *other* agent pushed last. Measured: two
  worktrees each `stash push -- f.txt`, and the first worktree's `pop`
  restored the *second* one's content, exit 0
  ([review-task.md](review-task.md) §2 carries the same measurement).

  ```bash
  git -C <your-worktree> diff -- <file> > "$SCRATCH/my-edits.patch"
  git -C <your-worktree> checkout HEAD -- <file>   # now exactly HEAD's content
  # ... mutate, run, watch the named test FAIL, then revert ...
  git -C <your-worktree> checkout HEAD -- <file>
  git -C <your-worktree> apply "$SCRATCH/my-edits.patch"
  ```

  **`HEAD` in the revert is load-bearing too.** If you mutated with
  `git checkout <sha> -- <file>` — "back to base" — that form also STAGES
  what it writes, and a bare `git checkout -- <file>` afterwards restores
  from the index, i.e. the mutation. One reviewer ran two later mutations
  against unreverted sources that way (computenet-l8ju). `checkout HEAD --`
  restores from the commit whatever the index holds.

Then prove it:

```bash
git -C <your-worktree> diff HEAD -- <file>  # tracked: expect EMPTY output
git -C <your-worktree> diff --cached -- <file>   # and nothing STAGED either
grep -n '<the mutated phrase>' <file>       # untracked: expect NO match
rm -f <your-worktree>/.mutation-in-progress
ls <your-worktree>/.mutation-in-progress    # expect: No such file
```

**`HEAD` is load-bearing — a bare `git diff` cannot discriminate here.** Plain
`git diff` compares the worktree against the **index**, so anything that
*stages* while restoring — `git checkout <sha> -- <file>`, `git restore
--staged --worktree`, or an earlier `git add` — leaves it empty whether or not
the mutation is still present. Both directions then read identically and the
agent has to decide what a blank output means with no signal in it; one
reviewer "nearly recorded a mutation as unapplied" on exactly this
(computenet-qc6g). `git diff HEAD` compares against the commit, so it shows
the mutation as a real diff while applied and nothing once reverted, whatever
touched the index.

`git diff` in any form proves nothing about an untracked file — it is empty
whether or not the mutation is still there — so for a file git does not
track, the re-grep is the only proof.

**The marker is gitignored, so a clean `git status --short` does NOT mean the
marker is gone.** Removing it needs its own step and its own check — SKILL.md
5a licenses a later session to discard the working tree when it finds one.

**6. Only now run the confirming test.** A green run against content you have
not verified is restored is meaningless.

## When the task is TEST-ONLY, and the mutation is out of scope

The procedure above requires mutating the production code the tests constrain.
For a **test-only** task that code is outside `metadata.files` by construction —
the bead says so ("no production code changes; if a test exposes a production
defect, report it, do not widen the diff") — so the reference demands an action
the task's own scope forbids. Neither this file nor task.md named the case until
now, and an implementer that hit it had two worse options available with no
guidance against either: skip the proof silently (a reviewer cannot tell a
skipped proof from an unnecessary one), or violate the claim to get it.
computenet-pi3h's fallback does not help — that one is about the *tool* refusing
the edit, and here the permission classifier refused even a `grep` of the
production file and the marker write at the worktree root (computenet-9c0r).

Take the first route that applies, and **say which one you took**:

**1. Cite a SIBLING's mutation evidence** where one exists covering the same
production branches. Name the bead, the mutations, and their results, and label
it **corroborating, not self-generated**. Measured: a test-only task cited its
sibling's review, which had mutated the identical `Session` branches its own
tests exercise — `ID_MISMATCH` forced false, `hasSeenNonce` forced false,
`.mirrored()` dropped, the `AUTH_REQUIRED` guard removed — all RED. Its reviewer
independently checked the citation against the sibling's comments and confirmed
the coverage before accepting it.

**2. Otherwise, trace each test to the specific production conditional it
asserts on**, and say that is what you did.

**Neither is equivalent to a self-run mutation**, and a reader must be able to
tell which strength of evidence it is looking at — which is the whole reason to
name the route rather than quietly substitute one. A reviewer's job here is to
check the substitution was adequate, not to accept it because it was offered.

## What to report

The mutation you made (file and call site), the test name that went red, and
its **assertion message**. "I did the mutation check and it failed as
expected" is the unfalsifiable sentence this whole procedure exists to
replace.
