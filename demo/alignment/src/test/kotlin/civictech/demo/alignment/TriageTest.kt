package civictech.demo.alignment

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The triage feature's pure units (feature computenet-i00bh): the `bd ready`
 * parse, [Jev]'s arithmetic, and [Eisenhower.quadrantOf]'s 2×2. All three are
 * free of HTTP and of the dataflow; the board end to end is [TriageBoardTest].
 *
 * Every expected number below is computed by hand from the formulas stated in
 * [Jev]'s KDoc, not read off this code.
 */
class TriageTest {

    private fun near(want: Double, got: Double?) = got != null && abs(want - got) < 1e-9

    private val nowMillis = java.time.Instant.parse("2026-09-29T00:00:00Z").toEpochMilli()

    private fun candidate(
        id: String = "computenet-x",
        priority: Int? = 2,
        dependentCount: Int = 0,
        ageDays: Long = 0,
    ) = Candidate(id, "T", "d", "epic", priority, dependentCount, ageDays)

    // ── the bd ready parse ───────────────────────────────────────────────

    /** `bd` prints human lines before its JSON; the parse skips to the first `[`/`{`. */
    @Test
    fun `parse skips bd's preamble and reads the bare-array shape`() {
        val text = """
            Loading workspace...
            3 ready issues
            [{"id":"computenet-a","title":"A","description":"da","issue_type":"epic",
              "priority":0,"dependent_count":7,"updated_at":"2026-09-19T00:00:00Z"}]
        """.trimIndent()
        val got = BdCandidateSource.parse(text, nowMillis)
        assertEquals(1, got.size)
        val c = got.single()
        assertEquals("computenet-a", c.id)
        assertEquals("A", c.title)
        assertEquals("da", c.description)
        assertEquals("epic", c.issueType)
        assertEquals(0, c.priority)
        assertEquals(7, c.dependentCount)
        assertEquals(10, c.ageDays, "2026-09-19 to 2026-09-29 is 10 whole days")
    }

    /** The `{"issues":[…]}` shape is the same population, so it parses identically. */
    @Test
    fun `parse reads the issues-object shape`() {
        val got = BdCandidateSource.parse("""{"issues":[{"id":"computenet-b","priority":3}]}""", nowMillis)
        assertEquals(listOf("computenet-b"), got.map { it.id })
        assertEquals(3, got.single().priority)
    }

    /**
     * Defaults and drops: no id is unusable so the row goes, a title falls back
     * to the id, an out-of-range or absent priority becomes null (Jev's
     * abstention trigger), and a missing stamp is age 0 rather than a throw.
     */
    @Test
    fun `parse drops an id-less row and defaults the rest`() {
        val got = BdCandidateSource.parse(
            """[{"title":"no id"},{"id":"computenet-c","priority":9},{"id":"computenet-d","updated_at":"nonsense"}]""",
            nowMillis,
        )
        assertEquals(listOf("computenet-c", "computenet-d"), got.map { it.id })
        assertNull(got[0].priority, "priority 9 is outside 0..3")
        assertEquals("computenet-c", got[0].title, "title falls back to the id")
        assertEquals(0, got[0].dependentCount)
        assertEquals(0, got[1].ageDays, "an unparseable stamp is age 0")
    }

    @Test
    fun `parse of output with no json at all is empty, not a throw`() {
        assertEquals(emptyList(), BdCandidateSource.parse("no issues are ready\n", nowMillis))
    }

    // ── Jev ──────────────────────────────────────────────────────────────

    private fun importance(vararg c: Candidate) = Jev.rate(c.toList(), Eisenhower.IMPORTANCE)

    private fun urgency(vararg c: Candidate) = Jev.rate(c.toList(), Eisenhower.URGENCY)

