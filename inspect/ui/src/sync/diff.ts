import type { Edge, Node, Ref } from '../api/types';
import { type EdgeRec, type NodeRec, edgeEqual, nodeEqual, normalizeEdge, normalizeNode } from './records';

export interface DiffResult<T> {
  next: Map<string, T>;
  /** true iff the *set* of ids changed (added or removed) — a pure content
   *  change on an id that survives (e.g. a node's lifecycle field) is never
   *  structural. This is the structure-vs-value split the layout memo and
   *  the canvas restyle-only path both key off. */
  structural: boolean;
}

/** Diff an absolute node snapshot against prior state. Unchanged nodes keep
 *  their previous object identity — everything downstream (Solid stores,
 *  the layout memo) relies on that invariant. */
export function diffNodes(prev: ReadonlyMap<Ref, NodeRec>, snapshot: readonly Node[]): DiffResult<NodeRec> {
  const next = new Map<Ref, NodeRec>();
  let structural = false;
  for (const dto of snapshot) {
    const cand = normalizeNode(dto);
    const before = prev.get(dto.ref);
    if (!before) structural = true;
    next.set(dto.ref, before && nodeEqual(before, cand) ? before : cand);
  }
  if (!structural) {
    for (const ref of prev.keys()) {
      if (!next.has(ref)) {
        structural = true;
        break;
      }
    }
  }
  return { next, structural };
}

/** Same shape as {@link diffNodes}, keyed by edge id. */
export function diffEdges(prev: ReadonlyMap<string, EdgeRec>, snapshot: readonly Edge[]): DiffResult<EdgeRec> {
  const next = new Map<string, EdgeRec>();
  let structural = false;
  for (const dto of snapshot) {
    const cand = normalizeEdge(dto);
    const before = prev.get(dto.id);
    if (!before) structural = true;
    next.set(dto.id, before && edgeEqual(before, cand) ? before : cand);
  }
  if (!structural) {
    for (const id of prev.keys()) {
      if (!next.has(id)) {
        structural = true;
        break;
      }
    }
  }
  return { next, structural };
}
