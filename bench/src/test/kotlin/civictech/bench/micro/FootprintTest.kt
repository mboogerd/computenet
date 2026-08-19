package civictech.bench.micro

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import civictech.cell.Timestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.math.abs

/**
 * The instrument's arithmetic and classification, on structures small enough to reason
 * about by hand — the FAST, UNTAGGED half of computenet-x9e.6.1.
 *
 * Everything expensive lives in `CellFootprintProbeTest` behind `@Tag("bench")`
 * (`[BEN1-10]` via F2). The one exception is [heapProbeMeasuresRealBytes], which spends a
 * handful of `System.gc()` calls, and it earns them: it is the only test in the tree that
 * checks the measurement PRIMITIVE against a quantity whose size is fixed by the JVM
 * rather than by this code. Without it, every other assertion here would be arithmetic
 * over numbers that might not be bytes at all.
 */
class FootprintTest {

    private val env = RunEnvironment(
        jvmVendor = "Test Vendor",
        jvmVersion = "21.0.11",
        heapSettings = "-Xmx2g",
        cpuModel = "Test CPU",
        coreCount = 10,
        os = "Test OS 1.0",
        jmhMode = FootprintReport.MODE,
        forkCount = 1,
        warmupIterations = 1,
        measurementIterations = 10,
        harnessCommitSha = "cafebabe",
    )

    // ---------------------------------------------------------------------------------
    // The measurement primitive.
    // ---------------------------------------------------------------------------------

    /**
     * A `long[]` of `m` elements occupies `8 * m` bytes of payload plus a small header — a
     * HotSpot guarantee about primitive array element width, not a modelled field layout —
     * so 8 MiB of longs must measure as 8 MiB. This is the calibration that makes
     * [HeapProbe.retainedBytes] a measurement of bytes rather than of an arbitrary counter.
     *
     * ## Why 256 chunks and not one array
     *
     * The first version of this test allocated a single `LongArray(1 shl 20)` and FAILED,
     * measuring 9 MiB for 8 MiB of payload — exactly 9 of G1's 1 MiB regions. That is G1's
     * humongous-object accounting, not an instrument defect, and it is documented on
     * [HeapProbe] along with what it means for a 1e5 footprint total. Chunks below G1's
     * humongous threshold (half a region) go through ordinary region allocation, where
     * heap-used is byte-accurate — so this test pins the primitive's accuracy in the regime
     * the humongous rounding does not distort, and the KDoc carries the regime it does.
     *
     * ## What the 5% tolerance is actually covering
     *
     * Measured, not guessed at: 256 x 32 KiB of payload reports ~3.1% more than the payload
     * itself (8_650_880 for 8_388_608). The per-chunk object headers are 4 KiB of that and
     * the holder array ~1 KiB; the remaining ~256 KiB is allocator fill waste — HotSpot
     * fills the unusable tail of a retiring TLAB with a filler object, which is live heap
     * and is counted as used. That is real occupancy of the same kind as the humongous
     * rounding above, and it is documented on [HeapProbe] for the same reason.
     *
     * What the tolerance is emphatically NOT for is a modelling error: a delta 8x or 0.1x
     * the true size would mean the primitive is measuring something other than bytes, and
     * that is what this test catches.
     */
    @Test
    fun heapProbeMeasuresRealBytes() {
        HeapProbe.requireCollectableHeap()
        val chunks = 256
        val longsPerChunk = 4_096 // 32 KiB, well under G1's humongous threshold
        val expected = 8L * chunks * longsPerChunk
        val measured = HeapProbe.retainedBytes {
            Array(chunks) { LongArray(longsPerChunk) }
        }
        val relativeError = abs(measured - expected).toDouble() / expected
        assertTrue(
            relativeError < 0.05,
            "retainedBytes measured $measured for $chunks x LongArray($longsPerChunk) whose " +
                "payload is $expected bytes (relative error $relativeError)",
        )
    }

    /** A window that holds nothing retains nothing beyond the instrument's own noise. */
    @Test
    fun holdingNothingRetainsNothingBeyondNoise() {
        HeapProbe.requireCollectableHeap()
        val noise = HeapProbe.noiseFloorBytes(samples = 3)
        assertTrue(
            noise < 1 shl 20,
            "the instrument's measured noise floor is $noise bytes, which is larger than " +
                "the 1 MiB this host should settle within — every attribution derived " +
                "from it would be unresolvable",
        )
    }

