# deliberate

Ask a question and watch a deliberation graph grow for it live. Two LLM
CLIs (Claude Code and Codex) propose arguments for and against each claim,
recursively. **Jev** (TypeSafe System One, `jev-latest`) makes every judgment
that steers the exploration: duplicate detection, saturation, relevance, and
the stances credence is computed from. The kernel-hosted agora argumentation
graph propagates credence. You can override the explorer per claim: force it
to expand a claim, or stop it. The goal specification is [`SPEC.md`](SPEC.md).

## Credence model

1. Every claim gets a Jev *plausibility* judgment (five levels, false … true, mapped to [0,1]). It is judged on the claim alone, with the question as context.
2. Every pro/con edge gets a Jev *relation strength* judgment: how strongly the child would bear on the parent if it were true.
3. Both judgments are recorded as stances of the agora user `jev`.
4. Agora propagates credence with DF-QuAD: supports raise a claim from its plausibility, attacks lower it, and each argument is weighted by its own credence and the strength of its edge.
5. The UI shows the credence agora propagates. The deliberation code never computes credence itself.

## Prerequisites

- JDK 21 (the Gradle toolchain provisions it) and Node 22+ for the UI.
- `TYPESAFE_API_KEY` in the environment. Jev judges every step, and the backend refuses to start without the key.
- The `claude` and `codex` CLIs on `PATH` and logged in. Each proposer call runs one CLI process with no tools, in an empty temp directory, with a 120 s timeout. Use `--proposers claude` or `--proposers codex` to run with just one of them.

## Run

```bash
# 1. build the UI once (the backend serves ui/dist at /)
cd demo/deliberate/ui && npm install && npm run build && cd -

# 2. start the backend (default port 8091)
./gradlew :demo:deliberate:run --args="--max-depth 2 --max-claims 30"
open http://localhost:8091
```

Gradle's `run` task uses `demo/deliberate` as its working directory, and the backend finds `ui/dist` from there or from the repo root. Pass `--ui <dir>` to serve the UI from somewhere else. If there is no built UI, `/` serves a one-line hint page. The API still works.

**UI dev mode** (hot reload): leave the backend running and run `cd demo/deliberate/ui && npm run dev`. Vite proxies `/graph`, `/events`, `/question` and `/override` to `DELIBERATE_BACKEND`, which defaults to `http://localhost:8091`.

## Knobs

| flag | default | meaning |
|---|---|---|
| `[port]` (first bare arg, or `$PORT`) | 8091 | HTTP port |
| `--proposers claude,codex` | both | which CLIs propose arguments |
| `--claude-model <m>` / `--codex-model <m>` | CLI default | model passed to that CLI |
| `--max-processes <n>` | 4 | concurrent CLI processes, app-wide (EXP-07) |
| `--args-per-call <n>` | 2 | arguments per proposer call, per side |
| `--max-rounds <n>` | 3 | rounds per claim before `ROUND_LIMIT` |
| `--max-depth <n>` | 3 | claims deeper than this are `DEPTH_LIMIT` |
| `--max-claims <n>` | 60 | claims per question; the rest become `BUDGET` |
| `--saturation <p>` | 0.7 | a side with Jev saturation ≥ p gets no more proposals |
| `--relevance <p>` | 0.5 | a claim with Jev relevance < p is `PRUNED` |

The budget is spent in breadth-first order. With the defaults, the root alone
can take up to 2 proposers × 2 sides × 2 arguments × 3 rounds = 24 claims. To
get a deeper tree from a small budget, lower `--args-per-call` and
`--max-rounds`: for example, `--args-per-call 1 --max-rounds 2 --max-claims 30`.

## HTTP

- `POST /question` with form field `text=…` returns `{"root":"<ref>"}`.
- `POST /override` with form fields `id=<ref>&mode=AUTO|EXPAND|STOP` returns `ok`. Bad input returns 400, and an unknown ref returns 404.
- `GET /graph` returns a `GraphDto` (see `Dto.kt`).
- `GET /events` is an SSE stream. Every message is a full `GraphDto`, and messages are coalesced to at most about 10 per second.

## Cost and time

Every round of every claim makes one CLI call per proposer per unsaturated
side, and each claim also costs about 4–6 Jev requests. A single CLI call takes
roughly 4–10 s, but at most `--max-processes` run at once, so when a whole tree
level expands together, most of the wall time is spent queueing for a process
slot.

Measured on 2026-09-27 with `--max-depth 2 --max-claims 30 --max-rounds 2
--args-per-call 1`: a 30-claim tree took about 1–2 minutes. It ran 14–17 rounds,
which is roughly 60 CLI invocations billed to your Claude and Codex accounts,
plus a couple of hundred Jev requests. The default 60-claim configuration costs
roughly twice as much. Jev calls slower than 20 s are logged to stderr. Nothing
persists across restarts.

## Tests

```bash
./gradlew :demo:deliberate:test --rerun         # fakes only, no network
DELIBERATE_LIVE=1 ./gradlew :demo:deliberate:test --tests '*LiveSmokeTest' --rerun
cd demo/deliberate/ui && npm run typecheck && npm test
```
