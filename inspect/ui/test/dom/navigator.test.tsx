/** @vitest-environment jsdom */
import { fireEvent, render, waitFor, within } from '@solidjs/testing-library';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import Navigator from '../../src/components/Navigator';
import { currentGraphId, screen } from '../../src/solid/routeState';
import { resetAppState, startApp } from './harness';

/** Navigator.tsx: the Home screen. `fixtures/graphs.json` names two graph
 *  summaries (one of them cold-less/plain "hot", the other with a null
 *  name); `fixtures/topology.json`'s 16 nodes all carry the SAME
 *  `graph`/component id, so the constellation grid — grouped from the
 *  topology store, not the graph list — renders exactly one card. This
 *  suite pins the two historical M4-EVAL defects named in the ticket
 *  (`Navigator.tsx:52-56`/`:225-227`): a constellation card whose only
 *  content was a decorative, `aria-hidden` SVG exposed no accessible name at
 *  all, and a graph card's tooltip-doubling-as-name led with the wrong
 *  thing ("0 restarts"). `getByRole(..., { name })` below resolves the real
 *  computed accessible name (the same algorithm a screen reader uses) — if
 *  either defect regressed, these queries would fail to find the element,
 *  not just render "something". */
describe('Navigator', () => {
  beforeAll(startApp);
  beforeEach(resetAppState);

  async function renderReady() {
    const result = render(() => <Navigator />);
    await waitFor(() => {
      expect(result.container.querySelectorAll('.graph-card').length).toBe(2);
    });
    return result;
  }

  it('renders one graph card per GraphList summary, each with a non-empty accessible name', async () => {
    const { container } = await renderReady();
    const cards = container.querySelectorAll('.graph-card');
    expect(cards.length).toBe(2); // fixtures/graphs.json

    for (const card of cards) {
      expect((card.textContent ?? '').trim().length).toBeGreaterThan(0);
    }

    const rail = within(container.querySelector('.graph-cards') as HTMLElement);
    // `skillmatch`'s card: named, plain hot lifecycle, health pills for its
    // dead-letter/parked/restart counts.
    const skillmatchCard = rail.getByRole('button', { name: /skillmatch/ });
    expect(skillmatchCard.textContent).toContain('16 cells');
    expect(skillmatchCard.querySelectorAll('.health-pill').length).toBeGreaterThan(0);
  });

  it('renders one constellation card per component, with a non-empty accessible name (M4-EVAL)', async () => {
    const { container } = await renderReady();
    const cards = container.querySelectorAll('.constellation-card');
    expect(cards.length).toBe(1); // one component across all 16 topology nodes

    for (const card of cards) {
      expect(card.getAttribute('aria-label')?.trim()).toBeTruthy();
    }

    // Pins the M4-EVAL regression directly: this query only succeeds if the
    // card's computed accessible name is exactly this non-empty string.
    const constellation = within(container.querySelector('.constellation-grid') as HTMLElement);
    expect(constellation.getByRole('button', { name: 'Open skillmatch' })).toBeTruthy();
  });

  it('clicking a constellation card enters that graph', async () => {
    const { container } = await renderReady();
    expect(screen()).toBe('home');

    const card = container.querySelector('.constellation-card') as HTMLElement;
    fireEvent.click(card);

    expect(screen()).toBe('graph');
    expect(currentGraphId()).toBe('g-016eda8f-96de-40e1-a04c-f997395ade62');
  });

  it('clicking a graph card enters that graph too', async () => {
    const { container } = await renderReady();
    const card = container.querySelector('.graph-card') as HTMLElement;
    fireEvent.click(card);

    expect(screen()).toBe('graph');
    expect(currentGraphId()).toBe('g-016eda8f-96de-40e1-a04c-f997395ade62'); // the fixture's first card
  });
});
