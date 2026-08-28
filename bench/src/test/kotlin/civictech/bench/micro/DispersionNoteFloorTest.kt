package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.ClassNoiseFloor
import civictech.bench.Drive
import civictech.bench.NOISE_FLOOR
import civictech.bench.QUIESCED_HOST_STATE
import civictech.bench.RunEnvironment
import civictech.bench.floorTable
import civictech.bench.noiseFloorFor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * That the per-class floor actually reaches the rendered dispersion note, on both
 * branches (`computenet-cm4w`).
 *
 * `ClassNoiseFloorTest` pins the resolution functions; this pins the WIRING — that
 * `DispersionNote` asks `noiseFloorFor` rather than comparing against `NOISE_FLOOR`
 * directly, that `ThroughputReport.toResults` carries each row's own benchmark class off
 * the results file, and that the rendered sentence says WHICH bound it used. The last of
 * those matters on its own: "above NOISE_FLOOR" and "above this class's floor" are
 * different claims about the world, and the note is where a reader meets them.
 *
 * Untagged, so it runs on every `:bench:test`.
 */
class DispersionNoteFloorTest {

    private val env = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.11+10-LTS",
        heapSettings = "defaults",
        cpuModel = "Apple M2 Pro",
        coreCount = 10,
        os = "Mac OS X 26.6.1",
        jmhMode = "thrpt",
        forkCount = 5,
        warmupIterations = 5,
        measurementIterations = 5,
        harnessCommitSha = "cbea0290",
    )

    /** A row whose relative dispersion is exactly [relative]. */
    private fun rowAt(relative: Double): BenchResult = BenchResult(
        value = 1000.0,
        unit = "ops/s",
        dispersion = 1000.0 * relative,
        drive = Drive.REAL,
        env = env,
    )

    private val floors = floorTable(
        listOf(
            ClassNoiseFloor(
                benchmarkClass = "OperatorThroughputBenchmark",
                observedRobustDispersion = 0.03,
                runs = 3,
                derivedOn = "2026-09-01",
                harnessCommitSha = "deadbeef",
                hostState = QUIESCED_HOST_STATE,
                jmhConfig = "mode=Throughput forks=2 warmup=5 iters=10",
                measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS",
            )
        )
    )

    @Test
    fun `a row of a class with a derived floor is measured against that floor`() {
        val note = DispersionNote(
            label = "UNION insert",
            drive = Drive.REAL,
            result = rowAt(0.02),
            benchmarkClass = "OperatorThroughputBenchmark",
            floors = floors,
        )
        note.floor shouldBe 0.06
        // 0.02 is four times the global bound and comfortably under its own class's.
        note.aboveHarnessSanityBound shouldBe false
        note.describe() shouldNotContain "above"
    }

    @Test
    fun `a row above its own class's floor says WHICH floor it exceeded`() {
        val note = DispersionNote(
            label = "UNION insert",
            drive = Drive.REAL,
            result = rowAt(0.09),
            benchmarkClass = "OperatorThroughputBenchmark",
            floors = floors,
        )
        note.aboveHarnessSanityBound shouldBe true
        note.describe() shouldContain
            "above the OperatorThroughputBenchmark class floor 0.06 (derived from that " +
            "class's own quiesced repeat runs)"
        note.describe() shouldNotContain "NOISE_FLOOR"
    }

    @Test
    fun `a class with no derived floor falls back, and the note still names NOISE_FLOOR`() {
        // "No derived floor" is relative to the `floors` table PASSED IN, which is the
        // synthetic one above and holds only `OperatorThroughputBenchmark`. It is not a
        // claim about `CLASS_NOISE_FLOOR_TABLE`, and it deliberately never was: this
        // class has in fact carried a live floor of 0.953 since `computenet-akfa` derived
        // it on 2026-08-28, and this test went on passing unchanged, which is the whole
        // point of `noiseFloorFor` taking its table as a parameter rather than reading the
        // tripwire table as a fixture. Stated here because the name alone now reads as a
        // claim about the live table that it does not make.
        val note = DispersionNote(
            label = "degree=64",
            drive = Drive.REAL,
            result = rowAt(0.02),
            benchmarkClass = "FanOutScalingBenchmark",
            floors = floors,
        )
        note.floor shouldBe NOISE_FLOOR
        note.aboveHarnessSanityBound shouldBe true
        note.describe() shouldContain "above the harness sanity bound NOISE_FLOOR $NOISE_FLOOR"
    }

    @Test
    fun `a row with no benchmark class at all falls back too, and is not an error`() {
        // `Footprint.toResults` builds rows from a heap walk; there is no JMH class to name.
        val note = DispersionNote(
            label = "TAGGED_SET total retained",
            drive = Drive.REAL,
            result = rowAt(0.02),
        )
        note.benchmarkClass shouldBe null
        note.floor shouldBe NOISE_FLOOR
        note.describe() shouldContain "NOISE_FLOOR"
    }

    /**
     * The default is the LIVE table, and since `computenet-ahn0` it is no longer empty:
     * `CellFootprintBenchmark` carries a derived floor and everything else falls back.
     * Both halves are asserted through the DEFAULT parameter — the synthetic `floors`
     * table the tests above use proves the resolution *rule*, and only this test proves
     * the rule is wired to the table the harness actually ships. A change that populated
     * or dropped an entry would fail here, which is the point.
     */
    @Test
    fun `the live table resolves the derived class to its own floor and the rest globally`() {
        // Derived: resolves to CellFootprintBenchmark's own floor, not the global bound.
        val derived = DispersionNote(
            label = "SET_CELL N1E5",
            drive = Drive.REAL,
            result = rowAt(0.02),
            benchmarkClass = "CellFootprintBenchmark",
        )
        derived.floor shouldBe noiseFloorFor("CellFootprintBenchmark")
        derived.floor shouldNotBe NOISE_FLOOR
        // 0.02 clears its class's own floor of 0.398 even though it is four times the
        // global bound — which is exactly the signal the per-class floor restores. A row
        // under its bound is not flagged, so `describe()` names no floor at all.
        derived.aboveHarnessSanityBound shouldBe false
        derived.describe() shouldNotContain "floor"

        // A row that genuinely exceeds the DERIVED floor is flagged, and the sentence
        // names the class's own floor rather than the global bound — the two are
        // different claims about the world, and this is the live-table path saying so.
        val reallyDispersed = DispersionNote(
            label = "KEYED_SET_CELL N1E5",
            drive = Drive.REAL,
            // Above the derived floor of 0.398 — see ClassNoiseFloorTest for the pin. The
            // literal is deliberately well clear of it so this stays a test of which
            // SENTENCE is rendered, not a re-derivation of the boundary.
            result = rowAt(1.5),
            benchmarkClass = "CellFootprintBenchmark",
        )
        reallyDispersed.aboveHarnessSanityBound shouldBe true
        reallyDispersed.describe() shouldContain
            "the CellFootprintBenchmark class floor ${noiseFloorFor("CellFootprintBenchmark")}"
        reallyDispersed.describe() shouldNotContain "NOISE_FLOOR"

        // Not derived: still the global bound, and still named as the global bound.
        val fallback = DispersionNote(
            label = "UNION insert",
            drive = Drive.REAL,
            result = rowAt(0.02),
            benchmarkClass = "OperatorThroughputBenchmark",
        )
        fallback.floor shouldBe NOISE_FLOOR
        fallback.describe() shouldContain "above the harness sanity bound NOISE_FLOOR"
    }

    /**
     * The class has to come off the results file, not from the caller: a renderer that
     * dropped it would fall back for every row and nobody would see a difference until a
     * floor was derived — at which point it would silently do nothing.
     */
    @Test
    fun `toResults carries each row's own benchmark class`() {
        val row = JmhRow(
            benchmark = "civictech.bench.micro.OperatorThroughputBenchmark.realInsert",
            mode = "thrpt",
            score = 1000.0,
            scoreError = 20.0,
            unit = "ops/s",
            params = mapOf("subject" to "UNION"),
        )
        val labelled = ThroughputReport.toResults(
            rows = listOf(row),
            env = env,
            label = RowLabel(params = listOf("subject")),
        )
        labelled.single().benchmarkClass shouldBe "OperatorThroughputBenchmark"
    }
}
