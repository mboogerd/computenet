# Feature review

**Read this file with the Read tool, not `cat`** — through Bash it truncates
to a ~2KB preview with no marker, and the authorship bound (§5) and
verdict-token rule (§8) are past the cut (computenet-bgdb).

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

**A `:bench` probe needs two arguments the bench reference never names**
(`-PbenchOnly=true` to select `@Tag("bench")`, and `-Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)`
for the `requiredHarnessSha` guard); every first attempt without them fails
3/3 and costs a Gradle cycle (computenet-db3k). The form that works:

```bash
./gradlew :bench:test -PbenchOnly=true --rerun --no-build-cache \
  --tests '*<ProbeTest>*' -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
```

**A cited test class may not be findable by filename**: `--tests` takes the
class name, and `ThroughputReportRenderTest` lives inside
`ThroughputReportTest.kt` — `grep -rn "class <Name>" --include='*.kt' bench/src`
finds it where `git ls-files | grep <Name>` does not (computenet-db3k).

**Any command, flag or entry point the diff DOCUMENTS is a claim, and you
check a claim by running it.** Extract it with `sed`/`awk` rather than
retyping — a check that only works when retyped charitably is not a working
check — substitute only the placeholders, run it, and compare what happens
against what the text says happens. This applies to prose, KDoc, bead-
prescribed methods and shell snippets alike.

This is not busywork. In one session it found three independent
shipping-blocking defects, none of them findable by reading, and all three
failed **silently and plausibly in the direction of a false green**. Those are
the three signatures to hunt:

- **A check that prints nothing on both branches.** PR #80: a new post-script
  check produced no output in the healthy case *and* the orphaned case,
  because a brand-new branch makes `git fetch origin feature/x` exit 128 and
  the `&&` chain short-circuits. It reads fine.
