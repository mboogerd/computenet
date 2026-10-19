# Studio

Run the backend with `./gradlew :demo:studio:run --args='18440 --data /tmp/studio'`.
For development, run `npm install && npm run dev` in `demo/studio/ui/`.
For a built UI, run `npm run build` there, then `./gradlew :demo:studio:installDist`.
From the repository root, launch
`demo/studio/build/install/studio/bin/studio --ui demo/studio/ui/dist`.
Default data: `~/.local/share/computenet/studio/default`; default port: 18440.

### Typed ports and output validation

Every node in graph snapshots and SSE graph frames gains
`types: {inputs: {port: {fields: ["new"], kind: null}}, output: {kind, extras, schema}}`.
The vocabulary is `text`, `gitRange`, `issueSet`, `judgment`, `json`, and `any`.
Text/custom/Beads writer execs produce text; Git produces gitRange; Beads sources
produce issueSet; Jev judges produce judgment. A gate preserves its selected
input's type, schema and extras and adds `gate`; disconnected gates resolve to any.
Claude/Codex produce text unless `outputSchema` is configured. Programs expose
the schema of their approved artifact, defaulting to text for legacy programs.
Types are derived from declarations, never from the latest sample output.

Input ports and requirements share the substitution parser. `{{c}}` and
`{{c.new}}` require `new`; `{{c.repo}}` requires the envelope field `repo`.
`{{c:gitRange}}` adds an explicit type constraint; repeated references union their
fields and conflicting annotations are rejected. Flat lookup is preserved,
including literal field names containing dots or hyphens. JSON schema properties
describe the JSON **inside the string `new`**, not envelope extras: `{{c.new}}`
returns that JSON text; `{{c.severity}}` requires an envelope field, and
`{{c.new.severity}}` is a literal flat field name, not nested access. An absent
field marks the node `invalid input` and launches nothing. Present null metadata
still renders empty. Programs can declare optional `inputRequirements`, e.g.
`{"in":{"kind":"gitRange","fields":["repo","new"]}}`; omitted inputs require any.

Wiring and configuration edits validate the tentative whole graph, including
downstream gate chains and the second logical wire sharing a physical link.
Incompatible edits return 409 with the precise port requirement before journal
or canvas writes. Removing a referenced input from a template requires deleting
its wire first. Any/loose JSON producers receive arrival checks. Held values and
catch-up are checked against current requirements and producer schemas before a
run. Config edits fence obsolete workers so their completions cannot publish.
No kernel payload link policy is installed.

Historical topology always replays. Incompatible legacy wires remain visible as
`{..., status: "quarantined", reason: "c: requires field 'repo'; text offers new, old"}`;
valid wires have `status: "ok", reason: null`. Quarantined wires deliver nothing,
and their held values cannot make the target runnable. Other nodes keep working.
Delete the wire or fix its declarations to lift quarantine. Unrelated edits can
retain an existing quarantine; new incompatibilities are refused. The canvas
shows dashed red quarantine wires with reasons, compatible drag targets, port
colors and a legend; the side panel shows derived types and a schema editor.

Claude/Codex/program config accepts optional `outputSchema` (a JSON Schema object;
null removes it). Studio pins NetworkNT **2.0.8** (Jackson 2 release line), only in
this module, with YAML excluded. The dialect is **2020-12**. Accepted keywords:
`$schema`, `$defs`, `$ref`, `type`, `properties`, `required`, `additionalProperties`,
`items`, `enum`, `const`, `title`, `description`, `minimum`, `maximum`,
`exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `minLength`, `maxLength`,
`minItems`, `maxItems`, `uniqueItems`, `minProperties`, `maxProperties`.
Unknown keywords (including format, pattern and combinators) are refused at config
time, as are malformed schemas. References are only local `#/$defs/name`, without
escaped, percent-encoded or slashed names, recursion, or more than 16 reference hops/4096 expanded
schema nodes. External, file and network refs are refused; no remote loader is
enabled. Schemas are capped at 64 KiB UTF-8 and depth 32; JSON values at 1 MiB and
depth 64. A synchronized LRU caches 128 compiled schemas. Studio checks envelope
presence and nominal shapes separately; it does not attempt general schema subtyping.

