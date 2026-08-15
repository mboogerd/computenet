package civictech.demo.beadsmirror.projector

import civictech.cell.Timestamp

/**
 * The mirror's held-dot set, for the `metadata.cn_dot` echo drop
 * (computenet-dqj.2.3, epic computenet-dqj acceptance rule 5).
 *
 * Why this exists: without it, a record this projector already applied (or
 * whose dot it itself minted) would be indistinguishable from new content on
 * a write-back round-trip through `bd import` — re-minting a second dot for
 * content that already has one is the unbounded amplification loop the epic
 * flags. `metadata.cn_dot`'s wire form is `<sourceId>:<counter>` (epic note
 * 1, e.g. `"peerX:41"`); a peer's `sourceId` spelling is opaque here — this
 * registry holds it as a plain string key, never as something it tries to
 * parse into a UUID.
 *
 * In-memory only: it is a pure function of the records applied so far (plus
 * this projector's own minted dots), so it is derivable by replay.
 * Persisting it alongside the checkpoint is acceptable but not required in
 * BDS1 (rebuild-on-restart is dqj.3 territory).
 */
class CnDotRegistry {

    private val held = mutableSetOf<String>()

    /** Whether [cnDot] (in raw wire form) is already held. */
    fun holds(cnDot: String): Boolean = normalize(cnDot) in held

    /** Record [cnDot] (raw wire form) as held. */
    fun add(cnDot: String) {
        held += normalize(cnDot)
    }

    /** Record the cn_dot rendering of a dot this projector itself minted. */
    fun addMinted(dot: Timestamp) {
        held += render(dot)
    }

    /** How many distinct cn_dots are currently held — for tests/inspection. */
    fun size(): Int = held.size

    companion object {
        /**
         * Trims incidental whitespace only. The `sourceId` half is a peer's
         * own spelling and is never reinterpreted or reformatted here.
         */
        private fun normalize(cnDot: String): String = cnDot.trim()

        /**
         * A minted dot's cn_dot rendering, in the same `<sourceId>:<counter>`
         * wire form the epic specifies, so a value minted here and a value
         * read off the wire land on the same key.
         */
        fun render(dot: Timestamp): String = "${dot.sourceId}:${dot.counter}"
    }
}