- **A stated safety property that does not hold.** PR #82: `git worktree add
  --force` was offered as an escape hatch with a safety claim that is false —
  run literally, the second worktree's index does not follow the first's
  commit, so a `git commit -am` committed stale content and *silently
  reverted* the fix, pushing clean. The detection advice keyed on a push
  rejection that can never happen.
- **A flag that is silently ignored.** PR #81: a KDoc's documented long-run
  entry point `-Dwire.burst.iterations=N` did nothing, because Gradle's `Test`
  task does not inherit the daemon's system properties —
  `./gradlew :wire:test -Dwire.burst.iterations=2` still ran 10 iterations.

Two of the three were in the work skill itself.

**Environment claims are claims too.** When the diff's substance is a recorded
measurement, "measured on macOS / JDK 26" is what makes the number comparable
to a sibling measurement, and it is checked against the *machine*, not read
from the prose:

```bash
uname -sm
echo "${JAVA_HOME:-<unset>}"
```

**A JVM claim must come from the RUN'S OWN banner, not from the environment.**
`/usr/libexec/java_home -V` used to be listed here and is the wrong source on
every machine in this fleet — on some hosts it returns *"Unable to locate a
Java Runtime"* (Gradle-provisioned and Homebrew JDKs don't register where it
looks), on others it lists nine JVMs; either answer says nothing about which
JVM the run used, and a reviewer on a listing host must not read an
implementer's `java_home` citation as fabricated (computenet-799w). Worse, the environment and the run
can disagree: bare `java` on this host is Homebrew JDK 26, **not** the Gradle
toolchain's Adoptium 21 that a Gradle-driven measurement actually uses. Two
BEN1 findings entries shipped with the wrong recorded JVM by exactly that
route (computenet-u90r). So take the identity from the artifact the run itself
produced — the JMH/Gradle banner in the teed log, or `java -version` from the
same launcher the run used — and check the diff's claim against *that*:

```bash
grep -m1 -iE 'vm version|java version' "<the run's own log>"   # JMH: '^# VM version'
```

`jdk` was in that alternation until computenet-k5a9. JMH 1.37 emits a
`sun.misc.Unsafe` deprecation warning ABOVE its own banner, and that warning
contains `jdk`, so `-m1` stopped there and returned a line with no JVM version
in it — output that looks like the check ran and found nothing. Two agents hit
it in one session, and the run it exists to catch was real: a derivation made
under JBR 25.0.2 instead of the module's declared JDK 21, caught only by
reading the banner directly, which moved the published floor from 0.593 to
1.044.

On computenet-dqy.46 every measured number reproduced exactly and the only
false statement was the environment claim — two same-size figures from
different runtimes. **Run those three commands; do not carry their answers
in this file.** They are shell- and session-dependent and two same-day
readings of the same host have already disagreed outright. Check the diff's
environment claim against a reading *you* just took, quote it, and treat any
environment fact written down here or in a bead as expired.

**Validating a workflow change** has one command that works on this host —
there is no `actionlint` here and `pip3 install pyyaml` is PEP-668 blocked, so
do not hand-roll it per review:

```bash
ruby .claude/skills/work/scripts/lint-workflow.rb .github/workflows/<file>.yml
```

It parses the YAML and runs every `run:` block through `bash -n`, exiting
non-zero on either failure. It does **not** know the Actions schema, so a
misspelled key or a bad `uses:` ref still passes — say so rather than
implying the workflow is fully validated. (Host quirk that costs a retry:
`YAML.unsafe_load_file` does not exist in this Ruby's Psych;
`YAML.load(File.read(...))` is the form that works.)

## Contents

1. Establish the standard — criteria and tasks from the bead
2. Read the actual diff — against freshly fetched origin/main
3. Prove the feature's tests actually ran — not replayed from cache
4. Your run is on macOS; the required checks are not
5. Repair by default — up to a bound (authorship limit + `review:` commits)
6. Re-fetch immediately before you certify
7. Decide — verdict shapes and what each requires
8. Report

## 1. Establish the standard

`$SCRATCH` throughout this file is **your own** agent-unique dir, created
once — `SCRATCH=$(mktemp -d "<harness scratchpad>/<feature-id>-review.XXXXXX")`
— never the shared harness scratchpad directly: that dir holds other agents'
files under exactly the names you would pick (~40 stale logs including
`exchange.log` and `wire.log`, computenet-84z6), and a reviewer that reads
one quotes the implementer's build as its own independent evidence.

**First: is there a PR?** Sections 4 and 6 assume one throughout — §4 reads
`gh pr checks`, §6 re-fetches and reasons about the PR head — and on the
direct-child route the orchestrator can dispatch you before opening it
(direct-child.md opens it on the implementer's first commit, and that step has
been skipped). If `metadata.pr` is empty and `gh pr list --head <branch>`
returns nothing:

**Say so in your first line, then proceed with an explicit NOT VERIFIED on
every CI-dependent clause** — do not silently substitute your local macOS run
for the six required checks, and do not invent a verdict for evidence that does
not exist yet. Your report hands the gate back to the orchestrator, which must
open the PR and close it before shipping. Recording the gap prominently is the
requirement, not a courtesy (computenet-a4cj).

```bash
bd show <feature-id> --json > "$SCRATCH/<id>.json"   # acceptance criteria, description
bd list --parent=<feature-id> --all --json  # the tasks (--all: they are closed by now)
bd comments <feature-id> --json > "$SCRATCH/comments.json"   # then read the file
```

**Read the comments — that third command is not optional.** `bd show --json`
carries only `comment_count`, never the bodies, so a review that skips this
has not seen the thread. On a long-lived item the thread is where the
decisive context lives: prior sessions' handoffs, a *withdrawn*
certification, a corrected premise, a human's answer to a parked question. A
reviewer unaware of a withdrawn certification reviews the wrong thing.
The redirect to a file is likewise not optional:
the JSON overruns the tool-result limit
on exactly the beads that need it, and a truncated array reads as fewer
comments rather than as an error ([bd-traps.md](bd-traps.md)).

**`bd list --parent` coming back empty is a shape, not a dead end.** This
file is written for a feature with child tasks, but an epic can be broken
down **flat** — bug/task/chore items worked directly, each with its own
worktree, branch and PR and no children of their own (SKILL.md 5f route 4 explicitly
permits working unparented bugs and chores, and route 3 a single cross-epic
item; computenet-9xj records that a task parented straight to an epic has no
feature to merge into). When the
list is empty, there is simply no task layer to reconcile. Judge the item
against **its own** acceptance criteria and diff, and skip exactly the three
§2 bullets that presuppose tasks — "Criteria with no owner", "Seams", and the
"no task claimed" half of "Scope drift" (judge scope against the item's own
`metadata.files` instead). Everything else in §2 through §8 applies
unchanged, reading "feature" as "the item". Two sentences written for the
common shape do not hold here either, and neither is a defect to report: §5's
"you reach this point only once every task has merged" (there were none) and
this file's opening "every task here is closed".

Read the parent epic too, and any spec sections the feature cites — those
are the authority (AGENTS.md), above the feature's own prose.

**Resolving the parent is a script, not a field read.** `bd show --json`
**omits `parent` entirely when it is unset** — a parented bead carries the
key, an unparented one has no such key at all — so `.[0].parent` reads `null`
both for an item that genuinely has none *and* for an id `bd` could not
resolve, and the field alone cannot tell you which. `bd list --parent` is no
help either: it is not transitive, so a membership scan answers "unparented"
for exactly the grandchildren that do have one (computenet-wpvy.32). Use the
walk, which follows `.parent` when set and the dotted-id prefix otherwise:

```bash
.claude/skills/work/scripts/epic-of.sh <feature-id>
```

`(unparented)` is a **positive answer**, not a read failure — a flat epic
breakdown and 5f's route-4 items are legitimately parentless, and the file
already tells you how to review one. Only a non-zero exit (`(no such id: …)`
or `(cycle? …)`) means unresolved.

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

**Read the key by its real name first.** The write flag is `--acceptance`;
the JSON read key is **`acceptance_criteria`**. `jq '.[0].acceptance'`
answers `null` on a bead that *has* criteria, which sends a reviewer down the
ladder below for no reason (computenet-2rix, [bd-traps.md](bd-traps.md)). And
read it from a file — `bd show` on a feature inlines its parent epic's whole
description and has overrun the tool-result limit at 55KB, and **a truncated
bead read is a truncated acceptance list** with nothing in the output saying
so (computenet-h0dj, computenet-rram).

**`acceptance_criteria` may be empty or absent altogether** — on a bead filed
mid-session by another agent it usually is, because nothing broke it down, and
three reviewers hit it in one session (computenet-d7tk, computenet-qxg5).
`bd show --json` cannot even tell the two apart: `.[0].acceptance_criteria`
reads `null` for an empty field and the key is simply missing for an absent
one, which is easy to misread as a `bd` failure rather than as the bead's real
shape. It is neither a dead end nor automatically a defect. Do not assume the
field is there, and do not invent a standard silently. Fall back **in this
order**, and **say in your report which text you treated as the criteria**:

1. the **structured description read together with the parent epic** — the
   description's "Implement"/"Test"/non-goals clauses, bounded by what the
   epic says the feature is for;
2. the **comment thread**, where a long-lived item's real standard often lives
   (a human's answer, a clarification, a decision-ready summary). It needs
   `bd comments <id> --json > "$SCRATCH/c.json"` and then reading the file,
   since `bd show` carries only `comment_count`;
3. **nothing locatable anywhere → park it** ([ask-human.md](ask-human.md)),
   not pass. A bead whose standard exists nowhere cannot be certified against,
   and a pass here is a reviewer certifying its own invention.

Whichever step answered, **write what you derived back onto the bead**
(`bd update <id> --acceptance=…`) *before* you judge, and quote it in your
verdict — so the bar survives the session, the next reader judges the same
item you did, and the orchestrator can disagree with it. **When you file a
bead yourself** — §7's residuals, a task for work you did not repair — put its
criteria in the field, with that same flag, rather than only in the
description: a bead filed mid-session should match one filed by a breakdown,
or the next reviewer inherits this problem.

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
- **Caveats that live only in the paperwork** — any limit the change relies on
  ("one workload", "single trial", "not itself measured") must be in the
  changed file, next to the claim it qualifies, not only in the bead comment
  or the PR body. Check the shipped text specifically: an honest PR body is
  what conceals the gap, because body-plus-diff reads as a consistent story
  (computenet-k9d.2, then k9d.7 immediately after — the only two instances
  seen; both times the claim sat in prose — a docstring, a skill file —
  though k9d.2's change was code, and both were found only because a reader
  was told to look). The body is read once, at merge; the file is read by
  every agent afterwards. Repair it under §5; it is not a wording preference. It is
  [orchestrator-authorship.md](orchestrator-authorship.md)'s rule displaced by
  one artifact — the caveat exists and the author is honest, it is just where
  nobody who needs it will look.

## 3. Prove the feature's tests actually ran

**Before any of it: a check whose failure mode is SILENCE is not evidence
until you have shown it prints something on the branch where it should fail.**
Two measured instances, both from reviewers on 2026-08-19 — a per-line read of
a multi-line Kotlin `toString` that under-reported a dump by an order of
magnitude in the reassuring direction, and an unquoted `--include=*.kt` glob
that zsh ate so the grep never ran and its silence read as "no dependents".
Both in
[references/gradle-evidence.md](gradle-evidence.md#measurements-whose-failure-mode-is-a-pass)
(computenet-rf0a). Quote your globs; measure dumps per block, not per line;
treat an implausibly reassuring number as a measurement bug.

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

**A diff whose changed content is comments, KDoc figures or recorded
measurements inside a COMPILED file is a fourth shape**: no suite exercises
comment text, so green checks evidence compilation and nothing about the
numbers. The review's evidence is re-derivation — recompute every stated
ratio from its own operands, check each figure names its host/date, and
confirm a quoted number is the one measured, not a blend. The failure mode: a
ratio printed beside a number that is not its denominator reads as checked
when it is not (computenet-z4h8: "59.6s / 398s ≈ 3–5%", true denominator
~1592 fork-seconds). Say in the verdict that re-derivation, not the checks,
is the evidence (computenet-61qu).

The module suites the tasks ran individually may not cover their
interaction. Run the affected module tests, and the repo-wide gate if the
feature touched anything cross-cutting — then prove the run happened.

**"Affected module" means the modules whose test INPUTS the diff changes, not
the module the diff sits in.** Those are usually the same and once were not, at
a cost of five green local gates in a row (computenet-uxr8). Feature
computenet-j2x.4 added four scenario files to `concord/corpus/24-data-cells/`,
taking it from 29 files to 33. Two implementers, two task reviewers and the
feature reviewer all ran `:concord:test` and `:concord:check` with
`--rerun-tasks`, all green with fresh JUnit timestamps, and none was negligent:
no task touched `oracle/`, `:concord:check` does not run `:oracle:test`, and
there is no import, no Gradle dependency and no path overlap between the changed
files and the test that broke. `:oracle`'s `CorpusCrossCheckTest` enumerates
that directory **at test time** and asserts a non-vacuity count, so
`build-test-fast` went red on `expected:<29> but was:<33>` after four task
merges. Nothing static can surface a runtime directory read.

Concretely: **a diff that ADDS or DELETES a file under `concord/corpus/` runs
`./gradlew :oracle:test --rerun` as well.** That directory exists to be
enumerated by harnesses in more than one module, so this is by construction, not
a special case. More generally, before you pick the suites, ask what READS the
files this diff adds or removes — not what imports them.

**[gradle-evidence.md](gradle-evidence.md) is that proof standard**: the
task-count line, the per-task state line read as an absence, and the JUnit
XML counts + timestamp via `.claude/skills/work/scripts/junit-count.py`, plus the `--rerun` and
`--no-build-cache` semantics.
A cargo-touching task has a different standard — gradle-evidence.md
§ "Cargo is not Gradle".
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
# several modules: repeat --rerun after EACH task (or use --rerun-tasks) —
# it binds only to the task immediately before it, and the combined
# task-count line reads plausibly while stale modules replay; prove each
# module by its own fresh JUnit timestamp (computenet-a61u)
./gradlew :a:test --rerun :b:test --rerun --no-build-cache
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
 Per suite you run, consume those signals and
**quote them in your verdict** — an unquantified "suites green", yours or
the implementers', is not a verification record, and nobody re-runs it after
you: your verdict *is* the evidence the merge rests on. The mutation-check
mechanics are in [review-task.md](review-task.md) §2; use them here
unchanged when a seam is what you're testing.

**When the diff's only executable artifact is in NO Gradle source set** — a
script under `scripts/`, a hook, a harness, a `.claude/skills/**/*.sh` — there
is no suite to cache-prove and the recipe above has nothing to bite on
(computenet-wl77). Two things substitute, and both are required:

- **Execute the artifact directly** and quote what it printed. That is the
  run; there is no build to stand in for it.
- **Show its arms are load-bearing by perturbing what each one claims to
  measure.** A script that reports PASS/SKIP/FAIL is only evidence if you can
  make each verdict appear on demand — feed it the state that should produce
  each, and quote the three outputs. That is this shape's mutation check, and
  without it "I ran it and it printed OK" proves the script runs, not that it
  measures anything.

  **Look for a companion `*.test.sh` before you build fixtures**, because
  under `.claude/skills/work/scripts/` and `scripts/flake-loop/` nearly every
  script has one, and each is already a per-arm harness: it stubs `bd` (or
  `git`) on `PATH`, drives one fixture per verdict and prints `N passed, M
  failed`. Run it, quote that line, and confirm it covers the arm the diff
  changed — adding the missing case is a cheaper repair than inventing the
  whole rig. Where no companion exists, build the fixtures yourself in
  `$SCRATCH` and say in the verdict that you did.

**And say plainly what the required checks did and did not evidence.** For
such a diff they evidence exactly one thing — *the branch does not break the
build* — because no required check executes the artifact at all. A verdict
that rests on green checks here is resting on a fact about other code. Say so
in as many words rather than letting six green rows read as verification.

## 4. Your run is on macOS; the required checks are not

Run `uname -sm` and put its output in your report. (For a diff proven
docs-only in §3 there is no platform-dependent behaviour to measure: skip to
the `gh pr checks` read below, and report its conclusions with §3's limit
attached.) This repo is developed on darwin; all **six** required checks
(`build-test-fast`, `build-test-serial`, `concord-full`, `ui-test`,
`agora-ui-test`, `kernel-test`) run on `ubuntu-latest`. For most
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
  has been present on some machines; `eclipse-temurin:21` costs a ~10-minute
  pull — and a present `docker` **binary** does not mean a running daemon:
  `docker info` decides) and quote that result too; where no daemon runs, the
  branch's own CI run two bullets below is the documented Linux substitute,
  not an improvisation (computenet-799w).
- **A red required check is attributed with
  [red-check-attribution.md](red-check-attribution.md), never by the query
  that occurs to you.** Its artifact 3 searches by the failing TEST CLASS; a
  reviewer that searched by module attributed a first-sighting `:wire` class
  to a different class's characterised flake and called a `:inspect` class
  "no prior bead" when one existed (computenet-9cji) — both errors in the
  direction that dismisses a red. Quote the artifact-3 query and its result
  in the verdict.
- **Before you set `review=passed`, read the branch's own CI run** — it is
  the only Linux evidence that exists:

  ```bash
  gh pr checks <pr-url>
  ```

  Quote each required check's name and conclusion in your verdict — and quote
  them for the **PR's current head**. If §6's re-fetch makes you merge
  `origin/main`, that merge moves the head and this reading goes stale; §6
  says how to re-take it. A green check on a diff that touches no compiled
  input is evidence of nothing (it too can be cache and skip), so say which
  checks actually exercised the changed modules — an `assumeTrue`-guarded
  suite reports `SKIPPED` under a green check, and only the job log says so;
  merge-task.md §4 carries the two greps that find it in `gh run view
  <run-id> --log` (computenet-hacm). A check still `pending` is not a pass —
  wait for it or certify draft, and wait with the script, never a hand-rolled
  loop or anything gated on `gh pr checks`' exit status (it exits 8 while
  pending, and its rows can be legitimately absent for the first minute —
  computenet-luhx, computenet-1zhu, computenet-15it, computenet-elm3):

  ```bash
  .claude/skills/work/scripts/wait-checks.sh <pr-url>
  # SETTLED (0) / TIMEOUT-PENDING (4) / QUERY-FAILED (3 — nothing was checked)
  ```

  **But do not wait for pending checks — you are the wrong agent for it.**
  One invocation is your budget. If it comes back `TIMEOUT-PENDING`, say so
  in your verdict — *"verdict conditional on `build-test-fast`, pending at
  the time of writing"* — and STOP. The orchestrator settles checks before
  shipping anyway (SKILL.md 5e), so your wait is duplicated work even when it
  succeeds, and it is the single situation that produced all six recurrences
  of the stalled-turn defect (computenet-kp0y): a reviewer's entire result was
  *"Still waiting — round 19/28. I'll hold here until the monitor reports
  settlement"* — 515s and 31 tool calls for no deliverable, from an agent whose
  prompt carried the prohibition verbatim and which, once recovered, diagnosed
  the trap itself. You have **no inbound wake-up**; your turn ending IS your
  completion. A conditional verdict terminates, and waiting cannot.

  You could not win the wait in any case: the ~9m20s window is smaller than
  `build-test-fast` and cannot be widened inside the 600000 ms cap
  (computenet-hil5), so a cold start needs two invocations. Exhaustion prints
  each pending check's age plus `ORDINARY` or `STUCK`; only `STUCK` is a
  finding worth reporting.

  A **red** required check is not yours to wave
  through: report it and leave the verdict draft.

## 5. Repair by default — up to a bound

A rejection forfeits everything already spent on the feature, so fix what you
can rather than sending it back.

**Budget the repair, because it obliges a CI cycle.** A repair moves the head,
so §6's re-read is no longer optional for you: you owe one full required-check
cycle — **9–12 minutes on this repo**, governed by `build-test-fast`, which is
the long pole; the other five settle in 15s–5m26s — plus the poll to watch it,
on top of the repair itself. (Measured 2026-08-19 across four runs: 8m58s,
12m1s, 8m52s, ~9m. The figure here read "2–4 minutes" until computenet-678u
measured it; a reviewer budgeting a merge-and-re-check cycle against its own
deadline was working from a number 2–4x too small, which is what turns "I can
still certify inside my window" into a review that runs out of time mid-cycle.) **Repair anyway**; this note exists so the cost is
*expected*, not so it is avoided (computenet-elm3). Plan it against the
~45–60 minute bound you were dispatched with, and if the repair plus its cycle
will not fit, that is the moment to hand back rather than after you have spent
the time. The poll idiom is in §6, at the point of use.

Within the feature's stated scope, repair: missed criteria, broken seams,
failing tests, gaps between tasks.

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
while you are still running
(red-check-attribution.md § "Where a fix for a red check gets dispatched" —
its default is to wait for you; the separate worktree is the
cannot-wait variant). It tells you *before* it does, and
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
# CAPTURE THE SHA ONCE and use that literal below. refs/remotes/origin/main is
# SHARED ACROSS WORKTREES of one repository, so it moves when ANOTHER agent
# fetches in a sibling worktree — with no fetch of your own. One reviewer
# watched it move between two of its own commands, which briefly made its own
# re-fetch line look self-contradictory (computenet-0rmu). Re-resolving the ref
# between commands measures against two different bases.
BASE=$(git -C <feature-worktree> rev-parse origin/main)
echo "$BASE"    # quote this next to the list below

# your own commits: after the review base, and not already on main
git -C <feature-worktree> log --oneline --no-merges <review-base-sha>..HEAD --not "$BASE"
for c in $(git -C <feature-worktree> log --format=%H --no-merges <review-base-sha>..HEAD --not "$BASE"); do
  git -C <feature-worktree> show --stat --format='%h %s' "$c"
done
```

