package civictech.oracle.run

import civictech.cell.CellRef
import civictech.cell.verify.ReplicaConvergence
import civictech.oracle.model.DotModel
import civictech.oracle.model.DotOrder
import civictech.oracle.model.DotState
import civictech.oracle.model.ModelDot
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.SourceId
import java.util.UUID

/**
 * The convergence oracle — `[ORA2-CONV-01..04]`, `[ORA2-DIFF-08]`, `[ORA2-DIFF-09]`.
 *
 * ## The one thing this file exists to make impossible
 *
 * A convergence check that asks *"do the replicas agree?"* passes a mesh in which every replica
 * is wrong in the same way — which is the **normal** shape of a bug in the dot algebra, since
 * every replica runs the same merge over the same dots. So this check never compares replicas to
 * each other as its verdict. It computes **one converged reference answer** from the replica-
 * tagged script ([DotModel.converged], `[ORA2-CONV-01]`) and compares **every** replica against
 * that one answer (`[ORA2-CONV-02]`). Replica-vs-replica agreement is still asserted — it is a
 * real requirement (`[ORA2-DIFF-08]`) — but it is asserted *in addition*, and its failure is a
 * different verdict ([RunOutcome.ReplicaDivergence]) from a unanimous wrong answer
 * ([RunOutcome.ReplicasAgreeButWrong], `[ORA2-CONV-03]`).
 *
 * ## What it composes with, and what it therefore does not re-implement
 *
 * The agreement half is `civictech.cell.verify.ReplicaConvergence`, the kernel's own replica-
 * convergence invariant. It is consumed, never modified and never re-derived here:
 *
 * - **Agreement is asked of it** ([ReplicaConvergence.converged]), not recomputed. That matters
 *   for more than tidiness: `converged()` is defined over the replicas the *location registry*
 *   still counts as live membership (its `liveRefs()` = `replicasOf(logicalId) ∩ attached`), so
 *   an orderly departure excludes a replica's frozen last fold instead of reading it as a
 *   stalled disagreement. Re-implementing agreement over the attached folds here would silently
 *   drop that departed-stream rule and false-positive on an eviction.
 * - **The folds come from it** ([ReplicaConvergence.state]), and it builds them by subscribing to
 *   each replica's OWN delta outlet — so what this check compares is a state reconstructed from
 *   gossip, never `OrMapCell.state()`/`membership()`/`value()`. That is `[ORA2-CONV-04]`, and it
 *   is what makes an incompletely-gossiped replica *able* to fail: a check reading the cell's
 *   internal truth would report a converged mesh no matter what the outlet stream carried.
 *
 * What `ReplicaConvergence` structurally cannot supply is the batch half — it holds no reference
 * — and that is exactly the half [DotModel] is. The two together are the oracle; neither alone
 * is.
 *
 * ## Reading a replica-tagged script
 *
 * `[ORA2-CONV-01]`'s "every step names the replica that accepted it" is [Script]'s own shape,
 * not a new one: a [civictech.oracle.model.SourceScript] *is* one replica's accepted log, and
 * [civictech.oracle.model.Delivery] is the gossip it absorbed. The mesh's [SourceId]s are the
 * script's slice ids, and [MeshObservation] is what binds each of them to the kernel [CellRef]
 * that ran it.
 *
 * ## Kind precedence
 *
 * 1. [RunOutcome.ModelEvaluationFailure] — the reference itself threw (a cyclic delivery script,
 *    an unranked source), so there is no expected value at all. A broken oracle, never a broken
 *    kernel (`[ORA2-DIFF-11]`, D10).
 * 2. [RunOutcome.ReplicaDivergence] — the replicas hold different states. At most one of them can
 *    hold the reference answer, so "they agree but are wrong" is not expressible and this is
 *    strictly the more informative finding.
 * 3. [RunOutcome.ReplicasAgreeButWrong] — reachable only from an agreeing mesh, which is what
 *    makes the two mesh verdicts disjoint rather than two renderings of one condition.
 *
 * Quiescence, dead letters and the wave-prefix property are **not** this check's business: it
 * reads a mesh the caller has already driven to quiescence, and [DifferentialRunner] owns those
 * verdicts. There is deliberately no second runner here (feature §3.1: "if ORA2 finds itself
 * writing a second sweep loop, a second report format, or a second shrinker, it has gone wrong").
 *
 * ## The dot order is supplied, never derived
 *
 * [ConvergenceCheck] is constructed from a [DotOrder] and builds its own [DotModel] from it, so
 * the reference and the order can never drift apart. `[ORA2-MODEL-12]`: the harness sorts the
 * real replicas' kernel dot sources with the kernel's own comparator and hands over the result;
 * nothing here recomputes `UUID.nameUUIDFromBytes("or-map-tags:...")`. Supplying a *wrong* order
 * on purpose is how `[ORA2-CTL-02]`'s control produces a uniformly-wrong reference and therefore
 * a [RunOutcome.ReplicasAgreeButWrong] — see BS-7.
 */