    // ---------------------------------------------------------------------------------
    // Classification: what the walk finds, and what it deliberately does not.
    // ---------------------------------------------------------------------------------

    /**
     * A hand-built stand-in for `SetCell`'s snapshot shape: element -> add-tags, plus a
     * boxed tag counter. Payload is the two `Integer` elements; tag/metadata is the three
     * `Timestamp`s and the one shared source `UUID`; the counter, the maps and the sets
     * are neither, and so belong to the unattributed remainder.
     */
    @Test
    fun walkClassifiesPayloadAndTagsOverATinySetShape() {
        val source = UUID.nameUUIDFromBytes("footprint-test".toByteArray())
        val first = 1_000
        val second = 1_001
        val snapshot = HashMap(
            mapOf(
                "adds" to HashMap(
                    mapOf(
                        first to HashSet(listOf(Timestamp(source, 1), Timestamp(source, 2))),
                        second to HashSet(listOf(Timestamp(source, 3))),
                    ),
                ),
                "counter" to 3L,
            ),
        )

        val walk = StateWalk.walk(snapshot, setOf(java.lang.Integer::class.java))

        assertEquals(
            setOf(first, second),
            walk.payload.map { it as Int }.toSet(),
            "payload must be exactly the inserted elements",
        )
        assertEquals(
            3,
            walk.tagMetadata.count { it is Timestamp },
            "every Timestamp in the graph is tag/metadata",
        )
        assertEquals(
            1,
            walk.tagMetadata.count { it is UUID },
            "the tags' shared source id is tag/metadata, and is one object",
        )
        assertFalse(
            walk.payload.any { it is Long },
            "the boxed tag counter is not payload for an Integer-payload subject — it " +
                "belongs to the unattributed remainder",
        )
        assertEquals(0, walk.opaque, "nothing in this shape should be opaque to the walk")
    }

    /**
     * Identity, not equality. Two `Timestamp`s that are `==` but distinct objects are two
     * objects; one object reachable twice is one. Counting a shared tag twice is exactly
     * how an attribution would come to exceed the total it is attributed from.
     */
    @Test
    fun walkDedupsByIdentityNotEquality() {
        val source = UUID.randomUUID()
        val shared = Timestamp(source, 7)
        val equalButDistinct = Timestamp(source, 7)
        assertEquals(shared, equalButDistinct, "the fixture needs two equal, distinct tags")

        val shapeWithSharedTag = listOf(listOf(shared), listOf(shared))
        val sharedWalk = StateWalk.walk(shapeWithSharedTag, emptySet())
        assertEquals(
            1,
            sharedWalk.tagMetadata.count { it is Timestamp },
            "one Timestamp reachable by two paths is one object",
        )
        assertSame(shared, sharedWalk.tagMetadata.first { it is Timestamp })

        val shapeWithEqualTags = listOf(listOf(shared), listOf(equalButDistinct))
        val equalWalk = StateWalk.walk(shapeWithEqualTags, emptySet())
        assertEquals(
            2,
            equalWalk.tagMetadata.count { it is Timestamp },
            "two equal but distinct Timestamps are two objects on the heap",
        )
    }

    /** A `null` root is an empty walk, not a crash. */
    @Test
    fun walkOfNothingFindsNothing() {
        val walk = StateWalk.walk(null, setOf(java.lang.Integer::class.java))
        assertEquals(WalkResult(emptyList(), emptyList(), 0, 0), walk)
    }

    // ---------------------------------------------------------------------------------
    // The catalog `[BEN1-20]` names.
    // ---------------------------------------------------------------------------------

