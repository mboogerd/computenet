# Task review

One task, on its own branch, before it merges into the feature branch. You
didn't write it: read what's there, not what you expect to be there.

You judge it against **the task's own acceptance criteria** — nothing wider.
Gaps between tasks, and criteria that live at the feature level, belong to
the feature review ([review-feature.md](review-feature.md)). Don't reach for
them here, and don't expand the task's scope to close them.

**Your tracker writes are scoped the same way.** Write to **the bead under
review and to items you create** — nothing else. Closing, re-prioritising,
reassigning, re-parenting or claiming any other bead is the orchestrator's.
That cuts both ways when you judge the diff: if the implementer wrote to
another bead, check the task's acceptance criteria and the cross-bead line in
its dispatch before calling it scope creep — a criterion can commission a
comment on named sibling beads, and on computenet-dqy.72 exactly that
happened and was briefly read as an overstep (computenet-szdd). Criterion-
prescribed writes are commissioned work. A close or a priority change on
another bead is not, whatever prescribed it: report it rather than repairing
it.

**When a criterion-prescribed cross-bead write is itself a DELIVERABLE and you
find it WRONG, say so under the literal heading REQUIRED ORCHESTRATOR
CORRECTION.** Your writes do not reach the other bead, so you cannot fix it —
and the defect sits in the exact artifact you were asked to verify. Measured:
a task's `cross_bead` commissioned posting an audit result as a comment on its
parent feature, where it is a feature-level deliverable the feature cannot
close without. The reviewer verified it and found its stated completeness bound
wrong (it said 14 files; 15, and 16 counting `minAuth`), confirmed this was a
reporting defect rather than a coverage one, and reported plainly "I could not
correct it — the audit lives on <feature> and a reviewer's writes don't reach
another bead". That was correct behaviour, and the only thing between a wrong
number sitting permanently in a feature-level deliverable and its correction
was how prominently that reviewer chose to mention it (computenet-59f5).

That literal framing is the point: it makes the finding something the
orchestrator cannot read past, and merge-task.md requires acting on it before
the merge. Distinct from computenet-szdd (making the write VISIBLE to the
policy check) and computenet-eetn (restating it in the dispatch prompt) — both
concern the write being authorized and seen; this is who may CORRECT it once
found wrong.

**A negative finding about another agent's tracker writes needs a lookup, not
a search.** Verifying that a claimed follow-up bead was really filed is a
natural and valuable review check, and it is precisely the query `bd search`
is worst at: it matches a literal substring of the **title and id only** —
descriptions are invisible, and a multi-word query hits only when those words
appear verbatim and adjacent. You will search from the residual's *subject*
wording while the bead was titled by its author, so the two rarely share an
adjacent word sequence, and **an empty result is no evidence at all**. A
reviewer that trusted one reported that an implementer "claimed to file a bead
and did not"; `bd show computenet-yhbd` returns that bead, open and correctly
parented (computenet-tay3). Check by **id** (`bd show <id>`) when one is
named, otherwise `bd list --parent=<epic> --all --json` or a grep of
`.beads/issues.jsonl`. If you cannot confirm either way, report the
uncertainty — never the accusation.

## Contents

1. The standard, then the diff — criteria from the bead, diff against the resolved base
2. Prove the tests ran — not replayed from cache, and not zero tests
3. Your run is on macOS; the required checks are not
4. Repair, don't bounce — the authorship bound and `review:` commits
5. Report — the verdict, stated in as many words

Plus: **When the diff under review edits `.claude/skills/work/`** — the extra
reading a self-modifying change needs, before §5.

## 1. The standard, then the diff

```bash
bd show <task-id> --json                          # criteria, files claim (a LIST — unwrap .[0])
bd comments <task-id> --json                      # the implementer's landing notes
git -C <task-worktree> fetch origin main
git -C <task-worktree> fetch origin <feature-branch> || true   # may not exist yet
FB=$(git -C <task-worktree> rev-parse --verify -q origin/<feature-branch> \
     || git -C <task-worktree> rev-parse --verify -q <feature-branch>)
BASE=$(git -C <task-worktree> merge-base "$FB" HEAD)
git -C <task-worktree> diff "$BASE" HEAD
```

