import { describe, expect, it } from 'vitest';
import type { StateSummaryPayload } from '../src/api/types';
import { indicatesChange } from '../src/sync/summaryChange';

function payload(overrides: Partial<StateSummaryPayload> = {}): StateSummaryPayload {
  return {
    ref: 'a',
    cardinality: '3 rows',
    frontier: { source: 'host0000', counter: 3 },
    staleMs: 0,
    ...overrides,
  };
}

describe('indicatesChange', () => {
  it('true-case: prev is undefined (first summary seen for this ref since selection)', () => {
    expect(indicatesChange(undefined, payload())).toBe(true);
  });

  it('true-case: frontier.source differs (counter equal)', () => {
    const prev = payload({ frontier: { source: 'host0000', counter: 3 } });
    const next = payload({ frontier: { source: 'hostzzzz', counter: 3 } });
    expect(indicatesChange(prev, next)).toBe(true);
  });

  it('true-case: frontier.counter differs (source equal)', () => {
    const prev = payload({ frontier: { source: 'host0000', counter: 3 } });
    const next = payload({ frontier: { source: 'host0000', counter: 4 } });
    expect(indicatesChange(prev, next)).toBe(true);
  });

  it('true-case: cardinality differs', () => {
    const prev = payload({ cardinality: '3 rows' });
    const next = payload({ cardinality: '4 rows' });
    expect(indicatesChange(prev, next)).toBe(true);
  });

  it('true-case: staleMs decreases (a change settled in this window)', () => {
    const prev = payload({ staleMs: 2000 });
    const next = payload({ staleMs: 0 });
    expect(indicatesChange(prev, next)).toBe(true);
  });

  it('true-case: null -> non-null frontier transition counts as different', () => {
    const prev = payload({ frontier: null });
    const next = payload({ frontier: { source: 'host0000', counter: 1 } });
    expect(indicatesChange(prev, next)).toBe(true);
  });

  it('true-case: non-null -> null frontier transition counts as different', () => {
    const prev = payload({ frontier: { source: 'host0000', counter: 1 } });
    const next = payload({ frontier: null });
    expect(indicatesChange(prev, next)).toBe(true);
  });

  it('false-case: a quiet window — identical frontier/cardinality, staleMs grows', () => {
    const prev = payload({ staleMs: 0 });
    const next = payload({ staleMs: 1000 });
    expect(indicatesChange(prev, next)).toBe(false);
  });

  it('false-case: identical frontier/cardinality/staleMs (a repeated window)', () => {
    const prev = payload({ staleMs: 1000 });
    const next = payload({ staleMs: 1000 });
    expect(indicatesChange(prev, next)).toBe(false);
  });

  it('false-case: both frontiers null, nothing else differs', () => {
    const prev = payload({ frontier: null, staleMs: 1000 });
    const next = payload({ frontier: null, staleMs: 2000 });
    expect(indicatesChange(prev, next)).toBe(false);
  });

  it('staleMs grows monotonically across several consecutive quiet windows, never flips true', () => {
    let prev: StateSummaryPayload | undefined = payload({ staleMs: 0 });
    for (const staleMs of [1000, 2000, 3000, 4000, 5000]) {
      const next = payload({ staleMs });
      expect(indicatesChange(prev, next)).toBe(false);
      prev = next;
    }
  });
});
