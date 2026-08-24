# The DST rig: consuming `civictech.testkit.dst`

This is a how-to for a consumer of the adversarial deterministic-simulation-testing (DST) rig
built under epic `computenet-umx` (CHA1) — for CHA2 (verdict semantics over rig-observed
outcomes), CHA3 (a churn generator built on top of the rig's fault plan), MEM2 and KE3, and any
other future consumer. It documents what exists in `testkit/src/main/kotlin/civictech/testkit/dst/`
today, not what the epic once planned; every claim below is checked against that code, not
against the roadmap prose. For requirement ids ([CHA1-NN]) and BS-N acceptance names, the
normative text is epic `computenet-umx`'s own description — this document is a map to the
code, not a restatement of the spec.

An example consumer end to end is `demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeCompositionDstTest.kt`
(`computenet-umx.3.11`) — a smaller sibling of `ExchangeCompositionExitTest` driven through the
rig under a partition and a crash. `testkit/src/test/kotlin/civictech/testkit/dst/` holds the
rig's own self-tests, several of which (`ExclusivePayloadAccountingTest`'s `ExclusiveBridgeGraph`
in particular, `computenet-umx.3.8`) are worth reading as a second worked example before writing
your own graph.

## 1. Building a `DstWorld`

A run is `DstRun(graph, plan, budget, check).execute(): DstReport`. `graph` is a `GraphSpec(id,
builder)` — a stable id (`GraphRegistry.register`) plus a `GraphBuilder { world -> ... }` that
constructs your kernel graph *into* a fresh `DstWorld` the rig hands it. Build deterministically
given the world's seed: same seed, same declarations, in the same order, with any randomness
drawn from `world.rng(purpose)` rather than `java.util.Random()` or `UUID.randomUUID()` fed by
wall-clock entropy.

`DstWorld` wraps a real `civictech.cell.host.SimulationController` (`world.controller`, seeded
from `world.seed`, exposed unchanged) plus **six seams** a `Fault` reaches the graph through, and
nothing else:

1. **`world.edges`** — named, unidirectional frame-plane interposers. Declare an edge with
   `world.edges.declare("a->b", from = "a", to = "b")`; the graph builder routes its own frame
   delivery through `world.edges.deliver(name, frame)`, and a fault (partition-DROP, duplicate,
   reorder, corrupt) intercepts that named edge. A bidirectional wire is two edges, which is what
   makes a one-way partition expressible without a direction parameter on the fault.
2. **`world.hosts`** — named host slots, each holding *your* deterministic rebuild function:
   `world.hosts.declare("name") { ctx -> ManagedHost(scheduler = ctx.scheduler, ...) }`. The
   builder lambda runs once at declare time (generation 0) and again, with an incremented
   generation and a fresh scheduler, every time a `CrashFault` discards the host. Only a host a
   `CrashFault` targets needs to go through this seam — everything else can be a plain
   `ManagedHost(scheduler = world.controller.scheduler(), ...)`, built once, the way
   `ExchangeCompositionDstTest`'s shard-mesh and peer1 hosts are.
3. **`world.journals`** — named per-cell journal decoration over `ManagedHost(journalFor = ...)`.
   `world.journals.declare("name")` returns a stable view to hand to `journalFor`; a journal
   fault (truncate, corrupt, reorder, duplicate) decorates it later without re-wiring the host.
