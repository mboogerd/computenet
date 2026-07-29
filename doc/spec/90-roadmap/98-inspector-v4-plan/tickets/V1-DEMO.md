# V1-DEMO — Two-JVM shopping convergence runbook: an inspector per peer, side by side

**Status**: Implemented — merged
**Model:** `claude-haiku-4-5` · **Escalate to:** `claude-sonnet-5`
**Wave:** 3 · **Branches:** `ticket/v1-demo`

## Context

`:demo:shopping` (`demo/shopping/`) is a collaborative shopping list built
purely from cells (`demo/shopping/README.md`). It has an M5 two-JVM peering
mode: two symmetric processes connected over the real `:wire` transport, each
hosting its own users' cells and streaming its derived views into its
counterpart's (`demo/shopping/src/main/kotlin/civictech/demo/Main.kt`'s class
doc, lines 40-44 and 150-171). `demo/shopping/README.md`'s "Two machines, one
graph (M5)" section is the existing single-inspector-free recipe:

```
./gradlew :demo:shopping:run --args="8080 --listen 9090"                 # peer 1
./gradlew :demo:shopping:run --args="8081 --peer ws://localhost:9090"    # peer 2
```

`:demo:shopping` also depends on `:inspect` (`demo/shopping/build.gradle.kts`
lines 6-13) and its `main(args)` (`Main.kt:309-335`) already reads two
inspector-related flags, verified by reading the source directly:

- `--inspect-port <port>` (or env `INSPECT_PORT`) — starts a
  `civictech.inspect.InspectorServer` for this JVM on `<port>`, printing
  `computenet inspector: http://localhost:<port>/api/inspect/topology`
  (`Main.kt:310-311,330-333`).
- `--net-name <name>` — labels this JVM's own cells' network host for the
  inspector's net-hull grouping (`Main.kt:312`, `DemoApp.startInspector`,
  `Main.kt:267-299`).

Both flags are stripped from the positional/port-parsing pass before the
demo's own port argument is read (`Main.kt:316`, `stripPairs`), so they can
appear in any order alongside `--listen`/`--peer`/`--journal`. This is the
same M5-NET pilot verified end-to-end, out of process, by
`demo/shopping/src/test/kotlin/civictech/demo/TwoJvmInspectorTest.kt`
(`@Tag("multi-jvm")`), which launches two real JVMs exactly this way
(`TwoJvmInspectorTest.kt:42-49`):

```kotlin
JvmPeer.launch("civictech.demo.MainKt", "$httpA", "--listen", "$ws",
    "--inspect-port", "$inspectA", "--net-name", "jvm-a")
JvmPeer.launch("civictech.demo.MainKt", "$httpB", "--peer", "ws://localhost:$ws",
    "--inspect-port", "$inspectB", "--net-name", "jvm-b")
```

`inspect/ui/README.md`'s "Two-JVM run recipe" section (under "M5-NET",
lines 218-229) is the closest existing manual recipe — it already launches
two shopping peers each with its own `--inspect-port`, but views them with a
**single** Vite dev server, switching which backend it proxies to via the
`INSPECT_BACKEND` env var (`inspect/ui/vite.config.ts`):

```
./gradlew :demo:shopping:installDist
./demo/shopping/build/install/shopping/bin/shopping 18081 --listen 19101 --inspect-port 17071 --net-name jvm-a
./demo/shopping/build/install/shopping/bin/shopping 18082 --peer ws://localhost:19101 --inspect-port 17072 --net-name jvm-b
INSPECT_BACKEND=http://localhost:17071 npm run dev   # in inspect/ui
```

`INSPECT_BACKEND` is read once at Vite startup (`vite.config.ts`'s
`process.env.INSPECT_BACKEND ?? 'http://localhost:7071'`), so two **separate**
Vite dev server processes — one per `INSPECT_BACKEND` value, on two different
ports — give you two inspector UIs open **at the same time**, which is what
this ticket needs and the existing recipe does not provide (it only ever
looks at one side at a time).