**Say in your report which baseline you diffed against and why.** Three are
possible — `origin/<feature-branch>`, the local `<feature-branch>`, or the
base commit the dispatch names when neither exists — and they are not
interchangeable: a reader cannot check your line counts or your scope claims
without knowing which one produced them. Your dispatch prompt states whether
the feature branch is on origin yet, so this is a fact you are given, not one
to discover by a failed command (computenet-e3my).

`bd show` never returns comment bodies, and the implementer's reasoning —
what it decided, what it deliberately left — usually lives only there. Read
both before the diff.

**An empty diff is not proof of no work — check the worktree first.**

```bash
git -C <task-worktree> status --short
```

A finished deliverable can exist only as an uncommitted working-tree file:
computenet-8kj.4.1 was reported complete with a 788-line document that was
never committed, on a branch byte-identical to `origin/main`. Every
downstream step reads that as nothing — the merge merges nothing, and a
reviewer diffing `origin/<feature-branch>...HEAD` sees an empty diff and can
reasonably fail the task for having produced nothing. If `status` is not
empty, **say so and name the files**: the work exists and the defect is that
it was not committed ([task.md](task.md) step 7 makes the commit the required
final step — and the whole handoff, since implementers do not push), which is
a different verdict from "produced nothing". Do not
commit it for the implementer without saying you did.

**Diff against the resolved base, never a bare local `main`.** A task worktree's
`main` is whatever the machine last fetched, and its copy of the feature
branch can be behind the merges the orchestrator has already made. Both
produce a diff that looks plausible and is wrong: the obvious
`git diff main...HEAD` once returned 21 files of unrelated repository history
in place of a 4-file task change, and on 2026-08-12 a branch forked before
`origin/main`'s last 20 commits produced a 24-file, 1,862-deletion diff that
reverted merged wire and demo-shell tests — one `gh pr ready` from landing
under auto-merge. If the diff's size or contents surprise you, the base is
the first thing to suspect: re-fetch and diff again before reviewing a line
of it. Fetch `main` in the same command so
`git log --oneline origin/main..origin/<feature-branch>` is available — if the
feature branch itself forked long before current `origin/main`, say so in your
report: that is a merge hazard the feature review has to resolve, not
something to fix on a task branch.

**The feature branch may not be on origin at all.** 5a pushes it and 5d opens
its PR only after the FIRST task merges, so for task 1 of every feature
`origin/<feature-branch>` does not exist and a bare
`git fetch origin <feature-branch>` fails hard with `couldn't find remote ref`.
That is why the fetch above is best-effort and the base comes from
`merge-base`: worktrees of one repository share their refs, so the local
`<feature-branch>` the orchestrator created is readable from the task
worktree whether or not it has ever been pushed, and the merge-base of it
with HEAD is the right baseline in both cases. Do not substitute
`origin/main` by hand — it is the correct base only when the feature branch
happens to be sitting on it, which is true for a fresh feature and false for
a resumed one carrying prior task merges, where it silently pulls that prior
work into your diff. Three reviewers in one session each rediscovered the
failure and each improvised that substitution (computenet-u1ai).

Check:

- **Each acceptance criterion**, actually met — not plausibly gestured at.
- **Writing metadata: `--set-metadata key=value` on `bd update`.** `bd update
  --metadata` exists too but takes a **JSON object**, so
  `bd update <id> --metadata review=passed` fails with
  `Error: invalid JSON in --metadata: must be valid JSON`. Use
  `--set-metadata`, which sets one key without quoting a document. Both
  *merge* into the existing metadata rather than replacing it (measured on bd
  1.1.2: `--metadata '{"k":"v"}'` upserts `k` and leaves the other keys
  standing, and `--metadata '{}'` is a no-op) — so neither flag will clear a
  key for you: clearing is `bd update <id> --unset-metadata <key>`, and
  `--set-metadata key=` leaves an empty-string sentinel behind instead.
  (`bd create` is the mirror image: `--metadata '{"k":"v"}'` there, and no
  `--set-metadata` at all.)
