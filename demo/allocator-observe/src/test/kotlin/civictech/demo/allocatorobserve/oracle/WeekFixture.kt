package civictech.demo.allocatorobserve.oracle

import java.time.Duration
import java.time.Instant

/**
 * The fixture week every `computenet-fpml.5` task replays, as plain constants
 * (design entry fpml.5-D9). Nothing here is computed: the timestamps, weights
 * and record boundaries are transcribed from the feature bead, and the numbers
 * they imply are hand-derived in [ReferenceReportTest]'s comments.
 *
 * It lives beside the reference fold on purpose. A later task compares the
 * *served* report against [ReferenceReport] over exactly these lines, so both
 * sides must replay the same bytes; a fixture built by whichever test needed it
 * first would drift.
 *
 * Independence note (fpml.5-D6): like [ReferenceReport] and [ReportComparison],
 * this file imports nothing from `civictech.demo.allocatorobserve`, and
 * [ReferenceIndependenceTest] enforces that. The `allocation.yaml` texts below
 * are therefore raw strings, not parsed declarations — a task that wants them
 * parsed feeds them to the production parser itself.
 */

/** `now` for the fixture week: the instant every fixture report is published at. */
val NOW: Instant = Instant.parse("2026-08-15T00:00:00Z")

/** The reporting window length: seven days, so the window is `[2026-08-08T00:00Z, 2026-08-15T00:00Z)`. */
val WINDOW: Duration = Duration.ofHours(168)

/** The first declaration: 60/40, observed exactly at the window's start. */
val D1: DeclarationSpec =
    DeclarationSpec(
        observedAt = Instant.parse("2026-08-08T00:00:00Z"),
        weights = mapOf("computenet" to 60.0, "glass-factory" to 40.0),
        monthlyCapHours = 100.0,
        window = null,
    )

/** The mid-week re-weighting: 30/70, observed at `2026-08-12T00:00Z`. */
val D2: DeclarationSpec =
    DeclarationSpec(
        observedAt = Instant.parse("2026-08-12T00:00:00Z"),
        weights = mapOf("computenet" to 30.0, "glass-factory" to 70.0),
        monthlyCapHours = 100.0,
        window = null,
    )

/** The two-event declaration history of the fixture week, in observation order. */
val HISTORY: List<DeclarationSpec> = listOf(D1, D2)

/**
 * The `allocation.yaml` text [D1] is the parse of, in the shape
 * `AllocationDeclarationParserTest` uses. Carried so a task that drives the
 * real declaration ingester writes the same declaration this fixture declares.
 */
val D1_YAML: String =
    """
    projects: {computenet: 60, glass-factory: 40}
    monthly_cap: {hours: 100}
    """.trimIndent()

/** The `allocation.yaml` text [D2] is the parse of. */
val D2_YAML: String =
    """
    projects: {computenet: 30, glass-factory: 70}
    monthly_cap: {hours: 100}
    """.trimIndent()

private fun line(project: String, workItem: String, started: String, ended: String): String =
    """{"v":1,"project":"$project","machine":"m1","work_item":"$workItem",""" +
        """"started":"$started","ended":"$ended"}"""

/** r1: computenet, 2026-08-08 09:00-12:00Z (3h). */
val R1: String = line("computenet", "w1", "2026-08-08T09:00:00Z", "2026-08-08T12:00:00Z")

/** r2: glass-factory, 2026-08-08 13:00-15:00Z (2h). */
val R2: String = line("glass-factory", "w2", "2026-08-08T13:00:00Z", "2026-08-08T15:00:00Z")

/** r3: computenet, 2026-08-09 09:00-13:00Z (4h). */
val R3: String = line("computenet", "w3", "2026-08-09T09:00:00Z", "2026-08-09T13:00:00Z")

/** r4: glass-factory, 2026-08-10 09:00-11:00Z (2h). */
val R4: String = line("glass-factory", "w4", "2026-08-10T09:00:00Z", "2026-08-10T11:00:00Z")

/** r5: computenet, 2026-08-11 09:00-11:00Z (2h) — the record the re-baseline corrects. */
val R5: String = line("computenet", "w5", "2026-08-11T09:00:00Z", "2026-08-11T11:00:00Z")

/** r5c: [R5] corrected to end at 12:00Z (3h), as the mid-week replacement file writes it. */
val R5_CORRECTED: String = line("computenet", "w5", "2026-08-11T09:00:00Z", "2026-08-11T12:00:00Z")

/** r6: glass-factory, 2026-08-11 20:00Z - 2026-08-12 02:00Z (6h) — straddles [D2]: 4h under D1, 2h under D2. */
val R6: String = line("glass-factory", "w6", "2026-08-11T20:00:00Z", "2026-08-12T02:00:00Z")

/** r7: computenet, 2026-08-12 09:00-11:00Z (2h). */
val R7: String = line("computenet", "w7", "2026-08-12T09:00:00Z", "2026-08-12T11:00:00Z")

/** r8: glass-factory, 2026-08-12 12:00-18:00Z (6h). */
val R8: String = line("glass-factory", "w8", "2026-08-12T12:00:00Z", "2026-08-12T18:00:00Z")

/** r9: glass-factory, 2026-08-13 09:00-14:00Z (5h). */
val R9: String = line("glass-factory", "w9", "2026-08-13T09:00:00Z", "2026-08-13T14:00:00Z")

/** r10: computenet, 2026-08-14 09:00-10:00Z (1h). */
val R10: String = line("computenet", "w10", "2026-08-14T09:00:00Z", "2026-08-14T10:00:00Z")

/** The fixture week straight through: r1..r10, one v1 JSONL line each. */
val LINES: List<String> = listOf(R1, R2, R3, R4, R5, R6, R7, R8, R9, R10)

/**
 * The log content after the mid-week re-baseline: the first seven lines are
 * replaced by a five-line corrected file (r1..r4, r5c) and r6..r10 are then
 * appended, so the final content is r1..r4, r5c, r6..r10 — one line shorter in
 * the middle of the week and three enacted hours heavier for `computenet`.
 */
val REBASELINE_FINAL_LINES: List<String> =
    listOf(R1, R2, R3, R4, R5_CORRECTED, R6, R7, R8, R9, R10)

/** The first seven lines, ingested before the replacement lands. */
val REBASELINE_PREFIX_LINES: List<String> = listOf(R1, R2, R3, R4, R5, R6, R7)

/** The five-line corrected file that replaces [REBASELINE_PREFIX_LINES]. */
val REBASELINE_REPLACEMENT_LINES: List<String> = listOf(R1, R2, R3, R4, R5_CORRECTED)