Read that list first and confirm every commit on it is one you wrote; the
line counts are only meaningful once it is. **Every flag in that snippet is
load-bearing**, each against a measured false-positive (computenet-rbfa; §6
states the merge-commit rule in full): `git diff --stat <base>...HEAD`
credits you with everything that landed on `main` mid-review; `--no-merges`
alone drops the merge commit but not the commits it brought in — the
`--not origin/main` is what excludes them; and `--not origin/main` against a
stale fetch still over-counts, which is why the fetch sits above the snippet
rather than in §6. All three fail in the safe direction — they over-count
and can never hide your own work — but a single extra file crosses the
bounds below and forces a draft on an untouched branch.

Your repairs are **substantive**, and disqualify you from certifying, if any
of these is true:

- **it touches a behavioural code path** — anything that changes what the
  system *does* at runtime. That is the primary test, and the command below
  decides it rather than your reading of the diff. A repair that only corrects
  **text** — prose, comments, KDoc, a message string nothing asserts on — does
  not fire *this* bullet however long it is. The next two still bind it;
- for a repair that *is* code, more than **~30 changed lines** — insertions +
  deletions, not net (a reviewer self-certified at 41 by reading it as net;
  computenet-e0i5). **This bullet bounds PRODUCTION changes. It does not bound
  a test that meets the test-only exception below** — no mutation-demonstrated
  test suite fits in 30 lines, so on the other reading the exception could
  never be exercised and would be dead letter. One reviewer had to adjudicate
  that mid-review with its certification hanging on the answer, and said so in
  its verdict (computenet-a4h1). Meet the exception in full and the line count
  is not the question; miss any part of it and the repair is substantive
  whatever its size.

  **Count the code half, and count it mechanically.** `--stat`'s
  "X insertions(+), Y deletions(-)" is the *whole* diff, prose included, so it
  cannot answer this bullet on its own; and hand-partitioning the diff into
  halves would put back exactly the judgement call e0i5 removed. Filter to
  non-comment lines in non-prose files and count what survives
  (computenet-hhm4):

  ```bash
  git show <sha> -- . ':(exclude)*.md' ':(exclude)doc/**' \
    | grep -E '^[+-]' | grep -vE '^[+-]{3}' \
    | grep -vE '^[+-][[:space:]]*(\*|//|/\*|#)' | wc -l
  ```

  **When your contribution is a MERGE commit** — you resolved a conflict with
  `main` — `git show <sha>` prints the first-parent diff (§6) and the count
  above is undefined. "What I authored" is then the lines present in neither
  parent; two reviewers independently reinvented the measure
  (computenet-f7gy):

  ```bash
  comm -23 <(git show <sha>:<file> | sort -u) \
           <(cat <(git show <sha>^1:<file>) <(git show <sha>^2:<file>) | sort -u)
  ```

  Empty means the merge is a pure union of its parents and authored nothing
  novel; anything printed is yours and counts. Run it per conflicted file; do
  not `&&`-chain a `diff` variant of it — `diff` exits 1 on any difference
  and the chain silently drops whatever follows (`comm` itself exits 0).

  **Zero is the first bullet's answer too**: nothing executable changed. Two
  reviewers handed the same commit — "28 insertions, 15 deletions, mostly
  wrapped prose" — now get the same number from the same command, so no
  verdict turns on which of them counted, and a reflow cannot inflate it
  (computenet-hhm4, computenet-e0i5 — two beads, opposite readings, one rule);
