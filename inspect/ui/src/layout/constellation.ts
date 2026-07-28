import type { Ref } from '../api/types';
import type { EdgeRec, NodeRec } from '../sync/records';
import { createLayeredLayout } from './layered';

// M4-FE ticket Implement §3: "Constellation: structure-only SVG thumbnails
// per component (reuse the layout module at thumbnail scale; dots + faint
// edges, no labels beyond the card header)." A thumbnail is a one-shot
// decorative render, not an interactive surface — unlike the canvas
// (`solid/layout.ts`'s single persistent `layoutEngine`), a fresh
// `createLayeredLayout` instance per call/component is fine here; there is
// no drag/selection state whose slot stability would need protecting across
// recomputes.
const THUMB_NODE = 6;
const THUMB_COL_GAP = 9;
const THUMB_ROW_GAP = 7;
const THUMB_MARGIN = 4;

export interface ConstellationDot {
  readonly ref: Ref;
  readonly x: number;
  readonly y: number;
}

export interface ConstellationEdge {
  readonly id: string;
  readonly x1: number;
  readonly y1: number;
  readonly x2: number;
  readonly y2: number;
}

export interface Constellation {
  readonly graphId: string;
  readonly dots: readonly ConstellationDot[];
  readonly edges: readonly ConstellationEdge[];
  readonly width: number;
  readonly height: number;
  /** The SVG `viewBox` to render this thumbnail with — the laid-out extent
   *  padded out to at least {@link MIN_VIEW_W}×{@link MIN_VIEW_H} and centred
   *  in it. Without the floor, an SVG scales its own content to fill the
   *  card, so a two-cell component's dots render several times larger than a
   *  sixteen-cell one's and the grid stops reading as one picture at one
   *  scale (M4-EVAL). With it, every thumbnail up to the floor shares a
   *  scale, and only genuinely larger components scale down. */
  readonly viewBox: string;
}

/** The thumbnail viewBox floor — sized so the pilot's two-cell side graph and
 *  its sixteen-cell pipeline render at a comparable dot size. */
export const MIN_VIEW_W = 110;
export const MIN_VIEW_H = 80;

/** Group nodes/edges by `Node.graph` (the M4-BE-stamped component id) and
 *  lay out each group independently at thumbnail scale. Nodes with
 *  `graph: null` (pre-M4 backend, or a genuinely ungrouped cell) contribute
 *  to no thumbnail — 10-target-v3.md "Known kernel gaps": Home only has
 *  something to show once a real component id exists. Returned sorted by
 *  `graphId` for a stable render order. */
export function buildConstellations(nodes: Iterable<NodeRec>, edges: Iterable<EdgeRec>): Constellation[] {
  const byGraph = new Map<string, NodeRec[]>();
  const graphOf = new Map<Ref, string>();
  for (const n of nodes) {
    if (!n.graph) continue;
    const list = byGraph.get(n.graph) ?? [];
    list.push(n);
    byGraph.set(n.graph, list);
    graphOf.set(n.ref, n.graph);
  }

  const edgesByGraph = new Map<string, EdgeRec[]>();
  for (const e of edges) {
    // An edge only ever connects two cells within the same component — that
    // is what "component" over the link set means — but both endpoints are
    // re-checked defensively rather than assumed, same spirit as
    // TopologyStore.adjacency()'s "dangling ref — ignore defensively".
    const g = graphOf.get(e.from.ref);
    if (!g || graphOf.get(e.to.ref) !== g) continue;
    const list = edgesByGraph.get(g) ?? [];
    list.push(e);
    edgesByGraph.set(g, list);
  }

  const out: Constellation[] = [];
  for (const [graphId, members] of byGraph) {
    const refs = members.map((n) => n.ref).sort();
    const adjacency = new Map<Ref, Ref[]>();
    for (const r of refs) adjacency.set(r, []);
    for (const e of edgesByGraph.get(graphId) ?? []) {
      adjacency.get(e.from.ref)?.push(e.to.ref);
    }

    const layout = createLayeredLayout({
      nodeW: THUMB_NODE,
      nodeH: THUMB_NODE,
      colGap: THUMB_COL_GAP,
      rowGap: THUMB_ROW_GAP,
      margin: THUMB_MARGIN,
    }).compute(refs, adjacency);

    const dots: ConstellationDot[] = refs.map((ref) => {
      const ln = layout.nodes.get(ref)!;
      return { ref, x: ln.x + ln.w / 2, y: ln.y + ln.h / 2 };
    });
    const dotByRef = new Map(dots.map((d) => [d.ref, d] as const));
    const cEdges: ConstellationEdge[] = [];
    for (const e of edgesByGraph.get(graphId) ?? []) {
      const from = dotByRef.get(e.from.ref);
      const to = dotByRef.get(e.to.ref);
      if (from && to) cEdges.push({ id: e.id, x1: from.x, y1: from.y, x2: to.x, y2: to.y });
    }

    out.push({
      graphId,
      dots,
      edges: cEdges,
      width: layout.width,
      height: layout.height,
      viewBox: viewBoxOf(layout.width, layout.height),
    });
  }
  return out.sort((a, b) => a.graphId.localeCompare(b.graphId));
}

/** The laid-out extent grown to the {@link MIN_VIEW_W}×{@link MIN_VIEW_H}
 *  floor, with the content centred in whatever slack that adds. */
export function viewBoxOf(width: number, height: number): string {
  const w = Math.max(width, MIN_VIEW_W);
  const h = Math.max(height, MIN_VIEW_H);
  return `${(width - w) / 2} ${(height - h) / 2} ${w} ${h}`;
}
