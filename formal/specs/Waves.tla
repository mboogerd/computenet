------------------------------ MODULE Waves ------------------------------
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Sources, Cells, Edges, From, To, WaveBound,
          EmitFrom, MayAbsorb, AbsorbAck, Fifo,
          LateEdges, CutEdges

VARIABLES counter, channel, absorbed, acked, live, linked, cutSent

waveVars == <<counter, channel, absorbed, acked, live, linked, cutSent>>

Delta(s, t) == [kind |-> "delta", src |-> s, t |-> t]
Progress(s, t) == [kind |-> "progress", src |-> s, t |-> t]
EdgeOpen == [kind |-> "edgeOpen"]
EdgeClose == [kind |-> "edgeClose"]

InEdges(c) == {e \in Edges : To[e] = c}
OutEdges(c) == {e \in Edges : From[e] = c}

RemoveAt(seq, i) == SubSeq(seq, 1, i - 1) \o SubSeq(seq, i + 1, Len(seq))

Enqueue(channels, targets, message) ==
    [e \in Edges |->
        IF e \in targets THEN Append(channels[e], message) ELSE channels[e]]

PickPositions(e) == IF Fifo THEN {1} ELSE 1..Len(channel[e])

RECURSIVE SetToSeq(_)
SetToSeq(set) ==
    IF set = {}
    THEN <<>>
    ELSE LET value == CHOOSE value \in set : TRUE
         IN <<value>> \o SetToSeq(set \ {value})

InitWaves ==
    /\ counter = [s \in Sources |-> 0]
    /\ channel = [e \in Edges |-> <<>>]
    /\ absorbed = [c \in Cells |-> {}]
    /\ acked = [e \in Edges |-> {}]
    /\ live = Edges \ LateEdges
    /\ linked = {}
    /\ cutSent = {}

Emit(s) ==
    /\ s \in EmitFrom
    /\ counter[s] < WaveBound
    /\ counter' = [counter EXCEPT ![s] = @ + 1]
    /\ channel' = Enqueue(
        channel, OutEdges(s) \cap live, Delta(s, counter[s] + 1))
    /\ UNCHANGED <<absorbed, acked, live, linked, cutSent>>

ForwardAt(e, i) ==
    LET message == channel[e][i]
        cell == To[e]
        remaining == [edge \in Edges |->
            IF edge = e THEN RemoveAt(channel[edge], i) ELSE channel[edge]]
    IN
    /\ message.kind \in {"delta", "progress"}
    /\ channel' = Enqueue(remaining, OutEdges(cell) \cap live, message)
    /\ UNCHANGED <<counter, absorbed, acked, live, linked, cutSent>>

AbsorbAt(e, i) ==
    LET message == channel[e][i]
        cell == To[e]
        wave == <<message.src, message.t>>
        remaining == [edge \in Edges |->
            IF edge = e THEN RemoveAt(channel[edge], i) ELSE channel[edge]]
    IN
    /\ message.kind = "delta"
    /\ cell \in MayAbsorb
    /\ absorbed' = [absorbed EXCEPT ![cell] = @ \cup {wave}]
    /\ IF AbsorbAck
       THEN /\ channel' = Enqueue(
                    remaining, OutEdges(cell) \cap live,
                    Progress(message.src, message.t))
            /\ acked' = [edge \in Edges |->
                IF edge \in OutEdges(cell) \cap live
                THEN acked[edge] \cup {wave}
                ELSE acked[edge]]
       ELSE /\ channel' = remaining
            /\ UNCHANGED acked
    /\ UNCHANGED <<counter, live, linked, cutSent>>

Link(e) ==
    /\ e \in LateEdges
    /\ e \notin live
    /\ e \notin linked
    /\ live' = live \cup {e}
    /\ linked' = linked \cup {e}
    \* The epoch/baseline task appends Baseline immediately after EdgeOpen.
    /\ channel' = Enqueue(channel, {e}, EdgeOpen)
    /\ UNCHANGED <<counter, absorbed, acked, cutSent>>

Cut(e) ==
    /\ e \in CutEdges \cap live
    /\ e \notin cutSent
    /\ live' = live \ {e}
    /\ cutSent' = cutSent \cup {e}
    /\ channel' = Enqueue(channel, {e}, EdgeClose)
    /\ UNCHANGED <<counter, absorbed, acked, linked>>

\* Frontier delivery lives in Frontier.tla because it needs that module's
\* constants and state. This action is the common non-frontier half.
DeliverNonFrontier(e, i) ==
    /\ i \in PickPositions(e)
    /\ \/ ForwardAt(e, i)
       \/ AbsorbAt(e, i)

=============================================================================
