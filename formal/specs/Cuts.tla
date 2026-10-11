------------------------------- MODULE Cuts -------------------------------
EXTENDS GlitchFree

RECURSIVE DownstreamCells(_)
DownstreamCells(e) ==
    {To[e]} \cup UNION {DownstreamCells(child) : child \in OutEdges(To[e])}

QuiescentStutter ==
    /\ \A s \in liveSources :
        Owner[s] \in EmitFrom => counter[s] = WaveBound
    /\ \A e \in Edges : channel[e] = <<>>
    /\ LateEdges \ (live \cup linked) = {}
    /\ (CutEdges \ cutSent) \cap live = {}
    /\ ~\E s \in Sources : RestartEnabled(s)
    /\ ~\E c \in Cells : InstanceSwapEnabled(c)
    /\ UNCHANGED vars

CutSpec == Init /\ [][Next \/ QuiescentStutter]_vars /\ Fairness

\* [FRM1-P-09] FloorDiscards — a frontier never applies a counter at or
\* below the floor established when its edge opened.
P09 ==
    \A c \in Frontier :
        \A e \in InEdges(c) :
            \A s \in Sources :
                applied[c][e][s] = 0 \/ applied[c][e][s] > floor[e][s]

\* [FRM1-P-10] CloseUnblocks — every wave recorded while processing the
\* close of an edge it awaited is eventually released.
P10 ==
    \A c \in Frontier :
        \A s \in Sources :
            \A t \in 1..WaveBound :
                (<<c, s, t>> \in closedWaiting)
                    ~> t \in Range(released[c][s])

\* [FRM1-P-11] TwoHopCutNotStranded — the expected G-39 counterexample:
\* a processed upstream cut is single-hop, so a downstream join may wait.
P11 ==
    \A c \in Frontier :
        \A s \in Sources :
            \A t \in 1..WaveBound :
                ((\E entry \in pending[c] :
                    entry[1] = s /\ entry[2] = t)
                 /\ (\E e \in cutDone :
                    s \in Upstream(e) /\ c \in DownstreamCells(e)))
                    ~> t \in Range(released[c][s])

\* [FRM1-P-12] NoCounterReuseAcrossEpochs.  Pair uniqueness is the
\* combination of monotone counters per source id and disjoint ids per epoch,
\* the latter being constructed by Successor when FreshEpochIds is true.
P12 ==
    [][\A s \in Sources : counter'[s] >= counter[s]]_vars

\* [FRM1-P-13] ReBaselinePrecedesNewEpoch + CompletenessRecomputed.  A
\* successor delta cannot be received at a frontier until every predecessor
\* id is retired there, and retirement leaves no stale pending entry behind.
P13 ==
    /\ \A c \in Frontier :
        \A s \in received[c] : Predecessors(s) \subseteq retired[c]
    /\ \A c \in Frontier :
        \A entry \in pending[c] : entry[1] \notin retired[c]

\* [FRM1-P-14] PreservedEpochSwapInvisible.  This is intentionally near-
\* definitional: the model checks the swap action's explicit wave-plane seam.
P14 ==
    [][\A c \in Swappable :
        InstanceSwap(c) =>
            /\ channel' = channel
            /\ watermark' = watermark
            /\ floor' = floor
            /\ open' = open]_vars

\* [FRM1-P-15] BaselineNeverCounted, stated as an action property.  Channel
\* movement and the baselines history may change; the completeness fold may not.
P15 ==
    [][\A e \in Edges :
        \A i \in 1..Len(channel[e]) :
            FrontierBaselineDelivery(e, i) =>
                /\ watermark' = watermark
                /\ pending' = pending
                /\ released' = released]_vars

\* [FRM1-P-16] GlitchFreedomResumesPostInstall.  Each release witness stores
\* the in-edges that were open and the subset that was above-floor and settled
\* at that instant.  Thus P02 remains sound while open/floor move: floors are
\* written once at EdgeOpen from flushedHigh, and a later close only removes an
\* expectation; P16 checks the historical release condition rather than the
\* current frontier.
P16 ==
    /\ \A c \in Frontier :
        \A w \in releaseWitness[c] :
            \A e \in LateEdges \cap InEdges(c) :
                e \in w.openEdges => floor[e][w.src] < w.t
    /\ \A c \in Frontier :
        \A w \in releaseWitness[c] :
            \A e \in LateEdges \cap InEdges(c) :
                (e \in w.openEdges /\ floor[e][w.src] < w.t)
                    => e \in w.settledEdges

=============================================================================
