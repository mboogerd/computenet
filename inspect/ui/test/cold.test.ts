import { describe, expect, it } from 'vitest';
import type { GraphSummary } from '../src/api/types';
import { COLD_NOTICE, COLD_TAG, coldGraphCount, formatColdSkipHint, isGraphCold } from '../src/nav/cold';

function graph(id: string, lifecycle: 'hot' | 'cold'): GraphSummary {
  return {
    id,
    name: null,
    cells: 2,
    hosts: 1,
    nets: 1,
    health: { deadLetters: 0, parked: 0, restarts: 0 },
    lifecycle,
  };
}

const list: readonly GraphSummary[] = [graph('g-a', 'hot'), graph('g-b', 'cold')];

/** M5-COLD ticket Implement §2-3, the pure half — the predicates the cold
 *  screen, the card dimming and the search hint are all derived from. */
describe('isGraphCold', () => {
  it('is true only for a graph the loaded list reports as cold', () => {
    expect(isGraphCold('g-b', list)).toBe(true);
    expect(isGraphCold('g-a', list)).toBe(false);
  });

  it('is false for a null id — Home is not a cold graph', () => {
    expect(isGraphCold(null, list)).toBe(false);
  });

  /** The conservative default: not knowing is not the same as knowing it is
   *  parked, and guessing cold would ghost a live graph and put a wake button
   *  in front of it. */
  it('is false for an id the list does not contain, and for an empty list', () => {
    expect(isGraphCold('g-gone', list)).toBe(false);
    expect(isGraphCold('g-b', [])).toBe(false);
  });
});

describe('coldGraphCount', () => {
  it('counts the cold graphs in the list', () => {
    expect(coldGraphCount(list)).toBe(1);
    expect(coldGraphCount([])).toBe(0);
    expect(coldGraphCount([graph('g-a', 'hot')])).toBe(0);
  });
});

describe('formatColdSkipHint', () => {
  it('is null when nothing was skipped, so the hint only appears when actionable', () => {
    expect(formatColdSkipHint(null)).toBeNull();
    expect(formatColdSkipHint({ cellsQueried: 18, coldSkipped: 0 })).toBeNull();
  });

  it('names the remedy, not just the fact', () => {
    expect(formatColdSkipHint({ cellsQueried: 18, coldSkipped: 2 })).toBe(
      '2 cold cells skipped — wake their graph to include',
    );
  });

  /** `SearchCost.coldSkipped` counts cells (contract + server `DataSearch`),
   *  so the hint says cells. Reporting them as graphs would be a number the UI
   *  invented. */
  it('says cells, singular when one — the field counts cells, not graphs', () => {
    expect(formatColdSkipHint({ cellsQueried: 0, coldSkipped: 1 })).toBe(
      '1 cold cell skipped — wake their graph to include',
    );
  });
});

describe('cold copy', () => {
  it('says state is unavailable, never previewing it (ticket Exclusions: no fake preview)', () => {
    expect(COLD_NOTICE).toContain('unavailable without waking');
    expect(COLD_NOTICE).not.toMatch(/checkpoint|last known|cached/i);
  });

  it('has a single ❄ tag every cold surface shares', () => {
    expect(COLD_TAG).toBe('❄');
  });
});