    /**
     * The scale's ends are always reached, whatever the corpus looks like: the
     * round's lowest raw term is 1.0 and its highest 9.0. This is the property
     * the absolute-cap version lacked, which put 16 of 18 real epics in DROP
     * (see [Jev]'s KDoc).
     */
    @Test
    fun `Jev spreads a round across the whole scale`() {
        val low = candidate(id = "low", priority = 3, dependentCount = 0)
        val mid = candidate(id = "mid", priority = 2, dependentCount = 1)
        val high = candidate(id = "high", priority = 0, dependentCount = 4)
        val got = importance(low, mid, high)
        assertTrue(near(1.0, got["low"]), "$got")
        assertTrue(near(5.0, got["mid"]), "$got")
        assertTrue(near(9.0, got["high"]), "$got")
    }

    /**
     * Even a corpus where EVERY candidate has no dependents — the real
     * ready-epic queue's actual shape — still spreads, because the remaining
     * signal is rank-normalized rather than capped.
     */
    @Test
    fun `Jev spreads a round in which no candidate has any dependents`() {
        val got = importance(
            candidate(id = "p3", priority = 3),
            candidate(id = "p1", priority = 1),
            candidate(id = "p0", priority = 0),
        )
        assertTrue(near(1.0, got["p3"]) && near(5.0, got["p1"]) && near(9.0, got["p0"]), "$got")
        assertTrue(got.values.any { it >= Eisenhower.MID }, "some candidate must be able to cross the midpoint: $got")
    }

    /** Ties share the midpoint of the positions they span, so equal evidence ranks equally. */
    @Test
    fun `Jev gives tied candidates the same mid-rank value`() {
        val got = importance(
            candidate(id = "a", priority = 3),
            candidate(id = "b", priority = 1),
            candidate(id = "c", priority = 1),
            candidate(id = "d", priority = 0),
        )
        assertTrue(near(1.0, got["a"]), "$got")
        assertTrue(near(got["b"]!!, got["c"]), "the tie is shared: $got")
        assertTrue(near(1.0 + 8.0 * 1.5 / 3.0, got["b"]), "midpoint of positions 1 and 2 of 0..3: $got")
        assertTrue(near(9.0, got["d"]), "$got")
    }

    /** Every output is on the rating scale, so no caller needs to clamp. */
    @Test
    fun `Jev never leaves the 1 to 9 scale`() {
        val round = (0..3).flatMap { p ->
            listOf(0, 3, 500).flatMap { dep ->
                listOf(0L, 30L, 9999L).map { age -> candidate("p$p-d$dep-a$age", p, dep, age) }
            }
        }
        for (dim in listOf(Eisenhower.IMPORTANCE, Eisenhower.URGENCY)) {
            val got = Jev.rate(round, dim)
            assertEquals(round.size, got.size, dim)
            got.forEach { (id, v) -> assertTrue(RatingScale.valid(v), "$dim of $id was $v") }
        }
    }

    /**
     * Degenerate rounds resolve towards attention rather than a silent DROP,
     * consistent with [Eisenhower.quadrantOf]'s midpoint rule.
     */
    @Test
    fun `a one-candidate round and an all-tied round rate mid-scale`() {
        assertTrue(near(5.0, importance(candidate(id = "only"))["only"]), "one candidate has no relative information")
        val tied = importance(candidate(id = "a", priority = 2), candidate(id = "b", priority = 2))
        assertTrue(tied.values.all { near(5.0, it) }, "$tied")
    }

    /**
     * The knowledge gate: no priority means Jev declines, and the declining row
     * is left out of the normalization too, so one unrateable candidate does not
     * distort the rest of the round.
     */
    @Test
    fun `Jev abstains without a priority, and the abstainer does not distort the round`() {
        val blind = candidate(id = "blind", priority = null, dependentCount = 5, ageDays = 60)
        val low = candidate(id = "low", priority = 3)
        val high = candidate(id = "high", priority = 0)
        val withBlind = importance(blind, low, high)
        assertEquals(setOf("low", "high"), withBlind.keys, "the abstainer is absent, not defaulted")
        assertEquals(importance(low, high), withBlind, "and it changed nothing for the others")
    }

