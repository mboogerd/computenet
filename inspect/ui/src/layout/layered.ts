import type { Ref } from '../api/types';

/** Fixed node dimensions — analytic anchors, never live DOM geometry (agora
 *  precedent, demo/agora/ui/src/layout/map.ts). */
export const NODE_W = 208;
/** Tall enough for the card's three rows at their natural line boxes — name
 *  row (20.4) + type (16.8) + badges (18), two 4px gaps and 12.8px of vertical
 *  padding = 76. At the previous 72 the type row (the only child with
 *  `overflow: hidden`, hence the only one flex could shrink below its content)
 *  absorbed the whole 4px deficit and clipped its glyphs mid-height. */
export const NODE_H = 80;
const COL_GAP = 96;
const ROW_GAP = 28;
const MARGIN = 32;

/** Sizing knobs for {@link createLayeredLayout} — defaults reproduce the
 *  canvas's full-size card layout exactly (the constants above). M4-FE
 *  ticket Implement §3: "Constellation ... reuse the layout module at
 *  thumbnail scale" — `layout/constellation.ts` is the other caller,
 *  passing a much smaller `nodeW`/`nodeH`/gaps rather than duplicating the
 *  Sugiyama layering algorithm for a second, dot-sized geometry. */
export interface LayeredLayoutConfig {
  readonly nodeW?: number;
  readonly nodeH?: number;
  readonly colGap?: number;
  readonly rowGap?: number;
  readonly margin?: number;
}

export interface LayoutNode {
  ref: Ref;
  layer: number;
  slot: number;
  x: number; // top-left
  y: number;
  w: number;
  h: number;
}

export interface Layout {
  nodes: Map<Ref, LayoutNode>;
  width: number;
  height: number;
}

/**
 * Deterministic layered (Sugiyama-style) layout: sources left, sinks right.
 * `layer[v]` = the longest path from any source (a node with no incoming
 * edge) to `v`, computed via Kahn's topological order — a node only enters
 * the frontier once every predecessor has, so its layer is purely a
 * function of its own incoming-edge subgraph. Adding a new downstream leaf
 * therefore never changes any existing node's layer.
 *
 * `slot[v]` (position within a layer) is NOT recomputed from scratch each
 * call — that is the part a naive "sort every layer by ref" approach gets
 * wrong: inserting a lexicographically-earlier ref into an existing layer
 * would shift every sibling already there. Instead this layout instance
 * remembers each ref's (layer, slot) across calls; a ref keeps its slot as
 * long as its layer doesn't change, and only claims a fresh (monotonic,
 * append-only) slot when it's new to a layer — never touching anyone else's
 * assignment. That is what "a new node must not reshuffle unrelated nodes;
 * re-layout only on structuralVersion change" (M0-FE ticket) requires.
 *
 * Known limitation (documented, not fixed here — out of scope for a single
 * FE milestone): if an existing node's layer *does* change (an upstream
 * relink pushes it deeper), it claims a fresh slot at the tail of its new
 * layer rather than being tastefully repacked; this cannot collide with an
 * already-placed node, but is not the tightest possible packing. Cycles
 * (which a Kahn's walk cannot layer) place any leftover nodes one layer
 * past the deepest resolved layer, ordered by ref for determinism —
 * skillmatch's pipeline is acyclic, so this path is untested against real
 * fixture data.
 */
export function createLayeredLayout(config: LayeredLayoutConfig = {}) {
  const nodeW = config.nodeW ?? NODE_W;
  const nodeH = config.nodeH ?? NODE_H;
  const colGap = config.colGap ?? COL_GAP;
  const rowGap = config.rowGap ?? ROW_GAP;
  const margin = config.margin ?? MARGIN;

  const assigned = new Map<Ref, { layer: number; slot: number }>();
  const nextSlot = new Map<number, number>();

  function slotFor(ref: Ref, layer: number): number {
    const prior = assigned.get(ref);
    if (prior && prior.layer === layer) return prior.slot;
    const n = nextSlot.get(layer) ?? 0;
    nextSlot.set(layer, n + 1);
    assigned.set(ref, { layer, slot: n });
    return n;
  }

  function compute(refs: readonly Ref[], adjacency: ReadonlyMap<Ref, readonly Ref[]>): Layout {
    const layer = computeLayers(refs, adjacency);
    const nodes = new Map<Ref, LayoutNode>();
    let maxLayer = 0;
    let maxSlot = new Map<number, number>();
    for (const ref of refs) {
      const l = layer.get(ref) ?? 0;
      const slot = slotFor(ref, l);
      maxLayer = Math.max(maxLayer, l);
      maxSlot.set(l, Math.max(maxSlot.get(l) ?? 0, slot));
      nodes.set(ref, {
        ref,
        layer: l,
        slot,
        x: margin + l * (nodeW + colGap),
        y: margin + slot * (nodeH + rowGap),
        w: nodeW,
        h: nodeH,
      });
    }
    const maxRows = maxSlot.size ? Math.max(...maxSlot.values()) + 1 : 0;
    const width = refs.length ? margin * 2 + (maxLayer + 1) * nodeW + maxLayer * colGap : 0;
    const height = refs.length ? margin * 2 + maxRows * nodeH + Math.max(0, maxRows - 1) * rowGap : 0;
    return { nodes, width, height };
  }

  return { compute };
}

function computeLayers(refs: readonly Ref[], adjacency: ReadonlyMap<Ref, readonly Ref[]>): Map<Ref, number> {
  const indeg = new Map<Ref, number>();
  for (const r of refs) indeg.set(r, 0);
  for (const r of refs) {
    for (const to of adjacency.get(r) ?? []) {
      if (indeg.has(to)) indeg.set(to, (indeg.get(to) ?? 0) + 1);
    }
  }

  const layer = new Map<Ref, number>();
  let frontier = refs.filter((r) => indeg.get(r) === 0).sort();
  let depth = 0;
  while (frontier.length) {
    for (const r of frontier) layer.set(r, depth);
    const nextSet = new Set<Ref>();
    for (const r of frontier) {
      for (const to of adjacency.get(r) ?? []) {
        if (!indeg.has(to) || layer.has(to)) continue;
        const d = (indeg.get(to) ?? 0) - 1;
        indeg.set(to, d);
        if (d === 0) nextSet.add(to);
      }
    }
    frontier = [...nextSet].sort();
    depth++;
  }

  // Leftover nodes are part of a cycle (Kahn's never drives their in-degree
  // to 0). Place them one layer past the deepest resolved layer,
  // deterministically ordered by ref.
  const leftover = refs.filter((r) => !layer.has(r)).sort();
  if (leftover.length) for (const r of leftover) layer.set(r, depth);

  return layer;
}
