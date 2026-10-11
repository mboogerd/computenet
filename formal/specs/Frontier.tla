----------------------------- MODULE Frontier -----------------------------
EXTENDS Waves

CONSTANTS Frontier, Carries, IgnoreArm, WatermarkByMax,
          FloorIgnored, CloseIgnored

VARIABLES watermark, floor, open, suspended,
          pending, released, applied, flushedHigh,
          releaseWitness, cutDone, closedWaiting

frontierVars ==
    <<watermark, floor, open, suspended, pending, released, applied, flushedHigh,
      releaseWitness, cutDone, closedWaiting>>

vars ==
    <<counter, channel, absorbed, acked, live, linked, cutSent,
      watermark, floor, open, suspended, pending, released, applied, flushedHigh,
      releaseWitness, cutDone, closedWaiting>>

Expects(c, s) == {e \in InEdges(c) : s \in Carries[e]}

CompleteUnder(watermarks, opens, floors, c, s, t) ==
    \A e \in Expects(c, s) \ IgnoreArm :
        (opens[e] /\ e \notin suspended /\ floors[e][s] < t)
            => watermarks[e][s] >= t

CompleteAt(watermarks, c, s, t) ==
    CompleteUnder(watermarks, open, floor, c, s, t)

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

PendingWaves(entries) ==
    {wave \in Sources \X (1..WaveBound) :
        \E entry \in entries : entry[1] = wave[1] /\ entry[2] = wave[2]}

ReadyWaves(watermarks, opens, floors, c, buffered) ==
    {wave \in PendingWaves(buffered) :
        CompleteUnder(watermarks, opens, floors, c, wave[1], wave[2])}

ReleaseWitnesses(waves, c, opens, floors, watermarks) ==
    {[src |-> wave[1],
      t |-> wave[2],
      openEdges |-> {e \in Expects(c, wave[1]) : opens[e]},
      settledEdges |->
          {e \in Expects(c, wave[1]) :
              opens[e]
              /\ floors[e][wave[1]] < wave[2]
              /\ watermarks[e][wave[1]] >= wave[2]}]
        : wave \in waves}

Init ==
    /\ InitWaves
    /\ watermark = [e \in Edges |-> [s \in Sources |-> 0]]
    /\ floor = [e \in Edges |-> [s \in Sources |-> 0]]
    /\ open = [e \in Edges |-> e \notin LateEdges]
    /\ suspended = {}
    /\ pending = [c \in Frontier |-> {}]
    /\ released = [c \in Frontier |-> [s \in Sources |-> <<>>]]
    /\ applied = [c \in Frontier |->
        [e \in Edges |-> [s \in Sources |-> 0]]]
    /\ flushedHigh = [c \in Frontier |-> [s \in Sources |-> 0]]
    /\ releaseWitness = [c \in Frontier |-> {}]
    /\ cutDone = {}
    /\ closedWaiting = {}

EmitStep(s) ==
    /\ Emit(s)
    /\ UNCHANGED frontierVars

\* The BS-5 late-open scenario linearizes Link only at a consumer frontier:
\* every already-emitted source counter has flushed there.  Link may still
\* happen at counter zero, and data emitted after it queues behind EdgeOpen.
\* This is a SCENARIO GUARD, not a kernel rule.  It excludes the interleaving
\* where the new arm's producer consumed a wave before the link while that
\* wave is still unflushed at the consumer: the consumer then installs a floor
\* below the producer's position and expects the edge for a wave the edge
\* never carries.  With the guard removed, MCFrontierCut-openLate (WaveBound
\* 3) violates P01 and P05 (the join strands at quiescence) while P02, P03,
\* P04, P09 and P16 still hold -- observed with TLC, review of
\* computenet-tusn0.2.
LinkStep(e) ==
    /\ IF To[e] \in Frontier
       THEN \A s \in Carries[e] : flushedHigh[To[e]][s] = counter[s]
       ELSE TRUE
    /\ Link(e)
    /\ UNCHANGED frontierVars