    @Test
    fun catalogCoversTheSevenFamiliesAtThreeScales() {
        assertEquals(
            listOf(
                "SetCell", "MapCell", "OrMapCell", "KeyedSetCell", "ListCell",
                "CounterCell", "PnCounterCell",
            ),
            Footprint.FAMILIES.map { it.name },
        )
        assertEquals(listOf(1_000, 10_000, 100_000), Footprint.SCALES)
        // The enums are what JMH's @Param enumerates, so a constant without a fixture
        // would be a benchmark combination with nothing behind it.
        assertEquals(
            CellFamily.entries.size,
            Footprint.FAMILIES.size,
            "every CellFamily constant needs a fixture",
        )
        CellFamily.entries.forEach { family ->
            assertEquals(family, Footprint.of(family).family)
            assertEquals(family.cellName, Footprint.of(family).name)
        }
        assertEquals(
            Scale.entries.map { it.elements },
            Footprint.SCALES,
            "SCALES is derived from Scale, not restated",
        )
        assertEquals(
            listOf("CounterCell", "PnCounterCell"),
            Footprint.FAMILIES.filterNot { it.scalesWithElements }.map { it.name },
            "only the two counters have state that does not grow with the write count",
        )
        assertEquals("SetCell", Footprint.byName("SetCell").name)
        assertThrows<FootprintMeasurementException> { Footprint.byName("NoSuchCell") }
    }

    /**
     * Element values must clear `Integer.valueOf`'s -128..127 cache, or the payload
     * objects would be JDK-interned instances that were already live before the
     * measurement began — and payload attribution would silently under-report by exactly
     * those objects.
     */
    @Test
    fun elementBaseClearsTheIntegerCache() {
        assertTrue(
            Footprint.ELEMENT_BASE > 127,
            "ELEMENT_BASE ${Footprint.ELEMENT_BASE} is inside Integer.valueOf's cache",
        )
    }

    // ---------------------------------------------------------------------------------
    // Multiplicity: derived from a calibration measurement, with both bounds explicit.
    // ---------------------------------------------------------------------------------

    @Test
    fun multiplicityAimsAtTheTargetSignalAndRespectsTheWriteBudget() {
        // A 20 MiB structure already clears the target on its own.
        assertEquals(
            1,
            Footprint.multiplicityFor(20L * 1024 * 1024, elements = 100_000),
        )
        // A 200 KiB structure wants ~40 copies to reach 8 MiB, and 40 x 1e3 writes fits.
        assertEquals(
            (Footprint.TARGET_SIGNAL_BYTES / (200L * 1024)).toInt(),
            Footprint.multiplicityFor(200L * 1024, elements = 1_000),
        )
        // A structure the instrument cannot resolve asks for the most the budget allows,
        // which is the belowResolution case's best chance of not being one.
        assertEquals(
            Footprint.MAX_WRITES_PER_WINDOW / 100_000,
            Footprint.multiplicityFor(0L, elements = 100_000),
        )
        assertEquals(
            Footprint.MAX_WRITES_PER_WINDOW / 1_000,
            Footprint.multiplicityFor(-4_096L, elements = 1_000),
        )
        // Never zero, however unaffordable the scale.
        assertEquals(1, Footprint.multiplicityFor(0L, elements = 10_000_000))
    }

    @Test
    fun measureRefusesAnImpossibleRequest() {
        val subject = Footprint.byName("CounterCell")
        assertThrows<FootprintMeasurementException> { Footprint.measure(subject, elements = 0) }
        assertThrows<FootprintMeasurementException> {
            Footprint.measure(subject, elements = 1, replicates = 1)
        }
    }

    // ---------------------------------------------------------------------------------
    // Statistics: the same statistic BenchResult.dispersion is defined as.
    // ---------------------------------------------------------------------------------

    @Test
    fun statDerivesThe99Point9PercentErrorOfTheMean() {
        val samples = listOf(1_000L, 1_010L, 990L, 1_000L, 1_000L)
        val stat = Stat.of(samples)
        assertEquals(1_000.0, stat.mean, 1e-9)
        // s = sqrt(((0)^2+(10)^2+(-10)^2+0+0)/4) = sqrt(50) ; t(0.9995, 4) = 8.610
        val expected = 8.610 * kotlin.math.sqrt(50.0) / kotlin.math.sqrt(5.0)
        assertEquals(expected, stat.dispersion, 1e-6)
        assertEquals(5, stat.samples)
    }

    @Test
    fun statRefusesFewerThanTwoSamples() {
        assertThrows<FootprintMeasurementException> { Stat.of(listOf(42L)) }
        assertThrows<FootprintMeasurementException> { Stat.of(emptyList()) }
    }

    @Test
    fun tQuantileFallsBackToTheNormalLimitPastTheTable() {
        assertEquals(8.610, Stat.tQuantile(4), 1e-9)
        assertEquals(3.646, Stat.tQuantile(30), 1e-9)
        assertEquals(Stat.T_LARGE_DF, Stat.tQuantile(31), 1e-9)
        assertEquals(Stat.T_LARGE_DF, Stat.tQuantile(10_000), 1e-9)
    }

