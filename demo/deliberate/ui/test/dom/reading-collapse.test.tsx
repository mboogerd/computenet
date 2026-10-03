import { createSignal } from 'solid-js';
import { render } from 'solid-js/web';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../../src/api/types';
import { DisagreementPanel } from '../../src/components/DisagreementPanel';
import { TreeView } from '../../src/components/TreeView';

vi.mock('../../src/sync/store', () => ({
  source: { ask: vi.fn(), override: vi.fn(), pause: vi.fn() },
}));

beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

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

const flush = () => new Promise<void>((resolve) => queueMicrotask(resolve));

// A framed question with two readings, each with one argument; "a2" is wide
// enough for the disagreement panel to list it.
const graph: GraphDto = {
  questions: [
    {
      root: 'q', text: 'Do fish sleep?', claims: 4, active: false,
      framing: {
        mode: 'READINGS', term: 'sleep',
        positions: [
          { ref: 'p1', text: 'Do fish rest with lowered responsiveness?', credence: 0.6, firstImpression: 0.9, neutralCredence: 0.6, verdictsDisagree: false },
          { ref: 'p2', text: 'Do fish show REM-like activity?', credence: 0.4, firstImpression: 0.1, neutralCredence: 0.4, verdictsDisagree: false },
        ],
      },
    },
  ],
  nodes: [
    { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q', text: 'Do fish sleep?', depth: 0, status: 'FRAMED', proposer: 'question' },
    { ref: 'p1', kind: 'CLAIM', credence: 0.6, root: 'q', text: 'Do fish rest with lowered responsiveness?', depth: 0, positionOf: 'q', status: 'SATURATED', override: 'AUTO', proposer: 'reading' },
    { ref: 'p2', kind: 'CLAIM', credence: 0.4, root: 'q', text: 'Do fish show REM-like activity?', depth: 0, positionOf: 'q', status: 'SATURATED', override: 'AUTO', proposer: 'reading' },
    { ref: 'a1', kind: 'CLAIM', credence: 0.7, root: 'q', text: 'They stop responding at night.', depth: 1, status: 'SATURATED', proposer: 'claude' },
    { ref: 'a1-p1', kind: 'EDGE', credence: 0.6, root: 'q', polarity: 'SUPPORT', source: 'a1', target: 'p1', strength: 0.6, depth: 1, status: 'PRUNED' },
    { ref: 'a2', kind: 'CLAIM', credence: 0.5, root: 'q', text: 'Zebrafish show a REM-like stage.', depth: 1, status: 'SATURATED', proposer: 'codex', spreadLow: 0.2, spreadHigh: 0.8 },
    { ref: 'a2-p2', kind: 'EDGE', credence: 0.6, root: 'q', polarity: 'ATTACK', source: 'a2', target: 'p2', strength: 0.6, depth: 1, status: 'PRUNED' },
  ],
};

const toggleFor = (host: HTMLElement, reading: string) =>
  host.querySelector(`button[aria-label$="arguments for ${reading}"]`) as HTMLButtonElement;
const argsOf = (host: HTMLElement, reading: string) =>
  [...host.querySelectorAll('section.reading')].find((s) => s.textContent?.includes(reading))?.querySelector('ul.tree') ?? null;

describe("collapsing a reading's arguments (computenet-urmh0)", () => {
  it('hides and shows only that reading’s arguments; its heading and gauge stay', () => {
    const host = mount(() => <TreeView graph={graph} root="q" />);
    const r1 = 'Do fish rest with lowered responsiveness?';
    const r2 = 'Do fish show REM-like activity?';
    const t1 = toggleFor(host, r1);
    expect(t1.getAttribute('aria-expanded')).toBe('true');
    expect(t1.textContent).toContain('1 pro · 0 con');
    expect(t1.getAttribute('aria-controls')).toBe(argsOf(host, r1)?.id);

    t1.click();
    expect(t1.getAttribute('aria-expanded')).toBe('false');
    expect(t1.getAttribute('aria-label')).toBe(`Show arguments for ${r1}`);
    expect(argsOf(host, r1)).toBeNull();
    expect(host.textContent).toContain(r1);
    expect(argsOf(host, r2)).not.toBeNull();
    expect(toggleFor(host, r2).getAttribute('aria-expanded')).toBe('true');

    t1.click();
    expect(argsOf(host, r1)).not.toBeNull();
    expect(argsOf(host, r1)!.textContent).toContain('They stop responding at night.');
  });

  it('focusing a claim under a collapsed reading expands that reading first', async () => {
    const host = mount(() => {
      const [focus, setFocus] = createSignal<{ ref: string; n: number }>();
      let n = 0;
      return (
        <>
          <TreeView graph={graph} root="q" focus={focus} />
          <DisagreementPanel graph={graph} root="q" onFocus={(ref) => setFocus({ ref, n: ++n })} />
        </>
      );
    });
    const r2 = 'Do fish show REM-like activity?';
    toggleFor(host, r2).click();
    expect(argsOf(host, r2)).toBeNull();

    (host.querySelector('.disagree__btn[data-ref="a2"]') as HTMLButtonElement).click();
    await flush();
    expect(argsOf(host, r2)).not.toBeNull();
    expect(toggleFor(host, r2).getAttribute('aria-expanded')).toBe('true');
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });
});
