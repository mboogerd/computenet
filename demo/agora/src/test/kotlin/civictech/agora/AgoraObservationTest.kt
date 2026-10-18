package civictech.agora

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgoraObservationTest {

    @Test
    fun `canonical observation serves dynamic credence sources and drains at idle`() {
        val h = Harness(seed = 29L)

        assertEquals(setOf("credences"), h.service.observationViews)
        assertTrue(h.service.cells().any { it.ref == h.service.observationSinkRef })

        val claim = h.service.createClaim("canonical observation")
        h.service.setStance(claim, "author", 0.8)
        h.runToIdle()

        assertEquals(0.8, h.service.hub.credenceOf(claim) ?: error("claim credence missing"), 1e-9)
    }
}
