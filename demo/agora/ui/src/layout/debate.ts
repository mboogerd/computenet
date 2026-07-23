import type { GraphStore } from '../sync/store';
import type { NodeRec, Ref } from '../api/types';

export interface ArgRow {
  /** the EDGE node — the connector, with its own arguable credence. */
  edge: NodeRec;
  /** the node the edge comes from (a claim, or — for edge-on-edge — an edge). */
  source: NodeRec;
  /** edges targeting THIS edge ("challenges to this link"). */
  challenges: number;
}
export interface DebateRows {
  support: ArgRow[];
  attack: ArgRow[];
}

/** How to rank the pro/con rows (spec §3 sort caveat):
 *  - `link`      — by the edge's own credence (v1 default; simpler, more legible).
 *  - `effective` — by effective pull, credence(edge) × credence(source), so a
 *    strong link from a discredited source can't outrank a moderate link from a
 *    solid one. This is the recursively-aggregated strength the edge actually
 *    exerts on the focal claim. */
export type DebateSort = 'link' | 'effective';

/** An edge's effective pull on its target = its own credence × its source's
 *  credence. Exposed so the row can display it in `effective` mode. */
export function effectivePull(r: ArgRow): number {
  return r.edge.credence * r.source.credence;
}

/** Pure: the pro/con rows for a focal claim. Reads credence imperatively from
 *  the store, so the ROW ORDER is stable across live credence updates and
 *  recomputes only on structural (or sort-mode) change — badges update live in
 *  place. Rows = the focal node's incoming edges, each paired with its source. */
export function debateRows(store: GraphStore, focal: Ref, sort: DebateSort = 'link'): DebateRows {
  const support: ArgRow[] = [];
  const attack: ArgRow[] = [];
  for (const edgeRef of store.incoming.get(focal) ?? []) {
    const edge = store.get(edgeRef);
    if (!edge || edge.kind !== 'EDGE' || !edge.source) continue;
    const source = store.get(edge.source);
    if (!source) continue;
    const row: ArgRow = {
      edge,
      source,
      challenges: (store.incoming.get(edgeRef) ?? []).length,
    };
    (edge.polarity === 'SUPPORT' ? support : attack).push(row);
  }
  const cmp = sort === 'effective' ? byEffective : byLink;
  support.sort(cmp);
  attack.sort(cmp);
  return { support, attack };
}

const tiebreak = (a: ArgRow, b: ArgRow) => (a.edge.ref < b.edge.ref ? -1 : 1);

function byLink(a: ArgRow, b: ArgRow): number {
  return b.edge.credence - a.edge.credence || tiebreak(a, b);
}
function byEffective(a: ArgRow, b: ArgRow): number {
  return effectivePull(b) - effectivePull(a) || tiebreak(a, b);
}