- more than **three files touched**, whether the repair is code or text. This
  bullet is unconditional, and it is what stops "it's only prose" becoming a
  licence: in this repository prose under `.claude/skills/**` and `doc/spec/**`
  *is* the deliverable — agents execute it — so a reviewer that rewrites a
  reference and then certifies itself is the conflict this list exists to
  remove, whatever the file extension. A file count is also the one bound a
  reflow cannot inflate;
- **when the filter prints nothing, say so and quote the repair diff** in your
  verdict, so the orchestrator can spot-check it. "Text-only" means *cheaply
  checkable by someone else*, never *nobody need read it*: one such repair
  corrected KDoc that contradicted measured exit codes (computenet-h6a),
  another a workflow header naming the wrong surface (computenet-dqy.63).
  Both were right; both were checked before they shipped.
  One case the count does not govern at all: a repaired file that is itself
  named in the item's acceptance criteria is the deliverable, whatever the
  filter prints — set `metadata.second_reader=<shas>` and certify, so
  ship-feature.md §4 routes it (computenet-wbl7);
- any **new or semantically changed test, corpus scenario, or assertion** —
  writing the check that decides the verdict is authoring the verdict.
  **Exception, and it is narrow: a test-only repair** — no production file in
  any of your repair commits — that you have *demonstrated failing*. For each
  test you added, mutate the production code it covers (code you did not
  write), show the test failing, revert the mutation, show it passing, and
  quote the assertion message from the failing run. Name the mutation in the
  verdict, following [mutation-check.md](mutation-check.md). That is the same
  mutation check review-task.md applies to an
  implementer's tests. **Two conditions on the mutation**, because a red run
  proves less than it looks like:
  - **Mutate the defect the test claims to catch**, not whatever is easiest to
    break — and when the test has several assertions, say which one went red
    and show that the assertion carrying the criterion discriminates on its
    own (mutation-check.md step 4; a mutation caught by an earlier assertion
    reads as a pass and is not one).
    Deleting the method under test reddens any test that calls it —
    including one that asserts the wrong thing — so it demonstrates that the
    test *runs*, not that it *constrains*. If the test claims to catch an
    off-by-one, the off-by-one is the mutation.
  - **Say where each expected value came from.** Mutation-sensitivity is not
    independence: a test that recomputes its expected value with a copy of the
    production formula goes red under exactly that mutation and is still blind
    to a bug in the formula — the false green that filed this bead. Expected
    values must be literals or derived some other way, and the verdict says
    which.

  Meet all of it and certify normally; skip any part of it and the repair is
  substantive as before. A fully green feature should not cost a second opus
  review for tests that prove themselves (computenet-7bc9);

  **If you certify READY but want your own work spot-checked, say so in the
  verdict AND set `metadata.second_reader=<what to check>`.** A reviewer that
  argues in its verdict about which rule governs is doing this file's job for
  it, and the argument lands on the orchestrator exactly as it decides whether
  to ship. The routing: a READY verdict carrying that key goes to
  [ship-feature.md](ship-feature.md) §4's second-reader dispatch — the same
  template, which is reusable as-is — **before** `gh pr ready`, not to the
  plain ship sequence. The trigger is what the code IS, not how long it is: a
  142-line mutation-demonstrated test needed a second reader not because it was
  long but because it was the test certifying the feature's central
  proposition, written by the agent that then certified the feature. When one
  was dispatched, it re-ran the reviewer's mutation and three more — three of
  four went red, and the fourth exposed a real bound on what the seam test
  proves, which no line count could have produced. One agent, ~6 minutes
  (computenet-a4h1);
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
  verdict ("re-fetched at <time>, origin/main unchanged at `<sha>`") — and
  understand that line as **expiring the moment you write it**. It is a
  timestamped observation, not a guarantee about the merge; auto-merge can
  land another PR while your report is in flight, which is why the
  orchestrator re-reads rather than trusting it.
