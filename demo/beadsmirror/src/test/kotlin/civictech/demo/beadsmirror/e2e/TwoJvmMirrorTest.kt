package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.dolt.DoltSql
import civictech.testkit.JvmPeer
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path

/**
 * Task computenet-7em.1.4: feature computenet-7em.1's required two-JVM
 * launch-path test — the Design's "at least one test exercises the two-JVM
 * launch path" and feature rule 3 ("each node SHALL serve its own
 * materialized fold over its own DemoShell HTTP route, independently
 * addressable"), both asserted across two real OS processes rather than two
 * [civictech.cell.host.ManagedHost]s in one JVM (that in-process rig is
 * computenet-7em.1.3's).
 *
 * Launches [civictech.demo.beadsmirror.BeadsMirrorApp]'s `main` twice via
 * [JvmPeer] — a `--listen 0` listener and a `--peer` dialer, the shape
 * `TwoJvmConvergenceTest` (`:demo:shopping`) established and computenet-dqy.25
 * requires (the *bound* port is announced and read back, never a pre-picked
 * one). Each child gets its own [BdScratchWorkspace]; the children only ever
 * talk to their own throwaway workspace and to each other's HTTP route and
 * `:wire` socket — never the live `.beads` (epic computenet-dqj §4), and the
 * parent JVM never touches either child's in-process objects, only their HTTP
 * surface and (for the no-sync assertion) their workspace's own `dolt_log`.
 *
 * Guarded like every other real-`bd` test in this module ([DoltSqlTest],
 * [civictech.demo.beadsmirror.BeadsMirrorAppTest.AgainstAScratchWorkspace]):
 * green-but-skipped where `bd`/`dolt` are not on `PATH` (CI), a real gate on
 * a developer machine.
 *
 * `@Tag("multi-jvm")`, matching `buildSrc`'s `kotlin-jvm.gradle.kts`
 * convention for tests that fork external `java` processes.
 */
class TwoJvmMirrorTest {

    private lateinit var listenerWorkspace: BdScratchWorkspace
    private lateinit var dialerWorkspace: BdScratchWorkspace

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        listenerWorkspace = BdScratchWorkspace.create()
        dialerWorkspace = BdScratchWorkspace.create()
    }

    @AfterEach
    fun tearDown() {
        if (::listenerWorkspace.isInitialized) listenerWorkspace.close()
        if (::dialerWorkspace.isInitialized) dialerWorkspace.close()
    }

    /** `GET http://localhost:$port$path` — the parent JVM's only access to either child (HTTP only). */
    private fun getStatusAndBody(port: Int, path: String): Pair<Int, String> {
        val connection = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            ?: ""
        connection.disconnect()
        return code to body
    }

    private fun up(port: Int): Boolean =
        runCatching { getStatusAndBody(port, "/beads/issues").first == 200 }.getOrDefault(false)

    /** `dolt_log`'s commit hashes for [doltRoot], in the order `dolt sql` prints them (newest first). */
    private fun logHead(doltRoot: Path): List<String> =
        DoltSql(doltRoot).query("select commit_hash from dolt_log")
            .map { it.getValue("commit_hash").jsonPrimitive.content }

    @Tag("multi-jvm")
    @Test
    fun `listener and dialer JVMs converge a fresh bd create over the real socket`() {
        // A fresh rig name per run: the two nodes' shared logical CellRefs are
        // derived from it (MirrorCellRefs), so a stale value from a prior run
        // sharing this JVM's classpath cache could never collide with it.
        val rig = "e2e-two-jvm-${System.nanoTime()}"

        // every port is `0`: each node binds its own and announces what it got, so
        // no test-side number is ever handed to a process that has yet to bind it
        // (computenet-dqy.25). The listener must announce its bound ws port before
        // the dialer can be told to dial it, which is what orders these two launches.
        val listenerPeer = JvmPeer.launch(
            "civictech.demo.beadsmirror.BeadsMirrorAppKt",
            "--workspace", listenerWorkspace.root.toString(),
            "--rig", rig,
            "--listen", "0",
            "0",
        )
        val listenerHttp = listenerPeer.port("http")
        val listenerWs = listenerPeer.port("ws")

        val dialerPeer = JvmPeer.launch(
            "civictech.demo.beadsmirror.BeadsMirrorAppKt",
            "--workspace", dialerWorkspace.root.toString(),
            "--rig", rig,
            "--peer", "ws://localhost:$listenerWs",
            "0",
        )
        val dialerHttp = dialerPeer.port("http")
        val peers = listOf(listenerPeer, dialerPeer)

        try {
            // 1. Both children come up serving their own fold on their own
            // announced HTTP port, independently addressable, each fold
            // matching its own workspace's export at baseline: both workspaces
            // are freshly `bd --sandbox init`ed, so both folds start empty.
            JvmPeer.await("both nodes serving their own fold", peers) {
                up(listenerHttp) && up(dialerHttp)
            }
            getStatusAndBody(listenerHttp, "/beads/issues") shouldBe (200 to "{}")
            getStatusAndBody(dialerHttp, "/beads/issues") shouldBe (200 to "{}")

            // Baseline for assertion 3, captured only after both nodes are up
            // (and so past their own start-time baseline, which only reads —
            // never mutates — the workspace it targets).
            val dialerHeadBefore = logHead(dialerWorkspace.doltRoot)

            // 2. bd create against the listener's workspace; awaitUntil the
            // DIALER's HTTP fold contains the new issue — cross-JVM gossip
            // over the real socket.
            val newId = listenerWorkspace.createIssue("cross-jvm issue")
            // `JvmPeer.await`, not a bare `awaitUntil`: every failure mode here
            // is a child-process death (a poll loop killed by an unserializable
            // payload, a peer that lost its socket), and Gradle renders a failed
            // test's exception but never a child's stdout — so a bare timeout
            // says only "timed out" and buries the cause in a process nobody
            // reads. `JvmPeer.await` folds both peers' buffered output into the
            // failure message. Timeout pinned to `awaitUntil`'s own 30s default
            // rather than `JvmPeer`'s 45s launch budget: these two waits are
            // convergence, not a cold JVM start.
            JvmPeer.await(
                "the listener's own create appears on its own fold",
                peers,
                AWAIT_CONVERGENCE_MS,
            ) { getStatusAndBody(listenerHttp, "/beads/issues/$newId").first == 200 }
            JvmPeer.await(
                "the new issue gossips to the dialer's fold over the real :wire socket",
                peers,
                AWAIT_CONVERGENCE_MS,
            ) { getStatusAndBody(dialerHttp, "/beads/issues/$newId").first == 200 }

            // 3. The dialer's own workspace stayed un-synced: no bd-level
            // transfer happened, only a cell delta over the socket, so its
            // dolt_log head is unchanged even though its served fold moved.
            logHead(dialerWorkspace.doltRoot) shouldBe dialerHeadBefore
        } finally {
            JvmPeer.destroy(listenerPeer, dialerPeer)
        }
    }
}

/**
 * Convergence budget for the post-`bd create` waits — `awaitUntil`'s own
 * default, kept explicit because [JvmPeer.await]'s default is the longer
 * cold-start launch budget.
 */
private const val AWAIT_CONVERGENCE_MS: Long = 30_000

private fun commandAvailable(vararg command: String): Boolean = try {
    val process = ProcessBuilder(*command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    process.waitFor() == 0
} catch (e: Exception) {
    false
}