- **The file claim.** Files touched outside `metadata.files` are a real
  problem: sibling tasks were scheduled in parallel on the assumption that
  claim was accurate. Report every one, even if the change itself is fine.
- **Tests.** Run the narrowest relevant suite per AGENTS.md, and verify it
  actually ran (§2). A task that changed behavior without a test asserting it
  hasn't finished.
- **Scope.** Changes nothing asked for, debug leftovers, unrelated
  reformatting.
- **Caveats in the changed file, not just the paperwork.** Any limit the
  change relies on — one workload, single trial, "not itself measured" —
  belongs next to the claim it qualifies, in the shipped text. Check for it
  *there* specifically: an honest bead comment and PR body are what conceal
  its absence, because body-plus-diff then reads as a consistent story
  (computenet-k9d.2, then k9d.7 immediately after — the only two instances
  seen; both times the claim sat in prose — a docstring, a skill file —
  though k9d.2's change was code, and both were found only because a reader
  was told to look). Absent, repair it under §4; it is not a wording preference. The
  body is read once, at merge; the file is read by every agent afterwards
  and by whoever next wants to change the number. Same rule as
  [orchestrator-authorship.md](orchestrator-authorship.md), displaced by one
  artifact: the caveat exists and the author is honest, it is simply written
  where nobody who needs it will look.
- **The criteria themselves.** If they don't meet
  [issue-quality.md](issue-quality.md) — uncheckable, or just the title again
  — that's a breakdown defect. Where the parent feature makes the intent
  clear, tighten them (`bd update <task-id> --acceptance=…`) and review
  against the tightened version, saying so. Where it doesn't, you can't judge
  this task: say that rather than passing it on vibes.
- **Missing entirely** — empty, or the key absent from `bd show --json`
  altogether (both read as `null`, and neither is a `bd` failure).

  **Read the key by its real name before you believe this.** The write flag is
  `--acceptance`, the JSON read key is **`acceptance_criteria`**, and
  `jq '.[0].acceptance'` answers `null` on a bead that *has* criteria
  ([bd-traps.md](bd-traps.md)). A reviewer that made exactly this misread
  entered the ladder below for no reason and overwrote five real criteria with
  nine of its own (computenet-2rix). Confirm with the correct key, from a file
  so the read cannot truncate, before concluding anything is missing:

  ```bash
  bd show <id> --json > "$SCRATCH/<id>.json"
  jq -r '.[0] | has("acceptance_criteria"), .acceptance_criteria' "$SCRATCH/<id>.json"
  ```

  Only if *that* is empty does the ladder apply. This is the
  shape a directly-filed bug or chore arrives in, with no breakdown to have
  written them, and three reviewers hit it in one session. Do not invent a
  standard silently: that is the reviewer marking its own paper, and it is
  worse than it looks, because the bar lives only in your report and a resumed
  item gets a different one (computenet-n58c). Fall back **in order**, and
  **say which text you used** (computenet-qxg5):
  1. the **structured description**, read with the parent feature or epic;
  2. the **comment thread** — `bd comments <id> --json > "$SCRATCH/c.json"`,
     then read the file, since `bd show` carries only `comment_count`;
  3. **nothing locatable anywhere → park it**, rather than pass.

  Whichever answered, write it down *before* you judge and quote it in your
  verdict: written down it survives the session and the orchestrator can
  disagree with it. Write reconstructed criteria **to a comment**
  (`bd comment <id> --file "$SCRATCH/criteria.md"`), not over the field with
  `bd update --acceptance=…`. That write is destructive and unrecoverable —
  the beads JSONL export is untracked, so there is no history to restore a
  clobbered field from, and a reviewer that reached the ladder by the misread
  above destroys the very criteria it was meant to judge against
  (computenet-2rix). This is a backstop,
  not the normal route — SKILL.md 5f has the orchestrator write criteria
  before dispatch, so their absence is itself a finding. Name it in your
  report alongside the criteria you wrote.

