# T01 — Repo truth, CI, and build hygiene

**Phase 0 · parallel with T02/T03 · fresh session · Sonnet 5**
**Write scope**: git tracking, `.github/`, `gradle.properties`,
`gradle/libs.versions.toml`, `buildSrc/`, `settings.gradle.kts`, all
`build.gradle.kts` files, `gen-test/` (deletion), `doc/ARCHITECTURE.md` (the
sentences these changes invalidate), `README.md` troubleshooting section.
**Do not touch**: any Kotlin source under `kernel/`, `wire/`, `demo/*/src`,
`concord/src` (except none needed).

## Problem

Audit findings (build-hygiene + docs-concordance + modularity audits, verified
2026-07-27 at `742f7ca`):

1. **No CI of any kind exists** (no `.github/`, no other CI config). 821 `@Test`
   methods, the `:concord:concordanceGate`, and `ExchangeCompositionExitTest`
   are enforced only by an agent remembering to run them locally. Concurrent
   agent workers self-certify "completed" with no independent gate.
2. **Load-bearing files were never committed** (`git log` empty for each, none
   gitignored): `CLAUDE.md`, `doc/ARCHITECTURE.md` (cited 4× by the tracked
   `AGENTS.md` and linked from `README.md:178`), `doc/adr/ADR - Adapter
   Synthesis.md`, `doc/spec/CONCORDANCE.md` (named a work-queue source by
   `AGENTS.md`), and `kernel/src/test/kotlin/civictech/cell/port/AdaptWaveParticipationSpikeTest.kt`.
   A fresh clone / new worktree is missing the document every contributor is
   told to read first, and the local `./gradlew test` result includes a test
   that exists in nobody else's checkout.
3. **`:gen-test` is a verified no-op gate.** The module contains exactly one
   tracked file (`gen-test/build.gradle.kts`). A live run shows every task
   `NO-SOURCE`; `failOnNoDiscoveredTests = false` (gen-test/build.gradle.kts:22-27)
   suppresses the only honest signal. Yet `kernel/build.gradle.kts:32-34` wires
   `compileKotlin dependsOn(":gen-test:test")` and `doc/ARCHITECTURE.md:38`
   claims "generator regressions fail before kernel compiles". The claim is
   false; real generator coverage is `:gen`'s own tests. The module also pins
   `junit-bom:5.10.0` (gen-test/build.gradle.kts:17) against the catalog's
   `junit = "5.13.4"` — a latent 3-minor-version skew.
4. **Concord's default profile silently skips 8 of 55 scenarios.**
   `concord/build.gradle.kts:37` defaults `concord.profiles` to `core`; the 6
   `dist` + 2 `dur` scenarios — the hardest semantic domains — never run under
   `./gradlew test`, while `CONCORDANCE.md` still counts them as covered.
5. **No test-timeout backstop anywhere.** 440 unbudgeted `runToIdle()` call
   sites; zero `@Timeout`, zero `junit-platform` timeout config. A livelock
   regression hangs the build silently instead of failing it.
6. **Build-script duplication and catalog drift.** A 5-line test-dependency
   block is copy-pasted across 11 `build.gradle.kts` files, with 2 lines
   redundant in the 8 modules that already get JUnit transitively via
   `testImplementation(project(":testkit"))` (testkit exposes
   `api(libs.junit)`, `api(kotlin("test"))`, `runtimeOnly(libs.junit.platform)`
   at testkit/build.gradle.kts:14-16). The KSP source-dir block is duplicated
   verbatim in 3 files. `concord/build.gradle.kts:19` hardcodes
   `kaml:0.77.1` outside the catalog. Five catalog aliases are dead:
   `junit-bom`, `assert4j`, `kotlinx-datetime`, `junit-api`, `junit-engine`.
7. **Misc**: `gradle.properties` lacks `org.gradle.parallel`; kernel overrides
   the convention's documented 1g test heap with 2g
   (kernel/build.gradle.kts:36-41) while `doc/ARCHITECTURE.md:46-49` documents
   only 1g; `demo/shell/build.gradle.kts:11` declares
   `implementation(project(":kernel"))` but `DemoShell.kt` imports nothing from
   `civictech.*`.

## Solution

### A. Track the untracked (do this first, by pathspec)

1. `git add` exactly: `CLAUDE.md`, `doc/ARCHITECTURE.md`,
   `doc/adr/ADR - Adapter Synthesis.md`, `doc/spec/CONCORDANCE.md`.
