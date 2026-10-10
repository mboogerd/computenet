package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * The partition-set repartition flip: COH §3.2 (records R1-R7, steps 1-9, the recovery table)
 * and PLP §5.6 (`p_begin`, in-band `FlipFence`/`FlipDrained` as barriers over dispositions,
 * scoped cursors `stable`/`R` at both shards, the range-handoff record the gainer does not act
 * on) and §5.8 (acknowledgement levels, `O` retention until `STABLE`). Modelled under M6's
 * recommended option (c): the router of a set with `Effectful` shards is a `Durable` term with
 * its own `O`.
 *
 * Configuration: one client lane into the router; keys `r` (moving range R, A → B), `a`
 * (A's stable range) and `b` (B's stable range). Both shards are `Effectful` with `D` and `X`;
 * each acts once per (seq, key). Client frames: f1 = {r,a}, f2 = {r,b}, f3 = {r}, so a boundary
 * frame is split across a stable and a moving range on both shards (PLP §5.6 step 4).
 *
 * **The router-to-shard hand-off, step by step** (each a separate transition, so a crash of
 * either party falls between any two):
 * 1. *send*: the router's `O` appends the item to its retention (durable; for a released R-slice
 *    that retention is its R2 record) and puts it on the wire;
 * 2. *accept*: the shard appends it to its `D` stream (`APPENDED`, not yet synced);
 * 3. *sync*: the shard's stream syncs; each newly `STABLE` data item is acknowledged `STABLE`;
 * 4. *ack*: the router receives the `STABLE` ack; `O` drops the item, which for a released
 *    R-slice is the R6 advance (R6 = released slices no longer retained);
 * 5. *dispose*: the shard processes its log in order (act → `X` record, the [24-DUR-09] window;
 *    duplicates dropped against the slice's scope); `FlipFence`/`FlipDrained` are processed only
 *    when everything ahead of them is processed with a `STABLE` disposition, write
 *    `SettledFor`/`DrainedFor`, and answer;
 * 6. *drain ack and R7*: the router logs R7 only when both shards have answered `FlipDrained`.
 *
 * Faults: a router crash loses everything volatile on both directions of its links and
 * re-creates it from its records (R1-R7, `O` retention); in the [FlipVariant.volatileRouter]
 * control, from nothing (computenet-d2lue). A shard crash loses its unsynced stream tail, its
 * inbound wire and its unsent answers, replays its synced stream (own log first, PLP I-P2) and
 * ends the link session, so the router's `O` resends that shard's retention in order. Abort
 * may be decided at any point before the decision.
 */
data class FlipVariant(
    /** Control computenet-d2lue: flip state and router retention are volatile. */
    val volatileRouter: Boolean = false,
    /** Control: B's `R` scope starts at A's high-water instead of `p_begin` (PLP §5.6 step 5). */
    val gainerCursorFromLoserHighWater: Boolean = false,
    /** Control: shards keep one unscoped cursor (no scopes, PLP §5.6 step 4). */
    val unscoped: Boolean = false,
    /** Control computenet-8g7kg: the moved-in state is a catch-up B acts on. */
    val handoffActs: Boolean = false,
    /**
     * Channel for PREPARE/COMMIT/ABORT/SHED/UNSETTLE. `true` (default): a separate asynchronous
     * channel (a remote shard: `assignInlet` and `routeInlet` are different lanes, so nothing
     * orders control against data). `false`: management band, applied before any later data.
     */
    val asyncControl: Boolean = true,
    /** COH §3.2 step 7 (review finding B2): release only once B has acknowledged `Committed`. */
    val releaseAfterCommittedAck: Boolean = true,
    /**
     * Control, the `299d4e9c` text of step 8 (model finding FLIP-1): on ABORT, release parked
     * frames to A at once, and send no fence after the decision.
     */
    val abortReleasesBeforeSettled: Boolean = false,
    /** Control (review blocker 1): `O` drops a released slice and R6 advances when it is SENT. */
    val releaseCursorOnSend: Boolean = false,
    /**
     * Shard streams are `BATCHED`: acceptance appends (`APPENDED`), a separate sync makes the
     * prefix `STABLE` and only then acknowledges it, and a crash loses the unsynced tail.
     * Default `false`: `SYNCHRONOUS` (`APPENDED = STABLE`, PLP §5.8), so acceptance is the sync.
     */
    val batched: Boolean = false,
    /**
     * Act and X record are separate steps (the [24-DUR-09] window). Default `false` for the
     * flip: the window's re-act ceiling is the cell model's subject, and fusing the two steps
     * keeps the flip's exhaustive runs in reach. A crash inside the window can only re-act
     * (within the ceiling), never lose a slice.
     */
    val actWindow: Boolean = false,
)

data class Slice(val seq: Int, val keys: Set<Char>) { override fun toString() = "f$seq$keys" }

/** Items on a router-to-shard path (one FIFO lane per shard). */
sealed interface Item
data class IData(val slice: Slice, val released: Boolean = false) : Item {
    override fun toString() = if (released) "released($slice)" else "data($slice)"
}
data class IFence(val pBegin: Int) : Item { override fun toString() = "FlipFence(p_begin=$pBegin)" }
data object IDrained : Item { override fun toString() = "FlipDrained" }

/** Answers on a shard-to-router path. */
sealed interface Reply
data class AStable(val item: IData) : Reply { override fun toString() = "STABLE ack $item" }
data object ASettled : Reply { override fun toString() = "FlipSettled" }
data object ADrained : Reply { override fun toString() = "DrainedFor ack" }
data object APrepared : Reply { override fun toString() = "Prepared" }
data object ACommitted : Reply { override fun toString() = "Committed" }

sealed interface Ctrl
data class CPrepare(val pBegin: Int, val loserHw: Int, val handoff: Set<Int>) : Ctrl { override fun toString() = "PREPARE" }
data object CCommit : Ctrl { override fun toString() = "COMMIT" }
data object CAbort : Ctrl { override fun toString() = "ABORT" }
data object CShed : Ctrl { override fun toString() = "SHED" }
data object CUnsettle : Ctrl { override fun toString() = "UNSETTLE" }

/** `X`'s cursors: unscoped, or (while a flip is open at this shard) per scope. */
data class Cursors(val cur: Int = 0, val r: Int? = null, val stable: Int? = null) {
    val highWater: Int get() = maxOf(cur, stable ?: 0)
}

data class ShardSt(
    /** Inbound wire (volatile). */
    val wire: List<Item> = emptyList(),
    /** `D` stream of accepted items; the first [synced] are `STABLE`. */
    val log: List<Item> = emptyList(),
    val synced: Int = 0,
    /** Next log index to dispose of (volatile; recovery replays the whole synced log). */
    val proc: Int = 0,
    val mem: Cursors = Cursors(),
    /** Cursors as of the last `X`/`SettledFor`/`DrainedFor`/`Committed` record. */
    val durable: Cursors = Cursors(),
    /** Acted (seq, key) whose X record is not yet written: the [24-DUR-09] window. */
    val pending: List<Pair<Int, Char>> = emptyList(),
    // durable records
    val settledFor: Boolean = false,
    val drainedFor: Boolean = false,
    val prepared: CPrepare? = null,
    val committed: Boolean = false,
    // volatile
    val ctlIn: List<Ctrl> = emptyList(),
    val replies: List<Reply> = emptyList(),
)

data class RouterSt(
    val routed: Int = 0,
    // durable: records R1-R7 and O's retention (lost on crash only in the volatile control)
    val begun: Boolean = false,
    val pBegin: Int = 0,
    val parked: List<Slice> = emptyList(),
    val settled: Boolean = false,
    val decision: Char? = null,
    /** Parked slices handed to `O` for release, in order. */
    val releasedSent: Int = 0,
    val drainSent: Boolean = false,
    val drainAcks: Set<Char> = emptySet(),
    val ended: Boolean = false,
    val rOwner: Char = 'A',
    /** `O`'s retention per shard path, in send order: data until `STABLE`, markers until answered. */
    val outA: List<Item> = emptyList(),
    val outB: List<Item> = emptyList(),
    // volatile
    val preparedAck: Boolean = false,
    val committedAck: Boolean = false,
    val prepareSent: Boolean = false,
    val toBSent: Boolean = false,
    val toASent: Boolean = false,
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

class FlipModel(
    val variant: FlipVariant = FlipVariant(),
    val routerCrashes: Int = 1,
    val shardCrashes: Int = 1,
    /** Client frames, a prefix of f1 = {r,a}, f2 = {r,b}, f3 = {r}. */
    val frames: Int = 3,
) : Spec<FlipSt> {
    override val name = "flip[$variant,frames=$frames,router×$routerCrashes,shard×$shardCrashes]"

    val client: List<Slice> = listOf(Slice(1, setOf('r', 'a')), Slice(2, setOf('r', 'b')), Slice(3, setOf('r'))).take(frames)

    override fun initial() = FlipSt(routerCrash = routerCrashes, shardCrash = shardCrashes)

    private fun owner(k: Char, rOwner: Char) = when (k) { 'a' -> 'A'; 'b' -> 'B'; else -> rOwner }

    private fun FlipSt.shard(id: Char) = if (id == 'A') a else b
    private fun FlipSt.withShard(id: Char, s: ShardSt) = if (id == 'A') copy(a = s) else copy(b = s)
    private fun RouterSt.out(id: Char) = if (id == 'A') outA else outB
    private fun RouterSt.withOut(id: Char, o: List<Item>) = if (id == 'A') copy(outA = o) else copy(outB = o)

    /** Router send: retained in `O` (unless [retain] is false), then on the wire. */
    private fun send(st: FlipSt, to: Char, item: Item, retain: Boolean = true): FlipSt {
        val r = if (retain) st.router.withOut(to, st.router.out(to) + item) else st.router
        val sh = st.shard(to)
        return st.copy(router = r).withShard(to, sh.copy(wire = sh.wire + item))
    }

    /** Control delivery: management band (applied now) or async channel. */
    private fun sendCtl(st: FlipSt, to: Char, c: Ctrl): FlipSt =
        if (variant.asyncControl) st.withShard(to, st.shard(to).let { it.copy(ctlIn = it.ctlIn + c) })
        else applyCtl(st, to, c)

    private fun reply(st: FlipSt, from: Char, a: Reply) = st.withShard(from, st.shard(from).let { it.copy(replies = it.replies + a) })

    private fun applyCtl(st: FlipSt, to: Char, c: Ctrl): FlipSt {
        val s = st.shard(to)
        return when (c) {
            is CPrepare -> {
                // B logs Prepared(tx) and stages; it does not act (COH §3.2 step 4). Idempotent.
                reply(st.withShard(to, if (s.prepared == null) s.copy(prepared = c) else s), to, APrepared)
            }
            CCommit -> {
                if (s.committed) return reply(st, to, ACommitted)
                val p = s.prepared ?: return st // unknown tx: nothing staged
                val rStart = if (variant.gainerCursorFromLoserHighWater) p.loserHw else p.pBegin
                fun scoped(c0: Cursors) = if (variant.unscoped) c0 else Cursors(c0.cur, r = rStart, stable = c0.cur)
                var nst = st.withShard(to, s.copy(committed = true, mem = scoped(s.mem), durable = scoped(s.durable)))
                if (variant.handoffActs) for (seq in p.handoff) nst = act(nst, seq, 'r') // 8g7kg
                reply(nst, to, ACommitted)
            }
            CAbort -> st.withShard(to, s.copy(prepared = null))
            CShed, CUnsettle -> st
        }
    }

    private fun act(st: FlipSt, seq: Int, k: Char): FlipSt {
        val key = seq to k
        return st.copy(acts = st.acts + (key to (st.acts[key] ?: 0) + 1))
    }

    /** Newly synced data items get their STABLE acknowledgement. */
    private fun syncAll(sh: ShardSt): Pair<ShardSt, List<Reply>> {
        val acks = sh.log.subList(sh.synced, sh.log.size).filterIsInstance<IData>().map { AStable(it) }
        return sh.copy(synced = sh.log.size) to acks
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
            if (ks.isNotEmpty()) s = send(s, target, IData(Slice(sl.seq, ks)))
        }
        return s
    }

    /**
     * Dispose of one data item at a shard (live or replay). Returns the new state and whether it
     * acted. A slice lies wholly inside one scope (PLP §5.6 step 4).
     */
    private fun dispose(st: FlipSt, id: Char, sh: ShardSt, sl: Slice, replay: Boolean): FlipSt {
        val c = sh.mem
        val scopeR = sl.keys == setOf('r')
        val hw = when {
            c.r == null -> c.cur
            scopeR -> c.r
            else -> c.stable!!
        }
        if (sl.seq <= hw) return st.withShard(id, sh)
        var t = st
        for (k in sl.keys) t = act(t, sl.seq, k)
        val nc = when {
            c.r == null -> c.copy(cur = sl.seq)
            scopeR -> c.copy(r = sl.seq)
            else -> c.copy(stable = sl.seq)
        }
        // In replay (no fault during recovery), or with the window fused, the X record is written at once.
        if (replay || !variant.actWindow) {
            val (ns, acks) = syncAll(sh.copy(mem = nc, durable = nc))
            return t.withShard(id, if (replay) ns else ns.copy(replies = ns.replies + acks))
        }
        return t.withShard(id, sh.copy(mem = nc, pending = sl.keys.map { sl.seq to it }))
    }

    private fun settle(sh: ShardSt, pBegin: Int): ShardSt {
        if (sh.settledFor) return sh
        val c = if (variant.unscoped) sh.mem else Cursors(sh.mem.cur, r = pBegin, stable = sh.mem.cur)
        return sh.copy(settledFor = true, mem = c, durable = c)
    }

    private fun drain(sh: ShardSt): ShardSt {
        if (sh.drainedFor) return sh
        val c = sh.mem
        val folded = if (c.r == null) c else Cursors(maxOf(c.r, c.stable!!))
        return sh.copy(drainedFor = true, mem = folded, durable = folded)
    }

    override fun next(s: FlipSt): List<Transition<FlipSt>> {
        val out = ArrayList<Transition<FlipSt>>()
        fun add(l: String, t: FlipSt, progress: Boolean = true) = out.add(Transition(l, t, progress))
        val r = s.router

        // ---- shards ------------------------------------------------------------------
        for (id in listOf('A', 'B')) {
            val sh = s.shard(id)
            if (sh.pending.isNotEmpty()) {
                // The X record; writing it syncs the stream prefix (one stream per D scope).
                val (ns, acks) = syncAll(sh.copy(pending = emptyList(), durable = sh.mem))
                add("$id writes X record for ${sh.pending} (stream synced)", s.withShard(id, ns.copy(replies = ns.replies + acks)))
                continue
            }
            if (sh.wire.isNotEmpty()) {
                val acc = sh.copy(wire = sh.wire.drop(1), log = sh.log + sh.wire.first())
                if (variant.batched) add("$id accepts ${sh.wire.first()} (APPENDED)", s.withShard(id, acc))
                else {
                    val (ns, acks) = syncAll(acc)
                    add("$id accepts ${sh.wire.first()} (STABLE)", s.withShard(id, ns.copy(replies = ns.replies + acks)))
                }
            }
            if (sh.synced < sh.log.size) {
                val (ns, acks) = syncAll(sh)
                add("$id syncs its stream (STABLE through ${sh.log.size})", s.withShard(id, ns.copy(replies = ns.replies + acks)))
            }
            if (sh.ctlIn.isNotEmpty()) {
                add("$id receives control ${sh.ctlIn.first()}", applyCtl(s.withShard(id, sh.copy(ctlIn = sh.ctlIn.drop(1))), id, sh.ctlIn.first()))
            }
            if (sh.proc < sh.log.size) {
                val item = sh.log[sh.proc]
                val adv = sh.copy(proc = sh.proc + 1)
                when (item) {
                    is IData -> {
                        val t = dispose(s, id, adv, item.slice, replay = false)
                        val acted = t.acts != s.acts
                        add(if (acted) "$id acts on $item" else "$id drops $item as duplicate (cursor ${sh.mem})", t)
                    }
                    // Barrier over dispositions (PLP §5.8): everything ahead is disposed (proc is
                    // here, nothing pending) and STABLE.
                    is IFence -> if (sh.synced >= sh.proc) {
                        val (ns, acks) = syncAll(settle(adv, item.pBegin))
                        add("$id takes $item: logs SettledFor, answers FlipSettled", s.withShard(id, ns.copy(replies = ns.replies + acks + ASettled)))
                    }
                    IDrained -> if (sh.synced >= sh.proc) {
                        val (ns, acks) = syncAll(drain(adv))
                        add("$id takes FlipDrained: folds scopes, logs DrainedFor, acknowledges", s.withShard(id, ns.copy(replies = ns.replies + acks + ADrained)))
                    }
                }
            }
        }

        // ---- router: answers from the shards -----------------------------------------
        for (id in listOf('A', 'B')) {
            val sh = s.shard(id)
            val a = sh.replies.firstOrNull() ?: continue
            var t = s.withShard(id, sh.copy(replies = sh.replies.drop(1)))
            val rr = t.router
            t = when (a) {
                is AStable -> {
                    val o = rr.out(id)
                    val i = o.indexOf(a.item)
                    t.copy(router = if (i >= 0) rr.withOut(id, o.filterIndexed { j, _ -> j != i }) else rr)
                }
                ASettled -> t.copy(router = rr.copy(settled = rr.settled || (rr.begun && !rr.ended), outA = rr.outA.filter { it !is IFence }))
                ADrained -> {
                    val acks = rr.drainAcks + id
                    val nr = rr.copy(drainAcks = acks, outA = if (id == 'A') rr.outA.filter { it != IDrained } else rr.outA,
                        outB = if (id == 'B') rr.outB.filter { it != IDrained } else rr.outB)
                    if (acks.size == 2 && !rr.ended) {
                        // R7: both shards drained; R2 records truncated.
                        t.copy(router = nr.copy(ended = true, rOwner = if (rr.decision == 'C') 'B' else 'A', parked = emptyList(), releasedSent = 0))
                    } else t.copy(router = nr)
                }
                APrepared -> t.copy(router = rr.copy(preparedAck = rr.begun && !rr.ended && rr.decision == null || rr.preparedAck))
                ACommitted -> t.copy(router = rr.copy(committedAck = true))
            }
            add("router receives ${a} from $id${if (a is ADrained && r.drainAcks.size == 1 && id !in r.drainAcks) " -> R7 FlipEnd" else if (a == ASettled && !r.settled) " -> R3 Settled" else ""}", t)
        }

        // ---- router: protocol --------------------------------------------------------
        val releasing = r.decision != null && !r.ended
        if (r.routed < client.size && !releasing) {
            val sl = client[r.routed]
            add("router accepts and routes $sl", route(s.copy(router = r.copy(routed = r.routed + 1)), sl))
        }
        if (s.beginBudget > 0 && !r.begun) {
            add("router: R1 FlipBegin(p_begin=${r.routed})", s.copy(beginBudget = 0, router = r.copy(begun = true, pBegin = r.routed)), progress = false)
        }
        if (r.begun && !r.ended) {
            val fenceAllowed = r.decision == null || (r.decision == 'A' && !variant.abortReleasesBeforeSettled)
            if (!r.settled && fenceAllowed && r.outA.none { it is IFence }) {
                add("router sends in-band FlipFence(p_begin=${r.pBegin}) to A", send(s, 'A', IFence(r.pBegin)))
            }
            if (r.settled && !r.prepareSent && r.decision == null) {
                val handoff = s.acts.keys.filter { it.second == 'r' }.map { it.first }.toSet()
                add("router sends PREPARE(handoff) to B", sendCtl(s.copy(router = r.copy(prepareSent = true)), 'B', CPrepare(r.pBegin, s.a.mem.highWater, handoff)))
            }
            if (r.decision == null) {
                if (r.preparedAck) add("router: R5 FlipDecision(COMMIT)", s.copy(router = r.copy(decision = 'C')))
                add("router: R5 FlipDecision(ABORT)", s.copy(router = r.copy(decision = 'A')), progress = false)
            }
            if (r.decision != null) {
                val commit = r.decision == 'C'
                if (!r.toBSent) add("router sends ${if (commit) "COMMIT" else "ABORT"} to B", sendCtl(s.copy(router = r.copy(toBSent = true)), 'B', if (commit) CCommit else CAbort))
                if (!r.toASent) add("router sends ${if (commit) "SHED" else "UNSETTLE"} to A", sendCtl(s.copy(router = r.copy(toASent = true)), 'A', if (commit) CShed else CUnsettle))
            }
            val mayRelease = when (r.decision) {
                'C' -> r.toBSent && (!variant.releaseAfterCommittedAck || r.committedAck)
                'A' -> r.toBSent && (variant.abortReleasesBeforeSettled || r.settled)
                else -> false
            }
            val to = if (r.decision == 'C') 'B' else 'A'
            if (mayRelease && r.releasedSent < r.parked.size) {
                val sl = r.parked[r.releasedSent]
                add("router releases parked $sl to $to through O",
                    send(s.copy(router = r.copy(releasedSent = r.releasedSent + 1)), to, IData(sl, released = true), retain = !variant.releaseCursorOnSend))
            }
            val r6Complete = r.releasedSent == r.parked.size && r.out(to).none { it is IData && it.released }
            if (mayRelease && r6Complete && !r.drainSent) {
                add("router: R6 complete; sends FlipDrained to A and B", send(send(s.copy(router = r.copy(drainSent = true)), 'A', IDrained), 'B', IDrained))
            }
        }

        // ---- faults ------------------------------------------------------------------
        if (s.routerCrash > 0) {
            val nr = if (variant.volatileRouter) RouterSt(routed = r.routed) else r.copy(
                preparedAck = false, committedAck = false, prepareSent = false, toBSent = false, toASent = false,
            )
            // Both directions of the router's links lose what is in flight; O resends its
            // retention in order on the new sessions; control re-sent from the records.
            fun reset(sh: ShardSt, id: Char) = sh.copy(wire = nr.out(id), replies = emptyList(), ctlIn = emptyList())
            add("CRASH router (re-created from ${if (variant.volatileRouter) "nothing: flip state volatile" else "its D records and O retention"})",
                s.copy(router = nr, a = reset(s.a, 'A'), b = reset(s.b, 'B'), routerCrash = s.routerCrash - 1), progress = false)
        }
        if (s.shardCrash > 0) for (id in listOf('A', 'B')) {
            val sh = s.shard(id)
            var ceil = s.ceiling
            for (k in sh.pending) ceil = ceil + (k to (ceil[k] ?: 0) + 1)
            // Unsynced tail, inbound wire, unsent answers and in-flight control are lost.
            val kept = sh.log.take(sh.synced)
            var t = s.copy(ceiling = ceil, shardCrash = s.shardCrash - 1)
            var ns = sh.copy(wire = emptyList(), log = kept, synced = kept.size, proc = 0, pending = emptyList(), mem = sh.durable, ctlIn = emptyList(), replies = emptyList())
            // Own log first: replay the synced stream against the recorded cursors.
            for (item in kept) {
                when (item) {
                    is IData -> { t = dispose(t, id, ns, item.slice, replay = true); ns = t.shard(id) }
                    is IFence -> ns = settle(ns, item.pBegin)
                    IDrained -> ns = drain(ns)
                }
            }
            t = t.withShard(id, ns.copy(proc = kept.size))
            // The session ends: the router's O resends this shard's retention in order, and the
            // router re-sends control it has no answer for.
            val rr = t.router
            t = t.withShard(id, t.shard(id).copy(wire = rr.out(id)))
            t = t.copy(router = if (id == 'B') rr.copy(prepareSent = rr.prepareSent && rr.preparedAck, toBSent = rr.toBSent && (rr.decision != 'C' || rr.committedAck)) else rr)
            add("CRASH shard $id (pending ${sh.pending}, unsynced ${sh.log.size - sh.synced} lost; replays ${kept.size}; router resends ${rr.out(id).size})", t, progress = false)
        }
        return out
    }

    override fun invariants(s: FlipSt): List<String> {
        val v = ArrayList<String>()
        for ((k, n) in s.acts) if (n > 1 + (s.ceiling[k] ?: 0)) v.add("I2 effect: (seq ${k.first}, key ${k.second}) acted $n times")
        val r = s.router
        for (sl in client.take(r.routed)) for (k in sl.keys) {
            val key = sl.seq to k
            if ((s.acts[key] ?: 0) > 0) continue
            fun has(i: Item) = i is IData && i.slice.seq == sl.seq && k in i.slice.keys
            val held = r.parked.drop(r.releasedSent).any { it.seq == sl.seq && k in it.keys } ||
                r.outA.any(::has) || r.outB.any(::has) ||
                listOf(s.a, s.b).any { sh -> sh.wire.any(::has) || sh.log.drop(sh.proc).any(::has) || sh.pending.contains(key) }
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
