# iroh adoption — residue findings

This document is the adoption-residue finding mandated by epic
`computenet-egl` (DSC0) §2 bullet 5: "The written finding still ships, as
adoption residue rather than a recommendation." DSC0 began as a spike and was
promoted to adoption on 2026-08-12 when Headscale was dropped; the spike's
report obligation survives it. This is evidence, not normative text — it does
not belong under `doc/spec/`, and its sibling is `doc/demo-findings.md`
(a living register of demo-discovered findings at the same `doc/` level).

**What this document is not**: a recommendation. It records what the adoption
actually measured, what the sidecar actually consumes, and what remains open
for DSC2. It makes no DSC2 decision, and it does not propose adopting anything
beyond what epic `computenet-egl` already scoped (§3 below explains why not).

**Evidence rule, applied throughout**: every cost, duration, or size figure in
this document is labelled either **MEASURED** — naming the run id or command
it came from — or **ESTIMATED** — naming what the estimate rests on. A number
with neither label is a defect in this document.

This task (`computenet-egl.6.1`) writes the framing above, the
cross-compilation record, the crate layout, the DSC2 residual scope, the
epic §3 exclusion restatement, and the `computenet-egl.4` address-model
residue note. A sequel task (`computenet-egl.6.2`) adds the relay hosting
policy section; that topic is intentionally absent below.

## Cross-compilation burden

There is **no cross-compilation anywhere** in the adoption's CI path. CI runs
two *native* builds, both on `ubuntu-latest` (Linux x64):
`.github/workflows/iroh-sidecar.yml` (observed at commit `806a5f56a`, read
but not edited by this task — it is claimed by sibling `computenet-cqde`)
first runs `cargo build`/`cargo test` directly against the sidecar crate,
then rebuilds the same crate a second time, natively, via
`./gradlew :iroh:check -Piroh.enabled=true`, to prove the Gradle flag wiring
actually reaches cargo. macOS arm64 coverage exists only on developer
machines; it is not exercised in CI at all (egl.5-D3 asymmetry) and no MEASURED
macOS arm64 figure was collected for this document — state it as unmeasured
rather than inventing one.

The MEASURED timing record, all excerpted from `computenet-egl.6`'s bead
comment thread because GitHub ages workflow run logs out in days while the
bead comment is durable:

**Run 33245631288** (PR #548, `pull_request` on `feature/computenet-egl.5`,
attempt 1, conclusion success) — COLD, every timing line reports
`cache-hit=false`:

| step               | seconds |
|---------------------|--------:|
| cargo-build          | 73 |
| cargo-test            | 6 |
| gradle-iroh-check    | 27 |
| **job wall time**    | **2m22s** |

This was also the first-ever non-macOS execution of the sidecar crate's test
suite (5 test binaries, all "test result: ok").

**Run 33316613245** (PR #583, `pull_request` on `fix/computenet-jtvd`) — two
attempts on the same run, same ref:

| step               | attempt 1 COLD | attempt 2 WARM |
|---------------------|---------------:|---------------:|
| cargo-build          | 57s | 11s |
| cargo-test            | 4s  | 5s  |
| gradle-iroh-check    | 19s | 21s |

Reading this pair: the rust build cache (`Swatinem/rust-cache`) is the whole
of the effect. `cargo-build` drops from 57s to 11s (~5x); `cargo-test` and
`gradle-iroh-check` move by 1-2s, within noise — `gradle-iroh-check` is
already served by the main-scoped Gradle caches that `cache-seed.yml`'s other
lanes populate, so the rust cache does not change it. `cache-hit=true` is
stamped uniformly on all three lines by the logging wrapper regardless of
which step actually benefited; it is not a per-step claim, still less a
Gradle-cache claim on the `gradle-iroh-check` line.

**A figure this document deliberately does NOT repeat**: sibling bead
`computenet-eys1.1` cites "~2m22s warm" for this workflow. That figure is
mislabelled — 2m22s is run 33245631288's COLD job wall time; every timing
line in that run says `cache-hit=false`. There is no warm job-wall figure in
the record above (only the per-step MEASURED numbers); do not carry the
`eys1.1` phrasing forward.

**Cache durability** — MEASURED via `gh api repos/mboogerd/computenet/actions/caches`
after `cache-seed.yml` run 33317371741 (`workflow_dispatch` on `main`,
conclusion success): a main-scoped rust-cache entry exists under key
`v0-rust-iroh-sidecar-Linux-x64-0b9fd15e-151fd492`, size **404563363 bytes**
(~386 MiB), alongside two PR-scoped entries under the identical key (sizes
404564434 and 404566722 bytes) — the key identity across main-scoped and
PR-scoped entries is what the warm-restore path rests on, and it is observed
here, not merely argued from `Swatinem/rust-cache`'s key derivation.

Two limits on that durability, carried here as stated limits rather than
claims:

- **No genuinely-new PR's first run has yet been observed restoring from the
  main-scoped cache entry.** GitHub's documented cache scoping (a run
  restores from its own ref, then the default branch) plus the key-identity
  observation above makes that a short inferential step, but it remains an
  inference, not a measurement — neither PR #583 nor #584 could supply it,
  since each already held its own PR-scoped entry to restore from instead.
