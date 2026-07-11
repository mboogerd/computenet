import { describe, it, expect } from 'vitest';
import { GraphStore } from '../src/sync/store';
import { diffSnapshot } from '../src/sync/diff';
import type { NodeDto } from '../src/api/types';
import edgeOnEdge from './fixtures/edge-on-edge.json';
import cascadeBefore from './fixtures/removal-cascade-before.json';
import cascadeAfter from './fixtures/removal-cascade-after.json';

const clone = (x: unknown): NodeDto[] => structuredClone(x) as NodeDto[];
const claimRef = (snap: NodeDto[]) => snap.find((n) => n.kind === 'CLAIM')!.ref;

describe('diffSnapshot / GraphStore', () => {
  it('preserves object identity for nodes that did not change', () => {
    const store = new GraphStore();
    store.applySnapshot(clone(edgeOnEdge), { now: 0 });
    const firstObjects = new Map(store.nodes);

    // A pure credence move on exactly one node.
    const snap2 = clone(edgeOnEdge);
    const moved = claimRef(snap2);
    snap2.find((n) => n.ref === moved)!.credence = 0.9;
    const delta = store.applySnapshot(snap2, { now: 1000 });

    expect(delta.changed.map((c) => c.next.ref)).toEqual([moved]);
    for (const n of snap2) {
      if (n.ref === moved) {
        expect(store.get(n.ref)!.credence).toBe(0.9);
        expect(store.get(n.ref)).not.toBe(firstObjects.get(n.ref));
      } else {
        // the whole point: untouched records keep their prior object identity
        expect(store.get(n.ref)).toBe(firstObjects.get(n.ref));
      }
    }
  });

  it('reconciles a transitive removal cascade', () => {
    const store = new GraphStore();
    store.applySnapshot(clone(cascadeBefore), { now: 0 });
    const delta = store.applySnapshot(clone(cascadeAfter), { now: 1000 });

    const afterRefs = new Set((cascadeAfter as NodeDto[]).map((n) => n.ref));
    const expectedRemoved = (cascadeBefore as NodeDto[])
      .map((n) => n.ref)
      .filter((r) => !afterRefs.has(r));

    expect(expectedRemoved.length).toBe(3); // claim + its edge + the edge-on-edge
    expect(new Set(delta.removed.map((r) => r.ref))).toEqual(new Set(expectedRemoved));
    for (const r of expectedRemoved) expect(store.get(r)).toBeUndefined();
    expect(store.nodes.size).toBe(afterRefs.size);
  });

  it('flags structural vs pure-value changes and bumps structuralVersion only on structural', () => {
    const store = new GraphStore();
    store.applySnapshot(clone(edgeOnEdge), { now: 0 });
    const v0 = store.structuralVersion;

    // credence-only -> not structural, no version bump
    const snap2 = clone(edgeOnEdge);
    snap2[0].credence = snap2[0].credence < 0.5 ? 0.7 : 0.3;
    const d1 = store.applySnapshot(snap2, { now: 1 });
    expect(d1.structural).toBe(false);
    expect(store.structuralVersion).toBe(v0);

    // drop a node -> structural, version bumps
    const snap3 = clone(snap2).slice(0, -1);
    const d2 = store.applySnapshot(snap3, { now: 2 });
    expect(d2.structural).toBe(true);
    expect(store.structuralVersion).toBe(v0 + 1);
  });

  it('builds the incoming index as edge-refs-by-target (edge-on-edge included)', () => {
    const store = new GraphStore();
    store.applySnapshot(clone(edgeOnEdge), { now: 0 });

    const edges = (edgeOnEdge as NodeDto[]).filter((n) => n.kind === 'EDGE');
    // every edge appears under incoming[target] and outgoing[source]
    for (const e of edges) {
      expect(store.incoming.get(e.target!)).toContain(e.ref);
      expect(store.outgoing.get(e.source!)).toContain(e.ref);
    }
    // an edge that targets another edge => that edge has an incoming entry
    const edgeOnEdge_ = edges.find((e) => edges.some((o) => o.ref === e.target));
    expect(edgeOnEdge_, 'fixture should contain an edge targeting an edge').toBeTruthy();
    expect(store.incoming.get(edgeOnEdge_!.target!)).toContain(edgeOnEdge_!.ref);
  });

  it('normalizes omitted wire fields to explicit null/false', () => {
    const { next } = diffSnapshot(new Map(), clone(edgeOnEdge), { now: 0 });
    for (const rec of next.values()) {
      if (rec.kind === 'CLAIM') {
        expect(rec.polarity).toBeNull();
        expect(rec.source).toBeNull();
        expect(rec.head).toBe(false);
      }
    }
  });
});
