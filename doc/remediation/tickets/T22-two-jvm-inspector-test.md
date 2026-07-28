# T22 — Two-JVM inspector topology assertion in `:demo:shopping`

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 2 · **Branches:** `ticket/T22`

Wave 2 because this test runs in the multi-JVM serial CI lane T13 (wave 1)
creates, and must carry T13's `@Tag("multi-jvm")` convention on the class —
apply the tag yourself; T13 only retags *pre-existing* `JvmPeer`-based
suites, it does not know this file will exist.

## Context

`:inspect`'s M5-NET milestone (`doc/spec/90-roadmap/97-inspector-plan/tickets/M5-NET.md`)
made the inspector report network-host placement: a cell announced by a peer
JVM appears in the observing side's topology with `Node.host == null` (no
process host — a mirrored location names a bridge, not a `ManagedHost`) and
`Node.net` set to a label for that peer connection
(`inspect/src/main/kotlin/civictech/inspect/Dto.kt:42-52`). The only
automated coverage of this is
`inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt`, which peers
two `LocationRegistry`s **in one JVM** via `Peering.loopback`. Its class KDoc
(`InspectorNetTest.kt:20-38`) is explicit that this is a stand-in:

> `Peering.loopback` — the deterministic P1 shape of a peer connection that
> `:wire` reproduces over a socket

That "reproduces" is an assumption the test suite never checks. The only
place the real cross-socket path (`:wire`, two OS processes, `--inspect-port`
+ `--net-name`) has ever been exercised is the hand-run recipe recorded in
`inspect/ui/README.md`'s "Two-JVM run recipe" section (lines 218-229):

```
./gradlew :demo:shopping:installDist
./demo/shopping/build/install/shopping/bin/shopping 18081 --listen 19101 --inspect-port 17071 --net-name jvm-a
./demo/shopping/build/install/shopping/bin/shopping 18082 --peer ws://localhost:19101 --inspect-port 17072 --net-name jvm-b
INSPECT_BACKEND=http://localhost:17071 npm run dev   # in inspect/ui
```

AGENTS.md's core invariant "In-process and remote paths should preserve the
same observable semantics" is exactly what goes unverified here, and
detection latency for a `:wire`-peering regression against the inspector is
currently unbounded (nothing fails CI; only a human running the recipe by
hand would notice). `doc/remediation/AUDIT-2026-07-28.md` §W4 item 4 and
`doc/architecture-decisions.md` finding B13 record this as the accepted, open
item this ticket implements.

`demo/shopping` is the right host for the new test: its `Main.kt` is already
"the M5-NET pilot — the one that runs two symmetric JVMs over the real
`:wire` transport" (`demo/shopping/src/main/kotlin/civictech/demo/Main.kt:245-246`),
its `build.gradle.kts` already depends on `:inspect` (`implementation(project(":inspect"))`,
`demo/shopping/build.gradle.kts:13`, with the comment explaining exactly this
role), and `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmConvergenceTest.kt`
already establishes the two-OS-process test shape this ticket mirrors.

## Problem

Concretely: `InspectorNetTest.kt` asserts the M5-NET contract only against
`Peering.loopback`. No test in the repo launches two real JVMs with
`--inspect-port` and asserts anything about the resulting topology. A
regression in `:wire`'s peering path that broke the inspector's remote-host
reporting (wrong `net`, non-null `host` on a mirrored cell, or a cell missing
entirely) would pass every existing gate.

## Solution direction (decided, with one correction to the request's framing)

One test in `:demo:shopping`, mirroring `TwoJvmConvergenceTest`'s shape
(`JvmPeer.launch` two OS processes on the current test classpath, `HttpProbe`
+ bounded `awaitUntil` polling, `finally { peerA.destroy(); peerB.destroy() }`):

1. Allocate ports with `JvmPeer.freePort()` for each peer's HTTP port, the
   `--listen` WS port, and each peer's `--inspect-port` (mirror the existing
   test's allocation style — one `freePort()` call per port, no fixed
   numbers like the README recipe's `17071`/`17072`).
2. Launch peer A with `--listen <ws> --inspect-port <ia> --net-name jvm-a`
   and peer B with `--peer ws://localhost:<ws> --inspect-port <ib> --net-name
   jvm-b`, exactly as `Main.kt:309-335` parses them (`args.value("--inspect-port")`,
   `args.value("--net-name")`, `stripPairs` before `demoPort`).
3. Wait (bounded) until peer A's `/api/inspect/topology`
   (`InspectorServer.BASE_PATH + "/topology"` =
   `/api/inspect/topology` — `InspectorServer.kt:560-561`) reports at least
   one node with `host == null` (i.e., B has announced across the wire and A
   has adopted it — the same ordering `InspectorNetTest.awaitNode` waits on,
   but from outside the process there is no `knowsNow` to call, so poll the
   HTTP response instead).
