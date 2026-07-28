import { describe, expect, it } from 'vitest';
import type { Node } from '../src/api/types';
import { diffEdges, diffNodes } from '../src/sync/diff';

function node(ref: string, extra: Partial<Node> = {}): Node {
  return {
    ref,
    name: ref,
    typeFqn: 'civictech.cell.data.SetCell',
    color: 'PURE',
    manifests: [],
    ports: [],
    host: 'h1',
    net: 'local',
    lifecycle: 'HOT',
    generation: 0,
    graph: null,
    ...extra,
  };
}

describe('diffNodes', () => {
  it('preserves object identity for nodes that did not change', () => {
    const first = diffNodes(new Map(), [node('a'), node('b')]);
    const second = diffNodes(first.next, [node('a'), node('b')]);
    expect(second.next.get('a')).toBe(first.next.get('a'));
    expect(second.next.get('b')).toBe(first.next.get('b'));
    expect(second.structural).toBe(false);
  });

  it('gives a changed node a new object identity but does not flag it structural', () => {
    const first = diffNodes(new Map(), [node('a', { lifecycle: 'HOT' })]);
    const second = diffNodes(first.next, [node('a', { lifecycle: 'SUSPENDED' })]);
    expect(second.next.get('a')).not.toBe(first.next.get('a'));
    expect(second.next.get('a')!.lifecycle).toBe('SUSPENDED');
    expect(second.structural).toBe(false);
  });

  it('flags an added node as structural', () => {
    const first = diffNodes(new Map(), [node('a')]);
    const second = diffNodes(first.next, [node('a'), node('b')]);
    expect(second.structural).toBe(true);
    expect(second.next.size).toBe(2);
  });

  it('flags a removed node as structural and drops it', () => {
    const first = diffNodes(new Map(), [node('a'), node('b')]);
    const second = diffNodes(first.next, [node('a')]);
    expect(second.structural).toBe(true);
    expect(second.next.has('b')).toBe(false);
  });

  it('normalizes omitted optional fields', () => {
    const raw = { ref: 'x', typeFqn: 't', lifecycle: 'HOT', generation: 0 } as unknown as Node;
    const { next } = diffNodes(new Map(), [raw]);
    const rec = next.get('x')!;
    expect(rec.name).toBeNull();
    expect(rec.color).toBeNull();
    expect(rec.manifests).toEqual([]);
    expect(rec.ports).toEqual([]);
    expect(rec.graph).toBeNull();
  });
});

describe('diffEdges', () => {
  const edge = (id: string, fused: boolean | null = null) => ({
    id,
    from: { ref: 'a', port: 'outlet' },
    to: { ref: 'b', port: 'inlet' },
    role: 'CONSUME' as const,
    fused,
  });

  it('preserves identity for unchanged edges, flags add/remove as structural', () => {
    const first = diffEdges(new Map(), [edge('e1')]);
    const same = diffEdges(first.next, [edge('e1')]);
    expect(same.next.get('e1')).toBe(first.next.get('e1'));
    expect(same.structural).toBe(false);

    const added = diffEdges(first.next, [edge('e1'), edge('e2')]);
    expect(added.structural).toBe(true);

    const removed = diffEdges(first.next, []);
    expect(removed.structural).toBe(true);
    expect(removed.next.size).toBe(0);
  });
});
