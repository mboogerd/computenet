package civictech.demo.skillmatch

import civictech.cell.data.MapDelta
import civictech.cell.data.MapView
import civictech.cell.data.Propagate
import civictech.cell.data.SetDelta
import civictech.cell.data.SetView
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Seeded incremental-vs-batch equivalence over the exact pipeline the app
 * wires ([SkillPipeline.build]): after random skill churn on both sides of
 * the join, every derived view — matches, per-pair counts, required counts,
 * the skills gap, and the hub-derived qualified set — equals a batch
 * recompute from the final input sets on every seed.
 */
class SkillMatchPipelineTest {

    interface MatchOutletProxy {
        val outlet: Subscribe<Propagate<SetDelta<Match>>>
    }

    interface GapOutletProxy {
        val outlet: Subscribe<Propagate<SetDelta<JobSkill>>>
    }

    interface PairCountOutletProxy {
        val outlet: Subscribe<Propagate<MapDelta<CandidateJob, Long>>>
    }

    interface JobCountOutletProxy {
        val outlet: Subscribe<Propagate<MapDelta<String, Long>>>
    }

    interface SkillCountOutletProxy {
        val outlet: Subscribe<Propagate<MapDelta<String, Long>>>
    }

    @Test
    fun `incremental equals batch recompute on every seed`() {
        val candidates = listOf("ada", "bo", "cy")
        val jobs = listOf("backend", "data")
        val skills = listOf("kotlin", "sql", "rust", "ml")

        for (seed in 0L until 10L) {
            val controller = SimulationController(seed)
            val host = ManagedHost(scheduler = controller.scheduler())
            val refs = SkillPipeline.build(host)

            val matches = SetView<Match>()
            val gap = SetView<JobSkill>()
            val matchCounts = MapView<CandidateJob, Long>()
            val required = MapView<String, Long>()
            val supply = MapView<String, Long>()
            val demand = MapView<String, Long>()

            host.lookup<MatchOutletProxy>(refs.matches)!!.outlet.subscribe(
                Use.fixed(object : Propagate<SetDelta<Match>> {
                    override fun propagate(value: SetDelta<Match>) {
                        matches.apply(value)
                    }
                }, PortRef.generate())
            )
            host.lookup<GapOutletProxy>(refs.gap)!!.outlet.subscribe(
                Use.fixed(object : Propagate<SetDelta<JobSkill>> {
                    override fun propagate(value: SetDelta<JobSkill>) {
                        gap.apply(value)
                    }
                }, PortRef.generate())
            )
            host.lookup<PairCountOutletProxy>(refs.matchCounts)!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<CandidateJob, Long>> {
                    override fun propagate(value: MapDelta<CandidateJob, Long>) {
                        matchCounts.apply(value)
                    }
                }, PortRef.generate())
            )
            host.lookup<JobCountOutletProxy>(refs.required)!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, Long>> {
                    override fun propagate(value: MapDelta<String, Long>) {
                        required.apply(value)
                    }
                }, PortRef.generate())
            )

            host.lookup<SkillCountOutletProxy>(refs.supply)!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, Long>> {
                    override fun propagate(value: MapDelta<String, Long>) {
                        supply.apply(value)
                    }
                }, PortRef.generate())
            )
            host.lookup<SkillCountOutletProxy>(refs.demand)!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, Long>> {
                    override fun propagate(value: MapDelta<String, Long>) {
                        demand.apply(value)
                    }
                }, PortRef.generate())
            )

            val candOps = host.lookup<CandidateInletProxy>(refs.candSkills)!!.inlet.call
            val jobOps = host.lookup<JobInletProxy>(refs.jobSkills)!!.inlet.call

            val rnd = Random(seed)
            val heldCand = mutableSetOf<CandidateSkill>()
            val heldJob = mutableSetOf<JobSkill>()
            repeat(80) {
                if (rnd.nextInt(2) == 0) {
                    val e = CandidateSkill(candidates[rnd.nextInt(candidates.size)], skills[rnd.nextInt(skills.size)])
                    if (e in heldCand && rnd.nextInt(10) < 4) {
                        candOps.remove(e); heldCand -= e
                    } else {
                        candOps.add(e); heldCand += e
                    }
                } else {
                    val e = JobSkill(jobs[rnd.nextInt(jobs.size)], skills[rnd.nextInt(skills.size)])
                    if (e in heldJob && rnd.nextInt(10) < 4) {
                        jobOps.remove(e); heldJob -= e
                    } else {
                        jobOps.add(e); heldJob += e
                    }
                }
                if (rnd.nextInt(5) == 0) controller.runToIdle()
            }
            controller.runToIdle()

            // batch recompute over the final inputs
            val batchMatches = heldCand.flatMap { cs ->
                heldJob.filter { it.skill == cs.skill }.map { js -> Match(cs.candidate, js.job, cs.skill) }
            }.toSet()
            val batchMatchCounts = batchMatches.groupBy { CandidateJob(it.candidate, it.job) }
                .mapValues { it.value.size.toLong() }
            val batchRequired = heldJob.groupBy { it.job }.mapValues { it.value.size.toLong() }
            val candSkillSet = heldCand.map { it.skill }.toSet()
            val batchGap = heldJob.filter { it.skill !in candSkillSet }.toSet()
            val batchSupply = heldCand.groupBy { it.skill }.mapValues { it.value.size.toLong() }
            val batchDemand = heldJob.groupBy { it.skill }.mapValues { it.value.size.toLong() }

            assertEquals(batchMatches, matches.current(), "seed=$seed matches diverged")
            assertEquals(batchMatchCounts, matchCounts.current(), "seed=$seed matchCounts diverged")
            assertEquals(batchRequired, required.current(), "seed=$seed required diverged")
            assertEquals(batchGap, gap.current(), "seed=$seed gap diverged")
            assertEquals(batchSupply, supply.current(), "seed=$seed supply diverged")
            assertEquals(batchDemand, demand.current(), "seed=$seed demand diverged")

            // qualified derived both ways (the hub-side comparison, F-1)
            val incQualified = matchCounts.current().filter { (cj, n) -> required[cj.job] == n }.keys
            val batchQualified = batchMatchCounts.filter { (cj, n) -> batchRequired[cj.job] == n }.keys
            assertEquals(batchQualified, incQualified, "seed=$seed qualified diverged")
        }
    }
}