4. **`world.steps`** — a hook fired before every controller step, with that step's index:
   `world.steps.onStep { world, step -> ... }`. This is the rig's *only* activation clock
   ([CHA1-02]) — no fault, and no graph builder, may use wall-clock time. It is also how a graph
   builder injects its own workload: `DstRun.execute()` drives the *entire* budget itself in one
   loop, so a builder cannot interleave writes with manual `controller.step()` calls the way an
   ordinary `SimulationController` test does. Schedule writes from a step hook instead (see
   `ExchangeCompositionDstTest`'s workload hook), and keep them spread over enough steps that the
   run does not quiesce — and the loop break — before a fault's activation window is reached.
5. **`world.trace`** — trace and fault-firing emission. A fault that fires without calling
   `world.trace.fault(id, ...)` is invisible to `[CHA1-24]`'s per-fault firing count and will
   report as inert even though it ran; `FrameInterposers.tracing` is the shared helper for the
   frame-plane case.
6. **`world.deadLetters`** — raw, unclassified `DeadLetter` capture from every declared host.

A fault class must never require a seventh seam. If one genuinely does not fit (as `PARTITION`'s
`PARK` mode did not — see §4), the answer is a small per-graph registry keyed by `DstWorld`
(`LinkControls`, `CrashWitnesses`, `ExclusiveLedgers` are the existing examples: a `WeakHashMap<
DstWorld, ...>` populated by the graph builder, read by the fault or the check), not a widened
`DstWorld`.

## 2. Writing a `FaultPlan`

```kotlin
FaultPlan.of(
    seed,
    PartitionFault.park("partition-peers", "peer0<->peer1", from = 10, until = 22),
    CrashFault.midDrain("crash-writer", "writer-host", atStep = 15, journal = "writer-journal"),
)
```

`FaultPlan(seed, faults)` is the whole seeded adversary — every source of randomness in the run
(the controller's cross-host pick, a fault's own randomness, a graph builder's workload
randomness) derives from `seed` alone via `world.rng(purpose)` ([CHA1-30]); there is no second
field to disagree with, which is what makes the plan shrinkable without re-rolling the run.

### A fault must be a value, encodable field-for-field

Verified against `CrashFault`'s and `PartitionFault`'s companion `CODEC`s
(`testkit/src/main/kotlin/civictech/testkit/dst/CrashFault.kt`,
`.../PartitionFault.kt`): a `Fault` is a sealed interface, and every landed implementation is an
immutable `data class` whose fields are strings, numbers, enums and nullable strings — nothing a
`FaultCodec` cannot write into `FaultRecord(id, kind, params: JsonObject)`. **No fault field may
be a lambda.** `CrashFault` needs a rebuild function to discard-and-respawn a host, and it
deliberately does *not* carry one: the rebuild function lives on the *host slot*
(`HostSlots.declare`'s `build` parameter), and the fault just names the host. That is the pattern
to copy — if your fault needs behaviour a value cannot carry, put the behaviour on a per-graph
registry the fault looks up by name, the same way `CrashFault` looks up its host slot and
`PartitionFault`'s `PARK` mode looks up its `LinkControl` (§4). A fault that cannot be written to
JSON cannot be written to a replay artifact ([CHA1-31]), which is the entire reason for this rule.

### A codec's `params` must be flat primitives

Verified against `CrashFault.CODEC`'s `decode` KDoc and `PartitionFault.CODEC`'s: both encode
every field as a **top-level** primitive on `FaultRecord.params` — `CrashFault` writes `host`,
`atStep`, `mode`, `journal` as siblings, not `{"config": {"atStep": ...}}`; `PartitionFault`
writes `StepWindow`'s `from`/`until` the same way rather than nesting a `"window"` object. This
is load-bearing, not a style choice: `ReductionStrategies.numericParamToward` (the shrinker's
semantics-aware strategy, §3) reaches a parameter *by name* off `FaultRecord.params` — see
`PlanShrinker.kt`'s `numericParamToward`, which does `record.params[param]`. A nested object
round-trips through encode/decode perfectly and is invisible to that lookup, so it silently
leaves the shrinker nothing to shrink for that field: no error, no warning, just a candidate list
that never proposes moving it. If your fault's constructor groups related fields into a nested
value type for its own convenience, flatten them at the codec boundary.

## 3. Replay and shrink

A failing seed run through `dstSweep(...)` (or any run whose `DstReport.outcome == FAILED`)
writes a `DstArtifact` under the module's `build/` directory ([CHA1-54], enforced by
`DstArtifacts.requireUnderBuildDirectory` — an artifact root outside `build/` is refused up
front, not discovered on the 84th seed). The artifact carries the plan (faults as `FaultRecord`s,
by kind and id), the seed, the graph id, the check id, the observed outcome, trace digest, trace
length and — when the check failed — its message and step. It does **not** carry the trace
itself.

- **Replay**: `DstReplay.from(file)` re-runs the artifact's `(graph, plan, check)` and grades the
  new report against what was recorded (`DstReplay.grade`). A verdict of `REPLAYED` means outcome,
  step count, failing-check message, failing step, trace length and trace digest all matched;
  `DIVERGED` means at least one did not, and is never silently treated as a pass ([CHA1-34]) —
  there is no `PASSED` verdict in `ReplayVerdict` at all. `INDETERMINATE` means no comparison was
  attempted: either the artifact was recorded from a `DstDriver.MULTI_JVM` run (see §4), or it was
  recorded by a different rig/commit stamp than the one replaying it (a trace digest is only
  valid within one commit — epic §9 risk 6). `DstReplay.assertReplays(file)` is the BS-1
  assertion form: it fails unless the verdict is exactly `REPLAYED`.
- **Shrink**: `PlanShrinker.shrink(artifact, ...)` reduces a **failing** artifact's fault list
  (never its seed — `require`d, and rejected loudly if a candidate strategy tries) by proposing
  candidate reductions and re-running each one in full, keeping only those that still fail "the
  same way" per a `FailurePredicate` ([CHA1-36]). The default strategy,
  `ReductionStrategies.dropFaults`, only ever drops one fault at a time — the one reduction that
  is unconditionally simpler regardless of what the fault was. `ReductionStrategies.
  numericParamToward(kind, param, target)` is the semantics-aware half: *you* say which parameter
  and which direction is less adversarial (shorten a partition window, lower a duplication
  probability toward `0.0`), because the rig cannot infer that from a bare JSON value. A shrink
  never claims global minimality — only "locally minimal under the strategy given, in
  `maxAttempts` attempts or fewer" (`ShrinkResult.summary()`), and a budget-exhausted shrink says
  so explicitly (`stoppedEarly`) rather than presenting a partial reduction as final.

### A check's failure message must be stable across runs of the same failure mode

Verified against `PlanShrinker.kt`'s `FailurePredicate.sameFailingCheck` (the default
`sameFailure` predicate `PlanShrinker.shrink` uses): it compares `report.outcome ==
recorded.outcome && report.failingCheck?.message == recorded.failingCheck` — the check's
*message*, not the step index or the trace digest, because which check fails is invariant across
a shrink by construction while the step and the digest are expected to move. Consequently **a
message that embeds a run-varying count or set defeats shrinking**: a reduction that genuinely
still reproduces the same defect, at a smaller scale, produces a *different* message and is
discarded as "a different failure" — silently, with no error, just a shrink that stops one step
short of where it should and a report claiming a larger minimal plan than the true one. This was
measured on `computenet-umx.3.7`: a reduction from 6 dropped frames to 3 failed with "only 18 of
30 arrived" against a recorded "only 12 of 30" and was discarded.

