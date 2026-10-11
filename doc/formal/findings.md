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