    @Test
    fun `Jev rates only the two Eisenhower axes`() {
        assertEquals(emptyMap(), Jev.rate(listOf(candidate()), "effort"))
        assertEquals(emptyMap(), Jev.rate(emptyList(), Eisenhower.IMPORTANCE))
    }

    /**
     * The axes must not be collinear or the 2x2 is decorative: two candidates
     * with opposite dependent/age profiles land in OPPOSITE quadrants, driven by
     * importance being dependent-dominated (0.65) and urgency priority-dominated
     * (0.65).
     */
    @Test
    fun `Jev's axes are independent enough to populate opposite quadrants`() {
        val structural = candidate(id = "structural", priority = 3, dependentCount = 5, ageDays = 0)
        val pressing = candidate(id = "pressing", priority = 0, dependentCount = 0, ageDays = 60)
        val round = listOf(structural, pressing)
        val imp = Jev.rate(round, Eisenhower.IMPORTANCE)
        val urg = Jev.rate(round, Eisenhower.URGENCY)
        assertTrue(near(9.0, imp["structural"]) && near(1.0, imp["pressing"]), "importance follows dependents: $imp")
        assertTrue(near(9.0, urg["pressing"]) && near(1.0, urg["structural"]), "urgency follows priority: $urg")
        assertEquals(Quadrant.SCHEDULE, quadrantOf(imp, urg, "structural"), "important, not urgent")
        assertEquals(Quadrant.DELEGATE, quadrantOf(imp, urg, "pressing"), "urgent, not important")
    }

    private fun quadrantOf(imp: Map<String, Double>, urg: Map<String, Double>, id: String): Quadrant? =
        Eisenhower.quadrantOf(
            buildMap {
                imp[id]?.let { put(Eisenhower.IMPORTANCE, DimStats(1, it, 0.0)) }
                urg[id]?.let { put(Eisenhower.URGENCY, DimStats(1, it, 0.0)) }
            },
        )

    // ── the 2x2 ──────────────────────────────────────────────────────────

    private fun quadrant(importance: Double?, urgency: Double?): Quadrant? = Eisenhower.quadrantOf(
        buildMap {
            importance?.let { put(Eisenhower.IMPORTANCE, DimStats(1, it, 0.0)) }
            urgency?.let { put(Eisenhower.URGENCY, DimStats(1, it, 0.0)) }
        },
    )

    @Test
    fun `the quadrant is the 2x2 over the two means`() {
        assertEquals(Quadrant.DO, quadrant(8.0, 8.0))
        assertEquals(Quadrant.SCHEDULE, quadrant(8.0, 2.0))
        assertEquals(Quadrant.DELEGATE, quadrant(2.0, 8.0))
        assertEquals(Quadrant.DROP, quadrant(2.0, 2.0))
    }

    /** Exactly on the midpoint resolves towards attention, not away from it. */
    @Test
    fun `the midpoint counts as the high side on both axes`() {
        assertEquals(Quadrant.DO, quadrant(Eisenhower.MID, Eisenhower.MID))
        assertEquals(Quadrant.SCHEDULE, quadrant(Eisenhower.MID, Eisenhower.MID - 1e-9))
        assertEquals(Quadrant.DELEGATE, quadrant(Eisenhower.MID - 1e-9, Eisenhower.MID))
    }

    /** An unrated axis is not a default corner: the idea is simply not placed. */
    @Test
    fun `the quadrant is null while either axis is unrated`() {
        assertNull(quadrant(8.0, null))
        assertNull(quadrant(null, 8.0))
        assertNull(quadrant(null, null))
        assertNull(Eisenhower.quadrantOf(mapOf("effort" to DimStats(1, 9.0, 0.0))), "other dimensions do not place it")
    }
}
