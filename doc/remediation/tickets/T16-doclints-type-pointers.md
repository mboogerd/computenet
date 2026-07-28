# T16 — `DocLints.checkPackagePointers` resolves type-level spec pointers, not just directories

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 2 · **Branches:** `ticket/T16`

## Context

`concord/` hosts the executable specification tooling. Beside the
`concordanceGate`, `DocLints` (`concord/src/main/kotlin/civictech/concord/lint/DocLints.kt`)
runs three doc-integrity lints wired into `:concord:check`, per its file-header
KDoc (`DocLints.kt:6-24`). Lint 1, package-pointer resolution, is documented
there (`DocLints.kt:12-15`) as: every backticked `cell.<pkg>.<Type>` reference
in `doc/spec/**/*.md` "must resolve, at the directory level, to a real
`kernel/src/main/kotlin/civictech/cell/<pkg>/` package." `COVERAGE.md` counts
this lint class as closed under T02-A3/T02-C1.

This ticket runs in **Wave 2** because ticket **T15** must land first: T15
fixes the two known stale citations that this tightened lint would otherwise
turn into build failures on landing —
`doc/spec/30-execution-model/34-scheduling.md:9` (`cell.host.AttentionPolicy`,
moved to `cell.control` by T11-C; verified live in the tree today — see
Problem) and the G-63 stale move-proposal it references. If T16 merges before
those citations are fixed, `:concord:check` goes red on files this ticket
does not own.

## Problem

`DocLints.checkPackagePointers` (`DocLints.kt:70-91`) extracts the package
path from a pointer and checks only directory existence:

```kotlin
val pkgPath = pkgDotted.replace('.', '/')
val dir = File(kernelCellRoot, pkgPath)
if (!dir.isDirectory && reportedPerFile.add(pkgDotted)) { ... }
```

The regex `packagePointer` (`DocLints.kt:51`) deliberately captures only the
lowercase package segments and stops before the trailing PascalCase type
segment — the type name itself is never checked against anything. A pointer
whose final segment is a type that moved to a different file within the same
package directory, or whose package directory still exists for unrelated
reasons, passes green as long as the directory is real.

This is a live, demonstrated blind spot, not a hypothetical: `34-scheduling.md:9`
currently reads `` `cell.host.AttentionPolicy` `` (and `:12`, `:308` reference
`AttentionPolicy` in the same stale context), but `AttentionPolicy` was moved
from `.host` to `.control` by T11-C — the actual declaration is now at
`kernel/src/main/kotlin/civictech/cell/control/AttentionPolicy.kt`. The lint
passes today because `cell/host/` still exists as a directory (other types
still live there). `doc/architecture-decisions.md` finding B12 records the
same class of failure already having landed once for real: `42-replication.md`
described the deleted `WritePosture` API while `DocLints` stayed green
throughout. Because `COVERAGE.md` marks this lint class closed, its green
status produces institutionalized false confidence — exactly the failure mode
finding B12 and audit item W3.4 (`doc/remediation/AUDIT-2026-07-28.md:87-90`)
call out.

## Solution direction

In `checkPackagePointers`, after the existing directory check passes (or in
place of it, if directory absence and unresolved-type both need reporting —
implementer's call, but do not weaken the directory check), also resolve the
pointer's **type segment**:

- The type segment is already available from the existing match: `packagePointer`
  matches `cell\.((?:[a-z][a-zA-Z0-9_]*\.)+)[A-Z][A-Za-z0-9_]*` — group 1 is
  the package path; the PascalCase suffix consumed by `[A-Z][A-Za-z0-9_]*` is
  the type name (capture it, e.g. by adding a second group, or by re-deriving
  it from `m.value`).
- Search the `.kt` files directly under the resolved package directory (the
  same `dir` the directory check already computed) for a declaration matching
  the type name: `(class|interface|object|enum class|typealias|fun|val)\s+<Name>\b`
  (word-boundary so `AttentionPolicy` doesn't spuriously match
  `AttentionPolicyImpl`). Latitude on exact regex shape; annotation classes
  and backtick-quoted names may be handled as encountered rather than
  pre-emptively — the corpus is small, keep this simple, no need for a full
  Kotlin parser.
- If the package directory resolves but no file in it declares the type,
  emit a `Finding(Severity.FATAL, ...)` naming the pointer, the chapter file
  (relative path, matching the existing message style), and the directory
  searched — mirror the phrasing of the existing directory-miss message at
  `DocLints.kt:81-85`.
- Keep the existing package-only-pointer behavior unchanged: pointers with no
  resolvable PascalCase type segment (there are none today per the regex,
  but keep this in mind if you restructure the regex) or whose directory does
  not exist at all should still report exactly as today — do not report both
  a directory-miss and a type-miss for the same pointer.
- Where the declaration-scan helper lives (private function in `DocLints`
  object vs. a small top-level helper) is left to the implementer; keep it
  next to `checkPackagePointers` since nothing else needs it yet.
- Update the KDoc at `DocLints.kt:12-15` to describe the tightened contract
  (type-level resolution, not directory-level only).

Do not change the density (lint 2) or status-header (lint 3) checks, and do
not change `packagePointer`'s two-segment exclusion behavior (bare
`cell.Handle`-style pointers, tested at `DocLintsTest.kt:76-83`, must keep
passing unchanged).

