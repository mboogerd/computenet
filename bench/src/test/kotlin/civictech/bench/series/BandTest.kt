package civictech.bench.series

import civictech.bench.BenchResult
import civictech.bench.COMBINED_ERROR_MARGIN
import civictech.bench.Drive
import civictech.bench.EffectResolution
import civictech.bench.RunEnvironment
import civictech.bench.resolveEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The band and the comparator: how a band forms, what it refuses to form from, and the
 * property that matters most — that "beyond the band" is `computenet-785b`'s effect-size
 * criterion with history on one side, not a second, independently-drifting rule.
 */
class BandTest {

    // -------------------------------------------------------------------------------
    // Band formation
    // -------------------------------------------------------------------------------

    @Test
    fun `fewer than the minimum quiesced entries forms no band`() {
        assertNull(HistoricalBand.of(emptyList()))
        assertNull(HistoricalBand.of(entries(10.0, 11.0)))
        assertNotNull(HistoricalBand.of(entries(10.0, 11.0, 12.0)))
    }

    @Test
    fun `shared-host entries are retained but do not contribute to a band`() {
        val quiesced = entries(10.0, 11.0, 12.0)
        val shared = entries(500.0, hostState = HostState.SHARED)

        val band = HistoricalBand.of(quiesced + shared)!!

        assertEquals(3, band.sampleCount)
        assertEquals(11.0, band.centre)
        // The 500.0 outlier would have moved a mean-and-range band enormously.
        assertEquals(1.0, band.runToRunHalfWidth)
    }

    @Test
    fun `three shared entries and no quiesced ones form no band at all`() {
        assertNull(HistoricalBand.of(entries(10.0, 11.0, 12.0, hostState = HostState.SHARED)))
    }

    @Test
    fun `the centre is the median, so one pathological run moves it by one position`() {
        val band = HistoricalBand.of(entries(10.0, 11.0, 12.0, 13.0, 900.0))!!
        assertEquals(12.0, band.centre)
    }

    @Test
    fun `the half-width is the larger of run-to-run spread and worst within-run error`() {
        // Spread dominates: 4 wide, error bars 0.1.
        val spreadDominates = HistoricalBand.of(
            entries(10.0, 12.0, 14.0, dispersion = 0.1),
        )!!
        assertEquals(2.0, spreadDominates.runToRunHalfWidth)
        assertEquals(0.1, spreadDominates.worstWithinRunError)
        assertEquals(2.0, spreadDominates.halfWidth)

        // Error dominates: three near-identical runs each claiming ±1.0. A band narrower
        // than any member's own stated precision would claim more resolution than the
        // data has.
        val errorDominates = HistoricalBand.of(
            entries(10.0, 10.01, 10.02, dispersion = 1.0),
        )!!
        assertTrue(errorDominates.runToRunHalfWidth < 0.02)
        assertEquals(1.0, errorDominates.halfWidth)
    }

    @Test
    fun `a band refuses entries that disagree about the unit`() {
        val mixed = entries(10.0, 11.0, 12.0) + listOf(entry(13.0, unit = "ops/s"))

        val failure = assertThrows<IllegalArgumentException> { HistoricalBand.of(mixed) }
        assertTrue(failure.message!!.contains("one unit"), failure.message)
    }

    @Test
    fun `a band refuses entries for two different measurements`() {
        val mixed = entries(10.0, 11.0, 12.0) + listOf(entry(13.0, params = mapOf("degree" to "64")))

        val failure = assertThrows<IllegalArgumentException> { HistoricalBand.of(mixed) }
        assertTrue(failure.message!!.contains("one measurement"), failure.message)
    }

    // -------------------------------------------------------------------------------
    // The comparator
    // -------------------------------------------------------------------------------

    @Test
    fun `no history yields InsufficientHistory, which is explicitly not a pass`() {
        val comparison = SeriesComparator.compare(entry(10.0, runId = "fresh"), emptyList())

        assertEquals(BandVerdict.InsufficientHistory, comparison.verdict)
        assertNull(comparison.band)
        assertNull(comparison.delta)
        assertTrue(comparison.describe().contains("INSUFFICIENT HISTORY"), comparison.describe())
        assertTrue(comparison.describe().contains("No claim is made"), comparison.describe())
    }