## 2. Prove the tests ran

**First settle which standard applies.** Everything in this section is
Gradle-shaped, and a task whose deliverable is a *written finding* — a
measurement, a spike, a documented experiment, a runbook — has no suite to
cache-prove. It is not therefore unreviewable, and it is not exempt:

> **For an empirical or documentation deliverable, the standard is
> independent re-execution.** Take the commands, queries and sequences the
> document records, run them yourself, and compare what you observe against
> what it claims. A verdict that only *read* the document has reviewed
> nothing.

That is not hypothetical rigor. On epic computenet-8kj re-running is what
caught every real defect: a factually wrong order-sensitivity assertion,
transcripts that were not replayable in the order printed, and a finished
deliverable left uncommitted. Four reviewers on that epic each derived this
standard from the orchestrator's dispatch prompt rather than from this file,
which worked only because the orchestrator happened to say it every time.

**A DERIVED document is traced, not re-executed.** A consolidation — one that
assembles findings its sources already established, and runs nothing of its
own — has no commands to re-run, and re-executing its *sources* re-reviews
work that already passed. Its standard is a trace: for each claim, name the
upstream artifact that established it and the review that certified that
artifact, and check that the claim still says what the source says.
Untraceable claims — present in the derived document, absent upstream — are
the defect this catches, and they are exactly what a re-execution standard
misses. Where the document *does* run something of its own, that part is
re-executed as above; the two standards apply per claim, not per document
(computenet-bx4y).

**Tolerance on transcripts.** Real `bd` output is pretty-printed and carries
warning preambles; documents paste one-line JSON. Reformatting, eliding a
preamble, and truncating a long array with an explicit ellipsis are **not**
defects. What is a defect: a changed value, a changed ordering that the
document's own argument depends on, an invented field, or output presented as
verbatim that cannot be reproduced by running the stated command.

Then, for anything with a suite:

`BUILD SUCCESSFUL` is not evidence that a test executed — a cached green
build is indistinguishable from a real one in the output you normally read.
**[gradle-evidence.md](gradle-evidence.md) is the proof standard**: the
task-count line, the per-task state line read as an absence (four states,
only two marked), the `| tail -N` and `-q` traps that destroy it, `--rerun`
binding and the build-cache restore it does not show, and the JUnit XML
counts + `timestamp` via `.claude/skills/work/scripts/junit-count.py`. Consume all three signals
per run and quote them — counts, module list, `newest` — in your report. An
unquantified "suite green", yours or the implementer's, is not a
verification record, and the orchestrator never re-runs it: your report *is*
the evidence the next session trusts.

**Carry `--no-build-cache`, here, at the point of use.** A bare `--rerun` can
still restore a CACHED result: the console prints its task-count line, the
`> Task :<module>:test` line carries no marker, and only the JUnit `timestamp`
betrays it — so two of the three signals agree and an agent closing its
evidence gathering stops. Measured twice on two different modules
(`:concord`, then `:oracle` with a `newest` ~4 minutes stale — computenet-qsfu,
computenet-qdj6), both caught by suspicion rather than by procedure. The flag
belongs in the command you actually run:

```bash
./gradlew :<module>:test --rerun --no-build-cache
```