    @Test
    fun scalingIsExactAndKeepsDispersionNonNegative() {
        val stat = Stat(mean = 2_000.0, dispersion = 10.0, samples = 5)
        val perElement = stat.scaled(1.0 / 1_000)
        assertEquals(2.0, perElement.mean, 1e-9)
        assertEquals(0.01, perElement.dispersion, 1e-9)
        // A negative factor flips the mean and must not produce a negative dispersion,
        // which BenchResult would refuse outright.
        assertEquals(10.0, stat.scaled(-1.0).dispersion, 1e-9)
    }

    // ---------------------------------------------------------------------------------
    // Attribution: the residual is reported, never split. [BEN1-21]
    // ---------------------------------------------------------------------------------

    @Test
    fun unattributedIsTheMeasuredRemainderWithPropagatedError() {
        val measurement = measurement(
            total = Stat(mean = 1_000_000.0, dispersion = 300.0, samples = 10),
            payload = Stat(mean = 160_000.0, dispersion = 40.0, samples = 10),
            tagMetadata = Stat(mean = 320_000.0, dispersion = 30.0, samples = 10),
        )
        assertEquals(520_000.0, measurement.unattributed.mean, 1e-9)
        assertEquals(
            kotlin.math.sqrt(300.0 * 300 + 40.0 * 40 + 30.0 * 30),
            measurement.unattributed.dispersion,
            1e-9,
        )
        assertEquals(10.0, measurement.bytesPerElement.mean, 1e-9)
    }

    /**
     * With nothing attributed, the whole total is unattributed — the shape a snapshot
     * carrying neither payload nor tag objects produces, and the case `[BEN1-21]` is
     * about. Nothing scales the components up to meet the total.
     */
    @Test
    fun withNoAttributionTheWholeTotalIsUnattributed() {
        val measurement = measurement(
            total = Stat(mean = 4_096.0, dispersion = 8.0, samples = 4),
            payload = Stat(mean = 0.0, dispersion = 0.0, samples = 4),
            tagMetadata = Stat(mean = 0.0, dispersion = 0.0, samples = 4),
        )
        assertEquals(4_096.0, measurement.unattributed.mean, 1e-9)
        assertEquals(8.0, measurement.unattributed.dispersion, 1e-9)
    }

    /**
     * A residual smaller than its own error bars is reported as measured, with the error
     * that says so — not clamped to zero, and not hidden.
     */
    @Test
    fun aResidualInsideItsOwnErrorBarsIsStillReported() {
        val measurement = measurement(
            total = Stat(mean = 1_000.0, dispersion = 500.0, samples = 3),
            payload = Stat(mean = 600.0, dispersion = 100.0, samples = 3),
            tagMetadata = Stat(mean = 500.0, dispersion = 100.0, samples = 3),
        )
        assertEquals(-100.0, measurement.unattributed.mean, 1e-9)
        assertTrue(
            abs(measurement.unattributed.mean) < measurement.unattributed.dispersion,
            "this fixture is meant to produce a residual inside its own error bars",
        )
    }