**Dependency: V1a.** The demo's whole point — "watch the observed list cell
update live in both inspectors as anti-entropy converges" — needs the
observed-cell state view to actually stream live updates. That lands in Wave
2 (`V1A-BE`/`V1A-FE`, coalesced `state.summary` + FE refetch-on-change), which
merges before Wave 3 starts. By the time you run this ticket it will already
be in `main`; nothing here depends on its exact internal shape, only on
selecting a cell and its value visibly updating in the panel/chip as items are
added.

## Problem

There is no runbook or convenience script for running two `:demo:shopping`
peers with an inspector each, viewed side by side, to watch convergence live.
`inspect/ui/README.md`'s existing recipe is the closest thing but only
supports looking at one peer's inspector at a time (a single Vite instance,
one `INSPECT_BACKEND`). No script exists to launch/monitor/tear down the
multi-process setup (two JVMs + two UI dev servers).

## Solution direction

Not prescriptive. Do all of the following exactly as specified — this is a
Haiku-tier ticket, so the commands and ports below are final, not suggestions
to re-derive.

**Chosen ports** (all non-default, and distinct from `inspect/ui/README.md`'s
own recipe so both can be run at once without colliding):

| purpose | peer A | peer B |
|---|---|---|
| shopping HTTP | `18191` | `18192` |
| `:wire` websocket | `19201` (shared — A listens, B dials) | |
| inspector | `17091` | `17092` |
| inspector UI (Vite dev) | `5191` | `5192` |

(`7071` is `InspectorServer.DEFAULT_PORT`; `8080`/`8081` and `5173` are the
repo's/Vite's own defaults and are commonly squatted by other concurrent
sessions per repo convention — all four chosen ports above avoid every one of
those.)

### 1. Convenience script

Create `scripts/demo-shopping-two-inspectors.sh` with **exactly** this
content (adjust only if a step genuinely fails in your environment, and say
so in the report):

```bash
#!/bin/bash
# Launches two :demo:shopping peers over the real :wire transport, each with
# its own :inspect backend and its own inspector UI dev server, so both
# graphs can be watched side by side while an item added on one peer
# converges onto the other. See doc/demo-shopping-inspector.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HTTP_A=18191
HTTP_B=18192
WS=19201
INSPECT_A=17091
INSPECT_B=17092
UI_A=5191
UI_B=5192

echo "==> building demo/shopping"
./gradlew :demo:shopping:installDist --console=plain -q

echo "==> inspect/ui dependencies"
if [ ! -d "$ROOT/inspect/ui/node_modules" ]; then
  (cd "$ROOT/inspect/ui" && npm ci)
fi

PIDS=()
cleanup() {
  echo "==> shutting down"
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> peer A (listener): http :$HTTP_A, ws :$WS, inspector :$INSPECT_A"
"$ROOT/demo/shopping/build/install/shopping/bin/shopping" "$HTTP_A" \
  --listen "$WS" --inspect-port "$INSPECT_A" --net-name jvm-a &
PIDS+=($!)

echo "==> peer B (dialer): http :$HTTP_B, peers ws://localhost:$WS, inspector :$INSPECT_B"
"$ROOT/demo/shopping/build/install/shopping/bin/shopping" "$HTTP_B" \
  --peer "ws://localhost:$WS" --inspect-port "$INSPECT_B" --net-name jvm-b &
PIDS+=($!)

wait_for() {
  local url="$1" name="$2"
  for _ in $(seq 1 60); do
    curl -sf "$url" >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  echo "!! $name never responded at $url" >&2
  exit 1
}

echo "==> waiting for both inspectors"
wait_for "http://localhost:$INSPECT_A/api/inspect/topology" "inspector A"
wait_for "http://localhost:$INSPECT_B/api/inspect/topology" "inspector B"

echo "==> starting inspector UI dev servers"
(cd "$ROOT/inspect/ui" && INSPECT_BACKEND="http://localhost:$INSPECT_A" npx vite --port "$UI_A" --strictPort) &
PIDS+=($!)
(cd "$ROOT/inspect/ui" && INSPECT_BACKEND="http://localhost:$INSPECT_B" npx vite --port "$UI_B" --strictPort) &
PIDS+=($!)

wait_for "http://localhost:$UI_A/" "inspector UI A"
wait_for "http://localhost:$UI_B/" "inspector UI B"

cat <<EOF

==> ready — open these two inspector UIs side by side:
    peer A inspector : http://localhost:$UI_A
    peer B inspector : http://localhost:$UI_B

    shopping app (peer A) : http://localhost:$HTTP_A
    shopping app (peer B) : http://localhost:$HTTP_B

Enter the "shopping" graph in both inspector tabs, select the "items" cell in
each, then add an item at http://localhost:$HTTP_A and watch both update.

Press Ctrl+C to stop everything.
EOF

wait
```

