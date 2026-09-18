package civictech.demo.beadsmirror.writeback

import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.projector.MirrorKey
import kotlinx.serialization.json.JsonObject

/**
 * The write-back stamp: two `metadata` keys the applier writes onto every
 * imposed row (feature computenet-6wc.3, decision 6wc.3-D1).
 *
 * `cn_dot` names the fold state the row was imposed FROM — the DOT_ORDER-max
 * winning dot over the issue's live keys — so a later reader (the sibling
 * echo gate, computenet-6wc.3.1) can correlate an imported row with the
 * mirror state that produced it. `cn_echo` is a token unique to ONE import
 * invocation, minted by [WriteBackApplier] rather than derived here: `cn_dot`
 * alone cannot serve as the echo signal, because it can legitimately repeat
 * across two impositions of the same row (a local edit whose dot loses on
 * height is re-overwritten by the same peer winner, re-stamping the same
 * `cn_dot` a second time).
 *
 * Deliberately pure — no `bd`/`dolt` subprocess, nothing filesystem- or
 * process-adjacent — so [WriteBackPurityTest] (this package's standing
 * subprocess-launch scan over `writeback`'s main sources, `BdImport.kt`
 * exempted by name) keeps passing without this file needing an exemption of
 * its own.
 */
object Provenance {

    /** The `metadata` key naming the originating replica and dot (6wc.3-D1). */
    const val CN_DOT: String = "cn_dot"

    /** The `metadata` key naming the per-import echo token (6wc.3-D1). */
    const val CN_ECHO: String = "cn_echo"

    /** Both stamp keys, stripped from every metadata comparison (6wc.3-D2) and from a re-baselined fold value. */
    val STAMP_KEYS: Set<String> = setOf(CN_DOT, CN_ECHO)

    /** `"<sourceId>:<counter>"` — the exact rendering `cn_dot` carries and a later reader parses back. */
    fun render(dot: Timestamp): String = "${dot.sourceId}:${dot.counter}"

    /**
     * The `cn_dot` value for [issueId]: the render of the [TaggedMapDelta.DOT_ORDER]-max
     * live dot across EVERY key [state] holds for that issue — every field
     * key plus the presence key ([MirrorKey.PRESENT]) — since any one of them
     * may carry the most recent winning edit. `null` when the issue has no
     * live key at all in [state] (nothing to stamp provenance for).
     */
    fun cnDotOf(state: TaggedMapDelta<MirrorKey, String>, issueId: String): String? {
        val winner = state.keys()
            .asSequence()
            .filter { it.issueId == issueId }
            .flatMap { state.liveDots(it).keys.asSequence() }
            .maxWithOrNull(TaggedMapDelta.DOT_ORDER)
            ?: return null
        return render(winner)
    }

    /**
     * [metadata] with [STAMP_KEYS] removed, or `null` when [metadata] is
     * `null` or becomes empty after stripping (6wc.3-D2: an object empty
     * after stripping counts as absent, both for comparison and for what the
     * planner re-stamps onto).
     */
    fun strip(metadata: JsonObject?): JsonObject? {
        if (metadata == null) return null
        val stripped = metadata.filterKeys { it !in STAMP_KEYS }
        return if (stripped.isEmpty()) null else JsonObject(stripped)
    }
}
