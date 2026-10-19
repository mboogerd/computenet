# Studio

Run the backend with `./gradlew :demo:studio:run --args='18440 --data /tmp/studio'`.
For development, run `npm install && npm run dev` in `demo/studio/ui/`.
For a built UI, run `npm run build` there, then `./gradlew :demo:studio:installDist`.
From the repository root, launch
`demo/studio/build/install/studio/bin/studio --ui demo/studio/ui/dist`.
Default data: `~/.local/share/computenet/studio/default`; default port: 18440.

Create, open and remove workflows using the toolbar switcher. Each workflow runs
independently in the backend, even when another is open in the UI. The URL hash
`#/w/<id>` preserves the open workflow across reloads and browser navigation.
An empty workspace starts with no workflows and offers “Create your first workflow”.

`--data/workflows.json` is the atomically written registry of id, name and creation
time. Names are trimmed, 1–80 characters and unique ignoring case. Each workflow
owns a runtime/ManagedHost and all its files under `--data/workflows/<id>/`;
workflows cannot share wires. A journal load failure marks only that workflow as
failed, with its error visible in the switcher. Removing a workflow stops its cells
and processes, ends its SSE streams and moves its directory intact to
`--data/trash/<id>-<ISO timestamp>/`. Trash is never automatically deleted.

The API is workflow-scoped; old `/api/graph`, `/api/nodes`, `/api/edges` and `/events`
routes are removed:

- `GET /api/workflows` lists `{id, name, createdAt, nodeCount, status, error}`.
- `POST /api/workflows {"name":"…"}` creates and returns a workflow (duplicate: 409).
- `DELETE /api/workflows/{id}` moves it to trash (204; unknown id: 404).
- Under `/api/workflows/{id}/`: `GET graph`, `POST nodes`, `PATCH/DELETE nodes/{n}`,
  `POST nodes/{n}/submit|fire|run|generate|approve`, `GET nodes/{n}/runs[/{runId}]`,
  `POST edges`, `DELETE edges/{e}`, and `GET events` (SSE).

Per-graph payloads are unchanged. Unknown workflow ids return 404; routes in a
failed workflow return 409 with its load error. Listing workflows does not open
or rerun their nodes. All routes retain the Host and JSON content-type guards.

Text nodes submit changes; Git nodes watch commit refs (first observation is silent).
Exec nodes invoke argv templates or read-only Claude/Codex agents. Program nodes
generate Python 3 stdlib code; review its sandboxed preview and promote its SHA-256
before sending its output downstream. Inputs dedupe
equal changes and compose pending changes while a process runs. Every canvas node
is a kernel cell; live wires use admitted staged links and catch-up.

Fire adds an ISO-8601 `fired` timestamp to the stored text/git change, triggering
a fresh downstream run even when the text or ref is unchanged. Restart and re-link
catch-up replay that stored change unchanged. Exec/program runs with unchanged
stdout retain their output and record run history without emitting downstream.
Promoting a program recomputes and publishes once all inputs are held, including
manual programs. Later inputs follow the live node's auto setting. A program with
no approved implementation keeps its live state `stale` until promotion.
Blank agent models use the CLI default, including the user’s Codex configuration.

The service binds loopback, validates Host on all routes, requires JSON on
POST/PATCH, and sends no CORS headers. Agents use read-only tools; generation
has no tools. Custom commands are user-authored and run with your OS permissions; Claude/Codex
cells use their own read-only modes. These exec cells are not sandboxed by Studio.
Every programmable Python run, live or preview, uses macOS `sandbox-exec` with:

```scheme
(version 1)(allow default)(deny network*)(deny file-write*)
(allow file-write* (subpath (param "WORK")) (literal "/dev/null"))
```

`WORK` is the run's fresh, canonical scratch directory, also used as `TMPDIR`.
Python can read local files, write inside WORK and `/dev/null`, and print output;
it cannot write elsewhere or access the network. If `/usr/bin/sandbox-exec` is
missing (including on non-macOS systems), programs fail with a clear error.
There is no unsandboxed fallback. The profile does not impose resource quotas.

