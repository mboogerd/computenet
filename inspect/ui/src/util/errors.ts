import type { ParkedEntry, Ref } from '../api/types';

/** The minimal edge shape {@link deriveEdgeParkedCounts} needs — structurally
 *  compatible with both the wire `Edge` (api/types.ts) and the client-side
 *  `EdgeRec` (sync/records.ts), so the canvas can pass either without a cast. */
export interface EdgeTarget {
  id: string;
  to: { ref: Ref; port: string };
}

/** Red-badge counts per cell (10-target-v3.md Errors toggle: "Red badges on
 *  erring cells"; M2-FE ticket Implement §2: "red badge with count on cells
 *  having dead letters or restarts"). Parked traffic alone does not mark a
 *  cell erring — it marks the inbound edge instead, see
 *  {@link deriveEdgeParkedCounts}.
 *
 *  `enabled` is the Errors toggle state, gated here (not at the call site)
 *  so "when off, none of it renders" (ticket) is a property of this pure
 *  function directly testable without mounting the canvas. */
export function cellErrorBadges(
  refs: Iterable<Ref>,
  errorCountOf: (ref: Ref) => number,
  enabled: boolean,
): Map<Ref, number> {
  const out = new Map<Ref, number>();
  if (!enabled) return out;
  for (const ref of refs) {
    const count = errorCountOf(ref);
    if (count > 0) out.set(ref, count);
  }
  return out;
}

/** Amber "n parked" pills, one per edge terminating at a parked (ref, port)
 *  (10-target-v3.md Errors toggle: "amber 'n parked' pills on edges";
 *  M2-FE ticket Implement §2: "amber parked pill on the inbound edge(s) of
 *  cells with parked counts (aggregate per edge target port when the
 *  contract's parked rows name a port that maps to an edge)").
 *
 *  A parked row names a (ref, port) pair — the cell and its inbound port,
 *  not an edge id — so this maps it onto whichever edge(s) actually
 *  terminate there. A fan-in port (several edges sharing the same
 *  `to.ref`/`to.port`) gets the same aggregated count rendered on each
 *  matching edge, since the parked count is a property of the port, not of
 *  any one upstream sender. A parked row whose port has no matching edge in
 *  the current topology (stale/unknown port) contributes nothing to the
 *  canvas — it is still counted in `ErrorStore.counters.parked` for the
 *  header strip — rather than guessing a placement.
 *
 *  `enabled` mirrors {@link cellErrorBadges}'s toggle gating. */
export function deriveEdgeParkedCounts(
  parked: readonly ParkedEntry[],
  edges: Iterable<EdgeTarget>,
  enabled: boolean,
): Map<string, number> {
  const out = new Map<string, number>();
  if (!enabled) return out;

  const byPort = new Map<string, number>();
  for (const p of parked) {
    if (p.count <= 0) continue;
    const key = portKey(p.ref, p.port);
    byPort.set(key, (byPort.get(key) ?? 0) + p.count);
  }
  if (byPort.size === 0) return out;

  for (const e of edges) {
    const count = byPort.get(portKey(e.to.ref, e.to.port));
    if (count !== undefined) out.set(e.id, count);
  }
  return out;
}

/** A ref (`uuid:instanceId`) can never contain a space, so joining with one
 *  is collision-free — unlike joining with ':', which a ref already contains. */
function portKey(ref: Ref, port: string): string {
  return ref + ' ' + port;
}
