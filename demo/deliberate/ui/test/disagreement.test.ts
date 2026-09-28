import { describe, expect, it } from 'vitest';
import type { GraphDto, NodeDto } from '../src/api/types';
import { disagreementsOf } from '../src/components/DisagreementPanel';

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
        node({ ref: 'a', text: 'A', spreadLow: 0.4, spreadHigh: 0.5 }), // width 0.1
        node({ ref: 'b', text: 'B', spreadLow: 0.1, spreadHigh: 0.9 }), // width 0.8
        node({ ref: 'c', text: 'C', spreadLow: 0.3, spreadHigh: 0.6 }), // width 0.3
      ],
    };
    const rows = disagreementsOf(graph, 'q');
    expect(rows.map((r) => r.ref)).toEqual(['b', 'c', 'a']);
    expect(rows[0]).toMatchObject({ low: 0.1, high: 0.9, width: 0.8 });
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
