import type { Ref } from '../api/types';
import type { Layout } from './layered';

export interface HostHull {
  host: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

const PAD = 20;

/**
 * Padded-bbox hulls (10-target-v3.md toggle table: "Process hosts — solid
 * hulls grouping cells by `ManagedHost`" — the contract offers "convex or
 * padded-bbox"; this picks the simpler, deterministic padded bounding box).
 * A node with a null host is not grouped (no hull drawn for it) — a
 * placement-less/unknown-host cell should not silently join whichever hull
 * happens to be lexicographically first.
 *
 * Pure function of the already-computed layout, so callers control when it
 * reruns (M1-FE ticket: "recomputed only on structuralVersion change or host
 * change" — see `Canvas.tsx`'s `hostFingerprint` memo, since a host
 * reassignment alone does not bump `structuralVersion`, a *value* change).
 * Deterministic host ordering (sorted) keeps SVG paint order stable across
 * recomputes.
 */
export function computeHostHulls(
  nodeRefs: readonly Ref[],
  layout: Layout,
  hostOf: (ref: Ref) => string | null,
): HostHull[] {
  const groups = new Map<string, { minX: number; minY: number; maxX: number; maxY: number }>();

  for (const ref of nodeRefs) {
    const host = hostOf(ref);
    if (!host) continue;
    const ln = layout.nodes.get(ref);
    if (!ln) continue;
    const g = groups.get(host) ?? { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity };
    g.minX = Math.min(g.minX, ln.x);
    g.minY = Math.min(g.minY, ln.y);
    g.maxX = Math.max(g.maxX, ln.x + ln.w);
    g.maxY = Math.max(g.maxY, ln.y + ln.h);
    groups.set(host, g);
  }

  const hulls: HostHull[] = [];
  for (const [host, g] of groups) {
    hulls.push({ host, x: g.minX - PAD, y: g.minY - PAD, w: g.maxX - g.minX + PAD * 2, h: g.maxY - g.minY + PAD * 2 });
  }
  return hulls.sort((a, b) => a.host.localeCompare(b.host));
}

/** A cheap fingerprint of "which host each node claims", stable-sorted so
 *  the only thing that changes it is an actual host reassignment (add/remove
 *  is already covered by `structuralVersion`, so this is deliberately not
 *  keyed on the node set too — callers combine both). */
export function hostFingerprint(nodeRefs: readonly Ref[], hostOf: (ref: Ref) => string | null): string {
  return [...nodeRefs]
    .sort()
    .map((ref) => `${ref}:${hostOf(ref) ?? ''}`)
    .join('|');
}
