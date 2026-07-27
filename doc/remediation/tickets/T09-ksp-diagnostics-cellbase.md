# T09 — KSP module boundary, diagnostics & @CellBase honesty

**Phase 2 · parallel with T07/T08/T10/T12 · fresh session · Sonnet 5**
**Prereq**: Phase 1 merged (T01 already deleted `:gen-test` and created the
`buildsrc.convention.ksp-cell` plugin).
**Write scope**: `gen/**`, `nature/**`, `kernel/build.gradle.kts` (one
dependency line), `demo/backlog-triage/**` (RatingCell migration),
`demo/agora/build.gradle.kts` (dead KSP block), `README.md` (authoring
section), `doc/ARCHITECTURE.md` (the invalidated claims),
`doc/ksp-dx-catalog.md` (status corrections).
**Do not touch**: kernel `src/main` sources except recompequirements forced by
the `:gen` move (imports should be unaffected if package names are kept).

## Problem

Modularity + API/DX + YAGNI + SRP audits (verified 2026-07-27 at `742f7ca`):

1. **The kernel's runtime classpath ships the code generator (high,
   trivially fixable).** `kernel/build.gradle.kts:14`
   `implementation(project(":gen"))` drags KotlinPoet 2.1.0,
   `symbol-processing-api`, and `kotlin-reflect` onto the runtime classpath
   of `:kernel` and every consumer. Kernel imports exactly four symbols from
   `:gen`: `Contract` (17×), `CellBase` (18×), `Protocol` (6×),
   `ProxyRegistry` (1×) — 113 lines out of 967; the 854-line processor rides
   along. `:nature` exists precisely for shared processor-time/runtime
   vocabulary (`doc/ARCHITECTURE.md:36`) and these types were left behind.
2. **The advertised authoring loop has no users, and the docs lie about it
   (high).** `README.md:144-163` presents `@CellBase` as *the* way to define
   a cell and says to copy `demo/agora/build.gradle.kts` — but agora and
   backlog-triage contain **zero** `@CellBase`/`@Contract` annotations and
   KSP emits nothing for them (`build/generated/` absent).
   `doc/ARCHITECTURE.md:44` ("only agora and backlog-triage define their own
   KSP cells") is factually wrong. Real demo cells hand-roll ports +
   `onEach` + unchecked-cast snapshot/restore
   (`demo/agora/.../ClaimCell.kt:36-108`,
   `demo/backlog-triage/.../RankingCells.kt:57-131`).
3. **Silent-misbehavior diagnostics are warnings, and the warn paths are
   untested (high).** `gen/.../ContractProcessor.kt:445`: unresolvable
   `@CellBase` payload → `logger.warn` — the port is declared but no
   handler binding is generated: **your inlet accepts messages and drops
   them**. Same at :423. `ContractProcessorTest` asserts 5 error paths
   (good) but zero of the four warn paths (:422, :445, :512, :542) and none
   of the three protocol-contract errors (:206, :210, :212).
4. **`ContractProcessor.process()` is one 301-line method emitting six
   artifact families with five inline lints (high, SRP audit).**
   `ContractProcessor.kt:82-382`: round-1 gating, discovery, three inline
   `buildCodeBlock` table builders (contract :139-198, protocol :200-221,
   cell :232-295) interleaved with five lints, two ServiceLoader
   registrations, proxy-table assembly, ports-object emission. The rest of
   the file is well-factored; the problem is entirely `process()`. Every new
   descriptor field edits the same inline blocks; testing one lint requires
   driving the full two-round processor.
5. **Descriptor fields shipped without consumers (low, YAGNI audit).**
   `ProtocolDescriptor.lane`/`.cardinality`
   (`nature/.../ContractDescriptor.kt:172,304-312`): declared at six
   `@Protocol` sites, emitted into every table, asserted by
   `PortProtocolDispatchTest.kt:28-39` — **consulted by nothing** at
   runtime. `Manifest.PULL_SERVING`/`GATED` constants cannot be produced at
   all (`manifestOf`, `kernel/.../nature/CellManifest.kt:26-33`, derives
   only the other four; `GATED`'s would-be source — the Gate policy — was
   deleted by T03).

## Solution

### A. Move the runtime vocabulary to `:nature` (finding 1)

1. Move `Contract.kt`, `CellBase.kt`, `ProxyRegistry.kt` from
   `gen/src/main/.../wire/` into `:nature`, **keeping the
   `civictech.gen.wire` package name** so generated code and all 41 kernel
   import sites are untouched (a package name not matching the module is
   acceptable here; note it in the file headers).
2. `kernel/build.gradle.kts`: delete `implementation(project(":gen"))`; keep
   `ksp(project(":gen"))`. `:gen` already depends on `:nature`, so the
   processor still sees the annotations.
3. Verify no other module needed `:gen` at compile time
   (`grep -rn 'project(":gen")' --include='*.kts' .` → only `ksp(...)`
   scopes remain). Update the ARCHITECTURE module table (`:gen` row, `:nature`
   row).

### B. Diagnostics: warn → error, and test every path (finding 3)

1. Promote `ContractProcessor.kt:445` and `:423` to `logger.error` (a
   silently-unbound inlet is not a warning). If any in-tree cell currently
   trips them, that is a real latent bug — fix the cell, don't soften the
   diagnostic.
2. Add `ContractProcessorTest` cases for all four (now-error) diagnostic
   paths and the three protocol errors, asserting exit code + message
   substring, matching the existing test style (:30-32 etc.).

### C. Give `@CellBase` its first real consumer (finding 2)

1. Migrate `RatingCell` (`demo/backlog-triage/.../RankingCells.kt:57`) to
   `@CellBase` — the honest candidate (`MetaRankCell`'s runtime-list ports
   structurally can't, and must stay hand-rolled; say so in a comment).
   Backlog-triage's build already has the KSP plumbing (per T01's convention
   plugin).
2. **Decision rule**: if migration hits a v1 ceiling
   (`doc/ksp-dx-catalog.md:420-422` — e.g. subclass descriptor rows), do
   NOT force it: revert, document the exact ceiling in the catalog §6b with
   the failing shape, and instead point the README at a kernel cell that
   genuinely uses `@CellBase`. Either way the README example must reference
   code that actually exercises the path.
3. `demo/agora/build.gradle.kts`: agora defines no annotated cells — remove
   its dead KSP block (added back the day it annotates something).
4. Fix `doc/ARCHITECTURE.md:44` to match whichever end-state (2) produced.
   Correct `doc/ksp-dx-catalog.md`'s three overstated LANDED claims (Phase 0
   raw-ctor migration — 15 sites remain; Phase 3 `<Cell>Ports` adoption;
   §6b adoption) from "landed" to "landed-in-gen, unadopted at call sites"
   — the catalog is trustworthy as design record, unreliable as status
   record; make status truthful. (Call-site raw-ctor migration itself is
   T11.)

