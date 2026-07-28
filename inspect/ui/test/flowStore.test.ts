import { describe, expect, it } from 'vitest';
import type { FlowRatesPayload } from '../src/api/types';
import { DECAY_AFTER_MISSED_WINDOWS, FlowStore } from '../src/sync/flowStore';

function batch(over: Partial<FlowRatesPayload> = {}): FlowRatesPayload {
  return { window: 1000, edges: [], ...over };
}

describe('FlowStore.applyBatch', () => {
  it('indexes every edge in the batch by id', () => {
    const store = new FlowStore();
    store.applyBatch(
      batch({
        edges: [
          { id: 'e1', rate: 12.5, lastWave: { source: '9c41', counter: 288 }, hop: 1 },
          { id: 'e2', rate: 2, lastWave: null, hop: null },
        ],
      }),
    );
    expect(store.get('e1')).toEqual({ id: 'e1', rate: 12.5, lastWave: { source: '9c41', counter: 288 }, hop: 1 });
    expect(store.get('e2')).toEqual({ id: 'e2', rate: 2, lastWave: null, hop: null });
    expect(store.get('unknown')).toBeUndefined();
    expect(store.all()).toHaveLength(2);
  });

  it('a later batch replaces an edge\'s reading rather than accumulating it', () => {
    const store = new FlowStore();
    store.applyBatch(batch({ edges: [{ id: 'e1', rate: 5, lastWave: null, hop: null }] }));
    store.applyBatch(batch({ edges: [{ id: 'e1', rate: 9, lastWave: null, hop: null }] }));
    expect(store.get('e1')?.rate).toBe(9);
    expect(store.all()).toHaveLength(1);
  });

  it('notifies subscribers on every applied batch', () => {
    const store = new FlowStore();
    let calls = 0;
    store.subscribe(() => calls++);
    store.applyBatch(batch());
    store.applyBatch(batch());
    expect(calls).toBe(2);
  });

  describe('decay (ticket Implement §1: "absent from a batch decays to zero after 2 missed windows")', () => {
    it('keeps the last reading through exactly one missed window (grace)', () => {
      const store = new FlowStore();
      store.applyBatch(batch({ edges: [{ id: 'e1', rate: 12.5, lastWave: null, hop: 1 }] }));
      store.applyBatch(batch({ edges: [] })); // e1 absent — 1st miss
      expect(store.get('e1')).toEqual({ id: 'e1', rate: 12.5, lastWave: null, hop: 1 });
    });

    it('decays to zero (the entry is removed) on the second consecutive missed window', () => {
      const store = new FlowStore();
      store.applyBatch(batch({ edges: [{ id: 'e1', rate: 12.5, lastWave: null, hop: 1 }] }));
      store.applyBatch(batch({ edges: [] })); // 1st miss — grace
      store.applyBatch(batch({ edges: [] })); // 2nd consecutive miss — decays
      expect(store.get('e1')).toBeUndefined();
      expect(store.all()).toEqual([]);
    });

    it('DECAY_AFTER_MISSED_WINDOWS is exactly 2', () => {
      expect(DECAY_AFTER_MISSED_WINDOWS).toBe(2);
    });

    it('reappearing before decay resets the miss counter — a 3rd absence does not immediately decay', () => {
      const store = new FlowStore();
      store.applyBatch(batch({ edges: [{ id: 'e1', rate: 12.5, lastWave: null, hop: 1 }] }));
      store.applyBatch(batch({ edges: [] })); // 1st miss
      store.applyBatch(batch({ edges: [{ id: 'e1', rate: 14, lastWave: null, hop: 1 }] })); // reappears — reset
      store.applyBatch(batch({ edges: [] })); // 1st miss again, not 3rd
      expect(store.get('e1')?.rate).toBe(14); // still present — grace, not decayed
    });

    it('a decayed edge can start fresh in a later batch', () => {
      const store = new FlowStore();
      store.applyBatch(batch({ edges: [{ id: 'e1', rate: 5, lastWave: null, hop: null }] }));
      store.applyBatch(batch({ edges: [] }));
      store.applyBatch(batch({ edges: [] })); // decayed
      expect(store.get('e1')).toBeUndefined();
      store.applyBatch(batch({ edges: [{ id: 'e1', rate: 8, lastWave: null, hop: null }] }));
      expect(store.get('e1')?.rate).toBe(8);
    });

    it('decay of one edge does not affect a sibling edge still reporting traffic', () => {
      const store = new FlowStore();
      store.applyBatch(
        batch({
          edges: [
            { id: 'e1', rate: 5, lastWave: null, hop: null },
            { id: 'e2', rate: 9, lastWave: null, hop: null },
          ],
        }),
      );
      store.applyBatch(batch({ edges: [{ id: 'e2', rate: 9, lastWave: null, hop: null }] })); // e1 1st miss
      store.applyBatch(batch({ edges: [{ id: 'e2', rate: 9, lastWave: null, hop: null }] })); // e1 decays
      expect(store.get('e1')).toBeUndefined();
      expect(store.get('e2')?.rate).toBe(9);
    });
  });
});
