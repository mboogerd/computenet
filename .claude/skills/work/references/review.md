# Review

Read this when a dispatch prompt makes you the task reviewer, the feature reviewer, or the second reader for a bead. Read [agent.md](agent.md) first. Then read `## Both reviews` and the section for your role, followed by `## Verdict and report` and `## Residuals and follow-ups`. You judge work you did not write, and your verdict is what the orchestrator merges or ships on. Nobody re-runs your checks after you.

## Contents

- Both reviews: the standard, the diff, evidence, platform, repairs, write scope, skill diffs, the second reader
- Task review: one task branch before it merges into the feature branch
- Feature review: the whole feature before it ships to `main`
- Verdict and report: tokens, bead writes, the final message
- Residuals and follow-ups: where found work is filed

Hard constraints:

- **Your final message states the literal verdict: PASS or FAIL for a task, READY or DRAFT for a feature, plus a NOT VERIFIED section.**
- **You never certify a substantive repair you authored.**
- **You never ship, merge, close or sync.** No `gh pr ready`, `git merge`, `git rebase`, `bd close`, `bd dolt push` or `gh run rerun`. A task reviewer never pushes. A feature reviewer pushes only its own repair commits to the feature branch.
- **You never overwrite a bead's acceptance or description.** Criteria you derive go into a comment.
- **Real work you found and did not repair is filed as a bead, not left in prose.**

## Both reviews

### The standard

The bead's own acceptance criteria are the standard. Nothing wider applies, and nothing looser.

```bash
.claude/skills/work/scripts/bead.sh <id>
```

```bash
bd comments <id> --json > <scratch>/<id>-comments.json
```

Read the whole comments file. `bd show` never returns comment bodies. The implementer's decisions, withdrawn certifications and human answers live only in the comments. Read the parent epic and any spec sections the bead cites too, because they outrank the bead's prose (AGENTS.md authority order). Resolve its epic with `.claude/skills/work/scripts/epic-of.sh <id>`: exit 0 prints an epic id or `(unparented)`, both real answers; exit 1 means unresolved.