4. Assert, on peer A's topology snapshot:
   - at least one node has `host == "shopping"` (the name `Main.kt:283-286`'s
     `startInspector` gives A's own `ManagedHost` in its `hosts` map) **and**
     `net == "jvm-a"` — A's own cells, under A's `--net-name`;
   - at least one node has `host == null` — a cell mirrored from B.

   **Correction to the assertion as originally framed:** do *not* assert that
   B's mirrored cells carry `net == "jvm-b"`. They will not. Verified against
   `inspect/src/main/kotlin/civictech/inspect/Peers.kt:1-84`: a peer's own
   `--net-name` is never transmitted over the wire (its KDoc, lines 24-40,
   explains why — `PeerId` reaches only the transport's ingress hello, never
   the registry, and reading it would be a peering-protocol change M5-NET
   explicitly excludes). Instead `Peers.netOf` derives the label locally on
   the *observing* side from the bridge egress cell the mirrored ref routes
   through: `PREFIX + sink.ref.id...` where `PREFIX = "peer-"`
   (`Peers.kt:67,79-82`). `InspectorNetTest.kt:112-113` pins exactly this:
   `remote.net shouldStartWith "peer-"` and `remote.net shouldNotBe "jvm-a"`.
   So the correct, verified assertion is: **A's mirrored (host == null) nodes
   have `net` starting with `"peer-"` and not equal to `"jvm-a"`** — that is
   the real observable "peer B placed remotely" signal, and it is what the
   loopback test already pins, so this test is honestly checking that
   `:wire` reproduces the *same* shape, not a shape this ticket invents.
   (Launch peer B with `--net-name jvm-b` anyway, matching the README recipe
   and giving B's own inspector endpoint the same realistic configuration —
   just do not assert its value appears on A's side.)
5. Decode responses with `kotlinx.serialization` against `:inspect`'s own DTOs
   (`civictech.inspect.TopologySnapshot`, `civictech.inspect.Node` —
   `inspect/src/main/kotlin/civictech/inspect/Dto.kt:25-75`), the same way
   `InspectorNetTest.snapshot()` does (`InspectorNetTest.kt:70-75`). This is
   available on the test classpath without a new dependency: `:demo:shopping`
   already has `implementation(project(":inspect"))` in `build.gradle.kts`,
   and Gradle's `testImplementation` extends `implementation`, so `:inspect`'s
   classes (and its `kotlinx-serialization-json` dependency) are already on
   `demo:shopping:test`'s classpath — confirm this compiles before assuming a
   new dependency is needed.
6. Tag the test class `@org.junit.jupiter.api.Tag("multi-jvm")` per T13's
   convention (`doc/remediation/AUDIT-2026-07-28.md` §W1 item 2: "Tag
   multi-process tests (`@Tag("multi-jvm")` on the `JvmPeer`-based suites)").
   `TwoJvmConvergenceTest.kt` does not carry this tag yet at the time this
   ticket is written (T13 has not merged); by the time T22 runs (wave 2,
   after T13's wave-1 checkpoint), T13 will have tagged it. Tag your own new
   file regardless — T13's retag pass only covers suites that existed when it
   ran.

Latitude (implementer's judgment): exact fixture — the shopping demo's
existing graph (items/votes union cells) is enough, no new cells needed;
extra cheap assertions if desired (e.g., that killing peer B's process
eventually retracts its mirrored cells from A's topology — mirrors
`InspectorNetTest`'s disconnect case, but is not required); port allocation
mechanics — mirror `TwoJvmConvergenceTest`'s existing style exactly.

## Files expected to touch

- `demo/shopping/src/test/kotlin/civictech/demo/` — one new test file (e.g.
  `TwoJvmInspectorTest.kt`; exact name is your judgment, keep it beside
  `TwoJvmConvergenceTest.kt` and named for what it verifies).

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W4 item 4 (lines 113-116) — the
  accepted work item this ticket implements, and the "runs in the W1 serial
  lane" note.
- `doc/architecture-decisions.md` finding B13 (line 48) — severity, location,
  solution, status `planned`.
- `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmConvergenceTest.kt` —
  the exact two-OS-process test shape to mirror (`JvmPeer.launch`, `finally`
  cleanup, `awaitUntil` polling via raw `HttpURLConnection` — feel free to use
  `HttpProbe` instead, per T12's canonical form).
- `testkit/src/main/kotlin/civictech/testkit/JvmPeer.kt` — `freePort()` and
  `launch(mainClass, vararg args)`.
- `testkit/src/main/kotlin/civictech/testkit/HttpProbe.kt` — `get`/`state`,
  and `await(timeoutMs, path, predicate)` for bounded polling (throws with
  last-seen body on timeout rather than silently returning stale data).
- `testkit/src/main/kotlin/civictech/testkit/AwaitUntil.kt:11` —
  `awaitUntil(what, timeoutMs = 30_000, condition)`, the non-HTTP bounded-wait
  primitive `TwoJvmConvergenceTest` uses between HTTP calls.
- `demo/shopping/src/main/kotlin/civictech/demo/Main.kt:243-299`
  (`startInspector`'s KDoc and body — confirms `hosts["shopping"]`, the
  `--net-name`/`netName` plumbing, and the M5-NET pilot framing) and
  `:309-335` (`fun main` — confirms `--inspect-port` and `--net-name` are the
  real flag spellings, parsed via `args.value(...)`, stripped before
  `demoPort` reads the positional port). No new flag is needed; both already
  exist and are wired to `startInspector`.
- `inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt:20-38`
  (class KDoc — the candid "assumption, not an assertion" admission this
  ticket closes) and `:92-122` (the loopback-shape assertions on `net`/`host`/
  `typeFqn` this new test's remote-node assertions should match in spirit).
- `inspect/ui/README.md` lines 218-229 ("Two-JVM run recipe") — the hand-run
  procedure this test automates; note it uses fixed ports (`17071`/`17072`,
  etc.) where the test should use `JvmPeer.freePort()` instead.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` lines 21,
  45-60 — the `/topology` response shape and the `host`/`net` field
  semantics (`"host": "sm-host" | null` — null means remote; `"net"` —
  network host / peer id, local cells report `--net-name`).
- `inspect/src/main/kotlin/civictech/inspect/Dto.kt:25-75` — `TopologySnapshot`
  and `Node`'s actual Kotlin shape (`ref`, `host`, `net`, `typeFqn`, ...), to
  decode against directly instead of hand-parsing JSON.
- `inspect/src/main/kotlin/civictech/inspect/Peers.kt` (whole file) — why the
  remote `net` label is a locally-derived `"peer-" + <bridge-egress-ref
  prefix>`, never the peer's own `--net-name`. This is the fact that
  corrects the naive "B's cells carry B's net label" framing — read it before
  writing the assertion.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:560-561`
  (`BASE_PATH`, `TOPOLOGY_PATH` constants) and `:90-114` (constructor KDoc —
  confirms `netName` defaults to `Node.LOCAL_NET` and only stamps *locally
  published* cells).
- `demo/shopping/build.gradle.kts` — confirms `implementation(project(":inspect"))`
  is already present, and `testImplementation(project(":testkit"))`; no
  `:inspect` dependency needs adding.

Do not modify: `demo/shopping/src/main/**` (the `--inspect-port`/`--net-name`
flags already exist and are already wired through `startInspector` — if you
find a genuinely missing flag or wiring gap, stop and report it rather than
adding it), `inspect/**`, `testkit/**` unless a trivially missing probe
helper turns out to be genuinely required (then note exactly what and why in
the completion report — do not add speculative helpers).

## Acceptance criteria

- [ ] The new test passes locally via the narrow Gradle invocation below.
- [ ] The test class carries `@Tag("multi-jvm")`.
- [ ] The test launches two real, separate JVMs (via `JvmPeer.launch`) wired
      with `--listen`/`--peer`, `--inspect-port`, and `--net-name`, exactly as
      `Main.kt` parses them today.
- [ ] The test asserts, from peer A's `/api/inspect/topology` only: at least
      one node with `host == "shopping"` and `net == "jvm-a"` (A's own
      cells), and at least one node with `host == null` and `net` starting
      with `"peer-"` and not equal to `"jvm-a"` (B's cells, mirrored,
      correctly placed remote).
- [ ] All waits are bounded (`awaitUntil`/`HttpProbe.await`-style polling with
      a timeout); no assertions on internal scheduling timing.
- [ ] Both launched processes are destroyed in a `finally` block even on
      assertion failure.
- [ ] No unrelated files in the diff.

## Verify

```bash
./gradlew :demo:shopping:test --tests '<new test FQN>'
```

## Report on completion

- Checks run and their results, including the exact test FQN used above.
- Confirm whether `:inspect`'s classes and `kotlinx-serialization-json` were
  already resolvable on `:demo:shopping`'s test classpath without a
  `build.gradle.kts` change (expected per the transitive-`implementation`
  reasoning in Solution direction item 5) — if not, what you added and why.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why — in particular, flag
  immediately (before writing code) if `--inspect-port` or `--net-name` turn
  out not to exist or not to behave as `Main.kt:309-335` describes.
