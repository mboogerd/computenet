package civictech.cell.port

import java.util.UUID

/**
 * An outlet's emission-epoch identity (spec 20/22 §Source identity: emission
 * epochs; 93 I-14 Rule S1): the current `sourceId` and its counter high-water.
 * Captured/adopted wholesale on a **preserved-epoch** continuation — drain,
 * migration, or a promotion whose state transfer carries it inside the
 * buffered swap window ([civictech.cell.evolve.Promotion.promote]) — so the
 * successor's waves continue the same source lane instead of minting a fresh
 * one. Every other transition (cold start, RESTART, replica/candidate spawn,
 * a fallback promotion swap) mints a fresh `sourceId` instead of adopting.
 */
data class OutletWaveState(val sourceId: UUID, val highWater: Long)