**Where a prior task's measurement artifacts live**, when the deliverable is a
measurement and re-rendering it from the raw artifact is your strongest check:
**read the implementer's `bd comment` on the task first** — the acceptance
criteria for measurement tasks require it to record the results-file and log
paths, so it is authoritative and costs one command. Failing that, the session
scratchpad (`/private/tmp/claude-501/<session>/scratchpad/…`) is the usual
home, and a gitignored path inside the task worktree (e.g.
`bench/build/bench-results/`) the other. **Do not `find` over the home
directory** — on this machine it consumes the entire 5-minute tool cap and
takes the rest of that call's output with it (computenet-ewyo). This matters
because re-rendering from the retained artifact and diffing byte-for-byte
against the committed text is what proves a table was tool-produced rather
than hand-typed — and it is unavailable if the artifact cannot be located.

Beyond that standard, a reviewer owes the stronger signal:

- **The strongest signal is cache-proof: break it and watch it fail.** For a
  test-bearing task, mutate the production code the test is supposed to
  constrain, re-run, see the *named* test fail, revert. A test that passes
  both ways proves nothing, and no cache can fake a red run. Report the
  mutation you made, the test name that went red, and its assertion message —
  "I did the mutation check and it failed as expected" is the same
  unfalsifiable sentence this section exists to stop.

  **A mutation must still COMPILE, and a multi-clause guard is mutated one
  clause at a time.** Deleting a whole `if (a && b && c)` usually breaks the
  build — and a compile failure greps identically to a killed test, so read
  the **build** result before the test result. Weakening one clause keeps it
  compiling and tells you *which* clause the test constrains, which is the
  finer answer anyway (computenet-danb).

  **When the task's deliverable IS the test — a repaired instrument, a new
  read barrier or probe — there is no production change to mutate, so the
  mutation runs the other way**: remove the instrument the task added and
  confirm the suite's verdict actually changes, i.e. that the barrier is what
  makes the test discriminate rather than decoration (computenet-wpvy.34).
  Everything below applies unchanged, marker included: task.md step 3 draws no
  distinction between a mutated production file and a mutated test one, and a
  committed test mutation is the quieter failure of the two — a green suite
  checking less than it claims.

  **Read the BUILD LOG, not only the JUnit XML.** A mutation that fails to
  *compile* leaves the previous run's XML on disk, and that stale XML parses
  as a plausible result — a mutation verdict for a mutation that never ran.
  It is not an exotic case: a mutation is often *expected* to break
  compilation. Caching and a failed compile look identical from the XML
  alone, and §2 warns only about caching (computenet-2x5l). Check both:

  ```bash
  ./gradlew :<module>:test --tests '<TestName>' --rerun --no-build-cache \
    > "$SCRATCH/mut.log" 2>&1
  grep -E '^e:|BUILD' "$SCRATCH/mut.log"     # 'e:' lines = it never compiled
  ```

  A `BUILD FAILED` with `e:` lines is **not** a red test. Fix the mutation
  until it compiles, or pick a different one, and never quote the XML from a
  run that did not build.

  **Revert only what you mutated.** `git checkout -- <file>` is file-granular
  and silently discards any *other* edit you made to that file earlier in the
  review — §2's mutation and §4's repair both happen and nothing orders them,
  so this is the ordinary case, not a corner (one reviewer lost a KDoc repair
  this way). Undo the mutation by hand, or park your own work as a patch
  first:

  ```bash
  git -C <task-worktree> diff -- <file> > "$SCRATCH/my-repairs.patch"
  git -C <task-worktree> checkout HEAD -- <file>   # now only HEAD's content
  # ... mutate, re-run, watch the named test FAIL ...
  git -C <task-worktree> checkout HEAD -- <file>   # undo the mutation — HEAD, not the index (mutation-check.md step 5)
  git -C <task-worktree> apply "$SCRATCH/my-repairs.patch"
  ```

  **Not `git stash`.** `refs/stash` is a single repo-wide ref *shared by every
  linked worktree* — a dozen of them run here at once — so `git -C <mine>
  stash pop` silently pops whatever agent stashed last, exit 0, wrong file
  contents (measured). A patch in your own `$SCRATCH` is worktree-local and
  cannot be taken by anyone else.

  **The procedure is [mutation-check.md](mutation-check.md); follow it rather
  than improvising.** It carries the order that makes the check safe (commit
  first), what to do when the Edit tool refuses the strongest mutation, why
  `--rerun` alone can restore a cached XML, and how to verify the revert
  actually reverted. Three sessions each got a different one of those wrong.

  **Leave the marker while it is applied** — the same rule
  [task.md](task.md) step 3 gives implementers, and it matters more here,
  because the incident that produced it (computenet-leg) was a *feature
  review* killed mid-run. A budget expiry between the mutation and the revert
  leaves a worktree dirty in exactly the shape of finished work, and the next
  session cannot tell them apart:

  ```bash
  echo "<the call site you mutated, and what you removed>" > <task-worktree>/.mutation-in-progress
  # ... mutate, re-run, watch the named test FAIL, revert ...
  rm <task-worktree>/.mutation-in-progress
  ```

  It is gitignored, so it can never be committed. Never commit while it
  exists. SKILL.md 5a is what reads it.

