package civictech.compmodel.cell

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * The leaf cell inside a stack. An abstract state machine, at the smallest abstraction that
 * can falsify the notes' claims.
 *
 * - [EFFECT]: an `Effectful` sink. Each handled frame is one act on the world's effect log.
 * - [EFFECT_STATEFUL]: `Effectful` and `Stateful` in one leaf — the DISPUTES:1232 combination
 *   COH F8 refuses under `D` (M1). Acts and absorbs into a set.
 * - [SET]: mergeable state (a grow-only set; idempotent absorption).
 * - [COUNTER]: non-idempotent state (a sum); its inlet enforces dedup on `applied`
 *   (PLP §5.3 "every inlet of a durable cell not declared idempotent (on applied)").
 * - [RELAY]: emits one output per input on its outlet (non-idempotent; enforces on
 *   `applied`); exercises `Outbox` and `Supervised`'s identity tier on the emission side.
 */
enum class LeafKind { EFFECT, EFFECT_STATEFUL, SET, COUNTER, RELAY }

/** Fault and management budgets per run; every one is a non-progress transition. */
data class Budget(
    val crash: Int = 0,
    val reconnect: Int = 0,
    val restart: Int = 0,
    val suspend: Int = 0,
    val checkpoint: Int = 0,
    val designate: Int = 0,
)

/**
 * One experiment configuration. [layers] is the layer *set* the scenario exercises; an order
 * experiment enumerates every permutation of it (see `OrderValidity`).
 */
data class Scenario(
    val name: String,
    val layers: Set<Layer>,
    val leaf: LeafKind,
    val lanes: Int = 1,
    val perLane: Int = 2,
    /** A aligns one frame per lane into a wave (glitch-free join, COH §2.5 `A`). */
    val waves: Boolean = false,
    /** Frames on this lane carry the old writer stamp 1; others 2. DESIGNATE(2) makes them stale. */
    val staleLane: Int? = null,
    /** Every frame of a lane carries one wave (the double-emit shape of computenet-wlwjw). */
    val sameWave: Boolean = false,
    val ownedFirst: Boolean = false,
    /**
     * F5's stated reason (COH §2.6): a set lane on which a deposed writer and the new leader
     * issue the same logical position (COH §3.1). The DESIGNATE of writer 2 is accepted before
     * the run; lane 1 then carries the stale unit (writer 1) followed by the valid one (writer 2)
     * at the SAME position. Requires [staleLane] = 1 and one frame per lane.
     */
    val staleThenValid: Boolean = false,
    /** The upstream takes a succession between its two frames (fresh epoch + ReBaseline). */
    val upstreamSuccession: Boolean = false,
    val budget: Budget,
)

/**
 * Deliberately broken variants: today's behaviour or rejected designs (the brief's
 * "controls that must diverge"). The default instance is the design as the notes state it.
 */
data class Variant(
    /** computenet-wlwjw: `X` keyed on the root wave per port (a high-water), not on positions. */
    val xKeyedOnWave: Boolean = false,
    /** computenet-kxdjx when false: the inlet's `dead` epochs are omitted from the checkpoint. */
    val snapshotDead: Boolean = true,
    /** M1 option (a): on a duplicate, replay the state transition and suppress only the act. */
    val suppressOnlyAct: Boolean = false,
    /** Control: `S` rotates its epoch on RESTART without journaling it (pre migration step 5). */
    val journalEpochRotation: Boolean = true,
    /**
     * Candidate fix for model finding REPLAY-1 (not in the notes): `D` logs each release from
     * `P`'s park, so replay re-runs the release at its logged point relative to later control
     * records (RESTART's epoch rotation). The notes log only acceptance and control signals.
     */
    val logPRelease: Boolean = false,
) {
    companion object { val DESIGN = Variant() }
}

// ---------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------

/** `P`: park queue and suspension flag (COH §2.5 `Suspendable`). */
data class PSt(val suspended: Boolean = false, val queue: List<Msg> = emptyList())
/** `A`: partial waves (COH §2.5 `Align`; `checkpointPending()` becomes `A`'s capture, §2.3). */
data class ASt(val buf: List<Data> = emptyList())
/** `F`: the `(epoch, writer)` fence (COH §3.1; "the fence stays in the CELL"). */
data class FSt(val fence: Int = 0)
/** `X`: the delivery frontier (PLP §5.1): `disposed`, `pullDischarged`, `dead` epochs (§5.7). */
data class XSt(
    val disposed: Map<LaneKey, Int> = emptyMap(),
    val pulls: Set<Int> = emptySet(),
    val dead: Set<Int> = emptySet(),
    /** Only for the wlwjw control: one wave high-water per port. */
    val waveHw: Int = 0,
)
/** `S`: the identity tier — outlet epoch and lane counter (COH §2.4, §4). */
data class SSt(val epoch: Int, val outSeq: Int = 0)
/** `O`: retained outbound frames, FIFO, until acknowledged (PLP §5.8, COH §3.6). */
data class OSt(val retained: List<Msg> = emptyList())
/** The leaf's own state; `applied` is the inner cursor (PLP §5.1), rolled back with the leaf. */
data class LeafSt(
    val set: Set<Int> = emptySet(),
    val applied: Map<LaneKey, Int> = emptyMap(),
    val counted: List<Key> = emptyList(),
)

