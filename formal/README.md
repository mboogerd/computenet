# ComputeNet formal models

## What is modelled

This directory holds executable TLA+ models of ComputeNet's dataflow semantics. The initial smoke pair checks the pinned TLC runner itself. The core model is split across `Waves.tla`, `Frontier.tla`, and `GlitchFree.tla`; see `ABSTRACTION.md` for the precise model-to-kernel mapping. The cut family (late edge opens, cuts, fresh source epochs, preserved-epoch swaps, and catch-up baselines; P09–P17) adds `Cuts.tla` on top of those modules and is checked through `MCFrontierCut`. Counterexamples that require a specification decision are recorded in the append-only `doc/formal/findings.md` evidence ledger.

## Layout

- `specs/` contains reusable TLA+ specifications.
- `models/` contains checked instance modules and their default `.cfg` files.
- `models/deep/` is reserved for configurations that exceed the default CI budget.
- `controls/` contains deliberately falsifying configurations that must report an expected violation.
- `tools/` pins the TLC release URL and checksum; the downloaded jar is ignored by Git.
- `check.sh` verifies the toolchain and runs the selected configurations.

## Running

Run the harness from any working directory:

```text
formal/check.sh                    # all default models, then all controls
formal/check.sh MCSmoke            # the first matching named configuration
formal/check.sh --controls         # controls only
formal/check.sh --deep             # deep configurations only, never the default gate
```

Named configurations are searched in `models/`, `controls/`, then `models/deep/`. An unknown name or option is a harness error with exit status 2. Each `.cfg` starts with `\* MODULE Name`; controls reuse the corresponding module in `models/`.

## Reading a trace

On a violation, TLC prints an `Error:` block followed by `State N:` entries that show the shortest behavior leading to the failure. The harness writes each full TLC stream to `<temporary-metadir>/<config>.log`, prints that log for a failed configuration, and removes the temporary metadata directory when the run exits. The printed failure output is therefore the durable trace to retain.

## Pass/fail contract

A model passes only when TLC exits 0. Every control declares its exact expected TLC error with a `\* EXPECT Error: ...` header. The expectation must begin with `Error: ` and must not be either generic trace header (`Error: The behavior up to this point is:` or `Error: The following behavior constitutes a counter-example:`). A control passes only when TLC exits 11, 12, or 13 and its log contains that exact error line. Exit 0 makes a control fail with `exit-0-control`; an allowed violation exit with a different error fails with `unexpected-control-error`; every other control status is a `crash`. A missing or malformed expectation fails with `missing-control-expectation` or `invalid-control-expectation` instead of falling back to any-violation behavior. For `Error: Temporal properties were violated.`, the control must list exactly one `PROPERTY`: TLC reports that a temporal property was violated but does not name which temporal property was violated. A different number fails with `invalid-temporal-property-count`.

A `models/` cfg carrying exactly one `\* EXPECT Error: ...` header is an expected-violation model: it uses the same expectation validation and verdict rules as a control, and its summary is labelled `model(expected-violation)`. If TLC exits 0, it fails with `exit-0-expected-violation`; a `models/` cfg with no expectation header remains a plain model and must exit 0. BS-7's `MCFrontierCut-chain2.cfg` is the intended user of this form.

The harness exits 0 only when every selected configuration passes this contract, 1 when a model or control fails it, and 2 for a harness error.

With the v1.7.4 jar on Microsoft OpenJDK 21.0.11, direct probes observed exit 0 for the clean smoke model, 11 for a deadlock, 12 for the smoke invariant violation, and 13 for a liveness violation. A direct `MCWatermarkRegress.cfg` probe on Darwin arm64 with OpenJDK 25.0.2 observed exit 13 and `Error: Action property P03 is violated.`; action-property violations are therefore not attributed to exit 12. TLC reported `5 states generated, 4 distinct states found` for the model, `4 states generated, 4 distinct states found` for the control, and `The depth of the complete state graph search is 4.` for both smoke runs.

## The `-deadlock` flag

TLC v1.7.4 documents the option as: “if specified DO NOT CHECK FOR DEADLOCK.” The harness deliberately omits it, so deadlock checking stays enabled.

