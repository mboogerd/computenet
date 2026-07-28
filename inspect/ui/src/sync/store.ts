import type { Edge, EdgeRemoval, Lifecycle, Node, Ref, TopologySnapshot } from '../api/types';
import { diffEdges, diffNodes } from './diff';
import { type EdgeRec, type NodeRec, normalizeEdge } from './records';

/** The single sync seam (agora/ui precedent, doc/spec .../10-target-v3.md
 *  "UI architecture" seam row): every topology fact — the initial snapshot,
 *  then each SSE delta — enters through one of this class's methods.
 *  Framework-free: no Solid imports, unit-tested directly. */
export class TopologyStore {
  private _nodes: Map<Ref, NodeRec> = new Map();
  private _edges: Map<string, EdgeRec> = new Map();
  private _structuralVersion = 0;
  private subs = new Set<() => void>();

  get nodes(): ReadonlyMap<Ref, NodeRec> {
    return this._nodes;
  }
  get edges(): ReadonlyMap<string, EdgeRec> {
    return this._edges;
  }
  /** Bumps only on add/remove of a node or edge — the layout memo's key, and
   *  the signal that a pure value change (e.g. a lifecycle flip) should
   *  restyle in place rather than re-run layout. */
  get structuralVersion(): number {
    return this._structuralVersion;
  }

  subscribe(fn: () => void): () => void {
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }

  private notify(): void {
    for (const fn of this.subs) fn();
  }

  /** Replace the whole known world (initial load, or a post-gap/-disconnect
   *  resync). Reuses prior record identity for anything unchanged. */
  applySnapshot(snapshot: TopologySnapshot): void {
    const nodeDiff = diffNodes(this._nodes, snapshot.nodes);
    const edgeDiff = diffEdges(this._edges, snapshot.edges);
    this._nodes = nodeDiff.next;
    this._edges = edgeDiff.next;
    if (nodeDiff.structural || edgeDiff.structural) this._structuralVersion++;
    this.notify();
  }

  applyNodeAdded(node: Node): void {
    const next = new Map(this._nodes);
    next.set(node.ref, {
      ref: node.ref,
      name: node.name ?? null,
      typeFqn: node.typeFqn,
      color: node.color ?? null,
      manifests: node.manifests ?? [],
      ports: node.ports ?? [],
      host: node.host ?? null,
      net: node.net ?? null,
      lifecycle: node.lifecycle,
      generation: node.generation,
      graph: node.graph ?? null,
    });
    this._nodes = next;
    this._structuralVersion++;
    this.notify();
  }

  applyNodeRemoved(ref: Ref): void {
    if (!this._nodes.has(ref)) return;
    const next = new Map(this._nodes);
    next.delete(ref);
    this._nodes = next;
    this._structuralVersion++;
    this.notify();
  }

  applyEdgeAdded(edge: Edge): void {
    const next = new Map(this._edges);
    next.set(edge.id, normalizeEdge(edge));
    this._edges = next;
    this._structuralVersion++;
    this.notify();
  }

  applyEdgeRemoved(edge: EdgeRemoval): void {
    if (!this._edges.has(edge.id)) return;
    const next = new Map(this._edges);
    next.delete(edge.id);
    this._edges = next;
    this._structuralVersion++;
    this.notify();
  }

  /** A lifecycle flip is a pure value change (the node keeps its layout
   *  slot; only its ghosted/not-ghosted styling changes) — no structural
   *  bump, and every OTHER node's record keeps its prior identity. */
  applyLifecycle(ref: Ref, lifecycle: Lifecycle, generation: number): void {
    const before = this._nodes.get(ref);
    if (!before) return; // defensive: an out-of-order lifecycle event for an unknown node
    if (before.lifecycle === lifecycle && before.generation === generation) return;
    const next = new Map(this._nodes);
    next.set(ref, { ...before, lifecycle, generation });
    this._nodes = next;
    this.notify();
  }

  get(ref: Ref): NodeRec | undefined {
    return this._nodes.get(ref);
  }

  /** Cell-to-cell adjacency (both CONSUME and OBSERVE collapsed to one
   *  directed edge each) — the layered layout's only input besides the node
   *  set. Recomputed on demand; cheap relative to a layout pass, and the
   *  layout memo already gates how often that pass runs. */
  adjacency(): Map<Ref, Ref[]> {
    const adj = new Map<Ref, Ref[]>();
    for (const ref of this._nodes.keys()) adj.set(ref, []);
    for (const e of this._edges.values()) {
      if (!adj.has(e.from.ref) || !adj.has(e.to.ref)) continue; // dangling ref — ignore defensively
      adj.get(e.from.ref)!.push(e.to.ref);
    }
    return adj;
  }
}
