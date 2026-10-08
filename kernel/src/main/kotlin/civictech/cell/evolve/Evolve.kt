package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.ManagedHost
import civictech.cell.membrane.Principal
import civictech.cell.membrane.TrafficLightApi
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.CycleHead
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.port.identity
import civictech.cell.verify.InvariantCell
import civictech.cell.verify.Violation
import java.util.concurrent.TimeUnit

/** Authority consulted before an evolution starts and before every attempt to advance it. */
fun interface EvolutionAuthority {
    /** A refusal reason, or `null` when [principal] may evolve this graph. */
    fun refuse(principal: Principal): String?

    companion object {
        /** The local-first default from spec 53: local callers pass; remote-stamped callers do not. */
        val LocalTrustedOnly = EvolutionAuthority { principal ->
            when (principal) {
                Principal.LocalTrusted -> null
                is Principal.Peer -> "remote principal ${principal.id} may not trigger evolution"
            }
        }
    }
}

/**
 * Integration seam for graph-owned durability and bookkeeping. The kernel default performs
 * the ordinary host despawn and has no journal or additional promotion bookkeeping.
 */
interface EvolutionHooks {
    val journal: PromotionJournal?
        get() = null

    fun despawnShadow(host: ManagedHost, ref: CellRef) {
        host.managementInlet.call.despawn(ref)
    }

    /** Attach a production input to a shadow; graph owners may journal the link. */
    fun <T : Any> tapShadow(outlet: FanOutlet<T>, inlet: FanInlet<T>): PortRef {
        outlet.subscribe(inlet)
        return inlet.ref
    }

    /** Remove a tap previously installed by [tapShadow]. */
    fun <T : Any> untapShadow(outlet: FanOutlet<T>, inlet: PortRef) {
        outlet.unsubscribe(inlet)
    }

    /**
     * Attach the same-ref, unhosted candidate used by rolling replicated evolution.
     * Kept separate from [tapShadow] because the candidate has no topology address of
     * its own until COMMIT; graph owners may still journal this ephemeral observation.
     */
    fun <T : Any> tapReplicatedShadow(outlet: FanOutlet<T>, inlet: FanInlet<T>): PortRef {
        outlet.subscribe(inlet)
        return inlet.ref
    }

    /** Remove a tap previously installed by [tapReplicatedShadow]. */
    fun <T : Any> untapReplicatedShadow(outlet: FanOutlet<T>, inlet: PortRef) {
        outlet.unsubscribe(inlet)
    }

    fun promoted(incumbent: CellRef) {}

    /** Same-ref counterpart to [promoted], where no incumbent ref is retired. */
    fun promotedReplica(ref: CellRef) {}
}

/** A running shadow/judge/promotion orchestration. */
interface EvolutionHandle {
    enum class State {
        SHADOWING,
        JUDGED_ACCEPT,
        PROMOTING,
        PROMOTED,
        REJECTED,
        ROLLED_BACK,
    }

    val state: State
    val reason: String?
    val candidateRef: CellRef
    val baselineRef: CellRef?

    fun verdict(): PromotionVerdict

    /**
     * Evaluate the latest host-drained observation prefix and, when settled, perform its
     * terminal action. A wave that has emitted but whose gate delivery is still queued is
     * deliberately absent from that prefix, so calling from outside the host is safe.
     *
     * The prefix carries [civictech.cell.host.Quiescence]'s limits: it covers gate deliveries
     * that travel this host's scheduler queue, not gates hosted elsewhere or attention-parked
     * traffic. It advances only when the host queue drains, so a host that never drains keeps
     * the handle [State.SHADOWING].
     */
    fun advance(): State

    /**
     * Poll [advance] until the handle is terminal or [timeoutMillis] elapses. Production may
     * continue while it polls; each candidate wave arranges publication after the host queue
     * has applied that wave's already-enqueued gate deliveries.
     */
    fun await(timeoutMillis: Long): State
}

/**
 * The kernel composition entry point for live evolution (spec 52/53): shadow the candidate,
 * wire invariant violations into one [PromotionJudge], observe candidate waves, and drive the
 * existing [Promotion] swap only after the judge accepts.
 */
object Evolve {
    class Refused(reason: String) : RuntimeException(reason)

