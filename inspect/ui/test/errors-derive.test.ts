import { describe, expect, it } from 'vitest';
import type { ParkedEntry } from '../src/api/types';
import { cellErrorBadges, deriveEdgeParkedCounts, type EdgeTarget } from '../src/util/errors';

function parked(over: Partial<ParkedEntry> = {}): ParkedEntry {
  return { ref: 'a:0', port: 'left', count: 5, oldestMs: 1000, ...over };
}

function edge(id: string, toRef: string, toPort: string): EdgeTarget {
  return { id, to: { ref: toRef, port: toPort } };
}

describe('cellErrorBadges', () => {
  it('badges a cell whose combined dead-letter + restart count is nonzero', () => {
    const badges = cellErrorBadges(['a:0', 'b:0'], (ref) => (ref === 'a:0' ? 3 : 0), true);
    expect(badges.get('a:0')).toBe(3);
    expect(badges.has('b:0')).toBe(false);
  });

  it('is empty for every ref when disabled — "when off, none of it renders"', () => {
    const badges = cellErrorBadges(['a:0'], () => 9, false);
    expect(badges.size).toBe(0);
  });

  it('omits a ref whose count is exactly zero', () => {
    const badges = cellErrorBadges(['a:0'], () => 0, true);
    expect(badges.size).toBe(0);
  });
});

describe('deriveEdgeParkedCounts', () => {
  it('maps a parked (ref, port) row onto the edge terminating there', () => {
    const pills = deriveEdgeParkedCounts([parked({ ref: 'a:0', port: 'left', count: 11 })], [edge('e1', 'a:0', 'left')], true);
    expect(pills.get('e1')).toBe(11);
  });

  it('is empty when disabled — "when off, none of it renders"', () => {
    const pills = deriveEdgeParkedCounts([parked({ count: 11 })], [edge('e1', 'a:0', 'left')], false);
    expect(pills.size).toBe(0);
  });

  it('contributes nothing for a parked port with no matching edge (stale/unknown port)', () => {
    const pills = deriveEdgeParkedCounts([parked({ ref: 'a:0', port: 'ghost', count: 4 })], [edge('e1', 'a:0', 'left')], true);
    expect(pills.size).toBe(0);
  });

  it('a fan-in port (two edges sharing the same target ref+port) gets the same count on both', () => {
    const pills = deriveEdgeParkedCounts(
      [parked({ ref: 'a:0', port: 'left', count: 6 })],
      [edge('e1', 'a:0', 'left'), edge('e2', 'a:0', 'left')],
      true,
    );
    expect(pills.get('e1')).toBe(6);
    expect(pills.get('e2')).toBe(6);
  });

  it('does not confuse two different cells that happen to share a port name', () => {
    const pills = deriveEdgeParkedCounts(
      [parked({ ref: 'a:0', port: 'left', count: 5 })],
      [edge('e1', 'b:0', 'left')],
      true,
    );
    expect(pills.size).toBe(0);
  });

  it('ignores a count: 0 row rather than mapping a zero pill onto its edge', () => {
    const pills = deriveEdgeParkedCounts([parked({ ref: 'a:0', port: 'left', count: 0 })], [edge('e1', 'a:0', 'left')], true);
    expect(pills.size).toBe(0);
  });

  it('is empty for an empty parked list', () => {
    expect(deriveEdgeParkedCounts([], [edge('e1', 'a:0', 'left')], true).size).toBe(0);
  });
});
