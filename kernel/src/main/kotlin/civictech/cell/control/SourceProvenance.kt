package civictech.cell.control

import civictech.cell.link.Link
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortIdentities
import java.util.UUID

/**
 * Best-effort, synchronous, **in-process-only** per-edge source reachability
 * (spec 20/22 `[22-LIVE-01]`'s floor-qualified completeness equation: "every
 * OPEN inlink with floor(s) < t"). The spec names a general upstream
 * traversal — "describe your frontier" — as the real fix (G-13's declined
 * multiplex-port form, 93 I-23) and separately flags hop-by-hop source-set
 * propagation as an undesigned gap (G-39). Neither is built. This is
 * deliberately smaller than either: no wire crossing, no query protocol, no
 * caching — just a direct, synchronous read of what is already known in two
 * decided shapes, composed through [Link.fromPort] (the G-13 minimal form,
 * M6.1 — the one piece of "describe your frontier" already landed: an
 * in-process link exposes its producer-side port object directly).
 *
 * [resolve] on a [Link] answers "which sourceIds can ever arrive on this
 * edge?" with:
 *  - a concrete, non-null [Set] when the answer is **known** — either a cell
 *    explicitly [publish]ed its outlet's resolved provenance (the unary and
 *    fan-in relay hops in [AbsorbAck.kt], which compose this recursively
 *    through their own input edges), or the outlet's owning cell is
 *    structurally a root — it has no open inbound link, no registered API
 *    targeted by a link-bypassing
 *    attachment, and either no registered input or a registered input has
 *    actually been entered externally. An owner whose input entry mode has
 *    not yet been observed is conservatively reactive; this keeps an opaque
 *    delegating wrapper unknown before its first delivery. A published set
 *    includes both its resolved input sources and every id the outlet minted
 *    itself;
 *  - `null` ("unknown") for everything else — a bridged edge
 *    ([Link.fromPort] is `null` across the wire, matching the existing
 *    cross-host residual in 20/22 §Bridged frontier), an unpublished outlet
 *    whose owning cell has an open or bypass-fed input, an unregistered outlet,
 *    or a graph cycle this resolver has already entered (guarded below so a
 *    cycle degrades to "unknown" instead of looping).
 *
 * `null` is the fail-closed default throughout: every caller treats "unknown"
 * exactly as it treats "this edge might carry that source" today — the
 * existing, safe, over-aligning behavior this mechanism only ever narrows
 * from, never widens past. Published provenance is evaluated from live
 * topology on every resolution; direct link-bypass targets are stamped before
 * their attachment becomes visible, and unary relay publishers consult that
 * stamp before publishing their linked frontier, while unobserved registered
 * inputs fail closed until their invocation establishes external or reactive
 * entry.
 * Emission history never excludes an edge. Thus an externally-fed
 * inlet-bearing source stays a root, while direct bypass targets and a
 * never-externally-entered wrapper target are unknown before their first
 * forwarded wave.
 *
 * Known limit (computenet-8txv7): after an inlet has established a genuine
 * external entry path, an opaque wrapper's first later delivery cannot be
 * attributed to that inlet before it happens. Its reactive invocation makes
 * subsequent resolution conservative, but attachment-time precision would
 * require the wrapper to expose its target or the inlet to declare that it is
 * external-only. For a published unary relay whose inlet also has an open link,
 * the reactive observation cannot be attributed to the wrapper rather than the
 * link, so that wrapper-plus-link shape remains a classification limit.
 *
 * Known limit (computenet-2e2g9): a topology event delivered under another
 * wave's context (an unlink performed inside a handler, re-evaluated by a
 * quorum hop) can make a published relay emit a source its input-edge resolver
 * does not report. Topology delivery must clear that context; provenance cannot
 * predict a future side effect without discarding independent-source
 * narrowing for every published relay.
 */
internal object SourceProvenance {
    /**
     * Declares [outlet]'s resolved source set as [resolve]'s result, re-read
     * fresh on every call (no snapshot, so a later topology change — an edge
     * opening or closing on the publishing hop's own inputs — is reflected
     * immediately, with no separate invalidation step). A relay hop
     * ([civictech.cell.control.relayAbsorbAcks]) calls this
     * once per output at construction, publishing the union of its own open
     * input edges' resolved sets. [resolve] adds the publishing outlet's own
     * locally minted ids to a successfully resolved set.
     */
    fun publish(outlet: FanOutlet<*>, resolve: () -> Set<UUID>?) {
        outlet.sourceProvenanceResolver = resolve
    }

    /** The sourceIds [link] can ever carry, or `null` if unknown (see class KDoc). */
    fun resolve(link: Link): Set<UUID>? {
        val outlet = link.fromPort as? FanOutlet<*> ?: return null
        return resolve(outlet, visiting = visitingThreadLocal.get())
    }

    private fun resolve(outlet: FanOutlet<*>, visiting: MutableSet<FanOutlet<*>>): Set<UUID>? {
        if (!visiting.add(outlet)) return null // cycle guard: degrade to unknown, never loop
        try {
            outlet.sourceProvenanceResolver?.let { resolvePublished ->
                val relayedSources = resolvePublished() ?: return null
                return relayedSources + outlet.mintedAsRoot
            }
            return when (PortIdentities.hasInboundWavePath(outlet)) {
                false -> outlet.mintedAsRoot.takeIf { it.isNotEmpty() }
                true, null -> null
            }
        } finally {
            visiting.remove(outlet)
        }
    }

    // One re-entrancy guard per call stack (not per resolve() call), so a
    // chain of several relay hops sharing this thread's resolution still
    // shares one cycle-detection set.
    private val visitingThreadLocal = ThreadLocal.withInitial<MutableSet<FanOutlet<*>>> { mutableSetOf() }
}
