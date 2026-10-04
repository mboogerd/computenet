import { createSignal } from 'solid-js';
import { render } from 'solid-js/web';
import { afterEach, describe, expect, it } from 'vitest';
import type { FramingDto } from '../../src/api/types';
import { FramingSummary } from '../../src/components/Framing';

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

const readings = (c1: number, c2: number, c3: number): FramingDto => ({
  mode: 'READINGS',
  term: 'intelligent',
  positions: [
    { ref: 'p1', text: 'General cognitive ability', credence: c1 },
    { ref: 'p2', text: 'Knowledge and analytical reasoning', credence: c2 },
    { ref: 'p3', text: 'Practical and social intelligence', credence: c3 },
  ],
});

describe('one spread bar for a framed question’s readings (computenet-3z7w5)', () => {
  it('numbers a marker per reading at its credence, bands the spread, and lists the full readings', () => {
    const [f, setF] = createSignal(readings(0.22, 0.31, 0.66));
    const host = mount(() => <FramingSummary framing={f()} />);
    const marks = [...host.querySelectorAll<HTMLElement>('.spread__mark')];
    expect(marks.map((m) => m.textContent)).toEqual(['1', '2', '3']);
    expect(marks.map((m) => m.style.left)).toEqual(['22%', '31%', '66%']);
    const band = host.querySelector<HTMLElement>('.spread__band')!;
    expect(band.style.left).toBe('22%');
    expect(parseFloat(band.style.width)).toBeCloseTo(44, 6);
    const rows = [...host.querySelectorAll('.spread__row')].map((r) => r.textContent);
    expect(rows).toEqual(['1General cognitive ability22%', '2Knowledge and analytical reasoning31%', '3Practical and social intelligence66%']);
    expect(host.querySelector('.framing__line')!.textContent).toBe('between 22% and 66%, depending on what you mean by intelligent');

    // A new frame: all new objects, new credences. Markers and rows move in place.
    setF(readings(0.4, 0.31, 0.7));
    const after = [...host.querySelectorAll<HTMLElement>('.spread__mark')];
    expect(after[0]).toBe(marks[0]);
    expect(after[0].style.left).toBe('40%');
    expect(host.querySelector<HTMLElement>('.spread__band')!.style.left).toBe('31%');
    expect(host.querySelectorAll('.spread__row')[0].textContent).toContain('40%');
  });

  it('POSITIONS framing keeps its share list and draws no spread bar', () => {
    const host = mount(() => (
      <FramingSummary framing={{ mode: 'POSITIONS', positions: [{ ref: 'a', text: 'Under 100.', credence: 0.7, share: 0.7 }] }} />
    ));
    expect(host.querySelector('.spread__track')).toBeNull();
    expect(host.querySelectorAll('.framing__row')).toHaveLength(1);
  });

  const positions = (...shares: number[]): FramingDto => ({
    mode: 'POSITIONS',
    positions: shares.map((share, i) => ({ ref: `p${i + 1}`, text: `Answer ${i + 1}`, credence: share, share })),
  });

  it('jointly implausible POSITIONS show a none-of-the-listed row, not an even split (computenet-x91yk)', () => {
    const host = mount(() => <FramingSummary framing={positions(0.1, 0.1, 0.1)} />);
    const rows = [...host.querySelectorAll('.framing__row')].map((r) => r.textContent);
    expect(rows).toEqual(['Answer 110%', 'Answer 210%', 'Answer 310%', 'None of the listed answers70%']);
    const fills = [...host.querySelectorAll<HTMLElement>('.framing__bar-fill')].map((f) => f.style.transform);
    expect(fills[3]).toBe('scaleX(0.7)');
  });

  it('POSITIONS whose shares fill the frame show no none-of-the-listed row (computenet-x91yk)', () => {
    const credences = [0.15, 0.2, 0.7];
    const total = credences.reduce((a, b) => a + b, 0);
    const host = mount(() => <FramingSummary framing={positions(...credences.map((c) => c / total))} />);
    const rows = [...host.querySelectorAll('.framing__row')].map((r) => r.textContent);
    expect(rows).toEqual(['Answer 114%', 'Answer 219%', 'Answer 367%']);
  });
});
