package civictech.demo.beadsmirror.e2e

import java.util.Random

/**
 * Sizing knobs for [ReadySchedule.derive]. The feature's Ex/agree names
 * "~60 steps over ~15 issues" as the default worked example; [steps] and
 * [maxIssues] are exposed rather than hard-coded so a future caller (the
 * sibling differential harness, computenet-98u.2) can size a run without
 * editing this file.
 */
data class ReadyScheduleConfig(
    val steps: Int = ReadySchedule.DEFAULT_STEPS,
    val maxIssues: Int = ReadySchedule.DEFAULT_MAX_ISSUES,
)

/**
 * Task computenet-98u.2.1's single-sided, seeded, ready-oriented schedule
 * generator — the schedule half of the differential ready harness (feature
 * computenet-98u.2; epic computenet-98u/BDS3). [derive] is a **pure**
 * function of `(seed, config)`: two calls with the same arguments render
 * `equals` lists, the same determinism shape
 * [SeededSchedule.derive] already establishes for the two-sided generator.
 *
 * **Single-sided, deliberately.** Unlike [SeededSchedule] this derives ONE
 * [List] of [ScheduleStep] for ONE workspace — no listener/dialer split, no
 * shared issue, no partition/heal phase. The differential harness this feeds
 * runs one schedule against one scratch workspace and compares the derived
 * ready set against `bd ready --json` after each step; there is nothing here
 * for a second side to converge with.
 *
 * **Legality, reusing [SeededSchedule]'s `SideModel` pattern.** [ReadyModel]
 * tracks which ids are open/closed and which dependency edges exist, purely
 * so [derive] only ever emits a step that is legal against a real `bd`
 * workspace in order: create before any mutate/dep/delete of that id, `dep
 * add` only between two live ids with no edge between them yet and always
 * blocked-after-blocker in creation order (so an edge always points
 * backward through time and a cycle is structurally unreachable, exactly
 * [SeededSchedule]'s reasoning), `dep remove` only on an edge the model
 * still holds. This is what lets a schedule be handed straight to
 * [BdScratchWorkspace.run][civictech.demo.beadsmirror.BdScratchWorkspace.run]
 * with no revalidation, and it is why [ReadyScheduleTest] can run one full
 * derived schedule against a real workspace as its legality proof.
 *
 * **Biased toward the ready predicate, not uniform.** The feature's rule 4
 * names the verbs this generator exists to exercise: blocking `dep add`
 * (types `blocks` AND `conditional-blocks`), `dep remove`, closing a
 * blocker, reopening a blocker, deleting a blocker, status transitions in
 * and out of the ready status set, and type-carrying creates. [nextStep]
 * weights those choices above a plain untyped create/update by offering
 * blocker-targeted close/reopen/delete choices ALONGSIDE (not instead of)
 * generic ones whenever a blocker id exists, and by offering the dep-add
 * choice twice whenever a legal pair exists — the same "add more closures to
 * bias the draw" technique [SeededSchedule.nextStep] would use if it needed
 * to (it does not; this generator does, per rule 4's explicit bias
 * instruction).
 *
 * **Untestable-by-schedule, per this task's decided direction — documented
 * here, not simulated:**
 * - The boolean `pinned` **column** on
 *   [civictech.demo.beadsmirror.ready.ReadyPredicate] (`isTruthyBoolean(fields,
 *   "pinned")`) has no `bd` 1.1.2 CLI verb at all — probed live in a scratch
 *   sandbox 2026-08-19, no `bd update` flag toggles it. Only the `pinned`
 *   **status** enum value is schedulable, via [ScheduleStep.StatusUpdate]; a
 *   schedule from this generator can drive an issue into `status = pinned`
 *   but can never set the separate boolean column that [ReadyPredicate][civictech.demo.beadsmirror.ready.ReadyPredicate]
 *   also tests. See [ScheduleStep.StatusUpdate]'s KDoc for the same note
 *   at the verb site.
 * - Per-edge `waits-for` gate metadata (`metadata.gate` =
 *   `all-children`/`any-children`, the `waits-for` dependency type
 *   `bd create --waits-for`/`--waits-for-gate` mints) is excluded from
 *   [civictech.demo.beadsmirror.ready.ReadyPredicate]'s own modelled scope
 *   (its KDoc's "excluded from computenet-98u's scope" paragraph) because the
 *   mirror's edge model carries no metadata at all — there is nothing this
 *   generator could legally schedule that the mirror could even represent,
 *   so it is not attempted here either.
 */