/** All layer states of one term; a `D` checkpoint is a copy of the part inside `D`. */
data class Ls(
    val p: PSt = PSt(),
    val a: ASt = ASt(),
    val f: FSt = FSt(),
    val x: XSt = XSt(),
    val s: SSt,
    val o: OSt = OSt(),
    val leaf: LeafSt = LeafSt(),
)

/** D's stream records (COH H1: one logical stream per `D` scope). */
sealed interface Rec
data class RAcc(val msg: Msg) : Rec
data class RX(val keys: List<Data>) : Rec
data class RCtl(val ctl: Ctl) : Rec
/** REPLAY-1 candidate fix: `P` released the head of its park here. */
data object RRel : Rec

/** Storage that survives a crash: checkpoint plus tail (COH H1 `checkpoint`/`read`). */
data class Store(val checkpoint: Ls, val tail: List<Rec> = emptyList())

/** The upstream sender: a durable, reliable sender with an `O` that requires `STABLE`. */
data class Up(
    val next: Int = 0,
    val succeeded: Boolean = false,
    val retained: List<Msg> = emptyList(),
    val wires: List<List<Msg>>,
    val acks: List<Msg> = emptyList(),
)

/** The downstream receiver of a RELAY's outputs: durable, exact per-position dedup. */
data class RSt(
    val hw: Map<Int, Int> = emptyMap(),
    val dead: Set<Int> = emptySet(),
    val content: Map<Pos, Int> = emptyMap(),
    val taken: List<Int> = emptyList(),
)

/** Observable world, separate from cell state: the effect log and ghost bookkeeping. */
data class World(
    val mint: Int = 100,
    val acts: Map<Key, Int> = emptyMap(),
    /** Payloads acted on (the effect log by content, so two frames at one position stay apart). */
    val actedPayloads: Set<Int> = emptySet(),
    val consumed: Map<Key, Int> = emptyMap(),
    /** Crashes that hit a key inside its act → X-record window: the declared [24-DUR-09] ceiling. */
    val ceiling: Map<Key, Int> = emptyMap(),
    /** Payloads refused / dead-lettered WITH a report. */
    val reported: Set<Int> = emptySet(),
    /**
     * Ghost reference (I4): the content of every input T accepted (non-replay acceptance at the
     * outermost layer), including pull-baseline content. A plain, local, volatile, always-live
     * cell over the same accepted inputs would hold exactly this minus what was refused.
     */
    val acceptedContent: Set<Int> = emptySet(),
    /** Ghost: management has sent SUSPEND and not yet RESUME (COH §2.4 continuation state). */
    val mgmtSuspended: Boolean = false,
    val relayed: Set<Int> = emptySet(),
    val designated: Int = 0,
    /** Violations detected inside a transition (gap, stale admitted, partial wave, ...). */
    val latched: List<String> = emptyList(),
)

data class CS(
    val up: Up,
    val store: Store?,
    val ls: Ls,
    /** Acted keys whose `X` record is not yet in `D`'s stream (the [24-DUR-09] window). */
    val pending: List<Data>,
    val outWire: List<Msg>,
    val outAcks: List<Msg>,
    val r: RSt,
    val w: World,
    val b: Budget,
)

// ---------------------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------------------