**A run that stalls, times out, or dies before your tests run is probably
this skill's own parallelism** — the signatures (`buildLogic.lock`, the
Kotlin-daemon `OutOfMemoryError` and the machine-wide scope of `pkill -f
KotlinCompileDaemon`, `awaitUntil` wall-clock timeouts) and what is never
contention are in [gradle-evidence.md](gradle-evidence.md). Retry once on a
signature, and if you fail a task on a build result, say which attempt it
was — contention reported as a defect sends the implementer after something
that is not there.

**Don't destroy a rare failure's evidence.** If a run's *failure* is what
matters — a flake hunt, a repetition loop — do not pass `-q`: it keeps the
detail off the console and it does not reach the Gradle daemon log either, and
the JUnit XML is the only place the suppressed exception and pre-interrupt
thread dump live. The next iteration overwrites `<module>/build/test-results`,
so the one occurrence you waited for is the one you lose.

**Don't hand-roll the loop either — it is committed.** `scripts/flake-loop/`
drives the JUnit Platform in-process over a package selector: one iteration
costs seconds instead of Gradle's ~40s, and every *failing* iteration gets its
own append-only file under `<out>/failures/` with the full stack trace, which
is the overwrite problem solved rather than worked around. A sample where
nothing *ran* cannot be read as a sample where nothing *failed*: the harness
takes iteration 1's own executed count as the baseline for the rest of the run
and refuses to start if that count is 0. Don't pass `--expect-tests N` unless
you are pinning a specific figure — the number changes whenever the module
gains a test, which is how every committed copy of it went stale
(computenet-dqy.56).

```bash
./gradlew -q --no-configuration-cache :wire:testClasses
CP=$(./gradlew -q --no-configuration-cache \
       -I scripts/flake-loop/print-test-classpath.init.gradle.kts \
       :wire:printTestClasspath | grep -v '^WARNING' | tr '\n' ':')
java -cp "$CP" scripts/flake-loop/SuiteLoop.java --package civictech.wire \
  --runs 260 --out build/flake-loop --label local
```

Substitute your module and package; quote the final `SUMMARY` line (it carries
the sample size, the failing-iteration count, the baseline it checked against,
and `unexpectedTestCountIterations`, which must be 0 or the sample is not what
it claims). Read that baseline rather than trusting the zero — a run whose
iteration 1 was itself truncated agrees with itself for the rest of the sample.
`scripts/flake-loop/run-linux-loop.sh` runs the same instrument in a JDK-21
container, with defaults shaped for `:wire`.

This supersedes the `gradlew`-in-a-loop below for any suite selectable as a
JUnit package. Keep the Gradle loop only where the harness cannot express the
sample — a flake that needs a **fresh JVM per iteration** (SuiteLoop reuses
one JVM, and the rates differ: the same macOS flake measured 0.83% fresh-JVM
against 0.26% in-process), or a suite that is not JUnit-on-a-classpath
(`:concord`, the npm UI suites). Then archive before re-running:

