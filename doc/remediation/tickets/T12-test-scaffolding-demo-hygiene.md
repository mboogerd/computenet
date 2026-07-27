# T12 — Test scaffolding & demo hygiene

**Phase 2 · parallel with T07/T08/T09/T10 · fresh session · Sonnet 5**
**Prereq**: Phase 1 merged (T01's timeout backstop exists — this ticket adds
the semantically better failures underneath it).
**Write scope**: `testkit/src/main`, `kernel/src/main/.../host/SimulationController.kt`,
`demo/shell/src/main`, demo **test** sources (`demo/*/src/test`), the two
demo `esc`/arg-parsing call sites in `demo/{tiering,skillmatch,agora,backlog-triage}/src/main`,
`wire/src/test`, `wire/src/main/.../WsTransport.kt` (backoff injection only),
seed-sweep test files listed below.
**Do not touch**: `demo/{shopping,exchange}/src/main` peering scaffold (T07
owns those Mains), kernel test semantics (only mechanical seed-clue
additions).

## Problem

Testability + DRY audits (verified 2026-07-27 at `742f7ca`):

1. **Unbudgeted `runToIdle()` is the dominant idiom (high).**
   `SimulationController.runToIdle()` (`kernel/.../host/SimulationController.kt:47-51`)
   is a bare `while (step()) {}` — 440 call sites across 88 files. `SimWorld`
   exists specifically to fix this ("a stalled simulation fails the test
   loudly instead of hanging forever", `testkit/.../SimWorld.kt:32-38`) and
   is used by **5 files**. T01's 5-minute JUnit backstop turns a hang into a
   failure; a budgeted drain turns it into a *diagnosable* failure.
2. **Soft-timeout awaits (medium).** `HttpProbe.await`
   (`testkit/.../HttpProbe.kt:38-47`) **returns the body on timeout**
   instead of failing — any caller that forgets a subsequent assert is a
   silent pass. Four hand-rolled `await` copies exist
   (`AgoraServerTest.kt:34`, `TriageServerTest.kt:30` — byte-identical soft
   loop, `Ws*SmokeTest.kt:65` ×2); `awaitUntil`
   (`testkit/.../AwaitUntil.kt:14`) gets it right and throws.
3. **38 of 60 seed-sweep files never report the failing seed (medium).**
   136 deterministic sweep loops; the modal `for (seed in 0L until 100L)`
   aborts at the first failing seed with a bare `shouldBe` — losing both the
   seed and the failure *density* (can't distinguish "seed 7 only" from "93
   of 100"). The right pattern exists in-repo:
   `GlitchFreeOperatorSuiteTest.kt:273-274` and `CheckInvariants.kt:13`
   ("pass the seed in `clue`").
4. **Wire smoke-test fragility (medium).** `WsReconnectSmokeTest.kt:96-109`
   releases an ephemeral port (`listener.stop`) then re-binds the same
   number — an OS-level race with any concurrent process (a documented
   reality in this environment). And `WsTransport.kt:193`'s
   `Thread.sleep(delay)` is the production reconnect backoff — not
   injectable, so reconnect timing is testable only by wall clock (the
   suite pays 10–20s deadlines).
5. **Demo test/util duplication with a latent bug (low).** `HttpProbe`
   adopted by 3 of 6 server-test suites; `AgoraServerTest`,
   `TriageServerTest`, `DemoServerTest` hand-roll HTTP helpers.
   `TieringApp.kt:226` and `SkillMatchApp.kt:269` carry a **byte-identical,
   subtly wrong** JSON escaper (`esc` — no control-char/newline handling)
   while `TriageApp.kt:379` does it right via `JsonPrimitive`. `value(flag)`
   arg parsing is hand-rolled 4× beside the shared `demoPort`
   (`DemoShell.kt:98`).

## Solution

### A. Budgeted `runToIdle` (finding 1)

Give `SimulationController.runToIdle(budget: Int = DEFAULT_BUDGET)` a
default step budget that **throws** (`IllegalStateException("simulation did
not quiesce within $budget steps — likely livelock")`) when exhausted; keep
an explicit escape for legitimately long runs. Calibrate `DEFAULT_BUDGET`
empirically: instrument one full `./gradlew :kernel:test` run, take the max
observed step count across all 440 call sites, set the default at ~10× that
(document the measurement in the constant's KDoc). All call sites keep
compiling unchanged. If any test trips the default, raise **that call
site's** explicit budget — never the default — and note it.

### B. Hard-failing awaits (finding 2)

1. `HttpProbe.await` throws on timeout (message includes the last-seen
   body); verify the 4 existing callers still pass (they already assert, so
   this is behavior-preserving for green paths).
2. Migrate `AgoraServerTest`, `TriageServerTest`, and shopping's
   `DemoServerTest` onto `HttpProbe` (add the `get`/`delete`/`postJson`
   overloads backlog-triage needs); delete the local copies. The two wire
   smoke tests keep their local await (different dependency direction) but
   fix its soft-timeout the same way.

### C. Seed reporting (finding 3)

1. Add `testkit` helper:
   `fun forEachSeed(seeds: LongRange, block: (Long) -> Unit)` — runs **all**
   seeds, collects failures, reports
   `"failed on N of M seeds; first: seed=K — <first failure message>"`
   (rethrowing with the first failure as cause so IDE navigation works).
2. Migrate the worst offenders — the files the audit named with zero seed
   context: `RelocationTest`, `DrainAndMigrateTest`, `BridgedGraphTest`,
   `DurableGlitchFreeReplayTest`, and the 9 `replication/` sweep files.
   Mechanical: wrap the existing loop body; assertions unchanged. Leave the
   remaining ~25 files for opportunistic migration (list them in the
   report). Never alter which seeds run (AGENTS.md rule).

### D. Wire test robustness (finding 4)

1. Fix the re-bind race: hold the port with a bound `ServerSocket` released
   immediately before re-listen, plus a bounded retry on `BindException`
   with a clear message.
2. Inject the reconnect backoff: `WsTransport` takes a
   `backoff: (attempt: Int) -> Long` (default = current behavior); the
   smoke test drives it to 0 and drops its 20s deadlines to ~2s. Add a
   bounded attempt ceiling or surfaced state is **out of scope** (noted in
   COVERAGE.md).

### E. Demo util dedup (finding 5)

Move into `:demo:shell` beside `respond`/`demoPort`: a correct `esc(s)`
built on `JsonPrimitive(s).toString()` and a `value(flag)`/`flag(name)` arg
helper. Migrate the 2 `esc` copies (tiering, skillmatch — this **fixes the
latent escaping bug**) and the 4 arg-parse copies (agora, backlog-triage,
plus shopping/exchange only if T07 has merged; otherwise leave those two and
note it). Add one `esc` edge-case test (quotes, backslash, newline, control
char) in `demo/shell`.

## Verification

```bash
./gradlew :kernel:test                       # budget default survives the full suite
./gradlew :demo:agora:test :demo:backlog-triage:test :demo:shopping:test
./gradlew :demo:tiering:test :demo:skillmatch:test :demo:slotfinder:test
./gradlew :wire:test                          # 5× (--rerun) for the re-bind fix
./gradlew test
```

## Report

The measured max step count and chosen default budget; call sites needing an
explicit budget; suites migrated to `HttpProbe`/`forEachSeed` and the
remaining opportunistic list; the wire deadline reduction achieved.
