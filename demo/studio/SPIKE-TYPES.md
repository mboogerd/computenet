# Studio typed ports spike — 2026-10-19

**Verdict: fits with changes.** Keep the string change envelope; validate logical canvas ports in Studio.
The kernel can enforce a per-instance policy, but existing natures cannot represent these payload types.
No production code changed; no paid model/API calls. These are throwaway probes, not the implementation.

## Evidence key

Repository-relative paths: `S/` = `demo/studio/src/main/kotlin/civictech/demo/studio/`;
`K/` = `kernel/src/main/kotlin/civictech/cell/`; `N/` = `nature/src/main/kotlin/civictech/nature/`;
`T/` = `demo/studio/src/test/kotlin/civictech/demo/studio/typespike/`.

## 1. Kernel natures — NO payload nature; YES per-instance handshake policy

`K/port/PortNatures.kt:24` projects metadata by `owner.javaClass`; its only stamp seam is internal
(`:15`, `:33`), and public `Port.natures` is read-only (`:41`). There is no cell override or factory
descriptor parameter (`S/StudioCell.kt:50`). The registry is keyed by class FQN
(`N/ContractRegistry.kt:198`, `:258`); replacing that entry is global, not per instance.
`N/ContractDescriptor.kt:52` defines a closed axis enum; `K/nature/NatureNegotiation.kt:45` uses a fixed
rank/subsumption lattice. `INSTANCE_SCOPING` means interest slicing, not arbitrary application types.
Precise gap: no public instance payload/schema contract or extensible payload axis reconciled by handshake.
Even separate cell classes carrying `Propagate<String>` do not distinguish schemas: the payload check
compares the erased API class (`K/link/Handshake.kt:76`, `:97`). Do not encode types as fake existing axes.
Proof: `T/KernelTypesSpikeTest.kt:21`, `sameStudioClassHasNoPayloadNatureAndMismatchedWireIsAdmitted`.

However, `LinkSupport.policies` is per port (`K/link/LinkSupport.kt:39`), and handshake runs policies
before installing anything (`K/link/Handshake.kt:301`). A closure resolving immutable declarations by
`LinkRequest.from/to` can reject same-class instances through the kernel, with `mismatch == null`.
Proof: `T/KernelTypesSpikeTest.kt:36`, `publicPerInstanceLinkPolicyMakesKernelRefuseBeforeInstallingAnything`:
text→requires-repo rejects with no installed links; gitRange→same inlet connects, staged on a real host.
This is a policy refusal, not `NatureMismatch`; no generated class per vocabulary type is necessary.

**Boundary:** canvas input names are multiplexed onto one physical inlet; the second edge for a node pair
skips handshake (`S/StudioRuntime.kt:288`, `:313`). Proof: `T/BoundarySpikeTest.kt:61`,
`logicalCanvasPortsShareOnePhysicalLinkSoEachCanvasEditNeedsValidation` (two edges, one kernel link).
Therefore recommend Studio validation on EVERY canvas edit, plus optional kernel policy defense.
Kernel-only enforcement needs distinct logical port contracts/mapping available during admission/recovery.
Cold policies must be declaration-derived, not hot-state callbacks (`doc/spec/10-programming-model/13-links.md:56`).
Policy reconstruction through journal replay, cold precheck, traffic gates and promotion is **unproven**.
Class-per-type plus distinct generated contract APIs is a possible closed nominal alternative, **unproven**:
more KSP/proxy/serializer/factory identities, journal compatibility risk, gate specializations and arbitrary
schema explosion. Class-per-type alone solves neither structural requirements nor dynamic JSON schemas.

## 2. Structured LLM output — flags verified; validate again in Studio