```bash
archive=$(mktemp -d)
for i in $(seq 1 100); do
  ./gradlew :wire:test --rerun || {
    cp -R wire/build/test-results "$archive/fail-$i"; echo "kept $archive/fail-$i"; break; }
done
```

## 3. Your run is on macOS; the required checks are not

Run `uname -sm` and put its output in your report — this repo is developed on
darwin, and that is where you almost certainly are. All **six** required
checks (`build-test-fast`, `build-test-serial`, `concord-full`, `ui-test`,
`agora-ui-test`, `kernel-test`) run on `ubuntu-latest`. For most diffs
that gap is invisible; for anything touching sockets, ports, filesystem
semantics, path handling, or process spawning it is exactly where the defect
hides — a `:wire:test` that passed 15/15 locally failed `build-test-fast`
deterministically on ubuntu because the new test encoded BSD/macOS TCP
behaviour, and nothing runnable locally could have shown that.

So:

- Report what you actually observed, qualified by the platform you observed it
  on: "green on darwin/arm64", never bare "green" and never "the required
  checks pass". You have not run them and must not claim to have.
- Defer the platform verdict to CI. The branch's own CI run is the only Linux
  evidence that exists, and reading it is not your step: the feature reviewer
  checks `gh pr checks` before certifying, and the orchestrator re-checks it
  before `gh pr ready` (SKILL.md step 5e). Say plainly in your report that
  Linux is unverified, so neither of them skips that gate on your say-so.
- For a port/socket/filesystem/process item, turn the inference into a
  measurement: run the suite in a JDK-21 Linux container (`groovy:4.0-jdk21`
  is present locally; `eclipse-temurin:21` costs a ~10-minute pull) and quote
  that result too.

## 4. Repair, don't bounce

Rejecting forfeits everything already spent on the task, so fix what you can
within its stated scope: a missed criterion, a thin test, a small wrong
edge. Commit on the task branch:

```bash
git -C <task-worktree> commit -m "review: <what you fixed>" -- <the paths you edited>
git -C <task-worktree> status --short          # expect empty
```

