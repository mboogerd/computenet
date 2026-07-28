# T13 — CI actually gates a push: two-JVM failures classified, fast and serial lanes split

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 1 · **Branches:** `ticket/T13`

## Context

`.github/workflows/ci.yml` was added in `8972acc` (2026-07-27, ticket T01) as
the repo's first CI of any kind. It defines two jobs on `push` + `pull_request`:
`build-test` (`./gradlew build check`, then a step that fails the build if
`git status --porcelain -uall -- doc '*/src'` is non-empty — a clean-clone
guard against load-bearing files that were never committed) and `concord-full`
(`./gradlew :concord:test -Pconcord.profiles=core,dist,dur`, then
`./gradlew :concord:concordance` and a no-op-diff check on
`doc/spec/CONCORDANCE.md`). The same commit range (`54fa5de`, also T01) added
`org.gradle.parallel=true` to `gradle.properties:2` as part of a build-dedupe
pass unrelated to CI.

The workflow has executed exactly once in the repo's history: run
`30336011854`, triggered by the push that landed the current `main` HEAD
(`dcfbb33`). `concord-full` passed in 4m0s. `build-test` **failed** in 6m45s
at `./gradlew build check`, specifically at the `:demo:exchange:test` task:
14 tests completed, 2 failed (`gh run view 30336011854 --log-failed`):

- `ExchangeScaffoldTest > edits on either JVM converge to the same
  region-sum board()` — `org.opentest4j.AssertionFailedError` at
  `demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeScaffoldTest.kt:81`
- `ExchangeScaffoldTest > a kill -9'd peer recovers its journaled writer
  state and both sides re-converge()` — same error class, at
  `ExchangeScaffoldTest.kt:116`

Both lines are the same gate in each test:
`awaitUntil("both peers serving HTTP", timeoutMs = 45_000) { up(httpA) &&
up(httpB) }` — the test spawns two peers as separate OS processes
(`ProcessBuilder`, see `ExchangeScaffoldTest.kt:32-38`) over free ports
(`JvmPeer.freePort()`) and waits up to 45s for both to answer HTTP. The
third test in the same file, `writer journal alone reconstructs the board
after restart` (`ExchangeScaffoldTest.kt:159-183`), builds `ExchangeApp`
directly in-process (no `ProcessBuilder`) and **passed** — it is not a
multi-JVM test. Because `:demo:exchange:test` failed, the workflow's final
step, `Fail on unexpected untracked files` (`ci.yml:30-31`), never ran (`gh
run view` reports it with a `-`, i.e. skipped) — it has never once executed
in this repo despite being the one guard T01 was written to add.

All three `ExchangeScaffoldTest` cases were re-run locally
(`./gradlew :demo:exchange:test --tests
'civictech.demo.exchange.ExchangeScaffoldTest'`) as part of writing this
ticket: all pass, total task time ~5s. The local machine has 16 cores; the
failed run used GitHub's default `ubuntu-latest` runner, which is 2-core.
`demo/shopping/src/test/kotlin/civictech/demo/{TwoJvmConvergenceTest.kt,CrashRestartConvergenceTest.kt}`
are the only other tests in the repo built on the same `JvmPeer` two-process
pattern; neither ran in the failing CI job (Gradle hadn't reached
`:demo:shopping:test` before `:demo:exchange:test` failed the build), so
their CI behavior under contention is unknown.

`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` (package
`buildsrc.convention`, precompiled script plugin id
`buildsrc.convention.kotlin-jvm`) is the shared test convention every module
applies (directly, or via `buildSrc/src/main/kotlin/ksp-cell.gradle.kts` for
`kernel` and `demo/backlog-triage`). It already configures
`tasks.withType<Test>().configureEach { useJUnitPlatform(); ... }`
(`kotlin-jvm.gradle.kts:28-49`) — the natural place to wire a repo-wide JUnit
tag filter, since every module's `Test` tasks flow through it.

`doc/remediation/AUDIT-2026-07-28.md` §W1 and
`doc/architecture-decisions.md` finding A1 record this as the audit's one
Critical: CI has never gated anything, and three docs (per the audit) claim
ratchets "gate the build" when only local `./gradlew test` runs have ever
enforced them.

## Problem