Make it executable (`chmod +x scripts/demo-shopping-two-inspectors.sh`).

If, when you actually run this, `--inspect-port` or `--net-name` turn out not
to work as described (they were verified by reading `Main.kt` directly for
this ticket, not by running it) — **stop and flag the orchestrator**. Do not
patch `demo/shopping` or any other production/demo code to make it work; that
is out of this ticket's file claim.

### 2. Runbook doc

Create `doc/demo-shopping-inspector.md` (top-level `doc/`, matching the
existing `doc/demo-findings.md` naming convention — this is a "how to run a
demo" doc, not a spec chapter, so it does not need a `**Status**:` header;
`docLints` only scans `doc/spec/**`). Include, in your own words but covering
every fact below (do not invent facts not given here or elsewhere in this
ticket):

- **What this demonstrates**: two independent `:demo:shopping` JVMs peered
  over the real `:wire` transport (M5), each running its own `:inspect`
  backend; an item added on one peer's shopping list converges onto the
  other's via the app's own anti-entropy-on-reannounce chaining, and — once
  V1a's live `state.summary` streaming is in place — the converging cell's
  value visibly updates in **both** inspector UIs without a manual refresh.
- **Prerequisites**: JDK 21 toolchain (repo standard), Node.js `^22.0.0`
  (`inspect/ui/package.json`'s `engines`), `curl`.
- **One-liner**: `./scripts/demo-shopping-two-inspectors.sh` from the repo
  root; Ctrl+C stops everything.
- **What it prints**: the two inspector UI URLs
  (`http://localhost:5191`, `http://localhost:5192`) and the two shopping app
  URLs (`http://localhost:18191`, `http://localhost:18192`) — reproduce the
  exact table of ports from §"Chosen ports" above.
- **Manual walkthrough** (in case the script cannot be used, or for narrating
  the demo step by step): the exact four `./gradlew`/binary/`npm` commands
  under "1. Convenience script" above, run one at a time in separate
  terminals, using the exact ports from the table.
- **The story to narrate**: open both inspector UIs; in each, click into the
  `shopping` graph from the Home screen; select (or, once `V1B-FE` has
  landed, pin) the `items` cell in both; open
  `http://localhost:18191` in a browser tab, add an item; watch the `items`
  cell's state view update live in peer A's inspector first, then — as the
  cross-JVM chain propagates and B's own `items` union folds it in — in peer
  B's inspector too. Note this needs `V1a` (state actually streams;
  otherwise the panel only updates on manual re-selection) — say so
  explicitly as a stated dependency, the same way this ticket does.
- **Cleanup**: the script's own `trap` kills both JVMs and both Vite
  instances on Ctrl+C or exit; if run manually, `kill` each backgrounded
  process or close each terminal.
