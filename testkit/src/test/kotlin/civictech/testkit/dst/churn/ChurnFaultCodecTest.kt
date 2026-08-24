package civictech.testkit.dst.churn

import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstArtifact
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstRig
import civictech.testkit.dst.Fault
import civictech.testkit.dst.FaultCodecs
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.ObservedRun
import civictech.testkit.dst.PlanRecord
import civictech.testkit.dst.ReassignEvent
import civictech.testkit.dst.RejoinEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CHA3-07] and [CHA3-48] mechanics: churn events are the rig's own [Fault]s, encodable
 * field-for-field into a [civictech.testkit.dst.FaultRecord], and every numeric knob CHA3
 * declares is actually *reachable* by the rig's shrinker.
 *
 * ## Why round-trip equality is not the assertion this suite is built around
 *
 * It is here, but it is the weak half. `doc/dst-rig.md` §2 records what CHA1 measured: a codec
 * that nests its fields into a sub-object round-trips **perfectly** and is invisible to
 * [civictech.testkit.dst.ReductionStrategies.numericParamToward], which reads a parameter by
 * name off the record's top-level `params`. The shrinker then proposes nothing for that field,
 * with no error and no warning, and a round-trip test stays green throughout — it is
 * structurally blind to the defect.
 *
 * So this suite asserts the two things a round trip cannot see:
 *
 *  - [everyChurnCodecWritesFlatPrimitiveParams] — every value in `params` is a
 *    `JsonPrimitive`, directly, per kind.
 *  - [theShrinkerProposesACandidateForEveryDeclaredKnob] — for each knob
 *    [ChurnReductions.declaredFor] declares, the rig's own strategy actually emits a
 *    candidate. A knob nobody can reach is a documented direction with no mechanism behind it.
 */
class ChurnFaultCodecTest {

    private companion object {
        const val HORIZON = 200

        fun join() = JoinEvent("j", "peer1", 7)
        fun rejoin() = RejoinEvent("r", "peer2", 11)
        fun depart() = DepartEvent("d", "peer3", 19, DepartureMode.EVICT_NO_CLOSE)
        fun reassign() = ReassignEvent("a", "peer0", 23, "interest-3", 5L)

        val ALL: List<Fault> = listOf(join(), rejoin(), depart(), reassign())

        /**
         * A minimal artifact for a strategy call.
         * [civictech.testkit.dst.ReductionStrategies.numericParamToward] takes an artifact but
         * reads only the plan, so nothing here has to correspond to a real run — and building
         * one that did would make this a shrink-execution test rather than a reachability one.
         */
        fun probeArtifact(): DstArtifact = DstArtifact(
            rig = DstRig.stamp(),
            suite = "churn-shrink-reachability-probe",
            seed = 1L,
            graphId = "churn-probe",
            checkId = "churn-probe-check",
            budget = HORIZON,
            plan = PlanRecord(emptyList()),
            observed = ObservedRun(
                outcome = DstOutcome.FAILED,
                steps = 1,
                failingCheck = "probe",
                failingStep = 1,
                traceDigest = "0",
                traceEvents = 1,
            ),
        )
    }

    /** [CHA3-07]: each churn event kind registers a codec, so a churn plan can be written. */
    @Test
    fun everyChurnEventKindRegistersACodec_CHA3_07() {
        // Read through CODEC, not KIND: KIND is a `const val` the compiler inlines, so naming
        // it loads nothing and would leave this assertion order-dependent (the mechanism
        // FaultCodecRoundTripTest measured for the CHA1 classes).
        val declared = listOf(
            JoinEvent.CODEC.kind,
            RejoinEvent.CODEC.kind,
            DepartEvent.CODEC.kind,
            ReassignEvent.CODEC.kind,
        )
        val registered = FaultCodecs.kinds()
        val missing = declared.filterNot { it in registered }
        assertTrue(
            missing.isEmpty(),
            "these churn event kinds registered no FaultCodec: $missing (registered: ${registered.sorted()})",
        )
        assertEquals(declared, ChurnReductions.kinds, "ChurnReductions.kinds must name every churn kind")
    }

    /** Each codec claims its own class and no other's — [FaultCodecs.encode]'s single-claimant rule. */
    @Test
    fun eachCodecClaimsExactlyItsOwnEventClass_CHA3_07() {
        assertEquals(JoinEvent.KIND, FaultCodecs.encode(join()).kind)
        assertEquals(RejoinEvent.KIND, FaultCodecs.encode(rejoin()).kind)
        assertEquals(DepartEvent.KIND, FaultCodecs.encode(depart()).kind)
        assertEquals(ReassignEvent.KIND, FaultCodecs.encode(reassign()).kind)
    }

    /** The weak half: encode then decode reconstructs an equal event, per kind. */
    @Test
    fun everyChurnEventRoundTripsThroughItsCodec_CHA3_07() {
        ALL.forEach { fault ->
            val record = FaultCodecs.encode(fault)
            assertEquals(fault, FaultCodecs.decode(record), "round trip changed ${fault.describe()}")
        }
    }

