package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import java.util.Random

/**
 * One `bd` mutation of a [SeededSchedule], runnable against a real
 * [BdScratchWorkspace] (task computenet-7em.2.2).
 *
 * The vocabulary is [MutationScript]'s (create, multi-field update, close,
 * delete) plus the two verbs that script never exercises but the feature's
 * acceptance names: `reopen` and `dep remove`. Every step runs through
 * [BdScratchWorkspace.run], which fails loudly on a non-zero `bd` exit — a
 * schedule that goes off the rails surfaces as a failed test rather than a
 * silently shorter run, exactly as [MutationScript]'s own KDoc requires.
 */
sealed interface ScheduleStep {

    /** Executes this step against [workspace]. */
    fun apply(workspace: BdScratchWorkspace)

    /**
     * `bd create <title> --id <id> --force`. The explicit `--id` (and the
     * `--force` that waives the prefix-match check it otherwise fails) is
     * what lets two independent scratch `bd` databases — [SeededSchedule]'s
     * listener and dialer workspaces — each mint a REAL issue row under the
     * identical id, which is the only way a `bd`-level mutation on one side
     * and a `bd`-level mutation on the other can ever land on the same
     * [civictech.demo.beadsmirror.projector.MirrorKey.issueId]: `bd` ids are
     * otherwise workspace-local and never collide (verified live against `bd`
     * 1.1.2 — two fresh `--sandbox init` databases both asked to create
     * "A" mint unrelated, differently-prefixed ids).
     */
    data class Create(val id: String, val title: String) : ScheduleStep {
        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("create", title, "--id", id, "--force")
        }
    }

    /**
     * `bd update <id> --status <status> --design <design>` — two fields in
     * ONE commit, [MutationScript] step 3's per-field-keying shape: a
     * whole-issue key would lose one of the two, so this is the case that
     * proves the OR-map's composite `(issue, field)` keying survives a real
     * two-sided schedule, not just the single-sided one [MutationScript]
     * scripts.
     */
    data class MultiFieldUpdate(val id: String, val status: String, val design: String) : ScheduleStep {
        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("update", id, "--status", status, "--design", design)
        }
    }

    /**
     * `bd update <id> --<field> <value>`, [field] restricted to plain string
     * flags ([FIELDS]) so any value is valid regardless of format. Used both
     * for ordinary single-field edits and — with a shared [id] on both sides
     * touching a DIFFERENT [field] each — the design's "same issueId on both
     * sides, different fields, both must survive" case ([SeededSchedule]'s
     * `sharedIssueId`).
     */
    data class FieldUpdate(val id: String, val field: String, val value: String) : ScheduleStep {
        init {
            require(field in FIELDS) { "not a plain string field: $field" }
        }

        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("update", id, "--$field", value)
        }

        companion object {
            /** Plain string `bd update` flags — no numeric/enum format to get wrong. */
            val FIELDS: Set<String> = setOf("design", "notes", "acceptance")
        }
    }

    /** `bd close <id> --force` — `--force` because a forced-generated schedule may close an issue with an open blocker. */
    data class Close(val id: String) : ScheduleStep {
        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("close", id, "--force")
        }
    }

    /** `bd reopen <id>` — the verb [MutationScript] never exercises; this feature's acceptance names it explicitly. */
    data class Reopen(val id: String) : ScheduleStep {
        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("reopen", id)
        }
    }

    /** `bd dep add <blockedId> <blockerId> --type <type>` — [blockedId] depends on [blockerId]. */
    data class DepAdd(val blockedId: String, val blockerId: String, val type: String) : ScheduleStep {
        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("dep", "add", blockedId, blockerId, "--type", type)
        }
    }

    /** `bd dep remove <blockedId> <blockerId>` — the other verb [MutationScript] never exercises. */
    data class DepRemove(val blockedId: String, val blockerId: String) : ScheduleStep {
        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("dep", "remove", blockedId, blockerId)
        }
    }

    /** `bd delete <id> --force` — whole-issue removal; `--force` orphans any dependent rather than refusing. */
    data class Delete(val id: String) : ScheduleStep {
        override fun apply(workspace: BdScratchWorkspace) {
            workspace.run("delete", id, "--force")
        }
    }
}

