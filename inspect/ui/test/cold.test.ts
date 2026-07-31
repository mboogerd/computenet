import { describe, expect, it } from 'vitest';
import type { GraphSummary } from '../src/api/types';
import { COLD_BANNER_NOTICE, COLD_STATE_NOTICE, COLD_TAG, coldGraphCount, formatColdSkipHint, isGraphCold } from '../src/nav/cold';

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

  /** V1C-BE narrows `coldSkipped` to "held for a migration flip only" (and
   *  makes suspended/drained cells directly searchable, so they stop being
   *  counted here at all) — waking does nothing for a held cell. But a
   *  browser cannot tell whether it is talking to a pre- or post-V1C-BE
   *  server, and on an OLDER server `coldSkipped` still counts parked cells
   *  too, for which waking WOULD help. So the hint states the fact and drops
   *  the remedy claim rather than naming one ("wake to include") that is a
   *  dead end under the narrowed meaning. */
  it('states the fact without promising a remedy that may not work for every skipped cell', () => {
    expect(formatColdSkipHint({ cellsQueried: 18, coldSkipped: 2 })).toBe(
      '2 cold cells skipped — parked or held cells are not searched',
    );
    expect(formatColdSkipHint({ cellsQueried: 18, coldSkipped: 2 })).not.toMatch(/wake/i);
  });

  /** `SearchCost.coldSkipped` counts cells (contract + server `DataSearch`),
   *  so the hint says cells. Reporting them as graphs would be a number the UI
   *  invented. */
  it('says cells, singular when one — the field counts cells, not graphs', () => {
    expect(formatColdSkipHint({ cellsQueried: 0, coldSkipped: 1 })).toBe(
      '1 cold cell skipped — parked or held cells are not searched',
    );
  });
});

describe('cold copy', () => {
  /** M5-COLD's no-fake-preview intent, restated for V1C-FE: a truthful
   *  "checkpoint"/"suspended" label naming where the value came from is not a
   *  stale preview dressed up as current — that distinction is drawn at the
   *  value itself (`util/statePresentation.ts`'s `provenanceLabel`). What
   *  would still be dishonest, and what these assertions still catch, is the
   *  notice claiming the parked read IS current/live/up to date. */
  it('the banner says the graph is parked and does not claim its state is current', () => {
    expect(COLD_BANNER_NOTICE).toMatch(/parked/i);
    expect(COLD_BANNER_NOTICE).not.toMatch(/current|up to date|as of now|live value/i);
  });

  it('the state-line notice says the same — parked, not a claim of currency', () => {
    expect(COLD_STATE_NOTICE).toMatch(/parked/i);
    expect(COLD_STATE_NOTICE).not.toMatch(/current|up to date|as of now|live value/i);
  });

  it('has a single ❄ tag every cold surface shares', () => {
    expect(COLD_TAG).toBe('❄');
  });
});
