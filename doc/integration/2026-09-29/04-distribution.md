# 04 Distribution assessment (read-only, main @ cbc7bb52, 2026-09-29)

## Summary
1. The distribution *core* is genuinely integrated: one kernel bridge seam (BridgeEgress/Ingress + WireCodec + Peering), one delta-CRDT `Replication` that all three replicating multi-JVM demos share, and interest-gated gossip that is the SAME slice-and-route mechanism as partitioning.
2. Everything *around* that core landed side by side: :wire and :iroh are two drivers with no shared Transport abstraction (beadsmirror invented one in-demo); SingleWriterReplication/LeaderElection has zero demo users; identity/SEC1/membranes are proven only in :wire/:iroh/:identity tests, never in a demo.
3. Location transparency holds at link level (C-13 fixed in code) but NOT by configuration: `InstanceSpec.placement` is recorded, its multi-host driver is unbuilt; 4 of 13 demos distribute, each with 700-1100 LOC of hand wiring.
4. Interest-driven replication is real in the kernel but only exercised in-process/loopback; topology is still "full mesh among overlapping replicas learned from full-broadcast announcements" (GOS1 open). The social north star (SOC1 deferred, SOC2/SOC3 open P3) has no :wire dependency at all.
5. Cross-wire composition is thin: over a REAL transport no test combines partitioning, Owned/Leased, single-writer, or graph evolution; the repo's composition gate (Exchange) runs on `Peering.loopback`, and concord's dist driver is loopback-only.

## Concept status table

