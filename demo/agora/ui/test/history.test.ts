import { describe, it, expect } from 'vitest';
import { History } from '../src/sync/history';
import type { Delta, NodeRec, Ref } from '../src/api/types';

const rec = (ref: Ref, credence: number): NodeRec => ({
  ref,
  kind: 'CLAIM',
  text: null,
  polarity: null,
  source: null,
  target: null,
  head: false,
  credence,
});

const baseline = (ref: Ref, c: number, t: number): Delta => ({
  added: [rec(ref, c)],
  removed: [],
  changed: [],
  structural: true,
  resync: false,
  t,
});

const move = (ref: Ref, prevC: number, c: number, t: number, resync = false): Delta => ({
  added: [],
  removed: [],
  changed: [{ prev: rec(ref, prevC), next: rec(ref, c) }],
  structural: false,
  resync,
  t,
});

describe('History windowed hot detection', () => {
  it('pulses a node whose credence drifts across a burst, ignores noise, dedupes the ticker', () => {
    const h = new History();
    h.record(baseline('X', 0.5, 0));
    h.record(baseline('Y', 0.5, 0));

    // X: one stance propagates in small per-hop steps, all within the window,
    // summing to a large move 0.5 -> 0.20.
    const pulses: Ref[] = [];
    const ticker: Ref[] = [];
    const path = [0.44, 0.38, 0.3, 0.24, 0.2];
    let prev = 0.5;
    let t = 100;
    for (const c of path) {
      const hot = h.record(move('X', prev, c, t));
      pulses.push(...hot.pulses);
      ticker.push(...hot.ticker.map((e) => e.ref));
      // Y jiggles by noise on the same frames — never significant.
      const yHot = h.record(move('Y', 0.5, 0.51, t + 1));
      h.record(move('Y', 0.51, 0.5, t + 2));
      pulses.push(...yHot.pulses);
      ticker.push(...yHot.ticker.map((e) => e.ref));
      prev = c;
      t += 400;
    }

    expect(pulses).toContain('X'); // drift eventually crosses 0.15
    expect(pulses).not.toContain('Y'); // noise never pulses
    expect(ticker.filter((r) => r === 'X').length).toBe(1); // deduped per window
    expect(ticker).not.toContain('Y');
  });

  it('suppresses pulses and ticker on a resync snapshot but still records the value', () => {
    const h = new History();
    h.record(baseline('X', 0.5, 0));
    const hot = h.record(move('X', 0.5, 0.05, 100, /* resync */ true));

    expect(hot.pulses).toEqual([]);
    expect(hot.ticker).toEqual([]);
    // state still reconciles — history advanced
    expect(h.series('X').at(-1)!.credence).toBe(0.05);
  });

  it('does not pulse a freshly added node (no prior history => zero drift)', () => {
    const h = new History();
    const hot = h.record(baseline('Z', 0.05, 0));
    expect(hot.pulses).toEqual([]);
  });
});
