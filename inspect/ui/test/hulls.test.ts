import { describe, expect, it } from 'vitest';
import type { TopologySnapshot } from '../src/api/types';
import { computeHostHulls, hostFingerprint } from '../src/layout/hulls';
import { createLayeredLayout } from '../src/layout/layered';
import { TopologyStore } from '../src/sync/store';
import multihost from '../fixtures/topology-multihost.json';

const snapshot = multihost as TopologySnapshot;

function build() {
  const store = new TopologyStore();
  store.applySnapshot(snapshot);
  const layout = createLayeredLayout().compute([...store.nodes.keys()], store.adjacency());
  return { store, layout };
}

describe('computeHostHulls (fixtures/topology-multihost.json — 3 hosts, 2 cells each)', () => {
  it('groups nodes into one hull per host, sorted deterministically by host name', () => {
    const { store, layout } = build();
    const hulls = computeHostHulls([...store.nodes.keys()], layout, (ref) => store.get(ref)?.host ?? null);
    expect(hulls.map((h) => h.host)).toEqual(['host-a', 'host-b', 'host-c']);
    for (const h of hulls) {
      expect(h.w).toBeGreaterThan(0);
      expect(h.h).toBeGreaterThan(0);
    }
  });

  it('each hull bounding box contains every member node with the padding margin', () => {
    const { store, layout } = build();
    const hostOf = (ref: string) => store.get(ref)?.host ?? null;
    const hulls = computeHostHulls([...store.nodes.keys()], layout, hostOf);
    for (const ref of store.nodes.keys()) {
      const host = hostOf(ref)!;
      const hull = hulls.find((h) => h.host === host)!;
      const ln = layout.nodes.get(ref)!;
      expect(ln.x).toBeGreaterThanOrEqual(hull.x);
      expect(ln.y).toBeGreaterThanOrEqual(hull.y);
      expect(ln.x + ln.w).toBeLessThanOrEqual(hull.x + hull.w);
      expect(ln.y + ln.h).toBeLessThanOrEqual(hull.y + hull.h);
    }
  });

  it('a node with a null host is not grouped into any hull', () => {
    const layout = {
      nodes: new Map([
        ['a', { ref: 'a', layer: 0, slot: 0, x: 0, y: 0, w: 100, h: 50 }],
        ['b', { ref: 'b', layer: 0, slot: 1, x: 0, y: 100, w: 100, h: 50 }],
      ]),
      width: 100,
      height: 150,
    };
    // 'a' has a real host; 'b' has none (placement-less/unknown).
    const hulls = computeHostHulls(['a', 'b'], layout, (ref) => (ref === 'a' ? 'host-a' : null));
    expect(hulls).toHaveLength(1);
    expect(hulls[0].host).toBe('host-a');
    // the hull is sized to 'a' alone, not padded out to also cover 'b'
    expect(hulls[0].h).toBeLessThan(100);
  });

  it('returns no hulls for an empty node set', () => {
    expect(computeHostHulls([], { nodes: new Map(), width: 0, height: 0 }, () => null)).toEqual([]);
  });
});

describe('hostFingerprint (M1-FE ticket: hulls recompute "on structuralVersion change or host change")', () => {
  it('is stable across calls when nothing changed', () => {
    const { store } = build();
    const refs = [...store.nodes.keys()];
    const hostOf = (ref: string) => store.get(ref)?.host ?? null;
    expect(hostFingerprint(refs, hostOf)).toBe(hostFingerprint(refs, hostOf));
  });

  it('changes when a node is reassigned to a different host (a pure value change, no structural bump)', () => {
    const { store } = build();
    const refs = [...store.nodes.keys()];
    const before = hostFingerprint(refs, (ref) => store.get(ref)?.host ?? null);

    const reassignedRef = refs[0];
    const after = hostFingerprint(refs, (ref) => (ref === reassignedRef ? 'host-z' : (store.get(ref)?.host ?? null)));

    expect(after).not.toBe(before);
  });

  it('does not change when the node set is identical and every host is unchanged', () => {
    const { store } = build();
    const refs = [...store.nodes.keys()];
    const hostOf = (ref: string) => store.get(ref)?.host ?? null;
    const a = hostFingerprint(refs, hostOf);
    const b = hostFingerprint([...refs].reverse(), hostOf); // order-independent (sorted internally)
    expect(a).toBe(b);
  });
});
