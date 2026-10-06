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

    fun promoted(incumbent: CellRef) {}
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
     * Settlement uses [ManagedHost.quiescence], so its one pending fence is an external host
     * barrier: [ManagedHost.recoverFrom] refuses until the host queue drains it. Conversely,
     * when recovery is already active or the scheduler is terminated, inability to take the
     * fence is contained here. The candidate emission still succeeds, but that wave does not
     * advance the settled prefix.
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
            host = host,
            gate = gate,
            gateOutlet = gateOutlet,
            incumbent = incumbent,
            candidate = candidate,
            candidateInputs = candidateInputs,
            candidateOutlet = candidateOutlet,
            outletName = outletName,
            downstream = downstream,
            judge = judge,
            observerRef = observerRef,
            violationSubscriptions = violationSubscriptions,
            baseline = baseline,
            baselineInputs = baselineInputs,
            authority = authority,
            hooks = activeHooks,
        )
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
                    host.quiescence().asFuture().whenComplete { _, failure ->
                        synchronized(lock) {
                            if (failure == null) judge.settleObservation()
                            fencePending = false
                        }
                    }
                } catch (_: RuntimeException) {
                    // A recovery record loop or terminated scheduler can refuse the fence.
                    // FanOutlet.observe propagates callback failures to the emitting cell, so
                    // containment belongs here at the observation boundary.
                    fencePending = false
                }
            }
        }
    }

    private class Handle<T : Any>(
        private val host: ManagedHost,
        private val gate: TrafficLightApi<T>,
        private val gateOutlet: FanOutlet<T>,
        private val incumbent: Cell,
        private val candidate: Cell,
        private val candidateInputs: List<PortRef>,
        private val candidateOutlet: FanOutlet<*>,
        private val outletName: String,
        private val downstream: List<Use<*>>,
        private val judge: PromotionJudge,
        private val observerRef: PortRef,
        private val violationSubscriptions: List<ViolationSubscription>,
        private val baseline: Baseline?,
        private val baselineInputs: List<PortRef>,
        private val authority: EvolutionAuthority,
        private val hooks: EvolutionHooks,
    ) : EvolutionHandle {
        @Volatile
        override var state: EvolutionHandle.State = EvolutionHandle.State.SHADOWING
            private set

        @Volatile
        private var terminalReason: String? = null

        override val reason: String?
            get() = terminalReason

        override val candidateRef: CellRef = candidate.ref
        override val baselineRef: CellRef? = baseline?.twin?.ref

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
            detachInputs(candidateInputs)
            hooks.despawnShadow(host, candidate.ref)
            baseline?.let { differential ->
                detachInputs(baselineInputs)
                hooks.despawnShadow(host, differential.twin.ref)
            }
            terminalReason = reason
            state = EvolutionHandle.State.REJECTED
            return state
        }

        private fun promote(): EvolutionHandle.State {
            state = EvolutionHandle.State.JUDGED_ACCEPT
            state = EvolutionHandle.State.PROMOTING
            try {
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
                    journal = hooks.journal,
                )
            } catch (aborted: Promotion.PromotionAborted) {
                if (!aborted.isCommitAbort()) {
                    state = EvolutionHandle.State.JUDGED_ACCEPT
                    throw aborted
                }
                detachJudgment()
                baseline?.let { differential ->
                    detachInputs(baselineInputs)
                    hooks.despawnShadow(host, differential.twin.ref)
                }
                terminalReason = aborted.message
                state = EvolutionHandle.State.ROLLED_BACK
                return state
            }

            detachJudgment()
            baseline?.let { differential ->
                detachInputs(baselineInputs)
                hooks.despawnShadow(host, differential.twin.ref)
            }
            hooks.promoted(incumbent.ref)
            state = EvolutionHandle.State.PROMOTED
            return state
        }

        private fun detachJudgment() {
            candidateOutlet.untap(observerRef)
            violationSubscriptions.forEach { subscription ->
                subscription.outlet.unsubscribe(subscription.ref)
            }
        }

        private fun detachInputs(refs: List<PortRef>) {
            refs.forEach { hooks.untapShadow(gateOutlet, it) }
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