- **The cache key's environment hash includes the rustc version.** A new Rust
  stable release makes every iroh PR's first run go cold again until the next
  scheduled `cache-seed.yml` run reseeds the main-scoped entry — self-healing
  within roughly 24h, **ESTIMATED** from `cache-seed.yml`'s own
  `cron: "17 4 * * *"` schedule (daily), not a standing defect.

## iroh crate layout as consumed

Read from `iroh/sidecar/Cargo.toml` and `iroh/sidecar/Cargo.lock` in this
worktree at the time of writing (not from iroh's own README or docs).

`Cargo.toml`'s only two direct dependencies:

```
iroh = "1.0"
tokio = { version = "1", features = [
    "io-util", "macros", "net", "process", "rt-multi-thread", "sync", "time",
] }
```

`iroh` is pulled in with default features — no explicit feature list. The
manifest carries its own header comment recording the deliberate absence of
the two excluded crates:

> Only `iroh` — deliberately no iroh-gossip / iroh-docs (epic computenet-egl
> §3 excludes them).

`Cargo.lock` resolves (all confirmed present at the versions the
`computenet-egl.6` breakdown recorded — no drift found):

| crate | version |
|---|---|
| iroh | 1.0.3 |
| iroh-base | 1.0.3 |
| iroh-dns | 1.0.3 |
| iroh-relay | 1.0.3 |
| iroh-metrics | 1.0.1 |
| iroh-metrics-derive | 1.0.1 |

`iroh-dns` and `iroh-relay` are transitive — pulled in by `iroh` itself to
support address lookup and relaying, not added directly by the sidecar.

## DSC2 residual scope

Epic `computenet-egl` §1 narrows DSC2 (discovery) to two open questions once
the connection layer is iroh: **(a)** which iroh discovery mechanism to run
with, and **(b)** whether a self-hosted rendezvous is needed. This section
states those two questions with the adoption facts that already bear on them,
so DSC2's epic body can be drafted from it without re-deriving the adoption.

**What is already settled and DSC2 no longer needs to solve**: peer identity.
`PeerId` is derived from the connection's iroh `NodeId` (`EndpointId`, its
ed25519 public key), and boundary admission is demonstrated as a public-key
allowlist over that derived id — both landed under this epic (`computenet-egl.3`/
`.4`). DSC2 inherits a cryptographically bound identity; it does not need to
invent one.

**The discovery-mechanism question, with the facts observed in this tree.**
`iroh/sidecar/src/endpoint.rs` defines a `LookupMode` enum with two variants:

- `LookupMode::N0` — "number 0's public relays and DNS/pkarr address lookup —
  the deployment default, and the only mode that reaches a peer whose address
  is unknown." It is the `#[default]` variant, and it is what the sidecar
  binary uses unless launched with `--offline` (`iroh/sidecar/src/main.rs`).
- `LookupMode::Offline` — "only addresses supplied locally via
  `SidecarEndpoint::add_peer` are resolvable, and no relay is used." Used by
  the Rust crate's own tests (`iroh/sidecar/tests/protocol.rs`,
  `iroh/sidecar/tests/two_endpoints.rs`, both via
  `SidecarConfig::offline_loopback()`), which is what makes those tests
  deterministic and network-free.