The fix is the same one `ExclusivePayloadLost` (`computenet-umx.3.8`) and this doc's own example
graph (`ExchangeCompositionDiverged` in `ExchangeCompositionDstTest.kt`) use: keep the message a
fixed string naming *what* invariant failed, and put every run-varying value — sets, counts,
diffs — behind a separate `detail()` (or similar) the shrinker never looks at. When a check
genuinely cannot be worded that way, `FailurePredicate.sameOutcome` is the documented escape
hatch — "outcome only" — but its own KDoc calls it "weaker than it looks": a graph with two
distinct properties, or one check that throws for two different reasons, will accept a reduction
that broke something else entirely and call it the same failure. Prefer a stable message.

## 4. The determinism contract, and its one seam-shaped exception

[CHA1-30]: every source of randomness in a run — the controller's own cross-host interleaving
pick, a fault's internal randomness (`DuplicateFault`'s probability roll, `ReorderFault`'s burst
threshold), and any workload randomness a graph builder needs — derives from the plan's `seed`,
via `world.rng(purpose)`. Two runs of the same `(graph, plan)` on the same rig commit produce the
same trace digest; `DstRun.assertDeterministic(runs)` is the cheap, available-to-every-consumer
assertion of exactly that, and is worth calling on a new graph before anything else. **Object
identity is not part of that contract** — `PortRef.generate()` and similar identity-only
constructs are not required to be seed-derived, because nothing about `[CHA1-30]` depends on
*which* opaque identity a subscription gets, only on what the trace and the check observe.

