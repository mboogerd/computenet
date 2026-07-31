package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.link.LinkResult
import civictech.cell.observe.observeAligned
import civictech.cell.observe.observeAll
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.awaitUntil
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.Collections
import java.util.Random
import java.util.UUID

/**
 * **The balanced-transfer internal-consistency acceptance suite** — the named
 * acceptance benchmark of spec 20/22 §The observation frontier
 * (`doc/spec/20-dataflow-semantics/22-consistency.md`, §Acceptance benchmark;
 * 96 §E2.5), transcribing research
 * `04-cross-cutting-watermarks-consistency.md` §3's "$1-transfer" experiment.
 *
 * Internal consistency is the target: *"every output is the correct output for
 * some subset of the inputs provided so far"* (`[22-OBS-01]`, as per-source
 * vector-frontier alignment). This suite is that claim's teeth for the
 * **composed** pipeline — the shape applications actually build — where
 * [civictech.cell.observe.AlignedObserveTest] and
 * [civictech.cell.data.op.FrontierGatedEmissionTest] each certify one mechanism
 * in isolation.
 *
 * ### The generative stream: a transfer is ONE wave
 *
 * [TransferSource] emits, per transfer, a **single** [SetDelta] carrying two
 * entries — the debit `(from, -amount)` and the credit `(to, +amount)` — under
 * one spontaneous emission, hence one wave `(sourceId, counter)`. That is the
 * ticket's first option, and it is chosen deliberately: the invariant below is
 * *"a debit and its credit are never observed split"*, and "not split" can only
 * be a wave-level fact if the pairing is a wave-level fact. Minting the pair as
 * one delta makes the two legs indivisible **by construction at the source**, so
 * every later split this suite could observe is a split introduced by the
 * dataflow — which is exactly what is under test. (A fork whose two arms merely
 * carry the same source `Timestamp` would work too, but it puts the pairing one
 * derivation step downstream of the fact being certified.)
 *
 * ### The graph: one root, three observed pipelines, four named views
 *
 * ```
 *                       source  (one wave = one balanced transfer)
 *                      /      \                    [the two QUEUED ingress edges]
 *          debitFilter        creditFilter
 *          /    |    \        /    |    \
 *  byAccount byTransfer \    /  byTransfer byAccount        (b) grouped aggregate
 *      |         \       pairs      /          |            (a) credit/debit join
 *      |          \    (SemiJoin   /           |
 *      |           \   emitOnFrontier)         |
 *      |            \             /            |
 *      |             outer (CombineLatest,     |            (c) outer self-join
 *      |              emitOnFrontier)          |
 *      \______________ observeAligned ________/
 * ```
 *
 * - **(a) the credit/debit join** — `SemiJoinCell(emitOnFrontier = true)`:
 *   debit legs ⋉ credit legs on transfer id, so a debit row is advertised only
 *   once its matching credit is live. `JoinSetCell` (the ticket's other
 *   candidate) is deliberately **not** used; see §"Why the join is gated" below.
 * - **(b) the grouped aggregate** — two `GroupByCell`s summing `amount` per
 *   account, one over the debit stream and one over the credit stream. The
 *   *split* is load-bearing: a single group-by over the whole leg stream folds
 *   both legs out of one [MapDelta], so its own view is trivially balanced under
 *   any sink and the suite would have no teeth. Splitting the aggregate across
 *   the diamond's two arms makes "the running total is zero" a genuinely
 *   *cross-view* claim — one only a wave-aligned composite can keep.
 *   (`CoalescingCombineCell`, the ticket's scalar-grand-total candidate, is not
 *   used: the per-account pair of aggregates is strictly stronger — it localizes
 *   an imbalance to an account instead of only summing it away.)
 * - **(c) the outer self-join** — `CombineLatestCell(emitOnFrontier = true)`
 *   over the two per-transfer-id aggregates: a key held by only one side
 *   null-extends to [UNMATCHED], a key held by both to `balanced(0)`. This is
 *   the pipeline that exercises the E2-GATE null-extension gate.
 *
 * All four observed views descend from **one root**, which is mandatory, not
 * stylistic: the aligned sink's completeness fold and `WaveGate` are both static
 * link sets with no upstream traversal (G-13), so an arm — or a gated
 * operator's inlet — fed by an *independent* root is a phantom expected edge
 * that holds the other's waves forever. Both gated cells here are fed by the
 * two arms of one shared-source diamond, the only topology in which the
 * within-wave flicker they gate even exists.
 *
 * ### The interleaving device, and the CC1 harness landmine
 *
 * The **only** queued edges are the two source→filter ingress edges, one per
 * host (`FrontierGatedEmissionTest`'s device): the controller's seeded
 * cross-host pick decides how far the debit branch runs ahead of the credit
 * branch, so both gated operators and the sink must hold, order and release a
 * seed-varied buffer. Everything downstream is fused. Measured 2026-07-31 over
 * seeds 0..49 × 50 waves: the sink holds up to **20** waves at once (mean 3.4
 * sampled after every transfer), and so do both gated operators — the depth is
 * real, not a one-wave hiccup, and `current()` is nonetheless balanced at every
 * one of those samples.
 *
 * This shape is chosen to sidestep the CC1 landmine rather than to tiptoe around
 * it: `Progress` (the CP-A3 absorb-ack) is delivered **synchronously on the
 * sender's thread** while a rerouted edge's *data* is queued, so rerouting an
 * absorbing arm asymmetrically reorders its ack ahead of its own still-queued
 * delta and breaks the per-link FIFO that `WaveFrontier`, `WaveGate` and
 * `AlignedCompositeCell` all rest on (spec 31 rule 3). Here the two queued edges
 * are the diamond's *ingress* pair — rerouted together, never one alone — and
 * they carry no `Progress` at all, because the source never absorbs. Every edge
 * that *does* carry an absorb-ack (a zero-amount transfer leaves an account
 * balance value-equal, so `debitByAccount`/`creditByAccount` swallow the wave
 * and ack it) is fused, both planes synchronous. The landmine is structurally
 * absent rather than merely unobserved.
 *
 * ### Why the join is gated (E2-SUITE finding 1)
 *
 * `JoinSetCell` — the ticket's other candidate for pipeline (a) — is ungated and
 * reconciles per *inlet invocation*. Fed by both arms of one wave it signals
 * **twice** for that wave: the debit arrives with no matching credit yet, so the
 * first invocation produces nothing and `absorbAck`s the wave (CP-A3), and the
 * credit's invocation then emits the pair *under the same wave* — an edge that
 * declared a wave settled and then emitted on it. Downstream, a completeness
 * fold that took the ack at its word releases the wave without the join's row
 * and installs that row afterwards as a straggler. That is the
 * `CoalescingCombineCell` D-COMBINE lesson (one delta per *arm* rather than per
 * *wave*) in a binary operator, sharpened by the ack.
 *
 * It is not a regression: E2.4 scoped `emitOnFrontier` to `SemiJoinCell` and
 * `CombineLatestCell`, and `JoinSetCell` never had it. But it is a real
 * composition limit, so this suite states it executably rather than in prose —
 * `control - the ungated inner join`, below, swaps the gated semijoin for a
 * `JoinSetCell` and measures it: **50 of 50** seeds publish composites that
 * correspond to *no* completed-transfer prefix, roughly two composites per wave
 * instead of one. The gated `SemiJoinCell` is therefore a load-bearing choice in
 * pipeline (a), not a stylistic one.
 *
 * ### The invariant, checked at EVERY observed output
 *
 * Every published composite (not only at idle) must satisfy:
 *  1. **zero total** — `Σ debitBalances + Σ creditBalances == 0`: a debit and
 *     its credit are never observed split;
 *  2. **the wave-prefix oracle** — the composite equals a from-scratch
 *     recompute over some *prefix of completed transfer waves*, in per-source
 *     counter order. The prefix `p` is read off the composite itself
 *     (`outer.size`, one key per applied transfer) and the whole composite is
 *     then compared with the oracle's `p`-th snapshot, so a view that lags or
 *     leads by even one wave fails; `p` is additionally required to be the
 *     composite's own index, i.e. exactly one composite per wave, in order;
 *  3. **no same-wave null extension** — no observed `outer` row, and no delta
 *     the gated join ever emitted, is [UNMATCHED].
 *
 * ### The documented failure controls (22-consistency.md:322-325)
 *
 * If a control never trips, the harness is too weak to certify anything. All
 * three below trip on every seed measured, so the suite demonstrably has teeth.
 *
 *  - **`observeAll`** (control 1, the ticket's point-consistent composite) — the
 *    [civictech.cell.observe.CompositeSink] over the *same* graph violates the
 *    zero-total invariant. Measured 2026-07-31 over seeds 0..49 × 50 waves:
 *    **50 of 50** seeds, and **7931 of 9817** published composites — the F-5
 *    flash, made arithmetic.
 *  - **the ungated outer join** (control 2, 96 §E2.5's second control) —
 *    `CombineLatestCell(emitOnFrontier = false)` emits a null-extended row and
 *    retracts it inside the same wave. Measured: **50 of 50** seeds. This
 *    control is read at the join's *outlet*, where a downstream operator or the
 *    wire would see it; the aligned sink buffers a wave's deltas and applies
 *    them together, so this particular flicker is invisible *at the sink* —
 *    which is exactly why gating has to happen at the operator, before emission,
 *    and why the control is measured where the damage is done.
 *  - **the ungated inner join** (an additional control, E2-SUITE finding 1
 *    above) — swapping the gated semijoin for a `JoinSetCell` makes the aligned
 *    composite publish snapshots that are the recompute over *no* prefix of
 *    completed transfers. Measured: **50 of 50** seeds; seed 0 publishes 101
 *    composites for 50 waves, 100 of which match no prefix.
 */