1. CI cannot be trusted as a merge gate today: its only run failed, on tests
   that are unrelated to the commit that triggered it and that pass
   deterministically on a higher-core-count machine. Nobody has established
   whether this is runner contention (2-core `ubuntu-latest` +
   `org.gradle.parallel=true` scheduling `:demo:exchange:test` concurrently
   with sibling module test tasks, each themselves spawning two external
   JVMs and racing a fixed 45s wall-clock budget) or a real timing-dependent
   defect in `demo/exchange`'s peering/startup path that only a starved
   scheduler exposes.
2. The clean-clone untracked-files guard (`ci.yml:30-31`) — the exact
   mechanism T01 built to stop load-bearing files from going untracked again
   — has never executed once, because it sits after a task that has always
   failed first.
3. There is no lane structure that isolates multi-process, wall-clock-timing
   tests from the rest of the suite, so a single flaky two-JVM test can (and
   did) block every other signal in the same job, including signals that had
   nothing to do with it (14 exchange tests, of which 12 passed cleanly).

## Solution direction

1. **Classify first, before changing anything.** Reproduce
   `ExchangeScaffoldTest` under constrained resources that approximate the
   2-core runner — e.g. `./gradlew :demo:exchange:test --max-workers=1` run
   alongside CPU-limited conditions (a `docker run --cpus=2` container, or
   `taskset`/`cpulimit` if available), and/or force the same interleaving CI
   had by running the full `build-test` job's task graph
   (`:kernel:test`, `:demo:skillmatch:test`, `:demo:shopping:test`,
   `:demo:exchange:test`, ...) under `--max-workers=1` with 2 CPUs available
   to the process. Record what you tried and what happened in the completion
   report.
   - **Abandon-trigger, verbatim in spirit from the audit:** if the failures
     reproduce locally under constrained cores *without* contention from
     sibling Gradle tasks — i.e. this is a real kernel/wire race, not runner
     starvation — **stop**. Report the diagnosis and the failing detail
     (seed, stack trace, minimal repro) for the orchestrator's C1-replan
     checkpoint (`doc/remediation/AUDIT-2026-07-28-PLAN.md` — "Checkpoint
     C1-replan"). Do not raise timeouts or otherwise paper over a real race
     to buy a green run; AGENTS.md's failing-seed rule governs, not CI
     plumbing.
2. If (and only if) classified as runner contention: tag every multi-process
   test with a JUnit `@Tag("multi-jvm")` — the two failing
   `ExchangeScaffoldTest` cases at minimum
   (`edits on either JVM converge to the same region-sum board`,
   `a kill -9'd peer recovers its journaled writer state and both sides
   re-converge`; leave `writer journal alone reconstructs the board after
   restart` untagged, it is single-process). Decide, and record the
   decision, whether `demo/shopping`'s `TwoJvmConvergenceTest` and
   `CrashRestartConvergenceTest` also move to the tagged lane — they share
   the same `JvmPeer` two-process shape and the same class of risk under
   contention, even though they weren't observed failing (the run never
   reached them).
3. Wire a tag-filtered lane split into the shared test config in
   `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`'s existing
   `tasks.withType<Test>().configureEach { ... }` block (`kotlin-jvm.gradle.kts:28-49`),
   using `useJUnitPlatform { excludeTags(...) / includeTags(...) }` gated on
   a Gradle project property. Exact property name and default direction
   (fast-lane-by-default vs opt-in) are the implementer's choice; document
   whichever is chosen in the completion report and keep the Verify section
   below in sync with it.
4. Split `ci.yml`'s `build-test` job into two: a fast lane (excludes the
   `multi-jvm` tag, keeps `org.gradle.parallel=true` and default worker
   count) and a serial multi-JVM lane (includes only the `multi-jvm` tag,
   runs with `--max-workers=1` to remove the contention variable this ticket
   diagnosed). Keep `concord-full` unchanged — it passed. Ensure the
   `Fail on unexpected untracked files` step is reachable on green, i.e.
   placed in whichever job(s) actually complete the full `build check` and
   are the ones expected to succeed most reliably; do not bury it behind the
   serial lane if that lane is the flakier one.
5. Do not touch `README.md` — ticket T14 owns re-truing it, including adding
   the CI status badge.

## Files expected to touch

- `.github/workflows/ci.yml` — split `build-test` into fast + serial lanes;
  relocate/confirm the untracked-files step
- `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` — tag-filtered test
  wiring (property-gated `useJUnitPlatform { includeTags/excludeTags }`)
- `demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeScaffoldTest.kt` —
  `@Tag("multi-jvm")` on the two two-process tests (annotations only)
