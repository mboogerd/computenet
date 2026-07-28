# T14 — orientation docs and README stop hiding `:inspect`

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 1 · **Branches:** `ticket/T14`

## Context

`AGENTS.md` §"Start every task here" tells every agent that `doc/ARCHITECTURE.md`
and `README.md` are the mandated orientation path — "Inspect the current
implementation and its closest tests before designing a change" (step 3) is
the only appearance of the string "inspect" in that file, and it is the
English verb, not the module.

`:inspect` is the Inspector backend — a read-only HTTP/SSE view of a host
process's live dataflow graph, delivered end-to-end by the 97-inspector-plan
run (`doc/spec/90-roadmap/97-inspector-plan/`, all six milestones M0–M5
merged; closing note in `90-progress-log.md`). It is ~8k lines of Kotlin
(main+test) plus its SolidJS/Vite frontend `inspect/ui/` (~22k lines TS), is
`include(":inspect")`d in `settings.gradle.kts`, and is consumed by
`demo/shopping/build.gradle.kts` and `demo/skillmatch/build.gradle.kts` (both
via `implementation(project(":inspect"))`, gated behind `--inspect-port`,
opt-in, default off). It required five new kernel accessors added
specifically for it: `ManagedHost.outletAt` (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1024`),
`ManagedHost.snapshotOf` (`:1054`), `ManagedHost.isDrained` (`:204`),
`ManagedHost.isSuspended` (`:231`), and `LocationRegistry.describe`
(`kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt:181`).

Despite all of that, `:inspect` appears zero times in `doc/ARCHITECTURE.md`
(`grep -c -i inspect doc/ARCHITECTURE.md` → `0`) and zero times in `README.md`
(`grep -c -i inspect README.md` → `0`); `AGENTS.md`'s two hits are both the
verb "inspect" (lines 25 and 168), never the module. `doc/ARCHITECTURE.md:3`
still pins itself "Snapshot as of commit `742f7ca` (2026-07-27)" — the commit
before the inspector plan started; `HEAD` is currently `dcfbb33`
(2026-07-28), 168 commits later.

The guardrail this ticket closes already exists and is green only by
exception: `kernel/src/test/kotlin/civictech/cell/architecture/ModuleInventoryTest.kt`
(finding B4/guardrail G2 from `doc/architecture-decisions.md`) asserts every
`include(...)` in `settings.gradle.kts` is named in `doc/ARCHITECTURE.md`,
with `companion object { val documentedExceptions = setOf(":inspect") }`
(lines 33–35) as the sole permitted gap. The test's own KDoc (lines 17–22)
states the amendment policy: "Shrinking `documentedExceptions` is always
allowed (and expected)." `doc/architecture-decisions.md` finding A3 (line 35)
records this as the audit finding this ticket resolves; guardrail G2
(lines 76–79) records the exception as scoped to A3 and says "the exception
is deleted by W3" — this ticket is that deletion.

`doc/architecture-decisions.md` finding A3 in full:

> `:inspect` (largest delta module) absent from `doc/ARCHITECTURE.md`,
> `AGENTS.md`, `README.md` — defeats the mandated orientation path; enabled
> the ratchet gap

## Problem

1. `doc/ARCHITECTURE.md` §1's module graph (lines 18–32) and module table
   (lines 39–48) have no `:inspect` row, no arrow from `:inspect` to
   `:kernel`/`:demo:shell`, and no mention of the five kernel accessors it
   consumes. The `:demo:*` row (line 48) says "Only `:demo:shopping` and
   `:demo:exchange` use `:wire`" but never mentions that `:demo:shopping` and
   `:demo:skillmatch` now depend on `:inspect` — true today per both demos'
   `build.gradle.kts`.
2. `doc/ARCHITECTURE.md`'s `:demo:shell` row (line 47) says it is "used by
   every runnable demo" — true, but incomplete: `inspect/build.gradle.kts:15`
   (`implementation(project(":demo:shell"))`) makes `:inspect` a consumer
   too, and `:inspect` is not a demo.
3. `demo/shell/build.gradle.kts:5–6` carries a comment — "`:demo:shell` is
   consumed as `implementation` by the demo modules — the JDK httpserver +
   SSE boilerplate duplicated verbatim across their mains" — that is now
   false by omission: `:inspect` consumes it too, and `:inspect` is
   explicitly not a demo module (`inspect/build.gradle.kts:7`: "It reuses
   `:demo:shell`'s JDK-httpserver + SSE framing rather than duplicating it").
4. `AGENTS.md`'s "Repository map" (§"Repository map", lines 41–90) has no
   bullet for `inspect/` or `inspect/ui/` alongside its existing bullets for
   `kernel/`, `nature/`, `gen/`, `testkit/`, `wire/`, `concord/`, `demo/`.
5. `README.md` has no line telling a reader how to run the inspector
   (`--inspect-port`) and no CI status badge, even though `.github/workflows/ci.yml`
   (job name `CI`, workflow file `ci.yml`) has existed and run since the W1
   package of this same audit.
6. `ModuleInventoryTest.documentedExceptions` (lines 33–35) still contains
   `":inspect"`, so the guardrail currently passes only because it excuses
   the exact gap it exists to catch.

## Solution direction

1. **`doc/ARCHITECTURE.md`**:
   - Add `:inspect` to the ASCII module graph (§1, lines 18–32): an edge from
     `:kernel` and from `:demo:shell` into `:inspect`.
   - Add an `:inspect` row to the module table (§1, lines 39–48): purpose (the
     Inspector backend — read-only HTTP/SSE view of a host process's live
     dataflow graph), deps `:kernel` + `:demo:shell` (main scope) +
     `kotlinx.serialization`, a note that its frontend `inspect/ui/` is npm/Vite
     and deliberately not wired into Gradle (same decision as `demo/agora/ui`),
     and a sentence naming the five kernel accessors it consumes
     (`ManagedHost.outletAt`/`snapshotOf`/`isDrained`/`isSuspended`,
     `LocationRegistry.describe`) with a pointer to
     `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md` for their
     rationale.
   - Re-true the `:demo:*` row: note that `:demo:shopping` and
     `:demo:skillmatch` additionally depend on `:inspect` (opt-in,
     `--inspect-port`).
   - Re-true the `:demo:shell` row's consumer claim to include `:inspect`.
   - Re-stamp the header snapshot line (line 3) to the commit this ticket
     branches from. `HEAD` is `dcfbb33` (2026-07-28) at ticket-authoring time;
     confirm with `git rev-parse --short HEAD` at branch-creation time since
     this is a multi-ticket wave and other tickets may land first.
2. **`AGENTS.md`**: one new bullet in "Repository map" (after the `demo/`
   bullet block, alongside the existing per-directory bullets) for `inspect/`
   — the Inspector backend module — plus a clause noting its `inspect/ui/`
   npm/Vite frontend, mirroring how the `demo/agora/` bullet already
   parenthesizes `demo/agora/ui/`.
3. **`README.md`**:
   - Add a "run the inspector" line/section: `--inspect-port` on a demo that
     supports it (`skillmatch` or `shopping`), pointing to the recipe in
     `inspect/build.gradle.kts`'s header comment and `inspect/ui/README.md`
     ("Run" section: `./gradlew :demo:skillmatch:run --args="8080
     --inspect-port 7071"` then `cd inspect/ui && npm install && npm run
     dev`).
   - Add the CI status badge for the existing `CI` workflow
     (`.github/workflows/ci.yml`, workflow `name: CI`). Badge URL pattern:
     `https://github.com/mboogerd/computenet/actions/workflows/ci.yml/badge.svg`
     (confirm the GitHub owner/repo slug against `git remote get-url origin`
     at implementation time — this ticket does not modify `.github/**`, only
     README's reference to it).
4. **`kernel/src/test/kotlin/civictech/cell/architecture/ModuleInventoryTest.kt`**:
   delete `":inspect"` from `documentedExceptions` (lines 33–35), leaving the
   set empty (`setOf()`) or the whole property empty per Kotlin style — do
   not delete the mechanism itself, only the now-satisfied exception.
5. **`demo/shell/build.gradle.kts`**: fix the stale comment at lines 5–6 so it
   no longer implies only "demo modules" consume `:demo:shell` — name
   `:inspect` as a consumer too.

Latitude: prose wording throughout. Keep `doc/ARCHITECTURE.md`'s existing
voice and density — terse, table/prose mixed, cross-referencing other spec
files by path rather than restating them.

## Files expected to touch

- `doc/ARCHITECTURE.md` — `:inspect` in the module graph and module table;
  re-true `:demo:*` and `:demo:shell` rows; re-stamp header snapshot commit.
- `AGENTS.md` — one repository-map bullet for `inspect/` (+ `inspect/ui/`).
- `README.md` — "run the inspector" line/section; CI status badge.
- `kernel/src/test/kotlin/civictech/cell/architecture/ModuleInventoryTest.kt` —
  delete `":inspect"` from `documentedExceptions` only. Do not touch the rest
  of the test.
- `demo/shell/build.gradle.kts` — comment fix only (lines 5–6). Do not touch
  the `dependencies` block.

Touching files outside this list: note it in the completion report rather
than expanding silently. Parallel work is scheduled on this claim.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §"W3 — Re-true the record", item 1 —
  the work package this ticket implements.
- `doc/architecture-decisions.md` finding A3 (line 35) and guardrail G2
  (lines 76–79) — why this gap exists and the exact closure condition
  (`documentedExceptions` emptied).
- `doc/ARCHITECTURE.md` §1 (lines 16–61) — the module graph and table to
  extend; match its existing terseness and cross-reference style.
- `kernel/src/test/kotlin/civictech/cell/architecture/ModuleInventoryTest.kt` —
  read the whole file; the `documented(module)` helper (lines 59–62) is how
  the doc gets checked, so the `:inspect` row must literally contain the
  backtick-quoted string `` `:inspect` `` to satisfy it.
- `inspect/build.gradle.kts` lines 7–12 — the module's own rationale comment
  (deps, why it reuses `:demo:shell`, why the frontend is unwired) to source
  the module-table prose from.
- `demo/shell/build.gradle.kts` lines 5–15 — the comment to fix, and the
  existing `kotlinx-serialization-json` paragraph immediately below it as a
  style match.
- `inspect/ui/README.md` "Run" section — the exact commands to cite for
  README's inspector line.
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md` — the
  "Orchestrator closing note (2026-07-28)" at the end confirms all six
  milestones are merged and is the rationale pointer for the five kernel
  accessors.
- `settings.gradle.kts` lines 12–27 — the full `include(...)` list
  `ModuleInventoryTest` parses; useful to sanity-check no other module is
  newly missing while you're in this file.

Do not modify: `.github/**` (T13 owns `ci.yml`), `doc/spec/**` (T15),
`inspect/` source (Kotlin or `ui/`) — this ticket is documentation-only plus
the one test-exception deletion and one comment fix.

## Acceptance criteria

- [ ] `doc/ARCHITECTURE.md`'s module table (§1) has an `:inspect` row with
      purpose, deps (`:kernel`, `:demo:shell`), the `inspect/ui` npm/Vite
      note, and the five named kernel accessors with a pointer to
      `90-progress-log.md`.
- [ ] `doc/ARCHITECTURE.md`'s ASCII module graph (§1) shows `:inspect` with
      edges from `:kernel` and `:demo:shell`.
- [ ] `doc/ARCHITECTURE.md`'s `:demo:*` row states that `:demo:shopping` and
      `:demo:skillmatch` depend on `:inspect`, verified true against
      `demo/shopping/build.gradle.kts` and `demo/skillmatch/build.gradle.kts`.
- [ ] `doc/ARCHITECTURE.md`'s `:demo:shell` row names `:inspect` as a
      consumer, verified true against `inspect/build.gradle.kts`.
- [ ] `doc/ARCHITECTURE.md:3`'s snapshot commit is re-stamped to the commit
      this ticket's branch actually starts from.
- [ ] `AGENTS.md` has a repository-map bullet naming `inspect/` and
      `inspect/ui/`.
- [ ] `README.md` has a line describing how to run the inspector
      (`--inspect-port`) and the CI status badge for the `CI` workflow,
      rendering (correct workflow file name `ci.yml`, correct owner/repo
      slug).
- [ ] `grep -ci inspect README.md`, `grep -ci inspect AGENTS.md`,
      `grep -ci inspect doc/ARCHITECTURE.md` each return nonzero, and at
      least one hit per file names the `:inspect`/`inspect/` module (not only
      the English verb).
- [ ] `ModuleInventoryTest.documentedExceptions` is empty and
      `civictech.cell.architecture.ModuleInventoryTest` passes.
- [ ] `demo/shell/build.gradle.kts`'s comment no longer implies `:demo:shell`
      is consumed only by demo modules.
- [ ] No unrelated files in the diff (no `.github/**`, no `doc/spec/**`, no
      `inspect/` source changes).

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.architecture.*'
grep -ci inspect README.md AGENTS.md doc/ARCHITECTURE.md
```

## Report on completion

- Checks run and their results (test output for `ModuleInventoryTest`, grep
  counts for all three docs).
- Files actually touched, and any not in the claim above.
- The exact commit hash `doc/ARCHITECTURE.md:3` was re-stamped to, and
  whether it matched `HEAD` at branch-creation time or diverged (e.g. because
  another wave-1 ticket landed first).
- Anything specified here you could not do, and why.