    /** Keep graph/runtime callers behind the evolution package's invariant-type boundary. */
    internal fun isInvariant(cell: Cell?): Boolean = cell is InvariantCell<*, *>

    /** Keep graph/runtime callers behind the evolution package's membrane-type boundary. */
    internal fun isTrafficLightDataOutlet(cell: Cell?, owner: CellRef, outletName: String): Boolean {
        val gate = cell as? TrafficLightApi<*> ?: return false
        val outlet = gate.dataOutlet as? FanOutlet<*> ?: return false
        val identity = outlet.identity() ?: return false
        return identity.owner == owner && identity.name == outletName
    }

    /**
     * Privileged direct primitive for a caller that holds the live cells but no [ApplyContext].
     * The authority check is deliberately first; [Promotion] owns the synchronous four-phase
     * swap and its PRECHECK/COMMIT failure semantics.
     */
    fun promoteDirect(
        host: ManagedHost,
        gate: Cell,
        incumbent: Cell,
        candidate: Cell,
        outletName: String,
        downstream: List<Use<*>>,
        judge: PromotionJudge? = null,
        authority: EvolutionAuthority = EvolutionAuthority.LocalTrustedOnly,
    ) {
        checkAuthority(authority)
        Promotion.promote(
            host = host,
            gate = gate,
            incumbent = incumbent,
            candidate = candidate,
            outletName = outletName,
            downstream = downstream,
            judge = judge,
            journal = null,
        )
    }

    /**
     * Privileged direct primitive for the same-ref rolling swap of a replicated cell. The
     * authority check is deliberately first; [Promotion] owns the replica rebind protocol.
     */
    fun promoteReplicaDirect(
        host: ManagedHost,
        replication: civictech.cell.replication.Replication,
        incumbent: civictech.cell.data.Replicable<*>,
        candidate: civictech.cell.data.Replicable<*>,
        outletName: String = "outlet",
        judge: PromotionJudge? = null,
        authority: EvolutionAuthority = EvolutionAuthority.LocalTrustedOnly,
    ) {
        checkAuthority(authority)
        Promotion.promoteReplica(
            host = host,
            replication = replication,
            incumbent = incumbent,
            candidate = candidate,
            outletName = outletName,
            judge = judge,
            journal = null,
        )
    }

    /** Incumbent-side differential shadow and the gates that judge it. */
    data class Baseline(
        val twin: Cell,
        val gates: List<InvariantCell<*, *>>,
    )

    /**
     * Graph/runtime entry point that keeps the concrete membrane and invariant types behind
     * the evolution package boundary. Labels are retained solely for actionable PRECHECK
     * refusals; callers that already hold typed gates use the public overload below.
     */
    internal fun run(
        host: ManagedHost,
        gateHandle: String,
        gate: Cell,
        incumbent: Cell,
        candidate: Cell,
        outletName: String,
        downstream: List<Use<*>>,
        policy: PromotionPolicy,
        gates: List<Pair<String, Cell>>,
        baselineTwin: Cell? = null,
        baselineGates: List<Pair<String, Cell>> = emptyList(),
        authority: EvolutionAuthority = EvolutionAuthority.LocalTrustedOnly,
        hooks: EvolutionHooks? = null,
    ): EvolutionHandle {
        @Suppress("UNCHECKED_CAST")
        val trafficLight = gate as? TrafficLightApi<Any>
            ?: throw Promotion.PromotionAborted(
                "PRECHECK",
                "gate '$gateHandle' (${gate.ref}) is not a live TrafficLightApi",
            )
        val candidateInvariants = gates.toInvariants("gate")
        val baseline = baselineTwin?.let { twin ->
            Baseline(twin, baselineGates.toInvariants("baseline gate"))
        }
        return run(
            host = host,
            gate = trafficLight,
            incumbent = incumbent,
            candidate = candidate,
            outletName = outletName,
            downstream = downstream,
            policy = policy,
            gates = candidateInvariants,
            baseline = baseline,
            authority = authority,
            hooks = hooks,
        )
    }

