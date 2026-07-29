import { describe, expect, it } from 'vitest';
import type { ActivityEntry } from '../src/api/types';
import { ACTIVITY_KIND_META, ACTIVITY_ROW_CAP, deriveActivityRows } from '../src/util/activity';

function entry(over: Partial<ActivityEntry> = {}): ActivityEntry {
  return { ref: 'a:0', kind: 'activated', atMs: Date.UTC(2026, 0, 1, 12, 34, 56), ...over };
}

const nameOf = (ref: string) => (ref === 'a:0' ? 'alpha' : null);

describe('deriveActivityRows', () => {
  it('maps every entry to a row with a label (topology-store name when known)', () => {
    const { rows } = deriveActivityRows([entry({ ref: 'a:0' })], { onlySelected: false, selectedRef: null, nameOf });
    expect(rows).toHaveLength(1);
    expect(rows[0].label).toBe('alpha');
  });

  it('falls back to the short ref when the topology store has no name for it', () => {
    const { rows } = deriveActivityRows([entry({ ref: 'b234567890:0' })], { onlySelected: false, selectedRef: null, nameOf });
    expect(rows[0].label).toBe('b2345678');
  });

  it('carries generation through only when the entry has one', () => {
    const { rows } = deriveActivityRows(
      [entry({ kind: 'restarted', generation: 4 }), entry({ kind: 'activated' })],
      { onlySelected: false, selectedRef: null, nameOf },
    );
    expect(rows[0].generation).toBe(4);
    expect(rows[1].generation).toBeUndefined();
  });

  it('formats atMs as a local HH:MM:SS clock', () => {
    const atMs = new Date(2026, 0, 1, 9, 5, 3).getTime();
    const { rows } = deriveActivityRows([entry({ atMs })], { onlySelected: false, selectedRef: null, nameOf });
    expect(rows[0].time).toBe('09:05:03');
  });

  it('caps rendering at the given cap, reporting how many were hidden', () => {
    const entries = Array.from({ length: 12 }, (_, i) => entry({ atMs: i }));
    const { rows, hiddenCount } = deriveActivityRows(entries, { onlySelected: false, selectedRef: null, nameOf, cap: 10 });
    expect(rows).toHaveLength(10);
    expect(hiddenCount).toBe(2);
  });

  it('defaults the cap to ACTIVITY_ROW_CAP (100) when not given', () => {
    const entries = Array.from({ length: ACTIVITY_ROW_CAP + 7 }, (_, i) => entry({ atMs: i }));
    const { rows, hiddenCount } = deriveActivityRows(entries, { onlySelected: false, selectedRef: null, nameOf });
    expect(rows).toHaveLength(ACTIVITY_ROW_CAP);
    expect(hiddenCount).toBe(7);
  });

  it('reports zero hidden when nothing was cut', () => {
    const { hiddenCount } = deriveActivityRows([entry()], { onlySelected: false, selectedRef: null, nameOf });
    expect(hiddenCount).toBe(0);
  });

  it('filters to the selected ref only when onlySelected is true and a ref is selected', () => {
    const entries = [entry({ ref: 'a:0' }), entry({ ref: 'b:0' }), entry({ ref: 'a:0' })];
    const { rows } = deriveActivityRows(entries, { onlySelected: true, selectedRef: 'a:0', nameOf });
    expect(rows).toHaveLength(2);
    expect(rows.every((r) => r.ref === 'a:0')).toBe(true);
  });

  it('is inert (shows everything) when onlySelected is true but nothing is selected', () => {
    const entries = [entry({ ref: 'a:0' }), entry({ ref: 'b:0' })];
    const { rows } = deriveActivityRows(entries, { onlySelected: true, selectedRef: null, nameOf });
    expect(rows).toHaveLength(2);
  });

  it('renders every row when onlySelected is false regardless of selection', () => {
    const entries = [entry({ ref: 'a:0' }), entry({ ref: 'b:0' })];
    const { rows } = deriveActivityRows(entries, { onlySelected: false, selectedRef: 'a:0', nameOf });
    expect(rows).toHaveLength(2);
  });

  it('returns no rows and zero hidden for an empty log', () => {
    const { rows, hiddenCount } = deriveActivityRows([], { onlySelected: false, selectedRef: null, nameOf });
    expect(rows).toEqual([]);
    expect(hiddenCount).toBe(0);
  });
});

describe('ACTIVITY_KIND_META', () => {
  it('has a distinct glyph and color token for each of the five kinds', () => {
    const kinds = ['activated', 'passivated', 'drained', 'woken', 'restarted'] as const;
    const glyphs = new Set(kinds.map((k) => ACTIVITY_KIND_META[k].glyph));
    const colors = new Set(kinds.map((k) => ACTIVITY_KIND_META[k].colorVar));
    expect(glyphs.size).toBe(kinds.length);
    expect(colors.size).toBe(kinds.length);
  });
});
