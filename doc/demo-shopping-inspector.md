# Two-JVM Shopping Demo with Inspector UI

This runbook demonstrates watching anti-entropy convergence in real time across two independent ComputeNet peers.

## What this demonstrates

This demo runs two independent `:demo:shopping` JVMs peered over the real `:wire` transport (the M5 configuration), each hosting its own `:inspect` backend for introspection. When an item is added to the shopping list on peer A, it converges onto peer B's graph via the shopping app's own anti-entropy-on-reannounce mechanism. Once the V1a dependency (live `state.summary` streaming) is in place, the converging cell's state visibly updates in both inspector UIs without requiring a manual refresh, showing the convergence process live across the network boundary.

## Prerequisites

- JDK 21 toolchain (ComputeNet repository standard)
- Node.js `^22.0.0` (as specified in `inspect/ui/package.json`'s `engines`)
- `curl` command-line utility

## One-liner

From the repository root:

```bash
./scripts/demo-shopping-two-inspectors.sh
```

Ctrl+C stops all processes cleanly.

## What it prints

The script outputs the following URLs when ready:

- Peer A inspector UI: `http://localhost:5191`
- Peer B inspector UI: `http://localhost:5192`
- Peer A shopping app: `http://localhost:18191`
- Peer B shopping app: `http://localhost:18192`

The script also allocates the following ports:

| purpose | peer A | peer B |
|---|---|---|
| shopping HTTP | `18191` | `18192` |
| :wire websocket | `19201` (shared — A listens, B dials) | |
| inspector | `17091` | `17092` |
| inspector UI (Vite dev) | `5191` | `5192` |

These ports are chosen to avoid collision with the repository's and Vite's own defaults (`8080`, `8081`, `7071`, `5173`), allowing concurrent runs with other sessions' recipes.

## Manual walkthrough (step by step)

If the convenience script cannot be used, or to understand each step:

1. Build the shopping demo distribution:
   ```bash
   ./gradlew :demo:shopping:installDist
   ```

2. Ensure `inspect/ui` dependencies are installed:
   ```bash
   cd inspect/ui && npm ci && cd ../..
   ```

3. Start peer A (listener) in a terminal:
   ```bash
   ./demo/shopping/build/install/shopping/bin/shopping 18191 \
     --listen 19201 --inspect-port 17091 --net-name jvm-a
   ```

4. Start peer B (dialer) in another terminal:
   ```bash
   ./demo/shopping/build/install/shopping/bin/shopping 18192 \
     --peer ws://localhost:19201 --inspect-port 17092 --net-name jvm-b
   ```

5. Start inspector UI for peer A in a third terminal:
   ```bash
   cd inspect/ui
   INSPECT_BACKEND=http://localhost:17091 npx vite --port 5191 --strictPort
   ```

6. Start inspector UI for peer B in a fourth terminal:
   ```bash
   cd inspect/ui
   INSPECT_BACKEND=http://localhost:17092 npx vite --port 5192 --strictPort
   ```

Wait for all services to be ready before proceeding. The inspector endpoints respond at `/api/inspect/topology`, and the UI servers respond at `/`.

## The story to narrate

1. Open both inspector UIs side by side: `http://localhost:5191` and `http://localhost:5192` in separate browser tabs or windows.

2. In each inspector, click into the `shopping` graph from the Home screen to see the graph topology.

3. Select (or, once the V1B-FE feature lands, pin) the `items` cell in both inspectors. This opens the state view panel for that cell.

4. Open the shopping app for peer A in another browser tab: `http://localhost:18191`.

5. Add an item to the shopping list using the peer A interface.

6. Observe the `items` cell's state view update live in peer A's inspector first (immediately, since the item originates there).

7. Watch as the cross-JVM chain propagates and peer B's own `items` union folds the new item in, causing the cell's state view to update in peer B's inspector as well.

**Important dependency note:** This live update behavior requires the V1a feature (state actually streaming) to be in place. Without V1a, the inspector panel only updates when the cell is manually re-selected. The script will run and items will converge, but the visual updates in the inspector UI will not be live.

## Cleanup

The convenience script sets up a `trap` that kills both JVMs and both Vite dev servers when the script exits via Ctrl+C or receives SIGTERM/SIGINT signals.

If running the steps manually, clean up by:
- Closing each terminal running a JVM peer or Vite dev server, or
- Sending `kill` signals to each backgrounded process

Verify cleanup by checking that no Java processes are listening on ports 18191, 18192, or 19201, and no Node.js/Vite processes are listening on ports 5191 or 5192.

## Troubleshooting

**Port already in use:** If the script exits with a "port already in use" error, a concurrent session (likely another agent in this multi-agent run) already has one of these ports. ComputeNet demos are commonly run concurrently by multiple sessions per the `AGENTS.md` multi-agent run discipline. 

Options:
- Wait for the concurrent session to finish and re-run with the same ports (preferred).
- If re-running is not practical, re-run the script with different port numbers (modify the variables in the script or run the manual steps with different ports), and note any changes made in your report.

The ticket's port choice specifically avoids the repository's common defaults (`8080`, `8081`, `7071`, `5173`) to minimize collisions in concurrent runs.

**Peers not connecting:** If the script exits because peer B cannot connect to peer A's websocket listener, ensure that both shopping processes have started successfully and are ready to accept connections. The script waits for the inspector endpoints to be ready before starting Vite, so both peers should be healthy before the UIs are launched.

**Inspector UI not loading:** If the inspector UI returns an error, verify that the correct `INSPECT_BACKEND` URL is being used and that the inspector API endpoint (`/api/inspect/topology`) is responding.
