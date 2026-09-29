package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.deliberate.ExplorationPolicy.Companion.FINISHED
import civictech.deliberate.ExplorationPolicy.Companion.SIDES
import civictech.deliberate.ExplorationPolicy.Companion.opposite
import java.util.concurrent.ExecutorService

/**
 * What [RoundProtocol] needs from [DeliberationEngine]: its lock-guarded
 * [state], its locks, its queue and the credence graph. [locked] runs a
 * block under the engine lock; [update] does too and then reports a change.
 */
internal interface RoundHost {
    val state: EngineState
    fun <T> locked(block: () -> T): T
    fun <T> update(block: () -> T): T
    /** EXP-08: runs a judge call bound to [c]'s question; on failure records the error and returns null. */
    fun <T> attempt(c: Claim, what: String, call: () -> T): T?
    /** SPEC §12: the sink bound around every call made for question [root]. */
    fun sinkFor(root: CellRef): UsageSink
    fun enqueue(c: Claim)
    fun schedule(c: Claim)
    /** Creates a claim cell for [text] and its edge onto [parent] on [side]; returns (claim ref, edge ref). */
    fun createArgument(text: String, parent: CellRef, side: Side): Pair<CellRef, CellRef>
    /** Sets (or, with null, clears) the `jev` stance of each ref. */
    fun setStances(vararg stances: Pair<CellRef, Double?>)
}

/**
 * One round of a claim (EXP-02..05) and what it sets off: the proposers'
 * turns, exact-text dedupe and Jev triage (EXP-03) with every verdict
 * applied — attaching arguments ([attach]), rewriting unexplored ones
 * (REPLACE, MERGE, under a rewrite reservation), recording evidence (REFINE),
 * and for a con against a well-believed claim, routing by what it denies
 * ([askBearing], model B) — then the attach-time
 * assessment of the new arguments ([assess]), the round's yield (EXP-10)
 * and saturation (EXP-04). The rules come from [policy]; everything the
 * round reads or writes of the engine's state goes through [host].
 */
