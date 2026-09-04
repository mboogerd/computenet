# dialogue — an argumentation map built live from a conversation transcript

Part of epic computenet-2aw (AGO1). `civictech.dialogue.DialogueApp` drives a
transcript of utterances through extraction, binding and reconciliation onto
the same argumentation-graph model `demo/agora` uses, and serves the result
over `demo/agora/ui`'s existing HTTP/SSE contract — no frontend change is
needed to render a dialogue-built map.

## Run (manual check)

Start the backend (port first — `demoPort` reads the first non-`--`
argument, so it must precede the flags; 8090 avoids 8080, which is routinely
squatted by other sessions on this machine):

```
./gradlew :demo:dialogue:run --args="8090 --transcript $(pwd)/demo/dialogue/src/test/resources/bs20-because.jsonl --journal /tmp/dialogue"
```

**Both file arguments have to be absolute** (hence the `$(pwd)`, run from the
repository root). Gradle's `application` plugin gives `:demo:dialogue:run` a
working directory of the *subproject* — `demo/dialogue/`, not the repo root —
so a repo-root-relative `--transcript` path resolves against
`demo/dialogue/demo/dialogue/…` and boot dies with a bare
`FileNotFoundException` before the port is ever announced. Verified
2026-09-04: the relative form fails, the `$(pwd)` form above serves
`/graph` with 17 nodes.

Point the existing frontend at it (`vite.config.ts` already proxies `/graph`
and `/events` to `AGORA_BACKEND`, so no frontend change is needed):

```
cd demo/agora/ui && npm install && AGORA_BACKEND=http://localhost:8090 npm run dev
```

Drive the transcript and watch the map build in the browser:

```
curl -X POST -d 'action=replay&pace=4' localhost:8090/transcript   # paced replay, admits per utterance
curl -X POST -d 'action=step' localhost:8090/transcript            # admit one utterance at a time
curl -X POST -d 'action=reset' localhost:8090/transcript           # retract everything (AGO1-REPLAY-03)
```

Read back what has happened:

```
curl localhost:8090/transcript             # loaded utterances, turn index, per-utterance extraction status + counts
curl 'localhost:8090/provenance?ref=<ref>' # the utterances behind a claim/edge ref (or ?key=<canonical key>)
```

`--transcript <file>` only *loads* the transcript at boot — nothing is
admitted until a `replay` or `step` action is issued.

`demo/dialogue/src/test/resources/bs20-because.jsonl` is a small (6-utterance,
3-speaker) capture/manual-run transcript: several "X because Y." sentences —
including one repeated endpoint text across two speakers, so the resulting
map has a shared node with more than one utterance behind it — one plain
claim, and one disagreement-marker line. JSONL has no comment syntax, so this
paragraph is where that transcript's shape is documented, not the file
itself. It is F5's own capture input; the demo transcript and cassette proper
belong to feature computenet-2aw.6.

## Test

```
./gradlew :demo:dialogue:test
cd demo/agora/ui && npm test
```

`demo/agora/ui/test/dialogue-graph.test.ts` feeds a captured `/graph`
response (`demo/agora/ui/test/fixtures/dialogue-graph.json`, captured from
`bs20-because.jsonl` under `--extractor rule`) through `GraphStore`'s own
parsing/diff layer — the same layer `sync.test.ts` exercises against agora's
fixtures — proving a dialogue-built graph is shape-compatible with what the
existing frontend already renders ([AGO1-OBS-01], BS-20). The test file's own
header documents the capture procedure in full.