Topology and executable node configuration live in each workflow’s `studio.journal`, using
one kernel `ApplyContext` and synchronous `FileJournal` for topology and data.
Node state, held inputs, generated code/approval hashes and the latest job record
are cell-owned JSON snapshots; non-effectful journaled handlers also preserve
changes after the last checkpoint. `state.json` is no longer read or written.
`graph.json` contains only canvas x/y/title metadata and canvas-edge-to-port
mappings. Run artifacts (`run.json`, stdout and stderr) remain plain files. Disposable
preview artifacts live separately under `nodes/<id>/previews/`; the `/runs` API
continues to list live runs and generation jobs. Each Python run materializes its
journaled code in its own `work/cell.py`.
Root-level v2 data migrates once into `workflows/default/` as “Default”, including
its journal, canvas metadata, node artifacts and Python sandbox profile. Interrupted
moves resume on startup. V1 plain-file data without a v2 journal is not upgraded
to journaled topology; journals are not a supported cross-build interchange format.

Recovery finishes before git polling, intake services or process workers start.
A durable inbox retains deliveries until intake commits their resulting state.
Equal held inputs absorb recovery/re-link catch-up, including fresh checkpoint
baselines. Restart never automatically retries a process. The latest job ledger
records `started(runId, SHA-256(inputs))` synchronously before launch, then
`completed(runId, result)` together with its output/code and node state. A started
job without a completion becomes `interrupted`: status `stale`, error
`previous run interrupted`. Its external effect may already have happened; an
explicit Run starts a new job. This does not depend on `Effectful` frontiers.

Studio checkpoints and compacts after every 100 committed state/structural
changes and on clean shutdown: this bounds normal replay work while avoiding a
full-graph snapshot on every edit (individual journal appends already fsync).
An empty graph keeps its journal tail because the kernel refuses an empty
checkpoint. Shutdown preserves live topology; it never records node deletions.
A cold kernel precheck refuses invalid links before GraphSpec's write-ahead
append. The same context and shared journal own program promotion bookkeeping.

Guarded SSE uses DemoShell's framing helpers because its SSE registration API has
no request-filter hook. The HTTP/SSE state contract has the additive preview field described below.


Programmable nodes expose this additive field in GET snapshots and SSE state frames:

```json
"preview": {
  "codeHash": "candidate sha256",
  "status": "idle",
  "output": {"old": null, "new": "candidate output"},
  "error": null
}
```

`preview` is null when no candidate exists. Its status is `idle`, `running` or
`failed`; output is a change object or null, and error is a string or null. Preview
runs immediately on held inputs after generation, and on new inputs regardless of
live auto mode. While busy it coalesces inputs into one follow-up run. A graph-owned
shadow cell shares the live gate's input fan-out; a separate Observe-role collector
receives its output. Production subscribers stay attached to the live cell. New
candidate code does not clear `approvedHash`: the approved implementation keeps
running until its replacement is promoted. Preview history does not increment the
live run count. The panel displays both outputs, sandbox status and errors; failed
previews require an explicit confirmation before the UI requests promotion.

`POST /api/workflows/{workflowId}/nodes/{id}/approve {"hash":"..."}` promotes exactly the current candidate
hash through `ApplyContext.promote`, using the shared data/topology journal, a
traffic gate and `StateMigrating` state handoff. A wrong hash returns 409. Detached
live and candidate jobs are fenced and their process trees stopped before the
swap; stale completions cannot publish. Handoff preserves held inputs, output and
job history while selecting the candidate's code and approval hash. A fresh
computation explicitly publishes the promoted output; equal output stays deduped.

Recovery discards unpromoted kernel shadows without executing Python. Their stored
code remains on the live cell; the first graph/SSE snapshot request, new input or
approval recreates the candidate lazily. Approving directly after restart works.
Completed promotion survives a compacted restart with identical state and no rerun.
A journal copied immediately after kernel COMMIT, before Studio's follow-up state
commit or recomputation, also recovers the promoted code and approvedHash. In that
window output is still the incumbent's last output: swap and publication are not
one atomic operation, and recovery never implicitly reruns it; explicit Run or a
new automatic input publishes it. Tests exercise clean restart and fsynced journal
crash images, not process termination at every instruction, storage failure at
every kernel boundary, or OS power loss. Kernel files are unchanged.