object ReadySchedule {

    /** The feature's Ex/agree worked example: ~60 steps. */
    const val DEFAULT_STEPS: Int = 60

    /** The feature's Ex/agree worked example: ~15 issues. */
    const val DEFAULT_MAX_ISSUES: Int = 15

    /**
     * The two directly-blocking dependency types `ReadyPredicate`'s KDoc
     * names from `blocked_state.go` — `parent-child` and `waits-for` are
     * deliberately absent (the former is a transitive propagation rule, not
     * a per-edge test; the latter is metadata-driven and unrepresentable —
     * see the type KDoc's "untestable-by-schedule" section).
     */
    private val BLOCKING_DEP_TYPES: List<String> = listOf("blocks", "conditional-blocks")

    /**
     * Status values this generator drives an issue through — the two ready
     * statuses ([civictech.demo.beadsmirror.ready.ReadyPredicate.DEFAULT_READY_STATUSES])
     * plus two that fall outside the ready set for different reasons:
     * `blocked` (a status a caller can set directly, independent of the
     * derived `is_blocked` edge computation) and `pinned` (the status enum
     * value — see [ScheduleStep.StatusUpdate]'s KDoc for why this is not the
     * boolean column of the same name). All four verified live against `bd`
     * 1.1.2 as legal `--status` values.
     */
    private val SCHEDULABLE_STATUSES: List<String> = listOf("open", "in_progress", "blocked", "pinned")

    /**
     * Issue types [ScheduleStep.TypedCreate] mints — one
     * [civictech.demo.beadsmirror.ready.ReadyPredicate.EXCLUDED_TYPES]
     * member (`gate`) and one ready-included type (`bug`), so a typed create
     * exercises the predicate's `issue_type` clause on both sides of the
     * boundary.
     */
    private val CREATE_TYPES: List<String> = listOf("gate", "bug")

    /**
     * Derives a single-sided, ready-oriented [ScheduleStep] list. Pure
     * function of `(seed, config)` — see the type KDoc's determinism
     * paragraph.
     */
    fun derive(seed: Long, config: ReadyScheduleConfig = ReadyScheduleConfig()): List<ScheduleStep> {
        val random = Random(seed)
        val model = ReadyModel()
        val steps = mutableListOf<ScheduleStep>()
        repeat(config.steps) { steps += nextStep(random, model, config) }
        return steps
    }

