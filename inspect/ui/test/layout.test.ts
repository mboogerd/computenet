import { describe, expect, it } from 'vitest';
import { createLayeredLayout } from '../src/layout/layered';

function chain(): { refs: string[]; adj: Map<string, string[]> } {
  // A -> B -> D, C -> D  (a small DAG: two sources, one 2-hop and one
  // 1-hop path into the shared sink D)
  const adj = new Map<string, string[]>([
    ['A', ['B']],
    ['B', ['D']],
    ['C', ['D']],
    ['D', []],
  ]);
  return { refs: ['A', 'B', 'C', 'D'], adj };
}

describe('createLayeredLayout', () => {
  it('layers sources at 0 and a sink at its longest incoming path (sources left, sinks right)', () => {
    const { refs, adj } = chain();
    const layout = createLayeredLayout().compute(refs, adj);
    expect(layout.nodes.get('A')!.layer).toBe(0);
    expect(layout.nodes.get('C')!.layer).toBe(0);
    expect(layout.nodes.get('B')!.layer).toBe(1);
    expect(layout.nodes.get('D')!.layer).toBe(2); // longest path A->B->D, not the 1-hop C->D
    // x increases strictly with layer
    expect(layout.nodes.get('A')!.x).toBeLessThan(layout.nodes.get('B')!.x);
    expect(layout.nodes.get('B')!.x).toBeLessThan(layout.nodes.get('D')!.x);
  });

  it('is deterministic and idempotent across repeated calls with the same graph', () => {
    const { refs, adj } = chain();
    const engine = createLayeredLayout();
    const l1 = engine.compute(refs, adj);
    const l2 = engine.compute(refs, adj);
    for (const ref of refs) {
      expect(l2.nodes.get(ref)).toEqual(l1.nodes.get(ref));
    }
  });

  it('does not reshuffle unrelated nodes when a new leaf node is inserted', () => {
    const { refs, adj } = chain();
    const engine = createLayeredLayout();
    const before = engine.compute(refs, adj);
    const beforePositions = new Map([...before.nodes].map(([ref, n]) => [ref, { ...n }]));

    // Insert E, a new sink hanging off B — structurally related to B/A only.
    const adj2 = new Map(adj);
    adj2.set('B', [...adj.get('B')!, 'E']);
    adj2.set('E', []);
    const after = engine.compute([...refs, 'E'], adj2);

    for (const ref of refs) {
      expect(after.nodes.get(ref)).toEqual(beforePositions.get(ref));
    }
    // The new node gets its own, non-colliding slot.
    expect(after.nodes.has('E')).toBe(true);
    expect(after.nodes.get('E')!.layer).toBe(2); // one hop past B
  });

  it('does not reshuffle a disconnected node when an isolated new node is inserted', () => {
    const engine = createLayeredLayout();
    const before = engine.compute(['X', 'Y'], new Map([['X', ['Y']], ['Y', []]]));
    const xBefore = { ...before.nodes.get('X')! };
    const yBefore = { ...before.nodes.get('Y')! };

    const after = engine.compute(
      ['X', 'Y', 'Z'],
      new Map([
        ['X', ['Y']],
        ['Y', []],
        ['Z', []],
      ]),
    );
    expect(after.nodes.get('X')).toEqual(xBefore);
    expect(after.nodes.get('Y')).toEqual(yBefore);
    expect(after.nodes.get('Z')!.layer).toBe(0);
  });

  it('places a same-layer sibling in its own slot without moving the earlier one, even when its ref sorts first', () => {
    const engine = createLayeredLayout();
    const before = engine.compute(['B'], new Map([['B', []]]));
    const bBefore = { ...before.nodes.get('B')! };

    // "A" sorts before "B" lexicographically — a naive "sort refs per layer"
    // layout would shift B's slot down when A appears; this one must not.
    const after = engine.compute(['A', 'B'], new Map([['A', []], ['B', []]]));
    expect(after.nodes.get('B')).toEqual(bBefore);
    expect(after.nodes.get('A')!.slot).not.toBe(bBefore.slot);
  });

  it('breaks a cycle deterministically instead of failing to layer those nodes', () => {
    const adj = new Map<string, string[]>([
      ['A', ['B']],
      ['B', ['A']], // A <-> B is a cycle; neither ever reaches in-degree 0
      ['C', ['A']], // a normal source feeding into the cycle
    ]);
    const layout = createLayeredLayout().compute(['A', 'B', 'C'], adj);
    expect(layout.nodes.get('C')!.layer).toBe(0);
    // A and B are placed (not dropped), one layer past the resolved frontier
    expect(layout.nodes.has('A')).toBe(true);
    expect(layout.nodes.has('B')).toBe(true);
  });

  it('returns an empty layout for no nodes', () => {
    const layout = createLayeredLayout().compute([], new Map());
    expect(layout.nodes.size).toBe(0);
    expect(layout.width).toBe(0);
    expect(layout.height).toBe(0);
  });
});