    /**
     * The half a round trip cannot see: every parameter is a **top-level primitive**.
     *
     * `doc/dst-rig.md` §2. A nested value object would pass the round-trip test above and
     * silently leave the shrinker nothing to move for that field.
     */
    @Test
    fun everyChurnCodecWritesFlatPrimitiveParams_CHA3_48() {
        ALL.forEach { fault ->
            val record = FaultCodecs.encode(fault)
            val nested = record.params.filterValues { it !is JsonPrimitive }
            assertTrue(
                nested.isEmpty(),
                "${record.kind} writes non-primitive params ${nested.keys}: a nested value round-trips " +
                    "perfectly and is invisible to ReductionStrategies.numericParamToward, which reads " +
                    "params by name off the top level (doc/dst-rig.md §2)",
            )
            assertTrue(record.params.isNotEmpty(), "${record.kind} encoded no params at all")
        }
    }

    /** The whole plan a generator produces encodes; nothing in it needs a codec that is missing. */
    @Test
    fun aGeneratedPlanEncodesEndToEnd_CHA3_07() {
        val plan = ChurnGenerator.generate(seed = 4242L).toFaultPlan()
        val records = plan.faults.map(FaultCodecs::encode)
        assertEquals(plan.faults.size, records.size)
        assertEquals(plan.faults, records.map(FaultCodecs::decode), "a generated plan did not round-trip")
    }

    /**
     * [CHA3-48]: every knob [ChurnReductions] declares is one the rig's shrinker can actually
     * move.
     *
     * The failure this pins is the one `FaultCodecRoundTripTest` names for CHA1:
     * `numericParamToward` wraps its encode in `runCatching` with an early return, so an
     * unreachable parameter makes the strategy propose **nothing**, silently. A declared
     * direction with no candidate behind it is documentation, not a shrink move.
     */
    @Test
    fun theShrinkerProposesACandidateForEveryDeclaredKnob_CHA3_48() {
        val artifact = probeArtifact()
        ChurnReductions.declaredFor(HORIZON).forEach { knob ->
            val fault = faultOfKind(knob.kind)
            val plan = FaultPlan(seed = 1L, faults = listOf(fault))
            val candidates = civictech.testkit.dst.ReductionStrategies
                .numericParamToward(knob.kind, knob.param, knob.target)
                .candidates(plan, artifact)
            assertTrue(
                candidates.isNotEmpty(),
                "no candidate proposed for ${knob.kind}.${knob.param} toward ${knob.target} " +
                    "(${knob.why}) — the parameter is not reachable on the encoded record",
            )
        }
    }

    /**
     * [CHA3-48]: the seed is held constant by [civictech.testkit.dst.PlanShrinker]'s own
     * `require`, and CHA3 does **not** re-implement it.
     *
     * What is asserted here is *conformance*: every candidate the churn strategy proposes
     * carries the plan's seed, so the rig's guard has nothing to reject. The guard itself —
     * that a seed-varying reduction is refused loudly rather than skipped — is CHA1's, pinned
     * by `ShrinkerTest`, and re-asserting it here would give a reader two places to check with
     * only one of them authoritative.
     */
    @Test
    fun everyChurnReductionCandidateHoldsTheSeed_CHA3_48() {
        val artifact = probeArtifact()
        val plan = FaultPlan(seed = 1L, faults = ALL)
        val candidates = ChurnReductions.strategyFor(HORIZON).candidates(plan, artifact)
        assertTrue(candidates.isNotEmpty(), "the churn strategy proposed nothing for a four-event plan")
        candidates.forEach { candidate ->
            assertEquals(
                plan.seed,
                candidate.plan.seed,
                "reduction \"${candidate.description}\" changed the seed; PlanShrinker would reject it",
            )
        }
    }

    /**
     * `churn-rejoin`.`atStep` carries **no** declared direction, deliberately — see
     * [ChurnReductions]. Pinned so that a later session adding one has to change a test that
     * states the reason, rather than filling in the gap for symmetry.
     */
    @Test
    fun rejoinStepHasNoDeclaredShrinkDirection_CHA3_48() {
        val bound = ChurnReductions.declaredFor(HORIZON)
            .filter { it.kind == RejoinEvent.CODEC.kind && it.param == "atStep" }
        assertTrue(
            bound.isEmpty(),
            "churn-rejoin.atStep is not monotone in adversarial-ness (later = longer absence = harsher; " +
                "past the horizon = never rejoins = weaker), so no single target is honest. A suite that " +
                "knows its graph's answer supplies one with ChurnReductions.atStepToward. Found: $bound",
        )
    }

    private fun faultOfKind(kind: String): Fault = when (kind) {
        JoinEvent.KIND -> join()
        RejoinEvent.KIND -> rejoin()
        DepartEvent.KIND -> depart()
        ReassignEvent.KIND -> reassign()
        else -> error("unknown churn kind \"$kind\"")
    }
}
