import { describe, expect, it } from 'vitest';
import type { SearchHit } from '../src/api/types';
import { formatSearchCost, isNoticeHit, isSearchModeEnabled, isSubmitMode, SEARCH_MODES } from '../src/nav/search';

/** M4-FE ticket Tests: "Vitest: ... search-mode gating." M5-SEARCH enables
 *  `data` and makes it the one submit-triggered mode. */
describe('SEARCH_MODES / isSearchModeEnabled', () => {
  it('lists exactly name, problems, data — in that order', () => {
    expect(SEARCH_MODES.map((m) => m.mode)).toEqual(['name', 'problems', 'data']);
  });

  it('all three modes are enabled now that data has a server behind it', () => {
    expect(isSearchModeEnabled('name')).toBe(true);
    expect(isSearchModeEnabled('problems')).toBe(true);
    expect(isSearchModeEnabled('data')).toBe(true);
  });

  it('no mode carries a disabledReason any more', () => {
    for (const m of SEARCH_MODES) expect(m.disabledReason).toBeUndefined();
  });
});

/** M5-SEARCH ticket Implement §2 ("as-you-submit (Enter, not per-keystroke)")
 *  and Exclusions ("No per-keystroke querying") — the whole point being that a
 *  data query reads real cell state, so it must not fire on every letter. */
describe('isSubmitMode', () => {
  it('is true for data only', () => {
    expect(isSubmitMode('data')).toBe(true);
    expect(isSubmitMode('name')).toBe(false);
    expect(isSubmitMode('problems')).toBe(false);
  });
});

/** The server's closing notice (`DataSearch.NOTICE_GRAPH`) — an empty `graph`,
 *  which no real component id can be. */
describe('isNoticeHit', () => {
  const hit = (graph: string): SearchHit => ({ graph, ref: null, label: 'x', detail: 'y' });

  it('recognizes the empty-graph notice row', () => {
    expect(isNoticeHit(hit(''))).toBe(true);
  });

  it('leaves a real hit navigable', () => {
    expect(isNoticeHit(hit('g-016eda8f-96de-40e1-a04c-f997395ade62'))).toBe(false);
  });
});

/** M5-SEARCH ticket Implement §2: "render the cost line under the results
 *  ('queried N cells · M cold skipped') — the cost being visible is a product
 *  requirement, not decoration." */
describe('formatSearchCost', () => {
  it('renders the contract shape', () => {
    expect(formatSearchCost({ cellsQueried: 4, coldSkipped: 2 })).toBe('queried 4 cells · 2 cold skipped');
  });

  it('counts one cell in English', () => {
    expect(formatSearchCost({ cellsQueried: 1, coldSkipped: 0 })).toBe('queried 1 cell · 0 cold skipped');
  });

  it('still renders a zero-cost search rather than hiding it', () => {
    expect(formatSearchCost({ cellsQueried: 0, coldSkipped: 0 })).toBe('queried 0 cells · 0 cold skipped');
  });

  it('is null for the modes that have no cost (name/problems send cost: null)', () => {
    expect(formatSearchCost(null)).toBeNull();
  });
});
