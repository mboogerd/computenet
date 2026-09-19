package civictech.demo.social

import civictech.cell.Propagate
import civictech.cell.durability.Journal
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * Snapshot of [ManagedHost.supervisionAccounting]'s `deadLetters` taken
     * in [stage], before replay is submitted (v10ou-D5, [SOC1-DUR-04]). Read
     * again in [complete] to compute the delta a correct recovery must leave
     * at zero.
     */
    private var deadLettersBefore = 0L

    /**
     * Every [DeadLetter] the host has emitted since [stage] subscribed,
     * collected on `host.deadLetterOutlet` (v10ou-D5). Thread-safe: the
     * outlet fires from a scheduler task, [complete] reads it from whatever
     * thread calls it once the host has drained.
     */
    private val collectedDeadLetters = CopyOnWriteArrayList<DeadLetter>()

    /** Pre-spawn every known key through [SocialGraph], then replay the WAL once. Callable once. */
    fun stage() {
        check(!staged) { "SocialRecovery.stage() already ran" }
        // v10ou-D5: the counter and the collector are both armed before
        // recoverFrom is called, so every dead letter replay produces is seen.
        deadLettersBefore = host.supervisionAccounting().deadLetters
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        collectedDeadLetters += value
                    }
                },
                PortRef.generate(),
            ),
        )
        staged = true
        graph.spawnKnown()
        host.recoverFrom(journal)
    }

    /**
     * Marks recovery complete. Only valid after [stage] and after the host
     * drained every staged frame (see the class KDoc).
     *
     * Runs the dead-letter refusal FIRST (v10ou-D5, [SOC1-DUR-04] — a missing
     * key is an error, so it must abort before anything below runs), then
     * [SocialGraph.suppressUnwrittenKeys] AFTER that (v10ou-D6, closes the
     * durable half of `computenet-2v3e4`): an empty key that survived the
     * dead-letter check is a ghost, not a missing key, and is suppressed
     * rather than refused.
     *
     * @throws IllegalStateException when the host's dead-letter count grew
     *   between [stage] and this call — every replayed frame must have found
     *   a live cell. The message starts `recovery refused: <delta> replayed
     *   frame(s) found no cell` and lists, one per line, every collected dead
     *   letter: `<namespace>:<key>` when it can be named from its creating
     *   fact ([describeDeadLetter]), else its raw description.
     */
    fun complete() {
        check(staged) { "SocialRecovery.complete() before stage()" }
        val delta = host.supervisionAccounting().deadLetters - deadLettersBefore
        if (delta > 0) {
            val header = "recovery refused: $delta replayed frame(s) found no cell"
            val lines = collectedDeadLetters.map(::describeDeadLetter)
            throw IllegalStateException((listOf(header) + lines).joinToString("\n"))
        }
        // v10ou-D5's dead-letter refusal (SOC1-DUR-04) lands above, ahead of
        // the suppression below (v10ou-D6): a missing key is an error, an
        // empty key that survived the refusal above is a ghost.
        graph.suppressUnwrittenKeys()
        completed = true
    }
}

/**
 * Names [letter] as `<namespace>:<key>` when its first invocation argument is
 * one of the four creating facts (v10ou-D5, [SOC1-DUR-04]):
 * [PersonFact.Profile] -> `snb-person:<person.id>`, [ForumFact.Info] ->
 * `snb-forum:<forum.id>`, [MessageFact.Body] -> `snb-message:<message.id>`,
 * or a bare [Message] -> `snb-authored:<creatorId>` (the author's copy,
 * written by [SocialGraph.addPost]/[SocialGraph.addComment] before the
 * `snb-message` cell's own `Body`). Anything else — no invocation, or a
 * non-creating call — falls back to [DeadLetter.description]. Reads
 * [DeadLetter.invocation] with inferred types only: `civictech.cell.proxy` is
 * not an allowed demo import ([DemoSurfaceAllowlistTest]).
 */
private fun describeDeadLetter(letter: DeadLetter): String =
    when (val arg = letter.invocation?.invocation?.args?.firstOrNull()) {
        is PersonFact.Profile -> "snb-person:${arg.person.id}"
        is ForumFact.Info -> "snb-forum:${arg.forum.id}"
        is MessageFact.Body -> "snb-message:${arg.message.id}"
        is Message -> "snb-authored:${arg.creatorId}"
        else -> letter.description
    }
