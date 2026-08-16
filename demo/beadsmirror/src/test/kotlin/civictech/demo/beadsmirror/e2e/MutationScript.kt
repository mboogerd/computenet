package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.projector.MirrorEdge

/**
 * The scripted `bd` mutation sequence feature computenet-dqj.5 names, driven
 * against a throwaway [BdScratchWorkspace] — the shared fixture behind
 * [ScriptedSequenceTest] (equality, restart, compaction) and the divergence
 * controls of computenet-dqj.5.3.
 *
 * The sequence, in order, with what each step is here to exercise:
 *
 * 1. `create A`, `create B` — plain adds.
 * 2. `dep add B A --type blocks` — the dependency feed, and the one edge the
 *    fold must end up with.
 * 3. `update A --status=... --design=...` — **two fields in ONE commit**: the
 *    per-field keying case (a whole-issue key would lose one of them; that is
 *    computenet-dqj.5.3's control, and this is the sequence it seeds).
 * 4. `close B --force` — a status transition, and the point [beforeRestart] ends so a
 *    restart lands mid-sequence.
 * 5. `create C`, then `delete C --force` — the removal case. `bd delete`
 *    leaves **no export trace at all** (BDS0 claim (c), re-verified in this
 *    feature's breakdown probe: `dolt_diff_issues` shows `diff_type=removed`
 *    and `bd export` stops printing it), so "C is absent" is checkable only
 *    against a fold that carries a presence key.
 * 6. `update A --add-label ...` — a commit with **zero** `dolt_diff_issues`
 *    rows (labels are their own table; breakdown probe). It is in the script
 *    to prove such a commit flows through the poller harmlessly; `labels`
 *    itself is an excluded field on both sides (design amendment 1,
 *    [civictech.demo.beadsmirror.baseline.BaselineBuilder.EXCLUDED_FIELDS]).
 * 7. `update A --set-metadata k=v` — an ordinary metadata edit, reaching the
 *    feed as `to_metadata`. Deliberately **not** a `cn_dot` key: echo-drop is
 *    [civictech.demo.beadsmirror.projector.EchoDropTest]'s business, and the
 *    last step of the script must be one that does produce a diff row, so the
 *    checkpoint can reach the workspace head (the poller advances it to the
 *    last *record's* commit, not to head).
 *
 * Every step runs through [BdScratchWorkspace.run], which fails loudly on a
 * non-zero `bd` exit — a mis-typed flag surfaces as a failed test rather than
 * as a silently shorter sequence.
 */
class MutationScript(private val workspace: BdScratchWorkspace) {

    /** The long-lived issue: multi-field update, label, metadata. Set by [beforeRestart]. */
    lateinit var idA: String
        private set

    /** Closed mid-sequence, and the depending side of the one edge. Set by [beforeRestart]. */
    lateinit var idB: String
        private set

    /** Created and then `bd delete`d — the issue that must be absent. Set by [afterRestart]. */
    lateinit var idC: String
        private set

    /** The exact edge set the fold must end up with: `bd dep add B A` gives `B -> A`. */
    val expectedEdges: Set<MirrorEdge> get() = setOf(MirrorEdge(idB, idA, DEP_TYPE))

    /** Steps 1-4: everything up to and including `close B`. */
    fun beforeRestart() {
        idA = workspace.createIssue("Issue A")
        idB = workspace.createIssue("Issue B")
        workspace.run("dep", "add", idB, idA, "--type", DEP_TYPE)
        workspace.run("update", idA, "--status", "in_progress", "--design", DESIGN)
        // --force because B depends on A: bd refuses to close an issue whose
        // blocker is still open ("blocked by open issues [A]", observed
        // 2026-08-16). The step under test is the close itself, not bd's
        // dependency policy.
        workspace.run("close", idB, "--force")
    }

    /** Steps 5-7: the removal, the zero-diff label commit, and the metadata edit. */
    fun afterRestart() {
        idC = workspace.createIssue("Issue C")
        workspace.run("delete", idC, "--force")
        workspace.run("update", idA, "--add-label", LABEL)
        workspace.run("update", idA, "--set-metadata", "$METADATA_KEY=$METADATA_VALUE")
    }

    /** The whole sequence, uninterrupted. */
    fun runAll() {
        beforeRestart()
        afterRestart()
    }

    companion object {
        const val DEP_TYPE: String = "blocks"
        const val DESIGN: String = "A's design, written in the same commit as its status"
        const val LABEL: String = "mirror-e2e"
        const val METADATA_KEY: String = "team"
        const val METADATA_VALUE: String = "platform"
    }
}

/**
 * `bd create <title> --json`, returning the new issue's id.
 *
 * Reads the id out of the JSON with a regex rather than a parser for the same
 * reason [civictech.demo.beadsmirror.BeadsMirrorAppTest] does: the shape of
 * `bd create --json`'s envelope is bd's business and may gain fields, while
 * `"id": "..."` is the one part this fixture depends on.
 */
fun BdScratchWorkspace.createIssue(title: String): String {
    val output = run("create", title, "--json")
    return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(output)!!.groupValues[1]
}
