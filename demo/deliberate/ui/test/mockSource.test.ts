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
  it('covers links as claims: explored links with arguments of their own, and flat leaves', async () => {
    const frames: GraphDto[] = [];
    const source = new MockSource(1, false);
    source.start((graph) => frames.push(graph), () => undefined);
    await vi.advanceTimersByTimeAsync(400);
    const g = frames.at(-1)!;
    const byRef = new Map(g.nodes.map((n) => [n.ref, n]));
    const links = g.nodes.filter((n) => n.kind === 'EDGE');
    // every edge carries its link's claim-like fields
    for (const l of links) {
      expect(l.text).toMatch(/^“.*” is a reason (for|against) “.*”$/);
      expect(l.status).toBeDefined();
      expect(l.override).toBeDefined();
    }
    const explored = links.filter((l) => (l.rounds ?? 0) > 0);
    expect(explored.length).toBeGreaterThanOrEqual(2);
    // arguments about a link target its edge and say so, for and against
    const onLink = g.nodes.filter((n) => n.onLink !== undefined);
    expect(onLink.some((n) => n.undercuts === undefined)).toBe(true);
    expect(onLink.some((n) => n.undercuts === n.onLink)).toBe(true);
    for (const n of onLink) expect(byRef.get(n.onLink!)?.kind).toBe('EDGE');
    // a node with no arguments has no spread; one with arguments may
    const targeted = new Set(links.map((l) => l.target));
    for (const n of g.nodes.filter((n) => !targeted.has(n.ref))) expect(n.spreadLow).toBe(n.spreadHigh);
    // a link is steered like a claim
    await source.override(explored[0].ref, 'EXPAND');
    expect(source.snapshot().nodes.find((n) => n.ref === explored[0].ref)?.override).toBe('EXPAND');
    source.stop();
  });
});
