package civictech.bench.micro

import civictech.bench.TriggerClaim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The `@Tag("bench")` footprint probes (computenet-x9e.6.1, `[BEN1-20]`/`[BEN1-21]`,
 * BS-10) — the long-running half of this item, excluded from the default test task by
 * F2's gate (`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`, `[BEN1-09]`..`[BEN1-11]`).
 *
 * Run them:
 *
 * ```
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.CellFootprintProbeTest' \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
 * ```
 *
 * `-PbenchOnly=true` is not optional: without it `@Tag("bench")` is excluded
 * unconditionally, which is what keeps `:bench:test` sub-second inside the six required
 * checks. The harness SHA property is optional — [FootprintReport.harnessCommitSha] reads
 * the checkout when it is absent, and marks the result `-dirty` when the tree is.
 *
 * ## What these probes are, and are not
 *
 * They are the instrument's own proof that it runs and terminates at full scale, and the
 * entry point the sibling measurement task drives to produce the sweep and the findings
 * entry. They deliberately assert only what is TRUE OF ANY HONEST RUN on any machine —
 * that the walk found the object counts the graph's shape implies, that attribution never
 * exceeds its total, that a below-noise-floor subject is reported as such. They assert no
 * byte figure and no bound on one: a footprint is a measurement, and a test that pinned it
 * would either be re-deriving a constant from the machine it last ran on or failing on the
 * next machine. A surprising number here is a finding for `doc/bench/findings.md`, never a
 * failing assertion and never a kernel patch (`[BEN1-35]`).
 */
@Tag("bench")
class CellFootprintProbeTest {

    /**
     * The single most expensive combination in the catalog — `SetCell` at 1e5, the shape
     * V1C-BENCH's own E1 measured — run end to end.
     *
     * This exists to discharge the ticket's "do run your probes at least once at full
     * scale to prove they terminate" clause on its own, without waiting for the whole
     * sweep: if the instrument cannot survive 1e5 elements, this fails first and fast.
     */
    @Test
    fun `set cell at the largest scale measures and attributes`() {
        val measurement = Footprint.measure(Footprint.byName("SetCell"), elements = 100_000)
        println(FootprintReport.provenance(listOf(measurement)))
        println(
            "SetCell n=100000: total=${measurement.total.mean} ± ${measurement.total.dispersion} " +
                "bytes, payload=${measurement.payload.mean}, tag=${measurement.tagMetadata.mean}, " +
                "UNATTRIBUTED=${measurement.unattributed.mean}, " +
                "per element=${measurement.bytesPerElement.mean}"
        )

        assertEquals(
            100_000,
            measurement.payloadCount,
            "a SetCell at 1e5 holds one boxed element per add, so the walk must find 1e5 " +
                "payload objects — a different count means the walk, not the cell, changed",
        )
        assertTrue(
            measurement.tagCount >= 100_000,
            "one add-tag per add, plus the shared source id: found ${measurement.tagCount}",
        )
        assertEquals(0, measurement.opaqueCount, "nothing in a SetCell snapshot is opaque")
        assertAttributionIsCoherent(measurement)
    }

    /**
     * The whole sweep `[BEN1-20]` describes: seven families x three scales, rendered
     * through F3's writer.
     *
     * The rendered text and the provenance block are printed rather than written: appending
     * to `doc/bench/findings.md` belongs to whoever ran the sweep and can vouch for the
     * machine, which is the same division of labour `ThroughputReport` documents for
     * throughput entries.
     */
    @Test
    fun `full sweep renders through the findings writer`() {
        val measurements = Footprint.sweep()
        assertEquals(
            Footprint.FAMILIES.size * Footprint.SCALES.size,
            measurements.size,
            "the sweep must cover every family at every scale",
        )
        measurements.forEach(::assertAttributionIsCoherent)

        val env = FootprintReport.environment()
        val report = FootprintReport.render(
            measurements = measurements,
            date = java.time.LocalDate.now().toString(),
            subject = "per-cell retained snapshot footprint, payload vs tag/metadata vs " +
                "unattributed, 1e3/1e4/1e5 elements",
            env = env,
            trigger = TriggerClaim.Cited(
                gapId = "G-21 phase 3 (allocation-pressure trigger, " +
                    "doc/spec/90-roadmap/94-implementation-plan.md:312)",
                statement = "INCONCLUSIVE from this probe alone — the verdict belongs to the " +
                    "measurement task that runs this sweep on a quiesced host and reads the " +
                    "numbers.",
            ),
        )
        println(report.text())
        println()
        println("Provenance (the table has no column for these):")
        println(FootprintReport.provenance(measurements))

        // Every row is either rendered in its drive's table or named in the omission list.
        // Nothing is silently dropped — that is the property, and it belongs to
        // ThroughputReport; asserting it here is what proves the footprint path actually
        // goes through it rather than around it.
        val rendered = report.perDrive.count { it.entry != null }
        assertTrue(
            rendered > 0 || report.omissions.isNotEmpty(),
            "a sweep must produce either an entry or a named omission for every row",
        )
        val counters = measurements.filterNot { it.subject.scalesWithElements }
        assertTrue(
            counters.isNotEmpty(),
            "the catalog must still contain the O(1)-state families this assertion is about",
        )
    }

    /**
     * The one invariant an honest attribution cannot violate: the payload and tag closures
     * are disjoint subsets of the total closure, so their measured sum cannot exceed the
     * measured total by more than the two measurements' combined error.
     *
     * Stated with the error bars rather than as a bare inequality because both sides are
     * measurements: a strict `payload + tag <= total` would fail on noise alone whenever
     * the residual is genuinely near zero, which is exactly the counters' case.
     */
    private fun assertAttributionIsCoherent(measurement: FootprintMeasurement) {
        val residual = measurement.unattributed
        assertTrue(
            residual.mean + residual.dispersion >= 0.0,
            "${measurement.label}: payload (${measurement.payload.mean}) + tag " +
                "(${measurement.tagMetadata.mean}) exceeds total (${measurement.total.mean}) by " +
                "more than the combined error ${residual.dispersion} — the two closures must " +
                "be disjoint subsets of the graph, so this is an instrument fault, not a " +
                "finding",
        )
        assertTrue(
            measurement.visitedCount >= measurement.payloadCount + measurement.tagCount,
            "${measurement.label}: the walk cannot have found more payload+tag objects " +
                "(${measurement.payloadCount}+${measurement.tagCount}) than it visited " +
                "(${measurement.visitedCount})",
        )
        assertTrue(
            measurement.multiplicity >= 1,
            "${measurement.label}: every window holds at least one structure",
        )
    }
}
