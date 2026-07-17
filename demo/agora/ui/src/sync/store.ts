import type { Delta, NodeDto, NodeRec, Ref } from '../api/types';
import { diffSnapshot } from './diff';

function push<K>(map: Map<K, Ref[]>, key: K, val: Ref): void {
  const arr = map.get(key);
  if (arr) arr.push(val);
  else map.set(key, [val]);
}

/** The single sync seam. Today fed by full snapshots; a future per-cell
 *  subscription API would add applyNodeUpsert/applyRemoval behind the same
 *  interface (not built — YAGNI). Framework-free: no Solid imports. */
export class GraphStore {
  private _nodes: Map<Ref, NodeRec> = new Map();
  private _incoming: Map<Ref, Ref[]> = new Map();
  private _outgoing: Map<Ref, Ref[]> = new Map();
  private _structuralVersion = 0;
  private subs = new Set<(d: Delta) => void>();

  /** All nodes by ref. Immutable records; identity is stable across pure
   *  credence updates. Read imperatively by the Map-mode layout memo. */
  get nodes(): ReadonlyMap<Ref, NodeRec> {
    return this._nodes;
  }
  /** EDGE refs whose target === key — i.e. the arguments *about* this node
   *  (== the spec's "children-by-target"; one index serves both). */
  get incoming(): ReadonlyMap<Ref, readonly Ref[]> {
    return this._incoming;
  }
  /** EDGE refs whose source === key. */
  get outgoing(): ReadonlyMap<Ref, readonly Ref[]> {
    return this._outgoing;
  }
  /** Bumps only on structural deltas — the Map-mode layout memo key. */
  get structuralVersion(): number {
    return this._structuralVersion;
  }

  applySnapshot(snapshot: readonly NodeDto[], opts: { resync?: boolean; now: number }): Delta {
    const { next, delta } = diffSnapshot(this._nodes, snapshot, opts);
    this._nodes = next;
    if (delta.structural) {
      this.rebuildIndexes();
      this._structuralVersion++;
    }
    for (const fn of this.subs) fn(delta);
    return delta;
  }

  private rebuildIndexes(): void {
    const inc = new Map<Ref, Ref[]>();
    const out = new Map<Ref, Ref[]>();
    for (const rec of this._nodes.values()) {
      if (rec.kind === 'EDGE' && rec.source && rec.target) {
        push(out, rec.source, rec.ref);
        push(inc, rec.target, rec.ref);
      }
    }
    this._incoming = inc;
    this._outgoing = out;
  }

  subscribe(fn: (d: Delta) => void): () => void {
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }

  get(ref: Ref): NodeRec | undefined {
    return this._nodes.get(ref);
  }

  /** Candidate focal claims, derived on demand (not a maintained index):
   *  CLAIMs by incoming-edge count desc, then recency desc, then ref. */
  focalCandidates(recencyOf?: (ref: Ref) => number): Ref[] {
    const claims: Ref[] = [];
    for (const rec of this._nodes.values()) {
      if (rec.kind === 'CLAIM') claims.push(rec.ref);
    }
    const recency = recencyOf ?? (() => 0);
    return claims.sort((a, b) => {
      const byArgs = (this._incoming.get(b)?.length ?? 0) - (this._incoming.get(a)?.length ?? 0);
      if (byArgs !== 0) return byArgs;
      const byRecency = recency(b) - recency(a);
      if (byRecency !== 0) return byRecency;
      return a < b ? -1 : a > b ? 1 : 0;
    });
  }
}
