package civictech.demo.skillmatch

import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SkillMatchAlignedFrameTest {

    @Test
    fun `candidate-only wave reaches every point-consistent view without buffering`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=cskill&candidate=ada&skill=kotlin")

            val expected = mapOf<String, Any?>(
                "candSkills" to setOf(CandidateSkill("ada", "kotlin")),
                "jobSkills" to emptySet<JobSkill>(),
                "matches" to emptySet<Match>(),
                "gap" to emptySet<JobSkill>(),
                "qualification" to emptyMap<CandidateJob, QualEntry>(),
                "market" to mapOf("kotlin" to MarketEntry(supply = 1, demand = 0, scarce = false)),
            )
            awaitUntil("candidate-only point views", timeoutMs = 5_000) {
                app.observationSnapshots() == expected
            }
            assertEquals(expected, app.observationSnapshots())
        } finally {
            app.stop()
        }
    }

    @Test
    fun `job-only wave reaches every point-consistent view without buffering`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=jskill&job=backend&skill=kotlin")

            val expected = mapOf<String, Any?>(
                "candSkills" to emptySet<CandidateSkill>(),
                "jobSkills" to setOf(JobSkill("backend", "kotlin")),
                "matches" to emptySet<Match>(),
                "gap" to setOf(JobSkill("backend", "kotlin")),
                "qualification" to emptyMap<CandidateJob, QualEntry>(),
                "market" to mapOf("kotlin" to MarketEntry(supply = 0, demand = 1, scarce = true)),
            )
            awaitUntil("job-only point views", timeoutMs = 5_000) {
                app.observationSnapshots() == expected
            }
            assertEquals(expected, app.observationSnapshots())
        } finally {
            app.stop()
        }
    }
}