### D. Extract lints and table builders (finding 4)

Mechanical, pinned by the existing 376-line `ContractProcessorTest`:

1. Five lints → a `ContractLints` object taking
   `(KSClassDeclaration, KSPLogger)`; unit-test the lints directly.
2. The three inline `buildCodeBlock` builders → private methods
   `contractTable()`, `protocolTable()`, `cellTable()`, matching the
   existing `generateProxyClass` style. `process()` drops to ~120 lines of
   round orchestration. **Generated output must be byte-identical** — assert
   via the existing golden tests; if none pin full output, add one before
   refactoring.

### E. Delete the consumer-less descriptor fields (finding 5)

1. Remove `lane` and `cardinality` from `ProtocolDescriptor`, the
   `@Protocol` annotation, the six declaration sites, the emission code, and
   the `PortProtocolDispatchTest:28-39` assertions. This is a
   generated-table schema change: per AGENTS.md the descriptor shape is a
   runtime contract — the full verification battery below is mandatory.
2. Delete `Manifest.PULL_SERVING` and `Manifest.GATED` constants (both
   unproducible; `GATED`'s mechanism was deleted in T03). The spec keeps the
   concepts; code re-adds them with their first consumer.

## Verification

```bash
./gradlew :gen:test
./gradlew :kernel:compileKotlin        # the T01 gate now runs :gen:test first
./gradlew :kernel:test
./gradlew test                          # descriptor schema change ⇒ full gate
./gradlew :demo:backlog-triage:test
./gradlew :concord:test -Pconcord.profiles=core,dist,dur
```

## Report

The A dependency-tree before/after (one `./gradlew :kernel:dependencies
--configuration runtimeClasspath` line each); the C decision taken (migrated
vs ceiling-documented, with the ceiling if hit); any cell the promoted
diagnostics flagged; confirmation of byte-identical generation for D.
