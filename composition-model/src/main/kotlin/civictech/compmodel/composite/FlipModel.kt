package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * The partition-set repartition flip: COH §3.2 (records R1-R7, steps 1-9, the recovery table)
 * and PLP §5.6 (`p_begin`, in-band `FlipFence`/`FlipDrained`, scoped cursors `stable`/`R` at
 * both shards, the range-handoff record the gainer does not act on). Modelled under M6's
 * recommended option (c): the router of a set with `Effectful` shards is a `Durable` term.
 *
 * Configuration: one client lane into the router; keys `r` (moving range R, A → B), `a`
 * (A's stable range) and `b` (B's stable range). Both shards are `Effectful` with `D` and
 * `X`; each acts once per (seq, key). Client frames: f1 = {r,a}, f2 = {r,b}, f3 = {r} — so a
 * boundary frame is split across a stable and a moving range on both shards (PLP §5.6 step 4).
 *
 * Faults: router crash (re-created with its `D` records; or, in the [FlipVariant.volatileRouter]
 * control, with nothing — computenet-d2lue), shard crash inside the act → X-record window,
 * and abort at any point before the decision. Every pair of steps the note lists is
 * separated by a fault point because faults are ordinary transitions.
 */
data class FlipVariant(
    /** Control computenet-d2lue: flip state (`flipping`, `flipBuffer`) is volatile. */
    val volatileRouter: Boolean = false,
    /** Control: B's `R` scope starts at A's high-water instead of `p_begin` (PLP §5.6 step 5). */
    val gainerCursorFromLoserHighWater: Boolean = false,
    /** Control: shards keep one unscoped cursor (no scopes, PLP §5.6 step 4). */
    val unscoped: Boolean = false,
    /** Control computenet-8g7kg: the moved-in state is a catch-up B acts on. */
    val handoffActs: Boolean = false,
    /**
     * Channel for PREPARE/COMMIT/ABORT/SHED/UNSETTLE. `true` (default): a separate
     * asynchronous channel (a remote shard: `assignInlet` and `routeInlet` are different
     * lanes, so nothing orders control against data). `false`: management band, applied
     * before any later data on that shard.
     */
    val asyncControl: Boolean = true,
    /**
     * COH §3.2 step 7 at 299d4e9c (review finding B2): release parked frames only "once B has
     * acknowledged Committed(tx)". `false` encodes the 5820e5c1 text, which released right
     * after sending COMMIT.
     */
    val releaseAfterCommittedAck: Boolean = true,
    /**
     * Fix candidate for model finding FLIP-1 (not in the note at 299d4e9c): on ABORT the router
     * first places the in-band FlipFence on A's path if A has not settled, so A has an `R` scope
     * at `p_begin` before any parked R-slice is released to it.
     */
    val abortFencesFirst: Boolean = false,
)

data class Slice(val seq: Int, val keys: Set<Char>) { override fun toString() = "f$seq$keys" }

sealed interface ShardIn
data class SData(val slice: Slice) : ShardIn { override fun toString() = "data($slice)" }
data class SFence(val pBegin: Int) : ShardIn { override fun toString() = "FlipFence(p_begin=$pBegin)" }
data object SDrained : ShardIn { override fun toString() = "FlipDrained" }

sealed interface Ctrl
data class CPrepare(val pBegin: Int, val loserHw: Int, val handoff: Set<Int>) : Ctrl
data object CCommit : Ctrl
data object CAbort : Ctrl
data object CShed : Ctrl
data object CUnsettle : Ctrl

/** `X`'s cursors: unscoped, or (while a flip is open at this shard) per scope. */
data class Cursors(val cur: Int = 0, val r: Int? = null, val stable: Int? = null)

data class ShardSt(
    val queue: List<ShardIn> = emptyList(),
    val mem: Cursors = Cursors(),
    val durable: Cursors = Cursors(),
    /** Acted (seq, key) whose X record is not yet written: the [24-DUR-09] window. */
    val pending: List<Pair<Int, Char>> = emptyList(),
    val settledFor: Boolean = false,
    val prepared: CPrepare? = null,
    val committed: Boolean = false,
    val ctlIn: List<Ctrl> = emptyList(),
)

