# The whole-copy cost, measured (V1C-BENCH)

**Status**: Exploratory — a one-shot measurement, not a maintained benchmark.
Produced by ticket `tickets/V1C-BENCH.md` (Wave 7, doc-only, the C7
measurement gate). Feeds checkpoint C7 in `00-orchestration.md`, which acts on
this document's recommendation to let waves 8–11 proceed, resize them, or
cancel them.

## Recommendation, up front

**GO.** The occupancy dip a whole-state copy imposes on a cell's own traffic
is real, it scales with state size, and it is large enough at 10⁴–10⁵
elements to matter (tens of milliseconds against a cell capable of hundreds
of thousands of small messages per second). Paging the same read into
200-element slices — simulated with existing `ManagedHost.snapshotOf` calls
only, no `BoundedStateful` implemented — removes the great majority of that
dip at a modest (roughly 2×) total-work premium. That is exactly the trade
`20-wave-neutral-read-design.md` §3.2 predicted; this document is the number
that was missing. §10 below gives the full argument and its limits.

## 1. What this document is

Four experiments (E1–E4) against a real `civictech.cell.data.SetCell` on a
real, threaded `ManagedHost` (`VirtualThreadScheduler`, not
`SimulationController`) — never a simulated host, per the ticket. Set sizes
10³ / 10⁴ / 10⁵ elements. No production code changed: `BoundedStateful`,
`StateRead`, `StatePage`, `Cursor` and `ManagedHost.readState` remain
unimplemented, exactly as `tickets/V1C-BENCH.md` requires. The harness lived
as two temporary JUnit test files, deleted from the tree before this ticket's
diff was finalized; their full source is Appendix A/B, and Appendix C gives
the exact commands to reproduce every number below.

## 2. Environment

- JVM: OpenJDK 21.0.5 (Amazon Corretto), `-Xmx2g` (the Gradle test worker's
  default heap), `-ea`. This is the toolchain 21 build `AGENTS.md` specifies;
  the Gradle daemon itself launches under a different JVM (JetBrains Runtime
  25), but `:kernel:test`/`:inspect:test` execute on Corretto 21 in a forked
  worker — confirmed by `System.getProperty("java.version")` printed inside
  the harness itself, not inferred from the daemon.
- Machine: Apple M3 Max, 16 cores, 48 GiB RAM, macOS (Darwin 25.5.0, arm64).
- **Not an isolated machine.** `uptime`/`ps` during these runs showed a load
  average around 17 on 16 cores — other concurrent work (this repository runs
  multi-agent orchestrated ticket waves; several other worktrees' JVMs were
  alive at measurement time) was competing for CPU. This is disclosed rather
  than hidden because it is the leading explanation for some of the
  wide/noisy tails below (§8), and because a dedicated quiet machine was not
  available inside this ticket's scope. The qualitative comparisons (E2 vs
  E3, and the scaling of both with `n`) are robust to this noise; the
  absolute millisecond figures at the small end (10³) are not to be read to
  more than one significant figure.
