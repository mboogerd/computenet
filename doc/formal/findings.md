# Formal-lane findings

This is the append-only evidence ledger for `lane:evidence` work under the
formal-model epic (`computenet-7fe`, FRM1). A finding records what an executable
model established and the limits of that evidence. This lane does not edit the
normative specifications, roadmap plans, or the concordance gap table; any
change to those authorities requires its own follow-up item.

## BS-7 — the G-39 two-hop cut strands a waiting join ([FRM1-P-11])

Recorded by `computenet-tusn0.4`, feature `computenet-tusn0`, epic
`computenet-7fe`.

### Minimal configuration

The counterexample is checked by `formal/models/MCFrontierCut-chain2.cfg`
against module `MCFrontierCut`:

```tla
SPECIFICATION CutSpec
CONSTANTS
    Sources = {"A"}
    Cells = {"A", "M", "B", "D"}
    Edges = {"am", "md", "ab", "bd"}
    From <- ChainFrom
    To <- ChainTo
    WaveBound = 2
    EmitFrom = {"A"}
    MayAbsorb = {"M"}
    AbsorbAck = TRUE
    Fifo = TRUE
    LateEdges = {}
    CutEdges = {"am"}
    Frontier = {"D"}
    Carries <- ChainCarries
    IgnoreArm = {}
    WatermarkByMax = TRUE
    FloorIgnored = FALSE
    CloseIgnored = FALSE
    Restarts = 0
    Owner <- IdentityOwner
    Successor <- IdentitySuccessor
    FreshEpochIds = TRUE
    AnnounceRestart = TRUE
    Swappable = {}
    SwapEmitsMarker = FALSE
    CountBaselines = FALSE
INVARIANTS P01 P02 P04
PROPERTY P11
```

The checked-in expectation and the observed TLC diagnostic are exact:

```text
\* EXPECT Error: Temporal properties were violated.
\* OBSERVED Error: Temporal properties were violated.
```

A direct TLC v1.7.4 run with `-workers 1` generated 1,031 states and found 393
distinct states. TLC does not print a complete-state-graph depth for this
temporal violation; the behavior it prints reaches the terminal stutter at state
10. The harness reports this configuration as `PASS model(expected-violation)`.

### Counterexample trace

The trace below intentionally retains only `live`, `channel`, `open`, `floor`,
`watermark`, `pending`, and `released`, the state needed to explain the
failure. It is the behavior one recorded `-workers 1` run printed (TLC banner
`fp 95`). TLC picks its fingerprint function at random per run, so the order of
states 3–9 is not reproducible: four further `-workers 1` runs on the same
tree (`fp 107`, `13`, `96`, `80`) each printed a different interleaving, all
ten states long, all cutting `am` at state 2, and all reaching the same state 9.
The trace is not claimed to be the shortest violating behavior.

1. Initially, `live = {am, md, ab, bd}`; all four `open` entries are `TRUE`;
   every `floor` and `watermark` entry is zero; every `channel` is empty;
   `pending[D] = {}`; and `released[D][A] = <<>>`.
2. `CutStep(am)` removes `am` from `live` and leaves
   `channel[am] = <<EdgeClose>>`.
3. `M` consumes that close: `channel[am]` becomes empty, while `md` remains in
   `live`, `open[md] = TRUE`, and `channel[md]` remains empty.
4. The source emits wave 1, so `channel[ab] = <<Delta(A, 1)>>`.
5. The source emits wave 2, so
   `channel[ab] = <<Delta(A, 1), Delta(A, 2)>>`.
6. `B` forwards wave 1: `channel[ab] = <<Delta(A, 2)>>` and
   `channel[bd] = <<Delta(A, 1)>>`.
7. `B` forwards wave 2: `channel[ab] = <<>>` and
   `channel[bd] = <<Delta(A, 1), Delta(A, 2)>>`.
8. `D` consumes wave 1 on `bd`: `watermark[bd][A] = 1` and
   `pending[D] = {<<A, 1, bd>>}`; `released[D][A]` remains empty.
9. `D` consumes wave 2 on `bd`: `watermark[bd][A] = 2` and
   `pending[D] = {<<A, 1, bd>>, <<A, 2, bd>>}`. All channels are empty, but
   `open[md] = TRUE`, `floor[md][A] = 0`, `watermark[md][A] = 0`, and
   `released[D][A] = <<>>`.