class ConvergenceCheck(private val order: DotOrder) {

    /** The reference. Built here from [order] so a caller cannot hand in a model ordered differently. */
    private val model = DotModel(order)

    /** The reference this check compares against — exposed for tests and reports, not for wiring. */
    fun reference(script: Script): ModelState.MapState = model.evaluate(script)

    /**
     * One quiescent mesh's verdict.
     *
     * @param seed the seed that produced [mesh]'s interleaving, carried by every failure kind.
     * @param caseMarker how this case is identified in a report — the caller's own marker, as on
     *   [DifferentialRunner.check].
     * @param script the replica-tagged global script. Both the kernel mesh and the reference read
     *   THIS value; that they read the same one is what makes the comparison differential.
     * @param mesh the driven mesh as the oracle reads it — every replica's outlet-stream fold plus
     *   the kernel invariant's own agreement verdict.
     */
    fun check(seed: Long, caseMarker: String, script: Script, mesh: MeshObservation): RunOutcome {
        require(mesh.folds.isNotEmpty()) {
            "A convergence check needs at least one replica fold; the mesh named none for ${mesh.logicalId}"
        }
        val referenceState: DotState = try {
            model.converged(script)
        } catch (t: Throwable) {
            return RunOutcome.ModelEvaluationFailure(seed, t)
        }
        val expected: ModelState.MapState = try {
            model.entries(referenceState)
        } catch (t: Throwable) {
            return RunOutcome.ModelEvaluationFailure(seed, t)
        }

        val perReplica = mesh.folds.entries.associate { (source, state) -> source.id to state }
        // Agreement is the kernel invariant's answer (its live-membership rule, not ours), AND the
        // named handles actually holding one state. The second conjunct is not a re-derivation of
        // `liveRefs()`: it is the caller's own enumeration of the replicas it asked about, which
        // `converged()` deliberately does not speak for when membership has moved under it.
        val agreed = mesh.agreed && perReplica.values.distinct().size == 1

        if (!agreed) {
            return RunOutcome.ReplicaDivergence(
                seed = seed,
                logicalId = mesh.logicalId.toString(),
                caseMarker = caseMarker,
                script = script,
                expected = expected,
                perReplica = perReplica,
                keys = keyEvidence(referenceState, expected, perReplica, disagreeingKeys(expected, perReplica)),
            )
        }

        val actual = perReplica.values.first()
        if (actual == expected) return RunOutcome.Success

        return RunOutcome.ReplicasAgreeButWrong(
            seed = seed,
            logicalId = mesh.logicalId.toString(),
            caseMarker = caseMarker,
            script = script,
            expected = expected,
            actual = actual,
            difference = StateDifference.between(expected, actual),
            replicas = perReplica.keys.toSet(),
            keys = keyEvidence(referenceState, expected, perReplica, disagreeingKeys(expected, perReplica)),
        )
    }

    /**
     * `[ORA2-CONV-01]`'s third clause: the converged answer is **invariant under the gossip
     * interleaving**, checked by driving the same script again under a different seed-derived
     * interleaving and requiring the same verdict.
     *
     * Implemented as "run [check] once per seed and report the first non-[RunOutcome.Success]"
     * rather than as a bespoke comparison, and that is the point: every interleaving is checked
     * against the SAME single reference, so two interleavings agreeing with the reference agree
     * with each other by construction, while two interleavings that agree with each other and not
     * with the reference are both reported — a pairwise comparison would call that pair stable and
     * say nothing.
     *
     * @param seeds the interleavings to drive. At least two, or the invariance claim is vacuous
     *   and this would be [check] under a longer name.
     * @param drive builds and drives the mesh at one seed and returns it at quiescence.
     */
    fun acrossInterleavings(
        seeds: List<Long>,
        caseMarker: String,
        script: Script,
        drive: (Long) -> MeshObservation,
    ): RunOutcome {
        require(seeds.size >= 2) {
            "Interleaving invariance needs at least two interleavings; got ${seeds.size}"
        }
        seeds.forEach { seed ->
            val outcome = check(seed, caseMarker, script, drive(seed))
            if (outcome != RunOutcome.Success) return outcome
        }
        return RunOutcome.Success
    }

