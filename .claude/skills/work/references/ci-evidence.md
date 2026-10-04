# CI evidence


Local runs here are darwin; every required check runs on `ubuntu-latest`.
Report "green on darwin/arm64", never "the required checks pass". For code
touching sockets, ports, filesystem semantics, paths or process spawning,
measure the gap: a JDK-21 Linux container when `docker info` shows a running
daemon, otherwise the branch's own CI run.

Wait for checks with `.claude/skills/work/scripts/wait-checks.sh <pr-url>`.

| last line | means |
|---|---|
| `SETTLED` | every required check finished; read the rows above for red |
| `TIMEOUT-PENDING`, or the call never returns | no verdict; a reviewer's one invocation for this head is spent ([review.md](review.md#feature-review)) |
| `NO-RUN` | GitHub never built this head; never wait it out |
| `UNBOUND` | the rows settled over a transport that names no commit: not evidence for this diff |
| `QUERY-FAILED` | nothing was read |

**A green check is not a run.** A required check can be green while the
diff's tests never executed: the task was `FROM-CACHE`/`UP-TO-DATE`, an
`assumeTrue` class was `SKIPPED`, or cargo captured a skip as a pass. Ship
evidence is met only by
`.claude/skills/work/scripts/ci-executed.sh <pr-url> <check-name> :<module>:test…`,
which selects the job by check name on the PR's current head, reads its log,
and prints one row per task; its header documents the tokens. Any row other
than `EXECUTED`, a `skipped-tests` count in the diff's modules, `STALE-HEAD` or
`QUERY-FAILED` goes under `NOT VERIFIED` in the PR body and your report, never
"CI green".

**Comparing runs asks the same question.** Before other runs' green says a red
is yours, run `ci-executed.sh <run-id> <check-name> <:task>` on each compared
run; count only runs whose task `EXECUTED`.

**Test stdout is not in CI logs.** Gradle does not echo it, and the lanes upload
no test-results artifact for most modules. A value someone must see on Linux
goes in the assertion message, or is measured in a local Linux container.

**A suite that runs only on CI** (multicast-gated, ubuntu-only): the task is
reviewed unexecuted and merged; the feature PR's run is its first execution.
Read that with `ci-executed.sh` before the feature review, and file a red there
as a task under the feature. Its mutation goes in a Linux container
([Mutation checks](evidence.md#mutation-checks)); without one the mutation is
`NOT VERIFIED` and parked. An unattended orchestrator never pushes a mutated
commit.

**A lane is evidence only for the tests its filter admits**, and a filter in
the lane's Gradle command leaves no `SKIPPED` line: `build-test-fast` runs
`-PexcludeMultiJvm=true`, so a `@Tag("multi-jvm")` test runs only in
`build-test-serial`. For a tagged or flag-gated test, find the admitting lane
in `.github/workflows/` (it may be a separate workflow run), read its log for
the test's `PASSED` line, and name the lane. `ci-executed.sh` prints the job
id; save the log with
`gh api repos/mboogerd/computenet/actions/jobs/<job-id>/logs > "<scratch>/ci-<check-name>.log"`
and read it only if it is not a JSON error body.

A log you could not fetch goes under `NOT VERIFIED`, never as passed. A red
required check is attributed per [recovery.md](recovery.md#a-red-required-check).