/**
 * A deterministic two-sided mutation schedule (task computenet-7em.2.2),
 * derived from a recorded [seed] via [java.util.Random] — the same
 * derivation shape [prior art][civictech.cell.replication.ReplicatedSessionTest]
 * uses, minus its 100-seed budget: this suite pays a real `bd`/`dolt` process
 * cost per step, so [ConvergenceSuite] runs a handful of RECORDED seed
 * constants rather than a wide sweep.
 *
 * **Every step generated is valid, in order, against a REAL `bd` workspace.**
 * [derive] tracks a small per-side model (which ids are open, which are
 * closed, which dependency edges exist) purely to choose the NEXT legal step
 * — create before any update/close/dep/delete of that id, `dep add` only
 * between two live ids with no edge between them yet and never in a
 * direction that could cycle (always blocked-after-blocker in creation
 * order, so the edge always points backward through time and a cycle is
 * structurally unreachable), `dep remove`/`reopen`/`close`/`delete` only on
 * an id the model still holds. This is why the class can be handed straight
 * to [BdScratchWorkspace.run] with no revalidation.
 *
 * **The shared issue.** [sharedIssueId] is minted with [ScheduleStep.Create]
 * on BOTH sides — the only way two independent `bd` databases land a mutation
 * on the same [civictech.demo.beadsmirror.projector.MirrorKey.issueId] — and
 * is excluded from the per-side model entirely (never closed, deleted, or
 * dep-linked by the random walk), so it survives to the end of both
 * schedules for the final pair of [ScheduleStep.FieldUpdate]s: [listenerSteps]
 * ends with an edit to `design`, [dialerSteps] with an edit to `notes` — two
 * DIFFERENT fields of the same issue, arriving from two different `bd`
 * databases, which is exactly the per-field-key merge the design calls out.
 *
 * **Determinism, not the cross-side interleaving.** [derive] draws
 * [listenerSteps] and [dialerSteps] from ONE [Random] stream, listener first,
 * so the whole derivation is a pure function of [seed] — but nothing here
 * claims anything about how the two sides interleave when a caller actually
 * runs them (concurrently, on two threads): the property under test is that
 * BOTH final orderings converge, regardless of interleaving.
 */