    @Test
    fun `a run inside the combined error bars is WithinBand`() {
        val history = entries(10.0, 11.0, 12.0, dispersion = 0.1)
        // Band: centre 11.0, halfWidth 1.0. Fresh run at 11.8 ± 0.1 -> effect 0.8,
        // bar 1.1. Not resolved.
        val comparison = SeriesComparator.compare(entry(11.8, dispersion = 0.1, runId = "fresh"), history)

        assertEquals(BandVerdict.WithinBand, comparison.verdict)
        assertEquals(0.8, comparison.delta!!, 1e-9)
        assertEquals(1.1, comparison.bar!!, 1e-9)
    }

    @Test
    fun `a run beyond the combined error bars is MovedHigher or MovedLower by sign`() {
        val history = entries(10.0, 11.0, 12.0, dispersion = 0.1)

        val higher = SeriesComparator.compare(entry(13.0, dispersion = 0.1, runId = "fresh"), history)
        assertEquals(BandVerdict.MovedHigher, higher.verdict)

        val lower = SeriesComparator.compare(entry(9.0, dispersion = 0.1, runId = "fresh"), history)
        assertEquals(BandVerdict.MovedLower, lower.verdict)
    }

    @Test
    fun `an effect exactly equal to the bar is not resolved — the comparison is strict`() {
        // Band centre 11.0, halfWidth 1.0; fresh dispersion 0.5 -> bar 1.5. Place the run
        // exactly 1.5 above the centre.
        val history = entries(10.0, 11.0, 12.0, dispersion = 0.1)
        val comparison = SeriesComparator.compare(entry(12.5, dispersion = 0.5, runId = "fresh"), history)

        assertEquals(1.5, comparison.delta!!, 1e-9)
        assertEquals(1.5, comparison.bar!!, 1e-9)
        assertEquals(BandVerdict.WithinBand, comparison.verdict)
    }

    @Test
    fun `entries under a different environment fingerprint are excluded, never averaged in`() {
        // The exact shape this ticket was filed from: a JDK-vendor substitution and an
        // M2 Pro / M3 Max comparison, both of which produced incomparable numbers.
        val otherMachine = entries(10.0, 11.0, 12.0).map {
            it.copy(env = it.env.copy(cpuModel = "Apple M2 Pro", coreCount = 10))
        }
        val otherJdk = entries(10.0, 11.0, 12.0).map {
            it.copy(env = it.env.copy(jvmVendor = "Homebrew", jvmVersion = "26.0.1"))
        }

        val comparison = SeriesComparator.compare(
            entry(50.0, runId = "fresh"),
            otherMachine + otherJdk,
        )

        assertEquals(BandVerdict.InsufficientHistory, comparison.verdict)
    }

    @Test
    fun `the harness commit is NOT part of the fingerprint, so a series spans commits`() {
        val history = entries(10.0, 11.0, 12.0).mapIndexed { i, e ->
            e.copy(env = e.env.copy(harnessCommitSha = "commit-$i"))
        }

        val comparison = SeriesComparator.compare(
            entry(11.0, runId = "fresh").let { it.copy(env = it.env.copy(harnessCommitSha = "commit-new")) },
            history,
        )

        assertEquals(BandVerdict.WithinBand, comparison.verdict)
        assertEquals(3, comparison.band!!.sampleCount)
    }

    @Test
    fun `a run is never compared against itself`() {
        // Re-running `append` for a run already in the file must not let that run's own
        // rows seed the band it is judged against.
        val history = entries(10.0, 11.0, 12.0) + listOf(entry(10.5, runId = "fresh"))

        val comparison = SeriesComparator.compare(entry(10.5, runId = "fresh"), history)

        assertEquals(3, comparison.band!!.sampleCount)
    }

    // -------------------------------------------------------------------------------
    // The property that keeps this from becoming a second criterion
    // -------------------------------------------------------------------------------

