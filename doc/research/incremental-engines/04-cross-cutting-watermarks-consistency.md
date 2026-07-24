# Cross-cutting: watermarks, lateness, and consistency models

Research date: 2026-07-23. Method: research agent fetched and quoted the
primary sources directly (single-agent verification — quotes checked against
fetched text, not adversarially voted like docs 01/02). Archived copies in
`doc/references/raw/`.

Sources:

- [S1] Flink docs, event time & watermarks — `flink-docs-event-time.html`,
  `flink-docs-streaming-analytics.html` (plus the DataStream windows and
  generating-watermarks pages, fetched but not archived)
- [S2] The Dataflow Model (Akidau et al., PVLDB 8(12), 2015)
- [S3] Jamie Brandon, "Internal consistency in streaming systems" (2021) —
  `scattered-thoughts-internal-consistency.html`
- [S4] Materialize blogs: virtual time, strong consistency —
  `materialize-blog-virtual-time.html`, `materialize-blog-strong-consistency.html`

## 1. Flink watermarks: completeness estimation coupled to destructive eviction

- A "Watermark(t) declares that event time has reached time t in that stream"
  — no more elements with timestamp ≤ t should follow. It is an *assertion*,
  not a guarantee: "a watermark for time t is an assertion that the stream is
  (probably) now complete up through time t." [S1]
- Watermarks are generated per source / per parallel subtask (ideally per
  Kafka partition, merged), and **propagate through multi-input operators as
  MIN over inputs**: "the current watermark of the operator is defined as the
  minimum of both of its inputs." [S1]
- **Idle-source problem**: one silent partition stalls the min and holds back
  event time for the whole pipeline; `withIdleness(Duration)` excludes an idle
  input from the min — a heuristic escape hatch that trades away the
  completeness assertion. [S1]
- **Allowed lateness and state eviction**: by default late elements "will be
  dropped"; with allowed lateness they re-fire the window ("late firings").
  "Flink keeps the state of windows until their allowed lateness expires. Once
  this happens, Flink removes the window and deletes its state." Later
  arrivals go to a side output or are dropped. [S1, windows doc]
- `GlobalWindows`: "no data is ever considered late because the end timestamp
  of the global window is Long.MAX_VALUE" — a never-evicting window means
  unbounded state. **This is ComputeNet's current default regime** (windows
  never close). [S1]

**Takeaway**: Flink's GC is watermark-driven and *destructive* — correctness
for a window is frozen at eviction time. ComputeNet borrowing eviction must
decide the same question: what happens to a tagged add arriving after its
window's state is discarded (drop / side-output / degrade-to-full-recompute).

## 2. The Dataflow Model: decouple windowing, triggering, accumulation

- Watermarks are "a lower bound (often heuristically established) on event
  times that have been processed"; "for most real-world distributed data sets,
  the system lacks sufficient knowledge to establish a 100% correct
  watermark." The paper itself positions watermark-based GC as approximate:
  useful for "decisions around progress that do not require complete accuracy,
  such as basic garbage collection policies." [S2 §1.3]
- Watermarks are "sometimes too fast" (late data exists) and "sometimes too
  slow" (one slow datum holds back the pipeline); "watermarks alone are
  insufficient" — hence **triggers**: windowing determines *where in event
  time* data group; triggering determines *when in processing time* results
  emit, allowing multiple panes per window. [S2 §2.3]
- **Three accumulation modes** [S2 §2.3]:
  1. **Discarding** — pane contents dropped after firing; later results bear
     no relation to previous ones (downstream must sum deltas).
  2. **Accumulating** — state kept; later results refine previous ones
     (overwrite sinks; "effectively the mode used in Lambda Architecture").
  3. **Accumulating & retracting** — on re-fire, "a retraction for the
     previous value will be emitted first, followed by the new value."
     Stated necessity: multiple serial GroupByKeyAndWindow stages — refires
     may land on different downstream keys, and without retraction the second
     grouping "will generate incorrect results."