`PartitionMode.PARK` is the one documented case where a fault cannot reach the graph through the
six seams above, and the resolution is instructive for any future fault with the same shape. Park
needs to *hold* traffic and release it later — the kernel offers two such primitives
(`LocationRegistry.hold`/`release`, and `Peering.Loopback.partition()`/`heal()`), and neither is
derivable from an edge name by a rig that does not know how the graph wired its bridges. Rather
than widen `DstWorld` with a seventh, park-specific seam, the graph builder declares a
`LinkControl` per edge (`LinkControls.declare(world, edge, LinkControl.severing(loopback))` or
`LinkControl.holding(registry, ref)`), and `PartitionFault` in `PARK` mode looks it up and calls
`park()`/`heal()` on it. This is the general pattern: when a fault's mechanism is not a frame
transform, a host rebuild, a journal decoration or a step-indexed hook, it is a per-graph
declaration the graph builder supplies and the fault resolves by name — never a special case
threaded through `DstWorld` itself.

### [CHA1-40]: multi-JVM is explicitly outside the replay guarantee

`DstDriver` has two values: `IN_PROCESS(deterministic = true)` and `MULTI_JVM(deterministic =
false)`. A `dstSweep(..., driver = DstDriver.MULTI_JVM)` run's artifact is written with that
driver stamped on it, and `DstReplay.of` checks `artifact.driver.claimsReplayReproducibility`
*before* re-running anything: a `MULTI_JVM` artifact is graded `INDETERMINATE` unconditionally,
with a message naming the reason ("the interleaving of a multi-JVM run is the OS scheduler's and
is not recoverable from seed=..."). `DstSweepReport.nonDeterministic` mirrors this at the sweep
level. **No claim of replay reproducibility is made, or should be relied on, for anything driven
across JVM boundaries** — only the in-process driver's runs can be replayed, shrunk, or asserted
deterministic in the sense §4's first paragraph describes. A CHA2/CHA3/MEM2/KE3 consumer that
needs multi-process behaviour (real socket transport, real process crashes) can still use the rig
for plan construction and fault classes, but should not build a shrink or a strict replay
assertion on top of a multi-JVM run's artifact.

## 5. `[CHA1-64]`: the module-boundary evidence for this rig

Measured against `feature/computenet-umx.3` at commit `9b9e13232` (base `ebab9b920`), and holding
unchanged through `computenet-umx.3.11`'s own diff (this file and
`ExchangeCompositionDstTest.kt`, both under `demo/exchange` and `doc/`, neither `:kernel` nor
`:testkit`):

- `git diff --stat <base>..HEAD -- kernel/build.gradle.kts` is **empty**. No third-party
  dependency was added to `:kernel`.
- `kernel/src/main/kotlin/civictech/cell/wire/Peering.kt` gained the `FrameInterpose` fun
  interface and the `interposeAToB`/`interposeBToA` trailing parameters on `Peering.loopback`
  (`computenet-umx.3.2`) — **kernel main was touched**, but by the additive, default-preserving
  test seam the epic's "Scope boundaries" section sanctions: both parameters default to
  `FrameInterpose.PASS_THROUGH`, so every pre-existing caller compiles and behaves unchanged. Say
  which of "no transport code entered `:kernel`" (true) and "`:kernel` is untouched" (false) you
  mean — they are different claims, and only the first is accurate.
- `testkit/build.gradle.kts` gained the `kotlinx.serialization` plugin and `api(libs.kotlinx.
  serialization)`, for `DstArtifact`'s JSON encoding ([CHA1-31]). `api` rather than
  `implementation` is deliberate: `FaultCodec`/`FaultRecord` expose `JsonObject` in public
  signatures, so a consumer registering a codec needs the type on its own compile classpath. The
  dependency *direction* is unchanged (`:testkit -> :kernel`; nothing new points into `:testkit`),
  and because `:testkit` is consumed as `testImplementation` by `:kernel` and every demo, this
  dependency reaches their **test** classpaths only — no module's main classpath gained a
  dependency, and the version comes from the same catalog entry `:kernel` and `:wire` already use
  for the same artifact.

Re-run the same two checks against your own consumer branch before writing a `[CHA1-64]` claim in
a PR description — the underlying facts do not change per-feature, but the diff range does, and
the evidence should survive someone re-checking it rather than being copied verbatim from a
different branch's measurement.