## Files expected to touch

- `concord/src/main/kotlin/civictech/concord/lint/DocLints.kt` — tighten
  `checkPackagePointers` to resolve the type segment; update its KDoc.
- `concord/src/test/kotlin/civictech/concord/lint/DocLintsTest.kt` — extend
  the package-pointer-resolution section (`:26-93`) with new cases (see
  Acceptance criteria).

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md:71-97` (§W3, esp. item 4 at :87-90) —
  the decided solution direction this ticket implements, and why it is
  scoped as "resolve the final PascalCase segment to a declared type,"
  including the exact grep-style pattern cited there.
- `doc/architecture-decisions.md:47` (finding B12) — severity, exact code
  pointer (`DocLints.kt:70-91`), and the fact that the two live stale
  citations are a T15 dependency, not this ticket's job.
- `concord/src/main/kotlin/civictech/concord/lint/DocLints.kt:1-91` — the
  full lint file header KDoc and the current `checkPackagePointers`
  implementation, plus the `packagePointer` regex (`:51`) whose match groups
  the new code will read from.
- `concord/src/test/kotlin/civictech/concord/lint/DocLintsTest.kt:1-93` — the
  existing package-pointer-resolution tests and their tiny-fixture idiom
  (`@TempDir`, `specDir()`/`kernelCellDir()`/`writeSpec()`) to mirror for the
  new cases; note the class KDoc's rationale (`:10-14`) for never pointing
  these tests at the real, evolving `doc/spec` tree.
- `doc/spec/30-execution-model/34-scheduling.md:9,12,308` — the real,
  currently-stale `cell.host.AttentionPolicy` citation this tightened lint
  will catch once landed; confirms the failure mode is live, not
  hypothetical. Do not fix this file — that is T15's job, already assumed
  merged by the time this ticket lands.

Do not modify: `doc/spec/**` (T15 owns fixing the citations there; this
ticket must not touch spec prose), any corpus YAML under `concord/corpus/**`,
`kernel/**`.

## Acceptance criteria

- [ ] `checkPackagePointers` resolves a pointer's final PascalCase segment
      against declarations (`class|interface|object|enum class|typealias|fun|val`)
      in the `.kt` files of the resolved package directory, not just the
      directory's existence.
- [ ] New test: a package pointer whose type is genuinely declared in the
      resolved directory is not flagged (passing type pointer).
- [ ] New test: a package pointer whose directory exists but whose type is
      declared nowhere in it (synthetic moved-type fixture, e.g. modeled on
      the real `AttentionPolicy`/`.host`→`.control` move) is a `FATAL`
      finding naming the pointer, the chapter file, and the directory
      searched.
- [ ] New test: a package-only pointer (existing directory-level behavior,
      e.g. mirroring `DocLintsTest.kt:28-36`) continues to pass unaffected by
      the new type-resolution logic.
- [ ] All pre-existing `DocLintsTest` cases in the package-pointer-resolution
      section (`:26-93`) still pass unmodified in intent (edits allowed only
      if the fixture's type name now needs a real backing declaration to stay
      green — check each one).
- [ ] Lint passes against the real spec tree post-T15 (run the `:concord:docLints`
      path / `:concord:check`; if it fails, the failure must be a genuine new
      stale pointer, not a false positive from this ticket's logic — report
      either outcome, do not silently work around a real finding by loosening
      the regex).
- [ ] `./gradlew :concord:check` green.
- [ ] No unrelated files in the diff (no `doc/spec/**`, no `concord/corpus/**`,
      no `kernel/**`).

## Verify

```bash
./gradlew :concord:test :concord:check
```

## Report on completion

- Checks run and their results (`:concord:test`, `:concord:check`).
- Files actually touched, and any not in the claim above.
- Whether the real spec tree (post-T15) passed cleanly, or surfaced a new
  genuine stale pointer — and if so, which one.
- Anything specified here you could not do, and why.
