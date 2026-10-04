package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * The promotion swap: COH §3.4 (`Swap` inserted by `S` between itself and the leaf; phases
 * PRECHECK, PREPARE, COMMIT, green/RETIRE, rollback; crash inside the window: replay rolls
 * back without a COMMIT record and forward with one), PLP §7 rows "T0/T1 promotion" and
 * "T2 promotion", and the effect-identity table (F11).
 *
 * Term: `D ∘ X ∘ S ∘ [Swap] ∘ leaf`. The leaf acts once per input on an external
 * destination named by its effect identity, and emits one output per input on its outlet
 * (stateless, so F8 does not apply). Upstream U retains until `STABLE` and may lose an
 * acknowledgement (one reconnect) — the only source of duplicates. Downstream R dedups by
 * exact position and fences `ReBaseline`d epochs.
 *
 * The promotion's identity rows modelled: same identity (T1 or T2), and a different identity
 * with `effectFrom = COMMIT` (X keeps the term's `disposed` frontier).
 */
enum class PromLeaf {
    /** `Effectful` sink: acts on the destination named by its effect identity; no outlet. */
    EFFECT,
    /** Replay-deterministic relay: one output per input; exercises S's lanes (PLP §7 T0/T1/T2 rows). */
    RELAY,
    /** An `Effectful` leaf that also emits (stateless, so F8 admits it) — see model finding F9-X. */
    EFFECT_EMIT,
}

data class PromotionVariant(
    val leaf: PromLeaf = PromLeaf.EFFECT,
    val tier: Int = 1,
    /** Candidate effect identity; the incumbent's is 1. */
    val candidateIdentity: Int = 1,
    /** Control computenet-lzfr0: T2 supersedes the candidate's fresh lane instead of the incumbent's. */
    val supersedeCandidateLane: Boolean = false,
    /** Control: the swap window is unlogged ("needs no journal", Evolution.kt:268-269). */
    val unloggedWindow: Boolean = false,
    /** Control: the candidate's X starts empty instead of at the term's disposed frontier. */
    val candidateDedupStartsEmpty: Boolean = false,
    /** Rollback allowed between COMMIT and RETIRE ("Before RETIRE", COH §3.4). */
    val rollbackAfterCommit: Boolean = true,
    /** Fix candidate: Swap's held frames are checked by X again on release (X re-check). */
    val recheckOnRelease: Boolean = false,
)

data class PFrame(val seq: Int) { override fun toString() = "in$seq" }
data class POut(val epoch: Int, val seq: Int, val payload: Int, val rebaseline: Boolean = false) {
    override fun toString() = if (rebaseline) "ReBaseline(e$epoch)" else "($epoch,0,$seq):p$payload"
}

sealed interface PRec
data class PAcc(val f: PFrame) : PRec
data class PXRec(val seq: Int) : PRec
data class PPrepare(val dummy: Int = 0) : PRec
data class PCommit(val epoch: Int) : PRec
data object PRetire : PRec
data object PRollback : PRec

enum class Phase { NONE, PREPARED, COMMITTED }

data class PromSt(
    // U
    val upRetained: List<PFrame>,
    val upWire: List<PFrame>,
    val upAcks: List<PFrame> = emptyList(),
    // term (volatile view)
    val disposed: Int = 0,
    val epoch: Int = 1,
    val outSeq: Int = 0,
    val leafIdentity: Int = 1,
    val phase: Phase = Phase.NONE,
    val held: List<PFrame> = emptyList(),
    val pendingX: Int? = null,
    /** The incumbent's identity tier, kept by S while a T2 swap is committed but not retired. */
    val incEpoch: Int = 1,
    val incOutSeq: Int = 0,
    // D's stream
    val tail: List<PRec> = emptyList(),
    // outbound
    val retained: List<POut> = emptyList(),
    val outWire: List<POut> = emptyList(),
    // R
    val rHw: Map<Int, Int> = emptyMap(),
    val rDead: Set<Int> = emptySet(),
    val rTaken: List<Int> = emptyList(),
    val rContent: Map<Pair<Int, Int>, Int> = emptyMap(),
    // world
    val acts: Map<Pair<Int, Int>, Int> = emptyMap(),
    val ceiling: Map<Int, Int> = emptyMap(),
    val mint: Int = 10,
    val latched: List<String> = emptyList(),
    // budgets
    val promote: Int = 1,
    val crash: Int = 1,
    val reconnect: Int = 1,
    val rollback: Int = 1,
)

class PromotionModel(val variant: PromotionVariant = PromotionVariant(), val frames: Int = 3) : Spec<PromSt> {
    override val name = "promotion[$variant]"

    private val script = (1..frames).map { PFrame(it) }

    override fun initial() = PromSt(upRetained = script, upWire = script)

    private class C(var s: PromSt)

    /** Inbound through D, X, S, Swap, leaf. */
    private fun accept(c: C, f: PFrame, replay: Boolean) {
        if (!replay) c.s = c.s.copy(tail = c.s.tail + PAcc(f), upAcks = c.s.upAcks + f)
        // X: exact per-lane dedup on disposed (single dense lane).
        if (f.seq <= c.s.disposed) return
        if (f.seq > c.s.disposed + 1 && c.s.held.none { it.seq == f.seq - 1 }) {
            c.s = c.s.copy(latched = c.s.latched + "I-P3 gap: $f above disposed ${c.s.disposed}")
            return
        }
        if (c.s.phase != Phase.NONE) { c.s = c.s.copy(held = c.s.held + f); return }
        handle(c, f, replay)
    }

    private val acts = variant.leaf != PromLeaf.RELAY
    private val emits = variant.leaf != PromLeaf.EFFECT

    private fun handle(c: C, f: PFrame, replay: Boolean) {
        val s = c.s
        val key = s.leafIdentity to f.seq
        c.s = s.copy(acts = s.acts + (key to (s.acts[key] ?: 0) + 1), disposed = maxOf(s.disposed, f.seq))
        if (emits) {
            val out = POut(s.epoch, s.outSeq + 1, f.seq)
            c.s = c.s.copy(outSeq = s.outSeq + 1, retained = c.s.retained + out, outWire = c.s.outWire + out)
        }
        if (acts) {
            // An Effectful inlet's X record follows the act (the [24-DUR-09] window).
            if (replay) c.s = c.s.copy(tail = c.s.tail + PXRec(f.seq)) else c.s = c.s.copy(pendingX = f.seq)
        }
    }

    private fun commit(c: C, epoch: Int, replay: Boolean) {
        val s = c.s
        if (!replay && !variant.unloggedWindow) c.s = s.copy(tail = s.tail + PCommit(epoch))
        c.s = c.s.copy(phase = Phase.COMMITTED, leafIdentity = variant.candidateIdentity)
        if (variant.candidateDedupStartsEmpty && variant.candidateIdentity != 1) c.s = c.s.copy(disposed = 0)
        if (variant.tier == 2) {
            // S mints a fresh epoch and announces ReBaseline superseding the INCUMBENT's lane
            // before releasing the buffer (COH §3.4 step 3, T2).
            val old = c.s.epoch
            val rb = POut(if (variant.supersedeCandidateLane) epoch else old, 0, 0, rebaseline = true)
            c.s = c.s.copy(incEpoch = old, incOutSeq = c.s.outSeq, epoch = epoch, outSeq = 0, retained = c.s.retained + rb, outWire = c.s.outWire + rb)
        }
    }

    private fun rollback(c: C, replay: Boolean) {
        if (!replay && !variant.unloggedWindow) c.s = c.s.copy(tail = c.s.tail + PRollback)
        val held = c.s.held
        if (c.s.phase == Phase.COMMITTED && variant.tier == 2) {
            // The incumbent resumes on its own lane: S restores the incumbent's identity tier.
            c.s = c.s.copy(epoch = c.s.incEpoch, outSeq = c.s.incOutSeq)
        }
        if (c.s.phase == Phase.COMMITTED && variant.candidateDedupStartsEmpty) c.s = c.s.copy(disposed = c.s.tail.filterIsInstance<PXRec>().maxOfOrNull { it.seq } ?: 0)
        c.s = c.s.copy(phase = Phase.NONE, held = emptyList(), leafIdentity = 1)
        for (f in held) recheckThenHandle(c, f, replay)
    }

    private fun recheckThenHandle(c: C, f: PFrame, replay: Boolean) {
        if (variant.recheckOnRelease && f.seq <= c.s.disposed) return
        handle(c, f, replay)
        if (!replay && c.s.pendingX != null) {
            // Releases run as one turn; their X records follow immediately.
            c.s = c.s.copy(tail = c.s.tail + PXRec(c.s.pendingX!!), pendingX = null)
        }
    }

    private fun recover(c: C) {
        val tail = c.s.tail
        val recorded = tail.filterIsInstance<PXRec>().maxOfOrNull { it.seq } ?: 0
        c.s = c.s.copy(
            disposed = recorded, epoch = 1, outSeq = 0, incEpoch = 1, incOutSeq = 0, leafIdentity = 1, phase = Phase.NONE, held = emptyList(),
            retained = emptyList(), outWire = emptyList(), pendingX = null,
        )
        var committed = false
        for (rec in tail) when (rec) {
            is PAcc -> accept(c, rec.f, replay = true)
            is PPrepare -> c.s = c.s.copy(phase = Phase.PREPARED, held = emptyList())
            is PCommit -> { committed = true; commit(c, rec.epoch, replay = true) }
            PRetire -> { val h = c.s.held; c.s = c.s.copy(phase = Phase.NONE, held = emptyList()); h.forEach { recheckThenHandle(c, it, true) } }
            PRollback -> rollback(c, replay = true)
            is PXRec -> {}
        }
        // Without a COMMIT record a prepared swap rolls back.
        if (c.s.phase == Phase.PREPARED && !committed) rollback(c, replay = false)
        c.s = c.s.copy(outWire = c.s.retained)
    }

    override fun next(s: PromSt): List<Transition<PromSt>> {
        val out = ArrayList<Transition<PromSt>>()
        fun step(l: String, progress: Boolean = true, f: C.() -> Unit) { val c = C(s); c.f(); out.add(Transition(l, c.s, progress)) }
        if (s.pendingX != null) {
            step("X record for in${s.pendingX}") { this.s = this.s.copy(tail = this.s.tail + PXRec(s.pendingX), pendingX = null) }
        } else {
            if (s.upWire.isNotEmpty()) step("T accepts ${s.upWire.first()}") {
                this.s = this.s.copy(upWire = s.upWire.drop(1)); accept(this, s.upWire.first(), false)
            }
            if (s.upAcks.isNotEmpty()) step("U receives ack ${s.upAcks.first()}") {
                this.s = this.s.copy(upAcks = s.upAcks.drop(1), upRetained = s.upRetained - s.upAcks.first())
            }
            if (s.outWire.isNotEmpty()) step("R receives ${s.outWire.first()}") {
                val o = s.outWire.first()
                this.s = this.s.copy(outWire = s.outWire.drop(1), retained = s.retained - o)
                rReceive(this, o)
            }
            if (s.promote > 0 && s.phase == Phase.NONE) step("PROMOTE: PRECHECK + PREPARE (Swap parks inbound)", false) {
                this.s = this.s.copy(promote = 0, phase = Phase.PREPARED, tail = if (variant.unloggedWindow) s.tail else s.tail + PPrepare())
            }
            if (s.phase == Phase.PREPARED) step("COMMIT (T${variant.tier}, candidate identity ${variant.candidateIdentity})") {
                val e = this.s.mint; this.s = this.s.copy(mint = e + 1); commit(this, e, false)
            }
            if (s.phase == Phase.COMMITTED) step("green: release held ${s.held} to candidate, RETIRE incumbent") {
                val h = this.s.held
                this.s = this.s.copy(phase = Phase.NONE, held = emptyList(), tail = if (variant.unloggedWindow) this.s.tail else this.s.tail + PRetire)
                h.forEach { recheckThenHandle(this, it, false) }
            }
            if (s.rollback > 0 && (s.phase == Phase.PREPARED || (s.phase == Phase.COMMITTED && variant.rollbackAfterCommit))) {
                step("ROLLBACK before RETIRE (release held to the incumbent)", false) {
                    this.s = this.s.copy(rollback = 0)
                    rollback(this, false)
                }
            }
        }
        if (s.reconnect > 0 && (s.upAcks.isNotEmpty() || s.upWire.isNotEmpty())) step("U->T reconnect: acks lost, U resends retained", false) {
            this.s = this.s.copy(reconnect = 0, upAcks = emptyList(), upWire = s.upRetained)
        }
        if (s.crash > 0) step("CRASH and recover from D", false) {
            var ceil = s.ceiling
            s.pendingX?.let { ceil = ceil + (it to (ceil[it] ?: 0) + 1) }
            this.s = this.s.copy(crash = 0, ceiling = ceil, upAcks = emptyList(), upWire = s.upRetained)
            recover(this)
        }
        return out
    }

    private fun rReceive(c: C, o: POut) {
        val s = c.s
        if (o.rebaseline) { c.s = s.copy(rDead = s.rDead + o.epoch); return }
        val known = s.rContent[o.epoch to o.seq]
        if (known != null && known != o.payload) c.s = s.copy(latched = s.latched + "P3 position (${o.epoch},${o.seq}) re-issued for p${o.payload}, had p$known")
        if (o.epoch in s.rDead) return
        val hw = s.rHw[o.epoch] ?: 0
        if (o.seq <= hw) return
        if (o.seq > hw + 1) { c.s = c.s.copy(latched = c.s.latched + "I-P3 gap at R: $o above $hw"); return }
        c.s = c.s.copy(rHw = c.s.rHw + (o.epoch to o.seq), rTaken = c.s.rTaken + o.payload, rContent = c.s.rContent + ((o.epoch to o.seq) to o.payload))
    }

    override fun invariants(s: PromSt): List<String> {
        val v = ArrayList(s.latched)
        val perSeq = s.acts.entries.groupBy({ it.key.second }, { it.value }).mapValues { it.value.sum() }
        if (acts) for ((seq, n) in perSeq) if (n > 1 + (s.ceiling[seq] ?: 0)) {
            v.add("I2 effect: input $seq acted $n times across identities ${s.acts.filterKeys { it.second == seq }}")
        }
        val dup = s.rTaken.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (dup.isNotEmpty()) v.add("I5 downstream took an output twice: $dup")
        // No silent loss of inputs: acted, held, or retained/in flight upstream.
        for (f in script) {
            if ((perSeq[f.seq] ?: 0) > 0 && emits) {
                // ...and its output: taken, retained, or fenced only if re-derivable.
                val outOk = f.seq in s.rTaken || s.retained.any { !it.rebaseline && it.payload == f.seq } || s.outWire.any { !it.rebaseline && it.payload == f.seq }
                if (!outOk) v.add("I3 output for input ${f.seq} lost downstream (R taken ${s.rTaken}, dead ${s.rDead})")
                continue
            }
            if ((perSeq[f.seq] ?: 0) > 0) continue
            if (f !in s.held && f !in s.upRetained && f !in s.upWire) v.add("I3 silent loss of input $f")
        }
        return v
    }

    override fun quiescent(s: PromSt): List<String> =
        script.filter { f -> s.acts.keys.none { it.second == f.seq } && f !in s.held }.map { "input $it never acted" }
}