On the JVM side, every call site that spawns the sidecar
(`IrohTransport.listen`/`connect` in `iroh/src/main/kotlin/civictech/iroh/IrohTransport.kt`,
and its callers in `iroh/src/test/kotlin/civictech/iroh/*.kt` and
`demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/IrohMirrorTransport.kt`)
defaults `sidecarArgs` to an empty list and never passes `--offline` — so
every JVM-driven test and the BDS2 iroh convergence rig (`IrohConvergenceSuiteTest`)
runs the sidecar in `LookupMode::N0`, the deployment-default mode, even though
peer addresses are also taught directly over the wire protocol via `ADD_PEER`
(`PROTOCOL.md` §3) rather than relying on discovery to find them. Only the
Rust crate's own unit/integration tests exercise `Offline` mode. DSC2's
discovery-mechanism question is therefore: whether N0's public-relay-backed
lookup is the right steady-state default for ComputeNet peers whose addresses
are *not* pre-known (today every tested path supplies addresses out of band),
or whether a different `address_lookup` implementation (iroh exposes this as
a pluggable trait; the sidecar already threads one — `MemoryLookup`, for the
locally-taught addresses — through `SidecarEndpoint::bind`) should back
discovery instead.

**The rendezvous-hosting question.** iroh's N0 preset resolves unknown peer
addresses through n0's operated relay and DNS/pkarr infrastructure — a
third-party rendezvous. DSC2 has to decide whether ComputeNet needs a
self-hosted alternative. This document's sequel section (relay hosting
policy, `computenet-egl.6.2`) is where the cost side of that question is
recorded; this section states only that the question is open and that the
sidecar's current wiring (`SidecarConfig.lookup: LookupMode`,
`SidecarEndpoint::bind`) already has the seam a self-hosted mode would plug
into — no rework of the sidecar's shape is implied by either answer.

## MirrorTransport address-model residue (deferred from `computenet-egl.4`)

`computenet-egl.4`'s closing determination named this document as the place
its address-model wart note lands, so it is recorded here rather than being
dropped.

`MirrorTransport`'s address model is port/URI-shaped (`TwoNodeRig` formats a
`ws://localhost:$wsPort` string from a listener's `boundWsPort` at
`demo/beadsmirror/src/test/kotlin/civictech/demo/beadsmirror/e2e/TwoNodeRig.kt:126-127`).
The iroh binding (`IrohMirrorTransport`,
`demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/IrohMirrorTransport.kt`)
satisfies that model as a wart rather than a fit: its `ListenLink.boundWsPort`
returns a synthetic, non-null port parsed out of the sidecar listener's first
`addresses` entry purely to satisfy `TwoNodeRig`'s `checkNotNull`, and
`dial()`'s corresponding `ws://` string is treated as an opaque token the
iroh binding never actually connects to — iroh identifies and dials peers by
`NodeId`, not by host:port. Re-typing `MirrorTransport`'s address model to fit
both a socket-backed and an id-backed transport was assessed during
`computenet-egl.4` and rejected because it could not be done with zero edits
to the existing e2e sources (`TwoNodeRig.kt`, `ConvergenceSuite.kt`), which
that feature's own acceptance forbade. It is noted here as known residue — a
follow-up shape, not a defect — rather than filed as a new bead.

## Explicitly excluded: iroh-gossip and iroh-docs

Epic `computenet-egl` §3 excludes both crates from this adoption, and this
adoption used only the `iroh` connection layer — see the crate layout above,
which lists no `iroh-gossip` or `iroh-docs` dependency, direct or transitive.
Restated here so the DSC2 residue above cannot be read as an invitation to
revisit that exclusion:

- **`iroh-docs`** is a competing multi-writer CRDT. ComputeNet already has a
  multi-writer convergence story (BDS2's OR-map fold over `:wire`); adopting
  iroh-docs would be a second, competing implementation of the same
  responsibility.
- **`iroh-gossip`** is a competing epidemic dissemination layer whose
  membership/reliability contract would sit *under* the FFI boundary this
  sidecar already draws — a place `SimulationController` cannot drive
  deterministically and where `Interest` scoping would be invisible to the
  kernel. Dissemination and membership stay `GOS1`/`GOS2`, implemented as
  cells inside the kernel's own dataflow model, not delegated to a Rust
  library on the other side of the sidecar boundary.

This document does not recommend adopting either crate, now or as a
consequence of anything measured above.

## See also

- Relay hosting policy — `computenet-egl.6.2` (sequel task; not yet written
  as of this section).
- `doc/ARCHITECTURE.md` §7 (Documentation map) — points to this document.
