# Feature review

Every task here is closed and each passed its own acceptance criteria. That
is not the same as the feature being done — tasks pass individually and
still leave seams nobody owned or criteria no task claimed.

You judge the **feature's** criteria and record a verdict. You are the last
gate before this merges to `main`. You didn't write this code: read what's
there, not what you expect to be there. You certify; the orchestrator ships
(SKILL.md step 5e) — you never run `gh pr ready`.

Every step below names the evidence it consumes — a diff, a task-count line,
a test name, a command's output. A step you could satisfy by writing
"verified" has not been done.

## Contents

1. Establish the standard — criteria and tasks from the bead
2. Read the actual diff — against freshly fetched origin/main
3. Prove the feature's tests actually ran — no FROM-CACHE evidence
4. Your run is on macOS; the required checks are not
5. Repair by default — up to a bound (authorship limit + `review:` commits)
6. Re-fetch immediately before you certify
7. Decide — verdict shapes and what each requires
8. Report

## 1. Establish the standard

```bash
bd show <feature-id> --json          # acceptance criteria, description
bd list --parent=<feature-id> --all --json  # the tasks (--all: they are closed by now)
bd comments <feature-id> --json > <scratchpad>/comments.json   # then read the file
```

**Read the comments — that third command is not optional.** `bd show --json`
carries only `comment_count`, never the bodies, so a review that skips this
has not seen the thread. On a long-lived item the thread is where the
decisive context lives: prior sessions' handoffs, a *withdrawn*
certification, a corrected premise, a human's answer to a parked question. A
reviewer unaware of a withdrawn certification reviews the wrong thing.
Redirect rather than reading inline — the JSON overruns the tool-result limit
on exactly the beads that need it, and a truncated array reads as fewer
comments rather than as an error (SKILL.md, "Two `bd` JSON traps").

**`bd list --parent` coming back empty is a shape, not a dead end.** This
file is written for a feature with child tasks, but an epic can be broken
down **flat** — bug/task/chore items worked directly, each with its own
worktree, branch and PR and no children of their own (SKILL.md 5f routes 3–4
explicitly permit working unparented items; computenet-9xj records that a
task parented straight to an epic has no feature to merge into). When the
list is empty, there is simply no task layer to reconcile: skip the
per-task-criteria reconciliation below and judge the item against **its own**
acceptance criteria and diff. Everything else here — §2 through §8 — applies
unchanged.

Read the parent epic too, and any spec sections the feature cites — those
are the authority (AGENTS.md), above the feature's own prose.

Judge the criteria as written. If they don't meet
[issue-quality.md](issue-quality.md), tighten them against the epic and the
cited spec before judging, and say in your report that you did — you are
about to certify or hold a PR on them, so an uncheckable criterion is a
decision you'd otherwise be making silently.

## 2. Read the actual diff

```bash
git -C <worktree> fetch origin main
git -C <worktree> rev-parse HEAD          # record this: your review base (see §5)
git -C <worktree> diff origin/main...HEAD
gh pr checks <pr-url>
```

Diff against the **fetched remote** base, never a local `main`: a worktree's
`main` is whatever the machine last fetched, and a stale one produces a diff
that looks plausible and is wrong. If the diff's size or contents surprise
you, suspect the base first and re-fetch before reviewing a line of it.

Look for what task-level review structurally cannot see:

- **Criteria with no owner** — a feature criterion no task claimed, so nobody implemented it.
- **Seams** — task A's producer and task B's consumer that never got tested together, mismatched error handling or naming across the boundary, a shared type each half interpreted differently.
- **Scope drift** — files in the diff no task claimed, or changes nothing asked for.

## 3. Prove the feature's tests actually ran

**First, settle whether any suite applies — by proving it, not asserting it.**
A markdown-only feature has no suite to run, but "docs-only" is a claim about
the diff and needs the diff as its evidence:

```bash
gh pr diff --name-only <pr-url>    # or: git diff --name-only origin/main...HEAD
```

