# T03 — Dead-code deletion & encapsulation tightening

**Phase 0 · parallel with T01/T02 · fresh session · Sonnet 5**
**Write scope**: Kotlin sources under `kernel/src/{main,test}`,
`nature/src/main`, plus the specific doc sentences these deletions invalidate
(`doc/ARCHITECTURE.md` mentions of deleted types).
**Do not touch**: any `build.gradle.kts` / `libs.versions.toml` (T01 owns
those), `demo/**` sources, `gen/**` (T09 owns it), `doc/spec/**` (T02 owns it).

## Problem

Audit findings (YAGNI + encapsulation + modularity + concurrency audits,
verified 2026-07-27 at `742f7ca`). Common shape: **declared knobs that are
silently ignored** (worse than absent — they invite false confidence), dead
alternatives that duplicate live invariants, and mutable state left publicly
writable by oversight.

Verified-dead items (grep evidence in the audit; re-verify before deleting):

1. `WritePosture.SAFETY_PARK` + `parkWrites`/`resumeWrites` + the `posture`
   field (`kernel/.../replication/SingleWriterReplication.kt:42,153,181,222-230`)
   — zero callers anywhere, ever. A safety-critical-looking branch with zero
   execution history. Its own KDoc concedes the detection half (G-44) never
   shipped.
2. `BoundaryPolicy.admission` / `PeerPredicate`
   (`kernel/.../membrane/BoundaryPolicy.kt:39-46,118`) — a **security
   predicate nothing consults**. Its four siblings are all wired into
   `CompositeCell`/`MediateProxy`; `admission` has no read site, and
   `forcesMediate` (:133) explicitly excludes it. Declaring
   `BoundaryPolicy(admission = onlyTrusted)` compiles and silently changes
   nothing. The real seam-1 gate is `Peering.Allowlist` (`wire/Peering.kt:76`).
3. `WaveScope` / `REMINT` (`kernel/.../membrane/CompositeCell.kt:36,52,140,152,189,202`)
   — written by three constructor paths, read by none; pollutes `Exposure`
   equality. "REMINT specified, not implemented" per its own comment.
4. `Broadcast` + `broadcast<>()` (`kernel/.../proxy/Handlers.kt:66-87`) — an
   alternative fan-out that lost to `FanOutlet` (which iterates its own
   subscriber map, `FanOutlet.kt:187`) and carries a **duplicate copy of the
   spec-23 exclusivity invariant**. Only reference: `OwnershipTest.kt:159`,
   which tests the unreachable copy.
5. `Throwing` + `throwing<>()` (`Handlers.kt:23-34`) — advertises "unmounted
   inputs throw", contradicting the actual park-and-replay model (`FanInlet`
   cold-parks via `Buffering`, `FanInlet.kt:87-88`). Only reference:
   `ProxyGenerationTest.kt:46` (tests the registry, not the behavior).
6. `Gate` + `PolicyTier.GATE` (`kernel/.../port/InletPolicy.kt:115-142`) —
   the GATE tier has **zero production installs** (only
   `InletPolicyStackTest.kt:80`); real backpressure lives in
   `IntakeControl`/`ParkQueue`. Keep `Admit` (T05 fixes it — it carries the
   CP-A3 law).
7. `PortRegistry.inlet(name)`/`outlet(name)` (`kernel/.../port/PortRegistry.kt:51-66`)
   — zero users anywhere including their author ("Adopt at will" adopted by
   no one).
8. **39 dead cross-package imports** — imports of `civictech.cell.*` whose
   simple name appears only in comments/KDoc. Known list includes
   `link/Interest.kt:4-5`, `data/delta/SetDelta.kt:6`, `control/ParkQueue.kt:3`,
   `Serializers.kt:16`, `replication/InstanceSet.kt:12-20` (5),
   `data/view/SetView.kt:6-8`, `observe/Observe.kt:17-19`,
   `data/KeyedSetCell.kt:14-17`. They double the *apparent* package coupling
   (19 → 10 two-package cycles when filtered) and would make any future
   layering ratchet fire on ghosts (T10 depends on this cleanup).
9. `ProtocolTraversal` hot-path waste (`kernel/.../protocol/Protocols.kt:143-146,202-207`)
   — `UUID.randomUUID()` (contended SecureRandom) + a ConcurrentHashMap-backed
   set allocated on **every** protocol delivery (incl. every absorb-ack),
   while the `epoch` field is never read and only one protocol (`Saturation`)
   registers a relay.

Encapsulation oversights (verified zero external writers/readers where
claimed):

10. `AttentionScheduler.dispatchStep` (`control/AttentionScheduler.kt:53`) and
    `WaveFrontier.unmatchedDrops` (`consistency/WaveFrontier.kt:78`) are
    public `var` with no `private set` — the scheduler's fairness clock and a
    failure-accounting counter, externally assignable.
11. `LocationRegistry.topology` (`host/LocationRegistry.kt:24`) publishes a
    freely mutable `TopologyIndex` (public `linked`/`unlinked`,
    `TopologyIndex.kt:25,33`), defeating the `internal link`/`unlink`
    discipline declared at :117-123. The index feeds `swapSet` (promotion)
    and `wouldCloseCycle` (cycle admission) — semantic contracts.