Models that legitimately finish their work instead provide an explicit stuttering action. The smoke specification's `Terminating` action holds at `n = 3` and leaves `n` unchanged, preventing a false terminal deadlock without disabling detection elsewhere.

## Pinned toolchain

`tools/tla2tools.version` pins the v1.7.4 tag and its official `tlaplus/tlaplus` GitHub release URL. `tools/tla2tools.sha256` pins SHA-256 `936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88`; `check.sh` verifies it before every TLC invocation. The GitHub release-asset API returned no independent digest for this older asset (`digest: null`) when the pin was created, so an API cross-check was not possible; the value was computed from the official HTTPS release asset before its first execution.

To bump TLC, edit `tools/tla2tools.version`, delete `tools/tla2tools.jar`, run `formal/check.sh`, independently verify the new official asset digest where one is published, and replace the digest in `tools/tla2tools.sha256`. A mismatched jar is retained for inspection and is never executed.

## Core model

Observed by `formal/check.sh` on 2026-10-11 CEST using an Apple M3 Max and OpenJDK 25.0.2:

| config | BS | properties | distinct states | depth | seconds |
| --- | --- | --- | ---: | ---: | ---: |
| `MCFrontierSmall-absorb.cfg` | BS-2 absorbing arm | P01, P02, P03, P04, P05, P06 | 608 | 16 | 1 |
| `MCFrontierSmall-nested.cfg` | BS-1 nested diamond | P01, P02, P03, P04 | 586 | 22 | 1 |
| `MCFrontierSmall-twoSources.cfg` | BS-4 independent silent source | P02, P04, P05, P08 | 146 | 16 | 1 |
| `MCFrontierSmall.cfg` | BS-1 diamond | P01, P02, P03, P04 | 146 | 16 | 1 |

The topology/restart cut family from the same run extends the state machine with
late opens, cuts, source-epoch restart, preserved-epoch swap, and baselines:

| config | BS | properties | distinct states | depth | seconds | verdict |
| --- | --- | --- | ---: | ---: | ---: | --- |
| `MCFrontierCut-baseline.cfg` | BS-9 catch-up baseline | P01, P02, P04, P05, P15 | 604 | 19 | 2 | `PASS model` |
| `MCFrontierCut-chain2.cfg` | BS-7 two-hop cut | P01, P02, P04, P11 | 393 | — | 2 | `PASS model(expected-violation)` |
| `MCFrontierCut-close.cfg` | BS-6 close unblocks | P01, P02, P04, P05, P10 | 692 | 18 | 2 | `PASS model` |
| `MCFrontierCut-openLate.cfg` | BS-5 late open and floor | P01, P02, P03, P04, P05, P09, P16 | 604 | 19 | 1 | `PASS model` |
| `MCFrontierCut-restart.cfg` | BS-8 fresh source epoch | P01, P02, P04, P12, P13, `EventuallyReleasedOrRetired` | 1,894 | 26 | 3 | `PASS model` |
| `MCFrontierCut-swap.cfg` | preserved-epoch swap | P01, P02, P04, P05, P14 | 92 | 12 | 1 | `PASS model` |

TLC does not print a complete-state-graph depth for the temporal violation in
`MCFrontierCut-chain2.cfg`; the behavior it prints reaches the stuttering
state at state 10. The direct `-workers 1` evidence and reduced trace are in
`doc/formal/findings.md`.

