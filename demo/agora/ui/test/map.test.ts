import { describe, it, expect } from 'vitest';
import { GraphStore } from '../src/sync/store';
import { layoutMap } from '../src/layout/map';
import type { NodeDto } from '../src/api/types';

const dto = (ref: string, kind: 'CLAIM' | 'EDGE', extra: Partial<NodeDto> = {}): NodeDto => ({
  ref,
  kind,
  credence: 0.5,
  ...extra,
});

function build(nodes: NodeDto[]): GraphStore {
  const s = new GraphStore();
  s.applySnapshot(nodes, { now: 0 });
  return s;
}

describe('layoutMap', () => {
  it('reifies edges as junction vertices and layers by hop distance from focal', () => {
    const store = build([
      dto('A', 'CLAIM'),
      dto('B', 'CLAIM'),
      dto('e', 'EDGE', { polarity: 'ATTACK', source: 'B', target: 'A' }),
    ]);
    const l = layoutMap(store, 'A');
    expect(l.vertices.get('A')!.depth).toBe(0);
    expect(l.vertices.get('e')!.depth).toBe(1); // the edge is its own vertex, one hop away
    expect(l.vertices.get('B')!.depth).toBe(2);
    expect(l.segments.map((s) => `${s.from}->${s.to}`).sort()).toEqual(['B->e', 'e->A']);
    expect(l.segments.find((s) => s.part === 'out')!.to).toBe('A'); // arrowhead points at target
  });

  it('layers an edge-on-edge structure deeper', () => {
    const store = build([
      dto('A', 'CLAIM'),
      dto('B', 'CLAIM'),
      dto('C', 'CLAIM'),
      dto('e1', 'EDGE', { polarity: 'ATTACK', source: 'B', target: 'A' }),
      dto('e2', 'EDGE', { polarity: 'ATTACK', source: 'C', target: 'e1' }),
    ]);
    const l = layoutMap(store, 'A');
    expect(l.vertices.get('e1')!.depth).toBe(1);
    expect(l.vertices.get('B')!.depth).toBe(2);
    expect(l.vertices.get('e2')!.depth).toBe(2); // e2 targets e1 (a depth-1 junction)
    expect(l.vertices.get('C')!.depth).toBe(3);
  });

  it('is deterministic across runs', () => {
    const nodes = [
      dto('A', 'CLAIM'),
      dto('B', 'CLAIM'),
      dto('C', 'CLAIM'),
      dto('eB', 'EDGE', { polarity: 'SUPPORT', source: 'B', target: 'A' }),
      dto('eC', 'EDGE', { polarity: 'ATTACK', source: 'C', target: 'A' }),
    ];
    const pos = (l: ReturnType<typeof layoutMap>) =>
      [...l.vertices.values()].map((v) => `${v.ref}:${v.x},${v.y}`).sort();
    expect(pos(layoutMap(build(nodes), 'A'))).toEqual(pos(layoutMap(build(nodes), 'A')));
  });

  it('reports unreachable components', () => {
    const store = build([
      dto('A', 'CLAIM'),
      dto('X', 'CLAIM'), // disconnected
      dto('Y', 'CLAIM'),
      dto('eXY', 'EDGE', { polarity: 'SUPPORT', source: 'X', target: 'Y' }),
    ]);
    const l = layoutMap(store, 'A');
    expect(new Set(l.unreachable)).toEqual(new Set(['X', 'Y', 'eXY']));
    expect(l.vertices.get('A')!.depth).toBe(0);
  });

  it('places focal above its neighbors (smaller y)', () => {
    const store = build([
      dto('A', 'CLAIM'),
      dto('B', 'CLAIM'),
      dto('eB', 'EDGE', { polarity: 'SUPPORT', source: 'B', target: 'A' }),
    ]);
    const l = layoutMap(store, 'A');
    expect(l.vertices.get('A')!.y).toBeLessThan(l.vertices.get('B')!.y);
  });
});