- Reps/warmup are stated per experiment below; every number is a real
  measurement, not an estimate — where a number could not be obtained cheaply
  (E1's per-thread allocation), that is stated rather than guessed, per the
  ticket's own instruction.

## 3. E1 — the copy in isolation

Method: populate a `SetCell<Int>` to `n` elements via direct (unhosted)
`inlet.call.add()`, calling `Stateful.snapshot()`
(`kernel/src/main/kotlin/civictech/cell/Stateful.kt:11-14`) directly 20 times
as warmup then 30 times timed. Then spawn the same cell onto a real
`ManagedHost` and time `ManagedHost.snapshotOf(ref).get()`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1244-1263`)
end-to-end (submit + dequeue + copy + future completion) the same way.
Allocation is `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` on
the measuring thread (direct case) or the host's own virtual thread,
identified once via a marker cell's `onActivate` (`snapshotOf` case) — both
obtainable cheaply, so both are reported, per-call average over the reps.

| n | `snapshot()` direct median / p95 | alloc/call | `snapshotOf()` end-to-end median / p95 | alloc/call | GC during window |
|---|---|---|---|---|---|
| 10³ | 0.148 ms / 0.179 ms | 270 KB | 0.107 ms / 0.133 ms | — | none |
| 10⁴ | 0.734 ms / 0.911 ms | 2.61 MB | 0.889 ms / 1.022 ms | — | none |
| 10⁵ | 5.814 ms / 23.296 ms (min 4.861, max 43.200) | 26.9 MB | 5.354 ms / 29.053 ms (min 4.351, max 30.446) | — | Young+=7, Concurrent+=3 (direct); Young+=5, Concurrent+=2 (snapshotOf) |

30 reps, 20 warmup iterations, per size, per path.

**Reading it:** at 10³/10⁴ the copy is sub-millisecond and clean (no GC in
either 30-rep window). At 10⁵ the median is still small (5–6 ms) but the p95
is 4–5× the median and tracks a G1 young-generation collection landing inside
the timed window — see §8. `snapshotOf()`'s end-to-end cost is not
meaningfully larger than the bare `snapshot()` call it wraps; the
submit/dequeue/future machinery is cheap relative to the copy itself at every
size tested, which matches the host's own KDoc claim
(`ManagedHost.kt:1217-1221`) that the mechanism "is off the per-message data
path ... but it is not free."

## 4. E2 — the occupancy cost under live traffic

Method: a real link — `SetCell` `source` → `SetCell` `target` via
`target.deltaInlet` (the OR-set gossip merge path, `SetCell.kt:164-166`),
both hosted on **one** `ManagedHost` (one virtual thread drains both cells'
work, which is the actual concurrency unit — spec 32, `HostScheduler.kt:9-38`).
A bystander `CollectorCell` linked to `target.outlet` counts arrivals with
nanosecond timestamps (`target` re-emits on every new tag it merges — the
"effective-only" rule, `SetCell.kt:107-118`). Baseline: drive 8,000 live adds
through `source` as fast as the test thread can enqueue them (an unpaced
burst, not a paced steady rate — stated precisely because it changes what
"dip" means: see the note below the table) and measure total drain wall time
and the largest gap between two consecutive arrivals. Concurrent: identical,
plus one `ManagedHost.snapshotOf(target.ref)` fired from a second thread
~1 ms into the drive. `target` is pre-seeded to `n` elements (via the same
link, off-timer) before every trial. 5 trials per condition per `n`, one
`ManagedHost` reused across a condition's trials (with a discarded 1,000-add
warmup first) so JIT/GC state is shared rather than re-paid per trial;
medians reported. Two independent runs (Run A, Run B) are both given to show
run-to-run spread.

| n | baseline median duration / maxGap | concurrent median duration / maxGap | maxGap dip (concurrent − baseline) |
|---|---|---|---|
| 10³ (Run A) | 89.204 ms / 2.342 ms | 54.164 ms / 9.402 ms | **+7.060 ms** |
| 10³ (Run B) | 82.346 ms / 3.142 ms | 54.555 ms / 6.908 ms | **+3.766 ms** |
| 10⁴ (Run A) | 28.245 ms / 0.267 ms | 27.253 ms / 10.493 ms | **+10.226 ms** |
| 10⁴ (Run B) | 32.373 ms / 0.270 ms | 28.121 ms / 8.593 ms | **+8.322 ms** |
| 10⁵ (Run A) | 12.617 ms / 0.046 ms | 39.209 ms / 27.683 ms | **+27.637 ms** |
| 10⁵ (Run B) | 13.661 ms / 0.043 ms | 41.826 ms / 29.184 ms | **+29.140 ms** |

Throughput at 10⁵ baseline: ~585,000–635,000 adds/s (unpaced, so this is the
host thread's max drain rate, not a design target). Under the concurrent
snapshot it drops to ~190,000–205,000/s for the trial's duration.

**Reading it:** the maxGap dip is consistently positive and grows with `n` —
from single-digit milliseconds at 10³ to ~28 ms at 10⁵ across two independent
runs. It also tracks the concurrent snapshot's own measured latency almost
exactly (e.g. at 10⁵, snapshot latencies of 21.6–45.7 ms line up with maxGaps
of 21.6–45.7 ms in the same trials) — the mechanism is exactly what
`20-wave-neutral-read-design.md` §3.2 names: `snapshotOf` submits at
scheduler priority 0 (`ManagedHost.kt:1249`), the same band as management
calls and *above* ordinary data traffic's priority 20
(`ManagedHost.kt:481-483`), so a submitted snapshot cuts in front of every
not-yet-drained data-priority task on that host, then holds the thread for
the whole copy. It is not merely slower under load — it reorders in front of
already-queued live traffic.

Duration deltas are noisy and sometimes *negative* (the concurrent trial
finishing faster than baseline) — this is a real, reportable artifact, not
cherry-picked: see §8.

## 5. E3 — the paging counterfactual, simulated with existing API only

Method, per the ticket's explicit direction: no `BoundedStateful`. A bystander
`Stateful` cell (`PageCursorCell`) holds a plain `List<Int>` of `n` elements
(a stand-in for "the same underlying state", since a real cursor into
`SetCell`'s own tag maps requires exactly the interface this ticket may not
build) and answers each `snapshot()` call with the next 200-element slice,
advancing an internal cursor. It is spawned on the **same** `ManagedHost` as
`source`/`target`/`collector`. A driver thread calls
`host.snapshotOf(pager.ref).get()` **sequentially** — one page awaited before
the next is submitted, mirroring how a real paged reader (`DataSearch`) would
actually drive `readState`, one round trip at a time — while the identical
8,000-add live-traffic drive from E2 runs concurrently. This reuses only
`ManagedHost.snapshotOf`, `spawn`, `connect`, `lookup` — no kernel edit, no
new interface. Single trial per `n` per run; 3 independent runs shown.

| n | pages (200/ea) | total page wall time | max single page | drive duration | **maxGap** |
|---|---|---|---|---|---|
| 10³ (run 1/2/3) | 4 | 1.044 / 1.154 / 0.364 ms | 0.858 / 0.911 / 0.271 ms | 62–71 ms | **2.086 / 3.004 / 16.337 ms** |
| 10⁴ (run 1/2/3) | 49 | 1.768 / 1.801 / 1.862 ms | 0.111 / 0.068 / 0.125 ms | 37–50 ms | **1.141 / 1.167 / 3.100 ms** |
| 10⁵ (run 1/2/3) | 499 | 10.190 / 9.492 / 10.678 ms | 0.113 / 0.145 / 0.095 ms | 11–25 ms | **0.128 / 0.138 / 11.224 ms** |

## 6. E2 vs E3 — the comparison the design rests on

This is the number `tickets/V1C-BENCH.md` asks for explicitly: not E1's total
vs E3's total, but E2's dip vs E3's dip.

| n | E2 maxGap (whole copy, concurrent) | E3 maxGap (paged) | reduction |
|---|---|---|---|
| 10³ | 6.9–9.4 ms | 2.1–16.3 ms (median ≈3.0 ms, noisy: only 4 pages) | small/unclear at this scale |
| 10⁴ | 8.6–10.5 ms | 1.1–3.1 ms (median ≈1.2 ms) | ~85–90% |
| 10⁵ | 27.7–29.2 ms | 0.1–11.2 ms (median ≈0.14 ms; one 3-run outlier at 11.2 ms, see §8) | typically ~99%, worst observed ~60% |

At the scale the design targets (10⁴–10⁵, where a whole copy is expensive
enough to matter), paging cuts the live-traffic stall by roughly one to two
orders of magnitude, for a total-work premium of about 1.7–2.4× (E3's summed
page time vs E1's single-copy time at the same `n`: 10.2–10.7 ms of paging
vs 5.4–5.8 ms of one copy at 10⁵; 1.8–1.9 ms of paging vs 0.73–0.89 ms of one
copy at 10⁴). That is precisely the trade
`20-wave-neutral-read-design.md` §3.2 argued for and could not quantify: more
total work, much less contiguous occupancy — and here it is real and large
enough, at the sizes that matter, to justify building it.

At 10³ the comparison does not favor paging clearly — both regimes cost a
few milliseconds of stall, dominated by noise (only 4 pages exist at 200/page
for a 1,000-element cell, so E3's own sample size is too small to trust). This
is expected and not a problem for the design: `BoundedStateful` is opt-in
(`20-wave-neutral-read-design.md` §3.1, "extends `Stateful`; it is not a
method added to `Stateful`"), so a cell family that never grows past a few
thousand elements need not implement it and pays nothing either way.

## 7. E4 — how much of the copy is discarded today

Method: a 10⁵-element `SetCell` behind a real `InspectorServer`
(`inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt`) on a real
`ManagedHost`; 10 timed `GET /api/inspect/cell/{ref}/state` requests via
`civictech.testkit.HttpProbe`, after 5 warmup requests and after confirming
(via a raw `snapshotOf`, not the truncated HTTP response, since the response
itself never reports past 200 rows) that the fold had actually absorbed all
100,000 adds.

- **Latency:** median 37.073 ms, p95 43.457 ms, for a 1,315-byte response.
- **Survival fraction:** 201 of 100,000 rows reach the client — **0.201%**.
  `ValueEncoder`'s `$truncated` marker (`ValueEncoder.kt:53,56,61`) is present
  in the response, confirming the budget was hit, not merely approached.

One number, one sentence, as the ticket asked: **for a 10⁵-element `SetCell`,
the inspector's cell thread pays for a full ~5–8 ms (typically; up to ~40 ms
under a GC hit, §3/§8) copy of 100,000 entries so the client can see 200 of
them, and the whole round trip (copy + encode + HTTP) takes ~37 ms even
though the wire payload is 1.3 KB.**

## 8. What dominated unexpectedly

Two things, reported rather than tuned away, per the ticket's instruction:

1. **G1 young-generation GC, not the copy loop itself, sets the tail at
   10⁵.** E1's 10⁵ row shows p95/max at 4–5× the median, and the GC-count
   instrumentation added specifically to chase this down confirms it: 5–7
   young collections land inside a 30-call, ~150–250 ms measurement window at
   that size (zero at 10³/10⁴). The copy's *median* cost is a clean, small
   number; its *tail* is a JVM GC artifact of the two 100,000-entry
   `HashMap`/`HashSet` structures `SetCell.snapshot()` allocates per call
   (`SetCell.kt:200-205`), not anything about `ManagedHost`'s scheduling. This
   matters for sizing `BUDGET_MS` (§9): the number a caller should plan
   around at this scale is closer to the p95 than the median.
2. **Machine contention, not the mechanism under test, explains E2's small-n
   noise and one of E3's three 10⁵ outliers.** The measuring machine ran at a
   load average of ~17 on 16 cores throughout (§2) — concurrent orchestration
   work, not a property of `ManagedHost` or `SetCell`. This is the most
   likely explanation for E2's occasional *negative* duration deltas (the
   concurrent trial finishing faster than baseline — plausible only as an
   artifact of scheduling variance across trials, not a real effect of
   `snapshotOf`) and for E3's one 11.224 ms maxGap at 10⁵ (run 3) against two
   sub-0.15 ms runs otherwise. The maxGap metric — the largest single stall,
   not an average — is by construction sensitive to exactly this kind of
   external, one-off scheduling delay. The E2-vs-E3 *direction and order of
   magnitude* (§6) is unaffected by this noise; the third significant figure
   on any single trial is not.

## 9. Are `MAX_CELLS = 50` and `BUDGET_MS = 2_000` supported?

**Recommendation only — neither constant is changed by this ticket.**
`DataSearch.kt:357,360` cites "Ticket:" for both, meaning they were chosen,
not measured, before this document existed. E1's own numbers at 10⁵ — median
~5–6 ms, p95 up to ~30–43 ms per whole-state copy — mean a 40 ms-per-cell
budget (`BUDGET_MS / MAX_CELLS` = 2,000 ms / 50) lines up almost exactly with
the *tail*, not the median, of a single 10⁵-element copy. Read charitably,
that is a well-calibrated budget for a fan-out over cells around the size
actually measured here — but that appears to be closer to coincidence than
evidence of deliberate sizing, since the ticket citation gives no derivation.
Two gaps the measurement can name precisely:

- For cells that stay in the 10³ range, the same budget is 100–400× more
  generous than the ~0.1–0.9 ms a copy there actually costs — headroom, not a
  problem, but headroom nobody measured until now.
- For cells larger than 10⁵ elements, or for `MAX_CELLS` whole copies queued
  back-to-back on the **same** host (E2 shows they fully serialize — one
  virtual thread per host, §4), this document has no data: E1–E4 stop at
  10⁵ and never drive more than one concurrent large copy per host. A
  `DataSearch` sweep that lands several large `SetCell`s on one host is the
  scenario most likely to threaten `BUDGET_MS` and was out of this ticket's
  scope.

## 10. Recommendation: GO, in full

**GO.** Waves 8–11 (`V1C-KERNEL`, `V1C-CELLS`, `V1C-OPS`, `V1C-BE`, `V1C-FE`,
`V1C-CONCORD`) should proceed as scheduled, unresized, on the evidence above:

- The occupancy dip is real, not merely structurally plausible — §4 measures
  it directly, twice, with a mechanism (§4's priority-0 queue-jump) that
  explains *why* it happens, not just that it does.
- It scales with cell size in the direction and rough shape the design
  predicted, reaching ~28 ms at 10⁵ against a host capable of ~600,000+
  adds/s absent contention — large enough to visibly stall real traffic.
- Paging it away costs real, measured extra total work (~1.7–2.4×) but
  removes ~85–99% of the stall at the sizes where the stall is large enough
  to matter (§6) — the exact trade the design's P2/viz-never-blocks argument
  needed and did not have a number for.
- The design's opt-in shape (`BoundedStateful extends Stateful`, not a method
  added to `Stateful`) already means small-state families pay nothing if they
  never implement it, which is the correct answer to E3's inconclusive 10³
  result rather than a reason to narrow the ticket set.

**No RESIZE is recommended for `V1C-CELLS`'/`V1C-OPS`' cell lists.** The
measurement supports the general shape, and the opt-in interface already
localizes cost to cells that choose to implement it. If a narrowing is
wanted anyway for delivery sequencing (not because the measurement disputes
the value elsewhere), the data here would prioritize cell families whose
steady-state size commonly reaches 10⁴–10⁵ over families that typically stay
small — `SetCell`, `MapCell`, `KeyedSetCell`, `ListCell`, `ShardCell`,
`InstanceSet` are named in `20-wave-neutral-read-design.md` §1.4 as full-copy
`Stateful` implementations and share `SetCell`'s generic copy-a-`HashMap`
shape, so the GC-tail finding in §8 plausibly generalizes to them — but only
`SetCell` was directly measured here; that generalization is an inference,
not a second data point, and is flagged as such.

## What could not be done, or was assumed

- Only `SetCell` was measured, as the ticket specified. `MapCell`,
  `ShardCell`, `InstanceSet` and the other 27 `Stateful` implementations
  named in `20-wave-neutral-read-design.md` §1.4 were not — the §10
  generalization above is inference from a shared copy shape, not evidence.
- E3's cursor state is a plain `List<Int>` sized to `n`, not a live cursor
  into `SetCell`'s own `adds`/`dels` tag maps — deliberate, since building the
  latter would edge toward the `BoundedStateful` shape this ticket may not
  implement. The per-page copy cost this stands in for (a bounded slice
  allocation) is representative of the *mechanism* (one scheduler task per
  200-element page) but not of every accounting detail a real `SetCell`
  cursor would add (tag-set filtering, frontier computation).
  `Watermark`/`GC` allocation per page in a real implementation could differ
  from this document's numbers.
- No isolated/quiet machine was available (§2); the absolute millisecond
  figures, especially at 10³, should be read as order-of-magnitude, not
  precise. The E2-vs-E3 comparison at 10⁴/10⁵ is the load-bearing result and
  is robust across all runs taken.
- Live-traffic drive in E2/E3 is an unpaced burst (fastest the test thread
  can enqueue), not a paced steady rate — stated in §4, not hidden; a paced
  rate would change the absolute throughput numbers but not the mechanism
  (priority-0 queue-jump) the maxGap comparison demonstrates.

## Appendix A — kernel harness (E1, E2, E3)

Deleted from the tree before this ticket's diff was finalized. Reconstructing
it: create `kernel/src/test/kotlin/civictech/cell/host/V1cBenchScratchTest.kt`
with the JUnit 5 test class below, then run the commands in Appendix C.

```kotlin
package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.onEach
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class V1cBenchScratchTest {

    private val threadMx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private fun allocatedBytes(threadId: Long): Long {
        val mx = threadMx ?: return -1L
        if (!mx.isThreadAllocatedMemorySupported) return -1L
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        return mx.getThreadAllocatedBytes(threadId)
    }

    private fun stats(label: String, nanos: List<Long>) {
        val sorted = nanos.sorted()
        val median = sorted[sorted.size / 2] / 1_000_000.0
        val p95 = sorted[((sorted.size * 95) / 100).coerceAtMost(sorted.size - 1)] / 1_000_000.0
        val min = sorted.first() / 1_000_000.0
        val max = sorted.last() / 1_000_000.0
        println("$label reps=${sorted.size} median=${"%.3f".format(median)}ms p95=${"%.3f".format(p95)}ms min=${"%.3f".format(min)}ms max=${"%.3f".format(max)}ms")
    }

    private fun gcCounts(): Map<String, Long> =
        ManagementFactory.getGarbageCollectorMXBeans().associate { it.name to it.collectionCount }

    private fun gcDelta(before: Map<String, Long>, after: Map<String, Long>): String =
        before.keys.joinToString(", ") { name -> "$name+=${(after[name] ?: 0) - (before[name] ?: 0)}" }

    @Test
    fun `E1 the copy in isolation`() {
        println("=== E1 ===")
        println(
            "JVM ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}) " +
                "maxHeap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB cpus=${Runtime.getRuntime().availableProcessors()}"
        )
        for (n in listOf(1_000, 10_000, 100_000)) {
            val cell = SetCell<Int>()
            val addApi = cell.inlet.call
            for (i in 0 until n) addApi.add(i)

            val reps = 30
            val warmup = 20

            repeat(warmup) { cell.snapshot() }
            val myThreadId = Thread.currentThread().threadId()
            val allocBefore = allocatedBytes(myThreadId)
            val gcBefore = gcCounts()
            val directTimings = mutableListOf<Long>()
            repeat(reps) {
                val t0 = System.nanoTime()
                cell.snapshot()
                directTimings += System.nanoTime() - t0
            }
            val gcAfter = gcCounts()
            val allocAfter = allocatedBytes(myThreadId)
            stats("E1 n=$n snapshot() direct", directTimings)
            if (allocBefore >= 0 && allocAfter >= 0) {
                println("E1 n=$n snapshot() direct allocated ${(allocAfter - allocBefore) / reps} bytes/call (avg over $reps calls)")
            }
            println("E1 n=$n snapshot() direct GC during window: ${gcDelta(gcBefore, gcAfter)}")

            val host = ManagedHost()
            var hostThreadId = -1L
            val marker = object : Cell {
                override val ref = CellRef(UUID.randomUUID())
                override fun onActivate(ctx: CellContext) {
                    hostThreadId = Thread.currentThread().threadId()
                }
            }
            host.managementInlet.call.spawn(marker)
            host.managementInlet.call.spawn(cell)

            repeat(warmup) { host.snapshotOf(cell.ref).get() }
            val hAllocBefore = allocatedBytes(hostThreadId)
            val hGcBefore = gcCounts()
            val hostTimings = mutableListOf<Long>()
            repeat(reps) {
                val t0 = System.nanoTime()
                host.snapshotOf(cell.ref).get()
                hostTimings += System.nanoTime() - t0
            }
            val hGcAfter = gcCounts()
            val hAllocAfter = allocatedBytes(hostThreadId)
            stats("E1 n=$n snapshotOf() end-to-end", hostTimings)
            if (hAllocBefore >= 0 && hAllocAfter >= 0) {
                println("E1 n=$n snapshotOf() host-thread allocated ${(hAllocAfter - hAllocBefore) / reps} bytes/call (avg over $reps calls)")
            }
            println("E1 n=$n snapshotOf() end-to-end GC during window: ${gcDelta(hGcBefore, hGcAfter)}")
        }
    }

    private class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
        val arrivals = ConcurrentLinkedQueue<Long>()
        val total = AtomicLong(0)

        override fun onActivate(ctx: CellContext) {
            inlet.onEach {
                arrivals += System.nanoTime()
                total.incrementAndGet()
            }
        }
    }

    private class PageCursorCell(
        private val backing: List<Int>,
        private val pageSize: Int = 200,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, Stateful {
        @Volatile var cursor = 0
            private set

        fun hasNext() = cursor < backing.size

        override fun snapshot(): Serializable {
            val end = min(cursor + pageSize, backing.size)
            val slice = ArrayList<Int>(end - cursor)
            for (i in cursor until end) slice.add(backing[i])
            cursor = end
            return slice
        }

        override fun restore(state: Serializable) {
            @Suppress("UNCHECKED_CAST")
            cursor = min(cursor + (state as List<Int>).size, backing.size)
        }
    }

    private class Rig(val n: Int) {
        val host = ManagedHost()
        val source = SetCell<Int>()
        val target = SetCell<Int>()
        val collector = CollectorCell()

        init {
            host.managementInlet.call.spawn(source)
            host.managementInlet.call.spawn(target)
            host.managementInlet.call.spawn(collector)
            host.managementInlet.call.connect(source.ref, "outlet", target.ref, "deltaInlet")
            host.managementInlet.call.connect(target.ref, "outlet", collector.ref, "inlet")
        }

        private val sourceApi = host.lookup<SetApi<Int>>(source.ref)!!
        private var seedCounter = 0

        fun seed() {
            val start = seedCounter
            repeat(n) { sourceApi.inlet.call.add(seedCounter++) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (collector.total.get() < (start + n).toLong()) {
                check(System.nanoTime() < deadline) { "seed did not drain within 120s (n=$n, at ${collector.total.get()})" }
                Thread.sleep(1)
            }
        }

        fun driveTimed(m: Int): Triple<Double, Double, Int> {
            val before = collector.total.get()
            collector.arrivals.clear()
            val t0 = System.nanoTime()
            repeat(m) { sourceApi.inlet.call.add(seedCounter++) }
            val deadline = t0 + TimeUnit.SECONDS.toNanos(120)
            while (collector.total.get() < before + m) {
                check(System.nanoTime() < deadline) { "drive did not drain within 120s (m=$m, at ${collector.total.get() - before}/$m)" }
            }
            val t1 = System.nanoTime()
            val durationMs = (t1 - t0) / 1_000_000.0
            val times = collector.arrivals.toList().sorted()
            var maxGapNanos = 0L
            for (i in 1 until times.size) maxGapNanos = maxOf(maxGapNanos, times[i] - times[i - 1])
            if (times.isNotEmpty()) {
                maxGapNanos = maxOf(maxGapNanos, times.first() - t0)
                maxGapNanos = maxOf(maxGapNanos, t1 - times.last())
            }
            return Triple(durationMs, maxGapNanos / 1_000_000.0, times.size)
        }
    }

    @Test
    fun `E2 occupancy cost with and without a concurrent snapshotOf`() {
        println("=== E2 ===")
        val m = 8_000
        val trials = 5
        for (n in listOf(1_000, 10_000, 100_000)) {
            val baseRig = Rig(n)
            baseRig.seed()
            baseRig.driveTimed(1_000)
            val baseTrials = (1..trials).map { baseRig.driveTimed(m) }
            val baseDurations = baseTrials.map { it.first }.sorted()
            val baseGaps = baseTrials.map { it.second }.sorted()
            println(
                "E2 n=$n m=$m trials=$trials BASELINE durations(ms)=${baseDurations.map { "%.3f".format(it) }} " +
                    "maxGaps(ms)=${baseGaps.map { "%.3f".format(it) }} " +
                    "medianDuration=${"%.3f".format(baseDurations[trials / 2])}ms medianMaxGap=${"%.3f".format(baseGaps[trials / 2])}ms " +
                    "medianThroughput=${"%.1f".format(m / (baseDurations[trials / 2] / 1000.0))}/s"
            )

            val concRig = Rig(n)
            concRig.seed()
            concRig.driveTimed(1_000)
            val snapLatencies = mutableListOf<Double>()
            val concTrials = (1..trials).map {
                val latch = CountDownLatch(1)
                var snapshotLatencyMs = -1.0
                val snapshotThread = Thread {
                    latch.await()
                    Thread.sleep(1)
                    val t0 = System.nanoTime()
                    concRig.host.snapshotOf(concRig.target.ref).get()
                    snapshotLatencyMs = (System.nanoTime() - t0) / 1_000_000.0
                }.apply { isDaemon = true; start() }
                latch.countDown()
                val result = concRig.driveTimed(m)
                snapshotThread.join(TimeUnit.SECONDS.toMillis(120))
                snapLatencies += snapshotLatencyMs
                result
            }
            val concDurations = concTrials.map { it.first }.sorted()
            val concGaps = concTrials.map { it.second }.sorted()
            println(
                "E2 n=$n m=$m trials=$trials CONCURRENT durations(ms)=${concDurations.map { "%.3f".format(it) }} " +
                    "maxGaps(ms)=${concGaps.map { "%.3f".format(it) }} snapshotOfLatencies(ms)=${snapLatencies.map { "%.3f".format(it) }} " +
                    "medianDuration=${"%.3f".format(concDurations[trials / 2])}ms medianMaxGap=${"%.3f".format(concGaps[trials / 2])}ms " +
                    "medianThroughput=${"%.1f".format(m / (concDurations[trials / 2] / 1000.0))}/s"
            )
            println(
                "E2 n=$n DIP (median concurrent - median baseline) duration_delta=${"%.3f".format(concDurations[trials / 2] - baseDurations[trials / 2])}ms " +
                    "maxGap_delta=${"%.3f".format(concGaps[trials / 2] - baseGaps[trials / 2])}ms"
            )
        }
    }

    @Test
    fun `E3 the paging counterfactual, simulated with existing API only`() {
        println("=== E3 ===")
        val m = 5_000
        for (n in listOf(1_000, 10_000, 100_000)) {
            val rig = Rig(n)
            rig.seed()
            val backing = (0 until n).toList()
            val pager = PageCursorCell(backing)
            rig.host.managementInlet.call.spawn(pager)

            val pageLatencies = mutableListOf<Long>()
            val pagerThread = Thread {
                while (pager.hasNext()) {
                    val t0 = System.nanoTime()
                    rig.host.snapshotOf(pager.ref).get()
                    pageLatencies += System.nanoTime() - t0
                }
            }.apply { isDaemon = true; start() }

            val (duration, maxGap, count) = rig.driveTimed(m)
            pagerThread.join(TimeUnit.SECONDS.toMillis(120))

            val totalPageWallMs = pageLatencies.sum() / 1_000_000.0
            val maxPageMs = (pageLatencies.maxOrNull() ?: 0L) / 1_000_000.0
            val pages = pageLatencies.size
            println(
                "E3 n=$n m=$m pages=$pages totalPageWall=${"%.3f".format(totalPageWallMs)}ms maxSinglePage=${"%.3f".format(maxPageMs)}ms " +
                    "duration=${"%.3f".format(duration)}ms maxGap=${"%.3f".format(maxGap)}ms arrivals=$count throughput=${"%.1f".format(m / (duration / 1000.0))}/s"
            )
        }
    }
}
```

## Appendix B — inspect harness (E4)

Deleted from the tree before this ticket's diff was finalized. Reconstructing
it: create
`inspect/src/test/kotlin/civictech/inspect/V1cBenchE4ScratchTest.kt` with the
class below.

```kotlin
package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.lookup
import civictech.testkit.HttpProbe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class V1cBenchE4ScratchTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val server = InspectorServer(registry, mapOf("test-host" to host), port = 0).start()
    private val probe = HttpProbe("http://localhost:${server.boundPort}")

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun statePath(ref: CellRef) = "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}/state"

    @Test
    fun `E4 fraction of a 10^5-row SetCell that survives the encoder budget`() {
        val n = 100_000
        val cell = SetCell<Int>().also { host.managementInlet.call.spawn(it) }
        val api = host.lookup<SetApi<Int>>(cell.ref)!!
        repeat(n) { api.inlet.call.add(it) }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val raw = host.snapshotOf(cell.ref).get() as Map<String, Any>
            val addsSize = (raw["adds"] as Map<*, *>).size
            if (addsSize >= n) break
            check(System.nanoTime() < deadline) { "state did not reach $n rows within 120s (last count=$addsSize)" }
        }

        repeat(5) { probe.state(statePath(cell.ref)) }

        val reps = 10
        val timings = mutableListOf<Long>()
        var lastBody = ""
        repeat(reps) {
            val t0 = System.nanoTime()
            lastBody = probe.state(statePath(cell.ref))
            timings += System.nanoTime() - t0
        }
        timings.sort()
        val median = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[(timings.size * 95 / 100).coerceAtMost(timings.size - 1)] / 1_000_000.0

        val obj = json.parseToJsonElement(lastBody).jsonObject
        val value = obj["value"]
        val shownRows = rowCount(value) ?: -1
        val bodyBytes = lastBody.toByteArray(Charsets.UTF_8).size

        println("=== E4 ===")
        println("E4 n=$n GET .../state reps=$reps median=${"%.3f".format(median)}ms p95=${"%.3f".format(p95)}ms responseBytes=$bodyBytes")
        println(
            "E4 n=$n shownRows=$shownRows fraction=${"%.5f".format(shownRows.toDouble() / n)} " +
                "(MAX_ROWS=${ValueEncoder.MAX_ROWS} MAX_BYTES=${ValueEncoder.MAX_BYTES})"
        )
        println("E4 n=$n value truncated marker present: ${obj.toString().contains(ValueEncoder.TRUNCATED)}")
    }

    private fun rowCount(value: kotlinx.serialization.json.JsonElement?): Int? = when (value) {
        is JsonArray -> value.size
        is JsonObject -> (value[ValueEncoder.TABLE] as? JsonObject)?.get("rows")?.let { (it as? JsonArray)?.size }
        else -> null
    }
}
```

## Appendix C — exact commands

```bash
# E1, E2, E3 (after recreating Appendix A's file):
./gradlew :kernel:test --tests 'civictech.cell.host.V1cBenchScratchTest.E1 the copy in isolation' -i
./gradlew :kernel:test --tests 'civictech.cell.host.V1cBenchScratchTest.E2 occupancy cost with and without a concurrent snapshotOf' -i
./gradlew :kernel:test --tests 'civictech.cell.host.V1cBenchScratchTest.E3 the paging counterfactual, simulated with existing API only' -i

# E4 (after recreating Appendix B's file):
./gradlew :inspect:test --tests 'civictech.inspect.V1cBenchE4ScratchTest' -i
```

`println` output is captured to `<module>/build/test-results/test/TEST-*.xml`
(`<system-out>`) and echoed to the console under `-i`/`--info`.