    /**
     * Start one live evolution and publish candidate observations only after this host's
     * scheduler has drained. At most one settlement fence is pending for this evolution;
     * waves observed before it runs share that fence and settle as one prefix.
     *
     * Settlement uses a kernel-internal recovery-aware host fence. The fence neither blocks
     * [ManagedHost.recoverFrom] nor completes while recovery has data delivery gated; it
     * re-arms after the gate lifts so already-observed waves settle without another wave.
     * A terminated scheduler's refusal is contained here: the candidate emission still
     * succeeds, but that wave does not advance the settled prefix.
     */
    fun <T : Any> run(
        host: ManagedHost,
        gate: TrafficLightApi<T>,
        incumbent: Cell,
        candidate: Cell,
        outletName: String,
        downstream: List<Use<*>>,
        policy: PromotionPolicy,
        gates: List<InvariantCell<*, *>>,
        baseline: Baseline? = null,
        authority: EvolutionAuthority = EvolutionAuthority.LocalTrustedOnly,
        hooks: EvolutionHooks? = null,
    ): EvolutionHandle {
        checkAuthority(authority)
        validateGates(policy, gates, baseline)

        val judge = PromotionJudge(policy, cycleHead = incumbent as? CycleHead<*>)
        val activeHooks = hooks ?: object : EvolutionHooks {}
        @Suppress("UNCHECKED_CAST")
        val gateOutlet = gate.dataOutlet as? FanOutlet<T>
            ?: throw Refused("gates: the traffic-light data outlet must be a FanOutlet")

        spawnIfNeeded(host, candidate, outletName)
        val candidateInputs = tapShadow(gateOutlet, candidate, activeHooks)

        baseline?.let { spawnIfNeeded(host, it.twin, outletName) }
        val baselineInputs = baseline?.let { tapShadow(gateOutlet, it.twin, activeHooks) }.orEmpty()

        val violationSubscriptions = mutableListOf<ViolationSubscription>()
        gates.forEach { invariant ->
            violationSubscriptions += subscribeViolations(invariant, judge::observeCandidateViolation)
        }
        baseline?.gates?.forEach { invariant ->
            violationSubscriptions += subscribeViolations(invariant, judge::observeIncumbentViolation)
        }

        val candidateOutlet = outlet(candidate, outletName)
        val observerRef = PortRef.generate()
        val settlement = ObservationSettlement(host, judge)
        candidateOutlet.observe(observerRef) { settlement.observeCandidateWave() }

        return Handle(
            candidateRef = candidate.ref,
            baselineRef = baseline?.twin?.ref,
            candidateOutlet = candidateOutlet,
            judge = judge,
            observerRef = observerRef,
            violationSubscriptions = violationSubscriptions,
            authority = authority,
            detachCandidateInputs = { candidateInputs.forEach { activeHooks.untapShadow(gateOutlet, it) } },
            discardCandidate = { activeHooks.despawnShadow(host, candidate.ref) },
            detachBaseline = baseline?.let { differential ->
                {
                    baselineInputs.forEach { activeHooks.untapShadow(gateOutlet, it) }
                    activeHooks.despawnShadow(host, differential.twin.ref)
                }
            },
            swap = {
                Promotion.promote(
                    host = host,
                    gate = gate,
                    incumbent = incumbent,
                    candidate = candidate,
                    outletName = outletName,
                    downstream = downstream,
                    // advance() already consumed a host-drained verdict. Re-reading the raw
                    // counters in Promotion PRECHECK would reopen the same tap-before-gate
                    // race if a newer wave emitted between that verdict and this call.
                    judge = null,
                    journal = activeHooks.journal,
                )
            },
            onPromoted = { activeHooks.promoted(incumbent.ref) },
        )
    }