- The bead changed after dispatch. If the title, description or acceptance differs from what your prompt quoted, say so right after the verdict line. Quote both versions and name the one you scored.
- A criterion's premise is disproved. Score it MET when the work disproves the premise with evidence, and say what was disproved. Do not bend the change until the criterion passes.
- The criteria are uncheckable or missing. If `acceptance_criteria` is empty or null, derive a standard, in this order. First, the description read together with the parent. Second, the comment thread. Write what you used as a comment on the bead before you judge, and quote it in your verdict. If neither source yields a standard, do not pass. Write a `QUESTION:` comment that names what is missing, return FAIL or DRAFT, and put the park under REQUIRED ORCHESTRATOR ACTION ([recovery.md § Parks](recovery.md#parks)).
- Someone claims to have filed a bead. Check by id with `bead.sh <id>` or `bd list --parent=<parent> --all --json`. A search that comes back empty is no evidence ([traps.md § bd](traps.md#bd)). If you cannot confirm the bead either way, report the uncertainty, never the accusation.

### The diff

Diff against a freshly fetched base, never a bare local `main`. Each role's section gives the commands. Record `git -C <worktree> rev-parse HEAD` before you change anything. That sha is your review base, and authorship is measured from it. If the diff's size or contents surprise you, suspect the base first, then re-fetch and diff again.

An empty diff is not proof that no work was done. Run `git -C <worktree> status --short` first. A finished deliverable that was never committed is a different finding from "produced nothing". Name the files, and do not commit them for the implementer without saying you did.

In every diff, check these:

- Each criterion is actually met, not just gestured at.
- The files claim. Every touched file outside `metadata.files` is a finding, even when the change is fine. Siblings were scheduled in parallel on that claim.
- Scope. Look for changes nobody asked for, debug leftovers and unrelated reformatting.
- Caveats live next to their claim in the shipped file. A limit the change relies on, such as one workload or a single trial, must sit beside the claim it qualifies. It is not enough for it to appear only in a bead comment or the PR body. Readers see the file long after anyone reads the body. A missing caveat is a defect, so repair it.
- Commissioned writes. The implementer may have written to another bead. That write is commissioned work if the criteria or the dispatch's cross-bead line prescribe it. A close or priority change on another bead is never commissioned: report it rather than undo it.
- Behaviour changed without a test that asserts it. That task is not finished.

### Evidence you can quote

Every claim in your verdict names its artifact: a count, a test name, a sha, or a command's output. A step you could satisfy by writing "verified" has not been done. [evidence.md](evidence.md) says how to prove a run executed, what reads your change, how to run a mutation check, and how to read CI. Apply the strongest check that fits each thing the diff claims:

| The diff claims or ships | Strongest check | Why |
|---|---|---|
| Behaviour with a test | Run the suite and prove it executed ([evidence.md § Did the tests run](evidence.md#did-the-tests-run)). Then mutate the code the test constrains and name the assertion that went red ([§ Mutation checks](evidence.md#mutation-checks)). | A cached or skipped run looks green, and a red run cannot be faked. |
| A command, flag or entry point written in prose, KDoc or a snippet | Copy it out verbatim, substitute only the placeholders, run it, and compare the result with what the text says. | Documented commands fail silently and plausibly, and read fine. |
| A script outside any Gradle source set | Execute it, and make each verdict arm fire by feeding it the state that should produce each one. Run its companion `*.test.sh` when one exists. | A script that prints OK proves only that it runs. |
| A measurement, spike or runbook | Re-execute the recorded commands and compare. Re-render tables from the retained artifact, at the path the implementer's comment records. | A review that only read the document reviewed nothing. |
| A derived document that runs nothing of its own | Trace each claim to the upstream artifact and review that established it. | Untraceable claims are the defect. |
| Figures or ratios in comments | Recompute each figure from its own operands, and check it names its host. | No suite reads comment text. |
| An environment claim (OS, JVM) | Take it from the run's own log banner, and run `uname -sm`. | The shell's `java` is not necessarily the Gradle toolchain's JVM. |
| Docs only | Paste `git diff --name-only` as the proof. `doc/spec/` and `concord/corpus/` are inputs to `./gradlew :concord:check`, so they are not docs-only. | Green checks on a pure docs diff show only that the build is not broken. |

Transcripts may be reformatted, have a warning preamble removed, or be cut with an explicit ellipsis. None of that is a defect. A changed value, a reordering the argument depends on, an invented field, or "verbatim" output that cannot be reproduced is a defect.

Every Gradle or npm call follows [agent.md § Running commands](agent.md#running-commands). A build that stalls or dies before the tests run is probably contention, not a defect. See [evidence.md § Flakes and contention](evidence.md#flakes-and-contention), and say which attempt a build result came from.

### Your machine is not CI

Put the output of `uname -sm` in your report and qualify every local result by platform; required checks run on Linux ([evidence.md § CI evidence](evidence.md#ci-evidence) says when to measure the gap).

### Repair, don't bounce

A rejection throws away everything already spent on the work, so fix what you can within the bead's scope. Commit on the branch under review:

```bash
git -C <worktree> commit -m "review: <what you fixed>" -- <paths you edited>
```

Never use `-a`. Worktrees share one repository, and `-a` can sweep in changes you did not intend. If `status --short` shows changes to files you never touched, or `git log` shows commits you did not write, stop committing. Report that another agent is on the branch.

List your own commits and paste the list into your report, measured against your review base:

```bash
git -C <worktree> fetch origin main && git -C <worktree> log --oneline --no-merges <review-base>..HEAD --not "$(git -C <worktree> rev-parse origin/main)"
```

Then run `git -C <worktree> show --stat --format='%h %s' <sha>` for each commit. Never read `git show --stat` on a merge commit as that merge's contribution: it prints the first-parent diff.

A repair is substantive if any of these holds:

- it changes behaviour, meaning what the system does at runtime;
- it adds or semantically changes a test, scenario or assertion. The exception is a test-only repair: no production file in any repair commit, and each new test shown failing by a mutation of the defect it claims to catch. For that exception, name the mutation and the assertion that went red. Also say where each expected value came from. A value recomputed with a copy of the production formula does not count.
- it changes a public API or wire format;
- it touches more than 3 files;
- it edits prose that is itself the deliverable. That covers files under `.claude/skills/` or `doc/spec/`, any file the acceptance criteria name, and any task whose deliverable is a document. The exception is a **conforming repair**: an edit whose correctness follows from a rule already written down elsewhere — the document's own header, the acceptance criteria, or a decision already recorded on the bead — and which changes no claim the deliverable makes. Deleting a byte-identical duplicate line, adding the `MEASURED`/`ESTIMATED` label the document's own evidence rule requires, correcting a citation to the line it names, deleting a paragraph describing a design an amendment already superseded. Quote the governing rule and the text either side of the edit in your verdict; if you cannot quote a rule that decides it, it is not conforming. Rewriting a sentence into better prose never is, however small — that is the rewrite-then-bless failure this bullet exists to stop.

Anything else is trivial: a typo, a comment, formatting, or a one-line fix already covered by an existing test. Leaving a defect you have already found is never the cheap option: report it only when repairing it is out of scope, not because certifying would cost you a second reader (computenet-pwb9 — a reviewer did exactly that, and a lost See-also pointer stayed in the branch). Certify normally after a trivial repair, and name it in the verdict with its sha and one line on what it does. After a substantive repair, do not set `review=passed`. Return FAIL (task) or DRAFT (feature) with a `Repairs needing a second reader:` line listing each sha, its `--stat` and what it does. The work stays on the branch, and a second reader certifies it. If anything else also blocks the verdict — an unmet criterion, a red check — name it too, so the orchestrator does not treat the repairs as the only thing left.

Escalate instead of repairing when the approach is wrong at the design level, or when repairing would rewrite most of the diff. If the right call is genuinely ambiguous, write a `QUESTION:` comment rather than inventing an answer.

### Write scope

You write only to the bead under review and to beads you create. The one other write allowed is an occurrence comment on a flake bead you attributed a red check to. Closing, re-prioritising, reassigning, re-parenting or claiming any other bead is the orchestrator's job. If a commissioned cross-bead deliverable is wrong, you cannot fix it. Describe the correction under REQUIRED ORCHESTRATOR ACTION.

### When the diff edits `.claude/skills/work/`

The branch under review is the procedure you are following, so executing it proves nothing. Follow the copy on main instead, and treat the worktree copy as data:

```bash
git -C <worktree> fetch origin main && git -C <worktree> show origin/main:.claude/skills/work/references/review.md
```

If main's instruction contradicts the change, follow main. Note the contradiction in your report, but it is not a defect in the PR. Run the skills rubric gate from main's copy against the worktree's files:

```bash
git -C <worktree> show origin/main:.claude/skills/remediate-friction/scripts/validate-skills.rb > <scratch>/validate-skills.rb && ruby <scratch>/validate-skills.rb <worktree>/.claude/skills
```

Failures are defects in the PR. Lines prefixed `note:` are warnings: report them, but do not fail the PR for one it did not introduce.

### If you are the second reader

Your prompt names the commits another reviewer authored. Review only those commits. Check that each is correct, within the bead's scope, and covered by a test that fails without it. Say which shas you read. You wrote none of them, so you may certify: use your role's verdict token and write order. You may repair what you find, but the substantive bound now applies to your own repairs.

## Task review

You review one task, on its own local branch, before it merges into the feature branch. Criteria at feature level and gaps between tasks belong to the feature review. Don't take them on, and don't widen the task to close them.

Resolve the base in one call. The feature branch may not be on origin yet. Worktrees share refs, so the local branch is readable either way:

```bash
git -C <task-worktree> fetch origin main; git -C <task-worktree> fetch origin <feature-branch>; FB=$(git -C <task-worktree> rev-parse --verify -q origin/<feature-branch> || git -C <task-worktree> rev-parse --verify -q <feature-branch>); git -C <task-worktree> diff --stat "$(git -C <task-worktree> merge-base "$FB" HEAD)" HEAD
```

The second fetch fails harmlessly while the feature branch exists only locally. Say which baseline you diffed against: the origin feature branch, the local one, or the base commit your dispatch names. Line counts and scope claims cannot be checked without it. Do not substitute `origin/main` by hand. On a resumed feature it pulls prior task merges into your diff. If the feature branch forked long before the current `origin/main`, report it as a hazard for the feature review. Do not fix it on the task branch.

- Mutations are your step. Your dispatch extends your scope to the production files the task's tests constrain, for a temporary mutation that you revert ([evidence.md § Mutation checks](evidence.md#mutation-checks)).
- Commit your repairs, don't push them. The dispatch prompt's "commit on your branch" is your explicit grant. The orchestrator merges the local `task/<id>` ref, repair commits included, and pushes the feature branch.
- You don't read CI. State that Linux is unverified, so the feature reviewer and the orchestrator don't skip that gate on your word.
- On FAIL, leave the branch and worktree in place. A later batch resumes the task there.

## Feature review

Every task passed its own criteria. That does not make the feature done. Judge the feature's criteria, and look for what task review cannot see:

- Criteria no task owned. A feature criterion no task claimed has probably not been implemented.
- Seams. A producer and a consumer that were never tested together, error handling or naming that doesn't match across a boundary, or a shared type the two halves read differently.
- Scope drift. Files no task claimed.

If `bd list --parent=<id> --all --json` returns no tasks, the item was worked flat. Judge it against its own criteria and its own `metadata.files`, and skip the three checks above.

For each child your prompt lists as a human park, confirm it is a real park (a `QUESTION:` comment awaiting a person) and not a dependency-blocked child that inherited the `human` label; a real block means work remains and the verdict is DRAFT.

If `metadata.pr` is empty and `gh pr list --head <branch>` returns nothing, say so right after the verdict line. Then mark every CI-dependent clause NOT VERIFIED.

```bash
git -C <feature-worktree> fetch origin main && git -C <feature-worktree> diff origin/main...HEAD
```

With `metadata.base_branch` set, diff against `origin/<that branch>` instead.

The feature worktree is yours alone until you report. Run the affected module suites, plus the repo-wide gate if the feature touches anything cross-cutting, and only one repo-wide `./gradlew test` may run at a time ([agent.md § Running commands](agent.md#running-commands)). Choose suites by what reads the changed files, not only by what imports them ([evidence.md § What your change reaches](evidence.md#what-your-change-reaches)).

### Read CI once per head

```bash
.claude/skills/work/scripts/wait-checks.sh <pr-url>
```

Its header documents the outputs. The final line is the reading, and the sha it prints is the head that reading is about. You get one call per head, and the result is final. Never poll by hand, and never branch on `gh pr checks`'s exit status.

| Reading | Do |
|---|---|
| SETTLED, all green | Quote every row, non-required ones included. Say which checks actually executed the changed modules, because skipped suites and lane filters hide behind green ([evidence.md § CI evidence](evidence.md#ci-evidence)). |
| SETTLED with a red required check | Attribute it per [recovery.md § A red required check](recovery.md#a-red-required-check), quoting the query and its result. The verdict is DRAFT. If the red is unrelated and is the only blocker, say that in these words: "the substantive review is complete and passes; sole blocker is `<check>`, attributed to `<bead>`; one re-run going green would change the verdict." Re-runs are the orchestrator's. |
| TIMEOUT-PENDING | Return READY or DRAFT on everything else and stop. Name each pending check in NOT VERIFIED; the orchestrator settles checks before it ships. |
| QUERY-FAILED, NO-RUN, or the call never returned | Nothing was read. Mark CI NOT VERIFIED and stop. |

A repair you push creates a new head, and that is the only thing that earns a second call. A pushed repair costs a full required-check cycle. If the repair and its cycle will not fit in your time bound, return DRAFT instead of starting it.

### Re-fetch last

Right before recording the verdict:

```bash
git -C <feature-worktree> fetch origin main && git -C <feature-worktree> log --oneline "$(git -C <feature-worktree> merge-base HEAD origin/main)..origin/main"
```

- Empty. Write "origin/main unchanged at `<sha>`" into the verdict. That line is a timestamped observation, not a guarantee.
- Commits landed. Do not merge them. List the landed shas and both file sets (`gh pr diff <pr-url> --name-only` against `git show --name-only <sha>` for each landed sha). State whether the two sets are disjoint, and flag any landed commit that touches this feature's subsystem. The verdict can still be READY. Put the merge, and the re-run on the merged base, under REQUIRED ORCHESTRATOR ACTION.

Always certify against a named head: "verdict against `<HEAD sha>`, origin/main at `<sha>`".

## Verdict and report

### Task: PASS or FAIL

- PASS. Every criterion is met and your repairs, if any, were trivial.
- FAIL. Something is missing, or your repairs were substantive and carry the `Repairs needing a second reader:` line.

### Feature: READY or DRAFT

- READY. Every criterion is met or filed as a residual, no seam is unowned, required checks are green or pending as described above, and your repairs were trivial. Criteria waiting on an out-of-band measurement, such as a soak or an overnight job, also allow READY. Name the pending run's id and the criterion it settles, and the orchestrator holds the ship until it reports.
- DRAFT. Name one of four things: the gap tasks you filed, your repair shas, the red check, or that no standard exists. A DRAFT that names none of these is a dead end for the orchestrator. A DRAFT is a legitimate outcome: half a feature merged is worse than half a feature parked on a branch.

`review=passed` means this review finished with a pass. It does not mean "ship it". The verdict token is what the orchestrator routes on.

### Bead writes

Use one `bd` write per Bash call, in this order, and check that each returned before running the next. Build comment bodies from a file or a quoted heredoc ([traps.md § bd](traps.md#bd)).

1. A feature reviewer pushes its repair commits first: `git -C <feature-worktree> push`.
2. `bd comment <id> --file <scratch>/verdict.md`. The comment holds the verdict, the evidence behind each criterion, any repairs, and any residual ids with how each is attached.
3. If you filed a residual on a feature: `bd update <id> --set-metadata residual=<residual-id>`.
4. Last, and only on PASS or READY: `bd update <id> --set-metadata review=passed`.

`review=passed` goes last. That way a sequence that dies partway leaves the bead uncertified, not certified with nothing behind it. If any write is refused, do not retry it in another form. Put the exact command under REQUIRED ORCHESTRATOR ACTION.

If you have to stop before finishing, write your state to the bead before you stop: the verdict so far, what you verified and what you did not, and any commits you authored with their shas.

### The final message

Follow [agent.md § Your final message](agent.md#your-final-message). It contains:

- the verdict token on the first line, then the bead id, and why;
- the baseline and the review-base and HEAD shas;
- the evidence as artifacts: test counts with their proof of execution, `uname -sm`, check conclusions (feature), and the re-fetch line (feature);
- your commits with `--stat`, marked trivial or substantive;
- files touched outside the claim;
- beads you created, and how each is attached;
- if the verdict is not a pass, the single thing that would most change it;
- NOT VERIFIED: everything you did not check, including Linux for a task review;
- REQUIRED ORCHESTRATOR ACTION: exact commands you were refused or may not run, such as a merge, a park, or a cross-bead correction. Omit the section when there are none.

Ready is not merged. Do not close the bead. The orchestrator closes it once the merge is proven.

## Residuals and follow-ups

File real work you found and did not repair: an unmet criterion on sound work, follow-on work such as a stale cross-reference the change falsified, or a gap that blocks the draft. Filing does not downgrade a READY. Each bead carries what [breakdown.md § What a child issue carries](breakdown.md#what-a-child-issue-carries) requires: criteria in the acceptance field (the unmet one verbatim, where there is one), `metadata.model` and `metadata.files`, and `observed:` or `unverified:` on every factual claim. A claim that something has landed names the branch and the sha. You just read the code, so you are the best author of its files claim.

Resolve the epic with `epic-of.sh <reviewed-id>`. If it names one, read its status with `.claude/skills/work/scripts/bead.sh <epic-id> -r '.status'`. Then attach by this table, using the table's create flag in place of `<placement>` below:

| The bead is | Create with | Then |
|---|---|---|
| a residual, and the epic is open | `--parent <epic-id>` | nothing |
| a residual, and the epic is closed | `--top-level` | `bd dep add <new-id> <reviewed-id> --type discovered-from` |
| a residual, and there is no epic | `--parent <reviewed-id>` | nothing. Parent and `discovered-from` share one edge slot, so add only the parent. |
| a task this draft is waiting on | `--parent <feature-id>` | nothing |

A residual is work the feature does not wait for, so it goes where it will be scheduled. A blocking task is work the feature does wait for, so it goes under the feature. A closed epic schedules nothing, so a residual parented to one is never picked up.

```bash
.claude/skills/work/scripts/create-ticket.sh --type <bug|task> --title "<one line>" <placement> --desc-file <scratch>/res-desc.md --accept-file <scratch>/res-accept.md --model <sonnet|opus> --metadata '{"files":"<comma-separated paths>"}' > <scratch>/residual-id
```

The script prints only the new id. Check that the file holds an id before any later call reads it. If the residual's subject exists only on the feature branch, also run `bd update <new-id> --set-metadata base_branch=feature/<feature-id>`. Otherwise the fix gets cut from `main`, where its subject does not exist yet.