internal class RoundProtocol(
    private val host: RoundHost,
    private val policy: ExplorationPolicy,
    private val judge: Judge,
    private val proposers: List<Proposer>,
    private val merger: Merger?,
    private val calls: ExecutorService,
) {
    private val config get() = policy.config
    private val state get() = host.state

    private companion object {
        fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';')
    }

    /** A proposal that survived exact-text dedupe; [also] are the other proposers of the same text. */
    private class Fresh(val text: String, val side: Side, val proposer: String) {
        val also = mutableListOf<String>()
    }

    private data class RewriteReservation(val created: Boolean, val wasQueued: Boolean)

    /** One round's working state (EXP-02..05, EXP-10), shared by its proposer turns. */
    private class Round(val claim: Claim, val sides: List<Side>, val forced: Boolean, cap: Int) {
        val counts = sortedMapOf<TriageAction, Int>()
        var dropped = 0
        val attached = mutableListOf<Claim>()
        val replaced = mutableListOf<Claim>()
        var budgetHit = false
        var asked = 0
        /** EXP-10: arguments asked for this round (the yield's denominator). */
        var requested = 0
        var failures = 0
        /** CTL-02: a forced round's own allowance, shared by its turns. */
        val allowance = SIDES.associateWith { cap }.toMutableMap()

        fun count(a: TriageAction) {
            counts.merge(a, 1, Int::plus)
        }
    }

    // ---------------------------------------------------------------- one round (EXP-02..05)

    /**
     * One round (EXP-02..05). The proposers take turns in their configured
     * order ([proposeTurn]); each turn sees the claim's arguments as they
     * stand after the previous turn's triage, so a later proposer is asked
     * for what is still missing. Returns a terminal status when the round
     * ends the expansion early (all calls failed, budget), else null
     * ([closeRound]). A [forced] round (CTL-02) ignores saturation and the
     * budget; its new arguments are bounded by an allowance of one per-side
     * cap per side instead.
     */
    fun run(c: Claim, sides: List<Side>, forced: Boolean): Status? {
        val r = Round(c, sides, forced, capOf(c))
        for (p in proposers) {
            if (r.budgetHit) break
            proposeTurn(r, p)
        }
        return closeRound(r)
    }

    /** EXP-02, EXP-03: one proposer's turn — its calls, exact-text dedupe, one triage request, every verdict applied. */
    private fun proposeTurn(r: Round, p: Proposer) {
        val c = r.claim
        val (ctx, existing) = host.locked {
            val pros = c.children.filter { it.side == Polarity.SUPPORT }
            val cons = c.children.filter { it.side == Polarity.ATTACK }
            state.contextOf(c) to (pros + cons)
        }
        // EXP-04: never attach beyond the cap within a round. Both sides, since OTHER_SIDE
        // may move an argument onto a side that was not asked this round.
        val room: MutableMap<Side, Int> = if (r.forced) r.allowance else SIDES.associateWith { side ->
            capOf(c) - existing.count { it.side == side }
        }.toMutableMap()
        val turnSides = r.sides.filter { room.getValue(it) > 0 }
        if (turnSides.isEmpty()) return
        val fresh = dedupe(r, p, existing, propose(r, p, ctx, turnSides, room))
        if (fresh.isEmpty()) return
        // EXP-03 step 2: one Jev triage request for this turn's remaining candidates.
        val verdicts = host.attempt(c, "triage") { judge.triage(ctx, fresh.map { Candidate(it.text, it.side) }) }
            ?.takeIf { it.size == fresh.size }
            ?: fresh.map { Triage(TriageAction.ADD) }
        val bearings = askBearing(c, ctx, fresh, verdicts)
        val resolved = arrayOfNulls<Claim>(fresh.size)
        for ((i, f) in fresh.withIndex()) {
            val v = verdicts[i]
            val indexed = v.target?.let { t -> if (t < existing.size) existing.getOrNull(t) else resolved.getOrNull(t - existing.size) }
            // Model B: a con that only denies the claim's bearing undercuts the claim's own link.
            val (asked, target) = when (bearings[i]) {
                Bearing.DENIES_BEARING -> TriageAction.UNDERCUT to c
                Bearing.NEITHER -> TriageAction.DROP to null
                Bearing.DISPUTES_CLAIM, null -> v.action to indexed
            }
            val action = resolveAction(c, asked, target, f)
            r.count(action)
            resolved[i] = applyVerdict(r, action, f, target, room)
        }
    }

    /**
     * Model B: asks [Judge.bearing] about the candidates [ExplorationPolicy.asksBearing]
     * selects — cons triage would ADD to [c], a well-believed claim with a link —
     * in one request. Returns the answers by candidate index; a candidate not
     * asked, or a failed call (error recorded on [c]), keeps its triage verdict.
     */
    private fun askBearing(c: Claim, ctx: ClaimContext, fresh: List<Fresh>, verdicts: List<Triage>): Map<Int, Bearing> {
        val (link, plausibility) = host.locked {
            val parent = c.parent?.takeIf { !c.isLink && c.link != null } ?: return@locked null
            LinkContext(c.text, parent.text, c.side!!) to c.plausibility
        } ?: return emptyMap()
        val asked = fresh.indices.filter { policy.asksBearing(plausibility, fresh[it].side, verdicts[it].action) }
        if (asked.isEmpty()) return emptyMap()
        val answers = host.attempt(c, "bearing") { judge.bearing(ctx, link, asked.map { fresh[it].text }) }
            ?.takeIf { it.size == asked.size }
            ?: return emptyMap()
        return asked.zip(answers).toMap()
    }

    /** EXP-02: [p] asked about every side in [turnSides] concurrently; a failed call records its error on the claim. */
    private fun propose(r: Round, p: Proposer, ctx: ClaimContext, turnSides: List<Side>, room: Map<Side, Int>): List<Pair<Side, List<String>>> {
        val c = r.claim
        val futures = turnSides.map { side ->
            val ask = minOf(config.argsPerCall, room.getValue(side))
            side to calls.submit<List<String>> { Usage.within(host.sinkFor(c.root)) { p.propose(ctx, side, ask).take(ask) } }
        }
        r.asked += futures.size
        r.requested += turnSides.sumOf { minOf(config.argsPerCall, room.getValue(it)) }
        return futures.mapNotNull { (side, f) ->
            try {
                side to f.get()
            } catch (e: Exception) {
                r.failures++
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                host.update { c.error = "${p.id}: $cause" }
                null
            }
        }
    }

    /** EXP-03 step 1: drops exact-text duplicates (of an existing argument or of an earlier proposal). */
    private fun dedupe(r: Round, p: Proposer, existing: List<Claim>, proposals: List<Pair<Side, List<String>>>): List<Fresh> {
        val byText = HashMap<String, Any>()
        existing.forEach { byText[normalize(it.text)] = it }
        val fresh = mutableListOf<Fresh>()
        for ((side, texts) in proposals) {
            for (t in texts.map(String::trim).filter(String::isNotEmpty)) {
                when (val hit = byText[normalize(t)]) {
                    is Claim -> { mergeProposers(hit, listOf(p.id)); r.dropped++; r.count(TriageAction.DUPLICATE) }
                    is Fresh -> { r.dropped++; r.count(TriageAction.DUPLICATE) }
                    else -> Fresh(t, side, p.id).also { fresh += it; byText[normalize(t)] = it }
                }
            }
        }
        return fresh
    }

    /**
     * EXP-03: what a triage verdict [action] on [f] comes to. A verdict that
     * needs a missing [target] is an ADD; REPLACE and MERGE rewrite [target]
     * here, and fall back to DUPLICATE when it cannot be rewritten.
     */
    private fun resolveAction(c: Claim, action: TriageAction, target: Claim?, f: Fresh): TriageAction = when (action) {
        TriageAction.REPLACE -> when {
            target == null -> TriageAction.ADD
            replace(target, f) -> TriageAction.REPLACE
            else -> TriageAction.DUPLICATE
        }
        TriageAction.MERGE -> when {
            target == null -> TriageAction.ADD
            merge(c, target, f) -> TriageAction.MERGE
            else -> TriageAction.DUPLICATE
        }
        TriageAction.REFINE -> if (target == null) TriageAction.ADD else TriageAction.REFINE
        // Only an argument (a claim with an edge) has a link to undercut.
        TriageAction.UNDERCUT -> if (target?.link == null) TriageAction.ADD else TriageAction.UNDERCUT
        else -> action
    }

    /**
     * EXP-03: applies a resolved [action] on [f]; returns the argument it now
     * stands for (a later candidate may target it), or null. Every survivor
     * that fits the budget and the cap is attached — even if STOP arrived
     * meanwhile (CTL-03). Survivors past the cap are not attached.
     */
    private fun applyVerdict(r: Round, action: TriageAction, f: Fresh, target: Claim?, room: MutableMap<Side, Int>): Claim? {
        val c = r.claim
        return when (action) {
            TriageAction.ADD, TriageAction.OTHER_SIDE -> if (action == TriageAction.OTHER_SIDE && c.isLink) {
                // A counter-argument generated while probing a link is not evidence that
                // the link holds or fails. Preserve it, but put it where it belongs:
                // against the parent claim (live-run correction to EXP-03/LINK-05).
                val parent = c.parent!!
                val fits = host.locked { fits(parent, Polarity.ATTACK, r.forced) }
                if (fits && room.getValue(f.side) > 0) {
                    add(r, parent, Polarity.ATTACK, f)?.also { room[f.side] = room.getValue(f.side) - 1 }
                } else null
            } else {
                val side = if (action == TriageAction.ADD) f.side else f.side.opposite
                if (room.getValue(side) > 0) add(r, c, side, f)?.also { room[side] = room.getValue(side) - 1 } else null
            }
            TriageAction.DUPLICATE -> {
                r.dropped++
                if (target != null) mergeProposers(target, listOf(f.proposer) + f.also)
                target
            }
            TriageAction.REPLACE, TriageAction.MERGE -> {
                // resolveAction() already rewrote the target
                r.replaced += target!!
                target
            }
            // Model B: evidence for the target is recorded on it, not attached as a child claim.
            TriageAction.REFINE -> {
                host.update {
                    val t = target!!
                    if (t.evidence.none { normalize(it) == normalize(f.text) }) t.evidence += f.text
                }
                target
            }
            // EXP-03 UNDERCUT: attached to the target's link, as one of its con arguments
            // (model B: the target may be the round's claim itself, see askBearing).
            TriageAction.UNDERCUT -> {
                val (link, fits) = host.locked { target!!.link!!.let { l -> l to fits(l, Polarity.ATTACK, r.forced) } }
                if (fits) add(r, link, Polarity.ATTACK, f) else target
            }
            TriageAction.DROP -> null
        }
    }

    /** Attaches [f] on [side] of [parent] if [r]'s question has budget left (EXP-06); else marks the round budget-hit. */
    private fun add(r: Round, parent: Claim, side: Side, f: Fresh): Claim? {
        if (r.budgetHit) return null
        if (!reserve(r.claim.root, r.forced)) {
            r.budgetHit = true
            return null
        }
        return attach(parent, side, f).also { r.attached += it }
    }

    /**
     * Ends a round once every proposer had its turn: counts it, assesses and
     * queues its new and reworded arguments, records its yield (EXP-10) and
     * re-judges saturation (EXP-04). Returns the status that ends the claim, if any.
     */
    private fun closeRound(r: Round): Status? {
        val c = r.claim
        val allProposersFailed = r.failures == r.asked && r.asked > 0
        val noProposerHasEverSucceeded = host.locked {
            if (!allProposersFailed && r.asked > 0) c.anyCallSucceeded = true
            !c.anyCallSucceeded
        }
        host.update {
            c.rounds++
            c.duplicatesDropped += r.dropped
            r.counts.forEach { (a, n) -> c.triage.merge(a, n, Int::plus) }
        }
        assessAndQueue(r.attached, r.replaced)
        recordYield(c, r.attached, r.counts, r.requested)
        judgeSaturation(c, r.sides)
        return when {
            r.budgetHit -> Status.BUDGET
            allProposersFailed && noProposerHasEverSucceeded -> Status.FAILED
            else -> null
        }
    }

    /**
     * CRED-01, CRED-02, EXP-05: every new (or reworded: REPLACE, MERGE) argument is assessed at once.
     * A rewrite may hit an argument attached in an earlier turn of this round, so assessment waits
     * for the last turn: an assessment of superseded wording must never land.
     */
    private fun assessAndQueue(attached: List<Claim>, replaced: List<Claim>) {
        val rewritten = replaced.distinct()
        val ready = (rewritten + attached).distinct()
        try {
            rewritten.forEach(::clearAssessment)
            assess(ready)
        } finally {
            // SPEC §3 "Exploration order": arguments compete with their parent's next
            // round immediately after assessment. Rewrite reservations are released
            // only now, so a stale task cannot explore wording with old judgments.
            // The finally also prevents an infrastructure failure from stranding a target.
            // A link joins the queue with its argument (SPEC §3 "Links as claims"), once the
            // argument's strength — the link's stance and uncertainty — is known.
            ready.flatMap { listOfNotNull(it, it.link) }.forEach { n ->
                val queueNow = host.update {
                    if (n.rewriteInFlight) {
                        n.rewriteInFlight = false
                        n.rewriteWasQueued = false
                    }
                    n.status == Status.QUEUED
                }
                if (queueNow) host.schedule(n)
            }
        }
    }

    /**
     * EXP-04: saturation per side, judged once every proposer had its turn
     * (a forced round's sides are re-judged too). A side at its cap needs no Jev call.
     */
    private fun judgeSaturation(c: Claim, sides: List<Side>) {
        val after = host.locked { state.contextOf(c) }
        for (side in sides) {
            if (host.locked { policy.atCap(c.view(), side) }) continue
            val p = host.attempt(c, "saturation") { judge.saturation(after, side) } ?: continue
            host.update {
                if (side == Polarity.SUPPORT) c.proSaturation = p else c.conSaturation = p
                if (policy.jevSaturates(p)) c.saturated += side else c.saturated -= side
            }
        }
    }

    /**
     * EXP-10: records the yield ([ExplorationPolicy.roundYield]) of [c]'s round
     * that attached [attached] out of [requested] asked-for arguments, triaged
     * as [counts]. Shown only: model C's value-of-information gate replaced the
     * yield stop.
     */
    private fun recordYield(c: Claim, attached: List<Claim>, counts: Map<TriageAction, Int>, requested: Int) {
        if (!policy.recordsYield(isRoot = c.parent == null, requested = requested)) return
        host.update {
            state.yields.getOrPut(c.root) { mutableListOf() } +=
                policy.roundYield(attached.map { AttachedValue(it.edge?.strength, it.relevance, it.quality) }, counts, requested)
        }
    }

    // ---------------------------------------------------------------- attaching and assessing

    /**
     * One Jev request per argument (plausibility, strength, quality, relevance),
     * all in parallel; then reach and contribution in creation order, so an
     * argument refining another one attached in the same round sees its reach.
     */
    fun assess(nodes: List<Claim>) {
        val futures = nodes.map { n ->
            val (question, path, text) = host.update {
                n.assessing = true
                // An argument about a link (an undercutter, a link supporter) is judged against the
                // link's text: the link is the last entry of its path (SPEC §3 "Links as claims").
                Triple(state.questions.getValue(n.root), n.path(), n.text)
            }
            n to calls.submit<Assessment?> { host.attempt(n, "assess") { judge.assess(question, path, text, n.side!!) } }
        }
        for ((n, f) in futures) {
            val a = try {
                f.get()
            } catch (e: Exception) {
                null
            }
            val edge = host.locked { n.edge!! }
            if (a != null) host.setStances(n.ref to a.plausibility, edge.ref to a.strength)
            host.update {
                n.assessing = false
                if (a != null) {
                    n.plausibility = a.plausibility
                    n.relevance = a.relevance
                    n.quality = a.quality
                    edge.strength = a.strength
                }
                val reach = policy.reachOf(n.parent!!.reach, edge.strength)
                n.reach = reach
                // Model B: the claim's own worth is damped by how settled its premise is;
                // its link is built from the undamped worth (the bearing is still open).
                val worth = policy.contribution(reach, n.relevance, n.quality)
                n.contribution = policy.contribution(reach, n.relevance, n.quality, n.plausibility)
                updateLink(n, worth)
            }
        }
    }

    /**
     * Caller holds the engine lock. SPEC §3 "Links as claims": [arg]'s link takes its reach
     * and [ExplorationPolicy.linkContribution] of [worth], the argument's contribution
     * without its plausibility factor.
     */
    private fun updateLink(arg: Claim, worth: Double?) {
        val l = arg.link ?: return
        l.reach = arg.reach
        l.contribution = policy.linkContribution(worth, arg.reach, arg.edge?.strength)
    }

    /**
     * Attaches [f] as an argument on [side] of [parent] — a claim, or a link
     * (then its edge targets the link's edge: SPEC §3 "Links as claims") —
     * together with the argument's own link.
     */
    private fun attach(parent: Claim, side: Side, f: Fresh): Claim {
        val (childRef, edgeRef) = host.createArgument(f.text, parent.ref, side)
        val child = Claim(childRef, parent.root, parent, side, f.text, parent.depth + 1, f.proposer, config.maxRounds)
        val edge = Edge(edgeRef, parent.root, childRef, parent.ref, side)
        host.update {
            child.edge = edge
            child.alsoProposedBy += f.also
            state.claims[childRef] = child
            state.edges[edgeRef] = edge
            parent.children += child
            state.linkFor(child, config.maxRounds)
        }
        return child
    }

    // ---------------------------------------------------------------- rewrites (EXP-03 REPLACE, MERGE)

    /**
     * Caller holds the engine lock. EXP-03 REPLACE only rewords an argument nobody has
     * explored yet — neither the argument nor its link (whose text quotes it).
     */
    private fun replaceable(t: Claim) = t.parent != null && t.status == Status.QUEUED &&
        t.children.isEmpty() && t.rounds == 0 &&
        t.link.let { l -> l == null || (l.children.isEmpty() && l.rounds == 0 && l.status !in setOf(Status.JUDGING, Status.EXPLORING)) }

    /** Reserve an unexplored queued target and invalidate any task carrying its old priority/text. */
    private fun beginRewrite(t: Claim): RewriteReservation? = host.update {
        if (!replaceable(t)) return@update null
        if (t.rewriteInFlight) return@update RewriteReservation(created = false, wasQueued = t.rewriteWasQueued)
        val wasQueued = t.queueGeneration > 0
        t.rewriteInFlight = true
        t.rewriteWasQueued = wasQueued
        t.queueGeneration++
        // Its link quotes the old wording: hold it too until the rewrite is assessed.
        t.link?.let { l ->
            l.rewriteInFlight = true
            l.queueGeneration++
        }
        RewriteReservation(created = true, wasQueued = wasQueued)
    }

    /** Undo a failed first rewrite reservation and restore the target's invalidated queue entry. */
    private fun cancelRewrite(t: Claim, reservation: RewriteReservation) {
        if (!reservation.created) return
        val (resume, link) = host.update {
            t.rewriteInFlight = false
            t.rewriteWasQueued = false
            val l = t.link?.also { it.rewriteInFlight = false }
            // Its link had been queued only once the argument was assessed.
            (reservation.wasQueued && t.status == Status.QUEUED) to l?.takeIf { t.contribution != null && it.status == Status.QUEUED }
        }
        if (resume) host.enqueue(t)
        link?.let(host::schedule)
    }

    /**
     * EXP-03 MERGE: rewrites [t] and the candidate as one sentence via the
     * [merger] and records the candidate's proposers on [t]. Returns false
     * (the caller falls back to DUPLICATE) when there is no merger, the call
     * failed (error recorded on [c]), or [t] got explored meanwhile.
     */
    private fun merge(c: Claim, t: Claim, f: Fresh): Boolean {
        val m = merger ?: return false
        val reservation = beginRewrite(t) ?: return false
        val (claim, current) = host.locked { c.text to t.text }
        val merged = try {
            Usage.within(host.sinkFor(c.root)) { m.merge(claim, t.side!!, current, f.text) }
        } catch (e: Exception) {
            host.update { c.error = "merge: $e" }
            cancelRewrite(t, reservation)
            return false
        }
        return host.update {
            (t.rewriteInFlight && replaceable(t)).also { ok ->
                if (ok) {
                    t.text = merged
                    t.link?.text = linkText(t)
                    t.merged = true
                    (listOf(f.proposer) + f.also).filter { it != t.proposer && it !in t.alsoProposedBy }.distinct()
                        .forEach { t.alsoProposedBy += it }
                }
            }
        }.also { if (!it) cancelRewrite(t, reservation) }
    }

    /** EXP-03 REPLACE: [t] takes the candidate's text and provenance; its old proposer is kept as `also`. */
    private fun replace(t: Claim, f: Fresh): Boolean {
        val reservation = beginRewrite(t) ?: return false
        return host.update {
            (t.rewriteInFlight && replaceable(t)).also { ok ->
                if (ok) {
                    val others = (listOf(t.proposer) + t.alsoProposedBy + f.also).filter { it != f.proposer }.distinct()
                    t.alsoProposedBy.clear()
                    t.alsoProposedBy += others
                    t.proposer = f.proposer
                    t.text = f.text
                    t.link?.text = linkText(t)
                }
            }
        }.also { if (!it) cancelRewrite(t, reservation) }
    }

    /** A rewritten sentence must never retain judgments made about its previous wording. */
    private fun clearAssessment(n: Claim) {
        val edge = host.locked { n.edge!! }
        host.update {
            n.plausibility = null
            n.relevance = null
            n.quality = null
            edge.strength = null
            val reach = policy.reachOf(n.parent!!.reach, edge.strength)
            n.reach = reach
            n.contribution = reach
            updateLink(n, reach)
            // The link is re-gated with the new wording's judgments (replaceable() kept it unexplored).
            n.link?.let { l -> if (l.status in FINISHED && l.override != Override.STOP) l.status = Status.QUEUED }
        }
        host.setStances(n.ref to null, edge.ref to null)
    }

    /** EXP-03 DUPLICATE: record the extra proposers on the argument that already makes the point. */
    private fun mergeProposers(t: Claim, ids: List<String>) = host.update {
        ids.filter { it != t.proposer && it !in t.alsoProposedBy }.distinct().forEach { t.alsoProposedBy += it }
    }

    /** EXP-04: the per-side cap of [c] ([ExplorationPolicy.capOf]). */
    private fun capOf(c: Claim) = policy.capOf(isRoot = c.parent == null)

    /** Caller holds the engine lock. Whether one more argument fits on [side] of [c] ([ExplorationPolicy.fits]). */
    private fun fits(c: Claim, side: Side, forced: Boolean) = policy.fits(c.view(), side, forced)

    /** EXP-06: one claim of [root]'s budget; a forced round (CTL-02) always gets it. */
    private fun reserve(root: CellRef, forced: Boolean): Boolean = host.locked {
        val n = state.treeSize.getValue(root)
        policy.mayReserve(n, forced).also { if (it) state.treeSize[root] = n + 1 }
    }
}