| Concept | Implemented | Complete | Canonical vs parallel | Evidence |
|---|---|---|---|---|
| Bridge seam (encode model, not define it) | yes | mostly | canonical, kernel-owned | kernel/src/main/kotlin/civictech/cell/wire/BridgeCells.kt:40,95; Peering.kt:424; WireCodec.kt:166 |
| Remote link handshake parity (C-13) | yes | yes in code | canonical | WireEdgeLink.kt:137 routes through shared handshake; spec 41-location-transparency.md:14-20,206 still says "violated" (doc drift) |
| RemoteLink.requestLinkFrom | partial | NO | — | BridgeCells.kt ~496-510: "no data flows from the named producer"; only requestLinkTo carries data |
| Transports :wire (WS) / :iroh (QUIC sidecar) | yes | yes | two parallel drivers, no shared interface, separate hello grammars | IrohTransport.kt:38-80; iroh/build.gradle.kts:27-49 (:wire test-only) |
| Transport abstraction for apps | demo-local only | no | parallel (beadsmirror) | demo/beadsmirror/.../MirrorTransport.kt:48-58, WS-shaped address "wart" :32-46 |
| Delta-CRDT replication | yes | yes | canonical; used by beadsmirror, shopping, tiering | replication/Replication.kt:50; MirrorPeering.kt:145-179; shopping Main.kt:125,270; TieringApp.kt:323,343 |
| Single-writer replication + leader election | yes (kernel) | kernel tests only | orphaned: 0 demo users; MEM1 (computenet-f7h) deferred | SingleWriterReplication.kt:236; git grep: kernel/test 13 files, testkit 1, demos 0 |
| Interest-scoped replication | yes | kernel yes, wire-untested | canonical (unifies with partitioning via keyOf) | Replication.kt:25-33, 917-954; Interest serialized WireCodec.kt:261-266 |
| Emergent/partial-view topology | no | — | full mesh today | GOS1 computenet-yk6 "replaces Replication's full mesh"; GOS2 computenet-bm3 |
| Config-only placement across hosts | no | — | hint recorded, driver unbuilt | graph/GraphDsl.kt:97-106 ("93 I-15, driver unbuilt"); no consumer of `.placement` |
| Cryptographic identity (DSC1/DSC4) | yes | yes in :wire/:iroh | canonical seam (kernel SignatureVerifier, :identity impl) | wire/build.gradle.kts:22; wire/AnnouncementIdentity.kt:12; epics ssa, 5y8t closed |
| Identity used by any demo | no | — | standalone from demos | git grep PeerAuthPolicy/AnnouncementAdmission/AnchorVouchedBinding in demo/*: 0 |
| Membrane integrity verifier | placeholder default | no | — | membrane/MediateProxy.kt:101 default SignatureVerifier.TransportVouched (byte-compare); CompositeCell.kt:176 |
| Security in executable spec | no | — | — | 43-security.md mints 0 requirement ids; concord/corpus/DISPUTES.md:1601 |
| Discovery (DSC2) / NAT (DSC3) | partial (iroh/discover) | no | DSC2 computenet-aas deferred, DSC3 computenet-83t open | iroh/src/main/kotlin/civictech/iroh/discover/* |
| Churn / membership (MEM1/MEM2) | partial | no | MEM1 deferred, MEM2 computenet-psl open | — |
| Beads replication (BDS2 done, BDS4/5 open) | read half | no | uses canonical Replication | computenet-6wc, computenet-bii open |

## Integration gaps

| # | Gap | Evidence | Why it blocks an all-features demo | Sev | Tracking |
|---|---|---|---|---|---|
| G1 | No placement-driven deployment: a graph cannot be split across JVMs by config | GraphDsl.kt:97-106 driver unbuilt; demos hand-wire WsTransport.listen/connect + Peering.Side (exchange Main.kt:222-238) | Each demo must re-author distribution; vision (a) "same program unchanged" unmet | H | untracked as a demo-facing feature (93 I-15 prose; PLC2 computenet-z1w is offline policy, not a driver) |
| G2 | No kernel/shared Transport abstraction over :wire and :iroh | MirrorTransport.kt:32-58 (demo-local, WS-shaped); IrohTransport hello deliberately distinct | Switching WS->iroh (the cross-owner path) needs per-demo bindings; beadsmirror carries ~555 LOC of iroh glue | H | untracked (MirrorTransport KDoc defers it to "DSC0's call") |
| G3 | Composition gate is in-process | ExchangeCompositionExitTest.kt:6-35 over Peering.loopback; durability in separate ExchangeScaffoldTest | No evidence partitioning+replication+glitch-free+durability compose across a real socket | H | untracked |
| G4 | Real-transport tests never combine partitioning, Owned/Leased, single-writer, or graph evolution | git grep over 65 real-transport test files: 0 hits each; GlitchFree 1, Journal 2, BoundaryPolicy 1 | Core invariants (ownership, evolution) unverified at the boundary the vision centres on | H | untracked |
| G5 | Security/identity not reachable from demos; membrane verifier is a placeholder | 0 demo uses of PeerAuthPolicy etc.; MediateProxy.kt:101 TransportVouched; 43-security.md 0 ids | Vision (c) untrusting peers cannot be shown; SOC2 depends on it | H | SOC2 computenet-a3n (P3), SEC1 closed without spec ids |
| G6 | Interest-scoped replication untested over wire; topology is full mesh from broadcast announcements | Replication.kt:917-954 vs no wire/iroh interest test; GOS1 | Vision (b) emergent topology not demonstrable; social feed interest is in-process only | M | GOS1 computenet-yk6, GOS2 computenet-bm3; wire test untracked |
| G7 | SingleWriterReplication orphaned from demos; MEM1 election deferred | 0 demo users | Leader/epoch replication (needed for non-CRDT state, e.g. exchange order book) unavailable to demos | M | MEM1 computenet-f7h deferred, MEM2 computenet-psl |
| G8 | Concord dist conformance runs only over Peering.Loopback | KernelDriverDist.kt:74 | 41-LOC-01 "same result across network" checked only on the in-JVM frame path | M | WIR1 computenet-ncz (vectors, not a transport driver) — partial |
| G9 | requestLinkFrom authorised but carries no data | BridgeCells.kt ~496-510 | Consumer-initiated pull links across peers impossible; only push direction works | M | untracked (stated limitation in KDoc) |
| G10 | Social north star has no distribution at all | demo-findings.md:1272-1274 (":demo:social declares no :wire dependency"); SOC1 deferred | The demo meant to integrate interest+identity+membranes+election is single-node | M | SOC2/SOC3 computenet-a3n/6a2 P3 |
| G11 | Spec 41 still states C-13 violated though code fixed | 41-location-transparency.md:14-20,206 vs WireEdgeLink.kt:137 | Misleads planners | L | untracked |
| G12 | GroupByCell not Replicable (aggregate can't gossip) | ExchangeCompositionExitTest.kt:17-22 | Forces replicate-inputs-and-recompute workaround in every distributed aggregate demo | L | unknown |

## Bridging proposals (ordered)

1. **(M) Kernel-level `PeerTransport` seam** — lift MirrorTransport's listen/dial/partition/heal into kernel (address-neutral `PeerAddress`), with WsTransport and IrohTransport as bindings; delete demo bindings. Unblocks G2, simplifies G1.
2. **(L) Placement driver (93 I-15)** — consume `InstanceSpec.placement` + a deployment manifest mapping placement tags -> hosts/peers; a demo launches N JVMs from one GraphSpec + manifest with zero code change. Closes vision (a). Depends on 1.
3. **(M) Real-transport composition gate** — re-run ExchangeCompositionExitTest's scenario over two JvmPeers via the new seam (WS and iroh), adding durability kill-9 and one Owned/Leased edge and one evolution promotion. Closes G3/G4; make it a required CI lane.
4. **(S) Concord dist driver over a real transport** — a second KernelDriverDist binding using WsTransport on localhost, run in concord-full. Closes G8.
5. **(M) Secure-by-configuration peering in demos** — expose PeerAuthPolicy/credentials via DemoShell flags; replace MediateProxy's TransportVouched default with the authenticated PeerId from the bridge; mint `[43-*]` ids + concord scenarios. Closes G5, prerequisite for SOC2.
6. **(S) Wire test for interest-scoped replication** — two peers with disjoint/overlapping Interest over WsTransport; assert non-overlap never crosses. Closes G6's evidence half; GOS1 closes the topology half.
7. **(M) Surface SingleWriterReplication to demos** — undefer MEM1 or ship a demo-facing leader-owned cell (exchange order book), over the wire. Closes G7.
8. **(M) SOC2 as the integration demo** — give :demo:social a :wire dependency and run feed replication across peers with interest scoping + identity + moderation membrane: the natural "all features together" target once 1-6 land.
9. **(S) Doc hygiene** — fix spec 41 C-13 text; file requestLinkFrom data-path and GroupByCell-Replicable as beads.

## Raw findings log
- F1 Transport: one kernel bridge seam (kernel/.../cell/wire: BridgeEgressCell/BridgeIngressCell BridgeCells.kt:40,95; Peering.kt:424; WireCodec.kt:166). :wire WsTransport (2986 LOC) and :iroh IrohTransport (2118 LOC) are both drivers over it; no shared Transport interface — each has its own hello grammar (IrohTransport.kt:52-80 "deliberately not WsTransport's"). :iroh main must not import :wire (iroh/build.gradle.kts:27-49).
- F2 RemoteLink.requestLinkFrom authorised but NOT wired: "no data flows from the named producer" (kernel/.../wire/BridgeCells.kt:~496-510). Only requestLinkTo carries data.
- F3 Replication: kernel Replication.kt:50 (delta-CRDT gossip mesh, emerges from announcements; keyOf unifies w/ partitioning, Replication.kt:25-33) + SingleWriterReplication.kt:236 (leader/epoch). SingleWriterReplication used ONLY in kernel tests + testkit churn test — zero demos. Replication( used by demo/beadsmirror, shopping, tiering + concord.
- F4 Identity: :wire and :iroh depend on :identity (wire/build.gradle.kts:22, iroh/build.gradle.kts:46); Ed25519SignatureVerifier used in wire/AnnouncementIdentity.kt:12 for announcement admission. PeerAuthPolicy/AnnouncementAdmission/AnchorVouchedBinding: zero uses in demo/* (git grep). MediateProxy default verifier = SignatureVerifier.TransportVouched (membrane/MediateProxy.kt:101), a byte-compare placeholder.
- F5 Demo distribution matrix (git grep main/test): only beadsmirror, exchange, shopping, tiering use WsTransport/Peering in main; 9/13 demos (agora, deliberate, skillmatch, slotfinder, social, dialogue, alignment, allocator-observe, backlog-triage) are single-process only. Only beadsmirror touches IrohTransport. No demo uses PeerAuthPolicy/credentials.
- F6 ExchangeCompositionExitTest (the repo's composition gate) is ONE in-process graph over Peering.loopback (ExchangeCompositionExitTest.kt:6-15); durability [D] covered by a SEPARATE two-JVM ExchangeScaffoldTest, not composed; replication of GroupByCell aggregate is a known gap worked around by replicating inputs (lines 17-22).
- F7 Config-only placement does NOT exist: InstanceSpec.placement is 'host-selector hint (recorded, routed by the multi-host replay driver — 93 I-15, driver unbuilt)' (kernel/.../graph/GraphDsl.kt:97-106); no production consumer of .placement. Spec 41-LOC-01 still flags C-13 (bridged links bypass handshake) at 41-location-transparency.md:14-19. Demos wire WsTransport.listen/connect + Peering.Side by hand (demo/exchange/.../Main.kt:222-238).
- F8 Epics open: BDS4 computenet-6wc (write half+lease plane), BDS5 computenet-bii (iroh cross-machine beads, 'integration not invention'), SOC2 computenet-a3n, SOC3 computenet-6a2 (north star), GOS1 computenet-yk6 ('replaces Replication full mesh'), GOS2 computenet-bm3, MEM2 computenet-psl, DSC3 computenet-83t, WIR1/2. All open, SOC2/SOC3 P3.
- F9 Interest-scoped replication is REAL in kernel: gossip link forms only when interests overlap and each delta is sliceTo'd to target interest (Replication.kt:917-954); Interest algebra serializes on wire (WireCodec.kt:261-266). But topology = 'link every overlapping replica I learn about' via full announcement broadcast (GOS1 computenet-yk6 exists to replace it). No wire/iroh/demo test exercises partial-interest replication over a real transport; social (the interest showcase, demo/social/.../Feed.kt:18-129) is single-process.
- F10 No kernel Transport abstraction: beadsmirror invented its own MirrorTransport seam (demo/beadsmirror/.../MirrorTransport.kt:48-58), explicitly WebSocket-shaped address model ('a wart', lines 32-46); iroh bindings (IrohMirrorTransport 233 LOC, DiscoveredIrohMirrorTransport 322 LOC) live in the demo. Glue per distributed demo: shopping Main.kt 694, tiering TieringApp.kt 785, beadsmirror ~1100 LOC transport/peering.
- F11 Over a REAL transport (65 test files using WsTransport.listen/connect, IrohTransport or JvmPeer): 0 combine PartitionedShardSet/ShardCell, 0 Owned/Leased, 0 SingleWriterReplication, 0 graph Evolution (the 'Promotion' hits are principal promotion in identity tests); GlitchFree only ExchangeScaffoldTest; Journal only SocialCrashRestartTest, TieringServerTest; BoundaryPolicy only WsBoundaryPolicyTest.
- F12 Concord dist driver bridges hosts ONLY via Peering.Loopback (concord/.../KernelDriverDist.kt:74) — no conformance scenario runs over :wire/:iroh. concord/corpus/41-location has 1 scenario (41-SPLIT-01), 42-replication 8 files. DISPUTES.md:1601 '43-security.md mints no ids' (SEC1 not covered).
- F13 POSITIVE: all 3 multi-JVM replicating demos use the one kernel Replication (beadsmirror MirrorPeering.kt:145-179, shopping Main.kt:125/270, tiering TieringApp.kt:323/343) — no parallel gossip. C-13 handshake bypass is fixed in code (WireEdgeLink.kt:137 routes through shared handshake) but spec 41:14-20,206 still says violated = doc drift.
