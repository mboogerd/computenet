package civictech.cell.nature

import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.consistency.GlitchFree
import civictech.cell.data.Partitioned
import civictech.cell.data.Replicable
import civictech.nature.Manifest

/**
 * PN-12 — the runtime twin of the KSP `manifestOf` scan in
 * `civictech.gen.wire.ContractProcessor`: the structural [Manifest] natures of a
 * cell, read off the marker interfaces its class implements. The two must agree
 * on every registered cell — `ManifestDriftTest` pins that (declared == installed).
 *
 * The marker → tag mapping (all *existing* markers, no new annotation):
 * - [GlitchFree]  → [Manifest.GLITCH_FREE]
 * - [Stateful]    → [Manifest.DURABLE]
 * - [Replicable] or [ReBaselineEmitting] → [Manifest.REPLICATED]
 * - [Partitioned] → [Manifest.PARTITIONED]
 *
 * `PULL_SERVING` / `GATED` have no static marker (they are installed policies,
 * not implemented interfaces) and are left for a declaration surface to set — the
 * vocabulary exists; the scan stays honest about what it can derive.
 */
fun manifestOf(clazz: Class<*>): Set<Manifest> = buildSet {
    if (GlitchFree::class.java.isAssignableFrom(clazz)) add(Manifest.GLITCH_FREE)
    if (Stateful::class.java.isAssignableFrom(clazz)) add(Manifest.DURABLE)
    if (Replicable::class.java.isAssignableFrom(clazz) ||
        ReBaselineEmitting::class.java.isAssignableFrom(clazz)
    ) add(Manifest.REPLICATED)
    if (Partitioned::class.java.isAssignableFrom(clazz)) add(Manifest.PARTITIONED)
}