10. `CutSpec` stutters in that quiescent state, so neither waiting wave can
    satisfy `P11`.

### Reading

The cut is upstream of the frontier: `am` closes, and the generic intermediate
cell `M` consumes the `EdgeClose`. Under G-36, that link metadata is single-hop,
so `M` does not propagate a source-set change onto `md`. Later waves still
arrive along `bd`, while `D` continues to expect matching settlement on its
open `md` arm forever. That is the G-39 residual: the local close rule is
insufficient to make the downstream topology view live.

The result is evidence against G-39's proposed liveness rule and the associated
unnumbered topology prose. E3 is already marked LANDED; this trace shows that
the remaining question is a specification input for the KE3/topology work, not
a request to reopen the landed implementation item.

The abstraction is deliberately small. `M` represents a generic
pass-through-or-absorbing intermediate, whereas the runtime's unary
`relayAbsorbAcks` path forwards progress only while exactly one consume input
is open. The model also omits operator-specific source knowledge, physical link
re-resolution, and transport timing. Those limits do not manufacture the
counterexample: its close behavior directly preserves G-36's single-hop rule.

### Disposition

This evidence lane chooses neither possible semantic repair. The specification
must decide whether source-set closure propagates beyond the cell that directly
observes a cut, or whether the resulting impossible-source wait becomes a
documented `Stall` with an explicit `RE-SCOPE` recovery path. That decision is
tracked by `computenet-co98h`; this finding does not silently select either
alternative.

No entry was added to `concord/corpus/DISPUTES.md`. The trace defeats no
numbered EARS requirement: `[22-LIVE-01]` concerns an independently silent
source, not topology removal, and the `[22-GF-01]`/`[22-GF-02]` safety rules
continue to hold because the waves remain buffered rather than releasing torn.
The conflict is therefore with decided prose plus the G-39 proposal, and stays
in this evidence ledger until the specification follow-up resolves it.

## BS-5 guard — an unguarded late open strands a waiting join (G-39; 93 I-13)

Recorded by `computenet-ayuhs`, feature `computenet-tusn0`, epic
`computenet-7fe`.

### Minimal configuration

The counterexample uses the constants of
`formal/models/MCFrontierCut-openLate.cfg` against module `MCFrontierCut`:

```tla
SPECIFICATION CutSpec
CONSTANTS
    Sources = {"A"}
    Cells = {"A", "B", "C", "D"}
    Edges = {"ab", "ac", "bd", "cd"}
    From <- OpenLateFrom
    To <- OpenLateTo
    WaveBound = 3
    EmitFrom = {"A"}
    MayAbsorb = {}
    AbsorbAck = TRUE
    Fifo = TRUE
    LateEdges = {"cd"}
    CutEdges = {}
    Frontier = {"D"}
    Carries <- OpenLateCarries
    IgnoreArm = {}
    WatermarkByMax = TRUE
    FloorIgnored = FALSE
    CloseIgnored = FALSE
    Restarts = 0
    Owner <- IdentityOwner
    Successor <- IdentitySuccessor
    FreshEpochIds = TRUE
    AnnounceRestart = TRUE
    Swappable = {}
    SwapEmitsMarker = FALSE
    CountBaselines = FALSE
PROPERTY P05
```

Mutation: delete the three-line `IF`/`THEN`/`ELSE` scenario guard from
`LinkStep(e)` in `formal/specs/Frontier.tla`, leaving `Link(e)` and its frame
condition unchanged. A second run replaces `PROPERTY P05` with
`INVARIANT P01`; each run names only the property being reproduced.

Direct TLC runs on the pinned jar with `-workers 1` observed:

```text
P05: exit 13 — Error: Temporal properties were violated.
P01: exit 12 — Error: Invariant P01 is violated.
```

The P05 run generated 2,337 states and found 902 distinct states. The P01 run
stopped at the invariant violation after 1,168 generated and 488 distinct
states. The checked-in guard was restored after both runs; this finding records
the temporary mutation, not a model change.

### Counterexample trace