- Not in the paper: "allowed lateness" as API concept (Beam/Flink-era term).

**Takeaway**: the retraction argument is load-bearing for ComputeNet: any
multi-stage grouped pipeline that re-fires needs first-class retraction.
ComputeNet already has it (tagged removals; `KeyedSetCell`'s atomic
retract+add is exactly "retraction followed by new value"), which is rare —
most stream processors bolt it on. The borrowable idea is the *decoupling*:
ComputeNet windows conflate where/when/how; a trigger-like knob on
glitch-free/observation cells (emit per wave vs emit on frontier) would
separate them cleanly.

## 3. Internal consistency (Jamie Brandon, 2021)

- Definition: "A system is internally consistent if every output is the
  correct output for some subset of the inputs provided so far." [S3]
- Eventual consistency is inadequate for streaming: it "allows the system to
  produce nonsense outputs as long as inputs are arriving," and with
  continuous input "it never has to converge to a correct output." [S3]
- Failure examples (balanced $1-transfer experiment where total must be 0):
  unsynchronized credit/debit joins "effectively creating money"; aggregates
  emitting totals that never existed; outer self-joins emitting
  later-retracted nulls. [S3]
- Verdicts (versions as tested, 2021 — cite as snapshots): PASS — Differential
  Dataflow 0.12.0, Materialize 0.7.1. FAIL — ksqlDB 0.15, Flink Table/SQL
  1.12.2, Kafka Streams 2.7.0. Flink DataStream can be made consistent by
  hand ("flink datastream api ~ timely dataflow; flink table api ~
  differential dataflow"). [S3]
- Mechanism for passing: watermarks/progress statements at the system edge
  "used to control emission from non-monotonic operators," joins synchronized
  by timestamp. [S3]

**Takeaway**: ComputeNet's glitch-free machinery is precisely an
internal-consistency device, but scoped to one diamond. The essay's balanced-
transfer suite is directly reusable as a ComputeNet acceptance benchmark for
gap 4 (consistent multi-view snapshot): total-invariant streams through
join/aggregate/outer-join cells, asserting no observed output violates the
invariant. Also a crisp vocabulary upgrade: ComputeNet's outer-join-by-
composition being "eventually consistent only" means it is *internally
inconsistent* in exactly the essay's outer-join sense.

## 4. Materialize virtual time: what coordination buys and costs

- Virtual time: events are "timestamped prescriptively rather than
  descriptively"; storage assigns virtual times at ingestion (same transaction
  → same time; input order respected), after which "the explicitly timestamped
  history is now unambiguous on matters of concurrency." [S4]
- Consistency across independently deployed dataflows comes from determinism:
  output histories "align exactly at each virtual time, because that is what
  differential dataflow does"; components "coordinate only indirectly, through
  the availability of virtual times in explicit histories." [S4]
- Query path: "Each query... is first assigned a timestamp," balancing
  responsiveness / freshness / consistency ("always choose a timestamp to the
  right of all previously chosen timestamps"). Latency cost is explicit:
  "Each arrowhead necessarily lags the arrowheads of its immediate inputs."
  Real-time recency ("zero staleness") is opt-in. [S4]
- Provenance caveat: these two posts do not use the terms "strict
  serializability" or "timestamp oracle"; the coordinator/CRDB-backed oracle
  lives in Materialize's architecture docs. Directionally supported here, but
  cite the docs for the oracle specifically.

**Takeaway (does NOT transfer)**: the single total-order timestamp assignment
at ingestion is the exact coordination point ComputeNet's replication model
rejects. What *does* transfer is the weaker compositional trick: determinism +
explicit timestamps ⇒ independently computed views align. ComputeNet already
has deterministic delta application and per-source waves; per-source-prefix
alignment ("outputs correspond to some per-source frontier of inputs") is the
coordination-free analog — a vector frontier instead of a scalar virtual time.
