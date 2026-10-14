package civictech.bench.macro

import civictech.bench.Drive
import civictech.bench.HostFacts
import civictech.bench.micro.FanDegree
import civictech.bench.micro.FanOutFixtures
import civictech.bench.micro.FanOutRig
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.durability.FileJournal
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * REAL-drive per-unit calibration for the macro cost export ([BEN2-24], mkyb1-D12).
 *
 * The local-dispatch result includes one delivery and the scheduler hop needed to prove
 * quiescence. That is the smallest observable local-dispatch unit the harness exposes:
 * the kernel has no dispatch counter, so subtracting the fence would invent a component
 * the harness cannot measure independently.
 *
 * [realWireByte] encodes and decodes one large frame. [CalibrationExport] divides that
 * score by the encoded byte count printed by [FrameState]. The resulting ns/byte is an
 * upper bound because the fixed per-frame encode/decode cost remains amortized into the
 * byte figure rather than being guessed and subtracted.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class MacroCalibrationBenchmark {

    /** One REAL fan-out fixture at degree one, rebuilt for every JMH iteration. */
    @State(Scope.Thread)
    open class DispatchState {
        lateinit var rig: FanOutRig

        @Setup(Level.Trial)
        fun announceHost() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Setup(Level.Iteration)
        fun openRig() {
            rig = FanOutFixtures.rig(FanDegree.D1, Drive.REAL)
        }

        @TearDown(Level.Iteration)
        fun closeRig() {
            rig.close()
        }
    }

    /** Small and large propagate frames, built once inside each measuring fork. */
    @State(Scope.Thread)
    open class FrameState {
        lateinit var smallFrame: HostedPortInvocation
        lateinit var largeFrame: HostedPortInvocation

        @Setup(Level.Trial)
        fun openFrames() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
            smallFrame = frame(elements = 1)
            largeFrame = frame(elements = LARGE_ELEMENTS)
            println("${CalibrationExport.FRAME_BYTES_PREFIX} ${WireCodec.encode(largeFrame).size}")
            println(
                "${CalibrationExport.SMALL_FRAME_BYTES_PREFIX} " +
                    WireCodec.encode(smallFrame).size,
            )
        }

        private fun frame(elements: Int): HostedPortInvocation {
            val source = UUID.randomUUID()
            val delta = SetDelta<Long>(
                adds = (0L until elements.toLong()).associateWith { element ->
                    setOf(Timestamp(source, element + 1))
                },
            )
            val method = Propagate::class.java.getMethod("propagate", Any::class.java)
            return HostedPortInvocation(
                cellRef = CellRef(UUID.randomUUID()),
                portName = "inlet",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(method, arrayOf(delta), null),
            )
        }
    }

    /** A fresh synchronous file journal for each fork. */
    @State(Scope.Thread)
    open class JournalState {
        lateinit var journal: FileJournal
        val record: ByteArray = ByteArray(JOURNAL_RECORD_BYTES)
        private lateinit var tempDir: Path

        @Setup(Level.Trial)
        fun openJournal() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
            tempDir = Files.createTempDirectory("computenet-macro-calibration-")
            journal = FileJournal(tempDir.resolve("calibration.journal").toFile())
        }

        @TearDown(Level.Trial)
        fun closeJournal() {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Benchmark
    fun realLocalDispatch(state: DispatchState, blackhole: Blackhole) {
        blackhole.consume(state.rig.applyOneAndQuiesce())
    }

    @Benchmark
    fun realCrossHostFrame(state: FrameState, blackhole: Blackhole) {
        blackhole.consume(WireCodec.decode(WireCodec.encode(state.smallFrame)))
    }

    @Benchmark
    fun realWireByte(state: FrameState, blackhole: Blackhole) {
        blackhole.consume(WireCodec.decode(WireCodec.encode(state.largeFrame)))
    }

    @Benchmark
    fun realJournalFsync(state: JournalState, blackhole: Blackhole) {
        state.journal.append(state.record)
        blackhole.consume(state.record.size)
    }

    companion object {
        const val LARGE_ELEMENTS: Int = 4_096
        const val JOURNAL_RECORD_BYTES: Int = 64
    }
}
