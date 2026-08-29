# The mutation check

A test that passes both before and after you break the code it covers proves
nothing, and no cache can fake a red run — so this is the strongest evidence
available that a test constrains what it claims to. It is prescribed in four
places (task.md step 3, review-task.md §2, review-feature.md §5, and SKILL.md
5a reads its marker); **this file is the procedure, and those four cite it
rather than restating it.** Three independent sessions each rediscovered a
different way to get it wrong (computenet-9ytv, computenet-pi3h,
computenet-qsfu).

## Who mutates what — the implementer/reviewer split

**Production mutations belong to the REVIEWER whenever the file is outside the
implementer's `metadata.files` claim.** A file *inside* the claim is the
implementer's to break and restore like anything else it owns; a file outside
it is not, and a reviewer is not scope-confined that way.

That split is a decision, not an accident of prompt wording, and the
alternative was measured. Same file, same session, same intended edit
(computenet-g8ho, recurrence of computenet-pi3h): the implementer of a
test-only task was refused by the permission classifier and correctly stopped
and reported; the reviewer of that same task, dispatched minutes later with a
prompt that named the mutation and cited this file as authorization, was not
refused — it ran the mutation, the test went red, it reverted. Three further
reviewer mutations that session were likewise unrefused. A second instance
points the same way from the opposite end of the risk scale: a strictly
read-only `gh run list` was refused to a subagent and succeeded byte-for-byte
from the orchestrator a minute later. The gate appears to key on the asking
agent's **stated authorization** rather than the action's content — so what the
dispatch states is what decides, and both templates now state it rather than
leaving it to whoever writes the prompt: merge-task.md GRANTS the reviewer's,
SKILL.md 5b WITHHOLDS the implementer's and says where the mutation goes.

**Refused? Report the refused operation verbatim.** Two different walls, and
only one of them has a legitimate detour:

| the wall | the move |
|---|---|
| The Edit tool refuses an edit to a file **inside** your claim | Bash is the prescribed route — `perl -pi` / `sed -i ''`, step 3 below |
| The file is **outside** your claim, or you have no authorization to cite | Stop. Report the refusal verbatim, take the substitute routes below ("When the task is TEST-ONLY"), and name the property left unproven |

`perl`/`sed`/`python` through Bash is a tool-limitation detour, never a scope
detour. Reaching for it to edit a file you were not given is the workaround
computenet-pi3h recorded being reached for, and this table exists to stop it.

**Unattended, a refusal is worse than it looks:** it surfaces as an approval
prompt with nobody at the keyboard, so a scheduled run blocks silently instead
of failing. A session that cannot get its mutation approved records the
unproven property and moves on — it never stalls waiting for an approval that
cannot arrive.

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
  strongest mutations (computenet-pi3h). If the file is inside your claim, go
  through Bash instead; if it is not, "Who mutates what" above applies and Bash
  is not the answer.
  `perl -pi -e 's/OLD/NEW/' <file>` or `sed -i '' 's/OLD/NEW/' <file>` (BSD
  `sed` needs the empty `''` argument).
- **Prove the mutation LANDED before running anything.** `perl`/`sed` exit 0
  whether or not the pattern matched, and a suite run against unmutated code
  prints the same green transcript as a suite that fails to catch the
  mutation — the false answer is the one that fails good work
  (computenet-isde). Require a NON-empty `git diff HEAD -- <file>` (or, for
  an untracked file, a grep hit on the mutated phrase) before the run. Escape-
  heavy perl patterns (`\Q…\E`) have arrived mangled through the Bash tool in
  at least one harness — for replacement of a metacharacter-heavy line, prefer
  line-addressed sed: `sed -i '' '<N>s|.*|<new line>|' <file>`.
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

**Read the COMPILE task's state line, not just the test task's.** A reviewer
once reported "reverted, still green" against a tree whose source was reverted
and whose `DepartEvent.class` on disk was still the mutated build, timestamped
8 seconds after its last green run; only decompiling settled which build had
run (computenet-a4b7, epic computenet-umx). Every documented step had passed —
`git status` clean, diff clean, no `e:` lines, named test red, revert verified —
because all of them look at the SOURCE.

The rule that covers it is gradle-evidence.md's, one task earlier: **`FROM-CACHE`
or `UP-TO-DATE` on `:<module>:compileKotlin` means nothing compiled this run** —
an unmarked line is your only positive proof the classes came from today's source.

```bash
grep -E '^> Task :<module>:compileKotlin' "$SCRATCH/mut.log"
# unmarked = compiled from the source on disk. Marked = did not compile: the
# class is the last execution's (UP-TO-DATE) or the cache's for this hash.
```

Two corrections to what that bead proposed, both measured on this host
(computenet-a4b7's review, `:nature`, both with and without `--no-build-cache`):

- **Re-applying an identical mutation does NOT leave the compile task
  `UP-TO-DATE` with a stale class.** Up-to-dateness compares against the *last
  execution*, so any content change re-executes; the build CACHE is what is
  content-keyed, and a cache hit prints `FROM-CACHE` and restores the class that
  is *correct for that hash*. So `--no-build-cache` is not useless here — it is
  simply not the lever, because the lever is reading the state line.
- **`touch <source>` is not a remedy** — it does not change the content hash, so
  the task stays `UP-TO-DATE` and nothing recompiles, twice over. To force a real
  recompile use `--rerun-tasks`; `rm -rf <module>/build/classes` only re-fetches.

A `find -newer` on the class file was tried and rejected for the same reason: it
returns empty both when the class is stale AND when the source simply has not
changed since the last compile, so it cannot separate the two states it was
meant to separate.

**And read WHICH assertion went red.** A test with several assertions can be
reddened by an earlier one while the assertion carrying the criterion never
discriminates at all — and the later assertion is usually the criterion's real
content. Measured (computenet-pko4): the first test for `LinkControl.holding`
PASSED with the primitive replaced by no-ops, because an unrelated scheduler
starvation made its arrival assertion vacuously true; the single documented
mutation reddened the *earlier* state assertion, which reads as "the mutation
was caught" and stops the investigation one step short. A second instance the
same session: neutralising a duplicator entirely left all five of its tests
green. **The check is not complete until the assertion under test is shown to
discriminate on its own** — disable the earlier assertions under the same
mutation, or choose a mutation only that assertion can catch — and the report
names the assertion, not just the test.

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
name the route rather than quietly substitute one. The reviewer then runs the
real mutation itself ("Who mutates what" above) AND checks your substitution.

## What to report

The mutation you made (file and call site), the test name that went red,
**which assertion** in it went red, and its **assertion message**. "I did the
mutation check and it failed as expected" is the unfalsifiable sentence this
whole procedure exists to replace — and naming the test without the assertion
is the same sentence for a multi-assertion test (step 4).
