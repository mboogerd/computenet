import { describe, expect, it } from 'vitest';
import type { TopologySnapshot } from '../src/api/types';
import { createLayeredLayout } from '../src/layout/layered';
import { TopologyStore } from '../src/sync/store';
import fixture from '../fixtures/topology.json';

/** The checked-in skillmatch fixture — exercised end to end through the same
 *  store and layout code the app uses, so a future edit that drifts from the
 *  api/types shape or breaks layering fails here rather than only visually.
 *
 *  M0-EVAL reconciled this file to reality: it is now a verbatim capture of
 *  `GET /api/inspect/topology` from the real skillmatch pilot (`:demo:skillmatch`
 *  with `--inspect-port`), sorted for a stable diff. It was authored from the
 *  contract's illustrative "13 cells, 3 hosts" before the server existed; the
 *  live graph is 16 cells (10 named pipeline cells + 6 ObserveCell sinks) on
 *  the single `skillmatch` process host, all edges CONSUME, `fused` null. */
const snapshot = fixture as TopologySnapshot;

describe('fixtures/topology.json', () => {
  it('is the real skillmatch graph: 16 cells on the one process host', () => {
    expect(snapshot.nodes.length).toBe(16);
    expect(new Set(snapshot.nodes.map((n) => n.host))).toEqual(new Set(['skillmatch']));
    expect(snapshot.edges.length).toBe(18);
    // M0 serves no fusion detection and indexes only consume-role links
    expect(new Set(snapshot.edges.map((e) => e.role))).toEqual(new Set(['CONSUME']));
    expect(snapshot.edges.every((e) => e.fused === null)).toBe(true);
    // placeholders the contract fixes until later milestones
    expect(snapshot.nodes.every((n) => n.net === 'local')).toBe(true);
    expect(snapshot.nodes.every((n) => n.graph === null)).toBe(true);
    expect(snapshot.nodes.every((n) => n.lifecycle === 'HOT')).toBe(true);
  });

  it('carries the descriptor metadata the canvas renders from', () => {
    // colors and manifests come from the generated CellDescriptor, so an empty
    // fixture here would silently hide the badge/chip rendering paths
    expect(new Set(snapshot.nodes.map((n) => n.color))).toEqual(new Set(['PURE']));
    expect(snapshot.nodes.every((n) => n.manifests.includes('DURABLE'))).toBe(true);
    const sets = snapshot.nodes.filter((n) => n.typeFqn === 'civictech.cell.data.SetCell');
    expect(sets.length).toBe(2);
    expect(sets.every((n) => n.manifests.includes('REPLICATED'))).toBe(true);
    // the app-supplied handle names, and the unnamed observation sinks
    expect(snapshot.nodes.filter((n) => n.name !== null).length).toBe(10);
    expect(snapshot.nodes.filter((n) => n.name === null).length).toBe(6);
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
