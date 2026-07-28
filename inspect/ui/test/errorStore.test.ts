import { describe, expect, it } from 'vitest';
import type { DeadLetterEntry, ErrorSnapshot, ParkedEntry, RestartEntry } from '../src/api/types';
import { ErrorStore } from '../src/sync/errorStore';

function snapshot(over: Partial<ErrorSnapshot> = {}): ErrorSnapshot {
  return {
    counters: { deadLetters: 0, parked: 0, restarts: 0, drainedOnTeardown: 0 },
    deadLetters: [],
    parked: [],
    restarts: [],
    ...over,
  };
}

function deadLetter(over: Partial<DeadLetterEntry> = {}): DeadLetterEntry {
  return {
    ref: 'a:0',
    cause: 'OwnershipViolation',
    description: 'boom',
    wave: { source: '9c41', counter: 288 },
    atMs: 1000,
    ...over,
  };
}

function parked(over: Partial<ParkedEntry> = {}): ParkedEntry {
  return { ref: 'a:0', port: 'left', count: 5, oldestMs: 1000, ...over };
}

function restart(over: Partial<RestartEntry> = {}): RestartEntry {
  return { ref: 'a:0', generation: 1, atMs: 1000, ...over };
}

describe('ErrorStore.applySnapshot', () => {
  it('indexes dead letters, parked rows, and restarts by ref', () => {
    const store = new ErrorStore();
    store.applySnapshot(
      snapshot({
        counters: { deadLetters: 1, parked: 5, restarts: 1, drainedOnTeardown: 0 },
        deadLetters: [deadLetter({ ref: 'a:0' })],
        parked: [parked({ ref: 'a:0', port: 'left', count: 5 })],
        restarts: [restart({ ref: 'a:0' })],
      }),
    );
    expect(store.counters).toEqual({ deadLetters: 1, parked: 5, restarts: 1, drainedOnTeardown: 0 });
    expect(store.deadLettersFor('a:0')).toHaveLength(1);
    expect(store.parkedFor('a:0')).toHaveLength(1);
    expect(store.restartsFor('a:0')).toHaveLength(1);
    expect(store.deadLettersFor('b:0')).toEqual([]);
  });

  it('never indexes a snapshot parked row with count 0 (defensive)', () => {
    const store = new ErrorStore();
    store.applySnapshot(snapshot({ parked: [parked({ count: 0 })] }));
    expect(store.parkedFor('a:0')).toEqual([]);
    expect(store.allParked()).toEqual([]);
  });

  it('replaces the whole known world — a second snapshot drops what the first had', () => {
    const store = new ErrorStore();
    store.applySnapshot(snapshot({ deadLetters: [deadLetter({ ref: 'a:0' })] }));
    store.applySnapshot(snapshot());
    expect(store.deadLettersFor('a:0')).toEqual([]);
  });
});

describe('ErrorStore.applyDeadLetter', () => {
  it('appends to the ref index and increments counters.deadLetters by one', () => {
    const store = new ErrorStore();
    store.applySnapshot(snapshot({ counters: { deadLetters: 3, parked: 0, restarts: 0, drainedOnTeardown: 0 } }));
    store.applyDeadLetter(deadLetter({ ref: 'a:0', cause: 'X' }));
    expect(store.deadLettersFor('a:0')).toEqual([deadLetter({ ref: 'a:0', cause: 'X' })]);
    expect(store.counters.deadLetters).toBe(4);

    store.applyDeadLetter(deadLetter({ ref: 'a:0', cause: 'Y' }));
    expect(store.deadLettersFor('a:0')).toHaveLength(2);
    expect(store.counters.deadLetters).toBe(5);
  });

  it('notifies subscribers', () => {
    const store = new ErrorStore();
    let calls = 0;
    store.subscribe(() => calls++);
    store.applyDeadLetter(deadLetter());
    expect(calls).toBe(1);
  });
});

describe('ErrorStore.applyParked', () => {
  it('adds a new parked row and recomputes counters.parked as the live sum', () => {
    const store = new ErrorStore();
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 5 }));
    expect(store.counters.parked).toBe(5);
    store.applyParked(parked({ ref: 'b:0', port: 'right', count: 7 }));
    expect(store.counters.parked).toBe(12);
    expect(store.parkedFor('a:0')).toEqual([parked({ ref: 'a:0', port: 'left', count: 5 })]);
  });

  it('a later row for the same (ref, port) replaces, not adds', () => {
    const store = new ErrorStore();
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 5 }));
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 9 }));
    expect(store.parkedFor('a:0')).toEqual([parked({ ref: 'a:0', port: 'left', count: 9 })]);
    expect(store.counters.parked).toBe(9);
  });

  it('a different port on the same ref is tracked independently', () => {
    const store = new ErrorStore();
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 5 }));
    store.applyParked(parked({ ref: 'a:0', port: 'right', count: 3 }));
    expect(store.parkedFor('a:0')).toHaveLength(2);
    expect(store.counters.parked).toBe(8);
  });

  it('count: 0 clears the (ref, port) row entirely — not a zero-count row kept around', () => {
    const store = new ErrorStore();
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 5 }));
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 0 }));
    expect(store.parkedFor('a:0')).toEqual([]);
    expect(store.allParked()).toEqual([]);
    expect(store.counters.parked).toBe(0);
  });

  it('clearing one port leaves a sibling port on the same ref untouched', () => {
    const store = new ErrorStore();
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 5 }));
    store.applyParked(parked({ ref: 'a:0', port: 'right', count: 3 }));
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 0 }));
    expect(store.parkedFor('a:0')).toEqual([parked({ ref: 'a:0', port: 'right', count: 3 })]);
    expect(store.counters.parked).toBe(3);
  });
});

describe('ErrorStore.applyRestart', () => {
  it('appends to the ref index and increments counters.restarts by one', () => {
    const store = new ErrorStore();
    store.applyRestart(restart({ ref: 'a:0', generation: 1 }));
    store.applyRestart(restart({ ref: 'a:0', generation: 2 }));
    expect(store.restartsFor('a:0')).toEqual([restart({ ref: 'a:0', generation: 1 }), restart({ ref: 'a:0', generation: 2 })]);
    expect(store.counters.restarts).toBe(2);
  });
});

describe('ErrorStore.allParked', () => {
  it('flattens every currently-parked row across every ref', () => {
    const store = new ErrorStore();
    store.applyParked(parked({ ref: 'a:0', port: 'left', count: 5 }));
    store.applyParked(parked({ ref: 'b:0', port: 'in', count: 2 }));
    expect([...store.allParked()].sort((x, y) => x.ref.localeCompare(y.ref))).toEqual([
      parked({ ref: 'a:0', port: 'left', count: 5 }),
      parked({ ref: 'b:0', port: 'in', count: 2 }),
    ]);
  });
});