Observed locally: Claude Code `2.1.302`, `codex-cli 0.163.0`; ran `claude --help`, `codex exec --help`
and version commands only. Claude: `--json-schema <schema>` is inline JSON, with `-p --output-format json`.
Codex: `--output-schema <FILE>` reads a schema file; `-o/--output-last-message <FILE>` writes final text.
[Claude CLI docs](https://code.claude.com/docs/en/headless#read-structured-output) specify `structured_output`,
whereas ordinary text is `result`. Studio currently reads only `result` (`S/StudioRuntime.kt:582`).
[Claude validation docs](https://code.claude.com/docs/en/agent-sdk/structured-outputs#error-handling) describe
validation/retries; even success can lack structured data. Require success, no `is_error`, and a present value.
[Codex docs](https://learn.chatgpt.com/docs/non-interactive-mode#create-structured-outputs-with-a-schema)
show schema-constrained final JSON in the `-o` file; Studio already reads that file (`S/StudioRuntime.kt:588`).
Codex help/docs establish requested response shape, not an independent local validation pass.
**Unproven:** installed versions' real model adherence, retry/failure behavior and accepted schema dialects;
no paid calls were made. Never treat process exit 0 or parseable JSON as sufficient validation.
Proof of adapter distinction: `T/BoundarySpikeTest.kt:22`,
`structuredClaudeOutputMustNotUseNarrativeResultOrFallbackOnMissingValue` uses explicitly synthetic fixtures.
`T/BoundarySpikeTest.kt:28`, `parsingCodexFinalFileIsNotSchemaValidation`, parses valid JSON of the wrong type.
Add flags to existing argv builders (`S/StudioProcess.kt:46`, `:56`); preserve current read-only constraints.
Validate extracted JSON before completed-success state/publication; failures finish the ledger as failed and
retain prior output, emitting nothing (`S/StudioRuntime.kt:595`, `:608`). Apply the same validator to Python.

## 3. JSON Schema library — absent; recommend a library for user schemas

Executed `./gradlew :demo:studio:dependencies --configuration runtimeClasspath`: only project modules,
Kotlin, coroutines, annotations and kotlinx-serialization JSON/core; no schema validator or Jackson.
Dependency declarations: `demo/studio/build.gradle.kts:7`; parser setup: `S/StudioModel.kt:6`.
`T/BoundarySpikeTest.kt:28` also proves NetworkNT/Everit/Harrel validator classes absent at test runtime.
[NetworkNT](https://github.com/networknt/json-schema-validator#readme) supports multiple schema drafts;
its documented cost is another JSON representation (Jackson), logging and optional/removable YAML support.
Use a pinned compatible release in **Studio only**, fixed dialect, compiled-schema cache, offline reference
resolution and schema-size/depth limits. Actual dependency integration/performance is **unproven**.
A handwritten type/required/properties/items/enum subset avoids dependencies, but must also define null,
integer-vs-number, nested errors, enum numeric equality, schema validity and unknown-keyword rejection.
It cannot honestly accept arbitrary “JSON Schema”; ignored constraints would turn failed outputs into passes.
Recommendation: library + a documented CLI-supported schema subset; reject unsupported schemas at config time.
Keep inference as a small structural checker; do not attempt general schema implication/subtyping.

## 4. Inference — nine passing cases; presence inference, explicit nominal constraints

Pure prototype: `T/TypePrototype.kt:21` (`infer`), `:34` (`satisfies`), `:48` (`gateOutput`).
Results are accept / reject / check-on-arrival: `any` and loose JSON need runtime checks, not false certainty.
The nine methods in `T/InferenceSpikeTest.kt:9` cover:

| Case | Result / proving method line |
|---|---|
| `{{c}}`, `{{c.new}}` | requires `new`; text accepts (`:9`) |
| repo + old + new, repeated roots | union fields, ordered roots; gitRange accepts (`:14`) |
| text → `{{c.repo}}` | rejects: `c: requires field 'repo'; text offers new, old` (`:20`) |
| issueSet added/removed/changed/workspace | accepts issueSet, rejects gitRange (`:25`) |
| `{{c:gitRange}}` plus field use | nominal constraint; conflicts/typos/malformed tokens reject (`:30`) |
| any / loose json / declared extra | arrival check / arrival check / accept (`:40`) |
| judgment answer and nullable metadata | `new`, probability, reason exist; `answer` does not (`:46`) |
| selected generic gate, then another gate | preserves type/schema/extras; upstream text breaks repo use (`:52`) |
| flat fields vs JSON inside `new` | schema properties inside `new` do not promise envelope extras (`:65`) |

Keep `old: string|null`, `new: string`; json/issueSet validate decoded `new` without changing its wire shape
(`S/StudioModel.kt:11`, `S/StudioBeads.kt:89`). Judgment's answer is `new`, and probability/reason can be null
(`S/StudioJudge.kt:68`). Gate preserves selected change and overwrites only `gate` (`:62`).
Do not infer non-null/value types from access alone. `{{c.repo}}` currently silently substitutes empty
when missing (`S/StudioProcess.kt:41`); fail visibly on arrival instead. Annotations need one shared parser
for discovery AND substitution (`S/StudioModel.kt:52`); today's parser ignores them (test `:30`).
Programs list input names, not templates (`S/StudioModel.kt:97`): require optional explicit per-input
contracts, default any. Resolve gates in topological order; disconnected passPort yields unknown/any.
Kind mapping: text/custom/bd exec→text; git→gitRange; beads→issueSet; judge→judgment; gate→selected input.
Claude/Codex default text, configured schema→json(schema); program→approved code/schema artifact.

## 5. Persistence — additive metadata possible; “unaffected” is too strong

Proof: `T/BoundarySpikeTest.kt:37`, `legacyConfigFactoryAndCheckpointRoundTripWithoutAnyTypeField`,
round-trips current definitions/checkpoints and restarts a real journal with text→requires-repo intact.
Types derivable from existing config need no persisted type cache; preserve factory class/signature and
String envelope (`S/StudioCell.kt:25`, `:32`, `:50`, `:94`). New exec schema/declarations can be optional
config keys with legacy defaults. Legacy programs default text, matching today's stdout protocol
(`S/StudioRuntime.kt:631`). Existing invalid graphs require diagnostic/quarantine, not silent edge deletion
or newly refusing historical topology replay. Revalidate held/catch-up values before scheduling work.
Program code + output schema must form one approved artifact: retain separate live/candidate schemas and
hash both. This requires additive checkpoint metadata; promotion currently selects only code/hash
(`S/StudioCell.kt:83`). Cross-build recovery after such edits is **unproven**, not guaranteed by a same-build
round-trip; kernel journals explicitly refuse unsupported versions (`K/durability/Journal.kt:99`).

## Implementation order and risks

1. Add `StudioTypes.kt`: vocabulary, envelope schemas, inference, tri-state compatibility and gate resolution.
   Share parsing across `StudioModel.kt`, `StudioProcess.kt`, `StudioJudge.kt`; preserve flat lookup semantics.
2. Add schema validation + CLI extraction in `StudioProcess.kt`/`StudioRuntime.execute`; validate before
   dedupe/publication and before intake queues work (`StudioRuntime.accept`). Persist actionable failed runs.
3. Validate tentative graphs atomically in `addEdge`/`patchNode`, including existing downstream gate chains,
   second canvas edges, output schema changes and promotion. Reject incompatible edits before journal writes.
4. Bind program schema to reviewed code in `StudioCell.kt` checkpoints/import and generation/approval flows;
   handle legacy invalid graphs after recovery, then expose derived types/errors via graph/SSE and UI.
   Test bad outputs/no emission, bad arrivals/no launch, config rollback, gate chains, preview/promotion and restart.
5. Optional kernel policy integration comes after a cold/recoverable logical-port design; this spike proves
   a local policy seam only. Risks: multiplexing, replay order, stale worker completion after config changes,
   schema-only changes bypassing approval, nullable metadata, dialect differences and metadata-only output dedupe.

Validation: `./gradlew :demo:studio:test --rerun` executed **113 tests (15 spike), zero failures/errors/skips**.
`32 actionable tasks: 1 executed, 31 up-to-date`; test task executed, with SPIKE.md's no-daemon/in-process workaround and outer sandbox permission. No repository-wide gate (spike scope).

## Outcome

Implemented Studio-level declaration inference, one discovery/substitution parser,
transactional edit validation across gate chains, arrival/held-value checks and
post-replay legacy-wire quarantine. The API and UI expose derived types and wire
diagnostics. Physical kernel payload policies remain deferred because logical
canvas ports still multiplex a physical link.

NetworkNT 2.0.8 (Jackson 2 line, Studio-only, no YAML) validates output schemas and
Claude structured output, Codex final files, and Python stdout before dedupe or
publication. Draft 2020-12, an explicit supported-keyword subset, local acyclic
definition refs, bounded size/depth/reference expansion, and a bounded compiled
cache are documented in README. Unknown/unsupported schemas fail before storage.
Program artifact hashes cover code and canonical schema; additive `liveSchema`
checkpoint metadata survives preview/promotion and restart. Schema-only edits
clear approval. Same-schema regeneration retains the promoted implementation.

Flat lookup is intentionally preserved: JSON properties stay inside the string
`new`; `{{x.new}}` supplies the whole JSON text and `{{x.new.severity}}` means a
literal envelope field. No nested access or general schema implication is added.
Connected input removal is refused until its wire is explicitly deleted. Config
edits fence detached workers. Existing invalid edges are retained and diagnosed,
while unrelated edits may preserve an existing quarantine.

The throwaway `typespike` suite is removed; production inference, schema and
integration tests replace it, including journal-byte equality on refusal, legacy
recovery quarantine, synthetic CLI failures, and real sandboxed Python approval.
Paid model adherence and cross-build kernel-journal interoperability remain
unproven; the CLI tests use synthetic responses, not model calls.

Validation: `:demo:studio:test --rerun` executed **119 tests**, zero failures,
errors or skips; `:demo:studio:installDist` passed in the same gate (25 actionable
tasks: three executed, 22 up-to-date). The final **12** focused schema/validation
tests also executed and passed before that full run. All **53** kernel architecture tests
executed with `--rerun` and passed (31 actionable tasks: nine executed, 22
up-to-date), including module inventory, demo isolation and bypass ratchets.
UI typecheck, **56** Vitest tests and production build passed. The adoption-matrix
checker and its **eight** unit tests passed without regenerating FEATURE-STATUS.
Gradle used SPIKE.md's no-daemon/in-process compiler workaround with outer
execution permission for real sandbox-exec tests.

The extra repository-wide `test` attempt exhausted Gradle Metaspace under the
in-process compiler workaround. A retry with two workers and 2 GiB Metaspace
completed the kernel task, then reproduced Beads-mirror's previously recorded
`ReadySecondSeedDifferentialTest` seed-21 five-minute timeout and an
`EchoSuppressionTwoNodeTest` timeout waiting for the listener's own workspace head.
That failed broader run was stopped; it is not a green repository-wide gate.
No Beads-mirror code or seeds were changed. Opt-in iroh cases were skipped by
that run's normal profile, and some other module tasks used cached results.
