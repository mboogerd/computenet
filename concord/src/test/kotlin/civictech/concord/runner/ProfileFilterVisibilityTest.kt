package civictech.concord.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException
import java.io.File

/**
 * Pins computenet-j2x.7: **a profile-filtered corpus run has to say what it
 * excluded.**
 *
 * The defect these tests exist for was silence, not wrongness.
 * `-Pconcord.profiles=core` correctly declined to run the five `dist` scenarios
 * of `corpus/42-replication/`, but it discarded them *before any JUnit node
 * existed*, so the run was green, the console said nothing, `CorpusRunner.xml`
 * held no trace of them and their `skipped` count was 0. A bead or PR citing
 * that run as evidence for `42-REPL-01` was citing a run that never executed it,
 * and nothing in the output could contradict it.
 *
 * So these assertions are about the run's *output*, not about which scenarios
 * execute: the fast local loop is a deliberate affordance and stays exactly as
 * fast. What must not survive is an exclusion that leaves no evidence.
 */
class ProfileFilterVisibilityTest {

    private val corpusFiles: List<File> =
        File("corpus").walkTopDown().filter { it.isFile && it.extension == "yaml" }.toList()

    private fun names(active: Set<String>) = CorpusRunner().corpusTests(active).map { it.displayName }

    @Test
    fun `a core-only run emits a reported node for every dist scenario it excluded`() {
        val excludedNames = names(setOf("core")).filter { "SKIPPED" in it }

        assertTrue(excludedNames.isNotEmpty()) {
            "a core-only run excluded scenarios but reported none of them — the computenet-j2x.7 defect"
        }
        // The five 42-replication scenarios are the ones that surfaced the bug; each
        // must be nameable in the output, with the reason attached to the hit so the
        // very grep that diagnosed the defect cannot be misread as proof it ran.
        for (id in listOf("42-REPL-01", "42-INTEREST-01", "42-TMAP-REPL-01", "42-REPL-DEPART-01", "42-REPL-LATE-01")) {
            val hit = excludedNames.singleOrNull { it.startsWith("$id ") }
            assertTrue(hit != null) { "core-only run does not report excluding $id; reported: $excludedNames" }
            assertTrue("42-replication" in hit!! && "dist" in hit) {
                "$id's skip report names neither its corpus directory nor its profile: $hit"
            }
        }
    }

    @Test
    fun `an excluded scenario reports as skipped, not as passed`() {
        val node = CorpusRunner().corpusTests(setOf("core")).single { it.displayName.startsWith("42-REPL-01 ") }

        // TestAbortedException is what makes JUnit record the node as SKIPPED — which
        // is what reaches both the Gradle console (TestLogEvent.SKIPPED) and the
        // `skipped=` attribute of CorpusRunner.xml. A node that merely passed would
        // add a green result for a scenario nothing drove.
        val aborted = assertThrows<TestAbortedException> { node.executable.execute() }
        assertTrue("did NOT run" in aborted.message!! && "profile: dist" in aborted.message!!) {
            "skip reason does not say the scenario did not run, or why: ${aborted.message}"
        }
        assertTrue("-Pconcord.profiles" in aborted.message!!) {
            "skip reason does not name the flag that caused it: ${aborted.message}"
        }
    }

    @Test
    fun `the summary node names the excluded count, profiles and directories`() {
        val summary = names(setOf("core")).first()

        assertTrue("EXCLUDED and NOT run" in summary) { "summary does not state that scenarios were excluded: $summary" }
        assertTrue("dist" in summary && "dur" in summary) { "summary does not name the excluded profiles: $summary" }
        assertTrue("42-replication" in summary) { "summary does not name the excluded directories: $summary" }
        assertTrue("active=[core]" in summary) { "summary does not name the active profile set: $summary" }
    }

    @Test
    fun `the summary node is present even when nothing was excluded`() {
        // Its absence therefore means the runner did not execute — never "nothing was
        // filtered", which is precisely the inference the defect invited.
        val summary = names(setOf("core", "dist", "dur")).first()

        assertTrue("0 scenarios excluded" in summary) { "full-profile run does not report a clean sweep: $summary" }
        assertTrue(names(setOf("core", "dist", "dur")).none { "SKIPPED" in it }) {
            "a full-profile run reported skips: nothing should be excluded"
        }
    }

    @Test
    fun `no discovered scenario is dropped without a node, under any profile set`() {
        // The invariant behind all of the above: discovery count in, node count out.
        // A scenario that vanishes between the two is invisible exactly the way
        // 42-replication was.
        for (active in listOf(setOf("core"), setOf("dist"), setOf("core", "dist", "dur"), emptySet())) {
            val nodes = CorpusRunner().corpusTests(active)
            assertEquals(corpusFiles.size + 1, nodes.size) {
                "active=$active produced ${nodes.size} nodes for ${corpusFiles.size} scenarios + 1 summary"
            }
        }
    }
}
