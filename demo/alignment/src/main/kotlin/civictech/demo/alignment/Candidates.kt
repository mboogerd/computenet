package civictech.demo.alignment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * One triage candidate: a beads issue to be rated (feature computenet-i00bh).
 *
 * The fields are exactly those `bd ready --json` emits and that [BeadsHeuristic] reads —
 * nothing is derived here, so a candidate is a faithful record of what the
 * tracker said at ingest time. [id] is the bead id verbatim (`computenet-8x9`),
 * which is already a valid [slug], so it becomes the alignment idea's id
 * unchanged and Phase 2 can map an ordered board back onto `bd` ids with no
 * lookup table.
 *
 * [priority] is beads' own scale — 0 (most urgent) to 3 — and null when the
 * export carried no usable value, which is [BeadsHeuristic]'s abstention trigger.
 * [dependentCount] is how many issues depend on this one, [ageDays] the whole
 * days since `updated_at`.
 */
internal data class Candidate(
    val id: String,
    val title: String,
    val description: String,
    val issueType: String,
    val priority: Int?,
    val dependentCount: Int,
    val ageDays: Long,
)

/**
 * Where triage candidates come from (feature computenet-i00bh).
 *
 * The seam exists so the ingest can be re-pointed without touching anything
 * downstream, exactly as `:demo:beadsmirror`'s `MirrorTransport` receives its
 * wiring instead of naming it. Phase 1's binding is [BdCandidateSource] (a
 * read-only `bd` subprocess); Phase 3's is the mirror's derived ready cell,
 * which is a new binding and no edit to the seeding path or its tests.
 */
internal fun interface CandidateSource {
    fun candidates(): List<Candidate>
}

/**
 * Candidates from a real beads workspace, by subprocess (feature computenet-i00bh).
 *
 * **Read-only, by construction.** The only command this runs is
 * `bd -C <workspace> ready --type=<type> --json`; nothing here mutates the
 * tracker, and the triage service never claims, closes or imports. The order it
 * produces is advice that `/work` consumes, and `/work` remains the only thing
 * that writes to beads.
 *
 * `bd` prefixes its JSON with human-readable lines, so [parse] skips to the
 * first `[` or `{` the way AGENTS.md's own freshness check does with
 * `sed -n '/^[[{]/,$p'`, and accepts both the bare-array and `{"issues":[…]}`
 * shapes for the same reason.
 */
internal class BdCandidateSource(
    private val workspace: Path,
    private val type: String = "epic",
    private val bd: String = "bd",
    private val now: () -> Long = System::currentTimeMillis,
) : CandidateSource {

    override fun candidates(): List<Candidate> {
        val process = ProcessBuilder(bd, "-C", workspace.toAbsolutePath().toString(), "ready", "--type=$type", "--json")
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.use { it.readBytes().decodeToString() }
        val err = process.errorStream.use { it.readBytes().decodeToString() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("bd ready timed out after 60s against $workspace")
        }
        if (process.exitValue() != 0) error("bd ready exited ${process.exitValue()} against $workspace: ${err.trim()}")
        return parse(out, now())
    }

    internal companion object {

        /**
         * [text] is `bd`'s stdout, with any preamble before the first `[`/`{`
         * skipped. A row without an `id` is dropped rather than failing the
         * whole ingest: one malformed row should not cost a triage round.
         * [nowMillis] anchors [Candidate.ageDays].
         */
        fun parse(text: String, nowMillis: Long): List<Candidate> {
            val start = text.indexOfFirst { it == '[' || it == '{' }
            if (start < 0) return emptyList()
            val root = Json.parseToJsonElement(text.substring(start))
            val rows = when (root) {
                is JsonArray -> root
                is JsonObject -> root["issues"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return rows.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.string("id") ?: return@mapNotNull null
                Candidate(
                    id = id,
                    title = o.string("title") ?: id,
                    description = o.string("description") ?: "",
                    issueType = o.string("issue_type") ?: "",
                    priority = o.int("priority")?.takeIf { it in 0..3 },
                    dependentCount = o.int("dependent_count") ?: 0,
                    ageDays = ageDays(o.string("updated_at") ?: o.string("created_at"), nowMillis),
                )
            }
        }

        /** Whole days from an ISO-8601 instant to [nowMillis]; 0 for an absent or unparseable stamp. */
        private fun ageDays(stamp: String?, nowMillis: Long): Long {
            val millis = stamp?.let {
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            } ?: return 0
            return ((nowMillis - millis) / 86_400_000L).coerceAtLeast(0)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun JsonObject.int(key: String): Int? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()?.toInt()
    }
}