- **Any commits** → your evidence is stale, and **the merge is not yours to
  run.** `git merge` is denied to reviewer agents by the permission
  classifier — four instances across two sessions now (computenet-whx4,
  computenet-dtvd) — so prescribing it to you produced a refusal every time
  and an improvised recovery each time. Hand it back instead, as the normal
  path rather than a fallback: state in your verdict which shas landed,
  whether their files are disjoint from this diff (**list both file sets**:
  `gh pr diff <pr-url> --name-only` against `git show --name-only <sha>`), and
  that the merge plus the §3 re-run on the merged base belongs to the
  orchestrator. If one of the landed shas touches the same subsystem as this
  feature, say so — that is the signal for the orchestrator to send the diff
  back to you for a scoped re-read rather than shipping on this verdict.

  SKILL.md 5e carries the other half: the orchestrator does the merge, re-runs
  the affected module suite, and sends back for a re-check when the
  disjointness claim does not hold. A verdict must never ride a merge nobody
  assessed.

  If your harness does *not* refuse the merge, running it yourself is still
  fine — merge, re-run the affected module suite (§3, with fresh task-count
  and JUnit numbers; the old ones no longer describe the code being merged),
  quote the shas, and re-read the diff if one of them touches this
  subsystem. Do not quietly skip the re-check
  and do not report the denial as a blocker on the feature itself. The same
  applies to any other refused command in this file: substitute the
  documented equivalent (e.g. `--rerun` instead of deleting
  `build/test-results`, review-task.md §2) or hand the step back, always
  saying which command was refused. A hand-back rides a **READY** verdict
  (its conditions otherwise met) — the push/re-read-checks ordering below
  travels to the orchestrator along with the merge; only the re-fetch
  evidence above is yours to quote.