class SeededSchedule private constructor(
    val seed: Long,
    val listenerSteps: List<ScheduleStep>,
    val dialerSteps: List<ScheduleStep>,
    val sharedIssueId: String,
) {
    companion object {

        /**
         * Random single-field-update steps per side, NOT counting the shared
         * issue's own create and final touch. `2 * (RANDOM_STEPS_PER_SIDE + 2)`
         * is the schedule's total step count — with [RANDOM_STEPS_PER_SIDE] =
         * 13 that is 30, the design's "~30 mutations" figure.
         */
        private const val RANDOM_STEPS_PER_SIDE = 13

        /** Recorded regression seeds, run by [ConvergenceSuite]. Seed 42 is the design's own worked example. */
        const val SEED_1: Long = 42L
        const val SEED_2: Long = 1337L
        const val SEED_3: Long = 20260817L

        /**
         * Pinned failing seeds — the deterministic-simulation rule
         * (AGENTS.md): a seed that is ever observed to fail a convergence
         * assertion is added here VERBATIM, never swapped for a friendlier
         * one, and [ConvergenceSuite] grows one more `@Test` running it. None
         * has been discovered yet; this is the slot, not a live case:
         *
         * ```kotlin
         * // const val SEED_REGRESSION_<short-description>: Long = <the exact seed that failed>
         * ```
         */

        fun derive(seed: Long): SeededSchedule {
            val random = Random(seed)
            val sharedId = "shared-$seed"

            val listenerModel = SideModel("L$seed")
            val dialerModel = SideModel("D$seed")

            val listenerSteps = mutableListOf<ScheduleStep>(
                ScheduleStep.Create(sharedId, "shared across L and D, seed $seed"),
            )
            val dialerSteps = mutableListOf<ScheduleStep>(
                ScheduleStep.Create(sharedId, "shared across L and D, seed $seed"),
            )

            repeat(RANDOM_STEPS_PER_SIDE) { listenerSteps += nextStep(random, listenerModel) }
            repeat(RANDOM_STEPS_PER_SIDE) { dialerSteps += nextStep(random, dialerModel) }

            // The same issueId, mutated on both sides, touching DIFFERENT
            // fields — per-field keys must both survive (design example 1).
            listenerSteps += ScheduleStep.FieldUpdate(sharedId, "design", "set by L, seed $seed")
            dialerSteps += ScheduleStep.FieldUpdate(sharedId, "notes", "set by D, seed $seed")

            return SeededSchedule(seed, listenerSteps, dialerSteps, sharedId)
        }

        private fun nextStep(random: Random, model: SideModel): ScheduleStep {
            val choices = mutableListOf<() -> ScheduleStep>()

            // create: always legal.
            choices += {
                val id = model.newId()
                model.created(id)
                ScheduleStep.Create(id, "issue $id")
            }

            if (model.openIds.isNotEmpty()) {
                choices += {
                    val id = model.openIds.pick(random)
                    ScheduleStep.MultiFieldUpdate(
                        id,
                        status = STATUSES.pick(random),
                        design = "design for $id, roll ${random.nextInt(1_000_000)}",
                    )
                }
                choices += {
                    val id = model.openIds.pick(random)
                    ScheduleStep.FieldUpdate(id, ScheduleStep.FieldUpdate.FIELDS.toList().pick(random), "roll ${random.nextInt(1_000_000)}")
                }
                choices += {
                    val id = model.openIds.pick(random)
                    model.closed(id)
                    ScheduleStep.Close(id)
                }
                choices += {
                    val id = model.openIds.pick(random)
                    model.deleted(id)
                    ScheduleStep.Delete(id)
                }
            }
            if (model.closedIds.isNotEmpty()) {
                choices += {
                    val id = model.closedIds.pick(random)
                    model.reopened(id)
                    ScheduleStep.Reopen(id)
                }
                choices += {
                    val id = model.closedIds.pick(random)
                    model.deleted(id)
                    ScheduleStep.Delete(id)
                }
            }
            val depPairs = model.candidateDepPairs()
            if (depPairs.isNotEmpty()) {
                choices += {
                    val (blocked, blocker) = depPairs.pick(random)
                    model.linked(blocked, blocker)
                    ScheduleStep.DepAdd(blocked, blocker, DEP_TYPE)
                }
            }
            if (model.edges.isNotEmpty()) {
                choices += {
                    val (blocked, blocker) = model.edges.toList().pick(random)
                    model.unlinked(blocked, blocker)
                    ScheduleStep.DepRemove(blocked, blocker)
                }
            }

            return choices.pick(random).invoke()
        }

        private val STATUSES: List<String> = listOf("open", "in_progress")
        private const val DEP_TYPE: String = "blocks"

        private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]
    }

    /**
     * The per-side model [derive] uses to pick only legal next steps. Tracks
     * ids created by the random walk — the shared issue is deliberately never
     * given to this model, so it can never be picked for close/delete/dep.
     */
    private class SideModel(private val idPrefix: String) {
        private var counter = 0

        val openIds = mutableListOf<String>()
        val closedIds = mutableListOf<String>()
        val edges = mutableSetOf<Pair<String, String>>() // (blockedId, blockerId)
        private val creationOrder = mutableListOf<String>()

        fun newId(): String = "$idPrefix-${++counter}"

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
         * blocked-after-blocker in creation order — the direction that can
         * never close a cycle, since every edge then points strictly
         * backward through the creation sequence.
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
