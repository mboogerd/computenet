# T23 — `inspect/ui` (and `agora/ui`) vitest + typecheck gated by CI, Node version pinned

**Status:** not-started
**Model:** haiku · **Escalate to:** sonnet
**Wave:** 2 · **Branches:** `ticket/T23`

## Context

`inspect/ui` is a SolidJS/Vite frontend (`inspect/ui/package.json`) with 259
passing vitest tests and a clean `tsc --noEmit`. Nothing in CI runs either
check — `.github/workflows/ci.yml` is Gradle-only (`./gradlew build check` in
job `build-test`, `./gradlew :concord:test ...` in job `concord-full`). The
inspector plan's per-milestone EVAL step used to run these checks by hand;
that control retired with the plan, so the frontend contract has drifted
silently (5 recorded fixture drifts across 6 milestones — see
`doc/remediation/AUDIT-2026-07-28.md` §W4 item 1 and
`doc/architecture-decisions.md` finding B5, line 40).

The 2026-07-27 audit explicitly declined a Node version pin for the agora
frontend at the time (`doc/remediation/COVERAGE.md:43`: *"UI is not in the CI
build and is a research frontend; add the day UI CI lands."*). That day is
this ticket.

The prior audit's plan banned `gradle-node-plugin` as the *mechanism* for
running npm checks from Gradle — it did not forbid a standalone npm-only CI
job. Adding a job that runs `npm ci && npm run typecheck && npm test`
directly, with no Gradle involvement, violates no prior decision.

`.github/workflows/ci.yml` is being restructured by ticket T13 (wave 1,
CI revival: splits `build-test` into fast/serial lanes) before this ticket
runs — T23 is sequenced after T13 in wave 2 specifically so it edits
whatever shape T13 leaves behind
(`doc/remediation/AUDIT-2026-07-28-PLAN.md:104-114`). Do not assume the exact
job names or step order shown below under "current shape" still exist by the
time you run — only that the file still has a top-level `jobs:` key with one
or more sibling job entries under it, and a top-level `on:` block (`push` +
`pull_request`) that already applies to every job with no per-job
duplication needed.

Current shape (pre-T13, for orientation only):

```yaml
name: CI

on:
  push:
  pull_request:

jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      ...

  concord-full:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      ...
```

`jobs:` sits at column 0; each job name is indented 2 spaces; `runs-on:` and
`steps:` are indented 4 spaces; each `steps:` list item is indented 6 spaces
(a `- uses:`/`- name:` line) with its own sub-keys at 8 spaces. Match this
indentation style for the new job(s) regardless of what else T13 changed.

## Problem

`inspect/ui`'s 259 tests and its typecheck are real, fast (verified locally:
typecheck ~2s, `vitest run` 3.31s), and run by nothing automated. A regression
in either can merge to `main` undetected. There is also no Node version
contract anywhere in the repo — CI runners default to whatever Node the
`ubuntu-latest` image ships, and local devs use whatever they have installed
(this machine's default is `v25.9.0`, an odd/non-LTS release — not a safe pin).

## Solution direction (decided)

1. **Pin Node 22** (the LTS line already anticipated for this exact ticket at
   `doc/remediation/AUDIT-2026-07-28-PLAN.md:22`, which notes T23's sandbox
   "may instead use `node:22-bookworm`"). Verified locally: `nvm install 22`
   installed `v22.23.1`; both `inspect/ui`'s and `demo/agora/ui`'s
   dependencies declare Node engine ranges that Node 22 satisfies
   (`vite@6`: `^18.0.0 || ^20.0.0 || >=22.0.0`; `vitest@2.1.8`: `^18.0.0 ||
   >=20.0.0` — checked via `npm view vite@6 engines` / `npm view
   vitest@2.1.8 engines`). All three npm commands below pass under `v22.23.1`
   for both `inspect/ui` and `demo/agora/ui` (see Verify).
   - `inspect/ui/.nvmrc` — new file, single line: `22`
   - `inspect/ui/package.json` — add `"engines": { "node": "^22.0.0" }`
   - Mirror both for `demo/agora/ui` (see step 3 — its checks pass locally,
     so it is included).
2. **Append one independent CI job**, `ui-test`, to `.github/workflows/ci.yml`
   under the existing `jobs:` key (do not touch existing jobs; no `needs:` —
   it must run in parallel, independent of whatever Gradle job(s) T13 left
   behind):

   ```yaml
     ui-test:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4

         - name: Set up Node
           uses: actions/setup-node@v4
           with:
             node-version-file: inspect/ui/.nvmrc
             cache: npm
             cache-dependency-path: inspect/ui/package-lock.json

         - name: Install dependencies
           working-directory: inspect/ui
           run: npm ci

         - name: Typecheck
           working-directory: inspect/ui
           run: npm run typecheck

         - name: Test
           working-directory: inspect/ui
           run: npm test
   ```

3. **`demo/agora/ui` has runnable, passing equivalents** — verified locally:
   `npm run typecheck` (`tsc --noEmit`) is clean, and `npm test` (`vitest
   run`) passes 19/19 tests across 6 files. Because both pass, include it:
   append a second independent job, `agora-ui-test`, mirroring `ui-test`
   exactly but with `inspect/ui` replaced by `demo/agora/ui` throughout
   (`node-version-file: demo/agora/ui/.nvmrc`,
   `cache-dependency-path: demo/agora/ui/package-lock.json`,
   `working-directory: demo/agora/ui`). Do **not** add a step for
   `npm run test:e2e` (Playwright) — it needs browser binaries the CI runner
   doesn't have installed by this ticket and is out of scope.
4. Do **not** add an `npm audit` step in either job. The audit report scoped
   that out on purpose (5 dev-only advisories in `inspect/ui`, 6 in
   `demo/agora/ui` as observed locally during `npm ci` — none in production
   dependencies); that is a deliberate deferral, not this ticket's job.
5. Do not wire any of this into Gradle. No `gradle-node-plugin`, no
   `./gradlew` invocation of npm, no changes to any `build.gradle.kts` or
   `buildSrc/**` file.

## Files expected to touch

- `.github/workflows/ci.yml` — append `ui-test` and `agora-ui-test` jobs
- `inspect/ui/.nvmrc` — new, content `22`
- `inspect/ui/package.json` — add `"engines": { "node": "^22.0.0" }` only
- `demo/agora/ui/.nvmrc` — new, content `22`
- `demo/agora/ui/package.json` — add `"engines": { "node": "^22.0.0" }` only

Do not modify: any Gradle file (`*.gradle.kts`, `buildSrc/**`,
`gradle.properties`); `inspect/ui` sources, tests, or `inspect/ui/fixtures/*`
(ticket T20 owns fixtures-adjacent test changes); `demo/agora/ui` sources or
tests; either `package-lock.json` (adding an `engines` field does not require
regenerating the lock — if your `npm ci` run touches it, something is wrong;
investigate before committing it).

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W4 item 1 (around line 101) — the
  decided design this ticket implements
- `doc/architecture-decisions.md:40` (finding B5) — the accepted-findings
  record this ticket resolves
- `doc/remediation/COVERAGE.md:43` — the Node-pin deferral this ticket now
  executes
- `doc/remediation/AUDIT-2026-07-28-PLAN.md:22` (sandbox image note),
  `:104-114` (wave 2 sequencing — T23 runs after T13 specifically because it
  edits `ci.yml`)
- `.github/workflows/ci.yml` — current shape to append to (will differ after
  T13; append-only regardless)
- `doc/remediation/tickets/T13-ci-revival.md` — exemplar ticket that also
  edits `ci.yml`; its Verify section's yaml-sanity pattern
  (`python3 -c "import yaml, sys; yaml.safe_load(...)"` /
  `ruby -ryaml -e "YAML.load_file(...)"`) is worth reusing — on this machine
  only the `ruby` form works (`python3` here has no `pyyaml` installed; check
  both, use whichever succeeds)
- `inspect/ui/package.json` — `test` = `vitest run`, `typecheck` = `tsc
  --noEmit`
- `demo/agora/ui/package.json` — same two scripts, plus an out-of-scope
  `test:e2e` = `playwright test` (do not wire this one up)

## Acceptance criteria

- [ ] `inspect/ui/.nvmrc` exists, single line `22`
- [ ] `inspect/ui/package.json` has `"engines": { "node": "^22.0.0" }` and is
      otherwise unchanged (scripts/dependencies untouched)
- [ ] `demo/agora/ui/.nvmrc` exists, single line `22`
- [ ] `demo/agora/ui/package.json` has `"engines": { "node": "^22.0.0" }` and
      is otherwise unchanged
- [ ] `.github/workflows/ci.yml` has a new `ui-test` job: checkout, Node
      setup via `node-version-file: inspect/ui/.nvmrc` with npm cache keyed
      on `inspect/ui/package-lock.json`, then `npm ci`, `npm run typecheck`,
      `npm test`, all with `working-directory: inspect/ui`
- [ ] `.github/workflows/ci.yml` has a new `agora-ui-test` job, same shape,
      rooted at `demo/agora/ui`
- [ ] Neither new job has a `needs:` on, or is depended on by, any existing
      job — both run independently of the Gradle jobs
- [ ] No `npm audit` step added anywhere
- [ ] No Gradle file touched
- [ ] `cd inspect/ui && npm ci && npm run typecheck && npm test` passes
      locally under Node 22
- [ ] `cd demo/agora/ui && npm ci && npm run typecheck && npm test` passes
      locally under Node 22
- [ ] `.github/workflows/ci.yml` parses as valid YAML
- [ ] No unrelated files in the diff

## Verify

```bash
# Node 22 available (install via nvm if not already present)
source "$NVM_DIR/nvm.sh" 2>/dev/null || source ~/.nvm/nvm.sh
nvm install 22
nvm use 22
node --version   # expect v22.x

# inspect/ui — all three must pass
cd inspect/ui
npm ci
npm run typecheck
npm test
cd -

# demo/agora/ui — all three must pass
cd demo/agora/ui
npm ci
npm run typecheck
npm test
cd -

# YAML sanity on the edited workflow file
ruby -ryaml -e "YAML.load_file('.github/workflows/ci.yml'); puts 'yaml OK'"
```

## Report on completion

- Checks run and their results (all six npm commands, plus the yaml parse)
- Node version actually verified against (record the exact `node --version`
  output used for local verification)
- Files actually touched, and any not in the claim above
- Confirm whether either `package-lock.json` changed as a side effect of
  `npm ci` after adding `engines` — expected: no
- Anything specified here you could not do, and why
