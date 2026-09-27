import { createSignal } from 'solid-js';
import { render } from 'solid-js/web';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { GraphDto, QuestionDto } from '../../src/api/types';
import { OverrideControl } from '../../src/components/OverrideControl';
import { PauseControl } from '../../src/components/PauseControl';
import { QuestionList } from '../../src/components/QuestionList';
import { Toasts } from '../../src/components/Toasts';
import { TreeView } from '../../src/components/TreeView';
import { source } from '../../src/sync/store';

vi.mock('../../src/sync/store', () => ({
  source: { ask: vi.fn(), override: vi.fn(), pause: vi.fn() },
}));

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

const flush = () => new Promise((r) => setTimeout(r, 0));

const frame = (claims: number): QuestionDto[] => [
  { root: 'q0', text: 'First?', claims, active: true },
  { root: 'q1', text: 'Second?', claims: claims + 1, active: true },
];

describe('question list across SSE frames', () => {
  it('keeps each button (and keyboard focus) when a new frame brings new objects', () => {
    const [questions, setQuestions] = createSignal(frame(1));
    const host = mount(() => <QuestionList questions={questions()} selected="q0" onSelect={() => undefined} />);
    const before = host.querySelectorAll('button');
    expect(before).toHaveLength(2);
    before[1].focus();
    expect(document.activeElement).toBe(before[1]);

    setQuestions(frame(2)); // every object new, same roots
    const after = host.querySelectorAll('button');
    expect(after[0]).toBe(before[0]);
    expect(after[1]).toBe(before[1]);
    expect(document.activeElement).toBe(before[1]);
    expect(after[1].title).toContain('3 claims');

    setQuestions(frame(3).map((q) => (q.root === 'q1' ? { ...q, paused: true } : q)));
    const paused = host.querySelectorAll('button');
    expect(paused[1]).toBe(before[1]);
    expect(paused[1].title).toContain('paused');
    expect(paused[1].querySelector('.qlist__dot')?.getAttribute('aria-label')).toBe('paused');
    expect(paused[1].querySelector('.qlist__dot')?.classList.contains('is-active')).toBe(false);
  });
});

describe('failed commands say so', () => {
  it('an override that fails reverts and shows a toast', async () => {
    vi.mocked(source.override).mockRejectedValueOnce(new Error('/override failed: 503'));
    const host = mount(() => (
      <>
        <OverrideControl id="c1" value="AUTO" />
        <Toasts />
      </>
    ));
    const expand = [...host.querySelectorAll('button')].find((b) => b.textContent === 'Expand')!;
    expand.click();
    expect(expand.getAttribute('aria-pressed')).toBe('true'); // optimistic
    await flush();
    expect(expand.getAttribute('aria-pressed')).toBe('false');
    const notice = host.querySelector('.toast')!;
    expect(notice.textContent).toContain("Couldn't set Expand on this claim — /override failed: 503");
    (notice.querySelector('.toast__close') as HTMLButtonElement).click();
  });

  it('a pause that fails reverts and shows a toast', async () => {
    vi.mocked(source.pause).mockRejectedValueOnce(new Error('unknown question q9'));
    const host = mount(() => (
      <>
        <PauseControl root="q9" paused={false} />
        <Toasts />
      </>
    ));
    const button = host.querySelector('button.pause') as HTMLButtonElement;
    button.click();
    await flush();
    expect(button.textContent).toBe('Pause');
    // toasts are app-wide, so the previous test's may still be up: this one is the newest
    const toasts = () => [...host.querySelectorAll('.toast')];
    const mine = toasts().at(-1)!;
    expect(mine.textContent).toContain("Couldn't pause this question — unknown question q9");
    const count = toasts().length;
    (mine.querySelector('.toast__close') as HTMLButtonElement).click();
    expect(toasts()).toHaveLength(count - 1);
    expect(toasts().some((t) => t.textContent?.includes('q9'))).toBe(false);
  });
});

describe('link preview', () => {
  const graph: GraphDto = {
    questions: [{ root: 'q', text: 'Should we?', claims: 2, active: false }],
    nodes: [
      { ref: 'q', kind: 'CLAIM', credence: 0.6, root: 'q', text: 'Should we?', depth: 0, status: 'SATURATED' },
      { ref: 'a', kind: 'CLAIM', credence: 0.7, root: 'q', text: 'It would help.', depth: 1, status: 'SATURATED' },
      {
        ref: 'a-q', kind: 'EDGE', credence: 0.65, root: 'q', polarity: 'SUPPORT', source: 'a', target: 'q', strength: 0.72,
        text: '“It would help.” is a reason for “Should we?”', depth: 1, status: 'PRUNED',
      },
    ],
  };

  it('exists only while the chip is hovered or focused, and leads with how far the link holds', () => {
    const [g, setG] = createSignal(graph);
    const host = mount(() => <TreeView graph={g()} root="q" />);
    const chip = host.querySelector('.linkchip') as HTMLButtonElement;
    expect(chip.textContent).toContain('holds 65%');
    expect(host.querySelector('[role="tooltip"]')).toBeNull();

    chip.dispatchEvent(new MouseEvent('mouseenter'));
    const peek = host.querySelector('[role="tooltip"]')!;
    expect(peek.textContent).toContain("Jev's first impression: strong link 72%");
    expect(chip.getAttribute('aria-describedby')).toBe(peek.id);

    chip.dispatchEvent(new MouseEvent('mouseleave'));
    expect(host.querySelector('[role="tooltip"]')).toBeNull();
    expect(chip.hasAttribute('aria-describedby')).toBe(false);

    chip.dispatchEvent(new FocusEvent('focus'));
    expect(host.querySelector('[role="tooltip"]')).not.toBeNull();
    chip.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(host.querySelector('[role="tooltip"]')).toBeNull();

    chip.click();
    expect(chip.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('#link-a-q')?.classList.contains('is-open')).toBe(true);
    expect(host.querySelector('[role="tooltip"]')).toBeNull();
    chip.click();
    expect(chip.getAttribute('aria-expanded')).toBe('false');

    // a new frame (all-new objects) keeps the chip, and brings no preview with it
    setG({ ...graph, nodes: graph.nodes.map((n) => ({ ...n })) });
    expect(host.querySelector('.linkchip')).toBe(chip);
    expect(host.querySelector('[role="tooltip"]')).toBeNull();
  });
});
