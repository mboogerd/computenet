import { createSignal, type Accessor } from 'solid-js';
import { render } from 'solid-js/web';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { GraphDto, NodeDto } from '../../src/api/types';
import { CruxesPanel } from '../../src/components/CruxesPanel';
import { DisagreementPanel } from '../../src/components/DisagreementPanel';
import { TreeView } from '../../src/components/TreeView';

vi.mock('../../src/sync/store', () => ({
  source: { ask: vi.fn(), override: vi.fn(), pause: vi.fn() },
}));

// jsdom does not implement scrollIntoView; stub it so focusInTree's call is observable.
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

let dispose: (() => void) | undefined;
afterEach(() => {
  dispose?.();
  dispose = undefined;
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

function mount(ui: () => unknown): HTMLElement {
  const host = document.createElement('div');
  document.body.append(host);
  dispose = render(ui as () => never, host);
  return host;
}

/** focusInTree defers its scroll/highlight step a microtask past ancestor
 *  expansion (ClaimCard.tsx); let it run before asserting on it. */
const flush = () => new Promise<void>((resolve) => queueMicrotask(resolve));

// q -> a (SUPPORT, link "a-q") -> b (SUPPORT, link "b-a"). "b"'s spread is
// wide enough for DisagreementPanel to list it; "a-q" is named as the
// question's one crux.
const nodes: NodeDto[] = [
  { ref: 'q', kind: 'CLAIM', credence: 0.6, root: 'q', text: 'Should we?', depth: 0, status: 'EXPLORING' },
  { ref: 'a', kind: 'CLAIM', credence: 0.55, root: 'q', text: 'It would help.', depth: 1, status: 'DONE', reason: 'SATURATED' },
  {
    ref: 'a-q', kind: 'EDGE', credence: 0.7, root: 'q', polarity: 'SUPPORT', source: 'a', target: 'q',
    strength: 0.7, sensitivity: 0.4, text: '“It would help.” is a reason for “Should we?”', depth: 1, status: 'DONE', reason: 'PRUNED',
  },
  {
    ref: 'b', kind: 'CLAIM', credence: 0.5, root: 'q', text: 'It has a real cost.', depth: 2, status: 'DONE', reason: 'SATURATED',
    spreadLow: 0.2, spreadHigh: 0.9,
  },
  {
    ref: 'b-a', kind: 'EDGE', credence: 0.6, root: 'q', polarity: 'SUPPORT', source: 'b', target: 'a',
    strength: 0.6, text: '“It has a real cost.” is a reason for “It would help.”', depth: 2, status: 'DONE', reason: 'PRUNED',
  },
];

const graph: GraphDto = {
  questions: [{ root: 'q', text: 'Should we?', claims: 3, active: false, cruxes: ['a-q'] }],
  nodes,
};

/** Mirrors app.tsx's wiring: one focus signal shared by TreeView and both panels. */
function Stage(props: { graph?: Accessor<GraphDto> }) {
  let seq = 0;
  const [focusRequest, setFocusRequest] = createSignal<{ ref: string; n: number }>();
  const focusClaim = (ref: string) => setFocusRequest({ ref, n: ++seq });
  const g = () => props.graph?.() ?? graph;
  return (
    <>
      <TreeView graph={g()} root="q" focus={focusRequest} />
      <CruxesPanel graph={g()} root="q" onFocus={focusClaim} />
      <DisagreementPanel graph={g()} root="q" onFocus={focusClaim} />
    </>
  );
}

describe('cruxes and disagreement entries focus the matching card (computenet-lmfg8)', () => {
  it('entries are real, accessibly-named buttons', () => {
    const host = mount(() => <Stage />);
    const disagree = host.querySelector('.disagree__btn[data-ref="b"]') as HTMLButtonElement;
    const crux = host.querySelector('.crux__btn[data-ref="a-q"]') as HTMLButtonElement;
    expect(disagree.tagName).toBe('BUTTON');
    expect(disagree.textContent).toContain('It has a real cost.');
    expect(crux.tagName).toBe('BUTTON');
    expect(crux.textContent).toContain('It would help.');
  });

  it('a click expands a collapsed ancestor, scrolls the claim card into view and highlights it', async () => {
    const host = mount(() => <Stage />);

    // Collapse "a", which unmounts "b"'s card.
    const chevron = [...host.querySelectorAll('button.linkish')].find(
      (b) => b.getAttribute('aria-controls') === 'children-a',
    ) as HTMLButtonElement;
    chevron.click();
    expect(chevron.getAttribute('aria-expanded')).toBe('false');
    expect(host.querySelector('[data-claim-ref="b"]')).toBeNull();

    const entry = host.querySelector('.disagree__btn[data-ref="b"]') as HTMLButtonElement;
    entry.click();
    expect(chevron.getAttribute('aria-expanded')).toBe('true'); // the ancestor re-expanded
    await flush();

    const card = host.querySelector('[data-claim-ref="b"]') as HTMLElement;
    expect(card).not.toBeNull();
    expect(card.classList.contains('is-focused')).toBe(true);
    expect(card.scrollIntoView).toHaveBeenCalled();
  });

  it('Enter and Space activate an entry, and focusing another moves the highlight', async () => {
    const host = mount(() => <Stage />);

    const disagreeBtn = host.querySelector('.disagree__btn[data-ref="b"]') as HTMLButtonElement;
    disagreeBtn.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await flush();
    const bCard = host.querySelector('[data-claim-ref="b"]') as HTMLElement;
    expect(bCard.classList.contains('is-focused')).toBe(true);

    // "a-q" is a link crux: its card is the LinkChip wrap inside "a"'s own card.
    const cruxBtn = host.querySelector('.crux__btn[data-ref="a-q"]') as HTMLButtonElement;
    cruxBtn.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
    await flush();

    expect(bCard.classList.contains('is-focused')).toBe(false);
    const linkEl = host.querySelector('[data-claim-ref="a-q"]') as HTMLElement;
    expect(linkEl).not.toBeNull();
    expect(linkEl.classList.contains('is-focused')).toBe(true);
  });

  it('a later snapshot does not re-scroll or re-expand what the user collapsed', async () => {
    const [g, setG] = createSignal<GraphDto>(graph);
    const host = mount(() => <Stage graph={g} />);

    (host.querySelector('.disagree__btn[data-ref="b"]') as HTMLButtonElement).click();
    await flush();
    const scroll = Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>;
    expect(scroll).toHaveBeenCalledTimes(1);

    // The user collapses "a" again, then a live snapshot (all new objects) arrives.
    const chevron = [...host.querySelectorAll('button.linkish')].find(
      (b) => b.getAttribute('aria-controls') === 'children-a',
    ) as HTMLButtonElement;
    chevron.click();
    setG({ ...graph, nodes: graph.nodes.map((n) => (n.ref === 'q' ? { ...n, credence: 0.61 } : { ...n })) });
    await flush();

    expect(chevron.getAttribute('aria-expanded')).toBe('false');
    expect(scroll).toHaveBeenCalledTimes(1);
  });
});
