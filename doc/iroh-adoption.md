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
policy section below.

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

## Relay hosting policy

This section is `computenet-egl.6.2`, the sequel to the sections above. It
covers what n0's public relays observe, what self-hosting a relay would
require, and the documented default for ComputeNet development.

### What n0's public relays observe

Read 2026-08-31 from n0's current docs (`docs.iroh.computer`) rather than
repeated from this document's own earlier drafts, per this section's mandate.

- **Payload confidentiality**: "Relay servers do not have access to the data
  being transmitted, as it's encrypted end-to-end", and "all relay traffic is
  end-to-end encrypted regardless" of authentication settings — [Relays
  concept page](https://docs.iroh.computer/concepts/relays), read
  2026-08-31. A relayed connection's *payload* is opaque to the relay; this
  is the honest baseline the rest of this section sits on top of, not a
  mitigation layered after the fact.
- **Connection-establishment assistance**: "When two endpoints first connect,
  they exchange network information through the relay to attempt a direct P2P
  connection" (STUN-like), and if that fails, "traffic flows through the relay
  instead" (DERP-style fallback) — same page, read 2026-08-31. The docs
  report "roughly 9 out of 10 networking conditions allow a direct
  connection", so the fallback-relay path is the minority case, not the norm —
  ESTIMATED by n0, not measured by this adoption.
- **What crosses the relay even for connection setup**: the relay's
  `iroh-relay` crate docs describe an access-control hook — "The relay calls
  an external HTTP endpoint for each incoming connection, passing the
  connecting endpoint's ID" —
  [`iroh-relay` README](https://raw.githubusercontent.com/n0-computer/iroh/main/iroh-relay/README.md),
  read 2026-08-31. So at minimum, a relay operator (n0, for the public relays)
  is positioned to observe the connecting endpoint's `NodeId` per connection
  it handles, plus ordinary transport-level facts (source IP, connection
  timing, and — for connections that fall back to full relaying rather than
  holepunching through — bytes relayed) as an inherent property of being a
  server in the data path. **`unverified:`** neither fetched page states
  explicitly what a relay logs or retains (versus merely has technical access
  to see in flight), nor whether the source *and* destination `NodeId` are
  both visible to the relay for a given connection, nor a precise definition
  of "timing" or "volume" as retained metadata — this document does not
  restate the illustrative "source/dest NodeIds, timing, volumes" list from
  the epic's motivating sentence as verified fact; only the points cited above
  are grounded in a fetched page.
- **After a direct connection forms**: "the relay steps back and data flows
  peer-to-peer" — same Relays concept page, read 2026-08-31. The fetched page
  does not say explicitly whether the relay continues to observe anything
  once traffic has moved off it (e.g. periodic keepalives); that continuation
  question is `unverified:`.
- **Environment note**: this document was written inside an environment with
  outbound web fetch available; the citations above came from live fetches on
  2026-08-31, not from memory or from repeating the epic's prose. Where a
  fetch could not confirm a specific claim, it is marked `unverified:` above
  rather than silently dropped or silently assumed true.

### What self-hosting a relay requires

Read 2026-08-31 from the `iroh-relay` crate's own docs and README, not from
iroh's general marketing pages.

- **What ships**: `iroh-relay` is both a library (`client`, `server` modules —
  "a fully-fledged iroh-relay server over HTTP or HTTPS") and a binary — "A
  CLI for running your own relay server. It can be configured to also offer
  QAD support and expose metrics" —
  [`docs.rs/iroh-relay`](https://docs.rs/iroh-relay/latest/iroh_relay/), read
  2026-08-31. There is no published pre-built container; the README's build
  instructions show compiling it yourself (`cargo build` with release flags) —
  [`iroh-relay` README](https://raw.githubusercontent.com/n0-computer/iroh/main/iroh-relay/README.md),
  read 2026-08-31.
- **TLS certificates**: "Both https and QUIC address discovery require TLS
  certificates." Production configuration takes manual certificate and key
  file paths (`manual_cert_path`, `manual_key_path`) in the server's TOML
  config; local development can instead generate self-signed certs via
  `cargo run -- -o path/to/certs`, or run with a `--dev` flag that skips HTTPS
  entirely for local testing — same README, read 2026-08-31. The fetched
  material does **not** mention automatic certificate issuance/renewal (e.g.
  Let's Encrypt / ACME) — `unverified:` whether such support exists anywhere
  in the crate; treat manual cert provisioning as the documented path.
- **Domain name**: neither fetched page ties a domain name requirement to
  the relay binary directly, but a real TLS certificate for a public HTTPS/QUIC
  endpoint implies one in practice (a self-signed cert only serves the
  `--dev`/local-testing path above) — this inference is this document's own,
  not a quoted requirement, and is marked as such.
- **Ports observed in the docs**: port 3340 for HTTP in dev mode, and port
  7824 for the QUIC server when enabled — same README, read 2026-08-31. These
  are the documented defaults, not values this adoption chose or configured.
- **Host and operational cost**: standing up a relay is explicitly **out of
  scope** for this adoption (per epic `computenet-egl` and this task's
  non-goals), so there is no MEASURED figure for host sizing, bandwidth, or
  ongoing operational effort anywhere in this section — every such figure
  below is **ESTIMATED**, and the estimate rests only on the shape of the
  workload the relay would carry (connection-setup assistance for all peers,
  plus best-effort fallback relaying for the roughly-1-in-10 connections that
  cannot holepunch, per the n0 docs cited above), not on any provisioning or
  load test this adoption performed:
  - A single small VM (the kind sized for a lightweight always-on network
    service, e.g. 1 vCPU / 1-2 GB RAM class) is **ESTIMATED** as plausibly
    sufficient for ComputeNet's current scale (a handful of demo peers), by
    analogy to the documented workload shape above — not from any load figure
    this adoption measured.
  - Bandwidth cost is **ESTIMATED** to track the fallback-relay fraction of
    traffic (the "roughly 9 out of 10" direct-connection figure above implies
    most bytes never transit the relay at all), so a relay's bandwidth bill is
    ESTIMATED to be small relative to total traffic volume at ComputeNet's
    current scale — again an inference from the cited ratio, not a
    measurement.
  - Ongoing operational effort (TLS renewal, binary upgrades, monitoring) is
    **ESTIMATED** as non-trivial but bounded — a single long-running service
    with a manual-certificate renewal cadence — by the shape of the manual TLS
    workflow documented above; this document does not attempt to quantify a
    time figure (e.g. hours/month) for it because doing so would require
    actually running one, which is out of scope.
- **Code fact, not a hosting fact**: `iroh-relay 1.0.3` already appears in
  `iroh/sidecar/Cargo.lock` as a *transitive* dependency of `iroh 1.0.3` (see
  "iroh crate layout as consumed" above). That the crate is already present in
  the lockfile means the *client* code that talks to relays is compiled in —
  it says nothing about whether ComputeNet operates a relay, and does not
  reduce any of the self-hosting requirements above.

### Documented default for ComputeNet development

**n0's public relays are the accepted default for ComputeNet development;
revisit before any real deployment.** This is a documented default, not a
decision beyond it — no self-hosted relay is stood up, configured, or
recommended by this document.

Grounding this in what the code and tests actually do, verified directly
against the sources rather than assumed (see also "DSC2 residual scope"
above, which names the same call sites):

- `iroh/sidecar/src/endpoint.rs` derives `Default` for `LookupMode` with
  `#[default]` on the `N0` variant (line 34), not `Offline`. `Offline` is only
  reached through `SidecarConfig::offline_loopback()`, an explicit
  constructor (lines 52-61), and the sidecar binary
  (`iroh/sidecar/src/main.rs`) only calls into that path when launched with
  the `--offline` CLI flag (`args.offline` gates
  `SidecarConfig::offline_loopback()`, `main.rs` lines 43-44, 112).
- On the JVM side, `IrohTransport.listen`/`connect`
  (`iroh/src/main/kotlin/civictech/iroh/IrohTransport.kt`, lines 128, 169)
  default `sidecarArgs` to `emptyList()`. The only test call sites that pass a
  non-empty `sidecarArgs` (`IrohReconnectTest.kt` lines 231, 270) pass
  `listenerArgs`, which supplies `--secret-key`/`--bind-addr`, never
  `--offline`. `IrohMirrorTransport`
  (`demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/IrohMirrorTransport.kt`)
  likewise spawns the sidecar with no `sidecarArgs`. So **every JVM-driven
  path — including the BDS2 iroh convergence rig
  (`IrohConvergenceSuiteTest`) — runs the sidecar in `LookupMode::N0`**, the
  n0-public-relay-backed mode, not `Offline`. Only the Rust crate's own
  `iroh/sidecar/tests/*.rs` suite exercises `Offline` (it constructs
  `SidecarConfig` directly rather than going through the CLI).

  This corrects an earlier draft of this task's own instructions, which
  stated the deterministic suites "run Offline, so today's CI and local tests
  touch no public relay at all." That is true only for the Rust crate's own
  tests; it is false for the JVM-driven paths, which are the majority of this
  adoption's test surface. Verified directly against the three files named
  above rather than taken on either version's word.
- **CI consequence**: `computenet-cqde` (this session) wired the JVM-driven
  iroh test cases — the ones shown above to run in `LookupMode::N0` — into
  the `iroh-sidecar` workflow's `ubuntu-latest` CI lane; its first run passed
  with 8 tests, 0 skipped (**MEASURED**: `computenet-cqde`'s first CI run of
  that lane this session, per this task's dispatch context — not re-verified
  against a run id by this task). Given the reading above, that means the
  `iroh-sidecar` CI lane reaches n0's public discovery/relay infrastructure
  from GitHub-hosted runners on every triggering PR. This document states
  that fact; it does not decide whether that exposure is acceptable — that is
  a policy question outside this task's scope, flagged in this task's closing
  comment for the orchestrator to consider filing separately.

## See also

- `doc/ARCHITECTURE.md` §7 (Documentation map) — points to this document.
- `doc/ARCHITECTURE.md` §7 (Documentation map) — points to this document.