    private fun nextStep(random: Random, model: ReadyModel, config: ReadyScheduleConfig): ScheduleStep {
        val choices = mutableListOf<() -> ScheduleStep>()

        // create: legal whenever under the issue cap. Plain create (default
        // `task` type) and typed create both offered, so plain creates are
        // not crowded out entirely by the bias below.
        if (model.liveCount < config.maxIssues) {
            choices += {
                val id = model.newId()
                model.created(id)
                ScheduleStep.Create(id, "issue $id")
            }
            choices += {
                val id = model.newId()
                model.created(id)
                val type = CREATE_TYPES.pick(random)
                ScheduleStep.TypedCreate(id, "issue $id ($type)", type)
            }
        }

        if (model.openIds.isNotEmpty()) {
            // status transitions in and out of the ready set.
            choices += {
                val id = model.openIds.pick(random)
                ScheduleStep.StatusUpdate(id, SCHEDULABLE_STATUSES.pick(random))
            }

            // generic close.
            choices += {
                val id = model.openIds.pick(random)
                model.closed(id)
                ScheduleStep.Close(id)
            }
        }

        // blocker-targeted close — biases toward closing an id that is
        // actually a blocker in some live edge, per rule 4's "blocker close".
        val openBlockers = model.openIds.filter { it in model.blockerIds }
        if (openBlockers.isNotEmpty()) {
            choices += {
                val id = openBlockers.pick(random)
                model.closed(id)
                ScheduleStep.Close(id)
            }
        }

        if (model.closedIds.isNotEmpty()) {
            // generic reopen.
            choices += {
                val id = model.closedIds.pick(random)
                model.reopened(id)
                ScheduleStep.Reopen(id)
            }
        }

        // blocker-targeted reopen.
        val closedBlockers = model.closedIds.filter { it in model.blockerIds }
        if (closedBlockers.isNotEmpty()) {
            choices += {
                val id = closedBlockers.pick(random)
                model.reopened(id)
                ScheduleStep.Reopen(id)
            }
        }

        if (model.liveIds.isNotEmpty()) {
            // generic delete.
            choices += {
                val id = model.liveIds.pick(random)
                model.deleted(id)
                ScheduleStep.Delete(id)
            }
        }

        // blocker-targeted delete — "delete of a blocker" per rule 4.
        val liveBlockers = model.liveIds.filter { it in model.blockerIds }
        if (liveBlockers.isNotEmpty()) {
            choices += {
                val id = liveBlockers.pick(random)
                model.deleted(id)
                ScheduleStep.Delete(id)
            }
        }

        // blocking dep add, offered twice to bias the draw toward it
        // whenever a legal pair exists (rule 4: "blocking dep add ... types
        // blocks AND conditional-blocks").
        val depPairs = model.candidateDepPairs()
        if (depPairs.isNotEmpty()) {
            repeat(2) {
                choices += {
                    val (blocked, blocker) = depPairs.pick(random)
                    val type = BLOCKING_DEP_TYPES.pick(random)
                    model.linked(blocked, blocker)
                    ScheduleStep.DepAdd(blocked, blocker, type)
                }
            }
        }

        // dep remove.
        if (model.edges.isNotEmpty()) {
            choices += {
                val (blocked, blocker) = model.edges.toList().pick(random)
                model.unlinked(blocked, blocker)
                ScheduleStep.DepRemove(blocked, blocker)
            }
        }

        check(choices.isNotEmpty()) {
            "no legal step available — model has no live ids and is at the issue cap (${config.maxIssues})"
        }
        return choices.pick(random).invoke()
    }

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    /**
     * The single-sided legality model [derive] consults to pick only legal
     * next steps — [SeededSchedule]'s `SideModel`, minus the shared-issue
     * carve-out (there is no shared issue here) and plus [blockerIds], which
     * that model has no need of.
     */
    private class ReadyModel {
        private var counter = 0

        val openIds = mutableListOf<String>()
        val closedIds = mutableListOf<String>()
        val edges = mutableSetOf<Pair<String, String>>() // (blockedId, blockerId)
        private val creationOrder = mutableListOf<String>()

        /** Every id the model still holds, open or closed — [Delete]'s legal targets. */
        val liveIds: List<String> get() = openIds + closedIds

        /** Count of ids currently tracked, open or closed — what [ReadyScheduleConfig.maxIssues] caps. */
        val liveCount: Int get() = openIds.size + closedIds.size

        /** Every id currently appearing as the BLOCKER side of a live edge. */
        val blockerIds: Set<String> get() = edges.map { it.second }.toSet()

        fun newId(): String = "R-${++counter}"

        fun created(id: String) {
            openIds += id
            creationOrder += id
        }

        fun closed(id: String) {
            openIds -= id
            closedIds += id
        }

        fun reopened(id: String) {
            closedIds -= id
            openIds += id
        }

        fun deleted(id: String) {
            openIds -= id
            closedIds -= id
            creationOrder -= id
            edges.removeAll { (blocked, blocker) -> blocked == id || blocker == id }
        }

        fun linked(blocked: String, blocker: String) {
            edges += (blocked to blocker)
        }

        fun unlinked(blocked: String, blocker: String) {
            edges -= (blocked to blocker)
        }

        /**
         * Every (blocked, blocker) pair without an edge yet, restricted to
         * blocked-after-blocker in creation order — [SeededSchedule.SideModel]'s
         * identical cycle-freedom argument.
         */
        fun candidateDepPairs(): List<Pair<String, String>> {
            val live = creationOrder.filter { it in openIds || it in closedIds }
            val pairs = mutableListOf<Pair<String, String>>()
            for (blockedIndex in live.indices) {
                for (blockerIndex in 0 until blockedIndex) {
                    val blocked = live[blockedIndex]
                    val blocker = live[blockerIndex]
                    if ((blocked to blocker) !in edges) pairs += (blocked to blocker)
                }
            }
            return pairs
        }
    }
}
