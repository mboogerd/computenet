import { describe, expect, it } from 'vitest';
import type { Edge, Node, TopologySnapshot } from '../src/api/types';
import { TopologyStore } from '../src/sync/store';

function node(ref: string, extra: Partial<Node> = {}): Node {
  return {
    ref,
    name: ref,
    typeFqn: 'civictech.cell.data.SetCell',
    color: 'PURE',
    manifests: [],
    ports: [
      { name: 'inlet', dir: 'IN', contractFqn: 'x' },
      { name: 'outlet', dir: 'OUT', contractFqn: 'x' },
    ],
    host: 'h1',
    net: 'local',
    lifecycle: 'HOT',
    generation: 0,
    graph: null,
    ...extra,
  };
}

function edge(id: string, from: string, to: string): Edge {
  return { id, from: { ref: from, port: 'outlet' }, to: { ref: to, port: 'inlet' }, role: 'CONSUME', fused: null };
}

function snapshot(nodes: Node[], edges: Edge[] = [], seq = 1): TopologySnapshot {
  return { seq, nodes, edges };
}

describe('TopologyStore.applySnapshot', () => {
  it('bumps structuralVersion once for the initial load', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a'), node('b')]));
    expect(store.structuralVersion).toBe(1);
    expect(store.nodes.size).toBe(2);
  });

  it('does not bump structuralVersion on a pure value resync', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a')]));
    const v0 = store.structuralVersion;
    store.applySnapshot(snapshot([node('a', { generation: 1 })]));
    expect(store.structuralVersion).toBe(v0);
    expect(store.get('a')!.generation).toBe(1);
  });

  it('bumps structuralVersion when the node set changes', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a')]));
    const v0 = store.structuralVersion;
    store.applySnapshot(snapshot([node('a'), node('b')]));
    expect(store.structuralVersion).toBe(v0 + 1);
  });
});

describe('TopologyStore delta mutators', () => {
  it('applyNodeAdded/Removed bump structuralVersion; unaffected nodes keep identity', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a'), node('b')]));
    const bBefore = store.get('b');
    const v0 = store.structuralVersion;

    store.applyNodeAdded(node('c'));
    expect(store.structuralVersion).toBe(v0 + 1);
    expect(store.get('b')).toBe(bBefore); // untouched node keeps identity

    store.applyNodeRemoved('c');
    expect(store.structuralVersion).toBe(v0 + 2);
    expect(store.nodes.has('c')).toBe(false);
  });

  it('applyEdgeAdded/Removed bump structuralVersion', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a'), node('b')]));
    const v0 = store.structuralVersion;

    store.applyEdgeAdded(edge('e1', 'a', 'b'));
    expect(store.structuralVersion).toBe(v0 + 1);
    expect(store.edges.get('e1')?.from.ref).toBe('a');

    store.applyEdgeRemoved({ id: 'e1' });
    expect(store.structuralVersion).toBe(v0 + 2);
    expect(store.edges.has('e1')).toBe(false);
  });

  it('applyLifecycle is a pure value change: no structural bump, other nodes keep identity', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a'), node('b')]));
    const v0 = store.structuralVersion;
    const bBefore = store.get('b');

    store.applyLifecycle('a', 'SUSPENDED', 1);

    expect(store.structuralVersion).toBe(v0);
    expect(store.get('a')!.lifecycle).toBe('SUSPENDED');
    expect(store.get('a')!.generation).toBe(1);
    expect(store.get('b')).toBe(bBefore);
  });

  it('applyLifecycle on an unknown ref is a no-op (defensive, out-of-order event)', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a')]));
    const v0 = store.structuralVersion;
    store.applyLifecycle('ghost', 'SUSPENDED', 1);
    expect(store.structuralVersion).toBe(v0);
    expect(store.get('ghost')).toBeUndefined();
  });
});

describe('TopologyStore.adjacency', () => {
  it('collapses CONSUME and OBSERVE edges into one directed cell-to-cell map', () => {
    const store = new TopologyStore();
    store.applySnapshot(
      snapshot(
        [node('a'), node('b'), node('c')],
        [edge('e1', 'a', 'b'), { ...edge('e2', 'b', 'c'), role: 'OBSERVE' }],
      ),
    );
    const adj = store.adjacency();
    expect(adj.get('a')).toEqual(['b']);
    expect(adj.get('b')).toEqual(['c']);
    expect(adj.get('c')).toEqual([]);
  });

  it('ignores an edge with a dangling endpoint rather than crashing', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot([node('a')], [edge('e1', 'a', 'missing')]));
    expect(store.adjacency().get('a')).toEqual([]);
  });
});