class InternalConsistencyTest {

    // ------------------------------------------------------------ the domain


    enum class Side { DEBIT, CREDIT }

    /** One leg of a transfer. Element identity is `(transfer, account, amount, side)`; ids are unique. */
    data class Leg(val transfer: Int, val account: String, val amount: Long, val side: Side) : Serializable

    /** One balanced transfer: `amount` leaves [from] and arrives at [to], in one wave. */
    data class Transfer(val id: Int, val from: String, val to: String, val amount: Long)

    /** One delta the outer join actually emitted, with the wave it rode. */
    data class Seen(val timestamp: Timestamp, val delta: MapDelta<Int, String>)

    /**
     * The generative source: **one transfer is one wave**, carrying both legs as
     * one two-entry [SetDelta]. Tags are minted per leg (tag hygiene, 21) from a
     * ref-derived source, so a replay would re-mint the same tags.
     */
    private class TransferSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Leg>>>())
        private val tagSource: UUID = UUID.nameUUIDFromBytes("transfer-legs:${ref.id}".toByteArray())
        private var tag = 0L

        fun send(transfer: Transfer) {
            val debit = Leg(transfer.id, transfer.from, -transfer.amount, Side.DEBIT)
            val credit = Leg(transfer.id, transfer.to, transfer.amount, Side.CREDIT)
            outlet.call.propagate(
                SetDelta(
                    adds = mapOf(
                        debit to setOf(Timestamp(tagSource, ++tag)),
                        credit to setOf(Timestamp(tagSource, ++tag)),
                    ),
                ),
            )
        }
    }

    /** Records the outer join's raw emissions with their wave identity — where control 2 is read. */
    private class OuterRecorder(
        private val seen: MutableList<Seen>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Int, String>>>())

        init {
            inlet.onEach { seen += Seen(CurrentContext.get()!!.timestamp, it) }
        }
    }

    // ------------------------------------------------------------- the graph

    private class Graph(seed: Long, gatedOuter: Boolean, private val gatedJoin: Boolean = true) {
        val controller = SimulationController(seed)

        /** The debit branch's ingress queue (and the host every observed cell lives on). */
        val hostD = ManagedHost(scheduler = controller.scheduler())

        /** The credit branch's ingress queue — a second host, so the two arms genuinely race. */
        val hostC = ManagedHost(scheduler = controller.scheduler())

        val source = TransferSource()
        val debitFilter = FilterCell<Leg> { it.side == Side.DEBIT }
        val creditFilter = FilterCell<Leg> { it.side == Side.CREDIT }

        val debitByAccount = GroupByCell<Leg, String, Long, Long>(
            keyFn = { it.account },
            aggregator = Aggregators.sumOf<Leg> { it.amount },
        )
        val creditByAccount = GroupByCell<Leg, String, Long, Long>(
            keyFn = { it.account },
            aggregator = Aggregators.sumOf<Leg> { it.amount },
        )
        val debitByTransfer = GroupByCell<Leg, Int, Long, Long>(
            keyFn = { it.transfer },
            aggregator = Aggregators.sumOf<Leg> { it.amount },
        )
        val creditByTransfer = GroupByCell<Leg, Int, Long, Long>(
            keyFn = { it.transfer },
            aggregator = Aggregators.sumOf<Leg> { it.amount },
        )

        /** (a) the credit/debit join — gated, so it emits once per completed wave. */
        val pairs = SemiJoinCell<Leg, Leg, Int>(
            leftKey = { it.transfer },
            rightKey = { it.transfer },
            negated = false,
            emitOnFrontier = true,
        )

        /**
         * (a), the ungated alternative — control 3 only. `combine` projects the
         * debit leg, so its output is *by construction* the same set the gated
         * semijoin advertises and one oracle serves both wirings; only the
         * emission discipline differs.
         */
        val innerJoin = JoinSetCell<Leg, Leg, Int, Leg>(
            leftKey = { it.transfer },
            rightKey = { it.transfer },
            combine = { debit, _ -> debit },
        )

        /** Whichever join feeds the `pairs` view in this wiring. */
        val joinRef: CellRef get() = if (gatedJoin) pairs.ref else innerJoin.ref

        /** (c) the outer self-join on transfer id — the null-extension gate under test. */
        val outer = CombineLatestCell<Int, Long, Long, String>(emitOnFrontier = gatedOuter) { _, debit, credit ->
            if (debit != null && credit != null) balanced(debit + credit) else UNMATCHED
        }

        val outerSeen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
        private val recorder = OuterRecorder(outerSeen)

        init {
            val mgmtD = hostD.managementInlet.call
            listOf(
                source, debitFilter, debitByAccount, debitByTransfer,
                creditByAccount, creditByTransfer, if (gatedJoin) pairs else innerJoin, outer, recorder,
            ).forEach { mgmtD.spawn(it) }
            hostC.managementInlet.call.spawn(creditFilter)

            // ---- ingress: the only queued edges, and BOTH of them (CC1) ----
            source.outlet.subscribe(
                Use.fixed(hostD.inlet<SetDelta<Leg>>(debitFilter.ref, "inlet"), PortRef.generate()),
            )
            source.outlet.subscribe(
                Use.fixed(hostC.inlet<SetDelta<Leg>>(creditFilter.ref, "inlet"), PortRef.generate()),
            )

            // ---- the two arms of the shared-source diamond, fused ----
            wire(debitFilter.outlet, debitByAccount.inlet)
            wire(debitFilter.outlet, debitByTransfer.inlet)
            wire(creditFilter.outlet, creditByAccount.inlet)
            wire(creditFilter.outlet, creditByTransfer.inlet)
            if (gatedJoin) {
                wire(debitFilter.outlet, pairs.left)
                wire(creditFilter.outlet, pairs.right)
            } else {
                wire(debitFilter.outlet, innerJoin.left)
                wire(creditFilter.outlet, innerJoin.right)
            }
            wire(debitByTransfer.outlet, outer.left)
            wire(creditByTransfer.outlet, outer.right)

            outer.outlet.subscribe(Use.fixed(recorder.inlet.call, PortRef.generate()))
            controller.runToIdle()
        }

        /** Seed-randomized partial draining between transfers — the `GlitchFreeDiamondTest` discipline. */
        fun drive(transfers: List<Transfer>, seed: Long) {
            val rnd = Random(seed + DRAIN_SEED_OFFSET)
            transfers.forEach { transfer ->
                source.send(transfer)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()
        }
    }

    // ------------------------------------------------ the wave-prefix oracle

    /**
     * The from-scratch recompute, folded forward one completed transfer wave at
     * a time. [snapshot] is the composite the four views MUST show once — and
     * only once — the graph has applied exactly the transfers folded so far;
     * `prefixes[p]` is therefore the whole meaning of "the correct output for
     * some per-source frontier of the inputs" for this graph.
     */
    private class Oracle {
        private val debit = LinkedHashMap<String, Long>()
        private val credit = LinkedHashMap<String, Long>()
        private val pairs = LinkedHashSet<Leg>()
        private val outer = LinkedHashMap<Int, String>()

        fun apply(transfer: Transfer) {
            debit.merge(transfer.from, -transfer.amount, Long::plus)
            credit.merge(transfer.to, transfer.amount, Long::plus)
            pairs += Leg(transfer.id, transfer.from, -transfer.amount, Side.DEBIT)
            outer[transfer.id] = balanced(0L)
        }

        fun snapshot(): Map<String, Any?> = mapOf(
            "debitBalances" to LinkedHashMap(debit),
            "creditBalances" to LinkedHashMap(credit),
            "pairs" to LinkedHashSet(pairs),
            "outer" to LinkedHashMap(outer),
        )
    }

    private fun prefixesOf(transfers: List<Transfer>): List<Map<String, Any?>> {
        val oracle = Oracle()
        val prefixes = ArrayList<Map<String, Any?>>(transfers.size + 1)
        prefixes += oracle.snapshot()
        transfers.forEach { oracle.apply(it); prefixes += oracle.snapshot() }
        return prefixes
    }

    /**
     * A seeded schedule of balanced transfers over a small account set.
     * `amount == 0` is admitted on purpose: it leaves an account's running sum
     * value-equal, so the per-account aggregate swallows the wave and settles
     * the sink's arm by a CP-A3 absorb-ack instead of a delta — the
     * completeness path the aligned fold's `noteAbsorbed` exists for.
     */
    private fun schedule(seed: Long, waves: Int): List<Transfer> {
        val rnd = Random(seed)
        return (1..waves).map { id ->
            Transfer(
                id = id,
                from = ACCOUNTS[rnd.nextInt(ACCOUNTS.size)],
                to = ACCOUNTS[rnd.nextInt(ACCOUNTS.size)],
                amount = rnd.nextInt(10).toLong(),
            )
        }
    }

    // ----------------------------------------------------------- the aligned run

    private class Recorded(
        val composites: List<Map<String, Any?>>,
        val outerSeen: List<Seen>,
        val sinkBuffered: Int,
        val sinkUnmatched: Long,
        val pairsBuffered: Int,
        val outerBuffered: Int,
    )

    private fun alignedRun(seed: Long, transfers: List<Transfer>, gatedJoin: Boolean = true): Recorded {
        val graph = Graph(seed, gatedOuter = true, gatedJoin = gatedJoin)
        // attached BEFORE the generator starts: a sink attached mid-stream is
        // caught up per arm as each arm's baseline arrives (AlignedCompositeCell's
        // documented catch-up boundary), which is an attach transient, not a defect
        // — and would trip an "invariant at every observed output" clause for the
        // wrong reason.
        val sink = graph.hostD.observeAligned {
            map("debitBalances", graph.debitByAccount.ref)
            map("creditBalances", graph.creditByAccount.ref)
            set("pairs", graph.joinRef)
            map("outer", graph.outer.ref)
        }
        graph.controller.runToIdle()

        val recorded = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { recorded += it }

        graph.drive(transfers, seed)
        val settled = prefixesOf(transfers).last()
        awaitUntil("every aligned composite delivered (seed $seed)") {
            recorded.size >= transfers.size + 1 && recorded.lastOrNull() == settled
        }

        val result = Recorded(
            composites = recorded.toList(),
            outerSeen = graph.outerSeen.toList(),
            sinkBuffered = sink.bufferedWaves,
            sinkUnmatched = sink.unmatchedDeltas,
            pairsBuffered = graph.pairs.bufferedWaves,
            outerBuffered = graph.outer.bufferedWaves,
        )
        sink.close()
        return result
    }

    private fun zeroTotalOf(composite: Map<String, Any?>): Long =
        sumOf(composite, "debitBalances") + sumOf(composite, "creditBalances")

    @Suppress("UNCHECKED_CAST")
    private fun sumOf(composite: Map<String, Any?>, name: String): Long =
        (composite[name] as Map<Any?, Long>).values.sum()

    // ------------------------------------------------------- the invariant run

    @Test
    fun `every observed composite is a completed-transfer prefix, for every seed`() {
        val waves = 50
        for (seed in 0L until 200L) {
            val transfers = schedule(seed, waves)
            val run = alignedRun(seed, transfers)
            val prefixes = prefixesOf(transfers)

            withClue("seed $seed") {
                // one composite per settled wave, plus the registration catch-up:
                // no torn republication, nothing swallowed.
                run.composites.size shouldBe waves + 1

                run.composites.forEachIndexed { i, composite ->
                    // (1) the balanced-transfer invariant, at EVERY observed
                    // output: no observed composite ever holds a half-applied
                    // transfer's money.
                    withClue("composite $i zero-total") { zeroTotalOf(composite) shouldBe 0L }

                    // (2) the wave-prefix oracle: the composite IS the recompute
                    // over some prefix of completed transfer waves, in per-source
                    // counter order — and that prefix is this composite's index,
                    // so publication is one-per-wave and monotone.
                    val prefix = (composite["outer"] as Map<*, *>).size
                    withClue("composite $i prefix $prefix") {
                        prefix shouldBe i
                        composite shouldBe prefixes[prefix]
                    }

                    // (3) the outer-join pipeline never shows a null-extended row
                    // for a transfer whose matching leg arrived in the same wave.
                    withClue("composite $i null-extension") {
                        (composite["outer"] as Map<*, *>).values.forEach { it shouldBe balanced(0L) }
                    }
                }

                // the gate at the operator, not just at the sink: the outer join
                // emitted exactly one delta per wave and never a null extension.
                run.outerSeen.groupBy { it.timestamp }.forEach { (timestamp, group) ->
                    withClue("outer wave $timestamp") { group.size shouldBe 1 }
                }
                run.outerSeen.forEach { seen ->
                    seen.delta.puts.values.forEach { it shouldBe balanced(0L) }
                    withClue("outer removals") { seen.delta.removals.isEmpty().shouldBeTrue() }
                }

                // liveness: nothing stranded, nothing installed outside the fold.
                run.sinkBuffered shouldBe 0
                run.sinkUnmatched shouldBe 0L
                run.pairsBuffered shouldBe 0
                run.outerBuffered shouldBe 0
            }
        }
    }

    // ------------------------------------------------------------- control 1

    /**
     * The point-consistent composite over the *same* graph, with no interleaving
     * device beyond the two ingress queues the aligned run also uses. Each named
     * view is folded by its own `ObserveCell` and the composite republishes on
     * every per-outlet change, so a read pairs `debitBalances` at wave `t` with
     * `creditBalances` at wave `t-1` — a composite that is the correct output for
     * **no** frontier of the inputs, which is exactly what `[22-OBS-01]` forbids
     * and what the zero-total invariant detects arithmetically.
     */
    @Test
    fun `control - the point-consistent composite breaks the balanced-transfer invariant`() {
        var violated = 0
        for (seed in 0L until 50L) {
            val transfers = schedule(seed, waves = 50)
            val graph = Graph(seed, gatedOuter = true)
            val sink = graph.hostD.observeAll {
                map("debitBalances", graph.debitByAccount.ref)
                map("creditBalances", graph.creditByAccount.ref)
                set("pairs", graph.pairs.ref)
                map("outer", graph.outer.ref)
            }
            graph.controller.runToIdle()

            val recorded = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
            sink.onChange { recorded += it }

            graph.drive(transfers, seed)
            val settled = prefixesOf(transfers).last()
            awaitUntil("control composite settles (seed $seed)") { recorded.lastOrNull() == settled }

            if (recorded.any { zeroTotalOf(it) != 0L }) violated++
            sink.close()
        }
        // if this never trips the harness is too weak to certify anything —
        // tune the interleaving as GlitchFreeDiamondTest does.
        // Measured 2026-07-31: 50 of these 50 seeds violate the invariant.
        (violated > 0).shouldBeTrue()
    }

    // ------------------------------------------------------------- control 2

    /**
     * The ungated outer join (96 §E2.5's second documented control): a key whose
     * debit arrives before its credit null-extends to [UNMATCHED] on the outlet
     * and is corrected inside the *same* wave — the internal-consistency essay's
     * exact outer-join failure (research 04 §3), read where a downstream operator
     * or the wire would see it.
     */
    @Test
    fun `control - the ungated outer join emits then retracts a null extension inside one wave`() {
        var flickered = 0
        for (seed in 0L until 50L) {
            val transfers = schedule(seed, waves = 50)
            val graph = Graph(seed, gatedOuter = false)
            graph.drive(transfers, seed)

            val flicker = graph.outerSeen.groupBy { it.timestamp }.any { (_, group) ->
                val nullExtended = mutableSetOf<Int>()
                var found = false
                group.forEach { seen ->
                    seen.delta.puts.forEach { (key, value) ->
                        if (key in nullExtended && value != UNMATCHED) found = true
                        if (value == UNMATCHED) nullExtended += key
                    }
                }
                found
            }
            if (flicker) flickered++
        }
        // Measured 2026-07-31: 50 of these 50 seeds emit-then-retract ungated.
        (flickered > 0).shouldBeTrue()
    }

    // ------------------------------------------------------------- control 3

    /**
     * E2-SUITE finding 1, stated executably: the *ungated* inner join
     * (`JoinSetCell`) in pipeline (a) makes the aligned composite publish
     * snapshots that are the recompute over **no** prefix of completed transfers.
     *
     * Diagnosis (see the class KDoc §Why the join is gated): the join reconciles
     * per inlet invocation, so one wave produces two signals on its outlet — an
     * absorb-ack when the debit arrives with no matching credit yet, then the
     * pair's delta when the credit arrives. The sink's completeness fold takes
     * the ack at its word, releases the wave without the join's row, and installs
     * that row afterwards as a straggler: two composites per wave, the first
     * holding a transfer that the balances have applied and the join has not.
     *
     * This is a composition limit of an operator E2.4 never gated, not a
     * regression in a merged mechanism — hence a control here (asserted to
     * *fail* the invariant) rather than a pinned expected-failure of the suite
     * proper.
     */
    @Test
    fun `control - the ungated inner join publishes composites matching no transfer prefix`() {
        var torn = 0
        for (seed in 0L until 50L) {
            val transfers = schedule(seed, waves = 50)
            val run = alignedRun(seed, transfers, gatedJoin = false)
            val prefixes = prefixesOf(transfers).toSet()
            if (run.composites.any { it !in prefixes }) torn++
        }
        // Measured 2026-07-31: 50 of these 50 seeds tear — seed 0 publishes 101
        // composites for 50 waves, 100 of which correspond to no prefix.
        (torn > 0).shouldBeTrue()
    }

    private companion object {
        val ACCOUNTS = listOf("A", "B", "C", "D", "E")

        /** Keeps the drain schedule's random stream distinct from the transfer schedule's. */
        const val DRAIN_SEED_OFFSET = 1_000_003L

        /** What the outer join yields for a transfer id held by only one side. */
        const val UNMATCHED = "unmatched"

        /** What it yields for a settled pair — always `balanced(0)` for a balanced transfer. */
        fun balanced(sum: Long) = "balanced($sum)"
    }
}

/** One fused, handshaken producer→consumer edge; a rejection is a harness bug, not a finding. */
@Suppress("UNCHECKED_CAST")
private fun <D : Any> wire(outlet: Subscribe<Propagate<D>>, inlet: Serve<Propagate<D>>) {
    val result = outlet.linkTo(inlet as LinkFrom<Propagate<D>>)
    check(result is LinkResult.Connected) { "link rejected: $result" }
}
