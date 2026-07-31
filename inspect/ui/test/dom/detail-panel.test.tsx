/** @vitest-environment jsdom */
import { fireEvent, render, waitFor } from '@solidjs/testing-library';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { CellState } from '../../src/api/types';
import DetailPanel from '../../src/components/DetailPanel';
import { setSelection } from '../../src/solid/selection';
import cellStateUnavailable from '../../fixtures/cell-state-unavailable.json';
import cellStatePage from '../../fixtures/cell-state-page.json';
import cellStatePageCheckpoint from '../../fixtures/cell-state-page-checkpoint.json';
import { CAND_SKILLS_REF, MATCHES_REF, resetAppState, setStateFixture, setStateResponder, startApp } from './harness';

const PAGE_REF = cellStatePage.ref;
const CHECKPOINT_REF = cellStatePageCheckpoint.ref;

/** DetailPanel.tsx: all four stacked sections (10-target-v3.md "Selecting a
 *  node shows all of its properties"), plus the three empty states — no
 *  selection, no errors for the selected cell, and a `kind: 'unavailable'`
 *  state response. `fixtures/cell-detail.json` is the generic descriptor the
 *  harness's fetch router serves for any `GET /cell/{ref}` (see harness.tsx)
 *  — it is `candSkills`'s own detail, so descriptor-field assertions below
 *  select `CAND_SKILLS_REF` to match it. */
describe('DetailPanel', () => {
  beforeAll(startApp);
  beforeEach(resetAppState);

  it('shows "Select a node to inspect it." when nothing is selected', () => {
    const { getByText } = render(() => <DetailPanel />);
    expect(getByText('Select a node to inspect it.')).toBeTruthy();
  });

  it('renders all four sections with descriptor data, and "No local errors" for a clean cell', async () => {
    const { container, getByText } = render(() => <DetailPanel />);
    setSelection(CAND_SKILLS_REF);

    await waitFor(() => {
      expect(getByText('candSkills')).toBeTruthy(); // detail-panel__name, from cellDetail()
    });

    const headings = [...container.querySelectorAll('.detail-section__title')].map((h) => h.textContent);
    expect(headings).toEqual(['Descriptor & placement', 'State', 'Flow', 'Errors']);

    // Descriptor & placement: class/color/ports/host/net/generation/lifecycle/links
    // (fixtures/cell-detail.json). `deltaInlet` also appears in the Flow
    // section's per-port table, so the ports list is scoped explicitly.
    expect(getByText('SetCell')).toBeTruthy(); // shortType('civictech.cell.data.SetCell')
    expect(container.querySelector('.ports-list')?.textContent).toContain('deltaInlet');
    expect(getByText('skillmatch')).toBeTruthy(); // process host
    expect(getByText('local')).toBeTruthy(); // network host
    expect(getByText('HOT')).toBeTruthy(); // lifecycle
    expect(container.textContent).toContain('in 0 · out 3 · taps 1'); // Links

    // State: the default `/cell/{ref}/state` fixture (cell-state-table.json)
    // is this same ref's own state.
    await waitFor(() => {
      expect(container.textContent).toContain('Kotlin');
      expect(container.textContent).toContain('TypeScript');
    });

    // F-5 (10-design-notes.md Binding constraints 4): the footnote is present.
    expect(container.textContent).toContain('per-cell consistent — cross-panel alignment not guaranteed');

    // Errors: candSkills has no entries in fixtures/errors.json (only
    // `matches`/MATCHES_REF does) — the empty state, not a blank section.
    expect(getByText('No local errors')).toBeTruthy();
  });

  it('shows the state-unavailable line, not a crash or a blank, for a kind: "unavailable" response', async () => {
    setStateFixture(MATCHES_REF, cellStateUnavailable);
    const { getByText } = render(() => <DetailPanel />);
    setSelection(MATCHES_REF);

    await waitFor(() => {
      expect(getByText('State unavailable for this cell.')).toBeTruthy();
    });
  });

  it('a "migrating" unreadable reason renders its own sentence, not the generic one', async () => {
    const migrating: CellState = { ...(cellStateUnavailable as CellState), unreadable: 'migrating' };
    setStateFixture(MATCHES_REF, migrating);
    const { container } = render(() => <DetailPanel />);
    setSelection(MATCHES_REF);

    await waitFor(() => {
      expect(container.textContent).toMatch(/repartition flip/i);
    });
    expect(container.textContent).not.toContain('State unavailable for this cell.');
  });
});