Paste that list into your verdict. It is docs-only only if **every** path is
prose that nothing consumes at build time — `.claude/skills/**`, `doc/**`
*except* `doc/spec/**`, `backlog/**`, `bugs/**`, a top-level `*.md`. That is
an allowlist: a path it does not name is not docs-only, whatever its
extension (`.github/**`, `gradle/**`, `concord/schema/*.md`, `demo/*/ui/**`,
`inspect/ui/**`). Not compiled is not the same as not verified, so three
things disqualify a diff even though they look like docs:
**the whole of `doc/spec/**` and `concord/corpus/*.yaml`**, which
`./gradlew :concord:check` reads as inputs to two fatal gates —
`concordanceGate` (a dangling `covers:` id or an orphan scenario) and
`docLints` (an unresolved `cell.<pkg>.<Type>` pointer, a bad Status header),
plus the generated `doc/spec/CONCORDANCE.md`; any other `.md` or resource
that is a build input; and any mix of docs with compiled source, even one
file. Measured 2026-08-12: appending one sentence naming a nonexistent
package to `doc/spec/00-foundations/03-glossary.md` — no corpus, no
`CONCORDANCE.md`, no source — failed `:concord:docLints` with
`[FATAL] Unresolved package pointer`. In all three the whole diff falls
through to the normal §3/§4 route — run the suite that covers the changed
input and quote its accounting.

If it *is* docs-only, say so with the file list as the evidence, then state
the limit instead of skipping the question: **green required checks on such a
diff evidence exactly one thing — the branch does not break the build.** They
compile and test no changed module (they cache and skip), so they are
evidence of nothing about the content, and citing them as if they were is the
failure this paragraph exists to prevent. You still quote their names and
conclusions per §4, labelled as what they are.

The content's evidence is then the reading in §2 plus execution of whatever
the diff itself makes checkable: run the commands the new text tells an agent
to run, resolve every path and id it cites, and run the greps its acceptance
criteria name — quoting the output. A verdict that reports no such artifact
has reviewed nothing.

The module suites the tasks ran individually may not cover their
interaction. Run the affected module tests, and the repo-wide gate if the
feature touched anything cross-cutting — then prove the run happened.

`BUILD SUCCESSFUL` is not that proof. Gradle replays cached results for
unchanged inputs, and a cached green build is indistinguishable from a real
one in the output you normally read: measured 2026-08-12, a green
`build-test-fast` finished in 21s with `:demo:tiering:test FROM-CACHE` and
`48 executed, 53 from cache`. So "I ran it and it was green" and "I ran it
and nothing happened" are the same sentence unless you read further.

Per suite you run, consume and **quote in your verdict**:

- **The task-count line** — Gradle's `N actionable tasks: X executed, Y from
  cache` (or `up-to-date`) — and confirm the *specific* test task you care
  about is not marked `FROM-CACHE` or `UP-TO-DATE` in the run's task output.
- **The JUnit XML counts and timestamp**, which prove the results are from
  *this* run, not the last one:

  ```bash
  python3 -c '
  import glob, sys
  from xml.etree import ElementTree as ET
  t = f = e = 0; newest = ""
  for p in glob.glob(sys.argv[1]):
      r = ET.parse(p).getroot()
      t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0))
      newest = max(newest, r.get("timestamp", ""))
  print(f"{t} tests, {f} failures, {e} errors, newest {newest}")' \
    'kernel/build/test-results/test/TEST-*.xml'
  ```

An unquantified "suites green" — yours or the implementers' — is not a
verification record, and nobody re-runs it after you: your verdict *is* the
evidence the merge rests on.

`--rerun` binds to the task it follows, not to the command line:
`./gradlew :kernel:test :wire:test --rerun` re-ran only `:wire:test` while
`:kernel:test` came back `UP-TO-DATE`, with both task names on screen and
`BUILD SUCCESSFUL` at the end. One `--rerun` per test task, or one task per
invocation; `--rerun-tasks` for a repo-wide run. The rest of the cache-proof
mechanics — mutation checks, and not destroying a rare failure's evidence
with `-q` — are in [review-task.md](review-task.md) §2; use them here
unchanged when a seam is what you're testing.

## 4. Your run is on macOS; the required checks are not

Run `uname -sm` and put its output in your report. (For a diff proven
docs-only in §3 there is no platform-dependent behaviour to measure: skip to
the `gh pr checks` read below, and report its conclusions with §3's limit
attached.) This repo is developed on darwin; every required check (`build-test-fast`, `build-test-serial`,
`concord-full`, `ui-test`, `agora-ui-test`) runs on `ubuntu-latest`. For most
diffs that gap is invisible; for anything touching sockets, ports, filesystem
semantics, path handling, or process spawning it is exactly where the defect
hides — a `:wire:test` that passed 15/15 locally failed `build-test-fast`
deterministically on ubuntu because the new test encoded BSD/macOS TCP
behaviour, and nothing runnable locally could have shown that.

So:

- Report what you observed, qualified by where you observed it: "green on
  darwin/arm64", never bare "green", and **never "the required checks
  pass"** — you have not run them and must not claim to.
- For a port/socket/filesystem/process feature, turn the inference into a
  measurement: run the suite in a JDK-21 Linux container (`groovy:4.0-jdk21`
  is present locally; `eclipse-temurin:21` costs a ~10-minute pull) and quote
  that result too.
- **Before you set `review=passed`, read the branch's own CI run** — it is
  the only Linux evidence that exists:

  ```bash
  gh pr checks <pr-url>
  ```

  Quote each required check's name and conclusion in your verdict. Two traps:
  a green check on a diff that touches no compiled input is evidence of
  nothing (it too can be cache and skip), so say which checks actually
  exercised the changed modules; and a check still `pending` is not a pass —
  wait for it or certify draft. A **red** required check is not yours to wave
  through: report it and leave the verdict draft.

## 5. Repair by default — up to a bound

A rejection forfeits everything already spent on the feature, so fix what you
can rather than sending it back. Within the feature's stated scope, repair:
missed criteria, broken seams, failing tests, gaps between tasks.

Commit repairs on the feature branch, in the feature worktree:

```bash
git -C <feature-worktree> commit -am "review: <what you fixed>"
```

You reach this point only once every task has merged, so the feature
worktree is yours alone — no other agent is committing here. SKILL.md step 5
("One worktree, one live agent") is what holds that true: the orchestrator
will not dispatch into or remove this worktree until your completion
notification arrives. It has exactly one escape hatch — a fix for a red
post-ready check, dispatched onto **this same branch in a separate worktree**
while you are still running (SKILL.md 5c). It tells you *before* it does, and
what it asks of you is: push everything you have now, then **stop committing
in this worktree** and say so. Comply — do not keep repairing.

**Do not expect a push rejection to warn you.** The second worktree shares
this branch's ref, so the other agent's commit moves your `HEAD` while your
index and working tree stay where they were, and your next `commit -am` lands
as a clean fast-forward that silently reverts its work (measured 2026-08-13 in
a throwaway repo; the push succeeded). The symptom you *can* see is
`git -C <feature-worktree> status --short` reporting changes to files you
never touched — `M` for a file the other agent edited, and `D` for one it
*added* (your index has never seen it, so a `commit -am` of yours deletes it).
If that happens — or if a push is rejected — **do not
commit**: run `git -C <feature-worktree> log --oneline -5`, and if commits you
did not write are there, stop, leave your edits uncommitted, and report that
another agent is on this branch.

**But you cannot certify code you wrote.** SKILL.md 5c insists a task
reviewer is never the agent that wrote the code; the same rule has to hold
for you, who also holds the certification. Measure your own authorship
against the review base you recorded in §2 — **your own commits only** — and
**paste the output into your report**:

```bash
# refresh the exclusion base FIRST: --not origin/main is only as good as this
# worktree's last fetch, and §6's fetch happens after this measurement
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> rev-parse origin/main    # quote this next to the list below

# your own commits: after the review base, and not already on main
git -C <feature-worktree> log --oneline --no-merges <review-base-sha>..HEAD --not origin/main
for c in $(git -C <feature-worktree> log --format=%H --no-merges <review-base-sha>..HEAD --not origin/main); do
  git -C <feature-worktree> show --stat --format='%h %s' "$c"