**Commit; do NOT push.** A dispatched agent's `git push -u origin
<task-branch>` is denied by the permission classifier, and the task branch is
local by design (computenet-zmso, [task.md](task.md) step 7): the orchestrator
merges `task/<id>` into the feature branch from the *local* ref, which
worktrees of one repository share, and pushes the feature branch. **Name your
repair commit's sha and `--stat` in your report** — that is what tells the
orchestrator its merge should contain your work, and the only check that
catches a repair the merge missed.

`-m … -- <paths>`, never `-am`: this repo's shared index needs the pathspec,
and `git` rejects the combination outright — `fatal: paths … with -a does not
make sense` (computenet-2x5l).

**Your repairs have an authorship bound, as a feature reviewer's do**
([review-feature.md](review-feature.md) §5 states the same idea at feature
scale, in more detail and in wording that is still being amended; the bound
below is the one that governs a *task* review, so read it here rather than
reconciling the two) — without one, a task reviewer can rewrite the
deliverable and then certify its own text (computenet-r197). Your repair is
**substantive**, and disqualifies you from certifying, if it touches a
**behavioural code path**, or adds or semantically changes a **test or
assertion**, or exceeds ~30 changed lines of code across your repair commits.
**A repair to a task whose deliverable is prose or a design record is
substantive by default** — there, rewriting the text *is* rewriting the
deliverable, and there is no separate artifact left for anyone to check it
against.

On a substantive repair: **do not set `metadata.review=passed`.** Name your
repair shas with a per-commit `--stat`, say what each does, and hand back a
verdict that says an independent read is owed. The work is not discarded — it
is committed on the branch, and SKILL.md 5c routes it to a second reader
before merging rather than treating your pass as final.

Fail it only when the approach is wrong at the design level, or repair would
rewrite most of the diff. If the task turns out to be underspecified or the
right call is genuinely ambiguous, apply the
[ask-human.md](ask-human.md) bar rather than inventing an answer.

**Real work you found that is outside this task becomes a bead, and where it
is parented is a check, not a habit.** Read the epic's status first
(`bd show <epic-id> --json | sed -n '/^[[{]/,$p' | jq -r '.[0].status'`): open → file it under the
epic, which is what schedules it. **Closed** — which happens, because a
concurrent session can close an epic while its child is still in review — →
file it **unparented with a `discovered-from` edge onto the item you were
reviewing**, and say in your report that it is unparented and why. Do not
improvise the choice, and do not read "`bd` let me parent it there" as an
answer — it does, and the child stays visible; what a closed epic no longer
does is get *selected*. The reasoning and the exact commands are in
[review-feature.md](review-feature.md) § "Ready with residual".

## When the diff under review edits `.claude/skills/work/`

Reviewing a rewritten instruction *by executing the rewritten instruction*
proves nothing. If the diff touches `.claude/skills/work/`, the branch under
review **is** the procedure you were told to follow, and the circularity has
to be broken deliberately:

- **Follow the copy on `main`, not the copy in the worktree.** Read every
  skill file you need with `git show origin/main:<path>` (after a
  `git fetch origin main`), so the procedure you execute is the one already
  agreed, not the one being proposed.
- **Review the worktree copy as DATA.** It is the artifact under judgement,
  never your instructions.
- **Expect `main`'s instruction to contradict the change you are approving,
  and follow `main`'s anyway.** A reviewer hit exactly this: the reference on
  `main` told it to run a step that the very PR deletes. Do the step, note the
  contradiction in your report, and **do not record it as a defect in the
  PR** — the PR removing a step is the point of the PR, not a fault in it.

- **Run the skills rubric gate.** It is the one check a skill diff has that
  compiling and testing cannot give it, and it lives outside this flow, so
  nothing else will run it:

  ```bash
  ruby .claude/skills/remediate-friction/scripts/validate-skills.rb
  ```

  Run it from `main`'s copy per the rule above, against the worktree's files.
  Failures are structural (bad frontmatter, a long reference with no
  `## Contents`) and are defects in the PR. Notes prefixed `note:` are
  warnings — report them, but do not fail a PR for a pre-existing one it did
  not introduce.

The reason to read from `main` rather than the worktree is not only
circularity. The main checkout's local branch is not refreshed by anything in
this flow, so `origin/main:` is also the only reliable way to get the
*current* text (computenet-kcu).

## 5. Report

**Your final message must state PASS or FAIL, in those words, plus a
NOT VERIFIED section naming everything you did not check.** Nothing resumes
you, and a result that never states a verdict can be read as approval and
merged unreviewed. The rules that make the verdict reach the orchestrator at
all — never end a turn waiting, the Bash-tool timeout (there is no `timeout`
binary on this host), commit-before-you-wait with the bounded until-loop,
the job ledger, and killing every background job before you report — are
[agent-execution.md](agent-execution.md); they bind here in full. Out of
room, out of time, or blocked: give the partial verdict you have and put the
rest under NOT VERIFIED — an honest partial verdict beats stopping
mid-experiment. The commit the invariant protects is section 4's `review:`
commit, brought forward — still no push. If you stop with a suite still
running, say so ON THE BEAD (which suite, which log, what is committed)
rather than returning the wait as your result.


**Pass** — say what you verified, with the test counts and the executed/from-cache
accounting behind it, and what you repaired. The orchestrator
merges the branch; do **not** merge it yourself, and do not touch the
feature branch or its PR. Concurrent merges into one feature branch race
each other, so merging is serialized by the orchestrator alone.

**Fail** — say exactly what is missing and what you already repaired. Leave
the branch and worktree in place; a later batch resumes the task there with
its context intact.

Either way, name every file touched outside the claim.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