CutStep(e) ==
    /\ Cut(e)
    /\ UNCHANGED frontierVars

FrontierDataDelivery(e, i) ==
    LET message == channel[e][i]
        cell == To[e]
        remaining == [edge \in Edges |->
            IF edge = e THEN RemoveAt(channel[edge], i) ELSE channel[edge]]
    IN
    /\ message.kind \in {"delta", "progress"}
    /\ IF message.t <= floor[e][message.src] /\ ~FloorIgnored
       THEN
            \* Pre-join data and progress do not enter any frontier state.
            /\ channel' = remaining
            /\ UNCHANGED
                <<counter, absorbed, acked, live, linked, cutSent, frontierVars>>
       ELSE
            LET oldWatermark == watermark[e][message.src]
                nextWatermark ==
                    IF WatermarkByMax
                    THEN IF oldWatermark >= message.t
                         THEN oldWatermark
                         ELSE message.t
                    ELSE message.t
                watermarks ==
                    [watermark EXCEPT ![e][message.src] = nextWatermark]
                late ==
                    message.kind = "delta"
                    /\ message.t <= flushedHigh[cell][message.src]
                buffered ==
                    IF message.kind = "delta" /\ ~late
                    THEN pending[cell] \cup {<<message.src, message.t, e>>}
                    ELSE pending[cell]
                ready == ReadyWaves(watermarks, open, floor, cell, buffered)
                releasedNow ==
                    ready
                        \cup IF late
                             THEN {<<message.src, message.t>>}
                             ELSE {}
                ReadyCounters(source) ==
                    {t \in 1..WaveBound : <<source, t>> \in ready}
                LateCounters(source) ==
                    IF late /\ source = message.src
                    THEN <<message.t>>
                    ELSE <<>>
                releasedAtCell ==
                    [source \in Sources |->
                        released[cell][source]
                            \o LateCounters(source)
                            \o SortedCounters(ReadyCounters(source))]
                AppliedCandidates(edge, source) ==
                    {t \in ReadyCounters(source) :
                        <<source, t, edge>> \in buffered}
                        \cup
                    IF late /\ edge = e /\ source = message.src
                    THEN {message.t}
                    ELSE {}
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
                    {entry \in buffered :
                        <<entry[1], entry[2]>> \notin ready}
                emitted ==
                    (IF late THEN <<Delta(message.src, message.t)>> ELSE <<>>)
                        \o ReadyMessages(ready)
                witnesses ==
                    ReleaseWitnesses(releasedNow, cell, open, floor, watermarks)
            IN
            /\ watermark' = watermarks
            /\ pending' = [pending EXCEPT ![cell] = stillPending]
            /\ released' = [released EXCEPT ![cell] = releasedAtCell]
            /\ applied' = [applied EXCEPT ![cell] = appliedAtCell]
            /\ flushedHigh' = [flushedHigh EXCEPT ![cell] = highAtCell]
            /\ releaseWitness' =
                [releaseWitness EXCEPT ![cell] = @ \cup witnesses]
            /\ channel' = [edge \in Edges |->
                IF edge \in OutEdges(cell) \cap live
                THEN remaining[edge] \o emitted
                ELSE remaining[edge]]
            /\ UNCHANGED
                <<counter, absorbed, acked, live, linked, cutSent,
                  floor, open, suspended,
                  cutDone, closedWaiting>>

