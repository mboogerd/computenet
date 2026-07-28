import { describe, expect, it } from 'vitest';
import type { TopologySnapshot } from '../src/api/types';
import { computeHostHulls, computeNetHulls, netFingerprint } from '../src/layout/hulls';
import { createLayeredLayout } from '../src/layout/layered';
import { cardAnchor, portAnchors } from '../src/layout/ports';
import { TopologyStore } from '../src/sync/store';
import { REMOTE_NOTICE, isRemotePlacement } from '../src/util/placement';
import nets from '../fixtures/topology-nets.json';

// A synthetic two-network snapshot, alongside the golden single-host
// `topology.json` and M1-FE's `topology-multihost.json` (20-api-contract.md
// §Fixture): one local JVM ("jvm-a", two process hosts) peered with one
// announced peer ("peer-7c19f3ab", no process host, no descriptors), plus the
// declared cross-boundary edge between them.
const snapshot = nets as TopologySnapshot;

function build() {
  const store = new TopologyStore();
  store.applySnapshot(snapshot);
  const layout = createLayeredLayout().compute([...store.nodes.keys()], store.adjacency());
  return { store, layout };
}

const netOf = (store: TopologyStore) => (ref: string) => store.get(ref)?.net ?? null;
const hostOf = (store: TopologyStore) => (ref: string) => store.get(ref)?.host ?? null;

describe('computeNetHulls (M5-NET Implement §2 — dashed hulls per network host)', () => {
  it('groups nodes into one hull per net, sorted deterministically', () => {
    const { store, layout } = build();
    const hulls = computeNetHulls([...store.nodes.keys()], layout, netOf(store), hostOf(store));
    expect(hulls.map((h) => h.net)).toEqual(['jvm-a', 'peer-7c19f3ab']);
  });

  it('flags a net as a peer exactly when no member reports a process host', () => {
    const { store, layout } = build();
    const hulls = computeNetHulls([...store.nodes.keys()], layout, netOf(store), hostOf(store));
    expect(hulls.find((h) => h.net === 'jvm-a')!.peer).toBe(false);
    expect(hulls.find((h) => h.net === 'peer-7c19f3ab')!.peer).toBe(true);
  });

  it('nests: every process hull lies strictly inside its net hull', () => {
    const { store, layout } = build();
    const refs = [...store.nodes.keys()];
    const netHulls = computeNetHulls(refs, layout, netOf(store), hostOf(store));
    const hostHulls = computeHostHulls(refs, layout, hostOf(store));

    // the peer's cells contribute no process hull at all — they sit directly
    // in the net hull (M5-NET: "nodes with host: null sit directly in the net
    // hull"), so every process hull belongs to a net that has one
    expect(hostHulls.map((h) => h.host).sort()).toEqual(['shopping', 'shopping-bridge']);

    for (const hostHull of hostHulls) {
      const member = refs.find((ref) => hostOf(store)(ref) === hostHull.host)!;
      const net = netOf(store)(member)!;
      const netHull = netHulls.find((h) => h.net === net)!;
      expect(hostHull.x).toBeGreaterThan(netHull.x);
      expect(hostHull.y).toBeGreaterThan(netHull.y);
      expect(hostHull.x + hostHull.w).toBeLessThan(netHull.x + netHull.w);
      expect(hostHull.y + hostHull.h).toBeLessThan(netHull.y + netHull.h);
    }
  });

  it('every member node lies inside its own net hull', () => {
    const { store, layout } = build();
    const refs = [...store.nodes.keys()];
    const hulls = computeNetHulls(refs, layout, netOf(store), hostOf(store));
    for (const ref of refs) {
      const hull = hulls.find((h) => h.net === netOf(store)(ref))!;
      const ln = layout.nodes.get(ref)!;
      expect(ln.x).toBeGreaterThanOrEqual(hull.x);
      expect(ln.y).toBeGreaterThanOrEqual(hull.y);
      expect(ln.x + ln.w).toBeLessThanOrEqual(hull.x + hull.w);
      expect(ln.y + ln.h).toBeLessThanOrEqual(hull.y + hull.h);
    }
  });

  it('a node with a null net is not grouped into any hull', () => {
    const layout = {
      nodes: new Map([
        ['a', { ref: 'a', layer: 0, slot: 0, x: 0, y: 0, w: 100, h: 50 }],
        ['b', { ref: 'b', layer: 0, slot: 1, x: 0, y: 400, w: 100, h: 50 }],
      ]),
      width: 100,
      height: 450,
    };
    const hulls = computeNetHulls(['a', 'b'], layout, (ref) => (ref === 'a' ? 'jvm-a' : null), () => 'h');
    expect(hulls).toHaveLength(1);
    expect(hulls[0].net).toBe('jvm-a');
    expect(hulls[0].h).toBeLessThan(400);
  });

  it('returns no hulls for an empty node set', () => {
    expect(computeNetHulls([], { nodes: new Map(), width: 0, height: 0 }, () => null, () => null)).toEqual([]);
  });
});

