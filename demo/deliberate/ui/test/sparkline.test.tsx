import { renderToString } from 'solid-js/web';
import { describe, expect, it } from 'vitest';
import { accumulate, pointsToPath, Sparkline, type SparkState } from '../src/components/Sparkline';

describe('accumulate (session credence history)', () => {
  it('starts a series with the first known credence', () => {
    const s = accumulate(undefined, 'q1', 0.5);
    expect(s).toEqual({ root: 'q1', points: [0.5] });
  });

  it('appends a changed credence for the same root', () => {
    let s: SparkState | undefined = accumulate(undefined, 'q1', 0.5);
    s = accumulate(s, 'q1', 0.6);
    expect(s.points).toEqual([0.5, 0.6]);
  });

  it('appends nothing when the credence is unchanged', () => {
    let s: SparkState | undefined = accumulate(undefined, 'q1', 0.5);
    s = accumulate(s, 'q1', 0.5);
    s = accumulate(s, 'q1', 0.5);
    expect(s.points).toEqual([0.5]);
  });

  it('resets the series when a different question is selected', () => {
    let s: SparkState | undefined = accumulate(undefined, 'q1', 0.5);
    s = accumulate(s, 'q1', 0.7);
    s = accumulate(s, 'q2', 0.2);
    expect(s).toEqual({ root: 'q2', points: [0.2] });
  });

  it('an unknown credence for a new root starts an empty series without recording a point', () => {
    const s = accumulate(undefined, 'q1', undefined);
    expect(s).toEqual({ root: 'q1', points: [] });
  });

  it('an unknown credence for the same root leaves the series untouched', () => {
    let s: SparkState | undefined = accumulate(undefined, 'q1', 0.5);
    s = accumulate(s, 'q1', undefined);
    expect(s.points).toEqual([0.5]);
  });
});

describe('pointsToPath', () => {
  it('renders nothing (null) with fewer than two points', () => {
    expect(pointsToPath([])).toBeNull();
    expect(pointsToPath([0.5])).toBeNull();
  });

  it('builds a polyline points string for two or more points', () => {
    const p = pointsToPath([0, 1]);
    expect(p).not.toBeNull();
    expect(p!.split(' ')).toHaveLength(2);
  });
});

describe('<Sparkline> (SPEC: session sparkline of the shown credence)', () => {
  it('renders nothing with fewer than two points', () => {
    expect(renderToString(() => <Sparkline points={[]} />)).not.toContain('<svg');
    expect(renderToString(() => <Sparkline points={[0.5]} />)).not.toContain('<svg');
  });

  it('renders an svg polyline once at least two points are given', () => {
    const html = renderToString(() => <Sparkline points={[0.4, 0.7]} />);
    expect(html).toContain('<svg');
    expect(html).toContain('polyline');
  });

  it('draws the accumulated series end to end: a question switch resets before it renders', () => {
    let state: SparkState | undefined = accumulate(undefined, 'q1', 0.4);
    state = accumulate(state, 'q1', 0.7); // two points now
    const before = renderToString(() => <Sparkline points={state!.points} />);
    expect(before).toContain('<svg');

    state = accumulate(state, 'q2', 0.3); // switching questions resets to one point
    const after = renderToString(() => <Sparkline points={state!.points} />);
    expect(after).not.toContain('<svg');
  });
});