/**
 * One term `stack(leaf)` between an upstream sender U and (for RELAY) a downstream
 * receiver R. Encodes:
 *
 * - **Acceptance** (COH §0 item 4, §2.3): a frame is offered to the outermost layer; it
 *   travels inward layer by layer. `D` appends before delivering inward ("append before
 *   delivery", PLP §6) and acknowledges `STABLE` (a `SYNCHRONOUS` stream, COH H1).
 * - **Positions and dedup** (PLP §5.3): `X` drops `seq <= disposed`, fences dead epochs,
 *   flags a gap on a dense lane (I-P3), and advances `disposed` after the handler returns
 *   (COH F3), durably in a separate `X` record written after the act (the [24-DUR-09]
 *   window). Pull baselines are checked against `pullDischarged` (PLP §5.4).
 * - **Durability** (COH §3.0, §4): a crash loses every layer outside `D` and restores every
 *   layer inside it from checkpoint, then replays the tail in order, after restoring `X`
 *   from all `X` records in the tail ([24-DUR-05] replay behaviour). Own log first (PLP
 *   I-P2): the replay is part of the crash transition, before any upstream re-delivery.
 * - **RESTART** (COH §2.4, M11 fallback (b) = succession; the note's stated behaviour until
 *   M11 is decided): resets everything inside `S`, mints a fresh epoch, journals the
 *   rotation in `D` when `S` is inside it, announces `ReBaseline(old)` and, for a mergeable
 *   leaf, gets a pull-baseline catch-up from upstream (M14's recommendation).
 * - **Suspension** (`P`): SUSPEND/RESUME are management-band signals entering at the
 *   outermost layer; `D` logs signals that pass it. Management sends RESUME because it sent
 *   SUSPEND, not because `P` remembers it; a frame reaching the leaf in between is a violation
 *   (COH §2.4 continuation state, F1's monotone-state half). `P`'s release of its park is not
 *   logged in the notes' text (model finding REPLAY-1; [Variant.logPRelease] is the candidate fix).
 * - **Refinement** (I4): a ghost reference over the content T accepted; a mergeable leaf may
 *   never hold content outside it (checked at every state) and equals it, minus refused and
 *   still-held inputs, at quiescence.
 * - **Fence** (`F`, COH §3.1): DESIGNATE raises the fence; a stale-writer unit is refused
 *   with a report.
 * - **Align** (`A`): holds a wave until one frame per lane is present, releases complete
 *   waves in wave order, all in one turn (glitch-freedom, PLP P9).
 * - **Outbox** (`O`, PLP §5.8): retains every emitted frame until R acknowledges it; resends
 *   in order on reconnect and after recovery. The RELAY leaf is replay-deterministic, so
 *   persist-before-transmit is skipped as F9 allows.
 *
 * Layer order matters through exactly three things: which states are inside `D` (survive a
 * crash), which are inside `S` (reset by RESTART), and the inbound processing order. A layer
 * that a scenario does not exercise is a pass-through with constant state, which is what
 * makes the projection argument of `OrderValidity` sound.
 */
