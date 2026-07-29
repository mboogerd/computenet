/** @vitest-environment jsdom */
import { render, waitFor } from '@solidjs/testing-library';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import DetailPanel from '../../src/components/DetailPanel';
import { setSelection } from '../../src/solid/selection';
import cellStateUnavailable from '../../fixtures/cell-state-unavailable.json';
import { CAND_SKILLS_REF, MATCHES_REF, resetAppState, setStateFixture, startApp } from './harness';

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
});
