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

/** Pure: the pro/con rows for a focal claim. Reads credence imperatively from
 *  the store, so the ROW ORDER is stable across live credence updates and
 *  recomputes only on structural change — badges update live in place. Rows =
 *  the focal node's incoming edges, each paired with its source. */
export function debateRows(store: GraphStore, focal: Ref): DebateRows {
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
  support.sort(byCredence);
  attack.sort(byCredence);
  return { support, attack };
}

function byCredence(a: ArgRow, b: ArgRow): number {
  return b.edge.credence - a.edge.credence || (a.edge.ref < b.edge.ref ? -1 : 1);
}
