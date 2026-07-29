/** @vitest-environment jsdom */
import { fireEvent, render, waitFor } from '@solidjs/testing-library';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import Canvas from '../../src/components/Canvas';
import ToggleBar from '../../src/components/ToggleBar';
import { onStateSummary } from '../../src/solid/detail';
import { setSelection } from '../../src/solid/selection';
import { showErrors, showFlow, showHosts, showNet, showState } from '../../src/solid/toggles';
import { CAND_SKILLS_REF, resetAppState, startApp } from './harness';

/** ToggleBar.tsx: each of the five checkboxes flips its own
 *  `solid/toggles.ts` signal (10-target-v3.md "the v3 model"), and — with
 *  the canvas mounted alongside it, as in the real app layout — the two
 *  overlays the ticket names explicitly (Errors, State) appear and disappear
 *  with their toggle. `MATCHES_REF` is the one ref `fixtures/errors.json`
 *  carries a dead letter and a restart for (2 total), so it is the one that
 *  gets a red `.node-error-badge`. */
describe('ToggleBar', () => {
  beforeAll(startApp);
  beforeEach(resetAppState);

  it('flips each toggle signal independently, in either direction', () => {
    const { getByLabelText } = render(() => <ToggleBar />);
    const cases: { label: string; get: () => boolean }[] = [
      { label: 'Process hosts', get: showHosts },
      { label: 'Network hosts', get: showNet },
      { label: 'Flow', get: showFlow },
      { label: 'Errors', get: showErrors },
      { label: 'State', get: showState },
    ];

    for (const { label, get } of cases) {
      expect(get()).toBe(false);
      const checkbox = getByLabelText(label) as HTMLInputElement;
      expect(checkbox.checked).toBe(false);

      fireEvent.click(checkbox);
      expect(get()).toBe(true);
      expect(checkbox.checked).toBe(true);

      fireEvent.click(checkbox);
      expect(get()).toBe(false);
      expect(checkbox.checked).toBe(false);
    }
  });

  it('Errors toggle: shows the red badge on an erring cell, and removes it when switched off', async () => {
    const { getByLabelText, container } = render(() => (
      <>
        <ToggleBar />
        <Canvas />
      </>
    ));
    await waitFor(() => expect(container.querySelectorAll('.node-card').length).toBe(16));
    expect(container.querySelector('.node-error-badge')).toBeNull();

    fireEvent.click(getByLabelText('Errors'));
    await waitFor(() => {
      const badge = container.querySelector('.node-error-badge');
      expect(badge).toBeTruthy();
      expect(badge!.textContent).toBe('2'); // 1 dead letter + 1 restart, fixtures/errors.json
    });

    fireEvent.click(getByLabelText('Errors'));
    await waitFor(() => expect(container.querySelector('.node-error-badge')).toBeNull());
  });

  it('State toggle: shows the per-cell chip once a state.summary has arrived for the observed cell, and removes it when switched off', async () => {
    const { getByLabelText, container, getByText } = render(() => (
      <>
        <ToggleBar />
        <Canvas />
      </>
    ));
    await waitFor(() => expect(container.querySelectorAll('.node-card').length).toBe(16));

    // Select the cell (the real observe/select path — `solid/detail.ts`),
    // then feed the one `state.summary` frame the chip actually reads from
    // (`stateSummaries`, fed only by `onStateSummary` — never by the plain
    // `GET .../state` response `cellState` holds instead). This is the same
    // terminal handler `solid/state.ts`'s SSE switch calls for a real
    // `state.summary` frame; see harness.tsx's EventSource-stub doc comment.
    const card = getByText('candSkills').closest('.node-card') as HTMLElement;
    fireEvent.click(card);
    await waitFor(() => expect(card.classList.contains('is-selected')).toBe(true));
    onStateSummary({ ref: CAND_SKILLS_REF, cardinality: '2', frontier: { source: 'a3f2c9de1b', counter: 412 }, staleMs: 40 });

    expect(container.querySelector('.node-state-chip')).toBeNull();

    fireEvent.click(getByLabelText('State'));
    await waitFor(() => {
      const chip = container.querySelector('.node-state-chip');
      expect(chip).toBeTruthy();
      expect(chip!.textContent).toContain('40ms');
    });

    fireEvent.click(getByLabelText('State'));
    await waitFor(() => expect(container.querySelector('.node-state-chip')).toBeNull());
  });
});