    /**
     * A total inside its own error bars is a measurement of nothing, and the instrument has
     * to say so rather than publish the figure. The fixture is `CounterCell`'s real shape at
     * 1e5, taken from a full-scale run of `CellFootprintProbeTest`: total 46.4 ± 126.2
     * bytes at multiplicity 2, with the baseline drift that run measured — **0 bytes**.
     *
     * ## Why `noiseFloorBytes` is 0 here, and why that is the whole point
     *
     * `belowResolution` has two bounds, and only the DISPERSION one is the fix this test
     * exists for; the baseline-drift bound predates it and is exercised separately by
     * [aDriftingBaselineAlsoPutsASubjectBelowResolution]. With the helper's default
     * `noiseFloorBytes = 4096` this fixture satisfied BOTH (46.4 x 2 = 92.8 <= 4096), so
     * the assertion below passed identically with the dispersion clause deleted — measured
     * by mutation, not reasoned about. Pinning the drift at the 0 bytes the real run
     * measured leaves the dispersion clause as the only thing that can make this true.
     */
    @Test
    fun belowResolutionIsReportedRatherThanRounded() {
        val tiny = measurement(
            total = Stat(mean = 46.4, dispersion = 126.2, samples = 10),
            payload = Stat(mean = 24.0, dispersion = 0.0, samples = 10),
            tagMetadata = Stat(mean = 0.0, dispersion = 0.0, samples = 10),
            multiplicity = 2,
            noiseFloorBytes = 0L,
        )
        assertTrue(
            tiny.total.mean * tiny.multiplicity > tiny.noiseFloorBytes,
            "the fixture must sit ABOVE the baseline-drift bound, or this test would pass " +
                "without the dispersion clause it exists to pin",
        )
        assertTrue(tiny.belowResolution)
        val large = measurement(
            total = Stat(mean = 20_000_000.0, dispersion = 4_000.0, samples = 10),
            payload = Stat(mean = 1_600_000.0, dispersion = 400.0, samples = 10),
            tagMetadata = Stat(mean = 3_200_000.0, dispersion = 400.0, samples = 10),
        )
        assertFalse(large.belowResolution)
        assertTrue(
            FootprintReport.provenance(listOf(tiny)).contains("BELOW RESOLUTION"),
            "provenance must name a below-resolution subject, not merely omit it",
        )
        assertTrue(FootprintReport.provenance(listOf(large)).contains("resolved: total"))
    }

    /**
     * The baseline-drift bound still fires on a host whose empty windows do drift — the
     * case the dispersion bound alone could be fooled by.
     */
    @Test
    fun aDriftingBaselineAlsoPutsASubjectBelowResolution() {
        val drifting = measurement(
            total = Stat(mean = 16.0, dispersion = 0.0, samples = 5),
            payload = Stat(mean = 16.0, dispersion = 0.0, samples = 5),
            tagMetadata = Stat(mean = 0.0, dispersion = 0.0, samples = 5),
            noiseFloorBytes = 40_000L,
            multiplicity = 2,
        )
        assertTrue(drifting.belowResolution)
    }

    /**
     * `[BEN1-20]` asks for payload and tag/metadata separately; a family whose snapshot
     * contains no `Timestamp` at all has zero of one of them, and the instrument states
     * that exact zero in the provenance rather than weighing an empty selection (which
     * measures pure noise — see `Stat.structurallyAbsent`).
     */
    @Test
    fun aStructurallyAbsentBucketIsStatedExactlyAndCarriesNoRow() {
        val noTags = FootprintMeasurement(
            subject = Footprint.byName("ListCell"),
            elements = 10_000,
            multiplicity = 20,
            total = Stat(mean = 201_547.8, dispersion = 400.0, samples = 10),
            payload = Stat(mean = 160_537.7, dispersion = 200.0, samples = 10),
            tagMetadata = Stat.structurallyAbsent(10),
            noiseFloorBytes = 0L,
            payloadCount = 10_000,
            tagCount = 0,
            visitedCount = 10_001,
            opaqueCount = 0,
        )
        assertFalse(noTags.tagPresent)
        assertTrue(noTags.payloadPresent)
        assertEquals(0.0, noTags.tagMetadata.mean, 1e-9)
        assertEquals(0.0, noTags.tagMetadata.dispersion, 1e-9)
        // The residual is unaffected: an exact zero contributes nothing to the propagation.
        assertEquals(201_547.8 - 160_537.7, noTags.unattributed.mean, 1e-6)

        val labels = FootprintReport.toResults(noTags, env).map { it.label }
        assertEquals(
            listOf(
                "ListCell n=10000 total retained",
                "ListCell n=10000 payload",
                "ListCell n=10000 UNATTRIBUTED",
                "ListCell n=10000 total per element",
            ),
            labels,
            "a structurally absent bucket carries no row — its exact zero is in the provenance",
        )
        val provenance = FootprintReport.provenance(listOf(noTags))
        assertTrue(
            provenance.contains("tag/metadata is EXACTLY 0 bytes and has no table row"),
            provenance,
        )
        assertTrue(provenance.contains("structurally absent rather than measured small"), provenance)
    }

    // ---------------------------------------------------------------------------------
    // The F3 handoff: every number leaves through BenchResult, under one environment.
    // ---------------------------------------------------------------------------------