12. `ParkQueue` (`control/ParkQueue.kt:31-34`) delegates the whole
    `MutableList` API; `parkQueue.clear()` type-checks and is precisely the
    "silently drop parked exclusives" operation the project bans.
13. `ContractRegistry.register` / `ProtocolRegistry.register`
    (`nature/.../ContractRegistry.kt:26-34,70-73`) do last-write-wins `put`
    with no collision check on the authoritative wire-id table (frames carry
    ids only — a silent repoint mis-decodes frames); `contracts`/`cells`
    (:54-55) return live map views.

## Solution

Order matters only in that deletions come first (they shrink the dead-import
sweep). For each deletion: remove the symbol, its KDoc advertisements
elsewhere (`Proxy.kt:28,59`, `ProxyRegistry.kt:27`, `ContractProcessor.kt:322`
mention Broadcast — comment-only edits there are allowed), and its
definition-only tests.

### A. Deletions

1. **SAFETY_PARK**: delete the enum value, `parkWrites`, `resumeWrites`, the
   `posture` field/param; `WritePosture` becomes single-valued — delete the
   enum entirely and the branch it selected. G-44 in the gap ledger keeps the
   design intent.
2. **admission/PeerPredicate**: delete both; add one line to
   `BoundaryPolicy`'s KDoc: "peer admission is enforced at the hello gate
   (`Peering.Allowlist`), not here".
3. **WaveScope**: delete the enum + `Exposure.waveScope` + the three
   constructor threads. The spec text (93 §I-*, ARCHITECTURE mention) keeps
   the REMINT design; update `doc/ARCHITECTURE.md`'s "(REMINT specified, not
   implemented)" line to "(REMINT: spec-only, no code — see 93)".
4. **Broadcast**: before deleting, confirm the live-path exclusivity test
   exists (`OwnershipTest.kt:62` "a second subscriber on an Owned-carrying
   outlet is refused"). Then delete class + factory + `OwnershipTest.kt:159`
   case.
5. **Throwing**: delete class + factory; drop that line from
   `ProxyGenerationTest.kt:46` (the remaining behaviors keep the registry
   covered).
6. **Gate**: delete class + `PolicyTier.GATE`; rewrite
   `InletPolicyStackTest.kt:80`'s case against a local test-double policy so
   the tier-chain ordering (ADMIT→ALIGN→ACTIVATE) and CP-A3 coverage remain.
   Add a KDoc note on `PolicyTier`: backpressure lives in
   `IntakeControl`/`ParkQueue`; a GATE tier can be reintroduced with its
   first real user.
7. **inlet(name)/outlet(name)**: delete.
8. **Dead imports**: sweep all of `kernel/src/main` — an import of
   `civictech.cell.*` is dead if its simple name has no non-comment,
   non-KDoc-link usage in the file. Delete each; where KDoc references break,
   switch to fully-qualified `[civictech.cell...]` links (no import needed)
   or plain backticks. Verify with a clean compile.
9. **ProtocolTraversal**: drop the unused `epoch` field; construct the
   traversal lazily — only wrap when `relays[id] != null` (without a relay
   the visited-edge set can never see a second edge). Keep behavior for the
   relay case identical (`ProtocolRelayTest` pins it).

### B. Encapsulation

10. Add `private set` to `dispatchStep` and `unmatchedDrops`.
11. Make `LocationRegistry.topology` `private`; expose read-only projections
    used by callers (`swapSet(ref)`, `wouldCloseCycle(from,to)`, `all()` — 
    check call sites incl. `TopologyIndexTest.kt:38-80` and route them through
    the projections); make `TopologyIndex.linked/unlinked` `internal`; make
    `mirrorLink`/`mirrorUnlink` `internal` (only caller `RegistryMirrorCell`
    is in `:kernel`).
12. `ParkQueue`: drop `: MutableList<T> by items`; expose `park` (alias
    `add`), `drain`, `drainWhile`, `size`, `isEmpty`, `snapshot(): List<T>`.
    Fix the two structural dependents: `Buffering` (`FanInlet.kt:88`) gets
    `park`, and any `synchronized(queue)` sites still work. Compile will
    surface remaining callers; migrate each to an intent method — if any
    caller genuinely needs an operation that would silently drop entries,
    stop and report instead of adding it.
13. `ContractRegistry`/`ProtocolRegistry`: replace `put` with
    `putIfAbsent`-style guard —
    `require(existing == null || existing == descriptor) { "contractId collision: …" }` —
    and return defensive copies (`.toList()`) from `contracts`/`cells`.

## Verification

```bash
./gradlew :kernel:test
./gradlew test              # whole-repo gate; concord + demos compile against the kernel
grep -rn "SAFETY_PARK\|PeerPredicate\|WaveScope\|class Broadcast\|class Throwing\|PolicyTier.GATE" \
  --include='*.kt' kernel/ nature/     # zero hits
```

## Report

Per deletion: symbol, files touched, tests rewritten. Per tightening: the
call sites migrated. Flag anything you found *not* dead (with the grep hit)
and left in place.
