package civictech.dialogue.extract

import civictech.dialogue.Segment
import java.io.File

/**
 * A record-through wrapper around an injected model function (epic
 * computenet-2aw §3.2 `[AGO1-EXTR-07]`, DESIGN 2aw-D4, §8/R1). The concrete
 * network client is deliberately out of scope: `model` is the sole seam
 * through which any non-determinism or external dependency may enter, so
 * that no gating test ever has to invoke a live LLM (2aw.F2-D1). Wiring a
 * real provider behind `model` is demo-time configuration for a later
 * feature; this class stays a pure record-through step regardless of what
 * `model` actually is.
 *
 * `extract` calls `model`, records the successful result keyed by
 * [segmentContentHash] into [recorder], and returns the items unchanged.
 * "Record-through" means every successful call lands in the cassette; if
 * `model` throws, the exception propagates from `extract` and nothing is
 * recorded for that segment — the failed-segment accounting the pipeline
 * gate performs (`ExtractionCell`, sibling task) is not this class's job.
 */
class LlmExtractor(
    private val model: (Segment) -> List<ExtractedItem>,
    private val recorder: CassetteRecorder,
) : Extractor {

    override fun extract(segment: Segment): List<ExtractedItem> {
        val items = model(segment)
        recorder.record(segment, items)
        return items
    }
}

/**
 * A single re-recording of a content hash with a different result than what
 * was previously recorded for it — worth a warning because a nondeterministic
 * model producing different results for identical content is exactly what a
 * cassette exists to freeze out.
 */
data class CassetteCollision(
    val contentHash: String,
    val previous: List<ExtractedItem>,
    val replacement: List<ExtractedItem>,
)

/**
 * Accumulates `[segmentContentHash] -> List<ExtractedItem>` pairs recorded
 * by [LlmExtractor] and writes them out as a cassette file loadable by
 * [CassetteExtractor] — `[AGO1-EXTR-07]`'s "usable by the deterministic
 * extractor". Reuses [CassetteExtractor]'s own `cassetteJson` /
 * `cassetteEntriesSerializer` (kept `internal` there for exactly this
 * purpose) so the written format is byte-for-byte the format
 * [CassetteExtractor] loads — never a parallel re-implementation of it.
 *
 * Write-on-demand: recording only accumulates in memory; [write] renders
 * the accumulated entries to a [File] on request. Re-recording the same
 * content hash with a different item list is last-write-wins, and the
 * collision is appended to [collisions] for the caller to inspect — a
 * nondeterministic model producing different results for identical content
 * is exactly what a cassette exists to freeze out.
 */
class CassetteRecorder {

    private val entries = linkedMapOf<String, List<ExtractedItem>>()

    private val _collisions = mutableListOf<CassetteCollision>()

    /** Content-hash collisions observed so far: same hash, different recorded items. */
    val collisions: List<CassetteCollision> get() = _collisions

    /** The entries recorded so far, keyed by content hash. */
    val recorded: Map<String, List<ExtractedItem>> get() = entries

    fun record(segment: Segment, items: List<ExtractedItem>) {
        val contentHash = segmentContentHash(segment)
        val previous = entries[contentHash]
        if (previous != null && previous != items) {
            _collisions += CassetteCollision(contentHash = contentHash, previous = previous, replacement = items)
        }
        entries[contentHash] = items
    }

    /** Writes the entries recorded so far to [file] in [CassetteExtractor]'s cassette format. */
    fun write(file: File) {
        val text = cassetteJson.encodeToString(cassetteEntriesSerializer, entries)
        file.writeText(text, Charsets.UTF_8)
    }
}
