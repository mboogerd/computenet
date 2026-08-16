/** @vitest-environment jsdom */
import { fireEvent, render, waitFor } from '@solidjs/testing-library';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import Canvas from '../../src/components/Canvas';
import { selection } from '../../src/solid/state';
import { setShowErrors } from '../../src/solid/toggles';
import { CAND_SKILLS_REF, resetAppState, startApp } from './harness';

/** Canvas.tsx, seeded from the real `fixtures/topology.json` (16 cells, 18
 *  edges — `test/fixture.test.ts` pins those counts against the real
 *  skillmatch server, so this suite reuses them rather than re-deriving).
 *  Covers the ticket's own headline example — `onSceneClick`'s background
 *  deselect (`Canvas.tsx`'s `e.currentTarget === e.target` guard) versus a
 *  card's `stopPropagation` + `setSelection` — "never verified except by
 *  hand" before this suite. FE-CANVAS landed on this branch's base, so the
 *  zoom-controls case below applies. */
describe('Canvas', () => {
  beforeAll(startApp);
  beforeEach(resetAppState);

  async function renderReady() {
    const result = render(() => <Canvas />);
    await waitFor(() => {
      expect(result.container.querySelectorAll('.node-card').length).toBe(16);
    });
    return result;
  }

  it('renders one node card per topology node and one edge element per edge', async () => {
    const { container } = await renderReady();
    expect(container.querySelectorAll('.node-card').length).toBe(16);
    // `.edge` (not `.edge-hit`, the always-on invisible hover target FE-TOOLTIPS
    // added) — one per edge, whether fused (a `<g class="edge ...">`) or plain
    // (a `<line class="edge ...">`); see `Canvas.tsx`'s `EdgeLine`.
    expect(container.querySelectorAll('.edge').length).toBe(18);
  });

  it('selects a card on click: selection() is set, the card gains is-selected and aria-pressed', async () => {
    const { getByText } = await renderReady();
    const card = getByText('candSkills').closest('.node-card') as HTMLElement;
    expect(card).toBeTruthy();

    fireEvent.click(card);

    await waitFor(() => {
      expect(selection()).toBe(CAND_SKILLS_REF);
      expect(card.classList.contains('is-selected')).toBe(true);
      expect(card.getAttribute('aria-pressed')).toBe('true');
    });
  });

  it('deselects on a background click but not on a click that bubbled from a card', async () => {
    const { container, getByText } = await renderReady();
    const card = getByText('candSkills').closest('.node-card') as HTMLElement;
    fireEvent.click(card);
    await waitFor(() => expect(selection()).toBe(CAND_SKILLS_REF));

    // A click on a card bubbles up to `.canvas__scene`, but `onCardKeyDown`'s
    // sibling `onClick` calls `e.stopPropagation()` (Canvas.tsx `node-card`
    // onClick) — so the scene's own `onSceneClick` never even runs for it.
    // Only a click that lands on the scene element ITSELF (background) must
    // deselect (`e.currentTarget === e.target`).
    const scene = container.querySelector('.canvas__scene') as HTMLElement;
    fireEvent.click(scene);

    await waitFor(() => expect(selection()).toBeNull());
  });

  it('selects a focused card on Enter', async () => {
    const { getByText } = await renderReady();
    const card = getByText('demand').closest('.node-card') as HTMLElement;
    card.focus();
    fireEvent.keyDown(card, { key: 'Enter' });

    await waitFor(() => {
      expect(card.classList.contains('is-selected')).toBe(true);
    });
  });

  describe('computenet-0994: BoundaryPolicy refusals do not light up the fault badge', () => {
    it('a cell with only a denial dead letter gets the denial badge, not the fault badge or is-erring', async () => {
      setShowErrors(true);
      const { container, getByText } = await renderReady();
      const card = getByText('qualification').closest('.node-card') as HTMLElement;
      expect(card).toBeTruthy();

      await waitFor(() => {
        expect(container.querySelector('.node-error-badge[data-kind="denial"]')).toBeTruthy();
      });

      const denialBadges = container.querySelectorAll('.node-error-badge[data-kind="denial"]');
      expect(denialBadges.length).toBe(1);
      expect(denialBadges[0].textContent).toBe('1');

      // Exactly one fault badge exists in the whole scene — `matches`'
      // (fixtures/errors.json's other row, a real OwnershipViolation). The
      // refusal on `qualification` never contributes a second one: a
      // pure-refusal graph must not look like cells are failing (SEC1-29).
      const faultBadges = container.querySelectorAll('.node-error-badge[data-kind="fault"]');
      expect(faultBadges.length).toBe(1);

      // `qualification`'s own card gets no red "erring" treatment — its only
      // dead letter is a refusal (denial != null), never a fault.
      expect(card.classList.contains('is-erring')).toBe(false);
    });
  });

  describe('zoom controls (FE-CANVAS)', () => {
    it('render with accessible names', async () => {
      const { getByLabelText } = await renderReady();
      expect(getByLabelText('Zoom out')).toBeTruthy();
      expect(getByLabelText('Zoom in')).toBeTruthy();
      expect(getByLabelText('Reset zoom to 100%')).toBeTruthy();
      expect(getByLabelText('Fit graph to screen')).toBeTruthy();
    });

    it('pressing + changes the pan wrapper transform', async () => {
      const { getByLabelText, container } = await renderReady();
      const pan = container.querySelector('.canvas__pan') as HTMLElement;
      expect(pan.style.transform).toContain('scale(1)');

      fireEvent.click(getByLabelText('Zoom in'));

      await waitFor(() => {
        expect(pan.style.transform).toContain('scale(1.2)');
      });
    });
  });
});