    /** Keys on which the reference and the replicas do not all agree. */
    private fun disagreeingKeys(
        expected: ModelState.MapState,
        perReplica: Map<String, ModelState>,
    ): List<Any?> {
        val everyKey: MutableSet<Any?> = LinkedHashSet<Any?>(expected.entries.keys)
        perReplica.values.forEach { state -> if (state is ModelState.MapState) everyKey.addAll(state.entries.keys) }
        return everyKey.filter { key ->
            val reference = expected.entries[key]
            val present = expected.entries.containsKey(key)
            perReplica.values.any { state ->
                val entries = (state as? ModelState.MapState)?.entries ?: return@any true
                entries.containsKey(key) != present || entries[key] != reference
            }
        }
    }

    /** `[ORA2-DIFF-09]`'s per-key report, naming the accepting replica of the winning dot. */
    private fun keyEvidence(
        referenceState: DotState,
        expected: ModelState.MapState,
        perReplica: Map<String, ModelState>,
        keys: List<Any?>,
    ): List<KeyDivergence> = keys.map { key ->
        KeyDivergence(
            key = key,
            expected = expected.entries[key],
            winningDot = winningDot(referenceState, key),
            actualByReplica = perReplica.mapValues { (_, state) ->
                (state as? ModelState.MapState)?.entries?.get(key)
            },
        )
    }

    /**
     * The reference's maximal live dot at [key] — whose [ModelDot.source] is the replica that
     * accepted the write the mesh was supposed to carry. `null` when the reference holds no live
     * dot there.
     */
    private fun winningDot(state: DotState, key: Any?): ModelDot? =
        state.liveDots(key).keys.maxWithOrNull(order.comparator())
}

/**
 * One quiescent replica mesh, as the convergence oracle reads it — the seam that keeps
 * [ConvergenceCheck] out of the business of *building* meshes.
 *
 * Deliberately a plain value rather than a live handle on hosts and cells. Two reasons worth
 * recording:
 *
 * 1. **The oracle must not be able to peek.** A [ConvergenceCheck] handed live `OrMapCell`s could
 *    read `membership()`/`value()` and satisfy itself about a mesh whose outlet streams carried
 *    nothing — precisely the failure `[ORA2-CONV-04]` forbids. Given only folds, it cannot.
 * 2. **Mesh construction belongs to whoever owns execution.** A hand-built mesh (BS-1, BS-6,
 *    BS-7) and a generated one differ entirely in how they are wired and not at all in how they
 *    are judged.
 *
 * @property logicalId the replicated logical id the mesh is of.
 * @property folds each replica's fold of its OWN delta outlet stream, keyed by the script slice
 *   that replica accepted.
 * @property agreed the kernel invariant's own agreement verdict
 *   ([ReplicaConvergence.converged]) — asked, not recomputed, so the departed-stream rule is
 *   honoured.
 */
data class MeshObservation(
    val logicalId: UUID,
    val folds: Map<SourceId, ModelState>,
    val agreed: Boolean,
) {
    companion object {
        /**
         * Read a driven mesh out of the kernel's own convergence invariant.
         *
         * @param replicas the script slice each replica ran, by kernel ref. Both halves are the
         *   caller's: only the harness knows which `CellRef` accepted which slice, and
         *   `[ORA2-MODEL-12]` says so.
         * @param stateOf how a fold reads as a comparable state — [TaggedMapTerminalFold.stateOf]
         *   for the tagged family, [PnCounterTerminalFold.stateOf] for the counter one, so the
         *   convergence verdict and a terminal comparison read a delta the same way.
         */
        fun <D : Any, S> of(
            logicalId: UUID,
            convergence: ReplicaConvergence<D, S>,
            replicas: Map<SourceId, CellRef>,
            stateOf: (S) -> ModelState,
        ): MeshObservation = MeshObservation(
            logicalId = logicalId,
            folds = replicas.mapValues { (source, ref) ->
                stateOf(
                    convergence.state(ref) ?: error(
                        "Replica '${source.id}' ($ref) was never attached to the convergence invariant, " +
                            "so it has no outlet-stream fold to check. [ORA2-CONV-04]: every replica's " +
                            "fold is reconstructed from its own delta outlet, never read off the cell.",
                    ),
                )
            },
            agreed = convergence.converged(),
        )
    }
}