done
```

Read that list first and confirm every commit on it is one you wrote; the
line counts are only meaningful once it is. Two ways of asking the question
give the wrong answer, both because §6 tells you to merge `origin/main`
mid-review:

- `git diff --stat <review-base-sha>...HEAD` credits you with everything that
  landed on `main` in the meantime. Measured during this file's own review,
  2026-08-12: after merging `origin/main` and authoring nothing, it reported
  `2 files changed, 131 insertions(+), 2 deletions(-)` — all of it commit
  `0440342` from `main`, including a whole new test file
  (`InspectorBindTest.kt`). That is over two of the bounds below, so it turns
  an untouched branch into a forced draft.
- `--no-merges` alone does not fix it. It drops the merge commit, not the
  commits the merge brought in — the same run still listed `0440342`. The
  `--not origin/main` is what excludes them.
- **`--not origin/main` against a stale `origin/main` does not fix it either**,
  which is why the fetch sits above the snippet rather than in §6. Measured
  2026-08-12 on a synthetic branch built to this exact shape (base commit,
  a commit landing on `main` mid-review, one merge of `origin/main`, one
  `review:` repair): with `origin/main` left where the worktree happened to
  have it, the command listed two commits — `review: my one repair` **and**
  `main commit A`, which the reviewer never touched; after
  `git fetch origin main`, the same command listed only `review: my one
  repair`. It fails in the safe direction — it over-counts, and can never hide
  your own work — but a single extra file is enough to cross the bounds below
  and force a draft on an untouched branch.

Your repairs are **substantive**, and disqualify you from certifying, if any
of these is true:

- more than ~30 changed lines total across your repair commits, or more than
  three files touched;
- any **new or semantically changed test, corpus scenario, or assertion** —
  writing the check that decides the verdict is authoring the verdict;
- any regenerated generated file (`CONCORDANCE.md`, KSP output consumers);
- any new claim filed against the honesty ledger (`concord/corpus/DISPUTES.md`)
  or a new bead asserting an existing requirement is broken;
- any change to public API, wire format, or behavior outside the failing path
  you were fixing.

Anything else — a typo, a comment, formatting, a rename in place, a one-line
fix with an existing test already covering it — is **trivial**, and you may
certify normally.

On substantive repairs: **hand back a draft verdict.** Do not set
`review=passed`. Say in the comment and the report exactly what you authored
(the `--stat`, the commit shas, and what each commit does), so the
orchestrator can dispatch an independent check of *your* commits before
shipping. File beads tasks for anything you did not repair. The work is not
discarded — it is on the branch, pushed, waiting for a second pair of eyes.

Escalate instead of repairing when the approach is wrong at the design level,
or when repair would rewrite most of the diff. Then apply the
[ask-human.md](ask-human.md) bar — that's a decision for a human, not a
rewrite you do unilaterally.

## 6. Re-fetch immediately before you certify

A verdict is only valid against the `main` the PR will actually merge into.
A thorough feature review takes 30–60 minutes; auto-merge lands a ready PR
within minutes of its checks going green, and this skill is *told* to keep
several PRs in flight. So `main` moving under you is the normal case, not the
exception. It has already happened: during the review of PR #56, PR #54
merged a change to the very subsystem under review (`WsTransport.loopback`);
the reviewer caught it only because it happened to re-check.

Last thing before recording the verdict:

```bash
git -C <worktree> fetch origin main
git -C <worktree> log --oneline $(git -C <worktree> merge-base HEAD origin/main)..origin/main
```

- **Empty output** → nothing landed since you integrated. Say so in the
  verdict ("re-fetched at <time>, origin/main unchanged at `<sha>`").
- **Any commits** → your evidence is stale. Merge `origin/main` again, re-run
  at least the affected module suite (§3, with fresh task-count and JUnit
  numbers — the old ones no longer describe the code being merged), and quote
  the shas that landed. If one of them touches the same subsystem as this
  feature, re-read the diff too, not just the tests.

Quote the shas or the "unchanged" line in the verdict. A verdict with no
re-fetch line is a verdict against a base that may no longer exist.

## 7. Decide

Three outcomes, not two.

### Ready

Every feature criterion met, required checks green, no unowned seams — the
three criteria in AGENTS.md § "Marking a PR ready is the agent's call" hold —
and your own repairs were trivial by §5. Record the verdict, but **do not run
`gh pr ready`**: on this repo a ready PR merges itself, and you are the party
that just certified (and possibly repaired) this code, so shipping it too
would be self-approval. The orchestrator reads your verdict and ships:

```bash
git -C <worktree> push
bd comment <feature-id> "Review passed: <criteria verified with their evidence; test counts and executed/from-cache accounting; uname; gh pr checks conclusions; re-fetch result; what you repaired and its --stat>. Verdict: ready."
bd update <feature-id> --set-metadata review=passed
```

### Ready with residual — the honest negative result

The diff is sound and mergeable **and** a named criterion is genuinely not
met. This is a real shape here, not a fudge: a diagnosis feature whose honest
outcome is "could not reproduce the hang across 19 green runs, but here is a
separately measured resource leak fix" is good work plus an unmet criterion,
at the same time. AGENTS.md already blesses the discipline — a requirement
that cannot be checked honestly is filed, never weakened into a passing
scenario. Failing sound work for it, or passing it and letting the criterion
disappear, are both wrong.

Merge it, and keep the residual alive:

```bash
bd create "<the unmet criterion, verbatim>" --type=bug --parent=<epic-id> \
  --description="Residual from <feature-id> (PR <url>): <what was tried, what was measured, why it is unmet>" \
  --acceptance="<the original criterion, unchanged>"