    /**
     * The replicated arm of [run] (spec 52 [52-CONV-01], spec 53 `[53-REPL-01..03]`): judge
     * a [Replicable] [candidate] against a [Replicable] [incumbent] and, only on `Accept`,
     * swap through [Promotion.promoteReplica] (the same-ref rolling rebind).
     *
     * **What the shadow taps.** A replicable's inputs are local writes and peer gossip, so
     * there is no single upstream gate. Both arrive as one stream: the incumbent's
     * effective-delta [Replicable.outlet] re-emits every effective mutation, merged remote
     * deltas included ([52-CONV-01]). That outlet is tapped into the candidate's
     * [Replicable.deltaInlet]. The candidate is deliberately **not** hosted and not a
     * replication member while shadowing: it shares the incumbent's [CellRef], and two live
     * objects under one ref would break the crash-recovery mechanism that [replication]'s
     * rebind relies on. The tap is removed before COMMIT, which performs the authoritative
     * rebind (state handoff, hosting, gossip re-link). The shadow starts empty and judges the
     * deltas observed from the tap onward; COMMIT restores it from the incumbent's snapshot.
     *
     * **Limit of this judgment.** Because the shadow is fed the incumbent's *effective deltas*
     * on its [Replicable.deltaInlet], it exercises only the candidate's merge/re-emit path:
     * local write ops (the cell's own op inlet) are never delivered to it, so a candidate
     * that changes how local ops are interpreted is not judged on that change. The same-ref
     * shadow tap is installed through [EvolutionHooks.tapReplicatedShadow], allowing a graph
     * owner to account for its ephemeral observation separately from hosted topology.
     *
     * A differential baseline is not offered here: a twin would need a third distinct
     * replica object and tap with no spec-settled meaning. The authority gate is checked at
     * start and before every [EvolutionHandle.advance], as for [run].
     */
    fun runReplica(
        host: ManagedHost,
        replication: civictech.cell.replication.Replication,
        incumbent: civictech.cell.data.Replicable<*>,
        candidate: civictech.cell.data.Replicable<*>,
        policy: PromotionPolicy,
        gates: List<InvariantCell<*, *>>,
        outletName: String = "outlet",
        authority: EvolutionAuthority = EvolutionAuthority.LocalTrustedOnly,
        hooks: EvolutionHooks? = null,
    ): EvolutionHandle {
        checkAuthority(authority)
        validateGates(policy, gates, null)
        if (candidate.ref != incumbent.ref) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "replicated evolution must reuse the incumbent's CellRef; candidate " +
                    "${candidate.ref} != incumbent ${incumbent.ref} (spec 53 §Replicated promotion)",
            )
        }
        val activeHooks = hooks ?: object : EvolutionHooks {}
        val judge = PromotionJudge(policy, cycleHead = incumbent as? CycleHead<*>)

        @Suppress("UNCHECKED_CAST")
        val incumbentOutlet = incumbent.outlet as? FanOutlet<Propagate<Any?>>
            ?: throw Refused("gates: replicated incumbent outlet must be a FanOutlet")
        @Suppress("UNCHECKED_CAST")
        val shadowInlet = candidate.deltaInlet as? FanInlet<Propagate<Any?>>
            ?: throw Refused("gates: replicated candidate delta inlet must be a FanInlet")
        val candidateOutlet = outlet(candidate, outletName)
        attachReplicaGates(candidateOutlet, gates)

        val violationSubscriptions = mutableListOf<ViolationSubscription>()
        gates.forEach { invariant ->
            violationSubscriptions += subscribeViolations(invariant, judge::observeCandidateViolation)
        }
        val observerRef = PortRef.generate()
        val settlement = ObservationSettlement(host, judge)
        candidateOutlet.observe(observerRef) { settlement.observeCandidateWave() }
        var tapRef = activeHooks.tapReplicatedShadow(incumbentOutlet, shadowInlet)

        return Handle(
            candidateRef = candidate.ref,
            baselineRef = null,
            candidateOutlet = candidateOutlet,
            judge = judge,
            observerRef = observerRef,
            violationSubscriptions = violationSubscriptions,
            authority = authority,
            detachCandidateInputs = { activeHooks.untapReplicatedShadow(incumbentOutlet, tapRef) },
            // The candidate was never hosted and shares the incumbent's ref: despawning it
            // would despawn the live incumbent.
            discardCandidate = {},
            detachBaseline = null,
            swap = {
                // COMMIT rebinds the candidate under the incumbent's ref, so the tap must be
                // gone first; the shadow then re-syncs by snapshot handoff plus anti-entropy.
                activeHooks.untapReplicatedShadow(incumbentOutlet, tapRef)
                try {
                    Promotion.promoteReplica(
                        host = host,
                        replication = replication,
                        incumbent = incumbent,
                        candidate = candidate,
                        outletName = outletName,
                        judge = null,
                        journal = activeHooks.journal,
                    )
                } catch (aborted: Promotion.PromotionAborted) {
                    // A PRECHECK refusal leaves the evolution retryable, so the shadow keeps
                    // being fed; a COMMIT abort is terminal and the handle detaches it.
                    if (aborted.message?.startsWith("promotion aborted at COMMIT:") != true) {
                        tapRef = activeHooks.tapReplicatedShadow(incumbentOutlet, shadowInlet)
                    }
                    throw aborted
                }
            },
            onPromoted = { activeHooks.promotedReplica(incumbent.ref) },
        )
    }

    private fun attachReplicaGates(
        candidateOutlet: FanOutlet<*>,
        gates: List<InvariantCell<*, *>>,
    ) {
        gates.forEach { invariant ->
            val inlet = invariant.inlet
            if (candidateOutlet.clazz != inlet.clazz) {
                throw Refused(
                    "gates: replicated candidate outlet ${candidateOutlet.clazz.name} is incompatible " +
                        "with invariant '${invariant.name}' inlet ${inlet.clazz.name}",
                )
            }
            @Suppress("UNCHECKED_CAST")
            (candidateOutlet as FanOutlet<Any>).subscribe(inlet as FanInlet<Any>)
        }
    }

    private fun checkAuthority(authority: EvolutionAuthority) {
        authority.refuse(currentPrincipal())?.let { reason ->
            throw Refused("authority: $reason")
        }
    }

    private fun List<Pair<String, Cell>>.toInvariants(role: String): List<InvariantCell<*, *>> =
        map { (handle, cell) ->
            cell as? InvariantCell<*, *>
                ?: throw Promotion.PromotionAborted(
                    "PRECHECK",
                    "$role handle '$handle' (${cell.ref}) is not an InvariantCell",
                )
        }

    private fun validateGates(
        policy: PromotionPolicy,
        gates: List<InvariantCell<*, *>>,
        baseline: Baseline?,
    ) {
        val expected = policy.gates.toSet()
        val supplied = gates.map { it.name }.toSet()
        if (expected != supplied) {
            throw Refused("gates: policy names $expected but candidate gates are $supplied")
        }
        if (policy.baseline) {
            val required = baseline
                ?: throw Refused("baseline: the policy requires a differential baseline")
            val baselineNames = required.gates.map { it.name }.toSet()
            if (baselineNames != expected) {
                throw Refused("baseline: policy gates $expected but baseline gates are $baselineNames")
            }
        } else if (baseline != null) {
            throw Refused("baseline: a twin was supplied but the policy does not enable a baseline")
        }
    }

    private fun spawnIfNeeded(host: ManagedHost, cell: Cell, outletName: String) {
        if (host.portAt(cell.ref, outletName) == null) Shadow.spawn(host, cell)
    }

    private fun <T : Any> tapShadow(
        gateOutlet: FanOutlet<T>,
        shadow: Cell,
        hooks: EvolutionHooks,
    ): List<PortRef> {
        val tapped = mutableListOf<PortRef>()
        val ports = PortRegistry.of(shadow)
        ports.names().forEach { name ->
            val inlet = ports[name]
            if (inlet is FanInlet<*> && inlet.clazz == gateOutlet.clazz) {
                @Suppress("UNCHECKED_CAST")
                tapped += hooks.tapShadow(gateOutlet, inlet as FanInlet<T>)
            }
        }
        return tapped
    }

    private fun subscribeViolations(
        invariant: InvariantCell<*, *>,
        observe: (Violation) -> Unit,
    ): ViolationSubscription {
        val ref = PortRef.generate()
        invariant.violations.subscribe(Use.fixed(object : Propagate<Violation> {
            override fun propagate(value: Violation) = observe(value)
        }, ref))
        return ViolationSubscription(invariant.violations, ref)
    }

    private fun outlet(cell: Cell, name: String): FanOutlet<*> =
        PortRegistry.of(cell)[name] as? FanOutlet<*>
            ?: throw Refused("gates: candidate ${cell.ref} has no fan-out outlet '$name'")

    private data class ViolationSubscription(
        val outlet: FanOutlet<Propagate<Violation>>,
        val ref: PortRef,
    )

    /** Coalesces every candidate wave covered by the same not-yet-run host drain fence. */
    private class ObservationSettlement(
        private val host: ManagedHost,
        private val judge: PromotionJudge,
    ) {
        private val lock = Any()
        private var fencePending = false

        fun observeCandidateWave() {
            judge.observeCandidateWave()
            synchronized(lock) {
                if (fencePending) return
                fencePending = true
                try {
                    // Observe taps fire before consumers. The lowest-priority fence runs after
                    // this emission's later data-band deliveries. Later waves queued before it
                    // runs are covered too, so they need no fence of their own.
                    host.recoveryAwareQuiescence().asFuture().whenComplete { _, failure ->
                        synchronized(lock) {
                            if (failure == null) judge.settleObservation()
                            fencePending = false
                        }
                    }
                } catch (_: RuntimeException) {
                    // A terminated scheduler can refuse the initial submission. FanOutlet.observe
                    // propagates callback failures to the emitter, so containment belongs here.
                    fencePending = false
                }
            }
        }
    }

    private class Handle(
        override val candidateRef: CellRef,
        override val baselineRef: CellRef?,
        private val candidateOutlet: FanOutlet<*>,
        private val judge: PromotionJudge,
        private val observerRef: PortRef,
        private val violationSubscriptions: List<ViolationSubscription>,
        private val authority: EvolutionAuthority,
        private val detachCandidateInputs: () -> Unit,
        private val discardCandidate: () -> Unit,
        private val detachBaseline: (() -> Unit)?,
        private val swap: () -> Unit,
        private val onPromoted: () -> Unit,
    ) : EvolutionHandle {
        @Volatile
        override var state: EvolutionHandle.State = EvolutionHandle.State.SHADOWING
            private set

        @Volatile
        private var terminalReason: String? = null

        override val reason: String?
            get() = terminalReason

        override fun verdict(): PromotionVerdict = judge.settledVerdict()

        @Synchronized
        override fun advance(): EvolutionHandle.State {
            checkAuthority(authority)
            if (state.isTerminal()) return state

            return when (val verdict = judge.settledVerdict()) {
                PromotionVerdict.Pending -> {
                    state = EvolutionHandle.State.SHADOWING
                    state
                }
                is PromotionVerdict.Reject -> {
                    if (verdict.terminal) reject(verdict.reason)
                    else {
                        state = EvolutionHandle.State.SHADOWING
                        state
                    }
                }
                PromotionVerdict.Accept -> promote()
            }
        }

        override fun await(timeoutMillis: Long): EvolutionHandle.State {
            require(timeoutMillis >= 0) { "timeoutMillis must be non-negative" }
            val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val started = System.nanoTime()
            while (!state.isTerminal() && System.nanoTime() - started < timeoutNanos) {
                advance()
                if (!state.isTerminal()) {
                    val elapsed = System.nanoTime() - started
                    val remainingMillis = TimeUnit.NANOSECONDS.toMillis((timeoutNanos - elapsed).coerceAtLeast(0))
                    if (remainingMillis > 0) Thread.sleep(minOf(10, remainingMillis))
                }
            }
            return state
        }

        private fun reject(reason: String): EvolutionHandle.State {
            detachJudgment()
            detachCandidateInputs()
            discardCandidate()
            detachBaseline?.invoke()
            terminalReason = reason
            state = EvolutionHandle.State.REJECTED
            return state
        }

        private fun promote(): EvolutionHandle.State {
            state = EvolutionHandle.State.JUDGED_ACCEPT
            state = EvolutionHandle.State.PROMOTING
            try {
                swap()
            } catch (aborted: Promotion.PromotionAborted) {
                if (!aborted.isCommitAbort()) {
                    state = EvolutionHandle.State.JUDGED_ACCEPT
                    throw aborted
                }
                detachJudgment()
                detachBaseline?.invoke()
                terminalReason = aborted.message
                state = EvolutionHandle.State.ROLLED_BACK
                return state
            }

            detachJudgment()
            detachBaseline?.invoke()
            onPromoted()
            state = EvolutionHandle.State.PROMOTED
            return state
        }

        private fun detachJudgment() {
            candidateOutlet.untap(observerRef)
            violationSubscriptions.forEach { subscription ->
                subscription.outlet.unsubscribe(subscription.ref)
            }
        }

        private fun EvolutionHandle.State.isTerminal(): Boolean =
            this == EvolutionHandle.State.PROMOTED ||
                this == EvolutionHandle.State.REJECTED ||
                this == EvolutionHandle.State.ROLLED_BACK

        /** [PromotionAborted] deliberately exposes its phase through its stable typed message. */
        private fun Promotion.PromotionAborted.isCommitAbort(): Boolean =
            message?.startsWith("promotion aborted at COMMIT:") == true
    }
}