FrontierMarkerDelivery(e, i) ==
    LET message == channel[e][i]
        cell == To[e]
        remaining == [edge \in Edges |->
            IF edge = e THEN RemoveAt(channel[edge], i) ELSE channel[edge]]
        isOpen == message.kind = "edgeOpen"
        isClose == message.kind = "edgeClose"
        nextOpen ==
            IF isOpen
            THEN [open EXCEPT ![e] = TRUE]
            ELSE IF CloseIgnored THEN open ELSE [open EXCEPT ![e] = FALSE]
        nextFloor ==
            IF isOpen
            THEN [floor EXCEPT ![e] = flushedHigh[cell]]
            ELSE floor
        waiting ==
            {wave \in PendingWaves(pending[cell]) :
                /\ e \in Expects(cell, wave[1])
                /\ open[e]
                /\ floor[e][wave[1]] < wave[2]
                /\ watermark[e][wave[1]] < wave[2]}
        ready == ReadyWaves(watermark, nextOpen, nextFloor, cell, pending[cell])
        ReadyCounters(source) ==
            {t \in 1..WaveBound : <<source, t>> \in ready}
        releasedAtCell ==
            [source \in Sources |->
                released[cell][source]
                    \o SortedCounters(ReadyCounters(source))]
        AppliedCandidates(edge, source) ==
            {t \in ReadyCounters(source) :
                <<source, t, edge>> \in pending[cell]}
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
            {entry \in pending[cell] : <<entry[1], entry[2]>> \notin ready}
        witnesses ==
            ReleaseWitnesses(ready, cell, nextOpen, nextFloor, watermark)
    IN
    /\ isOpen \/ isClose
    /\ open' = nextOpen
    /\ floor' = nextFloor
    /\ pending' = [pending EXCEPT ![cell] = stillPending]
    /\ released' = [released EXCEPT ![cell] = releasedAtCell]
    /\ applied' = [applied EXCEPT ![cell] = appliedAtCell]
    /\ flushedHigh' = [flushedHigh EXCEPT ![cell] = highAtCell]
    /\ releaseWitness' =
        [releaseWitness EXCEPT ![cell] = @ \cup witnesses]
    /\ cutDone' = IF isClose THEN cutDone \cup {e} ELSE cutDone
    /\ closedWaiting' =
        IF isClose
        THEN closedWaiting
            \cup {<<cell, wave[1], wave[2]>> : wave \in waiting}
        ELSE closedWaiting
    /\ channel' = [edge \in Edges |->
        IF edge \in OutEdges(cell) \cap live
        THEN remaining[edge] \o ReadyMessages(ready)
        ELSE remaining[edge]]
    /\ UNCHANGED
        <<counter, absorbed, acked, live, linked, cutSent,
          watermark, suspended>>

NonFrontierMarkerDelivery(e, i) ==
    LET message == channel[e][i]
        remaining == [edge \in Edges |->
            IF edge = e THEN RemoveAt(channel[edge], i) ELSE channel[edge]]
    IN
    /\ message.kind \in {"edgeOpen", "edgeClose"}
    \* G-36: metadata-plane edge markers stop at their direct consumer.
    /\ channel' = remaining
    /\ cutDone' =
        IF message.kind = "edgeClose" THEN cutDone \cup {e} ELSE cutDone
    /\ UNCHANGED
        <<counter, absorbed, acked, live, linked, cutSent,
          watermark, floor, open, suspended,
          pending, released, applied, flushedHigh, releaseWitness, closedWaiting>>

FrontierDelivery(e, i) ==
    \/ FrontierDataDelivery(e, i)
    \/ FrontierMarkerDelivery(e, i)

\* The complete Deliver action lives here, rather than an abstract hook in
\* Waves.tla: only this module can distinguish a frontier cell and update its
\* completeness fold atomically with dequeuing the message.
Deliver(e) ==
    /\ Len(channel[e]) > 0
    /\ \E i \in PickPositions(e) :
        IF To[e] \in Frontier
        THEN FrontierDelivery(e, i)
        ELSE \/ /\ DeliverNonFrontier(e, i)
                  /\ UNCHANGED frontierVars
             \/ NonFrontierMarkerDelivery(e, i)

Terminating ==
    /\ \A s \in EmitFrom : counter[s] = WaveBound
    /\ \A e \in Edges : channel[e] = <<>>
    /\ \A c \in Frontier : pending[c] = {}
    /\ UNCHANGED vars

Next ==
    \/ \E s \in Sources : EmitStep(s)
    \/ \E e \in Edges : Deliver(e)
    \/ \E e \in Edges : LinkStep(e)
    \/ \E e \in Edges : CutStep(e)
    \/ Terminating

=============================================================================
