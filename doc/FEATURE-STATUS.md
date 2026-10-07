# ComputeNet — Feature x Demo Adoption Matrix

> **GENERATED — do not hand-edit.** Regenerate with:
>
> ```
> python3 scripts/adoption-matrix/adoption_matrix.py --out doc/FEATURE-STATUS.md
> ```
>
> **Method**: for every `include(":demo:<name>")` of `settings.gradle.kts` except `:demo:shell` (the shared HTTP/SSE shell, not an application), scan `demo/<name>/src/main/**/*.kt` — never `src/test` — for the import/usage patterns in `scripts/adoption-matrix/features.tsv`, after stripping `//` line comments and `/* */` block comments. A cell is `USED` when any pattern of that row matches a comment-stripped line of any main-source file of that demo.
>
> **Imprecision, by design**: an import proves presence, not quality of use — a demo that bypasses the platform concept it names (report 06's `B` rows) reads the same as one that never touches it, `-`. String literals containing `//` or `/*` are not excluded from comment stripping. Transitive use reached only through `:wire` wiring or DSL configuration, without a matching import/regex in the demo's own main source, is not counted.
>
> **Not measured**: mobility and membranes have no import-detectable surface in today's demos (or are postponed by the umbrella epic's non-goals) and are not rows here. Test source sets (`src/test`) are never scanned, for any feature.
>
> The 2026-07-25 hand-written survey this file replaced is in git history: `git show 1de7dfd5:doc/FEATURE-STATUS.md`.

### Headline features

| Feature | agora | alignment | allocator-observe | backlog-triage | beadsmirror | deliberate | dialogue | exchange | shopping | skillmatch | slotfinder | social | tiering | demos |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Typed ports / explicit links | USED | USED | USED | USED | USED | USED | USED | USED | USED | USED | USED | USED | USED | 13 |
| Deltas & data-cell operators | - | USED | USED | USED | USED | - | USED | USED | USED | USED | USED | USED | USED | 11 |
| Glitch-free observation | USED | USED | - | USED | - | USED | USED | USED | USED | USED | USED | USED | USED | 11 |
| Interest-driven execution | - | - | - | - | - | - | - | USED | - | - | - | USED | - | 2 |
| Owned / Leased payloads | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Partitioning | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Durability / recovery | USED | USED | USED | USED | USED | USED | USED | USED | - | - | - | USED | USED | 10 |
| Replication | - | - | - | - | USED | - | - | - | USED | - | - | - | USED | 3 |
| Wire transport (ws / iroh) | - | - | - | - | USED | - | - | USED | USED | - | - | - | USED | 4 |
| Identity / authority | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Invariants / verify | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Evolution / promotion | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Budgets | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Inspector | USED | USED | - | USED | - | USED | USED | USED | USED | USED | USED | - | USED | 10 |
| Demograph | - | - | - | USED | - | - | - | - | - | - | - | - | - | 1 |
| **headline count** | 4 | 5 | 3 | 6 | 5 | 4 | 5 | 7 | 6 | 4 | 4 | 5 | 7 |  |

### Additional features (not part of the umbrella epic's acceptance)

| Feature | agora | alignment | allocator-observe | backlog-triage | beadsmirror | deliberate | dialogue | exchange | shopping | skillmatch | slotfinder | social | tiering | demos |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Generated @Contract/@CellBase cells | - | USED | - | - | - | - | - | - | - | - | - | - | - | 1 |
| Hosted execution (ManagedHost) | USED | USED | USED | USED | USED | USED | USED | USED | - | USED | USED | USED | USED | 12 |
| Colors (Blocking/Suspending) | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Time-travel | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |
| Query / Datalog | - | - | - | - | - | - | - | - | - | - | - | - | - | 0 |

## Headline features used by no demo

- Owned / Leased payloads (`owned-leased`)
- Partitioning (`partitioning`)
- Identity / authority (`identity`)
- Invariants / verify (`invariants`)
- Evolution / promotion (`evolution`)
- Budgets (`budgets`)

## Evidence

