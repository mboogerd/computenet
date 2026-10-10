package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * The promotion swap: COH §3.4 as revised for the model findings (SWAP-1: `P` is the gate,
 * `Swap` holds nothing; SWAP-2: rollback only before COMMIT, after it a new swap; F9-X: outputs
 * caused through an `X`-suppressed inlet are logged), PLP §7 rows "T0/T1 promotion" and
 * "T2 promotion", and the effect-identity table (F11).
 *
 * Term: `D ∘ P ∘ X ∘ S ∘ [Swap] ∘ leaf`. Phases, each its own transition so a crash falls
 * between any two: PRECHECK (side-effect free, volatile), PREPARE (logged; `P` parks inbound),
 * COMMIT (logged; T2 mints and announces), green (logged; `P` resumes), one transition per
 * frame `P` releases through `X` to the candidate, RETIRE (logged). Rollback (logged) only
 * from PREPARE. Crash: `D` replays; without a COMMIT record a prepared swap rolls back, with
 * one it rolls forward.
 *
 * The leaf acts once per input on an external destination named by its effect identity,
 * and/or emits one output per input on its outlet (stateless, so F8 does not apply). Upstream
 * U retains until `STABLE` and may lose an acknowledgement (one reconnect), the source of
 * duplicates. Downstream R dedups by exact position and fences `ReBaseline`d epochs.
 */
enum class PromLeaf {
    /** `Effectful` sink: acts on the destination named by its effect identity; no outlet. */
    EFFECT,
    /** Replay-deterministic relay: one output per input; exercises S's lanes (PLP §7 T0/T1/T2 rows). */
    RELAY,
    /** An `Effectful` leaf that also emits (stateless, so F8 admits it): model finding F9-X. */
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
    /** Control, the `299d4e9c` text (SWAP-2): rollback allowed between COMMIT and RETIRE. */
    val rollbackAfterCommit: Boolean = false,
    /** Control, the `299d4e9c` text (SWAP-1): `Swap` parks inbound frames in its `held`, below `X`. */
    val swapHoldsBelowX: Boolean = false,
    /** Control, the `299d4e9c` text (F9-X): an emitting `Effectful` leaf does not log its outputs. */
    val unloggedEffectOutputs: Boolean = false,
)

data class PFrame(val seq: Int) { override fun toString() = "in$seq" }
data class POut(val epoch: Int, val seq: Int, val payload: Int, val rebaseline: Boolean = false) {
    override fun toString() = if (rebaseline) "ReBaseline(e$epoch)" else "($epoch,0,$seq):p$payload"
}

sealed interface PRec
data class PAcc(val f: PFrame) : PRec
data class PXRec(val seq: Int) : PRec
data object PPrepare : PRec
data class PCommit(val epoch: Int) : PRec
data object PGreen : PRec
data object PRetire : PRec
data object PRollback : PRec
/** `O`'s output record (F9 as revised): the output an input caused, logged before transmit. */
data class POutRec(val out: POut, val input: Int) : PRec