    @Test
    fun everyRowLeavesAsARealDriveBenchResult() {
        val measurement = measurement(
            total = Stat(mean = 1_000_000.0, dispersion = 300.0, samples = 10),
            payload = Stat(mean = 160_000.0, dispersion = 40.0, samples = 10),
            tagMetadata = Stat(mean = 320_000.0, dispersion = 30.0, samples = 10),
        )
        val rows = FootprintReport.toResults(measurement, env)
        assertEquals(
            listOf(
                "SetCell n=100000 total retained",
                "SetCell n=100000 payload",
                "SetCell n=100000 tag/metadata",
                "SetCell n=100000 UNATTRIBUTED",
                "SetCell n=100000 total per element",
            ),
            rows.map { it.label },
        )
        assertTrue(rows.all { it.result.drive == Drive.REAL }, "a footprint run is never SIM")
        assertTrue(rows.all { it.result.env == env })
        assertEquals(
            listOf("bytes", "bytes", "bytes", "bytes", "bytes/element"),
            rows.map { it.result.unit },
        )
    }

    /**
     * The rendered entry goes through F3's writer, so a footprint entry inherits every
     * refusal `Findings.entry` makes — including its trigger-verdict check. The renderer
     * excludes the too-dispersed rows and names them, exactly as it does for throughput.
     */
    @Test
    fun renderGoesThroughTheFindingsWriterAndNamesItsOmissions() {
        val clean = measurement(
            total = Stat(mean = 1_000_000.0, dispersion = 100.0, samples = 10),
            payload = Stat(mean = 160_000.0, dispersion = 10.0, samples = 10),
            tagMetadata = Stat(mean = 320_000.0, dispersion = 10.0, samples = 10),
        )
        val noisy = measurement(
            elements = 1_000,
            // 50% relative dispersion: far past NOISE_FLOOR, so this row is excluded.
            total = Stat(mean = 1_000.0, dispersion = 500.0, samples = 10),
            payload = Stat(mean = 100.0, dispersion = 50.0, samples = 10),
            tagMetadata = Stat(mean = 200.0, dispersion = 100.0, samples = 10),
        )
        val report = FootprintReport.render(
            measurements = listOf(clean, noisy),
            date = "2026-08-19",
            subject = "per-cell retained footprint",
            env = env,
            trigger = TriggerClaim.Cited(
                gapId = "G-21 phase 3",
                statement = "INCONCLUSIVE — this is a unit test's fixture, not a measurement.",
            ),
        )
        val text = report.text()
        assertTrue(text.contains("drive=REAL"), text)
        assertTrue(text.contains("SetCell n=100000 UNATTRIBUTED"), text)
        assertTrue(text.contains("Trigger: G-21 phase 3"), text)
        assertTrue(report.omissions.isNotEmpty(), "the noisy rows must be named as omissions")
        assertTrue(
            report.omissions.all { it.label.startsWith("SetCell n=1000 ") },
            "only the noisy measurement's rows are omitted: ${report.omissions.map { it.label }}",
        )
    }

    @Test
    fun theInProcessEnvironmentDescribesThisJvm() {
        val jvm = FootprintReport.inProcessMeasuringJvm()
        assertEquals(System.getProperty("java.version"), jvm.version)
        assertTrue(
            jvm.vendor.contains(System.getProperty("java.vendor")),
            "vendor '${jvm.vendor}' must name this JVM's own vendor — the process that " +
                "measures IS the process that reports for an in-process probe",
        )
        assertTrue(jvm.heapSettings.isNotBlank())
        val environment = FootprintReport.environment(replicates = 4, harnessCommitSha = "abc1234")
        assertEquals(FootprintReport.MODE, environment.jmhMode)
        assertEquals(1, environment.forkCount)
        assertEquals(4, environment.measurementIterations)
        assertEquals("abc1234", environment.harnessCommitSha)
    }

    private fun measurement(
        total: Stat,
        payload: Stat,
        tagMetadata: Stat,
        elements: Int = 100_000,
        multiplicity: Int = 1,
        noiseFloorBytes: Long = 4_096L,
    ) = FootprintMeasurement(
        subject = Footprint.byName("SetCell"),
        elements = elements,
        multiplicity = multiplicity,
        total = total,
        payload = payload,
        tagMetadata = tagMetadata,
        noiseFloorBytes = noiseFloorBytes,
        payloadCount = elements,
        tagCount = elements,
        visitedCount = 4 * elements,
        opaqueCount = 0,
    )
}