data class RouterSt(
    val routed: Int = 0,
    // durable records R1-R7 (lost on crash only in the volatile control)
    val begun: Boolean = false,
    val pBegin: Int = 0,
    val parked: List<Slice> = emptyList(),
    val settled: Boolean = false,
    val preparedAck: Boolean = false,
    val decision: Char? = null,
    val released: Int = 0,
    val ended: Boolean = false,
    val rOwner: Char = 'A',
    // volatile
    val fenceSent: Boolean = false,
    val prepareSent: Boolean = false,
    val decisionSent: Boolean = false,
    val drainedSent: Boolean = false,
    val committedAck: Boolean = false,
    val inbox: Set<String> = emptySet(),
)

data class FlipSt(
    val router: RouterSt = RouterSt(),
    val a: ShardSt = ShardSt(),
    val b: ShardSt = ShardSt(),
    val acts: Map<Pair<Int, Char>, Int> = emptyMap(),
    val ceiling: Map<Pair<Int, Char>, Int> = emptyMap(),
    val routerCrash: Int,
    val shardCrash: Int,
    val beginBudget: Int = 1,
)

class FlipModel(val variant: FlipVariant = FlipVariant(), val routerCrashes: Int = 1, val shardCrashes: Int = 1) : Spec<FlipSt> {
    override val name = "flip[$variant]"

    val client: List<Slice> = listOf(Slice(1, setOf('r', 'a')), Slice(2, setOf('r', 'b')), Slice(3, setOf('r')))

    override fun initial() = FlipSt(routerCrash = routerCrashes, shardCrash = shardCrashes)

    private fun owner(k: Char, rOwner: Char) = when (k) { 'a' -> 'A'; 'b' -> 'B'; else -> rOwner }

    private fun FlipSt.shard(id: Char) = if (id == 'A') a else b
    private fun FlipSt.withShard(id: Char, s: ShardSt) = if (id == 'A') copy(a = s) else copy(b = s)

    private fun deliverData(st: FlipSt, to: Char, sl: Slice): FlipSt =
        st.withShard(to, st.shard(to).let { it.copy(queue = it.queue + SData(sl)) })

    /** Control delivery: management band (applied now) or async channel. */
    private fun sendCtl(st: FlipSt, to: Char, c: Ctrl): FlipSt =
        if (variant.asyncControl) st.withShard(to, st.shard(to).let { it.copy(ctlIn = it.ctlIn + c) })
        else applyCtl(st, to, c)

    private fun applyCtl(st: FlipSt, to: Char, c: Ctrl): FlipSt {
        val s = st.shard(to)
        val r = st.router
        return when (c) {
            is CPrepare -> {
                // B logs Prepared(tx) and stages; it does not act (COH §3.2 step 4). Idempotent.
                val ns = if (s.prepared == null) s.copy(prepared = c) else s
                st.withShard(to, ns).copy(router = r.copy(inbox = r.inbox + "Prepared"))
            }
            CCommit -> {
                if (s.committed) return st.copy(router = r.copy(inbox = r.inbox + "Committed"))
                val p = s.prepared ?: return st // unknown tx: nothing staged
                val rStart = if (variant.gainerCursorFromLoserHighWater) p.loserHw else p.pBegin
                fun scoped(c0: Cursors) = if (variant.unscoped) c0 else Cursors(c0.cur, r = rStart, stable = c0.cur)
                var nst = st.withShard(to, s.copy(committed = true, mem = scoped(s.mem), durable = scoped(s.durable)))
                if (variant.handoffActs) {
                    // 8g7kg: the moved-in state arrives as a catch-up that an Effectful shard fires.
                    for (seq in p.handoff) nst = act(nst, seq, 'r')
                }
                nst.copy(router = nst.router.copy(inbox = nst.router.inbox + "Committed"))
            }
            CAbort -> st.withShard(to, s.copy(prepared = null))
            CShed, CUnsettle -> st
        }
    }

    private fun act(st: FlipSt, seq: Int, k: Char): FlipSt {
        val key = seq to k
        return st.copy(acts = st.acts + (key to (st.acts[key] ?: 0) + 1))
    }

    private fun route(st: FlipSt, sl: Slice): FlipSt {
        var s = st
        val r = s.router
        val open = r.begun && !r.ended
        val moving = if (open) sl.keys.filter { it == 'r' }.toSet() else emptySet()
        if (moving.isNotEmpty()) s = s.copy(router = s.router.copy(parked = s.router.parked + Slice(sl.seq, moving)))
        val rest = sl.keys - moving
        for (target in listOf('A', 'B')) {
            val ks = rest.filter { owner(it, r.rOwner) == target }.toSet()
            if (ks.isNotEmpty()) s = deliverData(s, target, Slice(sl.seq, ks))
        }
        return s
    }