    @Test
    fun `beyond-the-band agrees with resolveEffect over two BenchResults, row for row`() {
        // The band comparator reaches `resolveEffect(effect, combinedError)`; the rest of
        // the module reaches `resolveEffect(left, right)`. This asserts they are the same
        // criterion by driving both over the same numbers. If COMBINED_ERROR_MARGIN or
        // the strictness of `>` is ever changed on one side only, this fails.
        val history = entries(10.0, 11.0, 12.0, dispersion = 0.1)
        val band = HistoricalBand.of(history)!!

        listOf(9.0, 10.5, 11.0, 11.8, 12.5, 13.0).forEach { value ->
            val fresh = entry(value, dispersion = 0.5, runId = "fresh")
            val viaBand = SeriesComparator.compare(fresh, history).verdict

            val viaRows = resolveEffect(
                benchResult(fresh.value, fresh.dispersion),
                benchResult(band.centre, band.halfWidth),
            )

            val expected = when (viaRows) {
                EffectResolution.Unresolved -> BandVerdict.WithinBand
                EffectResolution.Resolved ->
                    if (value > band.centre) BandVerdict.MovedHigher else BandVerdict.MovedLower
            }
            assertEquals(expected, viaBand, "disagreement at value=$value")
        }
    }

    @Test
    fun `the bar is exactly COMBINED_ERROR_MARGIN times the summed half-widths`() {
        val history = entries(10.0, 11.0, 12.0, dispersion = 0.1)
        val comparison = SeriesComparator.compare(entry(11.0, dispersion = 0.25, runId = "fresh"), history)

        assertEquals(
            COMBINED_ERROR_MARGIN * (0.25 + comparison.band!!.halfWidth),
            comparison.bar!!,
            1e-12,
        )
    }

    @Test
    fun `the report puts movement first and states all three counts`() {
        val history = entries(10.0, 11.0, 12.0, dispersion = 0.1)
        val comparisons = listOf(
            SeriesComparator.compare(entry(11.0, dispersion = 0.1, runId = "fresh"), history),
            SeriesComparator.compare(entry(30.0, dispersion = 0.1, runId = "fresh"), history),
            SeriesComparator.compare(entry(11.0, runId = "fresh", params = mapOf("degree" to "64")), history),
        )

        val text = SeriesComparator.report(comparisons)

        assertTrue(text.contains("3 row(s) — 1 moved, 1 within band, 1 without sufficient history."), text)
        assertTrue(
            text.indexOf("MOVED BEYOND BAND") < text.indexOf("WITHIN BAND"),
            "movement must be reported first:\n$text",
        )
        assertTrue(text.contains("NO BAND YET (1) — not a pass"), text)
    }

    // -------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------

    private fun entries(
        vararg values: Double,
        dispersion: Double = 0.1,
        hostState: HostState = HostState.QUIESCED,
    ): List<SeriesEntry> = values.mapIndexed { i, v ->
        entry(v, dispersion = dispersion, hostState = hostState, runId = "run-$i")
    }

    private fun entry(
        value: Double,
        dispersion: Double = 0.1,
        unit: String = "ns/op",
        hostState: HostState = HostState.QUIESCED,
        runId: String = "run-x",
        params: Map<String, String> = mapOf("degree" to "16"),
    ) = SeriesEntry(
        runId = runId,
        runTimestampUtc = "2026-08-22T07:00:00Z",
        benchmark = "civictech.bench.micro.SmokeBenchmark.baseline",
        params = params,
        mode = "avgt",
        value = value,
        dispersion = dispersion,
        unit = unit,
        hostState = hostState,
        env = RunEnvironment(
            jvmVendor = "Eclipse Adoptium",
            jvmVersion = "21.0.11",
            heapSettings = "launched with no VM options",
            cpuModel = "Apple M3 Max",
            coreCount = 16,
            os = "Mac OS X 26.6.1",
            jmhMode = "AverageTime",
            forkCount = 5,
            warmupIterations = 5,
            measurementIterations = 5,
            harnessCommitSha = "ec98411f",
        ),
    )

    private fun benchResult(value: Double, dispersion: Double) = BenchResult(
        value = value,
        unit = "ns/op",
        dispersion = dispersion,
        drive = Drive.REAL,
        env = RunEnvironment(
            jvmVendor = "Eclipse Adoptium",
            jvmVersion = "21.0.11",
            heapSettings = "launched with no VM options",
            cpuModel = "Apple M3 Max",
            coreCount = 16,
            os = "Mac OS X 26.6.1",
            jmhMode = "AverageTime",
            forkCount = 5,
            warmupIterations = 5,
            measurementIterations = 5,
            harnessCommitSha = "ec98411f",
        ),
    )
}
