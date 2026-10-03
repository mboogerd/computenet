import { createSignal } from 'solid-js';
import { render } from 'solid-js/web';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../../src/api/types';
import { CruxesPanel } from '../../src/components/CruxesPanel';
import { DisagreementPanel } from '../../src/components/DisagreementPanel';
import { TreeView } from '../../src/components/TreeView';

vi.mock('../../src/sync/store', () => ({
  source: { ask: vi.fn(), override: vi.fn(), pause: vi.fn() },
}));

let dispose: (() => void) | undefined;
afterEach(() => {
  dispose?.();
  dispose = undefined;
  document.body.innerHTML = '';
});

function mount(ui: () => unknown): HTMLElement {
  const host = document.createElement('div');
  document.body.append(host);
  dispose = render(ui as () => never, host);
  return host;
}

/**
 * computenet-syv45: every SSE frame is a fresh GraphDto, all new objects. A
 * list that iterated those objects remounted every row per frame, so the
 * readings' argument trees flickered. Each frame below has the same refs but
 * new objects and new values: the row must survive (same element) and show
 * the new value.
 */
const framed = (c: number): GraphDto => ({
  questions: [
    {
      root: 'q',
      text: 'Do fish sleep?',
      claims: 3,
      active: true,
      cruxes: ['a'],
      framing: {
        mode: 'READINGS',
        term: 'sleep',
        positions: [
          { ref: 'p1', text: 'Do fish rest with lowered responsiveness?', credence: c, firstImpression: 0.9, neutralCredence: c, verdictsDisagree: false },
          { ref: 'p2', text: 'Do fish show REM-like activity?', credence: 0.4, firstImpression: 0.1, neutralCredence: 0.4, verdictsDisagree: false },
        ],
      },
    },
  ],
  nodes: [
    { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q', text: 'Do fish sleep?', depth: 0, status: 'FRAMED', proposer: 'question' },
    { ref: 'p1', kind: 'CLAIM', credence: c, root: 'q', text: 'Do fish rest with lowered responsiveness?', depth: 0, positionOf: 'q', status: 'EXPLORING', override: 'AUTO', proposer: 'reading', plausibility: 0.9 },
    { ref: 'p2', kind: 'CLAIM', credence: 0.4, root: 'q', text: 'Do fish show REM-like activity?', depth: 0, positionOf: 'q', status: 'EXPLORING', override: 'AUTO', proposer: 'reading', plausibility: 0.1 },
    {
      ref: 'a', kind: 'CLAIM', credence: c, root: 'q', text: 'They stop responding to stimuli at night.', depth: 1,
      status: 'EXPLORING', override: 'AUTO', proposer: 'claude', plausibility: c, sensitivity: c, spreadLow: c - 0.3, spreadHigh: c,
    },
    { ref: 'a-p1', kind: 'EDGE', credence: 0.6, root: 'q', polarity: 'SUPPORT', source: 'a', target: 'p1', strength: 0.6, depth: 1, status: 'PRUNED', override: 'AUTO' },
  ],
});

describe('rows keep their DOM across SSE frames (computenet-syv45)', () => {
  it('a reading and the argument tree under it are not remounted', () => {
    const [g, setG] = createSignal(framed(0.55));
    const host = mount(() => <TreeView graph={g()} root="q" />);
    const reading = host.querySelector('section.reading');
    const card = host.querySelector('section.reading .card, section.reading li');
    expect(reading).not.toBeNull();
    expect(card).not.toBeNull();

    setG(framed(0.75));
    expect(host.querySelector('section.reading')).toBe(reading);
    expect(host.querySelector('section.reading .card, section.reading li')).toBe(card);
    expect(reading!.textContent).toContain('75%');
  });

  it('a crux row is not remounted and shows the new value', () => {
    const [g, setG] = createSignal(framed(0.55));
    const host = mount(() => <CruxesPanel graph={g()} root="q" />);
    const row = host.querySelector('li.crux');
    expect(row?.textContent).toContain('55%');

    setG(framed(0.75));
    expect(host.querySelector('li.crux')).toBe(row);
    expect(row!.textContent).toContain('75%');
  });

  it('a "where the rules disagree" row is not remounted and shows the new spread', () => {
    const [g, setG] = createSignal(framed(0.55));
    const host = mount(() => <DisagreementPanel graph={g()} root="q" />);
    const row = host.querySelector('li.disagree');
    expect(row?.textContent).toContain('25%–55%');

    setG(framed(0.75));
    expect(host.querySelector('li.disagree')).toBe(row);
    expect(row!.textContent).toContain('45%–75%');
  });
});