- `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmConvergenceTest.kt`,
  `demo/shopping/src/test/kotlin/civictech/demo/CrashRestartConvergenceTest.kt` —
  `@Tag("multi-jvm")` if the implementer decides they move lanes (annotations
  only)
- `testkit/**` — only if a shared tag-name constant belongs there instead of
  a string literal per call site; not required

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W1 — the decided design and the
  abandon-trigger this ticket must honor
- `doc/architecture-decisions.md` finding A1 — the accepted-findings record
  this ticket resolves
- `doc/remediation/AUDIT-2026-07-28-PLAN.md` — "Checkpoint C1-replan" —
  exactly what happens if the abandon-trigger fires, and how T22/T23 in
  Wave 2 depend on this ticket's serial lane and `ci.yml` shape
- `.github/workflows/ci.yml` — current two-job shape to split
- `demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeScaffoldTest.kt:73-183` —
  the three tests, two multi-process, one not
- `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:28-49` — the existing
  `tasks.withType<Test>` block to extend
- `AGENTS.md` — "Preserve deterministic simulation/generative tests. Do not
  replace a discovered failing seed with a friendlier seed." — governs the
  abandon-trigger

Do not modify: `README.md`, `doc/**`, `kernel/src/main/**`, `inspect/**`.

## Acceptance criteria

- [ ] The two-JVM failures are classified (runner contention vs real race)
      with reproducible evidence recorded in the completion report
- [ ] If classified as a real race: the ticket stops there, reports the
      diagnosis for C1-replan, and makes no further changes to `ci.yml` or
      test tags
- [ ] If classified as contention: every multi-process (`JvmPeer`/
      `ProcessBuilder`-based) test in the file claim carries
      `@Tag("multi-jvm")`
- [ ] `./gradlew test` (or whatever the chosen fast-lane invocation is)
      passes locally and its test report contains no result for any
      `multi-jvm`-tagged test method
- [ ] The chosen serial-lane invocation runs and passes, and its test report
      contains only `multi-jvm`-tagged test methods
- [ ] `ci.yml` defines both lanes plus the unchanged `concord-full` job, and
      the untracked-files check is reachable on a green run (not stranded
      behind a step likely to fail)
- [ ] No test timeout was raised anywhere without a recorded classification
      justifying it
- [ ] No unrelated files in the diff

## Verify

```bash
# Local reproduction under constrained parallelism (adjust to whatever
# constrained-resource method was actually used; record it in the report)
./gradlew :demo:exchange:test --tests 'civictech.demo.exchange.ExchangeScaffoldTest' --max-workers=1

# Fast lane: must run and pass without touching multi-jvm-tagged tests.
# Property name below (excludeMultiJvm) is illustrative — use the name
# actually chosen and keep this command in sync with it.
./gradlew test -PexcludeMultiJvm=true
grep -rl 'multi-jvm' */build/test-results/test/*.xml 2>/dev/null && echo "FAIL: tagged test ran in fast lane" || echo "OK: no tagged test in fast lane"

# Serial lane: must run and pass, and run only multi-jvm-tagged tests.
./gradlew test -PmultiJvmOnly=true --max-workers=1

# YAML sanity check on the split workflow (use actionlint if available in
# the sandbox; otherwise a plain parse is enough to catch structural errors)
python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('yaml OK')" \
  || ruby -ryaml -e "YAML.load_file('.github/workflows/ci.yml'); puts 'yaml OK'"
```

The ultimate proof — a green CI run on `main` after this ticket's branch
merges — is the orchestrator's post-merge responsibility, not something this
ticket's session can produce (merging and pushing to `main` is outside a
worker's role per AGENTS.md's multi-agent run discipline). Likewise,
enabling required status checks for `main` in GitHub's branch-protection
settings is a manual step in GitHub's UI/API that this ticket cannot perform;
name it explicitly as an outstanding manual step in the completion report.

## Report on completion

- Classification result (contention vs real race) and the exact evidence
  (commands run, timings, core counts) that supports it
- If stopped at the abandon-trigger: the diagnosis handed to C1-replan, and
  confirmation no `ci.yml`/tag/timeout changes were made
- If completed: the chosen property name(s) and lane invocations, which
  tests ended up tagged (including the `demo/shopping` decision either way),
  and where the untracked-files step now lives
- Checks run and their results (fast lane, serial lane, yaml sanity)
- Files actually touched, and any not in the claim above
- The two manual follow-ups this ticket cannot perform itself: pushing to
  `main` for a real green CI run, and enabling required status checks on
  `main` in GitHub's branch-protection settings