Claude gets `--json-schema <inline>`; structured responses require success,
explicit `is_error: false`, and a non-null `structured_output`, with no narrative
`result` fallback. Codex gets `--output-schema <file>` and its final `-o` file is
validated. Python stdout uses the same validator. Validation precedes equality
dedupe and publication: a bad output completes the ledger as failed with its
error, retains the previous output, and emits nothing. CLI tests use synthetic
fixtures; no model adherence claim or paid model calls are part of these checks.

Program hashes cover code plus canonical output schema. Schema-less hashes retain
the old code-only SHA-256. Changing the schema clears approval and creates a new
candidate hash for preview/promotion, even when code is unchanged; Run remains
blocked until promotion. Regenerated code with an unchanged schema continues to
retain the approved implementation until promotion. Checkpoints add `liveSchema`
alongside `liveCode`; promotion transfers both and recovery preserves the approved
artifact. This is additive Studio metadata, not a new cross-build kernel journal
compatibility guarantee.

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

### Beads work items and findings

Add a **Beads** source with the toolbar, then set `workspace` to an absolute
directory containing `.beads`. `bd` must be on the server's PATH. The source has
no input ports. Its config is `{"workspace":"/absolute/workspace","query":"ready",
"intervalSec":60}`. `query: "list"` also accepts optional `status`, `label`, and
`type` filters (the CLI's default list status semantics apply when status is blank).
Both queries use `--limit=0` to watch the entire matching set.

Studio reads through argv-only `bd --directory <workspace> --readonly --sandbox
ready|list --json`, with only those fixed read commands and filters. This is
smaller than importing Beads-mirror's Dolt projection, and the kernel's
`DemoIsolationRatchetTest` prohibits new dependencies between demo applications.
No demo dependency or isolation exception is added. `--readonly` refuses writes;
`--sandbox` disables auto-push. The workspace is validated before CLI launch and
Beads discovery is pinned to its `.beads` directory. There is one poll in flight
per source; poll errors appear in `state.error` and polling continues.

The first successful observation records a silent baseline in the cell's
checkpoint. Each observed difference emits **one change**, for example:

```json
{"old":"[]","new":"[{\"id\":\"ws-abc\",\"title\":\"Check result\",\"status\":\"open\",\"priority\":2,\"type\":\"task\",\"labels\":[]}]","added":["ws-abc"],"removed":[],"changed":[],"workspace":"/absolute/workspace"}
```

`old` and `new` are JSON **text**, each encoding an array sorted by id, containing
only `{id,title,status,priority,type,labels}`; labels are sorted too. `old` can be
null on an initial Fire. `added`, `removed`, and `changed` are sorted id arrays;
changed means one of those small fields changed for a retained id. Description
or timestamp edits alone do not emit. Changes between polls are observed as one
net difference. The card shows the current count, including the silent baseline,
and the latest added ids. Downstream templates use `{{x.new}}`, `{{x.old}}`,
`{{x.added}}`, `{{x.removed}}`, `{{x.changed}}`, and `{{x.workspace}}`, replacing
`x` with the input port name. Array template values render as JSON text.

Pending bursts compose the first old set with the latest new set, recomputing
all three id arrays across that interval. Fire re-emits the stored change with
an ISO-8601 `fired` timestamp, even if the set is unchanged. Recovery/re-link
catch-up retains that exact stored change and dedupes it. Changing the workspace,
query or active list filters establishes a new silent baseline; changing just
the interval retains it. Each workflow owns its polling runtime independently.

Choose either preset in an **exec** cell's settings to write findings:

- `bd-comment`: configure `workspace`, `issue` and `text` templates. Runs
  `bd --directory <workspace> --sandbox comment -- <issue> <text>`.
- `bd-create`: configure `workspace`, `title`, `description`, `type`, `priority`
  (0–4), and comma-separated `labels` templates. Runs `bd ... create --json`
  with the corresponding named flags. Issues are always **unparented**; no
  parent option is forwarded. Defaults: type `task`, priority `2`, empty labels.

Both presets default to `auto: false`, including when switching from an automatic
custom command. Connect inputs, review settings, and use **Run**. Every template
value stays within its own argv element; no shell interprets it. The configured
workspace is the working directory; these presets do not infer a tracker from
upstream inputs or `cwd`. Output uses the normal `{"old","new"}` change envelope.

**These presets write to the real tracker of the configured workspace. This
repository's tracker syncs to DoltHub on push.** Studio does not push or sync it;
`--sandbox` also disables Beads auto-push. Inputs, results and job status use the
normal durable exec ledger. Recovery never automatically retries an interrupted
write: its external effect may already have happened, so inspect the tracker
before choosing Run again.

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


### Jev judgments and gates

Add **+ Jev**, then place a `judge` cell before Claude/Codex to avoid expensive
runs for changes that do not qualify. Set `TYPESAFE_API_KEY` in the server's
environment before launching Studio. The key is never a node setting and is not
written to the journal, canvas metadata, run artifacts or logs. Unknown judge
configuration fields (including credential fields) are rejected before storage.
A missing key or API failure records a failed, completed job without output.

Example gate config:

```json
{
  "question": "Is this CI failure caused by the diff rather than flakiness? Diff: {{diff}} Log: {{log}}",
  "output": "probability",
  "mode": "gate",
  "threshold": 0.7,
  "passPort": "diff",
  "auto": true
}
```

Input ports are the distinct placeholder roots in question order, using the same
substitution rules as exec prompts. Every input still holds a change object
`{"old": null, "new": "text", ...extra}`. Defaults are `output: "boolean"`,
`mode: "judge"`, `threshold: 0.5`, `passPort: ""` (first input), `choices: []`,
`passChoices: []`, `model: ""` (`jev-latest`), `timeoutSec: 60`, and `auto: true`.
Choice output requires 1–255 distinct nonblank strings in `choices`; choice gates
pass only answers listed in `passChoices` (empty means pass none). Boolean output
maps Jev's Noul probability to true at 0.5; probability output retains the numeric
value as text. Choice probability is the selected option’s probability, not its
confidence. Probability gates use the inclusive configured threshold.

Judge mode emits this change (the first `old` is null):

```json
{"old": "false", "new": "true", "probability": 0.91, "reason": null}
```

Gate mode passes the selected input's original `old`, `new` and extra fields,
adding/replacing only `gate: {"probability": 0.91, "reason": null}`. A rejected
judgment emits nothing, retains held inputs and the previous output, and records
status/outcome `gated`. Like exec/program, equal output `new` text suppresses
publication while retaining the completed run. Input bursts during a running
judgment compose into one follow-up judgment (first pending `old`, latest `new`).
Manual Run and the auto toggle work like exec. Re-link and recovery dedupe held
inputs; interrupted jobs recover as stale without calling Jev again.

The side panel edits choices one per line and shows run outcomes and judgments.
State gains `judgment: {last: {answer, probability, reason}, passed, gated}`; gate
counters count completed decisions, including passes suppressed as equal output.
Run details gain `judgment` and `outcome` (`judged`, `passed`, `gated`, `failed`);
run summaries gain `outcome`. Existing fields retain their meanings. Duration is
end-to-end request/retry latency. `costUsd` is populated only for an explicit API
`costUsd`/`cost_usd` amount; token usage is not converted into a guessed cost.
The [System One API](https://docs.typesafe.ai/api) currently returns Noul/Choice
answers and token usage, without promising explanation text or USD cost. `reason`
and `costUsd` are null when absent. HTTP 429/529 retry up to four attempts with
exponential backoff inside one timeout budget; other errors fail immediately.
Raw HTTP bodies, headers and transport exception messages are never run artifacts.
Default tests inject fake HTTP responses and make no real TypeSafe calls.