class CellModel(
    val scenario: Scenario,
    val stack: List<Layer>,
    val variant: Variant = Variant.DESIGN,
) : Spec<CS> {

    override val name = "cell[${stack.code()}|${scenario.name}${if (variant != Variant.DESIGN) "|$variant" else ""}]"

    private val n = stack.size
    private val dIdx = stack.indexOf(Layer.DURABLE)
    private val sIdx = stack.indexOf(Layer.SUPERVISED)
    private val xIdx = stack.indexOf(Layer.EFFECT_DEDUP)
    private val pIdx = stack.indexOf(Layer.SUSPENDABLE)
    private val fIdx = stack.indexOf(Layer.FENCE)
    private val oIdx = stack.indexOf(Layer.OUTBOX)
    private val alignOn = Layer.ALIGN in stack && scenario.waves && scenario.lanes > 1

    private fun insideD(i: Int) = dIdx in 0 until i
    private fun insideS(i: Int) = sIdx in 0 until i
    private val leafIdx = n

    val script: List<Data> = buildList {
        if (scenario.staleThenValid) {
            add(Data(Pos(1, 0, 1), payload = 1, writer = 2))
            add(Data(Pos(1, 1, 1), payload = 11, writer = 1))
            add(Data(Pos(1, 1, 1), payload = 12, writer = 2))
        } else if (scenario.upstreamSuccession) {
            add(Data(Pos(1, 0, 1), payload = 1, wave = 1, owned = scenario.ownedFirst))
            add(Data(Pos(2, 0, 1), payload = 2, wave = 2))
        } else {
            for (seq in 1..scenario.perLane) for (lane in 0 until scenario.lanes) {
                add(
                    Data(
                        Pos(1, lane, seq),
                        payload = lane * 10 + seq,
                        wave = if (scenario.sameWave) 1 else seq,
                        writer = scenario.staleLane?.let { if (lane == it) 1 else 2 } ?: 0,
                        owned = scenario.ownedFirst && seq == 1 && lane == 0,
                    ),
                )
            }
        }
    }

    override fun initial(): CS {
        // staleThenValid: DESIGNATE(2) was accepted (and, with F inside D, checkpointed) before the run.
        val ls = Ls(s = SSt(epoch = 50), f = FSt(if (scenario.staleThenValid) 2 else 0))
        // Reduction: U has emitted (retained + put on the wire) every frame before T runs;
        // emission order relative to T's steps changes no reachable disposition, only the
        // interleaving count. The succession scenario emits its second frame later.
        val upfront = if (scenario.upstreamSuccession) script.take(1) else script
        return CS(
            up = Up(
                next = upfront.size, retained = upfront,
                wires = List(scenario.lanes) { l -> upfront.filter { it.pos!!.lane == l } },
            ),
            store = if (dIdx >= 0) Store(ls) else null,
            ls = ls, pending = emptyList(), outWire = emptyList(), outAcks = emptyList(),
            r = RSt(), w = World(designated = if (scenario.staleThenValid) 2 else 0), b = scenario.budget,
        )
    }

    // -- mutable working copy of one transition ---------------------------------------
    private inner class Ctx(s: CS) {
        var up = s.up; var store = s.store; var ls = s.ls; var pending = s.pending
        var outWire = s.outWire; var outAcks = s.outAcks; var r = s.r; var w = s.w; var b = s.b
        val handled = ArrayList<Data>()
        /** True while a crash recovery or RESTART replays `D`'s tail. */
        var replaying = false
        fun mint(): Int { val e = w.mint; w = w.copy(mint = e + 1); return e }
        fun latch(v: String) { w = w.copy(latched = w.latched + v) }
        fun append(rec: Rec) { store = store!!.copy(tail = store!!.tail + rec) }
        fun freeze(): CS {
            if (alignOn) {
                // Glitch-freedom: frames reaching the leaf in one turn form complete waves.
                handled.filter { it.pull == null }.groupBy { it.wave }.forEach { (wave, fs) ->
                    if (fs.map { it.pos!!.lane }.toSet().size < scenario.lanes) latch("partial wave $wave delivered to the leaf: $fs")
                }
            }
            if (b.crash == 0 && b.reconnect == 0) {
                // Reduction: with no crash or reconnect left, no in-flight acknowledgement can
                // be lost any more, so its arrival time is unobservable; deliver it now.
                var ret = up.retained
                for (a in up.acks) { val i = ret.indexOf(a); if (i >= 0) ret = ret.filterIndexed { j, _ -> j != i } }
                up = up.copy(retained = ret, acks = emptyList())
                var o = ls.o.retained
                for (a in outAcks) { val i = o.indexOf(a); if (i >= 0) o = o.filterIndexed { j, _ -> j != i } }
                ls = ls.copy(o = OSt(o)); outAcks = emptyList()
            }
            return CS(up, store, ls, pending, outWire, outAcks, r, w, b)
        }
    }

    // -- inbound processing ------------------------------------------------------------
    private fun inward(c: Ctx, from: Int, msg: Msg, replay: Boolean) {
        var i = from
        while (i < n) {
            when (stack[i]) {
                Layer.DURABLE -> if (!replay) { c.append(RAcc(msg)); c.up = c.up.copy(acks = c.up.acks + msg) }
                Layer.OUTBOX -> {}
                Layer.SUSPENDABLE -> if (c.ls.p.suspended || c.ls.p.queue.isNotEmpty()) {
                    c.ls = c.ls.copy(p = c.ls.p.copy(queue = c.ls.p.queue + msg)); return
                }
                Layer.FENCE -> if (msg is Data && msg.writer in 1 until c.ls.f.fence) { refuse(c, msg, i); return }
                Layer.ALIGN -> if (alignOn && msg is Data && msg.pull == null) { align(c, i, msg, replay); return }
                Layer.EFFECT_DEDUP -> when (msg) {
                    is ReBaseline -> { xRebaseline(c, msg); return }
                    is Data -> when (xCheck(c, msg)) {
                        XV.PASS -> {}
                        XV.DUP -> { dropDup(c, msg); return }
                        XV.FENCED -> { dropDup(c, msg); return }
                        XV.GAP -> { c.latch("I-P3 gap: $msg reached X above disposed ${c.ls.x.disposed}"); return }
                    }
                }
                Layer.SUPERVISED -> {}
            }
            i++
        }
        if (msg is Data) leafHandle(c, msg)
    }

    private fun align(c: Ctx, at: Int, msg: Data, replay: Boolean) {
        var buf = c.ls.a.buf + msg
        c.ls = c.ls.copy(a = ASt(buf))
        // Release complete waves in wave order; an incomplete earlier wave blocks later ones
        // (A never reorders a lane, PLP I-P2).
        while (true) {
            val first = buf.minOfOrNull { it.wave } ?: break
            val group = buf.filter { it.wave == first }
            if (group.map { it.pos!!.lane }.toSet().size < scenario.lanes) break
            buf = buf.filter { it.wave != first }
            c.ls = c.ls.copy(a = ASt(buf))
            for (g in group.sortedWith(compareBy({ it.pos!!.lane }, { it.pos!!.seq }))) inward(c, at + 1, g, replay)
            buf = c.ls.a.buf
        }
    }

    private enum class XV { PASS, DUP, FENCED, GAP }

    private fun xCheck(c: Ctx, m: Data): XV {
        val x = c.ls.x
        if (m.pull != null) return if (m.pull in x.pulls) XV.DUP else XV.PASS
        if (variant.xKeyedOnWave) return if (m.wave <= x.waveHw) XV.DUP else XV.PASS
        val p = m.pos!!
        if (p.epoch in x.dead) return XV.FENCED
        val hw = x.disposed[LaneKey(p.epoch, p.lane)] ?: 0
        return when {
            p.seq <= hw -> XV.DUP
            p.seq > hw + 1 -> XV.GAP
            else -> XV.PASS
        }
    }

    private fun xAdvance(c: Ctx, m: Data) {
        if (xIdx < 0) return
        val x = c.ls.x
        val nx = when {
            m.pull != null -> x.copy(pulls = x.pulls + m.pull)
            variant.xKeyedOnWave -> x.copy(waveHw = maxOf(x.waveHw, m.wave))
            else -> {
                val lk = LaneKey(m.pos!!.epoch, m.pos.lane)
                x.copy(disposed = x.disposed + (lk to maxOf(x.disposed[lk] ?: 0, m.pos.seq)))
            }
        }
        c.ls = c.ls.copy(x = nx)
    }

    private fun xRebaseline(c: Ctx, rb: ReBaseline) {
        val x = c.ls.x
        c.ls = c.ls.copy(x = x.copy(dead = x.dead + rb.epoch, disposed = x.disposed.filterKeys { it.epoch != rb.epoch }))
    }

    private fun dropDup(c: Ctx, m: Data) {
        // Dropped and counted; an exclusive payload is discharged (MH:1904-1915 pattern).
        // M1 option (a): the state transition is replayed, only the act is suppressed.
        if (variant.suppressOnlyAct && scenario.leaf == LeafKind.EFFECT_STATEFUL) {
            c.ls = c.ls.copy(leaf = c.ls.leaf.copy(set = c.ls.leaf.set + m.payload))
        }
    }

    private fun refuse(c: Ctx, m: Data, at: Int) {
        c.w = c.w.copy(reported = c.w.reported + m.payload)
        if (xIdx in 0 until at) {
            xAdvance(c, m)
            if (insideD(xIdx)) c.append(RX(listOf(m)))
        }
    }

    private fun leafHandle(c: Ctx, m: Data) {
        val isDup = when (scenario.leaf) {
            LeafKind.COUNTER, LeafKind.RELAY -> m.pos != null && m.pos.seq <= (c.ls.leaf.applied[LaneKey(m.pos.epoch, m.pos.lane)] ?: 0)
            else -> false
        }
        c.handled.add(m)
        if (!c.replaying && c.w.mgmtSuspended) {
            c.latch("P suspension lost: $m reached the leaf while management holds the term SUSPENDED (no RESUME sent)")
        }
        if (scenario.staleThenValid && m.writer in 1 until c.w.designated) {
            c.latch("I1 stale unit $m (writer ${m.writer}) reached the leaf after DESIGNATE ${c.w.designated} was accepted")
        }
        xAdvance(c, m)
        if (isDup) return
        val leaf = c.ls.leaf
        val applied = if (m.pos != null) leaf.applied + (LaneKey(m.pos.epoch, m.pos.lane) to m.pos.seq) else leaf.applied
        when (scenario.leaf) {
            LeafKind.EFFECT, LeafKind.EFFECT_STATEFUL -> {
                act(c, m)
                if (scenario.leaf == LeafKind.EFFECT_STATEFUL) c.ls = c.ls.copy(leaf = leaf.copy(set = leaf.set + m.payload))
            }
            LeafKind.SET -> c.ls = c.ls.copy(leaf = leaf.copy(set = if (m.pull != null) leaf.set + m.baseline else leaf.set + m.payload))
            LeafKind.COUNTER -> c.ls = c.ls.copy(leaf = leaf.copy(counted = leaf.counted + m.key, applied = applied))
            LeafKind.RELAY -> {
                c.ls = c.ls.copy(leaf = leaf.copy(applied = applied))
                emit(c, m.payload)
            }
        }
    }

    private fun act(c: Ctx, m: Data) {
        val k = m.key
        c.w = c.w.copy(
            acts = c.w.acts + (k to (c.w.acts[k] ?: 0) + 1),
            actedPayloads = c.w.actedPayloads + m.payload,
            consumed = if (m.owned) c.w.consumed + (k to (c.w.consumed[k] ?: 0) + 1) else c.w.consumed,
        )
        if (xIdx >= 0 && insideD(xIdx)) c.pending = c.pending + m
    }

    private fun emit(c: Ctx, payload: Int) {
        val s = c.ls.s
        val out = Data(Pos(s.epoch, 0, s.outSeq + 1), payload = payload)
        c.ls = c.ls.copy(s = s.copy(outSeq = s.outSeq + 1))
        if (oIdx >= 0) c.ls = c.ls.copy(o = OSt(c.ls.o.retained + out))
        c.outWire = c.outWire + out
        c.w = c.w.copy(relayed = c.w.relayed + payload)
    }

    // -- control -----------------------------------------------------------------------
    /** A control signal entering at stack index [from]: `D` logs it, the handling layer applies it. */
    private fun control(c: Ctx, from: Int, ctl: Ctl, replay: Boolean) {
        for (i in from until n) {
            val l = stack[i]
            if (l == Layer.DURABLE && !replay) c.append(RCtl(ctl))
            when {
                (ctl == Suspend || ctl == Resume) && l == Layer.SUSPENDABLE -> {
                    c.ls = c.ls.copy(p = c.ls.p.copy(suspended = ctl == Suspend)); return
                }
                ctl is Designate && l == Layer.FENCE -> { c.ls = c.ls.copy(f = FSt(maxOf(c.ls.f.fence, ctl.writer))); return }
                ctl is Restart && l == Layer.SUPERVISED -> { doRestart(c, ctl.epoch, replay); return }
            }
        }
    }

    /** RESTART as succession (M11 fallback (b); COH §2.4 "Without Durable"). */
    private fun doRestart(c: Ctx, epoch: Int, replay: Boolean) {
        val old = c.ls.s.epoch
        val fresh = Ls(s = SSt(epoch))
        var ls = c.ls
        for (i in sIdx + 1 until n) ls = take(ls, fresh, stack[i])
        ls = ls.copy(leaf = fresh.leaf, s = SSt(epoch))
        c.ls = ls
        if (dIdx > sIdx) recoverInsideD(c)
        if (scenario.leaf == LeafKind.RELAY) {
            val rb = ReBaseline(old)
            if (oIdx >= 0) c.ls = c.ls.copy(o = OSt(c.ls.o.retained + rb))
            c.outWire = c.outWire + rb
        }
        if (!replay && scenario.leaf == LeafKind.SET) {
            // M14: a pull-baseline catch-up of the upstream's state, with a fresh id.
            val id = c.mint()
            val content = script.take(c.up.next).map { it.payload }.toSet()
            val pull = Data(pos = null, payload = 0, pull = id, baseline = content)
            c.up = c.up.copy(retained = c.up.retained + pull, wires = c.up.wires.mapIndexed { l, wr -> if (l == 0) wr + pull else wr })
        }
    }

    private fun take(into: Ls, from: Ls, l: Layer): Ls = when (l) {
        Layer.DURABLE -> into
        Layer.OUTBOX -> into.copy(o = from.o)
        Layer.SUSPENDABLE -> into.copy(p = from.p)
        Layer.FENCE -> into.copy(f = from.f)
        Layer.ALIGN -> into.copy(a = from.a)
        Layer.EFFECT_DEDUP -> into.copy(x = from.x)
        Layer.SUPERVISED -> into.copy(s = from.s)
    }

    /** Restore every layer inside `D` from its checkpoint, then replay the tail (COH §3.8 `NONE`). */
    private fun recoverInsideD(c: Ctx) {
        val st = c.store ?: return
        val was = c.replaying
        c.replaying = true
        var ls = c.ls
        for (i in dIdx + 1 until n) ls = take(ls, st.checkpoint, stack[i])
        ls = ls.copy(leaf = st.checkpoint.leaf)
        if (!variant.snapshotDead) ls = ls.copy(x = ls.x.copy(dead = emptySet()))
        c.ls = ls
        // [24-DUR-05]: X's frontier = checkpoint + every X record in the tail, before replay.
        if (insideD(xIdx)) st.tail.filterIsInstance<RX>().flatMap { it.keys }.forEach { xAdvance(c, it) }
        for (rec in st.tail) when (rec) {
            is RAcc -> inward(c, dIdx + 1, rec.msg, replay = true)
            is RCtl -> control(c, dIdx + 1, rec.ctl, replay = true)
            RRel -> c.ls.p.queue.firstOrNull()?.let { m ->
                c.ls = c.ls.copy(p = c.ls.p.copy(queue = c.ls.p.queue.drop(1)))
                inward(c, pIdx + 1, m, replay = true)
            }
            is RX -> {}
        }
        // Acts re-run during replay get their X record now.
        if (c.pending.isNotEmpty()) { c.append(RX(c.pending)); c.pending = emptyList() }
        c.replaying = was
    }

    // -- transitions -------------------------------------------------------------------
    override fun next(s: CS): List<Transition<CS>> {
        val out = ArrayList<Transition<CS>>()
        fun step(label: String, progress: Boolean = true, f: Ctx.() -> Unit) {
            val c = Ctx(s); c.f(); out.add(Transition(label, c.freeze(), progress))
        }
        val up = s.up
        // Reduction: while an act awaits its X record, only that record or a fault may run.
        // Interleaving other frames into the window only widens a declared ceiling.
        if (s.pending.isNotEmpty()) {
            step("X record for ${s.pending.map { it.key }}") { append(RX(pending)); pending = emptyList() }
            faults(s, out, ::step)
            return out
        }
        // U emits its next frame (persist-before-transmit: retained, then on the wire).
        if (up.next < script.size) {
            val m = script[up.next]
            val gated = scenario.upstreamSuccession && up.next == 1 && !up.succeeded
            if (!gated) step("U emits $m") {
                this.up = this.up.copy(next = up.next + 1, retained = up.retained + m,
                    wires = up.wires.mapIndexed { l, w -> if (l == m.pos!!.lane) w + m else w })
            } else step("U succession: fresh epoch, ReBaseline(e1)") {
                val rb = ReBaseline(1)
                this.up = this.up.copy(succeeded = true, retained = up.retained + rb, wires = up.wires.mapIndexed { l, w -> if (l == 0) w + rb else w })
            }
        }
        for (l in up.wires.indices) if (up.wires[l].isNotEmpty()) {
            val m = up.wires[l].first()
            step("T accepts $m (lane $l)") {
                this.up = this.up.copy(wires = up.wires.mapIndexed { i, w -> if (i == l) w.drop(1) else w })
                if (m is Data) w = w.copy(acceptedContent = w.acceptedContent + (if (m.pull != null) m.baseline else setOf(m.payload)))
                inward(this, 0, m, replay = false)
            }
        }
        if (up.acks.isNotEmpty()) step("U receives STABLE ack for ${up.acks.first()}") {
            val a = up.acks.first()
            val idx = up.retained.indexOf(a)
            this.up = this.up.copy(acks = up.acks.drop(1), retained = if (idx >= 0) up.retained.filterIndexed { i, _ -> i != idx } else up.retained)
        }
        if (pIdx >= 0 && !s.ls.p.suspended && s.ls.p.queue.isNotEmpty()) step("P releases ${s.ls.p.queue.first()}") {
            val m = ls.p.queue.first()
            ls = ls.copy(p = ls.p.copy(queue = ls.p.queue.drop(1)))
            if (variant.logPRelease && insideD(pIdx)) append(RRel)
            inward(this, pIdx + 1, m, replay = false)
        }
        // RESUME is management's, sent because it SUSPENDed: it does not depend on P remembering.
        if (s.w.mgmtSuspended) step("RESUME") { w = w.copy(mgmtSuspended = false); control(this, 0, Resume, false) }
        if (s.outWire.isNotEmpty()) step("R receives ${s.outWire.first()}") {
            val m = outWire.first(); outWire = outWire.drop(1)
            rReceive(this, m); outAcks = outAcks + m
        }
        if (s.outAcks.isNotEmpty()) step("T.O receives ack ${s.outAcks.first()}") {
            val a = outAcks.first(); outAcks = outAcks.drop(1)
            val idx = ls.o.retained.indexOf(a)
            if (idx >= 0) ls = ls.copy(o = OSt(ls.o.retained.filterIndexed { i, _ -> i != idx }))
        }
        faults(s, out, ::step)
        return out
    }

    private fun faults(s: CS, out: MutableList<Transition<CS>>, step: (String, Boolean, Ctx.() -> Unit) -> Unit) {
        val up = s.up
        // -- optional: management and faults --
        val b = s.b
        if (b.suspend > 0 && pIdx >= 0 && !s.w.mgmtSuspended) step("SUSPEND", false) {
            this.b = b.copy(suspend = b.suspend - 1); w = w.copy(mgmtSuspended = true); control(this, 0, Suspend, false)
        }
        if (b.designate > 0 && fIdx >= 0) step("DESIGNATE writer 2", false) {
            this.b = b.copy(designate = b.designate - 1)
            control(this, 0, Designate(2), false)
            w = w.copy(designated = 2)
        }
        if (b.checkpoint > 0 && dIdx >= 0) step("D checkpoint", false) {
            this.b = b.copy(checkpoint = b.checkpoint - 1)
            store = Store(ls); pending = emptyList()
        }
        if (b.reconnect > 0 && (up.acks.isNotEmpty() || up.wires.any { it.isNotEmpty() })) step("U->T link reconnect (in-flight frames and acks lost; U resends retained)", false) {
            this.b = b.copy(reconnect = b.reconnect - 1)
            this.up = up.copy(acks = emptyList(), wires = resend(up.retained))
        }
        if (b.reconnect > 0 && (s.outAcks.isNotEmpty() || s.outWire.isNotEmpty())) step("T->R link reconnect (O resends retained)", false) {
            this.b = b.copy(reconnect = b.reconnect - 1)
            outAcks = emptyList(); outWire = ls.o.retained
        }
        if (b.restart > 0 && sIdx >= 0) step("RESTART (succession)", false) {
            this.b = b.copy(restart = b.restart - 1)
            val e = mint()
            if (insideD(sIdx) && variant.journalEpochRotation) append(RCtl(Restart(e)))
            doRestart(this, e, replay = false)
        }
        if (b.crash > 0) step("CRASH of T's host, then re-create with gap NONE and recover", false) {
            this.b = b.copy(crash = b.crash - 1)
            crash(this)
        }
    }

    private fun resend(retained: List<Msg>): List<List<Msg>> = List(scenario.lanes) { l ->
        retained.filter { m -> if (m is Data) (m.pos?.lane ?: 0) == l else l == 0 }
    }

    private fun crash(c: Ctx) {
        // The act -> X-record window: one re-act per crash per in-flight position is declared.
        c.pending.forEach { c.w = c.w.copy(ceiling = c.w.ceiling + (it.key to (c.w.ceiling[it.key] ?: 0) + 1)) }
        c.pending = emptyList()
        c.up = c.up.copy(acks = emptyList(), wires = resend(c.up.retained))
        c.outWire = emptyList(); c.outAcks = emptyList()
        // Everything outside D is volatile; S outside D re-mints (it has no identity to adopt).
        val fresh = Ls(s = SSt(c.mint()))
        var ls = c.ls
        for (i in 0 until n) if (!insideD(i)) ls = take(ls, fresh, stack[i])
        if (dIdx < 0) ls = ls.copy(leaf = fresh.leaf)
        c.ls = ls
        recoverInsideD(c)
        if (oIdx >= 0) c.outWire = c.ls.o.retained
    }

    private fun rReceive(c: Ctx, m: Msg) {
        val r = c.r
        when (m) {
            is ReBaseline -> c.r = r.copy(dead = r.dead + m.epoch)
            is Data -> {
                val p = m.pos!!
                val known = r.content[p]
                if (known != null && known != m.payload) c.latch("P3 position $p re-issued for different content: had p$known, got p${m.payload}")
                if (p.epoch in r.dead) return
                val hw = r.hw[p.epoch] ?: 0
                when {
                    p.seq <= hw -> {}
                    p.seq > hw + 1 -> c.latch("I-P3 gap at R: $m above $hw")
                    else -> c.r = r.copy(hw = r.hw + (p.epoch to p.seq), content = r.content + (p to m.payload), taken = r.taken + m.payload)
                }
            }
        }
    }

    // -- invariants --------------------------------------------------------------------
    private fun located(s: CS, m: Data): Boolean {
        fun carries(x: Msg) = x == m || (scenario.leaf == LeafKind.SET && x is Data && m.payload in x.baseline)
        return s.up.retained.any(::carries) || s.up.wires.any { w -> w.any(::carries) } ||
            s.ls.p.queue.any(::carries) || s.ls.a.buf.any(::carries)
    }

    private fun disposedTruth(s: CS, m: Data): Boolean = when (scenario.leaf) {
        LeafKind.EFFECT, LeafKind.EFFECT_STATEFUL -> m.payload in s.w.actedPayloads
        LeafKind.SET -> m.payload in s.ls.leaf.set
        LeafKind.COUNTER -> m.key in s.ls.leaf.counted
        LeafKind.RELAY -> m.payload in s.w.relayed
    }

    override fun invariants(s: CS): List<String> {
        val v = ArrayList<String>(s.w.latched)
        for (m in script.take(s.up.next)) {
            if (!disposedTruth(s, m) && m.payload !in s.w.reported && !located(s, m)) {
                v.add("I3/I1 silent loss: accepted $m is neither disposed, held, nor reported")
            }
        }
        for ((k, n) in s.w.acts) {
            val allowed = 1 + (s.w.ceiling[k] ?: 0)
            if (n > allowed) v.add("I2 effect: $k acted $n times, ceiling allows $allowed")
        }
        for ((k, n) in s.w.consumed) {
            if (n > 1 + (s.w.ceiling[k] ?: 0)) v.add("I1 custody: Owned payload of $k consumed $n times")
        }
        val dupCount = s.ls.leaf.counted.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (dupCount.isNotEmpty()) v.add("I5 duplicate taken twice by the counter: $dupCount")
        // I4, checked when it happens: the leaf never holds content T has not accepted.
        if (!s.w.acceptedContent.containsAll(s.ls.leaf.set)) {
            v.add("I4 premature addition: leaf set ${s.ls.leaf.set} holds ${s.ls.leaf.set - s.w.acceptedContent}, which no accepted input carried")
        }
        val rDup = s.r.taken.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (rDup.isNotEmpty()) v.add("I5/I4 downstream took one output twice under different positions: $rDup")
        if (fIdx >= 0 && s.ls.f.fence < s.w.designated) {
            v.add("I1 custody of exclusive authority: F's fence ${s.ls.f.fence} regressed below the accepted DESIGNATE ${s.w.designated}")
        }
        for (p in s.w.relayed) {
            // Held: on the wire, retained in O, or its input is still held in T (it will be
            // re-derived with the same position, PLP P2).
            val held = s.outWire.any { it is Data && it.payload == p } || s.ls.o.retained.any { it is Data && it.payload == p } ||
                script.any { it.payload == p && located(s, it) }
            if (p !in s.r.taken && !held) v.add("I3 silent loss of output p$p between T and R")
        }
        return v
    }

    override fun quiescent(s: CS): List<String> {
        val v = ArrayList<String>()
        if (scenario.leaf == LeafKind.SET) {
            // Exact equality with the ghost reference over accepted, accounted inputs: accepted,
            // minus refused-with-report, minus what is still held in T's custody.
            val held = script.filter { m -> s.ls.p.queue.contains(m) || s.ls.a.buf.contains(m) }.map { it.payload }.toSet()
            val expect = s.w.acceptedContent - s.w.reported - held
            if (s.ls.leaf.set != expect) v.add("I4 refinement: converged set ${s.ls.leaf.set} != reference $expect")
        }
        if (scenario.leaf == LeafKind.EFFECT_STATEFUL) {
            for (m in script) if ((s.w.acts[m.key] ?: 0) > 0 && m.payload !in s.ls.leaf.set) {
                v.add("I4 refinement: $m was acted on but its state transition is missing (state ${s.ls.leaf.set})")
            }
        }
        return v
    }
}
