package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.HttpProbe
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * V0-BE (b): `InspectorServer` serving `inspect/ui`'s built `dist/` directly,
 * so a demo started with `--inspect-port` and a prior `npm run build` needs
 * nothing but that one process. No dependency on an actual Vite build here —
 * every test writes its own tiny fixture directory, since the acceptance bar
 * is the serving mechanism (content type, traversal, API-route precedence,
 * graceful absence), not the real frontend.
 */
class InspectorStaticTest {

    private val registry = LocationRegistry()

    /**
     * The host's scheduler, owned here rather than left to [ManagedHost]'s own
     * default, purely so [tearDown] can stop it (computenet-4vh) — see
     * `InspectorErrorsTest` for the full rationale.
     */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private var server: InspectorServer? = null
    private var probe: HttpProbe? = null

    @AfterEach
    fun tearDown() {
        probe?.close()
        server?.close()
        hostScheduler.shutdown()
    }

    private fun serve(uiDist: Path): HttpProbe {
        val started = InspectorServer(registry, mapOf("test-host" to host), port = 0, uiDist = uiDist).startUnscheduled()
        server = started
        return HttpProbe("http://localhost:${started.boundPort}").also { probe = it }
    }

    private fun fixtureDist(dir: Path): Path {
        Files.writeString(dir.resolve("index.html"), "<html><body>inspector</body></html>")
        Files.writeString(dir.resolve("app.js"), "console.log('inspector')")
        Files.writeString(dir.resolve("style.css"), "body { margin: 0 }")
        Files.writeString(dir.resolve("logo.svg"), "<svg></svg>")
        return dir
    }

    @Test
    fun `each fixture extension is served with the correct status and content type`(@TempDir dir: Path) {
        val probe = serve(fixtureDist(dir))

        val index = probe.get("/")
        index.statusCode() shouldBe 200
        index.headers().firstValue("Content-Type").orElse(null) shouldBe "text/html"
        index.body() shouldBe "<html><body>inspector</body></html>"

        val js = probe.get("/app.js")
        js.statusCode() shouldBe 200
        js.headers().firstValue("Content-Type").orElse(null) shouldBe "application/javascript"

        val css = probe.get("/style.css")
        css.statusCode() shouldBe 200
        css.headers().firstValue("Content-Type").orElse(null) shouldBe "text/css"

        val svg = probe.get("/logo.svg")
        svg.statusCode() shouldBe 200
        svg.headers().firstValue("Content-Type").orElse(null) shouldBe "image/svg+xml"
    }

    @Test
    fun `a path resolving to nothing under dist is a 404`(@TempDir dir: Path) {
        val probe = serve(fixtureDist(dir))

        probe.get("/does-not-exist.txt").statusCode() shouldBe 404
    }

    @Test
    fun `a traversal attempt escaping dist is a 404, not the escaped file`(@TempDir dir: Path) {
        val probe = serve(fixtureDist(dir))
        // a real file that exists just outside the dist directory — proves a
        // successful escape would have been observable, had the guard not caught it
        val secretName = "${dir.fileName}-secret.txt"
        Files.writeString(dir.resolveSibling(secretName), "should never be served")

        // a raw socket, not HttpProbe/HttpClient: the java.net.http client
        // normalizes ".." out of a URI's path before the request ever leaves
        // the process, which would silently defeat this test rather than
        // exercise InspectorServer's own guard. This sends the literal,
        // unnormalized request line the guard has to defend against.
        val status = rawGet(server!!.boundPort, "/../$secretName")

        status shouldBe 404
    }

    @Test
    fun `the static route does not shadow the API routes`(@TempDir dir: Path) {
        val probe = serve(fixtureDist(dir))

        probe.get(InspectorServer.TOPOLOGY_PATH).statusCode() shouldBe 200
        probe.get(InspectorServer.GRAPHS_PATH).statusCode() shouldBe 200
        probe.get(InspectorServer.ERRORS_PATH).statusCode() shouldBe 200
    }

    @Test
    fun `a missing dist directory degrades to API-only without throwing`(@TempDir dir: Path) {
        val missing = dir.resolve("never-built")

        val probe = serve(missing)

        // construction/start above already proved "does not throw"; every
        // API route still answers, and a static request is a plain 404
        probe.get(InspectorServer.TOPOLOGY_PATH).statusCode() shouldBe 200
        probe.get("/").statusCode() shouldBe 404
        probe.get("/app.js").statusCode() shouldBe 404
    }

    @Test
    fun `a dist directory that is a plain file, not a directory, also degrades cleanly`(@TempDir dir: Path) {
        val notADirectory = dir.resolve("dist-is-a-file")
        Files.writeString(notADirectory, "not a directory")

        val probe = serve(notADirectory)

        probe.get(InspectorServer.TOPOLOGY_PATH).statusCode() shouldBe 200
        probe.get("/").statusCode() shouldBe 404
    }

    /** A bare-metal `GET`, sent byte-for-byte so no client-side URI normalization runs first. */
    private fun rawGet(port: Int, path: String): Int =
        Socket("localhost", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
            statusLine.split(" ").getOrElse(1) { "-1" }.toInt()
        }
}