| property | model configuration(s) | control configuration(s) | observed TLC outcome for the control |
| --- | --- | --- | --- |
| P01 | `MCFrontierSmall.cfg`; `MCFrontierSmall-nested.cfg`; `MCFrontierSmall-absorb.cfg` | `MCTornRelease.cfg` | exit 12 — `Invariant P01 is violated.` |
| P02 | all four `MCFrontierSmall*.cfg` model configurations | `MCTornComplete.cfg` | exit 12 — `Invariant P02 is violated.` |
| P03 | `MCFrontierSmall.cfg`; `MCFrontierSmall-nested.cfg`; `MCFrontierSmall-absorb.cfg` | `MCWatermarkRegress.cfg` | exit 13 — `Action property P03 is violated.` |
| P04 | all four `MCFrontierSmall*.cfg` model configurations | `MCOutOfOrder.cfg` | exit 12 — `Invariant P04 is violated.` |
| P05 | `MCFrontierSmall-absorb.cfg`; `MCFrontierSmall-twoSources.cfg` | `MCNoAbsorbAck.cfg` (lists `PROPERTY P05`, but deadlock checking reports its stall before P05 is evaluated) | exit 11 — `Deadlock reached.` |
| P06 | `MCFrontierSmall-absorb.cfg` | `MCUnackedAbsorb.cfg` (load-bearing knob; `IgnoreArm = {"cd"}` lets TLC reach the named property) | exit 13 — `Error: Temporal properties were violated.` |
| P07 | `MCFrontierSmall-absorb.cfg` (positive baseline; P07 is control-only) | `MCNoAbsorbAck.cfg` | exit 11 — `Deadlock reached.` |
| P08 | `MCFrontierSmall-twoSources.cfg` | `MCOverAligned.cfg` | exit 12 — `Invariant P08 is violated.` |
| P09 | `MCFrontierCut-openLate.cfg` | `MCFloorIgnored.cfg` | exit 12 — `Error: Invariant P09 is violated.` |
| P10 | `MCFrontierCut-close.cfg` | `MCCloseIgnored.cfg` | exit 13 — `Error: Temporal properties were violated.` |
| P11 | `MCFrontierCut-chain2.cfg` (the expected-violation model is its own falsifier) | no separate control | exit 13 — `Error: Temporal properties were violated.` |
| P12 | `MCFrontierCut-restart.cfg` | `MCCounterReuse.cfg` | exit 13 — `Error: Action property P12 is violated.` |
| P13 | `MCFrontierCut-restart.cfg` | `MCSilentRestart.cfg` | exit 12 — `Error: Invariant P13 is violated.` |
| `EventuallyReleasedOrRetired` | `MCFrontierCut-restart.cfg` | no checked-in control; the reviewed successor-routing mutation violates the temporal property | not a control configuration |
| P14 | `MCFrontierCut-swap.cfg` | `MCSwapEmitsMarker.cfg` | exit 13 — `Error: Action property P14 is violated.` |
| P15 | `MCFrontierCut-baseline.cfg` | no dedicated checked-in control; the reviewed direct safe-branch mutation uses the same `CountBaselines` fault and violates P15 | `Error: Action property P15 is violated.` in the direct mutation run |
| P16 | `MCFrontierCut-openLate.cfg` | `MCPreJoinReleased.cfg` | exit 12 — `Error: Invariant P16 is violated.` |
| P17 | `MCFrontierCut-baseline.cfg` (positive baseline; P17 is control-only) | `MCBaselineCounted.cfg` (declares P01 to expose the torn release) | exit 12 — `Error: Invariant P01 is violated.` |

Control state counts are omitted because violating runs under `-workers auto` are not stable. The outcomes above came from direct serial TLC runs and are declared in each control's `\* EXPECT Error: ...` header, which `check.sh` enforces. No control names P05: a wave left pending keeps `Terminating` disabled, so the stall is a deadlock, which TLC reports before it evaluates any temporal property (`ABSTRACTION.md`, "Property map"). Every default core configuration completed within the CI budget, so the deep configurations ledger stays empty ([FRM1-D-09]).

## Deep configurations ledger

Configurations whose default bounds exceed the ten-minute CI budget belong in `models/deep/`, remain outside the default gate, and must be recorded here by the model feature that adds them.

| config | bounds | last run | result |
| --- | --- | --- | --- |

## Maintenance

`formal/` is maintained by whoever next touches wave semantics; the `formal-check` CI job is the enforcement.

## Tools considered

Alloy was considered and declined for this work. Its bounded relational analysis is well suited to static topology questions, but the load-bearing properties here are temporal and fairness-sensitive behaviors over an evolving relation, which TLA+ represents directly.

Apalache may optionally check a candidate inductive invariant, but it is never the gate; TLC remains the sole required checker and any Apalache result must be recorded explicitly.

## Smoke pair

`Smoke.tla`, `MCSmoke.tla`, and their passing and broken configurations give the harness teeth before any real property lands. The core-model feature may delete this pair once at least one real model and its falsifying control replace it.
