import type { GraphStore } from '../sync/store';
import type { Polarity, Ref } from '../api/types';

/** Fixed node dimensions (spec §3): so segment anchors are analytic and we
 *  never read live DOM geometry. */
export const CLAIM_W = 220;
export const CLAIM_H = 64;
export const JUNCTION = 16;
const COL_GAP = 34;
const ROW_H = 128;
const MARGIN = 40;

export interface Vertex {
  ref: Ref;
  kind: 'CLAIM' | 'EDGE';
  x: number; // top-left
  y: number;
  w: number;
  h: number;
  depth: number;
}
export interface Segment {
  edgeRef: Ref;
  from: Ref;
  to: Ref;
  polarity: Polarity;
  /** 'out' carries the arrowhead/T-bar (it points at the edge's target). */
  part: 'in' | 'out';
}
export interface MapLayout {
  vertices: Map<Ref, Vertex>;
  segments: Segment[];
  unreachable: Ref[];
  width: number;
  height: number;
}

const dim = (kind: 'CLAIM' | 'EDGE') =>
  kind === 'EDGE' ? { w: JUNCTION, h: JUNCTION } : { w: CLAIM_W, h: CLAIM_H };

/** Pure: the reified drawing graph (every EDGE becomes a junction vertex,
 *  spec §3) laid out in BFS hop-layers rooted at `focal`. Depends only on
 *  structure + focal — credence never enters here, so a vote never re-lays-out.
 *  Deterministic (sorted BFS + ref tiebreaks). */
export function layoutMap(store: GraphStore, focal: Ref): MapLayout {
  const vertices = new Map<Ref, Vertex>();
  const adj = new Map<Ref, Set<Ref>>();
  const ensureAdj = (r: Ref) => adj.get(r) ?? adj.set(r, new Set()).get(r)!;
  const linkUndirected = (a: Ref, b: Ref) => {
    ensureAdj(a).add(b);
    ensureAdj(b).add(a);
  };

  for (const rec of store.nodes.values()) {
    const { w, h } = dim(rec.kind);
    vertices.set(rec.ref, { ref: rec.ref, kind: rec.kind, x: 0, y: 0, w, h, depth: -1 });
    ensureAdj(rec.ref);
  }

  const segments: Segment[] = [];
  for (const rec of store.nodes.values()) {
    if (
      rec.kind === 'EDGE' &&
      rec.source &&
      rec.target &&
      vertices.has(rec.source) &&
      vertices.has(rec.target)
    ) {
      const pol: Polarity = rec.polarity ?? 'SUPPORT';
      linkUndirected(rec.source, rec.ref);
      linkUndirected(rec.ref, rec.target);
      segments.push({ edgeRef: rec.ref, from: rec.source, to: rec.ref, polarity: pol, part: 'in' });
      segments.push({ edgeRef: rec.ref, from: rec.ref, to: rec.target, polarity: pol, part: 'out' });
    }
  }

  // BFS layers from focal (sorted expansion => deterministic)
  const layers: Ref[][] = [];
  const focalV = vertices.get(focal);
  if (focalV) {
    focalV.depth = 0;
    const queue: Ref[] = [focal];
    while (queue.length) {
      const cur = queue.shift()!;
      const d = vertices.get(cur)!.depth;
      (layers[d] ??= []).push(cur);
      for (const nb of [...(adj.get(cur) ?? [])].sort()) {
        const v = vertices.get(nb)!;
        if (v.depth === -1) {
          v.depth = d + 1;
          queue.push(nb);
        }
      }
    }
  }

  // initial x by discovery order
  for (let d = 0; d < layers.length; d++) {
    let x = 0;
    for (const ref of layers[d]) {
      const v = vertices.get(ref)!;
      v.x = x;
      v.y = d * ROW_H;
      x += v.w + COL_GAP;
    }
    centerLayer(layers[d], vertices);
  }

  // barycenter sweeps to reduce crossings / align under parents
  for (let pass = 0; pass < 3; pass++) {
    for (let d = 1; d < layers.length; d++) sweep(layers[d], vertices, adj);
    for (let d = layers.length - 2; d >= 1; d--) sweep(layers[d], vertices, adj);
  }

  const unreachable = [...vertices.values()].filter((v) => v.depth === -1).map((v) => v.ref);

  return { vertices, segments, unreachable, ...bounds(vertices) };
}

function centerLayer(layer: Ref[], vertices: Map<Ref, Vertex>): void {
  if (!layer.length) return;
  let min = Infinity;
  let max = -Infinity;
  for (const ref of layer) {
    const v = vertices.get(ref)!;
    min = Math.min(min, v.x);
    max = Math.max(max, v.x + v.w);
  }
  const shift = -(min + max) / 2;
  for (const ref of layer) vertices.get(ref)!.x += shift;
}

function sweep(layer: Ref[], vertices: Map<Ref, Vertex>, adj: Map<Ref, Set<Ref>>): void {
  const want = layer.map((ref) => {
    const self = vertices.get(ref)!;
    const xs: number[] = [];
    for (const nb of adj.get(ref) ?? []) {
      const v = vertices.get(nb)!;
      if (v.depth !== self.depth) xs.push(v.x + v.w / 2);
    }
    const desired = xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : self.x + self.w / 2;
    return { ref, desired };
  });
  want.sort((a, b) => a.desired - b.desired || (a.ref < b.ref ? -1 : 1));

  // place left-to-right honoring desired center but never overlapping
  let cursor = -Infinity;
  for (const w of want) {
    const v = vertices.get(w.ref)!;
    const left = Math.max(w.desired - v.w / 2, cursor);
    v.x = left;
    cursor = left + v.w + COL_GAP;
  }
  layer.length = 0;
  layer.push(...want.map((w) => w.ref));
  centerLayer(layer, vertices);
}

function bounds(vertices: Map<Ref, Vertex>): { width: number; height: number } {
  let minX = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const v of vertices.values()) {
    if (v.depth === -1) continue;
    minX = Math.min(minX, v.x);
    maxX = Math.max(maxX, v.x + v.w);
    maxY = Math.max(maxY, v.y + v.h);
  }
  if (!isFinite(minX)) return { width: 0, height: 0 };
  // shift all vertices so the drawing starts at MARGIN,MARGIN
  for (const v of vertices.values()) {
    if (v.depth === -1) continue;
    v.x += MARGIN - minX;
    v.y += MARGIN;
  }
  return { width: maxX - minX + MARGIN * 2, height: maxY + MARGIN * 2 };
}
