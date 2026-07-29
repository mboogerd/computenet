import { describe, expect, it } from 'vitest';
import type { Value } from '../src/api/types';
import { diffRows } from '../src/sync/valueDiff';

function table(columns: readonly string[], rows: unknown[]): Value {
  return { $table: { columns, rows } } as unknown as Value;
}

describe('diffRows', () => {
  it('first render (prev === undefined) returns two empty sets, even for a non-trivial table', () => {
    const next = table(['n'], [['1'], ['2']]);
    const result = diffRows(undefined, next);
    expect(result.added.size).toBe(0);
    expect(result.changed.size).toBe(0);
  });

  it('everything that is not $table or a plain array returns two empty sets', () => {
    expect(diffRows(1, 2)).toEqual({ added: new Set(), changed: new Set() });
    expect(diffRows({ a: 1 }, { a: 2 })).toEqual({ added: new Set(), changed: new Set() });
    expect(diffRows('x', 'y')).toEqual({ added: new Set(), changed: new Set() });
  });

  describe('single-column table (key = whole row; identical key implies identical content)', () => {
    it('added row', () => {
      const prev = table(['skill'], [['Kotlin']]);
      const next = table(['skill'], [['Kotlin'], ['TypeScript']]);
      const result = diffRows(prev, next);
      expect(result.added).toEqual(new Set(['["TypeScript"]']));
      expect(result.changed.size).toBe(0);
    });

    it('unchanged row is neither added nor changed', () => {
      const prev = table(['skill'], [['Kotlin']]);
      const next = table(['skill'], [['Kotlin']]);
      const result = diffRows(prev, next);
      expect(result.added.size).toBe(0);
      expect(result.changed.size).toBe(0);
    });

    it('duplicate keys within one value: keep the first occurrence, never throw', () => {
      const prev = table(['skill'], [['Kotlin']]);
      const next = table(['skill'], [['Kotlin'], ['Kotlin'], ['TypeScript']]);
      expect(() => diffRows(prev, next)).not.toThrow();
      const result = diffRows(prev, next);
      expect(result.added).toEqual(new Set(['["TypeScript"]']));
    });
  });

  describe('multi-column table (key = first cell; remaining cells decide "changed")', () => {
    it('changed row: same key (first cell), different remaining content', () => {
      const prev = table(['id', 'count'], [['a', 1]]);
      const next = table(['id', 'count'], [['a', 2]]);
      const result = diffRows(prev, next);
      expect(result.added.size).toBe(0);
      expect(result.changed).toEqual(new Set(['"a"']));
    });

    it('unchanged row (same key, same remaining content) is neither added nor changed', () => {
      const prev = table(['id', 'count'], [['a', 1]]);
      const next = table(['id', 'count'], [['a', 1]]);
      const result = diffRows(prev, next);
      expect(result.added.size).toBe(0);
      expect(result.changed.size).toBe(0);
    });

    it('added row: key absent from prev', () => {
      const prev = table(['id', 'count'], [['a', 1]]);
      const next = table(['id', 'count'], [['a', 1], ['b', 5]]);
      const result = diffRows(prev, next);
      expect(result.added).toEqual(new Set(['"b"']));
      expect(result.changed.size).toBe(0);
    });

    it('tombstoned rows: keyed/diffed via rowCells()/isTombstoneRow(), never re-deriving row shape', () => {
      const prev = table(['id', 'count'], [['a', 1]]);
      const next = table(['id', 'count'], [{ cells: ['a', 1], tombstoned: true }, ['b', 2]]);
      const result = diffRows(prev, next);
      // "a" keeps the same key and the same remaining cell content
      // (tombstone-ness is not itself a cell), so it is not flagged changed;
      // "b" is a genuinely new key.
      expect(result.added).toEqual(new Set(['"b"']));
      expect(result.changed.size).toBe(0);
    });
  });

  describe('plain arrays (key = whole stringified element; no "changed" concept)', () => {
    it('added element', () => {
      const result = diffRows([1, 2], [1, 2, 3]);
      expect(result.added).toEqual(new Set(['3']));
      expect(result.changed.size).toBe(0);
    });

    it('unchanged elements produce no flash', () => {
      const result = diffRows([1, 2], [1, 2]);
      expect(result.added.size).toBe(0);
      expect(result.changed.size).toBe(0);
    });

    it('skips a $truncated marker element (contract: appended as the last element on a plain array)', () => {
      const truncatedMarker = { $truncated: { total: 10, shown: 2 } } as unknown as Value;
      const prev = [1, 2] as unknown as Value;
      const next = [1, 2, 3, truncatedMarker] as unknown as Value;
      const result = diffRows(prev, next);
      expect(result.added).toEqual(new Set(['3']));
    });

    it('duplicate elements within one value: keep the first occurrence, never throw', () => {
      const prev = [1] as unknown as Value;
      const next = [1, 2, 2, 2] as unknown as Value;
      expect(() => diffRows(prev, next)).not.toThrow();
      const result = diffRows(prev, next);
      expect(result.added).toEqual(new Set(['2']));
    });

    it('a prior non-array value (shape mismatch) is treated as empty prior state, not thrown', () => {
      const result = diffRows(42, [1, 2]);
      expect(result.added).toEqual(new Set(['1', '2']));
    });
  });
});
