------------------------------ MODULE Waves ------------------------------
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Sources, Cells, Edges, From, To, WaveBound,
          EmitFrom, MayAbsorb, AbsorbAck, Fifo,
          LateEdges, CutEdges, Carries,
          Restarts, Owner, Successor, FreshEpochIds, AnnounceRestart,
          Swappable, SwapEmitsMarker, CountBaselines

VARIABLES counter, channel, absorbed, acked, live, linked, cutSent,
          liveSources, restartCount, swapped

waveVars ==
    <<counter, channel, absorbed, acked, live, linked, cutSent,
      liveSources, restartCount, swapped>>

Delta(s, t) == [kind |-> "delta", src |-> s, t |-> t]
Progress(s, t) == [kind |-> "progress", src |-> s, t |-> t]
Baseline(s) == [kind |-> "baseline", src |-> s]
ReBaseline(superseded) == [kind |-> "reBaseline", supersedes |-> superseded]
EdgeOpen == [kind |-> "edgeOpen"]
EdgeClose == [kind |-> "edgeClose"]

InEdges(c) == {e \in Edges : To[e] = c}
OutEdges(c) == {e \in Edges : From[e] = c}

RemoveAt(seq, i) == SubSeq(seq, 1, i - 1) \o SubSeq(seq, i + 1, Len(seq))

Enqueue(channels, targets, message) ==
    [e \in Edges |->
        IF e \in targets THEN Append(channels[e], message) ELSE channels[e]]

EnqueueMessages(channels, targets, messages) ==
    [e \in Edges |->
        IF e \in targets THEN channels[e] \o messages ELSE channels[e]]

PickPositions(e) == IF Fifo THEN {1} ELSE 1..Len(channel[e])

RECURSIVE SetToSeq(_)
SetToSeq(set) ==
    IF set = {}
    THEN <<>>
    ELSE LET value == CHOOSE value \in set : TRUE
         IN <<value>> \o SetToSeq(set \ {value})

RECURSIVE BaselinesFor(_)
BaselinesFor(sourceSeq) ==
    IF Len(sourceSeq) = 0
    THEN <<>>
    ELSE <<Baseline(Head(sourceSeq))>> \o BaselinesFor(Tail(sourceSeq))

IdentityOwner == [s \in Sources |-> s]
IdentitySuccessor == [s \in Sources |-> s]

Predecessors(s) ==
    {predecessor \in Sources :
        predecessor # s /\ Successor[predecessor] = s}

InitialSources == {s \in Sources : Predecessors(s) = {}}

RestartEnabled(s) ==
    /\ s \in liveSources
    /\ Owner[s] \in EmitFrom
    /\ restartCount[Owner[s]] < Restarts
    /\ IF FreshEpochIds THEN Successor[s] # s ELSE TRUE

InstanceSwapEnabled(c) == c \in Swappable \ swapped

InitWaves ==
    /\ counter = [s \in Sources |-> 0]
    /\ channel = [e \in Edges |-> <<>>]
    /\ absorbed = [c \in Cells |-> {}]
    /\ acked = [e \in Edges |-> {}]
    /\ live = Edges \ LateEdges
    /\ linked = {}
    /\ cutSent = {}
    /\ liveSources = InitialSources
    /\ restartCount = [c \in Cells |-> 0]
    /\ swapped = {}

Emit(s) ==
    /\ s \in liveSources
    /\ Owner[s] \in EmitFrom
    /\ counter[s] < WaveBound
    /\ counter' = [counter EXCEPT ![s] = @ + 1]
    /\ channel' = Enqueue(
        channel, OutEdges(Owner[s]) \cap live, Delta(s, counter[s] + 1))
    /\ UNCHANGED
        <<absorbed, acked, live, linked, cutSent,
          liveSources, restartCount, swapped>>

ForwardAt(e, i) ==
    LET message == channel[e][i]
        cell == To[e]
        remaining == [edge \in Edges |->
            IF edge = e THEN RemoveAt(channel[edge], i) ELSE channel[edge]]
    IN
    /\ message.kind \in {"delta", "progress", "baseline", "reBaseline"}
    /\ channel' = Enqueue(remaining, OutEdges(cell) \cap live, message)
    /\ UNCHANGED
        <<counter, absorbed, acked, live, linked, cutSent,
          liveSources, restartCount, swapped>>

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
    /\ UNCHANGED
        <<counter, live, linked, cutSent,
          liveSources, restartCount, swapped>>

Link(e) ==
    /\ e \in LateEdges
    /\ e \notin live
    /\ e \notin linked
    /\ live' = live \cup {e}
    /\ linked' = linked \cup {e}
    /\ channel' = EnqueueMessages(
        channel,
        {e},
        <<EdgeOpen>> \o BaselinesFor(SetToSeq(Carries[e] \cap liveSources)))
    /\ UNCHANGED
        <<counter, absorbed, acked, cutSent,
          liveSources, restartCount, swapped>>

Cut(e) ==
    /\ e \in CutEdges \cap live
    /\ e \notin cutSent
    /\ live' = live \ {e}
    /\ cutSent' = cutSent \cup {e}
    /\ channel' = Enqueue(channel, {e}, EdgeClose)
    /\ UNCHANGED
        <<counter, absorbed, acked, linked,
          liveSources, restartCount, swapped>>

Restart(s) ==
    LET successor == IF FreshEpochIds THEN Successor[s] ELSE s
        owner == Owner[s]
        nextLive ==
            IF FreshEpochIds
            THEN (liveSources \ {s}) \cup {successor}
            ELSE liveSources
    IN
    /\ RestartEnabled(s)
    /\ liveSources' = nextLive
    /\ counter' = [counter EXCEPT ![successor] = 0]
    /\ restartCount' = [restartCount EXCEPT ![owner] = @ + 1]
    /\ channel' =
        IF AnnounceRestart
        THEN Enqueue(channel, OutEdges(owner) \cap live, ReBaseline({s}))
        ELSE channel
    /\ UNCHANGED <<absorbed, acked, live, linked, cutSent, swapped>>

InstanceSwap(c) ==
    /\ InstanceSwapEnabled(c)
    /\ swapped' = swapped \cup {c}
    /\ channel' =
        IF SwapEmitsMarker
        THEN Enqueue(channel, OutEdges(c) \cap live, EdgeOpen)
        ELSE channel
    /\ UNCHANGED
        <<counter, absorbed, acked, live, linked, cutSent,
          liveSources, restartCount>>

\* Frontier delivery lives in Frontier.tla because it needs that module's
\* constants and state. This action is the common non-frontier half.
DeliverNonFrontier(e, i) ==
    /\ i \in PickPositions(e)
    /\ \/ ForwardAt(e, i)
       \/ AbsorbAt(e, i)

=============================================================================
