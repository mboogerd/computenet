---------------------------- MODULE GlitchFree ----------------------------
EXTENDS Frontier

RECURSIVE Upstream(_)
Upstream(e) ==
    IF From[e] \in Sources
    THEN {From[e]}
    ELSE UNION {Upstream(parent) : parent \in InEdges(From[e])}

ASSUME \A e \in Edges : Upstream(e) \subseteq Carries[e]

Fairness == \A e \in Edges : WF_vars(Deliver(e))

Spec == Init /\ [][Next]_vars /\ Fairness

Range(sequence) == {sequence[i] : i \in DOMAIN sequence}

\* [FRM1-P-01] NoTornWave — [22-GF-01]
P01 ==
    \A c \in Frontier :
        \A s \in Sources :
            \A e1, e2 \in Expects(c, s) :
                \A t \in (applied[c][e2][s] + 1)..applied[c][e1][s] :
                    <<s, t>> \in absorbed[From[e2]]

\* [FRM1-P-02] CompletenessSound — completeness over silent or stuck edges
P02 ==
    \A c \in Frontier :
        \A s \in Sources :
            \A t \in Range(released[c][s]) :
                \A e \in Expects(c, s) :
                    (open[e] /\ e \notin suspended /\ floor[e][s] < t)
                        => watermark[e][s] >= t

\* [FRM1-P-03] WatermarkMonotone — G-40 max fold
P03 ==
    [][\A e \in Edges, s \in Sources :
        watermark'[e][s] >= watermark[e][s]]_vars

\* [FRM1-P-04] ReleaseOrder — per-source waves release strictly in order
P04 ==
    \A c \in Frontier :
        \A s \in Sources :
            \A i, j \in DOMAIN released[c][s] :
                i < j => released[c][s][i] < released[c][s][j]

\* [FRM1-P-05] EventuallyReleased — G-40 liveness
P05 ==
    \A c \in Frontier :
        \A s \in Sources :
            \A t \in 1..WaveBound :
                (\E entry \in pending[c] :
                    entry[1] = s /\ entry[2] = t)
                    ~> t \in Range(released[c][s])

\* [FRM1-P-06] AbsorbAckEmitted — [22-LIVE-01] absorb-ack rule
P06 ==
    \A c \in MayAbsorb :
        \A s \in Sources :
            \A t \in 1..WaveBound :
                <<s, t>> \in absorbed[c]
                    ~> \A e \in OutEdges(c) : <<s, t>> \in acked[e]

\* [FRM1-P-07] AbsorbAckLoadBearing — BS-3 control only
\* Defined by controls/MCNoAbsorbAck.cfg, where disabling absorb-acks must
\* strand a pending wave through either a deadlock or a P05 violation.

\* [FRM1-P-08] SilentSourceNeverBlocks — [22-LIVE-01]
P08 ==
    \A c \in Frontier :
        \A s \in Sources :
            \A t \in 1..WaveBound :
                (\E entry \in pending[c] :
                    entry[1] = s /\ entry[2] = t)
                    => ~(\A e \in {edge \in InEdges(c) : s \in Upstream(edge)} :
                        (open[e] /\ e \notin suspended /\ floor[e][s] < t)
                            => watermark[e][s] >= t)

=============================================================================
