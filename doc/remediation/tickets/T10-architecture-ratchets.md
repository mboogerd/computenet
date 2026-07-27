# T10 — Architectural conformance ratchets

**Phase 2 · parallel with T07/T08/T09/T12 · fresh session · Sonnet 5**
**Prereq**: T03 merged (dead imports deleted — the baseline must pin the real
edge set, not ghosts).
**Write scope**: `concord/src/test/**` (one file deletion-edit + one new
test), `kernel/src/test/**` (two new tests + one checked-in baseline
resource), `doc/ARCHITECTURE.md` (one paragraph documenting the ratchets).
**Do not touch**: any production source. This ticket is test-code only. No
new dependencies (no ArchUnit/Konsist) — plain JUnit + file walking, matching
the repo's existing `concordanceGate` make-the-invariant-executable idiom.

## Problem

Modularity + encapsulation audits (verified 2026-07-27 at `742f7ca`): the
project's load-bearing boundaries are prose, not checks — an inconsistency in
its own methodology (it enforces its *harder* invariants executably via
`concordanceGate`).

1. **The concord neutrality rule is comment-only and has already drifted.**
   Stated three times ("only `civictech.concord.driver.kernel` may import
   `civictech.cell.*`": `concord/build.gradle.kts:8-10`,
   `doc/ARCHITECTURE.md:273`, `AGENTS.md`), enforced nowhere. One violation
   already exists — `concord/src/test/kotlin/civictech/concord/provenance/ConcordanceTest.kt:10`
   imports `civictech.cell.data.op.UnionSetCell` (dead: used only inside a
   fixture string at :63). The whole L0–L4 layering assumes L1/L2/L4 are
   kernel-blind; a *live* leak would be silent until a second driver binding
   is attempted.
2. **No check keeps demos on the intended application surface.** Demos
   currently import only `.host`/`.port`/`.graph`/`.data*`/`.observe`/`.link`
   (verified clean) — but with `:kernel` at 323 public / 20 internal
   declarations, nothing prevents the next demo from reaching into
   `.control`/`.consistency`/`.protocol`/`.proxy` internals.
3. **The kernel package graph is one 20-package SCC and unguarded.** The
   spec's claimed layering doesn't physically exist (T02 filed the marker).
   The realistic goal is not acyclicity — several cycles are mutually
   recursive ADTs — but a **ratchet**: pin the current edge set and fail on
   any new edge, converting an unbounded problem into a bounded one.

## Solution

### A. Concord import ban (executable)

1. Delete the dead import at `ConcordanceTest.kt:10` (keep the fixture
   string).
2. New test in `concord/src/test` (e.g. `provenance/NeutralityGateTest`):
   walk `concord/src/{main,test}/kotlin/**/*.kt`; for each file whose
   `package` line is not `civictech.concord.driver.kernel` (nor a sub-package
   of it), fail if any line matches `^import civictech\.cell`. Failure
   message lists file + line. Runs with the normal test task, so
   `:concord:check` gates it.

### B. Demo surface allowlist

New test (put it in `kernel/src/test` — it walks sibling module sources via
the repo root; resolve the root robustly, e.g. walk up from
`System.getProperty("user.dir")` to the directory containing
`settings.gradle.kts`): for every `demo/*/src/main/**/*.kt`, imports matching
`civictech.cell.*` must fall in the allowed prefixes:
`civictech.cell` (root vocabulary), `.host`, `.port`, `.graph`, `.data`,
`.observe`, `.link`, `.wire` (exchange/shopping legitimately use `Peering`),
plus `civictech.testkit`. Seed the allowlist from the *current* clean state
(verify by running); a violation names file, import, and the allowlist
location.

### C. Kernel layering ratchet

1. New test `kernel/src/test/.../ArchitectureRatchetTest`: parse every
   `kernel/src/main/**/*.kt`, build the set of directed package→package
   edges from live imports (`civictech.cell.X` importing `civictech.cell.Y`,
   package = first segment after `cell`; ignore intra-package).
2. Compare against a checked-in baseline resource
   (`kernel/src/test/resources/architecture/package-edges.txt`, one
   `from -> to` per line, sorted). **Generate the baseline from the current
   post-T03 tree** and commit it.
3. Assertions, in this order:
   - fail on any edge present in code but absent from the baseline
     ("new cross-package dependency — either revert it or consciously add it
     to the baseline in the same PR, citing why");
   - **warn-only** (print, don't fail) on baseline edges no longer present —
     then tighten: the test rewrites nothing automatically; instruct via
     message to delete the stale line so the ratchet only tightens.
4. KDoc the test with the SCC context and the marker id T02 filed, so a
   reader knows the baseline is a ratchet, not an endorsement.

### D. Document

One short paragraph in `doc/ARCHITECTURE.md` (§1 or §5): the three
executable boundary checks, where they live, and the rule for changing a
baseline (same-PR, cited reason).

## Verification

```bash
./gradlew :concord:test :concord:check
./gradlew :kernel:test --tests '*ArchitectureRatchetTest' --tests '*Neutrality*'
./gradlew test
```

Prove each ratchet can fail: temporarily add a forbidden import in a scratch
edit, observe the failure message quality, revert (do not commit the
scratch).

## Report

Baseline edge count; the failure-message text of each ratchet (paste one);
confirmation the can-fail check was performed for all three.
