package civictech.inspect

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Backend-side guardrail for the fixture/type drift the 2026-07-28 audit's
 * finding B5 names: the JSON files under `inspect/ui/fixtures` are the frontend's only
 * concrete stand-in for real server responses, hand-authored and hand-kept in
 * sync with the `Dto.kt` `@Serializable` types this backend actually encodes.
 * Nothing validated that sync automatically before this test — five silent
 * fixture/type drifts landed across six milestones, each caught only by a
 * since-retired manual EVAL pass.
 *
 * [decoders]' file -> Dto.kt-decode-step mapping is hand-written, never
 * inferred from the filename (a name is a hint, not a contract — several are
 * ambiguous, e.g. every `cell-state-*.json` decodes as the same [CellState]
 * regardless of the value shape its name describes). [decoders]' key set is
 * asserted to equal the fixture directory's actual contents, so a new fixture
 * dropped in without a mapping entry fails loudly instead of going unchecked.
 *
 * A fresh [strict] `Json` is used rather than [inspectorJson]: the latter's
 * `encodeDefaults = true` is an encode-side concern and irrelevant here, but a
 * fresh instance with `ignoreUnknownKeys` left at its documented default
 * (`false`) states this test's intent plainly — an unknown key must fail —
 * without depending on `inspectorJson` never changing that default.
 */
class FixtureContractTest {

    private val strict = Json { ignoreUnknownKeys = false }

    companion object {
        private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

        private val fixturesDir = File(repoRoot, "inspect/ui/fixtures")
    }

    /**
     * file -> decode step, verified against each fixture's actual shape (not
     * assumed from its name) before being assigned here.
     *
     * `error-event-dead-letter.json` / `error-event-parked.json` /
     * `error-event-restart.json` are all the SSE envelope, [Event] — `payload`
     * is a plain `JsonObject` in `Dto.kt`, so the envelope decodes strictly
     * without this test separately verifying `payload`'s inner shape against
     * `DeadLetterRow`/`ParkedRow`/`RestartRow`. That is a real limitation of
     * this test, not silently expanded scope: a `payload` field rename inside
     * one of those row types would not be caught here.
     *
     * `flow-rates.json` is **not** a bare [FlowBatch] despite its name — its
     * actual top-level shape is a JSON array of [Event] envelopes (one per
     * aggregation window an SSE stream delivered), each carrying a
     * [FlowBatch]-shaped `payload` one level down. Decoding it as a bare
     * `FlowBatch` fails strict decode outright (`window`/`edges` are not the
     * file's top-level keys), so it is mapped as `List<Event>` here instead —
     * the ticket's candidate mapping named `FlowBatch`, but this is what the
     * fixture's actual content requires.
     *
     * `activity.json` / `activity-event.json` are V2's pair, authored by V2-FE
     * and mapped here by V2-BE: the first is the `GET /api/inspect/activity`
     * body ([ActivitySnapshot]), the second one `activity` SSE envelope
     * ([Event]) — and carries the same `payload`-shape limitation the three
     * `error-event-*.json` entries above describe. Because the mapping is
     * asserted to *equal* the directory's contents, these two entries are green
     * only once both branches have merged; that is intended, and noted in the
     * V2-BE report so the repo gate is run after both land rather than against
     * either in isolation.
     *
     * `cell-state-page.json` / `cell-state-page-checkpoint.json` are V1c's pair,
     * authored by `V1C-FE` and mapped here by `V1C-BE` on the same arrangement
     * `activity.json` established: the first is a live paged read carrying a
     * cursor, the second a drained host's checkpoint read, and both are
     * [CellState] — every `cell-state-*.json` decodes as that type regardless of
     * the value shape its name describes. Because the mapping is asserted to
     * *equal* the directory's contents, these two entries are green only once
     * both branches have merged; that is intended, and noted in the V1C-BE
     * report so the repo gate is run after both land rather than against either
     * in isolation. V1C-BE's additive `CellState.provenance` / `.page` /
     * `.unreadable` fields all carry defaults, so every existing
     * `cell-state-*.json` strict-decodes both before and after.
     *
     * `error-event-wave-health.json` / `error-event-wave-health-cleared.json`
     * are V3's pair, authored by V3-FE and mapped here by V3-BE on the same
     * arrangement: both are the SSE envelope ([Event]) carrying a
     * [WaveHealthRow]-shaped `payload` — the `open` row and the `cleared` row
     * that retires it — and both inherit the `payload`-shape limitation
     * described above. `errors.json` keeps its existing entry; V3-BE's additive
     * `ErrorSnapshot.waveHealth` / `ErrorCounters.waveHealth` /
     * `DeadLetterRow.invocation` / `.disposition` / `RestartRow.cause` fields
     * all carry defaults, so that fixture strict-decodes both before and after
     * V3-FE extends it in place.
     */
    private val decoders: Map<String, (String) -> Unit> = mapOf(
        "topology.json" to { s: String -> strict.decodeFromString<TopologySnapshot>(s) },
        "topology-multihost.json" to { s: String -> strict.decodeFromString<TopologySnapshot>(s) },
        "topology-nets.json" to { s: String -> strict.decodeFromString<TopologySnapshot>(s) },
        "cell-detail.json" to { s: String -> strict.decodeFromString<CellDetail>(s) },
        "cell-state-scalar.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "cell-state-table.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "cell-state-tree.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "cell-state-opaque.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "cell-state-truncated.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "cell-state-unavailable.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "cell-state-page.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "cell-state-page-checkpoint.json" to { s: String -> strict.decodeFromString<CellState>(s) },
        "errors.json" to { s: String -> strict.decodeFromString<ErrorSnapshot>(s) },
        "error-event-dead-letter.json" to { s: String -> strict.decodeFromString<Event>(s) },
        "error-event-parked.json" to { s: String -> strict.decodeFromString<Event>(s) },
        "error-event-restart.json" to { s: String -> strict.decodeFromString<Event>(s) },
        "flow-rates.json" to { s: String -> strict.decodeFromString<List<Event>>(s) },
        "graphs.json" to { s: String -> strict.decodeFromString<GraphList>(s) },
        "graphs-cold.json" to { s: String -> strict.decodeFromString<GraphList>(s) },
        "search-name.json" to { s: String -> strict.decodeFromString<SearchResult>(s) },
        "search-problems.json" to { s: String -> strict.decodeFromString<SearchResult>(s) },
        "search-data.json" to { s: String -> strict.decodeFromString<SearchResult>(s) },
        "search-data-cold.json" to { s: String -> strict.decodeFromString<SearchResult>(s) },
        "activity.json" to { s: String -> strict.decodeFromString<ActivitySnapshot>(s) },
        "activity-event.json" to { s: String -> strict.decodeFromString<Event>(s) },
        "error-event-wave-health.json" to { s: String -> strict.decodeFromString<Event>(s) },
        "error-event-wave-health-cleared.json" to { s: String -> strict.decodeFromString<Event>(s) },
    )

    @Test
    fun `the mapping covers exactly the fixture directory's current contents`() {
        val actual = fixturesDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.name }
            ?.toSet()
            ?: error("fixtures directory not found at $fixturesDir")
        actual shouldBe decoders.keys
    }

    @Test
    fun `every fixture strict-decodes against its mapped Dto type`() {
        val failures = mutableListOf<String>()
        decoders.forEach { (name, decode) ->
            val file = File(fixturesDir, name)
            runCatching { decode(file.readText()) }
                .onFailure { failures += "$name -> ${it::class.simpleName}: ${it.message}" }
        }
        failures.shouldBeEmpty()
    }
}