/** NONE → (PRECHECKED) → PREPARED → COMMITTED → GREEN → NONE (RETIRE). */
enum class Phase { NONE, PRECHECKED, PREPARED, COMMITTED, GREEN }

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
    /** P's park queue (custody inside D, outside X). */
    val parked: List<PFrame> = emptyList(),
    /** Only in the [PromotionVariant.swapHoldsBelowX] control: Swap's held buffer below X. */
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

    private val acts = variant.leaf != PromLeaf.RELAY
    private val emits = variant.leaf != PromLeaf.EFFECT
    /** F9 as revised: outputs caused through an X-suppressed (Effectful) inlet are logged. */
    private val logOutputs = acts && emits && !variant.unloggedEffectOutputs

    /** P is suspended from PREPARE until green or rollback. */
    private fun pGated(s: PromSt) = !variant.swapHoldsBelowX && (s.phase == Phase.PREPARED || s.phase == Phase.COMMITTED)
    private fun swapHolding(s: PromSt) = variant.swapHoldsBelowX && (s.phase == Phase.PREPARED || s.phase == Phase.COMMITTED)

    /** Inbound through D, P, X, S, [Swap], leaf. */
    private fun accept(c: C, f: PFrame, replay: Boolean) {
        if (!replay) c.s = c.s.copy(tail = c.s.tail + PAcc(f), upAcks = c.s.upAcks + f)
        // P: parks while gated, and behind anything it already parks (FIFO).
        if (pGated(c.s) || c.s.parked.isNotEmpty()) { c.s = c.s.copy(parked = c.s.parked + f); return }
        throughX(c, f, replay)
    }

    private fun throughX(c: C, f: PFrame, replay: Boolean) {
        // X: exact per-lane dedup on disposed (single dense lane), checked at delivery time.
        if (f.seq <= c.s.disposed) return
        if (f.seq > c.s.disposed + 1 && c.s.held.none { it.seq == f.seq - 1 }) {
            c.s = c.s.copy(latched = c.s.latched + "I-P3 gap: $f above disposed ${c.s.disposed}")
            return
        }
        if (swapHolding(c.s)) { c.s = c.s.copy(held = c.s.held + f); return }
        handle(c, f, replay)
    }

    private fun handle(c: C, f: PFrame, replay: Boolean) {
        val s = c.s
        val key = s.leafIdentity to f.seq
        c.s = s.copy(acts = if (acts) s.acts + (key to (s.acts[key] ?: 0) + 1) else s.acts, disposed = maxOf(s.disposed, f.seq))
        if (emits) {
            val logged = if (logOutputs) c.s.tail.filterIsInstance<POutRec>().firstOrNull { it.input == f.seq } else null
            if (logged != null) {
                // Re-derived in replay, but O already logged this output: restore it, not re-emit.
                restoreOutput(c, logged.out)
            } else {
                val out = POut(c.s.epoch, c.s.outSeq + 1, f.seq)
                c.s = c.s.copy(outSeq = c.s.outSeq + 1, retained = c.s.retained + out, outWire = c.s.outWire + out)
                if (logOutputs) c.s = c.s.copy(tail = c.s.tail + POutRec(out, f.seq))
            }
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
            // before anything is released (COH §3.4 step 3, T2).
            val old = c.s.epoch
            val rb = POut(if (variant.supersedeCandidateLane) epoch else old, 0, 0, rebaseline = true)
            c.s = c.s.copy(incEpoch = old, incOutSeq = c.s.outSeq, epoch = epoch, outSeq = loggedMax(c.s.tail, epoch), retained = c.s.retained + rb, outWire = c.s.outWire + rb)
        }
    }

    /** Release Swap's held buffer (control only), as the old text's green/rollback did. */
    private fun releaseHeld(c: C, replay: Boolean) {
        val h = c.s.held
        c.s = c.s.copy(held = emptyList())
        for (f in h) {
            handle(c, f, replay)
            if (!replay && c.s.pendingX != null) c.s = c.s.copy(tail = c.s.tail + PXRec(c.s.pendingX!!), pendingX = null)
        }
    }

    private fun rollback(c: C, replay: Boolean) {
        if (!replay && !variant.unloggedWindow) c.s = c.s.copy(tail = c.s.tail + PRollback)
        if (c.s.phase == Phase.COMMITTED && variant.tier == 2) {
            // Old text: the incumbent resumes on its own (already superseded) lane.
            c.s = c.s.copy(epoch = c.s.incEpoch, outSeq = c.s.incOutSeq)
        }
        if (c.s.phase == Phase.COMMITTED && variant.candidateDedupStartsEmpty) c.s = c.s.copy(disposed = c.s.tail.filterIsInstance<PXRec>().maxOfOrNull { it.seq } ?: 0)
        c.s = c.s.copy(phase = Phase.NONE, leafIdentity = 1)
        releaseHeld(c, replay)
    }

    /** In replay, P's release after green/rollback is re-run at that record, in queue order. */
    private fun replayRelease(c: C) {
        val q = c.s.parked
        c.s = c.s.copy(parked = emptyList())
        for (f in q) throughX(c, f, replay = true)
    }

    /** `O` restores a logged output (once) and the lane counter it advanced. */
    private fun restoreOutput(c: C, o: POut) {
        if (o in c.s.retained) return
        c.s = c.s.copy(retained = c.s.retained + o, outSeq = if (o.epoch == c.s.epoch) maxOf(c.s.outSeq, o.seq) else c.s.outSeq)
    }

    /** PLP §5.8: a lane counter is restored as at least the highest logged output `seq`. */
    private fun loggedMax(tail: List<PRec>, epoch: Int) =
        tail.filterIsInstance<POutRec>().filter { it.out.epoch == epoch }.maxOfOrNull { it.out.seq } ?: 0

    private fun recover(c: C) {
        val tail = c.s.tail
        val recorded = tail.filterIsInstance<PXRec>().maxOfOrNull { it.seq } ?: 0
        // O's logged outputs are retained again, in log order, and advance their lane counter,
        // before anything is re-derived.
        c.s = c.s.copy(
            disposed = recorded, epoch = 1, outSeq = loggedMax(tail, 1), incEpoch = 1, incOutSeq = 0, leafIdentity = 1, phase = Phase.NONE,
            parked = emptyList(), held = emptyList(), retained = tail.filterIsInstance<POutRec>().map { it.out }, outWire = emptyList(), pendingX = null,
        )
        var committed = false
        for (rec in tail) when (rec) {
            is PAcc -> accept(c, rec.f, replay = true)
            PPrepare -> c.s = c.s.copy(phase = Phase.PREPARED)
            is PCommit -> { committed = true; commit(c, rec.epoch, replay = true) }
            PGreen -> { c.s = c.s.copy(phase = Phase.GREEN); replayRelease(c); releaseHeld(c, true) }
            PRetire -> c.s = c.s.copy(phase = Phase.NONE)
            PRollback -> { rollback(c, replay = true); replayRelease(c) }
            is PXRec -> {}
            is POutRec -> restoreOutput(c, rec.out)
        }
        // Without a COMMIT record a prepared swap rolls back.
        if (c.s.phase == Phase.PREPARED && !committed) { rollback(c, replay = false); replayRelease(c) }
        c.s = c.s.copy(outWire = c.s.retained)
    }

    override fun next(s: PromSt): List<Transition<PromSt>> {
        val out = ArrayList<Transition<PromSt>>()
        fun step(l: String, progress: Boolean = true, f: C.() -> Unit) {
            val c = C(s); c.f()
            // Reduction: with no crash or reconnect left, no acknowledgement can be lost any
            // more, so its arrival time is unobservable; deliver it at once (as CellModel does).
            if (c.s.crash == 0 && c.s.reconnect == 0 && c.s.upAcks.isNotEmpty()) {
                c.s = c.s.copy(upRetained = c.s.upRetained - c.s.upAcks.toSet(), upAcks = emptyList())
            }
            out.add(Transition(l, c.s, progress))
        }
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
            if (!pGated(s) && s.parked.isNotEmpty()) step("P releases ${s.parked.first()} through X to the ${if (s.phase == Phase.GREEN || s.leafIdentity != 1 || s.epoch != 1) "candidate" else "leaf"}") {
                val f = this.s.parked.first(); this.s = this.s.copy(parked = this.s.parked.drop(1)); throughX(this, f, false)
            }
            if (s.promote > 0 && s.phase == Phase.NONE) step("PRECHECK (side-effect free)", false) {
                this.s = this.s.copy(promote = 0, phase = Phase.PRECHECKED)
            }
            if (s.phase == Phase.PRECHECKED) step("PREPARE (logged; ${if (variant.swapHoldsBelowX) "Swap holds inbound below X" else "P parks inbound"})") {
                this.s = this.s.copy(phase = Phase.PREPARED, tail = if (variant.unloggedWindow) s.tail else s.tail + PPrepare)
            }
            if (s.phase == Phase.PREPARED) step("COMMIT (T${variant.tier}, candidate identity ${variant.candidateIdentity})") {
                val e = this.s.mint; this.s = this.s.copy(mint = e + 1); commit(this, e, false)
            }
            if (s.phase == Phase.COMMITTED) step("green (logged): ${if (variant.swapHoldsBelowX) "Swap releases held ${s.held}" else "P resumes"}") {
                this.s = this.s.copy(phase = Phase.GREEN, tail = if (variant.unloggedWindow) this.s.tail else this.s.tail + PGreen)
                releaseHeld(this, false)
            }
            if (s.phase == Phase.GREEN) step("RETIRE (logged): S removes Swap, incumbent destroyed") {
                this.s = this.s.copy(phase = Phase.NONE, tail = if (variant.unloggedWindow) this.s.tail else this.s.tail + PRetire)
            }
            val mayRollback = s.phase == Phase.PREPARED || (s.phase == Phase.COMMITTED && variant.rollbackAfterCommit)
            if (s.rollback > 0 && mayRollback) {
                step("ROLLBACK from ${s.phase} (logged; P resumes toward the incumbent)", false) {
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
        val outputs = (s.retained + s.outWire).filter { !it.rebaseline }.map { it.payload }.toSet() + s.rTaken
        for (f in script) {
            val disposed = if (acts) (perSeq[f.seq] ?: 0) > 0 else f.seq in outputs || f.seq <= s.disposed
            if (disposed && emits) {
                // ...and its output: taken, retained, or on the wire.
                if (f.seq !in outputs) v.add("I3 output for input ${f.seq} lost downstream (R taken ${s.rTaken}, dead ${s.rDead})")
                continue
            }
            if (disposed) continue
            if (f !in s.parked && f !in s.held && f !in s.upRetained && f !in s.upWire) v.add("I3 silent loss of input $f")
        }
        return v
    }

    override fun quiescent(s: PromSt): List<String> {
        val outputs = s.rTaken.toSet()
        return script.filter { f -> (acts && s.acts.keys.none { it.second == f.seq }) || (emits && f.seq !in outputs) }
            .map { "input $it never ${if (acts) "acted" else "relayed"}${if (emits) " / its output never taken" else ""}" }
    }
}
