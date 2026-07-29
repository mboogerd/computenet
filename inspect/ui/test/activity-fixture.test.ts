import { describe, expect, it } from 'vitest';
import type { ActivityEntry, ActivityKind, ActivitySnapshot, InspectEvent, TopologySnapshot } from '../src/api/types';
import { ActivityStore } from '../src/sync/activityStore';
import activityFixture from '../fixtures/activity.json';
import activityEvent from '../fixtures/activity-event.json';
import topology from '../fixtures/topology.json';

const snapshot = activityFixture as ActivitySnapshot;
const topologySnapshot = topology as TopologySnapshot;

const ALL_KINDS: readonly ActivityKind[] = ['activated', 'passivated', 'drained', 'woken', 'restarted'];

/** V2-FE ticket Implement §14 + Acceptance criteria: `fixtures/activity.json`
 *  covers all five kinds, every ref is a real node in `fixtures/topology.json`
 *  (mirroring `test/errors-fixture.test.ts`'s cross-check), and `generation`
 *  appears only on the `restarted` entry. */
describe('fixtures/activity.json', () => {
  it('contains at least one entry of each of the five ActivityKinds', () => {
    const seen = new Set(snapshot.entries.map((e) => e.kind));
    for (const kind of ALL_KINDS) expect(seen.has(kind), kind).toBe(true);
  });

  it('every ref is a real node in fixtures/topology.json', () => {
    const refs = new Set(topologySnapshot.nodes.map((n) => n.ref));
    for (const e of snapshot.entries) expect(refs.has(e.ref), e.ref).toBe(true);
  });

  it('generation is present only on the restarted entry', () => {
    for (const e of snapshot.entries) {
      if (e.kind === 'restarted') expect(e.generation).toBeTypeOf('number');
      else expect(e.generation).toBeUndefined();
    }
  });

  it('loads through ActivityStore and yields the five kinds, newest-first', () => {
    const store = new ActivityStore();
    store.applySnapshot(snapshot);
    expect(store.entries).toHaveLength(snapshot.entries.length);
    const kinds = new Set(store.entries.map((e) => e.kind));
    for (const kind of ALL_KINDS) expect(kinds.has(kind), kind).toBe(true);
    // oldest-first on the wire -> newest-first out of the store
    expect(store.entries[0]).toEqual(snapshot.entries[snapshot.entries.length - 1]);
  });

  it('a wake entry and its paired activated entry both survive — not de-duplicated', () => {
    const wokenRef = snapshot.entries.find((e) => e.kind === 'woken')?.ref;
    expect(wokenRef).toBeDefined();
    const store = new ActivityStore();
    store.applySnapshot(snapshot);
    const kindsForRef = store.entriesFor(wokenRef!).map((e) => e.kind);
    expect(kindsForRef).toContain('woken');
    expect(kindsForRef).toContain('activated');
  });
});

describe('fixtures/activity-event.json', () => {
  it('is a well-formed SSE envelope for a node that is a real node in fixtures/topology.json', () => {
    const event = activityEvent as InspectEvent;
    expect(event.kind).toBe('activity');
    if (event.kind !== 'activity') throw new Error('unreachable');
    const refs = new Set(topologySnapshot.nodes.map((n) => n.ref));
    expect(refs.has(event.payload.ref)).toBe(true);
  });

  it('applies through the same store path the SSE case uses, appending one entry', () => {
    const event = activityEvent as InspectEvent;
    if (event.kind !== 'activity') throw new Error('unreachable');
    const store = new ActivityStore();
    store.applySnapshot(snapshot);
    const before = store.entries.length;
    store.apply(event.payload);
    expect(store.entries).toHaveLength(before + 1);
    expect(store.entries[0]).toEqual(event.payload as ActivityEntry);
  });
});