2. `AdaptWaveParticipationSpikeTest.kt`: run it
   (`./gradlew :kernel:test --tests '*AdaptWaveParticipationSpikeTest'`).
   If green and it asserts real behavior, commit it; if it is an abandoned
   spike (trivial/exploratory asserts), delete it. Either way it stops being
   untracked. Record the choice in the final report.
3. `CONCORDANCE.md` policy = **tracked, generated**: CI (below) regenerates and
   fails on diff, so drift shows in review.

### B. CI — `.github/workflows/ci.yml`

One workflow, on `push` + `pull_request`:

- `actions/checkout@v4`, `actions/setup-java@v4` (temurin 21),
  `gradle/actions/setup-gradle@v4`.
- Job **build-test**: `./gradlew build check`.
- Job **concord-full**: `./gradlew :concord:test -Pconcord.profiles=core,dist,dur`
  and `./gradlew :concord:concordance`, then
  `git diff --exit-code doc/spec/CONCORDANCE.md` (regeneration must be a no-op).
- Final step in build-test: fail on unexpected untracked files —
  `test -z "$(git status --porcelain -uall -- doc '*/src')"` (guards the whole
  class of finding #2 permanently).
- A CI checkout is a fresh clone, so this also delivers the clean-clone guard.

Expect the first runs to surface latent flakiness — triage as findings in the
final report, not as a reason to soften the workflow.

### C. Concord profile default

In `concord/build.gradle.kts:37` flip the default from `core` to
`core,dist,dur`. Local fast loops opt *out* with `-Pconcord.profiles=core`.
Update the two doc mentions of the default (`doc/ARCHITECTURE.md` §5,
`AGENTS.md` verification block) to match.

### D. Test-timeout backstop

In `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`, inside the existing
`tasks.withType<Test>` configuration, add:

```kotlin
systemProperty("junit.jupiter.execution.timeout.testable.method.default", "5m")
```

5 minutes is deliberately generous (seed sweeps); the point is hang → failure,
not tightness. If any legitimate test exceeds it, raise per-class with
`@Timeout`, don't raise the default.

### E. Delete `:gen-test`

1. Remove the `gen-test` module directory and its entry in
   `settings.gradle.kts`.
2. In `kernel/build.gradle.kts:32-34` repoint the gate to the tests that
   actually exist: `dependsOn(project(":gen").tasks.named("test"))` — this
   makes ARCHITECTURE.md's claim true.
3. Update `doc/ARCHITECTURE.md` (module table row for `:gen-test`, and the
   §Troubleshooting sentence in `README.md:184-186` that references it).

### F. Build dedupe + catalog hygiene

1. Move the shared test stack into the convention plugin
   (`buildsrc.convention.kotlin-jvm`): `testImplementation(libs.kotest.assertions.core)`,
   `testImplementation(libs.junit)`, `testRuntimeOnly(libs.junit.platform)`,
   `testImplementation(kotlin("test"))`. Delete those lines from the 11
   modules that repeat them (keep each module's `testImplementation(project(":testkit"))`).
2. Add a `kaml` alias to `gradle/libs.versions.toml`; use it from
   `concord/build.gradle.kts:19`.
3. Delete the five dead aliases (`junit-bom`, `assert4j`, `kotlinx-datetime`,
   `junit-api`, `junit-engine`).
4. Add a second convention plugin `buildsrc.convention.ksp-cell` bundling the
   KSP plugin + `implementation`/`ksp` on `:gen` + the generated-source dir;
   apply it in `kernel`, `demo/agora`, `demo/backlog-triage` (the 3
   duplicators).
5. `gradle.properties`: add `org.gradle.parallel=true`.
6. Heap: set `maxHeapSize = "2g"` in the convention plugin, delete the kernel
   override, and fix `doc/ARCHITECTURE.md:46-49` to say 2g.
7. Remove `implementation(project(":kernel"))` from
   `demo/shell/build.gradle.kts` (keep the comment explaining it returns the
   day `DemoShell`'s API takes a cell-model type).

## Tests / verification

```bash
./gradlew build check
./gradlew :concord:test          # must now run core+dist+dur by default
./gradlew :kernel:compileKotlin  # confirms the :gen:test gate wiring
git status --porcelain -uall     # empty except doc/remediation (this run's files)
```

Push the branch and confirm the CI workflow goes green (or report exactly
which latent failures it surfaced).

## Report

List: every file committed/deleted, the spike-test decision, CI run result,
any flakiness surfaced by the full concord profile or timeouts.
