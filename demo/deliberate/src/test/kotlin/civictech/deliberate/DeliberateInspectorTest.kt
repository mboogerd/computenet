package civictech.deliberate

import civictech.inspect.InspectorServer
import civictech.inspect.InspectorFlag
import civictech.testkit.HttpProbe
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * computenet-3iv0w.3 (3iv0w-D4): deliberate serves its live graph under the
 * shared `--inspect-port` opt-in (`InspectorFlag`), on its own port, labelled
 * `"deliberate"`. Uses test-local fakes for [Judge]/[Proposer] (no
 * `TYPESAFE_API_KEY` needed), same shape as `DeliberateAppTest`.
 */
class DeliberateInspectorTest {

    private class FixedJudge : Judge {
        override fun plausibility(question: String, claim: String) = 0.6
        override fun assess(question: String, path: List<String>, child: String, side: Side) =
            Assessment(plausibility = 0.6, strength = 0.7, quality = 0.9, relevance = 1.0)
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>) = candidates.map { Triage(TriageAction.ADD) }
        override fun saturation(ctx: ClaimContext, side: Side) = 0.0
    }

    private class CountingProposer(override val id: String) : Proposer {
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> =
            List(max) { "$id ${side.name.lowercase()} argument" }
    }

    @Test
    fun `the opt-in inspector serves deliberate's live graph`() {
        val app = DeliberateApp(
            port = 0,
            judge = FixedJudge(),
            proposers = listOf(CountingProposer("claude"), CountingProposer("codex")),
            inspector = InspectorFlag.Options(port = 0),
        ).start()
        try {
            val server = app.inspector
            requireNotNull(server) { "app.inspector must be non-null once started with an inspector option" }
            val json = HttpProbe("http://localhost:${server.boundPort}").state(InspectorServer.TOPOLOGY_PATH)

            assertTrue(""""host":"deliberate"""" in json, "process host name: $json")
            assertTrue(""""typeFqn"""" in json, "expected at least one node: $json")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = DeliberateApp(
            port = 0,
            judge = FixedJudge(),
            proposers = listOf(CountingProposer("claude"), CountingProposer("codex")),
        ).start()
        try {
            assertNull(app.inspector)
        } finally {
            app.stop()
        }
    }
}