- **Troubleshooting**: a "port already in use" failure most likely means a
  concurrent session already has one of these ports — this repo's demos are
  commonly run concurrently by multiple sessions (see `AGENTS.md`'s
  multi-agent run discipline); re-run with different ports if so, and note
  that this is why the ticket's ports deliberately avoid the repo's common
  defaults (`8080`/`8081`/`7071`/`5173`).

## Files expected to touch

- `doc/demo-shopping-inspector.md` — new.
- `scripts/demo-shopping-two-inspectors.sh` — new, executable.

Touching any other file: not permitted for this ticket — stop and flag the
orchestrator instead (see "Solution direction" above for the specific
`demo/shopping` case).

## Read first

- `demo/shopping/README.md` — the existing single-inspector-free two-JVM
  recipe this ticket extends.
- `demo/shopping/src/main/kotlin/civictech/demo/Main.kt:39-44,150-171,
  243-335` — peering mode, `startInspector`, and `main`'s flag parsing
  (`--inspect-port`, `--net-name`, `--listen`, `--peer`, `stripPairs`).
- `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmInspectorTest.kt` — the
  automated precedent for launching two real JVMs this exact way.
- `inspect/ui/README.md`'s "Two-JVM run recipe" (under "M5-NET",
  lines 218-229) — the closest existing manual recipe, and
  `INSPECT_BACKEND`'s meaning (`inspect/ui/vite.config.ts`).
- `scripts/stage-preview.sh` — this repo's existing convention for a small
  shell convenience script (though it does not manage background processes;
  your script needs `bash`'s array/`trap` support, which is why it is
  `#!/bin/bash` rather than `#!/bin/sh` like that one — note this deviation
  in your report).
- `doc/demo-findings.md` — the existing `doc/demo-*.md` naming precedent your
  new runbook follows.
- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §Verticals
  (V1-DEMO) — "no remote-state capability needed" (this demo needs nothing
  from the deferred V4 distribution work).

Do not modify: anything under `demo/**`, `inspect/**`, `kernel/**`,
`concord/**`, or any plan document other than this ticket's own `**Status**:`
line.

## Acceptance criteria

- [ ] `doc/demo-shopping-inspector.md` exists and covers every bullet listed
      under "2. Runbook doc" above.
- [ ] `scripts/demo-shopping-two-inspectors.sh` exists, is executable, and
      matches the script given above (or documents exactly what you had to
      change and why).
- [ ] Running the script once succeeds end to end: both inspector UI URLs
      (`:5191`, `:5192`) respond, both shopping app URLs (`:18191`, `:18192`)
      respond, and Ctrl+C cleanly stops every process it started (no leftover
      `java`/`vite` processes on those ports afterward).
- [ ] No file outside the two listed in "Files expected to touch."

## Verify

```bash
chmod +x scripts/demo-shopping-two-inspectors.sh
./scripts/demo-shopping-two-inspectors.sh &
SCRIPT_PID=$!
sleep 45   # first run builds :demo:shopping and installs inspect/ui deps
curl -sf http://localhost:18191/ >/dev/null && echo "shopping A OK"
curl -sf http://localhost:18192/ >/dev/null && echo "shopping B OK"
curl -sf http://localhost:5191/ >/dev/null && echo "inspector UI A OK"
curl -sf http://localhost:5192/ >/dev/null && echo "inspector UI B OK"
kill -INT "$SCRIPT_PID"
wait "$SCRIPT_PID" 2>/dev/null || true
```

## Report on completion

- Checks run and their results (paste the four `curl ... OK` lines).
- Files actually touched, and any not in the claim above.
- Whether `--inspect-port`/`--net-name`/`--listen`/`--peer` worked exactly as
  described, or you had to stop and flag a discrepancy.
- Any port collisions hit against a concurrent session, and how you resolved
  them (re-running with the same ports after the other session freed them is
  preferred over silently renumbering — if you renumbered, say so and update
  both files consistently).
- Anything specified here you could not do, and why.
