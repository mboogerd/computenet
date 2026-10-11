# ComputeNet formal models

## What is modelled

This directory holds executable TLA+ models of ComputeNet's dataflow semantics. The initial smoke pair checks the pinned TLC runner itself; the core model will describe the precise model-to-kernel abstraction in `ABSTRACTION.md` when it lands.

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

A model passes only when TLC exits 0. A control passes the harness only when TLC exits 11 (deadlock), 12 (safety or action-property violation), or 13 (liveness violation). Exit 0 makes a control fail with `exit-0-control`; every other control status is a `crash`, never an expected violation. The harness exits 0 only when every selected configuration passes this contract, 1 when a model or control fails it, and 2 for a harness error.

With the v1.7.4 jar on Microsoft OpenJDK 21.0.11, direct probes observed exit 0 for the clean smoke model, 11 for a deadlock, 12 for the smoke invariant violation, and 13 for a liveness violation. TLC reported `5 states generated, 4 distinct states found` for the model, `4 states generated, 4 distinct states found` for the control, and `The depth of the complete state graph search is 4.` for both smoke runs.

## The `-deadlock` flag

TLC v1.7.4 documents the option as: “if specified DO NOT CHECK FOR DEADLOCK.” The harness deliberately omits it, so deadlock checking stays enabled.

Models that legitimately finish their work instead provide an explicit stuttering action. The smoke specification's `Terminating` action holds at `n = 3` and leaves `n` unchanged, preventing a false terminal deadlock without disabling detection elsewhere.

## Pinned toolchain

`tools/tla2tools.version` pins the v1.7.4 tag and its official `tlaplus/tlaplus` GitHub release URL. `tools/tla2tools.sha256` pins SHA-256 `936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88`; `check.sh` verifies it before every TLC invocation. The GitHub release-asset API returned no independent digest for this older asset (`digest: null`) when the pin was created, so an API cross-check was not possible; the value was computed from the official HTTPS release asset before its first execution.

To bump TLC, edit `tools/tla2tools.version`, delete `tools/tla2tools.jar`, run `formal/check.sh`, independently verify the new official asset digest where one is published, and replace the digest in `tools/tla2tools.sha256`. A mismatched jar is retained for inspection and is never executed.

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