/** V1C-FE ticket — the paged state view, provenance/elision rendering, and
 *  the cold selection now reading real state. `fixtures/cell-state-page.json`
 *  / `cell-state-page-checkpoint.json` are the two checked-in fixtures this
 *  ticket adds (shared with `V1C-BE`'s `FixtureContractTest` decoder map, per
 *  the wave's cross-ticket coupling); every other shape below is an inline
 *  sample, per `00-orchestration.md` §"Standing rules" (a third fixture would
 *  be a cross-ticket change neither branch may make unilaterally). */
describe('DetailPanel — paged state (V1C-FE)', () => {
  beforeAll(startApp);
  beforeEach(resetAppState);

  const page2: CellState = {
    ref: PAGE_REF,
    frontier: null,
    kind: 'page',
    value: { $table: { columns: ['skill'], rows: [['Go'], ['Scala']] } },
    staleMs: 0,
    provenance: 'live',
    page: { cursor: null, limit: 3, entries: 2, exclusivesElided: 0, walkStable: true },
    unreadable: null,
  };

  it('walks a big cell page by page, accumulating entries into one rendered value', async () => {
    setStateResponder(PAGE_REF, (params) =>
      params.get('cursor') === 'p-7f3a1' ? { body: page2 } : { body: cellStatePage },
    );

    const { container, getByText, queryByText } = render(() => <DetailPanel />);
    setSelection(PAGE_REF);

    await waitFor(() => expect(container.textContent).toContain('Rust'));
    expect(container.textContent).toContain('3 entries loaded — more available');
    expect(queryByText('Scala')).toBeNull();

    fireEvent.click(getByText('Load next page'));

    await waitFor(() => expect(container.textContent).toContain('Scala'));
    expect(container.textContent).toContain('Kotlin'); // page 1's entries are still there — accumulated, not replaced
    expect(container.textContent).toContain('5 entries — complete');
    expect(container.querySelector('.state-page__more')).toBeNull(); // walk complete — no more affordance
  });

  it('fetches exactly one page per click — no automatic multi-page fetching', async () => {
    let calls = 0;
    setStateResponder(PAGE_REF, (params) => {
      if (params.get('cursor') === 'p-7f3a1') {
        calls += 1;
        return { body: page2 };
      }
      return { body: cellStatePage };
    });

    const { getByText } = render(() => <DetailPanel />);
    setSelection(PAGE_REF);
    await waitFor(() => expect(getByText('Load next page')).toBeTruthy());

    fireEvent.click(getByText('Load next page'));
    await waitFor(() => expect(calls).toBe(1));

    // give any (incorrect) background/auto-advance behavior a chance to fire
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(calls).toBe(1);
  });

  it('abandoning a walk mid-flight (selecting another cell) discards the in-flight page response', async () => {
    let resolvePending: ((outcome: { body: unknown }) => void) | undefined;
    setStateResponder(PAGE_REF, (params) => {
      if (params.get('cursor') === 'p-7f3a1') {
        return new Promise((resolve) => {
          resolvePending = resolve;
        });
      }
      return { body: cellStatePage };
    });

    const { container, getByText } = render(() => <DetailPanel />);
    setSelection(PAGE_REF);
    await waitFor(() => expect(getByText('Load next page')).toBeTruthy());

    fireEvent.click(getByText('Load next page'));
    await waitFor(() => expect(resolvePending).toBeDefined());

    setSelection(CAND_SKILLS_REF);
    await waitFor(() => expect(container.textContent).toContain('Kotlin'));

    resolvePending!({ body: page2 });
    await new Promise((resolve) => setTimeout(resolve, 0));

    // the abandoned walk's second page must never surface once a different
    // cell is selected
    expect(container.textContent).not.toContain('Scala');
  });

  it('a 410 stale cursor restarts the walk from page 1, silently — no error, and stops looping on a second 410', async () => {
    const restarted: CellState = {
      ...(cellStatePage as unknown as CellState),
      page: { ...(cellStatePage as unknown as CellState).page!, cursor: null },
    };
    let staleServed = false;
    let noCursorCalls = 0;
    setStateResponder(PAGE_REF, (params) => {
      const cursor = params.get('cursor');
      if (cursor === null) {
        // 1st call: the initial selection fetch (page 1, cursor: 'p-7f3a1').
        // 2nd call: the automatic 410 restart's OWN page-1 re-fetch, this
        // time landing complete (cursor: null).
        noCursorCalls += 1;
        return { body: noCursorCalls === 1 ? cellStatePage : restarted };
      }
      if (cursor === 'p-7f3a1' && !staleServed) {
        staleServed = true;
        return { status: 410, body: { error: 'unknown cursor' } };
      }
      return { body: restarted };
    });

    const { container, getByText } = render(() => <DetailPanel />);
    setSelection(PAGE_REF);
    await waitFor(() => expect(getByText('Load next page')).toBeTruthy());

    fireEvent.click(getByText('Load next page'));

    await waitFor(() => expect(container.textContent).toContain('the walk restarted from the first page'));
    expect(container.querySelector('.detail-section__status--error')).toBeNull(); // no error surfaced
    expect(container.querySelector('.state-page__more')).toBeNull(); // the restarted walk is complete (cursor: null)
  });

  it('renders "live" provenance as a quiet marker', async () => {
    setStateFixture(PAGE_REF, cellStatePage);
    const { container } = render(() => <DetailPanel />);
    setSelection(PAGE_REF);

    await waitFor(() => expect(container.textContent).toContain('Kotlin'));
    const label = container.querySelector('.state-provenance');
    expect(label?.textContent).toBe('live');
    expect(label?.classList.contains('state-provenance--stale')).toBe(false);
  });

  it('renders "checkpoint" provenance labelled as stale, at the value', async () => {
    setStateFixture(CHECKPOINT_REF, cellStatePageCheckpoint);
    const { container } = render(() => <DetailPanel />);
    setSelection(CHECKPOINT_REF);

    await waitFor(() => expect(container.textContent).toContain('Kotlin'));
    const label = container.querySelector('.state-provenance');
    expect(label?.textContent).toMatch(/checkpoint/i);
    expect(label?.textContent).toMatch(/not as of now/i);
    expect(label?.classList.contains('state-provenance--stale')).toBe(true);
    // "snapshot"/"page" pins staleMs to 0 — must not be rendered as freshness
    expect(container.textContent).not.toMatch(/0ms stale/);
  });

  it('renders "liveSuspended" provenance neutrally — visually distinct from "checkpoint", never a warning', async () => {
    const suspended: CellState = {
      ref: MATCHES_REF,
      frontier: null,
      kind: 'snapshot',
      value: { $table: { columns: ['skill'], rows: [['Kotlin']] } },
      staleMs: 0,
      provenance: 'liveSuspended',
      page: null,
      unreadable: null,
    };
    setStateFixture(MATCHES_REF, suspended);
    const { container } = render(() => <DetailPanel />);
    setSelection(MATCHES_REF);

    await waitFor(() => expect(container.textContent).toContain('Kotlin'));
    const label = container.querySelector('.state-provenance');
    expect(label?.textContent).toMatch(/suspended/i);
    expect(label?.textContent).not.toMatch(/warning|degraded|caution/i);
    expect(label?.classList.contains('state-provenance--stale')).toBe(false);
  });

  it('renders a nonzero exclusivesElided as an ownership fact, never conflated with truncation', async () => {
    const withExclusive: CellState = {
      ref: MATCHES_REF,
      frontier: null,
      kind: 'page',
      value: { $table: { columns: ['skill'], rows: [['Kotlin']] } },
      staleMs: 0,
      provenance: 'live',
      page: { cursor: null, limit: 1, entries: 1, exclusivesElided: 2, walkStable: true },
      unreadable: null,
    };
    setStateFixture(MATCHES_REF, withExclusive);
    const { container } = render(() => <DetailPanel />);
    setSelection(MATCHES_REF);

    await waitFor(() => expect(container.textContent).toContain('Kotlin'));
    const label = container.querySelector('.state-page__exclusives');
    expect(label?.textContent).toContain('2 entries hold exclusive values');
    expect(label?.textContent).not.toMatch(/showing \d+ of \d+/);
    expect(label?.textContent).not.toMatch(/load more/i);
  });

  it('row-flash is suppressed for kind: "page" — an appended page never renders as "added rows"', async () => {
    setStateResponder(PAGE_REF, (params) =>
      params.get('cursor') === 'p-7f3a1' ? { body: page2 } : { body: cellStatePage },
    );

    const { container, getByText } = render(() => <DetailPanel />);
    setSelection(PAGE_REF);
    await waitFor(() => expect(container.textContent).toContain('Rust'));

    fireEvent.click(getByText('Load next page'));
    await waitFor(() => expect(container.textContent).toContain('Scala'));

    expect(container.querySelectorAll('[data-flash]').length).toBe(0);
  });
});
