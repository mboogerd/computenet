import { describe, it, expect } from 'vitest';
import { GraphStore } from '../src/sync/store';
import { debateRows } from '../src/layout/debate';
import type { NodeDto } from '../src/api/types';

const dto = (ref: string, kind: 'CLAIM' | 'EDGE', extra: Partial<NodeDto> = {}): NodeDto => ({
  ref,
  kind,
  credence: 0.5,
  ...extra,
});

describe('debateRows', () => {
  it('splits incoming edges into support/attack, sorted by edge credence desc', () => {
    const store = new GraphStore();
    store.applySnapshot(
      [
        dto('A', 'CLAIM', { text: 'focal' }),
        dto('S1', 'CLAIM'),
        dto('S2', 'CLAIM'),
        dto('K1', 'CLAIM'),
        dto('eS1', 'EDGE', { polarity: 'SUPPORT', source: 'S1', target: 'A', credence: 0.4 }),
        dto('eS2', 'EDGE', { polarity: 'SUPPORT', source: 'S2', target: 'A', credence: 0.8 }),
        dto('eK1', 'EDGE', { polarity: 'ATTACK', source: 'K1', target: 'A', credence: 0.6 }),
      ],
      { now: 0 },
    );
    const rows = debateRows(store, 'A');
    expect(rows.support.map((r) => r.edge.ref)).toEqual(['eS2', 'eS1']); // 0.8 before 0.4
    expect(rows.attack.map((r) => r.edge.ref)).toEqual(['eK1']);
    expect(rows.support[0].source.ref).toBe('S2');
  });

  it('counts challenges (edges targeting an edge) for the edge chip', () => {
    const store = new GraphStore();
    store.applySnapshot(
      [
        dto('A', 'CLAIM'),
        dto('B', 'CLAIM'),
        dto('C', 'CLAIM'),
        dto('e1', 'EDGE', { polarity: 'ATTACK', source: 'B', target: 'A', credence: 0.5 }),
        dto('e2', 'EDGE', { polarity: 'ATTACK', source: 'C', target: 'e1', credence: 0.5 }),
      ],
      { now: 0 },
    );
    const rows = debateRows(store, 'A');
    expect(rows.attack).toHaveLength(1);
    expect(rows.attack[0].challenges).toBe(1); // e2 challenges e1
  });

  it('effective-pull sort ranks by credence(edge) × credence(source), not edge alone', () => {
    const store = new GraphStore();
    store.applySnapshot(
      [
        dto('A', 'CLAIM', { text: 'focal' }),
        // strong link (0.9) from a discredited source (0.2) -> pull 0.18
        dto('weak', 'CLAIM', { credence: 0.2 }),
        dto('eWeak', 'EDGE', { polarity: 'SUPPORT', source: 'weak', target: 'A', credence: 0.9 }),
        // moderate link (0.6) from a solid source (0.9) -> pull 0.54
        dto('solid', 'CLAIM', { credence: 0.9 }),
        dto('eSolid', 'EDGE', { polarity: 'SUPPORT', source: 'solid', target: 'A', credence: 0.6 }),
      ],
      { now: 0 },
    );
    // by link credence: the strong-but-discredited edge wins
    expect(debateRows(store, 'A', 'link').support.map((r) => r.edge.ref)).toEqual(['eWeak', 'eSolid']);
    // by effective pull: the solid source's edge wins (0.54 > 0.18)
    expect(debateRows(store, 'A', 'effective').support.map((r) => r.edge.ref)).toEqual(['eSolid', 'eWeak']);
  });

  it('returns empty columns for an unknown or edgeless focal', () => {
    const store = new GraphStore();
    store.applySnapshot([dto('A', 'CLAIM')], { now: 0 });
    expect(debateRows(store, 'A')).toEqual({ support: [], attack: [] });
    expect(debateRows(store, 'nope')).toEqual({ support: [], attack: [] });
  });
});
