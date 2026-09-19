package civictech.demo.social

import civictech.cell.durability.Journal
import civictech.cell.host.ManagedHost

/**
 * Two-phase crash recovery of a `--journal` [SocialApp] (SOC1 F7, feature
 * `computenet-v10ou`, design v10ou-D1/D2; [SOC1-DUR-01], [SOC1-DUR-03]).
 *
 * **[stage]** restores the graph in the one order that keeps replay
 * convergent (`KeyedCells.kt` recover contract): first
 * [SocialGraph.spawnKnown] — every durably-known key of all four families,
 * spawned through [SocialGraph] so each cell's observe sink exists — then
 * [ManagedHost.recoverFrom] over the shared root WAL, exactly once. Never
 * `family.recover()`: its replay half resolves `<root>/<family>/host.journal`,
 * which this pipeline never writes, so it would replay nothing (see
 * [SnbPipeline]'s KDoc). Replay in the other order would dead-letter every
 * frame (`unknown cell <ref>`) and the recovered graph would read empty.
 *
 * **Staged is not delivered.** [ManagedHost.recoverFrom] only *submits* each
 * journaled frame to the host scheduler; delivery into the cells — and any
 * dead letter for a frame whose cell is not live — happens in later scheduler
 * tasks. Nothing about the recovered graph can be judged inside [stage]. That
 * is what [complete] is for, and **[complete] is only valid once the host has
 * drained**: on a `SimulationController` after `runToIdle()`, in production
 * behind [SocialApp.start]'s quiescence fence.
 */
class SocialRecovery(
    private val host: ManagedHost,
    private val graph: SocialGraph,
    private val journal: Journal,
) {
    @Volatile
    private var staged = false

    @Volatile
    var completed = false
        private set

    /** Pre-spawn every known key through [SocialGraph], then replay the WAL once. Callable once. */
    fun stage() {
        check(!staged) { "SocialRecovery.stage() already ran" }
        staged = true
        graph.spawnKnown()
        host.recoverFrom(journal)
    }

    /**
     * Marks recovery complete. Only valid after [stage] and after the host
     * drained every staged frame (see the class KDoc).
     *
     * This task adds nothing else here: feature `computenet-v10ou`'s other
     * tasks fill it — the dead-letter refusal naming a missing `(family, key)`
     * (v10ou-D5, [SOC1-DUR-04]) and the ghost-key suppression (v10ou-D6).
     */
    fun complete() {
        check(staged) { "SocialRecovery.complete() before stage()" }
        completed = true
    }
}
