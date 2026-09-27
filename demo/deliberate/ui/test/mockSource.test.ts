import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../src/api/types';
import { MockSource } from '../src/mock/mockSource';

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(console, 'info').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('MockSource', () => {
  it('accepts multiple questions while earlier trees are still growing', async () => {
    const frames: GraphDto[] = [];
    const source = new MockSource(10, true);
    source.start((graph) => frames.push(graph), () => undefined);

    await expect(source.ask('First question?')).resolves.toBe('q0');
    await expect(source.ask('Second question?')).resolves.toBe('q1');

    expect(frames.at(-1)?.questions.map((q) => [q.root, q.text, q.active])).toEqual([
      ['q0', 'First question?', true],
      ['q1', 'Second question?', true],
    ]);
    source.stop();
  });

  it('derives the contract reach field from parent reach and relation strength', async () => {
    const frames: GraphDto[] = [];
    const source = new MockSource(10, true);
    source.start((graph) => frames.push(graph), () => undefined);
    await source.ask('Reach?');

    await vi.advanceTimersByTimeAsync(60);

    const nodes = frames.at(-1)!.nodes;
    expect(nodes.find((n) => n.ref === 'q0')?.reach).toBe(1);
    expect(nodes.find((n) => n.ref === 'q0-c1')?.reach).toBeCloseTo(0.78);
    source.stop();
  });

  it('mirrors STOP/AUTO status transitions and rejects unknown claims', async () => {
    const source = new MockSource(10, true);
    source.start(() => undefined, () => undefined);
    await source.ask('Control?');

    await source.override('q0', 'STOP');
    expect(source.snapshot().nodes.find((n) => n.ref === 'q0')?.status).toBe('STOPPED');
    await vi.advanceTimersByTimeAsync(30);
    expect(source.snapshot().nodes.find((n) => n.ref === 'q0')?.status).toBe('STOPPED');

    await source.override('q0', 'AUTO');
    expect(source.snapshot().nodes.find((n) => n.ref === 'q0')?.status).toBe('QUEUED');
    await expect(source.override('missing', 'STOP')).rejects.toThrow('unknown claim missing');
    source.stop();
  });

  it('cancels every pending scripted update on stop', async () => {
    const frames: GraphDto[] = [];
    const source = new MockSource(10, true);
    source.start((graph) => frames.push(graph), () => undefined);
    await source.ask('Stop?');
    const countAtStop = frames.length;

    source.stop();
    await vi.runAllTimersAsync();

    expect(frames).toHaveLength(countAtStop);
  });

  it('generates credence layers, their consensus and spread, and an undercut', async () => {
    const frames: GraphDto[] = [];
    const source = new MockSource(1, true);
    source.start((graph) => frames.push(graph), () => undefined);
    await source.ask('Layers?');
    await vi.advanceTimersByTimeAsync(200);
    const g = frames.at(-1)!;
    expect(g.consensusMembers).toEqual(['wlo', 'jnb', 'woe']);
    for (const n of g.nodes) {
      expect(Object.keys(n.credences!)).toHaveLength(7);
      const values = Object.values(n.credences!);
      expect(n.spreadLow).toBe(Math.min(...values));
      expect(n.spreadHigh).toBe(Math.max(...values));
      expect(n.consensus!).toBeGreaterThanOrEqual(n.spreadLow!);
      expect(n.consensus!).toBeLessThanOrEqual(n.spreadHigh!);
    }
    const u = g.nodes.find((n) => n.undercuts !== undefined)!;
    expect(g.nodes.find((n) => n.ref === u.undercuts)?.kind).toBe('EDGE');
    source.stop();
  });
});
