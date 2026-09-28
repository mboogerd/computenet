import { describe, expect, it } from 'vitest';
import type { GraphDto, NodeDto } from '../src/api/types';
import { disagreementsOf } from '../src/components/DisagreementPanel';
import { AGREE_POINTS } from '../src/util/format';

const node = (over: Partial<NodeDto> & { ref: string }): NodeDto => ({
  kind: 'CLAIM',
  root: 'q',
  credence: 0.5,
  ...over,
});

describe('disagreementsOf (where the rules disagree)', () => {
  it('orders claims by spread width, widest first', () => {
    const graph: GraphDto = {
      questions: [],
      nodes: [
        node({ ref: 'a', text: 'A', spreadLow: 0.35, spreadHigh: 0.5 }), // width 0.15, clears AGREE_POINTS
        node({ ref: 'b', text: 'B', spreadLow: 0.1, spreadHigh: 0.9 }), // width 0.8
        node({ ref: 'c', text: 'C', spreadLow: 0.3, spreadHigh: 0.6 }), // width 0.3
      ],
    };
    const rows = disagreementsOf(graph, 'q');
    expect(rows.map((r) => r.ref)).toEqual(['b', 'c', 'a']);
    expect(rows[0]).toMatchObject({ low: 0.1, high: 0.9, width: 0.8 });
    expect(rows[2].low).toBeCloseTo(0.35);
    expect(rows[2].high).toBeCloseTo(0.5);
    expect(rows[2].width).toBeCloseTo(0.15);
  });

  it('excludes claims with no spread fields at all, rather than scoring them a zero-width tie', () => {
    const graph: GraphDto = {
      questions: [],
      nodes: [
        node({ ref: 'wide', text: 'wide', spreadLow: 0.1, spreadHigh: 0.9 }),
        node({ ref: 'no-spread', text: 'leaf' }), // neither field set
      ],
    };
    const rows = disagreementsOf(graph, 'q');
    expect(rows.map((r) => r.ref)).toEqual(['wide']);
  });

  it('excludes a claim whose spread is a zero-width tie (both fields present, equal), not just claims missing spread fields', () => {
    const graph: GraphDto = {
      questions: [],
      nodes: [
        node({ ref: 'wide', text: 'wide', spreadLow: 0.1, spreadHigh: 0.9 }),
        node({ ref: 'tie', text: 'tie', spreadLow: 0.47, spreadHigh: 0.47 }), // width 0
      ],
    };
    const rows = disagreementsOf(graph, 'q');
    expect(rows.map((r) => r.ref)).toEqual(['wide']);
  });

  it('excludes a claim whose spread reads as agreement under AGREE_POINTS, not only an exact tie', () => {
    const graph: GraphDto = {
      questions: [],
      nodes: [
        node({ ref: 'wide', text: 'wide', spreadLow: 0.1, spreadHigh: 0.9 }),
        node({ ref: 'within', text: 'within', spreadLow: 0.4, spreadHigh: 0.4 + AGREE_POINTS / 100 }), // width == AGREE_POINTS
      ],
    };
    const rows = disagreementsOf(graph, 'q');
    expect(rows.map((r) => r.ref)).toEqual(['wide']);
  });

  it('renders nothing when no claim clears the agreement threshold', () => {
    const graph: GraphDto = {
      questions: [],
      nodes: [node({ ref: 'tie', text: 'tie', spreadLow: 0.5, spreadHigh: 0.5 })],
    };
    expect(disagreementsOf(graph, 'q')).toEqual([]);
  });

  it('excludes nodes outside the question, and non-CLAIM nodes', () => {
    const graph: GraphDto = {
      questions: [],
      nodes: [
        node({ ref: 'here', text: 'here', root: 'q', spreadLow: 0.2, spreadHigh: 0.8 }),
        node({ ref: 'elsewhere', text: 'elsewhere', root: 'other', spreadLow: 0.2, spreadHigh: 0.8 }),
        node({ ref: 'link', text: 'link', kind: 'EDGE', spreadLow: 0.2, spreadHigh: 0.8 }),
      ],
    };
    const rows = disagreementsOf(graph, 'q');
    expect(rows.map((r) => r.ref)).toEqual(['here']);
  });

  it('caps the list at 10 entries', () => {
    const nodes: NodeDto[] = [];
    for (let i = 0; i < 15; i++) {
      nodes.push(node({ ref: `n${i}`, text: `n${i}`, spreadLow: 0, spreadHigh: i / 20 }));
    }
    const rows = disagreementsOf({ questions: [], nodes }, 'q');
    expect(rows).toHaveLength(10);
    expect(rows[0].ref).toBe('n14');
  });
});
