import { describe, expect, it } from 'vitest';
import type { ActivityEntry, ActivitySnapshot } from '../src/api/types';
import { ACTIVITY_STORE_CAP, ActivityStore } from '../src/sync/activityStore';

function entry(over: Partial<ActivityEntry> = {}): ActivityEntry {
  return { ref: 'a:0', kind: 'activated', atMs: 1000, ...over };
}

function snapshot(entries: readonly ActivityEntry[]): ActivitySnapshot {
  return { entries };
}

describe('ActivityStore.applySnapshot', () => {
  it('loads entries and exposes them newest-first', () => {
    const store = new ActivityStore();
    store.applySnapshot(
      snapshot([entry({ ref: 'a:0', atMs: 1000 }), entry({ ref: 'a:0', atMs: 2000 }), entry({ ref: 'a:0', atMs: 3000 })]),
    );
    expect(store.entries.map((e) => e.atMs)).toEqual([3000, 2000, 1000]);
  });

  it('replaces the whole known world — a second snapshot drops what the first had', () => {
    const store = new ActivityStore();
    store.applySnapshot(snapshot([entry({ ref: 'a:0' })]));
    store.applySnapshot(snapshot([]));
    expect(store.entries).toEqual([]);
  });

  it('defensively caps at ACTIVITY_STORE_CAP even if the wire sent more, keeping the newest', () => {
    const store = new ActivityStore();
    const entries = Array.from({ length: ACTIVITY_STORE_CAP + 10 }, (_, i) => entry({ ref: 'a:0', atMs: i }));
    store.applySnapshot(snapshot(entries));
    expect(store.entries).toHaveLength(ACTIVITY_STORE_CAP);
    // oldest-first input -> the newest ACTIVITY_STORE_CAP were kept
    expect(store.entries[0].atMs).toBe(ACTIVITY_STORE_CAP + 9);
    expect(store.entries[store.entries.length - 1].atMs).toBe(10);
  });
});

describe('ActivityStore.apply', () => {
  it('appends and notifies subscribers', () => {
    const store = new ActivityStore();
    let calls = 0;
    store.subscribe(() => calls++);
    store.apply(entry({ ref: 'a:0', atMs: 1000 }));
    store.apply(entry({ ref: 'a:0', atMs: 2000 }));
    expect(calls).toBe(2);
    expect(store.entries.map((e) => e.atMs)).toEqual([2000, 1000]);
  });

  it('evicts the oldest entry first once past the cap — bounded, "never grows without bound"', () => {
    const store = new ActivityStore();
    for (let i = 0; i < ACTIVITY_STORE_CAP + 5; i++) store.apply(entry({ ref: 'a:0', atMs: i }));
    expect(store.entries).toHaveLength(ACTIVITY_STORE_CAP);
    // the 5 oldest (atMs 0..4) were evicted; the newest is atMs CAP+4
    expect(store.entries[0].atMs).toBe(ACTIVITY_STORE_CAP + 4);
    expect(store.entries[store.entries.length - 1].atMs).toBe(5);
  });

  it('a wake legitimately produces both a woken and an activated entry for the same ref — never de-duplicated', () => {
    const store = new ActivityStore();
    store.apply(entry({ ref: 'a:0', kind: 'woken', atMs: 1000 }));
    store.apply(entry({ ref: 'a:0', kind: 'activated', atMs: 1001 }));
    expect(store.entriesFor('a:0')).toHaveLength(2);
    expect(store.entriesFor('a:0').map((e) => e.kind)).toEqual(['activated', 'woken']);
  });
});

describe('ActivityStore.entriesFor', () => {
  it('returns only the given ref, newest-first', () => {
    const store = new ActivityStore();
    store.apply(entry({ ref: 'a:0', atMs: 1000 }));
    store.apply(entry({ ref: 'b:0', atMs: 1500 }));
    store.apply(entry({ ref: 'a:0', atMs: 2000 }));
    expect(store.entriesFor('a:0').map((e) => e.atMs)).toEqual([2000, 1000]);
    expect(store.entriesFor('b:0').map((e) => e.atMs)).toEqual([1500]);
    expect(store.entriesFor('c:0')).toEqual([]);
  });
});