The trace retains only the variables that change or establish the terminal
wait. It is one recorded `-workers 1` P05 behavior (TLC banner `fp 17`). TLC
randomizes its fingerprint function per run, so this interleaving is not
claimed to be reproducible or shortest.

1. Initially, `counter[A] = 0`; `live = {ab, ac, bd}`; `cd` is closed; every
   `floor` and `watermark` entry is zero; every channel and `pending[D]` is
   empty; and `released[D][A] = <<>>`.
2. The source emits wave 1: `counter[A] = 1`, with `Delta(A, 1)` on `ab` and
   `ac`.
3. `C` consumes wave 1 from `ac`; because `cd` is not live, that wave is not
   forwarded onto `cd`.
4. The source emits wave 2: `counter[A] = 2`; `ab` now holds waves 1 and 2,
   and `ac` holds wave 2.
5. `C` consumes wave 2, again while `cd` is not live.
6. The source emits wave 3: `counter[A] = 3`; `ab` holds waves 1–3 and `ac`
   holds wave 3.
7. `B` forwards wave 1: `ab` retains waves 2–3 and `bd` holds wave 1.
8. `C` consumes wave 3, still before the late edge exists.
9. `B` forwards wave 2: `ab` retains wave 3 and `bd` holds waves 1–2.
10. `LinkStep(cd)` adds `cd` to `live` and queues `EdgeOpen` followed by
    `Baseline(A)` on `cd`.
11. `B` forwards wave 3: `ab` is empty and `bd` holds waves 1–3.
12. `D` consumes `EdgeOpen`: `open[cd] = TRUE` and
    `floor[cd][A] = flushedHigh[D][A] = 0`; the baseline remains on `cd`.
13. `D` consumes wave 1 from `bd`: `watermark[bd][A] = 1` and
    `pending[D] = {<<A, 1, bd>>}`.
14. `D` consumes wave 2 from `bd`: `watermark[bd][A] = 2` and the pending set
    gains `<<A, 2, bd>>`.
15. `D` consumes wave 3 from `bd`: `watermark[bd][A] = 3`; `bd` is empty; and
    the pending set gains `<<A, 3, bd>>`.
16. `D` consumes `Baseline(A)`. All channels are now empty, but
    `open[cd] = TRUE`, `floor[cd][A] = 0`, `watermark[cd][A] = 0`,
    `pending[D] = {<<A, 1, bd>>, <<A, 2, bd>>, <<A, 3, bd>>}`, and
    `released[D][A] = <<>>`.
17. `CutSpec` stutters in that quiescent state, so P05 fails.

### Reading

The guard requires the target frontier to have flushed every already-emitted
counter carried by the late edge before `LinkStep` can fire. That is a scenario
restriction encoding BS-5's given that the new arm opens after the earlier
waves flush, not a kernel rule. Without it, `C` may consume waves before `cd`
exists while `D` has not flushed them. Opening `cd` at floor zero then makes
`D` expect that arm for waves it can never receive there. The Baseline message
does not repair the wait: in the model it records catch-up arrival without
advancing `watermark`, changing `pending`, or representing a wave position.

This is the added-link half of the G-39 / 93 I-13 source-set dissemination
residual. P05 exposes the liveness failure directly. P01 also fails because a
later delivery on the new arm can advance it far enough to release an earlier
wave without the contribution the new arm never carried.

`unverified:` no kernel test reproduces this complete late-open interleaving.
The relevant code has the same local ingredients:
`Handshake.handshake` delivers `EdgeOpen` before on-link catch-up;
`WaveFrontier.registerHandlers` installs the edge with
`flushedHighWater.toMap()`; and `WaveFrontier.offer` releases a non-null
`MessageContext.baseline` immediately without advancing a watermark or adding
pending wave state. Those named anchors establish the correspondence, not that
the runtime reaches the model's stranded state.

### Disposition

The existing G-39 follow-up, `computenet-co98h`, owns both this added-link
case and the BS-7 upstream-cut case. It must decide whether hop-by-hop source-set
propagation prevents the impossible expectation, whether a local drain or
explicit `Stall`/`RE-SCOPE` rule closes it, and what runtime scenario verifies
the decision. This finding records the counterexample and selects no repair.