- typed-links / agora: demo/agora/src/main/kotlin/civictech/agora/AgoraApp.kt, demo/agora/src/main/kotlin/civictech/agora/AgoraService.kt, demo/agora/src/main/kotlin/civictech/agora/cell/ClaimCell.kt, demo/agora/src/main/kotlin/civictech/agora/cell/EdgeCell.kt
- typed-links / alignment: demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentApp.kt, demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentCells.kt
- typed-links / allocator-observe: demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/AllocatorObserveApp.kt, demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/view/AllocatorReportViews.kt
- typed-links / backlog-triage: demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/TriageApp.kt
- typed-links / beadsmirror: demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/MirrorGraph.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/baseline/Rebaseline.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/projector/MirrorCellFactory.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/ready/ReadySetCell.kt
- typed-links / deliberate: demo/deliberate/src/main/kotlin/civictech/deliberate/Cells.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/CredenceGraph.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/DeliberateApp.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/Sensitivity.kt
- typed-links / dialogue: demo/dialogue/src/main/kotlin/civictech/dialogue/DialoguePipeline.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/DialogueRuntime.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/apply/BindingTable.kt
- typed-links / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- typed-links / shopping: demo/shopping/src/main/kotlin/civictech/demo/Main.kt
- typed-links / skillmatch: demo/skillmatch/src/main/kotlin/civictech/demo/skillmatch/SkillMatchApp.kt
- typed-links / slotfinder: demo/slotfinder/src/main/kotlin/civictech/demo/slotfinder/SlotFinderApp.kt
- typed-links / social: demo/social/src/main/kotlin/civictech/demo/social/Feed.kt, demo/social/src/main/kotlin/civictech/demo/social/SnbPipeline.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialApp.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialGraph.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialRecovery.kt, demo/social/src/main/kotlin/civictech/demo/social/ViewerInterest.kt
- typed-links / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
- delta-operators / alignment: demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentApp.kt, demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentCells.kt
- delta-operators / allocator-observe: demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/AllocatorObserveApp.kt, demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/declaration/DeclarationIngester.kt, demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/ingest/SpendLogIngester.kt, demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/view/AllocatorReportViews.kt, demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/view/SessionLedger.kt
- delta-operators / backlog-triage: demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/TriageApp.kt
- delta-operators / beadsmirror: demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/MirrorGraph.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/projector/MirrorCellFactory.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/projector/MirrorProjector.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/ready/ReadySetCell.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/writeback/Provenance.kt
- delta-operators / dialogue: demo/dialogue/src/main/kotlin/civictech/dialogue/DialoguePipeline.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/DialogueRuntime.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/TranscriptSource.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/mint/ClaimMint.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/mint/RelationMint.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/mint/StanceProject.kt
- delta-operators / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- delta-operators / shopping: demo/shopping/src/main/kotlin/civictech/demo/Main.kt
- delta-operators / skillmatch: demo/skillmatch/src/main/kotlin/civictech/demo/skillmatch/SkillMatchApp.kt
- delta-operators / slotfinder: demo/slotfinder/src/main/kotlin/civictech/demo/slotfinder/SlotFinderApp.kt
- delta-operators / social: demo/social/src/main/kotlin/civictech/demo/social/Feed.kt, demo/social/src/main/kotlin/civictech/demo/social/Queries.kt, demo/social/src/main/kotlin/civictech/demo/social/ShortReads.kt, demo/social/src/main/kotlin/civictech/demo/social/SnbPipeline.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialGraph.kt, demo/social/src/main/kotlin/civictech/demo/social/ViewerInterest.kt
- delta-operators / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
- glitch-free-observe / agora: demo/agora/src/main/kotlin/civictech/agora/cell/CredenceView.kt
- glitch-free-observe / alignment: demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentApp.kt
- glitch-free-observe / backlog-triage: demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/TriageApp.kt
- glitch-free-observe / deliberate: demo/deliberate/src/main/kotlin/civictech/deliberate/Cells.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/CredenceGraph.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/Durability.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/Sensitivity.kt
- glitch-free-observe / dialogue: demo/dialogue/src/main/kotlin/civictech/dialogue/DialogueRuntime.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/apply/GraphApplier.kt
- glitch-free-observe / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- glitch-free-observe / shopping: demo/shopping/src/main/kotlin/civictech/demo/Main.kt
- glitch-free-observe / skillmatch: demo/skillmatch/src/main/kotlin/civictech/demo/skillmatch/SkillMatchApp.kt
- glitch-free-observe / slotfinder: demo/slotfinder/src/main/kotlin/civictech/demo/slotfinder/SlotFinderApp.kt
- glitch-free-observe / social: demo/social/src/main/kotlin/civictech/demo/social/SocialApp.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialGraph.kt
- glitch-free-observe / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
- interest / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- interest / social: demo/social/src/main/kotlin/civictech/demo/social/Feed.kt, demo/social/src/main/kotlin/civictech/demo/social/SnbPipeline.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialApp.kt, demo/social/src/main/kotlin/civictech/demo/social/ViewerInterest.kt
- durability / agora: demo/agora/src/main/kotlin/civictech/agora/AgoraApp.kt
- durability / alignment: demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentApp.kt
- durability / allocator-observe: demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/AllocatorObserveApp.kt
- durability / backlog-triage: demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/TriageApp.kt
- durability / beadsmirror: demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/MirrorGraph.kt
- durability / deliberate: demo/deliberate/src/main/kotlin/civictech/deliberate/DeliberateApp.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/Durability.kt
- durability / dialogue: demo/dialogue/src/main/kotlin/civictech/dialogue/DialogueRuntime.kt
- durability / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- durability / social: demo/social/src/main/kotlin/civictech/demo/social/SocialApp.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialRecovery.kt
- durability / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
- replication / beadsmirror: demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/MirrorGraph.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/MirrorPeering.kt
- replication / shopping: demo/shopping/src/main/kotlin/civictech/demo/Main.kt
- replication / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
- wire / beadsmirror: demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/DiscoveredIrohPeerTransport.kt
- wire / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- wire / shopping: demo/shopping/src/main/kotlin/civictech/demo/Main.kt
- wire / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
- inspector / agora: demo/agora/src/main/kotlin/civictech/agora/AgoraApp.kt
- inspector / alignment: demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentApp.kt
- inspector / backlog-triage: demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/TriageApp.kt
- inspector / deliberate: demo/deliberate/src/main/kotlin/civictech/deliberate/DeliberateApp.kt
- inspector / dialogue: demo/dialogue/src/main/kotlin/civictech/dialogue/DialogueApp.kt
- inspector / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- inspector / shopping: demo/shopping/src/main/kotlin/civictech/demo/Main.kt
- inspector / skillmatch: demo/skillmatch/src/main/kotlin/civictech/demo/skillmatch/SkillMatchApp.kt
- inspector / slotfinder: demo/slotfinder/src/main/kotlin/civictech/demo/slotfinder/SlotFinderApp.kt
- inspector / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
- demograph / backlog-triage: demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/TriageApp.kt
- gen-cells / alignment: demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentCells.kt
- hosts / agora: demo/agora/src/main/kotlin/civictech/agora/AgoraApp.kt, demo/agora/src/main/kotlin/civictech/agora/AgoraService.kt
- hosts / alignment: demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentApp.kt, demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentCells.kt
- hosts / allocator-observe: demo/allocator-observe/src/main/kotlin/civictech/demo/allocatorobserve/AllocatorObserveApp.kt
- hosts / backlog-triage: demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/TriageApp.kt
- hosts / beadsmirror: demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/MirrorGraph.kt, demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/feed/FeedCursor.kt
- hosts / deliberate: demo/deliberate/src/main/kotlin/civictech/deliberate/CredenceGraph.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/DeliberateApp.kt, demo/deliberate/src/main/kotlin/civictech/deliberate/Durability.kt
- hosts / dialogue: demo/dialogue/src/main/kotlin/civictech/dialogue/DialoguePipeline.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/DialogueRuntime.kt, demo/dialogue/src/main/kotlin/civictech/dialogue/apply/GraphApplier.kt
- hosts / exchange: demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt
- hosts / skillmatch: demo/skillmatch/src/main/kotlin/civictech/demo/skillmatch/SkillMatchApp.kt
- hosts / slotfinder: demo/slotfinder/src/main/kotlin/civictech/demo/slotfinder/SlotFinderApp.kt
- hosts / social: demo/social/src/main/kotlin/civictech/demo/social/BoundedReader.kt, demo/social/src/main/kotlin/civictech/demo/social/SnbPipeline.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialApp.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialGraph.kt, demo/social/src/main/kotlin/civictech/demo/social/SocialRecovery.kt
- hosts / tiering: demo/tiering/src/main/kotlin/civictech/demo/tiering/TieringApp.kt
