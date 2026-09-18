package civictech.demo.allocatorobserve

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `AllocatorObserveApp` end to end (task `computenet-fpml.4.2`, feature
 * `computenet-fpml.4`): one poll driver over F1's spend ingest, F2's
 * declaration history and F3's report views, serving `GET /state` and an
 * `/events` SSE stream over a real loopback [civictech.demo.shell.DemoShell].
 *
 * **Every tick is explicit.** Each app is built with a `pollInterval` of one
 * day, so the background poll thread never ticks during a test, and every
 * observation follows an [AllocatorObserveApp.pollOnce] the test made itself.
 * The one exception is the dead-loop test, which is *about* the background
 * thread and therefore gives it a short interval.
 */
class AllocatorObserveAppTest {

    @TempDir
    lateinit var tmp: Path

    private val clock = AtomicReference(NOW)

    private val started = mutableListOf<AllocatorObserveApp>()
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        started.forEach { runCatching { it.stop() } }
    }

    // -----------------------------------------------------------------
    // fixture
    // -----------------------------------------------------------------

    private val log: Path get() = tmp.resolve(LOG_NAME)
    private val runDir: Path get() = tmp.resolve("run")
    private val declaration: Path get() = tmp.resolve("allocation.yaml")

    /** One valid v1 spend-log line; the shape pinned by `SpendLineClassifierTest`. */
    private fun line(project: String, workItem: String, startedAt: Instant, endedAt: Instant): String =
        """{"v":1,"project":"$project","machine":"MacBoo","work_item":"$workItem","started":"$startedAt","ended":"$endedAt"}"""

    /** [count] valid lines ending [count] hours before [NOW], one hour each — all inside the window. */
    private fun lines(count: Int, from: Int = 0): List<String> =
        (from until from + count).map { i ->
            val endedAt = NOW.minus(Duration.ofHours((i + 1).toLong()))
            line(if (i % 2 == 0) CN else GF, "fpml.4.2-$i", endedAt.minus(Duration.ofHours(1)), endedAt)
        }

    private fun append(vararg text: String) {
        Files.writeString(
            log,
            text.joinToString("") { "$it\n" },
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun writeDeclaration(text: String = VALID_DECLARATION) = Files.writeString(declaration, text)

    /**
     * An app over this fixture. [pollInterval] defaults to a day so the
     * background thread never ticks on its own; every test but the dead-loop
     * one drives [AllocatorObserveApp.pollOnce] directly.
     */
    private fun app(
        pollInterval: Duration = Duration.ofDays(1),
        now: () -> Instant = { clock.get() },
    ): AllocatorObserveApp =
        AllocatorObserveApp(
            AllocatorObserveConfig(
                logPath = log,
                runDir = runDir,
                declarationPath = declaration,
                port = 0,
                pollInterval = pollInterval,
                windowLength = WINDOW,
            ),
            now = now,
        ).also { started += it }

    private fun probe(app: AllocatorObserveApp): HttpProbe =
        HttpProbe("http://localhost:${app.boundPort}").also { closeables += it }

    private fun tap(app: AllocatorObserveApp): SseTap<JsonElement> =
        SseTap("http://localhost:${app.boundPort}/events") { Json.parseToJsonElement(it) }
            .also { closeables += it }

    private fun JsonElement.obj(vararg path: String): JsonElement =
        path.fold(this) { element, key -> element.jsonObject.getValue(key) }

    private fun JsonElement.recordCount(): Int = obj("ingest", "recordCount").jsonPrimitive.int

    // -----------------------------------------------------------------
    // rule 1 — a growing log is reflected by GET /state, poll after poll
    // -----------------------------------------------------------------

    @Test
    fun `a growing spend log is served with its record count, checkpoint offset and report`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        val app = app().start()
        val probe = probe(app)

        val first = Json.parseToJsonElement(probe.state())
        first.recordCount() shouldBe 3
        first.obj("ingest", "checkpointOffset").jsonPrimitive.long shouldBe Files.size(log)
        first.obj("ingest", "failures", "malformed").jsonPrimitive.long shouldBe 0L
        first.obj("ingest", "failures", "unknownVersion").jsonPrimitive.long shouldBe 0L
        first.obj("ingest", "failures", "declarationParseFailed").jsonPrimitive.long shouldBe 0L
        first.obj("ingest", "declarationEvents").jsonPrimitive.int shouldBe 1
        first.obj("report", "window", "perProject").jsonObject.keys.shouldNotBeEmpty()
        first.obj("report", "cap", "projectionRule").jsonPrimitive.content shouldBe "linear"

        append(*lines(2, from = 3).toTypedArray())
        app.pollOnce()

        val second = Json.parseToJsonElement(probe.state())
        second.recordCount() shouldBe 5
        second.obj("ingest", "checkpointOffset").jsonPrimitive.long shouldBe Files.size(log)
        second.obj("ingest", "polls").jsonPrimitive.long shouldBe 2L
    }

    // -----------------------------------------------------------------
    // rule 2 — exactly one SSE frame per poll, equal to GET /state
    // -----------------------------------------------------------------

    @Test
    fun `one poll of three new records produces exactly one frame, and none in between`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        val app = app().start()
        val probe = probe(app)
        val tap = tap(app)

        tap.awaitAtLeast(1, "the initial frame")
        tap.frames().first().recordCount() shouldBe 3

        // Three records in ONE write, then ONE tick. A driver that broadcast
        // per record — the failure this rule exists to exclude — would emit
        // three frames here, two of them carrying an intermediate count.
        append(*lines(3, from = 3).toTypedArray())
        app.pollOnce()

        tap.awaitAtLeast(2, "the frame for that one poll")

        // Bounded quiescence: extra per-record frames would be produced by the
        // very same `pollOnce` call and so would already be queued behind frame
        // 2; this window only has to outlast their delivery, and it never
        // lengthens a passing run beyond itself.
        val deadline = System.currentTimeMillis() + QUIESCENCE_MS
        while (System.currentTimeMillis() < deadline && tap.frames().size <= 2) Thread.sleep(25)

        val frames = tap.frames()
        frames.size shouldBe 2
        frames[1].recordCount() shouldBe 6
        tap.count { it.recordCount() in 4..5 } shouldBe 0

        // The frame IS the served document, not a second shape of it.
        val served = Json.parseToJsonElement(probe.state())
        frames[1].obj("report", "publishedAt").jsonPrimitive.content shouldBe
            served.obj("report", "publishedAt").jsonPrimitive.content
        frames[1] shouldBe served
    }

    // -----------------------------------------------------------------
    // rule 3 — bad input is counted, never folded
    // -----------------------------------------------------------------

    @Test
    fun `a malformed spend line and an unparseable declaration are counted and change no fold`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        val app = app().start()
        val probe = probe(app)

        append("{not json", lines(1, from = 3).single())
        app.pollOnce()

        val afterBadLine = Json.parseToJsonElement(probe.state(INGEST_PATH))
        afterBadLine.obj("failures", "malformed").jsonPrimitive.long shouldBe 1L
        afterBadLine.obj("recordCount").jsonPrimitive.int shouldBe 4
        val declarationEvents = afterBadLine.obj("declarationEvents").jsonPrimitive.int

        Files.writeString(declaration, "weights: [")
        app.pollOnce()

        val afterBadDeclaration = Json.parseToJsonElement(probe.state(INGEST_PATH))
        afterBadDeclaration.obj("failures", "declarationParseFailed").jsonPrimitive.long shouldBe 1L
        afterBadDeclaration.obj("declarationEvents").jsonPrimitive.int shouldBe declarationEvents
        afterBadDeclaration.obj("recordCount").jsonPrimitive.int shouldBe 4
    }

    // -----------------------------------------------------------------
    // rule 4 — read-only
    // -----------------------------------------------------------------

    @Test
    fun `a POST answers 405 and leaves the served document byte-for-byte unchanged`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        val app = app().start()
        val probe = probe(app)

        val before = probe.state()
        probe.postForm("x=1", "/state").statusCode() shouldBe 405
        probe.state() shouldBe before
    }

    // -----------------------------------------------------------------
    // fpml.4-D6 — a dead poll loop freezes the served state, relabelled
    // -----------------------------------------------------------------

    @Test
    fun `a throwing poll loop stops, is exposed, and freezes every state route at 503`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        // The bead prescribed "a clock that throws after N calls". A flag is
        // used instead because the call count of one healthy tick is an
        // implementation detail of three collaborators (the declaration
        // ingester's clock, the driver's `lastPollAt`, the views' publish
        // instant), so pinning N would make this test fail for a change that
        // is none of its business. The property asserted is identical: the
        // FIRST tick succeeds, a later one throws.
        val broken = AtomicBoolean(false)
        val app = app(pollInterval = Duration.ofMillis(20)) {
            if (broken.get()) throw IllegalStateException("clock broke") else clock.get()
        }.start()
        val probe = probe(app)

        val lastGood = Json.parseToJsonElement(probe.state())
        lastGood.recordCount() shouldBe 3

        broken.set(true)
        awaitUntil("the poll loop to stop on the broken clock") { app.pollLoopStopped != null }

        app.pollLoopStopped shouldNotBe null

        val frozen = probe.get("/state")
        frozen.statusCode() shouldBe 503
        val body = Json.parseToJsonElement(frozen.body())
        body.obj("ingest").jsonPrimitive.content shouldBe "frozen"
        body.obj("failure").jsonPrimitive.content shouldContain "clock broke"
        body.obj("stale").recordCount() shouldBe 3

        probe.get(INGEST_PATH).statusCode() shouldBe 503
        probe.get("/state/report").statusCode() shouldBe 503
    }

    // -----------------------------------------------------------------
    // computenet-w20a4 — the /events surface labels a frozen fold, same as
    // /state's 503 envelope. D6 as written ("SSE simply stops receiving
    // frames") covers only an already-connected subscriber, and even for that
    // case never said it gets a signal rather than silence; this closes both
    // gaps: a client connecting AFTER the loop has died, and one already
    // connected WHEN it dies.
    // -----------------------------------------------------------------

    @Test
    fun `a client connecting to events after the poll loop has died sees the frozen envelope as its initial frame`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        val broken = AtomicBoolean(false)
        val app = app(pollInterval = Duration.ofMillis(20)) {
            if (broken.get()) throw IllegalStateException("clock broke") else clock.get()
        }.start()

        broken.set(true)
        awaitUntil("the poll loop to stop on the broken clock") { app.pollLoopStopped != null }

        // Connects only now — never saw a live frame. A revert to the
        // unlabelled `holder.current?.toJson()` initial frame would hand this
        // client the same 3-record document a live fold would, with nothing
        // to tell the two apart.
        val tap = tap(app)
        val initial = tap.awaitAtLeast(1, "the initial frame").first()

        initial.obj("ingest").jsonPrimitive.content shouldBe "frozen"
        initial.obj("failure").jsonPrimitive.content shouldContain "clock broke"
        initial.obj("stale").recordCount() shouldBe 3
    }

    @Test
    fun `an already-connected events subscriber receives the frozen envelope when the poll loop dies`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        val broken = AtomicBoolean(false)
        val app = app(pollInterval = Duration.ofMillis(20)) {
            if (broken.get()) throw IllegalStateException("clock broke") else clock.get()
        }.start()

        val tap = tap(app)
        tap.awaitAtLeast(1, "the initial frame")
        tap.frames().first().recordCount() shouldBe 3

        broken.set(true)
        // Without the death-time broadcast this subscriber would see frames
        // simply stop arriving — exactly the silence D6 describes as
        // acceptable for the wrong reason: it never says the client learns
        // anything. `runCatching` skips frames whose `ingest` is the normal
        // object (not the frozen string), rather than throwing on them.
        val frozenFrames = tap.awaitMatching("a frozen frame", timeoutMs = 5_000) { frame ->
            runCatching { frame.obj("ingest").jsonPrimitive.content == "frozen" }.getOrDefault(false)
        }

        frozenFrames.shouldNotBeEmpty()
        val frame = frozenFrames.first()
        frame.obj("failure").jsonPrimitive.content shouldContain "clock broke"
        frame.obj("stale").recordCount() shouldBe 3
    }

    // -----------------------------------------------------------------
    // fpml.4-D7 — re-baseline accounting
    // -----------------------------------------------------------------

    @Test
    fun `a truncated log re-baselines, is counted, and reconciles the served record set`() {
        append(*lines(3).toTypedArray())
        writeDeclaration()

        val app = app().start()
        val probe = probe(app)
        Json.parseToJsonElement(probe.state()).obj("ingest", "reBaselineCount").jsonPrimitive.long shouldBe 0L

        Files.writeString(log, lines(1).single() + "\n")
        app.pollOnce()

        val after = Json.parseToJsonElement(probe.state())
        after.obj("ingest", "reBaselineCount").jsonPrimitive.long shouldBe 1L
        after.recordCount() shouldBe 1
    }

    // -----------------------------------------------------------------
    // main's argument parsing
    // -----------------------------------------------------------------

    @Test
    fun `parseArgs reads the three required paths, the positional port and the window`() {
        val config = parseArgs(arrayOf("--log", "a", "--run-dir", "b", "--declaration", "c", "0"))
        config.logPath shouldBe Path.of("a")
        config.runDir shouldBe Path.of("b")
        config.declarationPath shouldBe Path.of("c")
        config.port shouldBe 0
        config.windowLength shouldBe Duration.ofHours(168)
        config.pollInterval shouldBe Duration.ofMillis(1000)
    }

    @Test
    fun `parseArgs reads the optional window and poll interval`() {
        val config = parseArgs(
            arrayOf("--log", "a", "--run-dir", "b", "--declaration", "c", "--window-hours", "24", "--poll-interval-ms", "250", "0"),
        )
        config.windowLength shouldBe Duration.ofHours(24)
        config.pollInterval shouldBe Duration.ofMillis(250)
        config.port shouldBe 0
    }

    @Test
    fun `parseArgs refuses a missing required flag rather than exiting`() {
        val failure = shouldThrow<IllegalArgumentException> {
            parseArgs(arrayOf("--run-dir", "b", "--declaration", "c", "0"))
        }
        failure.message!! shouldContain "--log"
        failure.message!! shouldContain ALLOCATOR_OBSERVE_USAGE
    }

    private companion object {
        const val CN = "computenet"
        const val GF = "glass-factory"

        /** Named here rather than inline so no test literal looks like a hardcoded log path. */
        const val LOG_NAME = "spend.jsonl"

        const val INGEST_PATH = "/state/ingest"

        val NOW: Instant = Instant.parse("2026-09-18T12:00:00Z")
        val WINDOW: Duration = Duration.ofHours(168)

        /** How long the SSE quiescence check waits for a frame that must not arrive. */
        const val QUIESCENCE_MS = 500L

        val VALID_DECLARATION =
            """
            projects:
              $CN: 60
              $GF: 40
            window: rolling-month
            monthly_cap:
              hours: 100
            """.trimIndent()
    }
}
