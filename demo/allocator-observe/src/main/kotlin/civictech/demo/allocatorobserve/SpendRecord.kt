package civictech.demo.allocatorobserve

/**
 * The v1 socaity spend-log record schema, pinned by `doc/allocator-mvp.md`
 * R3 (Metering) and bead `socaity-fqf` — verified 2026-08-23 in the epic
 * comment on `computenet-fpml`. One record per worker session.
 *
 * `started`/`ended` are kept as the raw strings the log carries, deliberately
 * NOT parsed into instants: the differential oracle (F5, computenet-fpml.5)
 * replays the raw log byte-for-byte, so ingest must not normalize away
 * information the oracle needs to compare against
 * (fpml.1-D2 — see the `computenet-fpml.1` feature description).
 */
data class SpendRecord(
    val v: Int,
    val project: String,
    val machine: String,
    val workItem: String,
    val started: String,
    val ended: String,
)