    override fun next(s: FlipSt): List<Transition<FlipSt>> {
        val out = ArrayList<Transition<FlipSt>>()
        fun add(l: String, t: FlipSt, progress: Boolean = true) = out.add(Transition(l, t, progress))
        val r = s.router

        // Shards: a pending X record blocks that shard (the window).
        for (id in listOf('A', 'B')) {
            val sh = s.shard(id)
            if (sh.pending.isNotEmpty()) {
                add("$id writes X record for ${sh.pending}", s.withShard(id, sh.copy(pending = emptyList(), durable = sh.mem, queue = sh.queue.drop(1))))
                continue
            }
            if (sh.ctlIn.isNotEmpty()) {
                add("$id receives control ${sh.ctlIn.first()}", applyCtl(s.withShard(id, sh.copy(ctlIn = sh.ctlIn.drop(1))), id, sh.ctlIn.first()))
            }
            val head = sh.queue.firstOrNull() ?: continue
            when (head) {
                is SData -> {
                    val sl = head.slice
                    val c = sh.mem
                    val scopeR = sl.keys == setOf('r')
                    val hw = when {
                        c.r == null -> c.cur
                        scopeR -> c.r
                        else -> c.stable!!
                    }
                    if (sl.seq <= hw) {
                        add("$id drops $sl as duplicate (cursor $hw)", s.withShard(id, sh.copy(queue = sh.queue.drop(1))))
                    } else {
                        var t = s
                        for (k in sl.keys) t = act(t, sl.seq, k)
                        val nc = when {
                            c.r == null -> c.copy(cur = sl.seq)
                            scopeR -> c.copy(r = sl.seq)
                            else -> c.copy(stable = sl.seq)
                        }
                        add("$id acts on $sl", t.withShard(id, sh.copy(mem = nc, pending = sl.keys.map { sl.seq to it })))
                    }
                }
                is SFence -> {
                    // A freezes its R scope at p_begin and answers once everything ahead is STABLE.
                    var ns = sh.copy(queue = sh.queue.drop(1))
                    if (!sh.settledFor) {
                        val c = if (variant.unscoped) sh.mem else Cursors(sh.mem.cur, r = head.pBegin, stable = sh.mem.cur)
                        ns = ns.copy(settledFor = true, mem = c, durable = c)
                    }
                    add("$id takes $head, logs SettledFor, answers FlipSettled", s.withShard(id, ns).copy(router = r.copy(inbox = r.inbox + "Settled")))
                }
                SDrained -> {
                    val c = sh.mem
                    val folded = if (c.r == null) c else Cursors(maxOf(c.r, c.stable!!))
                    add("$id takes FlipDrained, folds scopes", s.withShard(id, sh.copy(queue = sh.queue.drop(1), mem = folded, durable = folded)))
                }
            }
        }

        // Router: accept the next client frame (not while a decided flip is releasing).
        val releasing = r.decision != null && !r.ended
        if (r.routed < client.size && !releasing) {
            val sl = client[r.routed]
            add("router accepts and routes $sl", route(s.copy(router = r.copy(routed = r.routed + 1)), sl))
        }
        if (s.beginBudget > 0 && !r.begun) {
            add("router: R1 FlipBegin(p_begin=${r.routed})", s.copy(beginBudget = 0, router = r.copy(begun = true, pBegin = r.routed)), progress = false)
        }
        if (r.begun && !r.ended) {
            if (!r.fenceSent && !r.settled && r.decision == null) {
                add("router sends in-band FlipFence(p_begin=${r.pBegin}) to A", s.copy(router = r.copy(fenceSent = true)).let {
                    it.copy(a = it.a.copy(queue = it.a.queue + SFence(r.pBegin)))
                })
            }
            if ("Settled" in r.inbox && !r.settled) add("router: R3 Settled", s.copy(router = r.copy(settled = true)))
            if (r.settled && !r.prepareSent && r.decision == null) {
                val handoff = s.acts.keys.filter { it.second == 'r' }.map { it.first }.toSet()
                add("router sends PREPARE(handoff) to B", sendCtl(s.copy(router = r.copy(prepareSent = true)), 'B', CPrepare(r.pBegin, s.a.mem.cur, handoff)))
            }
            if ("Prepared" in r.inbox && !r.preparedAck) add("router learns R4 Prepared", s.copy(router = r.copy(preparedAck = true)))
            if (r.decision == null) {
                if (r.preparedAck) add("router: R5 FlipDecision(COMMIT)", s.copy(router = r.copy(decision = 'C')))
                add("router: R5 FlipDecision(ABORT)", s.copy(router = r.copy(decision = 'A')), progress = false)
            }
            if (r.decision != null && !r.decisionSent) {
                var t = s.copy(router = r.copy(decisionSent = true))
                t = if (r.decision == 'C') sendCtl(sendCtl(t, 'B', CCommit), 'A', CShed) else sendCtl(sendCtl(t, 'B', CAbort), 'A', CUnsettle)
                if (r.decision == 'A' && variant.abortFencesFirst && !r.settled) t = t.copy(a = t.a.copy(queue = t.a.queue + SFence(r.pBegin)))
                add("router sends ${if (r.decision == 'C') "COMMIT to B, SHED to A" else "ABORT to B, UNSETTLE to A"}", t)
            }
            if ("Committed" in r.inbox && !r.committedAck) add("router learns B Committed", s.copy(router = r.copy(committedAck = true)))
            val mayRelease = r.decisionSent && (r.decision == 'A' || !variant.releaseAfterCommittedAck || r.committedAck)
            if (mayRelease && r.released < r.parked.size) {
                val sl = r.parked[r.released]
                val to = if (r.decision == 'C') 'B' else 'A'
                add("router releases parked $sl to $to (R6 ${r.released + 1})", deliverData(s.copy(router = r.copy(released = r.released + 1)), to, sl))
            }
            if (r.decisionSent && r.released == r.parked.size && !r.drainedSent) {
                val t = s.copy(
                    router = r.copy(drainedSent = true, ended = true, rOwner = if (r.decision == 'C') 'B' else 'A'),
                    a = s.a.copy(queue = s.a.queue + SDrained), b = s.b.copy(queue = s.b.queue + SDrained),
                )
                add("router sends FlipDrained to A and B, R7 FlipEnd", t)
            }
        }

        // Faults.
        if (s.routerCrash > 0) {
            val nr = if (variant.volatileRouter) RouterSt(routed = r.routed) else r.copy(
                fenceSent = false, prepareSent = false, decisionSent = false, drainedSent = r.ended, committedAck = false, inbox = emptySet(),
            )
            // In-flight replies to the router are lost; the router re-sends from its records.
            // Control already handed to a shard's channel is treated as reliably delivered.
            add("CRASH router (re-created from its D records${if (variant.volatileRouter) ": none, flip state volatile" else ""})",
                s.copy(router = nr, routerCrash = s.routerCrash - 1), progress = false)
        }
        if (s.shardCrash > 0) for (id in listOf('A', 'B')) {
            val sh = s.shard(id)
            var ceil = s.ceiling
            for (k in sh.pending) ceil = ceil + (k to (ceil[k] ?: 0) + 1)
            add("CRASH shard $id (pending ${sh.pending})", s.withShard(id, sh.copy(pending = emptyList(), mem = sh.durable))
                .copy(ceiling = ceil, shardCrash = s.shardCrash - 1), progress = false)
        }
        return out
    }

    override fun invariants(s: FlipSt): List<String> {
        val v = ArrayList<String>()
        for ((k, n) in s.acts) if (n > 1 + (s.ceiling[k] ?: 0)) v.add("I2 effect: (seq ${k.first}, key ${k.second}) acted $n times")
        for (sl in client.take(s.router.routed)) for (k in sl.keys) {
            val key = sl.seq to k
            if ((s.acts[key] ?: 0) > 0) continue
            val held = s.router.parked.drop(s.router.released).any { it.seq == sl.seq && k in it.keys } ||
                listOf(s.a, s.b).any { sh -> sh.queue.any { it is SData && it.slice.seq == sl.seq && k in it.slice.keys } }
            if (!held) v.add("I3 silent loss: (seq ${sl.seq}, key $k) accepted by the router is neither acted nor held")
        }
        return v
    }

    override fun quiescent(s: FlipSt): List<String> {
        val v = ArrayList<String>()
        for (sl in client) for (k in sl.keys) if ((s.acts[sl.seq to k] ?: 0) == 0) v.add("(seq ${sl.seq}, key $k) never acted")
        return v
    }
}