bd comment <feature-id> "Review passed with residual: <verified criteria + evidence as above>. NOT met: <criterion, verbatim> — <evidence that it is not met>. Filed as <new-id> under <epic-id>, which is what carries the unmet criterion forward."
bd update <feature-id> --set-metadata review=passed
bd update <feature-id> --set-metadata residual=<new-id>
```

`review=passed` is deliberate: an unmet criterion is not a reason to withhold
a merge of code that is otherwise correct. The orchestrator still closes
`<feature-id>` when the PR merges (SKILL.md 5e) and that is correct — the
residual bead, not the feature, is what carries the criterion forward. That
is why it is filed under the **epic**: the epic cannot close while it is
open, and it is the epic's owner who schedules it, not you. Name the unmet
criterion verbatim in the comment; a residual glossed as "minor follow-up" is
how it stops existing.

### Draft

Not good enough, or your repairs were substantive (§5). Say concretely why,
and leave the work recoverable rather than vague:

```bash
bd comment <feature-id> "Review: staying in draft. <what's missing and why repair wasn't the right call, or: what I authored and why it needs an independent check — <--stat and shas>>"
```

Create beads tasks for the remaining work (`bd create --parent=<feature-id>`
with `model` and `files` metadata, per [feature.md](feature.md)) so the next
batch picks them up — a feature left in draft with no tasks describing what's
missing is a dead end. Two drafts legitimately have no tasks, and each is
routed by what the comment names rather than by a task id (SKILL.md 5e):
the **substantive-repair** case, where nothing is missing except a reader for
your own commits — name the commit shas and their `--stat`, or it is the same
dead end; and a **red required check** (§4), which is not the feature's work
to task — name the check and its conclusion. Do not set `review=passed`.

Draft is a legitimate outcome, not a failure. Half a feature merged is worse
than half a feature parked on a branch.

### In every case

**Don't run `bd dolt push`** — issue-state sync to the remote is the
orchestrator's job (it serializes pushes across concurrent agents); your
local `bd` writes are enough. That comment and metadata stay in the local
beads DB; the orchestrator's Finalize push (SKILL.md step 6) sends them to
the shared tracker. The `review=passed` marker is read by this machine, which
is the one that resumes the feature, so local is where it needs to be.

**Don't `bd close` the feature.** Ready is not merged: a required check can
still fail and leave the PR open forever. Closing here would let the epic
close on top of it and abandon the branch. Leave it `in_progress`; the
orchestrator closes it once it has confirmed the PR actually merged.

## 8. Report

The feature id, the verdict and why, and — as artifacts, not adjectives — the
test counts with their executed/from-cache accounting, `uname -sm`, the
`gh pr checks` conclusions, the re-fetch result, the `--stat` of everything
you authored, and any tasks or residual beads you created. If you left it
draft, name the single thing that would most change the verdict.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