**Never read `git show --stat` on a MERGE commit as what that merge
contributed.** For a merge, `git show` prints the **first-parent** diff — which
for a `--no-ff` merge of a sibling's work is that sibling's entire change, and
for your own `origin/main` merge is everything that landed on `main` since you
forked. Either way it reads as if the merge authored all of it, which invites
attributing a sibling PR's work to this branch (computenet-rbfa; SKILL.md step
5c reads the same first-parent trap from the other side, where a post-merge
`git diff --stat HEAD~1 HEAD` silently never fires). When you enumerate what
**this branch** changed — for §5's line count, for your report, for anything —
use three dots:

```bash
git -C <worktree> diff --name-only origin/main...HEAD    # what THIS branch changed
git -C <worktree> diff --stat      origin/main...HEAD
```

`git show --name-only <sha>` on a **non-merge** commit is fine and is what §6's
relevance test uses; the trap is specifically merges. The shas §6 feeds it are
safe by construction: they come from `merge-base..origin/main`, and the `main`
ruleset requires linear history and the repo squash-merges, so nothing has
landed there as a merge commit since the ruleset went active (last merge commit
on `main`: `c387809f`, 2026-07-29).

**A stopping rule, because `main` can land faster than one review takes.**
The hand-back above is already bounded — you list the shas once and stop, and
the merge is the orchestrator's. The unbounded loop exists only on the
merge-it-yourself branch: re-merge, re-run, re-fetch, and something else has
landed. So on **that** branch it is not "re-merge on any commit" —

- **Re-merge only for a commit that is behaviourally relevant to this diff**:
  it touches a file this PR touches (`gh pr diff <pr-url> --name-only` against
  `git show --name-only <sha>`), or it changes something this diff depends on.
  A landed commit in an unrelated subsystem is *recorded*, not re-merged.
- **Certify against the head you actually tested**, and say which one:
  "verdict against `<worktree HEAD sha>`, with `origin/main` at `<sha>` as of
  `<time>`". A verdict naming no sha cannot be checked against anything later.
- **Stop after the second relevant re-merge.** A third means the base is
  moving faster than this feature can be reviewed, which is a scheduling
  problem and not yours: say so in the verdict, certify against the head you
  tested, and hand the window to the orchestrator.

The relevance test is the same on both branches: on the hand-back it decides
whether you flag subsystem overlap for the orchestrator, here whether you
merge at all.

**The window after you certify belongs to the orchestrator.** Your re-fetch
line expires the moment you write it (above), and 5e's pre-ship block
independently re-reads `merge-base..origin/main`, compares the PR head to the
worktree head, and sends the diff back to you when something relevant landed.
So you do not hold the review open waiting for the ship: certify, name your
sha, stop (computenet-wpvy.33).

**That merge creates a new head, so it invalidates the `gh pr checks` reading
§4 requires you to quote.** Every required check you read belongs to the
*previous* head; at the moment you are asked to quote them they describe a
commit that is no longer the PR's. Quoting the pre-merge green certifies a
commit that was never tested. So the ordering is not optional:

```bash
git -C <worktree> push                                   # publish the merge
gh pr view <pr-url> --json headRefOid -q .headRefOid     # must equal your HEAD
git -C <worktree> rev-parse HEAD
gh pr checks <pr-url>                                    # re-read on the NEW head
```

**Budget for the wait.** A fresh Linux run is roughly 3–5 minutes, and two
reviewers in one session each spent it rediscovering this. Plan it rather than
discovering it; a `pending` check is not a pass, so if you cannot wait, certify
draft and say the checks were still running on the post-merge head.

Quote the shas or the "unchanged" line in the verdict. A verdict with no
re-fetch line is a verdict against a base that may no longer exist.

## 7. Decide

Every verdict comment and residual bead below quotes criteria and code
verbatim — build those bodies with a quoted heredoc (or `bd comment --file`)
per issue-quality.md's "Backticks…" rule: backticks in a double-quoted
argument execute as shell and vanish from the stored text, and filing a
residual is exactly how that once ran `gh pr ready` as a side effect
(computenet-9w9). The inline `"<...>"` forms below are placeholders.

Three outcomes, not two.

### Ready

Every feature criterion met, required checks green, no unowned seams — the
three criteria in AGENTS.md § "Marking a PR ready is the agent's call" hold —
and your own repairs were trivial by §5, or were a test-only repair that met
§5's exception in full (name the mutation and the origin of the expected
values in the comment below). Record the verdict, but **do not run
`gh pr ready`**: on this repo a ready PR merges itself, and you are the party
that just certified (and possibly repaired) this code, so shipping it too
would be self-approval. The orchestrator reads your verdict and ships:

**One `bd` write per call** (SKILL.md's `bd` traps). `bd` writes can run past
120s, and a chained block that dies mid-sequence leaves a half-recorded
certification — the worst state available, since `review=passed` without its
evidence comment reads as a clean pass. Each fenced block below is **one**
call; run them in order, and check each returned before the next.

```bash
git -C <worktree> push
```

```bash
bd comment <feature-id> --file - <<'EOF'
Review passed: <criteria verified with their evidence; test counts and
executed/from-cache accounting; uname; gh pr checks conclusions; re-fetch
result; what you repaired and its --stat>. Verdict: ready.
EOF
```

```bash
bd update <feature-id> --set-metadata review=passed
```

`review=passed` goes **last**: it is the flag the orchestrator ships on, so a
sequence that dies earlier leaves the feature uncertified rather than
certified with nothing behind it.

### Ready with residual — the honest negative result

The diff is sound and mergeable **and** a named criterion is genuinely not
met. This is a real shape here, not a fudge: a diagnosis feature whose honest
outcome is "could not reproduce the hang across 19 green runs, but here is a
separately measured resource leak fix" is good work plus an unmet criterion,
at the same time. AGENTS.md already blesses the discipline — a requirement
that cannot be checked honestly is filed, never weakened into a passing
scenario. Failing sound work for it, or passing it and letting the criterion
disappear, are both wrong.

Merge it, and keep the residual alive. **A residual is executed literally by
whoever picks it up, so [issue-quality.md](issue-quality.md)'s two authorship
rules bind hardest here**: a claim that something has *landed*, *merged* or
*shipped* names the branch and the sha (a feature-branch merge is not a `main`
merge, and a constraint like append-only rests entirely on which one it was),
and a prescribed remedy is checked against the data the bead cites or labelled
a suggestion to verify. Both were violated in one residual, and a reader
obeying it would have published a document contradicting itself in a single
commit (computenet-7z3t).

**Where the residual attaches depends
on one query**, run before you file — the answer has changed under a live
session, since a concurrent session can close the epic mid-review:

```bash
bd show <epic-id> --json | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.[0].status'
.claude/skills/work/scripts/epic-of.sh <feature-id>   # (unparented) is a real answer
```

| the reviewed item has… | attach the residual by |
|---|---|
| an **open** epic ancestor | `bd update "$RES" --parent=<epic-id>` — the epic cannot close while it is open, and the epic's owner is who schedules it |
| a **closed** epic ancestor | no parent, plus `bd dep add "$RES" <feature-id> --type discovered-from` — the type is a FLAG, never positional: `bd dep add <a> <b> discovered-from` fails with `requires 2 arg(s), only received 3`, and the positional shape is a fair guess because `bd create --deps` really does take `discovered-from:<id>` (computenet-l1bb) — a closed epic schedules nothing (nobody selects it at step 3), and the edge keeps the residual reachable from the work it came out of: `bv --robot-triage --graph-root` traverses it exactly like parentage |
| **no epic at all** (a 5f route-4 item — `(unparented)` is normal, computenet-wpvy.42) | `bd update "$RES" --parent=<item-id>` — parent it to the reviewed item itself, and do **not** also add the `discovered-from` edge (one-slot rule below) |

**If the residual's subject exists only on the feature branch** — a type, a
field, a layer that is not on `main` yet — stamp it, or the next session cuts
it from `origin/main` and the implementer reports wrong-branch
(computenet-nb44, one dispatch cycle): `bd update "$RES" --set-metadata
base_branch=feature/<feature-id>`. Parenting to the epic stays right for
schedulability; the field is what the direct-child route and 5a read to pick
the base.

Create it the same way in all three cases:

```bash
# Create UNPARENTED, then attach: a --parent create allocates the child id
# from a per-database counter, and two machines filing between syncs mint one
# id for different beads (computenet-wpvy.45); the hash id survives the
# re-parent. bd CREATE returns an object (bd SHOW a list), and bd prints
# warnings on stdout before the JSON, so slice from the first `{` before jq.
bd create "<the unmet criterion, verbatim>" --type=bug \
  --description="Residual from <feature-id> (PR <url>): <what was tried, what was measured, why it is unmet — and, on the closed-epic row: filed UNPARENTED deliberately, epic <epic-id> closed at review time>" \
  --acceptance="<the original criterion, unchanged>" \
  --metadata '{"model":"<sonnet|opus>","files":"<the files a fix touches>"}' \
  --json | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.id' > "$SCRATCH/residual-id"
cat "$SCRATCH/residual-id"      # must print the new id, not an empty line
```

**`--metadata` is not optional, and it is the flag reviewers keep omitting.**
A residual is a dispatchable work item by construction — it is filed precisely
so a later session picks it up — so it needs the same routing fields any
dispatchable item needs. Filed without them: `next-batch.py` returns it with
`files=[]` and batches it alone (correct scheduling for a claimless task, so
nothing errors and nothing warns), SKILL.md 5b's empty-claim rule then reads it
as a *forgotten* claim, and the dispatched implementer gets **no boundary at
all** — "stay inside your `metadata.files` claim" degenerates to naming
nothing. Three of five units in one session arrived this way, and one residual
even ended its description with a literal `Files: …` line: the author knew the
file set and wrote it into prose instead of into the field (computenet-se7r,
computenet-419f). You have just read the code — you are the best-placed author
of that claim, and the orchestrator otherwise guesses it without having read
anything. Note the flag is `--metadata` with a JSON object on `bd create`;
`--set-metadata` exists only on `bd update` ([bd-traps.md](bd-traps.md)).

A shell variable cannot cross a Bash call, so the id goes to a file and
every later call re-reads it (`RES=$(cat "$SCRATCH/residual-id")` first).
Check the `cat` printed a real id: if `jq` saw a warning preamble it writes
an empty file, and everything below would run against an empty `$RES`.
Then, **one `bd` write per call**, in order:

1. the attach write from the table;
2. `bd comment <feature-id>` — the verified criteria with their evidence,
   the unmet criterion **verbatim** (a residual glossed as "minor follow-up"
   is how it stops existing), `$RES`, and how it is attached;
3. `bd update <feature-id> --set-metadata residual=$RES`;
4. **last**, `bd update <feature-id> --set-metadata review=passed` — a
   sequence that dies earlier leaves the feature uncertified rather than
   certified with a residual nobody recorded.

**A criterion waiting on an out-of-band measurement is a third shape**, and
it is neither met nor unmet: the code is right and the number is not in yet
(a soak, a CI matrix run, an overnight job). Do not invent a verdict for it
and do not hold the review open until it reports. Certify the code, set
`review=passed`, and in the verdict comment name **the pending measurement,
its run id or url, and the criterion it settles** — then leave the ship gate
with the orchestrator, which SKILL.md 5e tells to hold the ship until that
measurement reports rather than reading `review=passed` as shippable
(computenet-wpvy.28). `review=passed` means *this review is finished*, not
*ship it*.

`review=passed` is deliberate: an unmet criterion is not a reason to withhold
a merge of code that is otherwise correct. The orchestrator still closes
`<feature-id>` when the PR merges (SKILL.md 5e) and that is correct — the
residual bead, not the feature, is what carries the criterion forward. That
is why it is filed under the **epic**: the epic cannot close while it is
open, and it is the epic's owner who schedules it, not you. Name the unmet
criterion verbatim in the comment; a residual glossed as "minor follow-up" is
how it stops existing.

**On the no-epic row, the parent edge and the `discovered-from` edge are the
same slot, so you get exactly one.** `bd` holds at most one edge per ordered
pair, and there both would run `RES -> <item>`: add the `discovered-from`
edge first and the `--parent` update fails outright — `dependency … already
exists` — leaving the residual with no parent at all (computenet-ofzz,
measured again 2026-08-17). Already added it? `bd dep remove "$RES"
<item-id>`, then `--parent`. Parent-child is the stronger edge and carries
the same provenance; the `discovered-from` edge is still correct — and
required — when the residual points at a *different* bead than its parent
(that is how unparented beads reach `--graph-root` views, e.g.
computenet-bybk and computenet-0her onto computenet-dqy.60). After parenting to an epic-less
item, `epic-of.sh` still answers `(unparented)` — correctly, there is still
no epic on the chain; check `bd show <RES>`'s `parent` field, not that.

On the closed-epic row, do not parent to the closed epic even though `bd`
lets you and the child stays visible in `bd ready` — **that visibility is
exactly what makes the wrong choice silent**: a closed epic schedules
nothing, nobody selects it at step 3, and the criterion would sit in a
container no session opens again.

Say in your report how the residual is attached and why, so the orchestrator
does not read an unparented or item-parented filing as a mistake.

### Draft

Not good enough, or your repairs were substantive (§5). Say concretely why,
and leave the work recoverable rather than vague:

```bash
bd comment <feature-id> --file - <<'EOF'
Review: staying in draft. <what's missing and why repair wasn't the right
call, or: what I authored and why it needs an independent check — <--stat
and shas>>
EOF
```

Create beads tasks for the remaining work (`bd create --parent=<feature-id>`
with `model` and `files` metadata, per [feature.md](feature.md)) so the next
batch picks them up.

**The residual-attachment table above does NOT apply to these.** The two
shapes differ by whether the feature waits: a *residual* is follow-on work the
feature does not wait for, so it attaches where someone will schedule it (the
epic); a *draft-blocking task* is work the feature DOES wait for, so it is
parented to the feature it blocks. Parent one of these under the epic and it
blocks nothing — a later session picks the feature up and ships it with its
blocker sitting beside it, unenforced. A reviewer hit the contradiction on
computenet-ahn0, chose the feature parent, and reported it rather than leaving
it silent; that judgement was right (computenet-7vsj). Only on an unparented
route-4 item does the table's row apply, because there `--parent=<item-id>`
IS the feature.

The one-slot rule (parent XOR `discovered-from`, computenet-ofzz) and the
`base_branch` stamp apply here with MORE force: a draft's subject is by
construction not on `main` (computenet-4uv1). A feature left in draft with no
tasks describing what's missing is a dead end. Two drafts legitimately have no tasks, and each is
routed by what the comment names rather than by a task id (SKILL.md 5e):
the **substantive-repair** case, where nothing is missing except a reader for
your own commits — name the commit shas and their `--stat`, or it is the same
dead end; and a **red required check** (§4), which is not the feature's work
to task — name the check and its conclusion. Do not set `review=passed`.

Draft is a legitimate outcome, not a failure. Half a feature merged is worse
than half a feature parked on a branch.

### In every case

**Write to the feature under review and to items you create — nothing else.**
Closing, re-prioritising, reassigning, re-parenting or claiming any other
bead is the orchestrator's, and the tasks under this feature are other beads.
Apply the same rule when you judge the diff: an implementer's write onto a
bead it was not assigned is *commissioned* work if the item's acceptance
criteria or the cross-bead line in its dispatch prescribe it — on
computenet-dqy.72 three criterion-prescribed comments were briefly read as an
overstep and cost an adjudication (computenet-szdd). Check both before
calling it scope creep, and report a mismatch instead of undoing the write.

**Don't run `bd dolt push`** — not because a push needs anyone's permission
(it does not: AGENTS.md, "Syncing bead state is required, not optional"), but
because it would be the redundant kind. Issue-state sync is the orchestrator's
job precisely so pushes are serialized across concurrent agents; yours would
contend with theirs and carry writes their next bracket already carries. Your
local `bd` writes are enough. That comment and metadata stay in the local
beads DB; the orchestrator's Finalize push (SKILL.md step 6) sends them to
the shared tracker. The `review=passed` marker is read by this machine, which
is the one that resumes the feature, so local is where it needs to be.

**Don't `bd close` the feature.** Ready is not merged: a required check can
still fail and leave the PR open forever. Closing here would let the epic
close on top of it and abandon the branch. Leave it `in_progress`; the
orchestrator closes it once it has confirmed the PR actually merged.

## When the diff under review edits `.claude/skills/work/`

The circularity-breaking procedure — follow `main`'s copy, review the
worktree copy as data, expect and tolerate the contradiction, run the skills
rubric gate (`validate-skills.rb`) — is [review-task.md](review-task.md)
§ "When the diff under review edits `.claude/skills/work/`", the single
copy. It applies here unchanged.

## 8. Report

**Your final message must state the verdict in one word — READY or DRAFT,
the pass and the fail of §7 — plus a NOT VERIFIED section naming everything
you did not check.** Nothing resumes you, and a result that never states a
verdict can be read as approval and shipped uncertified. The rules that make
the deliverable reach the orchestrator at all — never end a turn waiting,
the Bash-tool timeout (there is no `timeout` binary on this host), the job
ledger, and killing every background job before you report — are
[agent-execution.md](agent-execution.md); they bind here in full. Out of
room, out of time, or blocked: give the partial verdict you have and put the
rest under NOT VERIFIED — an honest partial verdict beats stopping
mid-experiment.


The feature id, the verdict and why, and — as artifacts, not adjectives — the
test counts with their executed/from-cache accounting, `uname -sm`, the
`gh pr checks` conclusions, the re-fetch result, the `--stat` of everything
you authored, and any tasks or residual beads you created. If you left it
draft, name the single thing that would most change the verdict.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
