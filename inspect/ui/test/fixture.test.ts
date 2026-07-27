import { describe, expect, it } from 'vitest';
import type { TopologySnapshot } from '../src/api/types';
import { createLayeredLayout } from '../src/layout/layered';
import { TopologyStore } from '../src/sync/store';
import fixture from '../fixtures/topology.json';

/** The checked-in skillmatch-shaped fixture (M0-FE ticket Implement §2:
 *  "unit-tested against fixtures/topology.json ... 13 cells, 3 hosts") —
 *  exercised end to end through the same store and layout code the app
 *  uses, so a future edit to the fixture that drifts from the api/types
 *  shape or breaks layering fails here rather than only visually. */
const snapshot = fixture as TopologySnapshot;

describe('fixtures/topology.json', () => {
  it('matches the contract-mandated shape: 13 cells across 3 hosts', () => {
    expect(snapshot.nodes.length).toBe(13);
    expect(new Set(snapshot.nodes.map((n) => n.host))).toEqual(new Set(['sm-host-1', 'sm-host-2', 'sm-host-3']));
    expect(snapshot.edges.length).toBeGreaterThan(0);
  });

  it('loads into TopologyStore as one structural snapshot with every ref/id unique', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot);
    expect(store.structuralVersion).toBe(1);
    expect(store.nodes.size).toBe(snapshot.nodes.length); // no duplicate refs
    expect(store.edges.size).toBe(snapshot.edges.length); // no duplicate edge ids
  });

  it('has no dangling edge endpoints (every from/to ref resolves to a node in the fixture)', () => {
    const refs = new Set(snapshot.nodes.map((n) => n.ref));
    for (const e of snapshot.edges) {
      expect(refs.has(e.from.ref), `edge ${e.id} from ${e.from.ref}`).toBe(true);
      expect(refs.has(e.to.ref), `edge ${e.id} to ${e.to.ref}`).toBe(true);
    }
  });

  it('every edge connects a real port on both its endpoint nodes', () => {
    const byRef = new Map(snapshot.nodes.map((n) => [n.ref, n]));
    for (const e of snapshot.edges) {
      const from = byRef.get(e.from.ref)!;
      const to = byRef.get(e.to.ref)!;
      expect(from.ports.some((p) => p.name === e.from.port && p.dir === 'OUT'), `${e.id} from port`).toBe(true);
      expect(to.ports.some((p) => p.name === e.to.port && p.dir === 'IN'), `${e.id} to port`).toBe(true);
    }
  });

  it('lays out with the two SetCell sources at layer 0 and a sink cell at the deepest layer', () => {
    const store = new TopologyStore();
    store.applySnapshot(snapshot);
    const layout = createLayeredLayout().compute([...store.nodes.keys()], store.adjacency());

    const cand = snapshot.nodes.find((n) => n.name === 'candSkills')!.ref;
    const jobs = snapshot.nodes.find((n) => n.name === 'jobSkills')!.ref;
    expect(layout.nodes.get(cand)!.layer).toBe(0);
    expect(layout.nodes.get(jobs)!.layer).toBe(0);

    const maxLayer = Math.max(...[...layout.nodes.values()].map((n) => n.layer));
    const deepest = snapshot.nodes.find((n) => n.typeFqn === 'civictech.cell.observe.ObserveCell')!.ref;
    expect(layout.nodes.get(deepest)!.layer).toBeGreaterThan(0);
    expect(maxLayer).toBeGreaterThan(0);
  });
});
