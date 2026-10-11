------------------------------- MODULE Cuts -------------------------------
EXTENDS GlitchFree

RECURSIVE DownstreamCells(_)
DownstreamCells(e) ==
    {To[e]} \cup UNION {DownstreamCells(child) : child \in OutEdges(To[e])}

QuiescentStutter ==
    /\ \A s \in EmitFrom : counter[s] = WaveBound
    /\ \A e \in Edges : channel[e] = <<>>
    /\ LateEdges \ (live \cup linked) = {}
    /\ (CutEdges \ cutSent) \cap live = {}
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

\* [FRM1-P-12]: epoch/baseline task.
\* [FRM1-P-13]: epoch/baseline task.
\* [FRM1-P-14]: epoch/baseline task.
\* [FRM1-P-15]: epoch/baseline task.

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