describe('netFingerprint (the Network hosts memo dependency)', () => {
  it('is stable across calls when nothing changed, and order-independent', () => {
    const { store } = build();
    const refs = [...store.nodes.keys()];
    expect(netFingerprint(refs, netOf(store))).toBe(netFingerprint([...refs].reverse(), netOf(store)));
  });

  it('changes when a peer comes back under a new label (a pure value change)', () => {
    const { store } = build();
    const refs = [...store.nodes.keys()];
    const before = netFingerprint(refs, netOf(store));
    const after = netFingerprint(refs, (ref) => {
      const net = netOf(store)(ref);
      return net === 'peer-7c19f3ab' ? 'peer-04b1ce22' : net;
    });
    expect(after).not.toBe(before);
  });
});

describe('isRemotePlacement (M5-NET Exclusions — the detail placeholder gate)', () => {
  it('is true exactly for the peer-announced cells of the fixture', () => {
    const { store } = build();
    const remote = [...store.nodes.values()].filter((n) => isRemotePlacement(n));
    expect(remote.map((n) => n.net)).toEqual(['peer-7c19f3ab', 'peer-7c19f3ab']);
  });

  it('is false for a locally hosted cell, whatever its net is named', () => {
    const { store } = build();
    const local = store.get('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:0')!;
    // deliberately NOT keyed on net === 'local': the launcher named this JVM
    expect(local.net).toBe('jvm-a');
    expect(isRemotePlacement(local)).toBe(false);
  });

  it('is false for nothing selected', () => {
    expect(isRemotePlacement(null)).toBe(false);
    expect(isRemotePlacement(undefined)).toBe(false);
  });

  it('states the milestone limitation verbatim', () => {
    expect(REMOTE_NOTICE).toBe('remote — state/flow/errors not available in this milestone');
  });
});

describe('cardAnchor (M3-EVAL open question 1 — the edge the canvas used to drop)', () => {
  const ln = { ref: 'a', layer: 0, slot: 0, x: 10, y: 20, w: 100, h: 40 };

  it('anchors an unknown IN port on the left edge and an unknown OUT port on the right', () => {
    expect(cardAnchor(ln, 'IN')).toEqual({ x: 10, y: 40 });
    expect(cardAnchor(ln, 'OUT')).toEqual({ x: 110, y: 40 });
  });

  it('is the fallback for a peer cell, whose port list is empty', () => {
    const { store } = build();
    const peerCell = store.get('dddddddd-dddd-dddd-dddd-dddddddddddd:0')!;
    expect(peerCell.ports).toEqual([]);
    // the declared cross-boundary edge lands on "inlet", which this cell never
    // reported — without a fallback the whole edge would be invisible
    expect(portAnchors(ln, peerCell.ports).get('inlet')).toBeUndefined();
    expect(cardAnchor(ln, 'IN')).toBeDefined();
  });
});
