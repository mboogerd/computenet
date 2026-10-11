----------------------------- MODULE Frontier -----------------------------
EXTENDS Waves

CONSTANTS Frontier, Carries, IgnoreArm, WatermarkByMax

VARIABLES watermark, floor, open, suspended,
          pending, released, applied, flushedHigh

frontierVars ==
    <<watermark, floor, open, suspended, pending, released, applied, flushedHigh>>

vars ==
    <<counter, channel, absorbed, acked,
      watermark, floor, open, suspended, pending, released, applied, flushedHigh>>

Expects(c, s) == {e \in InEdges(c) : s \in Carries[e]}

CompleteAt(watermarks, c, s, t) ==
    \A e \in Expects(c, s) \ IgnoreArm :
        (open[e] /\ e \notin suspended /\ floor[e][s] < t)
            => watermarks[e][s] >= t

Complete(c, s, t) == CompleteAt(watermark, c, s, t)

MinNat(set) == CHOOSE value \in set : \A other \in set : value <= other
MaxNat(set) == CHOOSE value \in set : \A other \in set : value >= other

RECURSIVE SortedCounters(_)
SortedCounters(set) ==
    IF set = {}
    THEN <<>>
    ELSE LET least == MinNat(set)
         IN <<least>> \o SortedCounters(set \ {least})

RECURSIVE MessagesForSources(_, _)
MessagesForSources(sourceSeq, ready) ==
    IF Len(sourceSeq) = 0
    THEN <<>>
    ELSE LET source == Head(sourceSeq)
             counters == SortedCounters(
                 {t \in 1..WaveBound : <<source, t>> \in ready})
             messages == [i \in 1..Len(counters) |-> Delta(source, counters[i])]
         IN messages \o MessagesForSources(Tail(sourceSeq), ready)

ReadyMessages(ready) ==
    MessagesForSources(
        SetToSeq({s \in Sources : \E t \in 1..WaveBound : <<s, t>> \in ready}),
        ready)

Init ==
    /\ InitWaves
    /\ watermark = [e \in Edges |-> [s \in Sources |-> 0]]
    /\ floor = [e \in Edges |-> [s \in Sources |-> 0]]
    /\ open = [e \in Edges |-> TRUE]
    /\ suspended = {}
    /\ pending = [c \in Frontier |-> {}]
    /\ released = [c \in Frontier |-> [s \in Sources |-> <<>>]]
    /\ applied = [c \in Frontier |->
        [e \in Edges |-> [s \in Sources |-> 0]]]
    /\ flushedHigh = [c \in Frontier |-> [s \in Sources |-> 0]]

EmitStep(s) ==
    /\ Emit(s)
    /\ UNCHANGED frontierVars

FrontierDelivery(e, i) ==
    LET message == channel[e][i]
        cell == To[e]
        remaining == [edge \in Edges |->
            IF edge = e THEN RemoveAt(channel[edge], i) ELSE channel[edge]]
        oldWatermark == watermark[e][message.src]
        nextWatermark ==
            IF WatermarkByMax
            THEN IF oldWatermark >= message.t THEN oldWatermark ELSE message.t
            ELSE message.t
        watermarks == [watermark EXCEPT ![e][message.src] = nextWatermark]
        late == message.kind = "delta" /\ message.t <= flushedHigh[cell][message.src]
        buffered ==
            IF message.kind = "delta" /\ ~late
            THEN pending[cell] \cup {<<message.src, message.t, e>>}
            ELSE pending[cell]
        ready ==
            {wave \in Sources \X (1..WaveBound) :
                /\ \E entry \in buffered :
                    entry[1] = wave[1] /\ entry[2] = wave[2]
                /\ CompleteAt(watermarks, cell, wave[1], wave[2])}
        ReadyCounters(source) ==
            {t \in 1..WaveBound : <<source, t>> \in ready}
        LateCounters(source) ==
            IF late /\ source = message.src THEN <<message.t>> ELSE <<>>
        releasedAtCell ==
            [source \in Sources |->
                released[cell][source]
                    \o LateCounters(source)
                    \o SortedCounters(ReadyCounters(source))]
        AppliedCandidates(edge, source) ==
            {t \in ReadyCounters(source) : <<source, t, edge>> \in buffered}
                \cup
            IF late /\ edge = e /\ source = message.src THEN {message.t} ELSE {}
        appliedAtCell ==
            [edge \in Edges |-> [source \in Sources |->
                LET candidates == AppliedCandidates(edge, source)
                IN IF candidates = {}
                   THEN applied[cell][edge][source]
                   ELSE MaxNat(candidates)]]
        highAtCell ==
            [source \in Sources |->
                LET counters == ReadyCounters(source)
                IN IF counters = {}
                   THEN flushedHigh[cell][source]
                   ELSE MaxNat(counters)]
        stillPending ==
            {entry \in buffered : <<entry[1], entry[2]>> \notin ready}
        emitted ==
            (IF late THEN <<Delta(message.src, message.t)>> ELSE <<>>)
                \o ReadyMessages(ready)
    IN
    /\ watermark' = watermarks
    /\ pending' = [pending EXCEPT ![cell] = stillPending]
    /\ released' = [released EXCEPT ![cell] = releasedAtCell]
    /\ applied' = [applied EXCEPT ![cell] = appliedAtCell]
    /\ flushedHigh' = [flushedHigh EXCEPT ![cell] = highAtCell]
    /\ channel' = [edge \in Edges |->
        IF edge \in OutEdges(cell)
        THEN remaining[edge] \o emitted
        ELSE remaining[edge]]
    /\ UNCHANGED <<counter, absorbed, acked, floor, open, suspended>>

\* The complete Deliver action lives here, rather than an abstract hook in
\* Waves.tla: only this module can distinguish a frontier cell and update its
\* completeness fold atomically with dequeuing the message.
Deliver(e) ==
    /\ Len(channel[e]) > 0
    /\ \E i \in PickPositions(e) :
        IF To[e] \in Frontier
        THEN FrontierDelivery(e, i)
        ELSE /\ DeliverNonFrontier(e, i)
             /\ UNCHANGED frontierVars

Terminating ==
    /\ \A s \in EmitFrom : counter[s] = WaveBound
    /\ \A e \in Edges : channel[e] = <<>>
    /\ \A c \in Frontier : pending[c] = {}
    /\ UNCHANGED vars

Next ==
    \/ \E s \in Sources : EmitStep(s)
    \/ \E e \in Edges : Deliver(e)
    \/ Terminating

=============================================================================
