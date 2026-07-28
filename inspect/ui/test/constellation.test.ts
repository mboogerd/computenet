import { describe, expect, it } from 'vitest';
import { buildConstellations } from '../src/layout/constellation';
import type { EdgeRec, NodeRec } from '../src/sync/records';

function node(ref: string, graph: string | null, extra: Partial<NodeRec> = {}): NodeRec {
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
    graph,
    ...extra,
  };
}

function edge(id: string, from: string, to: string): EdgeRec {
  return { id, from: { ref: from, port: 'out' }, to: { ref: to, port: 'in' }, role: 'CONSUME', fused: null };
}

describe('buildConstellations', () => {
  it('groups nodes into one thumbnail per distinct Node.graph', () => {
    const nodes = [node('a', 'g-1'), node('b', 'g-1'), node('c', 'g-2')];
    const out = buildConstellations(nodes, []);
    expect(out.map((c) => c.graphId)).toEqual(['g-1', 'g-2']); // sorted
    expect(out.find((c) => c.graphId === 'g-1')!.dots).toHaveLength(2);
    expect(out.find((c) => c.graphId === 'g-2')!.dots).toHaveLength(1);
  });

  it('excludes nodes with graph: null from every thumbnail (pre-M4-BE / genuinely ungrouped)', () => {
    const nodes = [node('a', 'g-1'), node('b', null)];
    const out = buildConstellations(nodes, []);
    expect(out).toHaveLength(1);
    expect(out[0].dots.map((d) => d.ref)).toEqual(['a']);
  });

  it('includes an edge only when both endpoints belong to the same graph', () => {
    const nodes = [node('a', 'g-1'), node('b', 'g-1'), node('c', 'g-2')];
    const edges = [edge('e1', 'a', 'b'), edge('e2', 'a', 'c')]; // e2 crosses graphs — should never happen, defensive
    const out = buildConstellations(nodes, edges);
    const g1 = out.find((c) => c.graphId === 'g-1')!;
    expect(g1.edges.map((e) => e.id)).toEqual(['e1']);
    const g2 = out.find((c) => c.graphId === 'g-2')!;
    expect(g2.edges).toEqual([]);
  });

  it('drops an edge whose endpoint is ungrouped (graph: null)', () => {
    const nodes = [node('a', 'g-1'), node('b', null)];
    const edges = [edge('e1', 'a', 'b')];
    const out = buildConstellations(nodes, edges);
    expect(out[0].edges).toEqual([]);
  });

  it('produces distinct dot coordinates for two nodes in the same component', () => {
    const nodes = [node('a', 'g-1'), node('b', 'g-1')];
    const out = buildConstellations(nodes, [edge('e1', 'a', 'b')]);
    const [d1, d2] = out[0].dots;
    expect(d1.x === d2.x && d1.y === d2.y).toBe(false);
  });

  it('every constellation edge endpoint coordinate matches its dot', () => {
    const nodes = [node('a', 'g-1'), node('b', 'g-1')];
    const out = buildConstellations(nodes, [edge('e1', 'a', 'b')]);
    const c = out[0];
    const a = c.dots.find((d) => d.ref === 'a')!;
    const b = c.dots.find((d) => d.ref === 'b')!;
    const e = c.edges[0];
    expect([e.x1, e.y1]).toEqual([a.x, a.y]);
    expect([e.x2, e.y2]).toEqual([b.x, b.y]);
  });

  it('returns no thumbnails for an empty node set', () => {
    expect(buildConstellations([], [])).toEqual([]);
  });

  it('a single-node component gets a positive-size layout with one dot', () => {
    const out = buildConstellations([node('a', 'g-1')], []);
    expect(out[0].dots).toHaveLength(1);
    expect(out[0].width).toBeGreaterThan(0);
    expect(out[0].height).toBeGreaterThan(0);
  });
});
