import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../src/api/types';
import { EXAMPLE_QUESTION, GENERIC, MockSource } from '../src/mock/mockSource';

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(console, 'info').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('MockSource', () => {
  // A typed question gets topic-neutral claims: nothing from the built-in example leaks into it.
  const DENYLIST = /northfield|library|libraries|sunday|saturday|weekend|reading|volunteer|staff|visitor|student|town hall|study centre/i;

  it('?mock=empty: a typed question runs a topic-neutral script, not the built-in example', async () => {
    const frames: GraphDto[] = [];
    const source = new MockSource(10, true);
    source.start((graph) => frames.push(graph), () => undefined);
    const root = await source.ask('Should we switch to the new process?');
    await vi.advanceTimersByTimeAsync(10 * 60);
    const texts = frames.at(-1)!.nodes.filter((n) => n.root === root && n.kind === 'CLAIM' && n.depth !== 0).map((n) => n.text ?? '');
    expect(texts.length).toBe(Object.keys(GENERIC).length);
    for (const t of texts) expect(t).not.toMatch(DENYLIST);
    for (const t of Object.values(GENERIC)) expect(t).not.toMatch(DENYLIST);
    source.stop();
  });

  it('?mock: the built-in example keeps its own claims', async () => {
    const frames: GraphDto[] = [];
    const source = new MockSource(10);
    source.start((graph) => frames.push(graph), () => undefined);
    await vi.advanceTimersByTimeAsync(10 * 60);
    const g = frames.at(-1)!;
    const example = g.questions.find((q) => q.text === EXAMPLE_QUESTION)!;
    expect(g.nodes.some((n) => n.root === example.root && DENYLIST.test(n.text ?? '') && n.depth !== 0)).toBe(true);
    source.stop();
  });

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

  it('holds a paused question still and resumes it (CTL-05)', async () => {
    const source = new MockSource(10, true);
    source.start(() => undefined, () => undefined);
    const root = await source.ask('Pause?');
    await source.pause(root, true);
    expect(source.snapshot().questions.find((q) => q.root === root)?.paused).toBe(true);
    const size = source.snapshot().nodes.length;
    await vi.advanceTimersByTimeAsync(200);
    expect(source.snapshot().nodes.length).toBe(size);
    await source.pause(root, false);
    await vi.advanceTimersByTimeAsync(200);
    expect(source.snapshot().nodes.length).toBeGreaterThan(size);
    expect(source.snapshot().questions.find((q) => q.root === root)?.paused).toBe(false);
    await expect(source.pause('missing', true)).rejects.toThrow('unknown question missing');
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
      expect(Object.keys(n.credences!)).toHaveLength(8);
      expect(Object.keys(n.argumentsFirstCredences!)).toHaveLength(8);
      const values = Object.values(n.credences!);
      expect(n.spreadLow).toBe(Math.min(...values));
      expect(n.spreadHigh).toBe(Math.max(...values));
      expect(n.consensus!).toBeGreaterThanOrEqual(n.spreadLow!);
      expect(n.consensus!).toBeLessThanOrEqual(n.spreadHigh!);
      expect(n.argumentsFirstConsensus).toBeGreaterThanOrEqual(0);
      expect(n.argumentsFirstConsensus).toBeLessThanOrEqual(1);
    }
    const targets = new Set(g.nodes.filter((n) => n.kind === 'EDGE').map((n) => n.target));
    for (const n of g.nodes.filter((n) => !targets.has(n.ref))) {
      expect(n.argumentsFirstCredences).toEqual(n.credences);
      expect(n.argumentsFirstConsensus).toBe(n.consensus);
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

  it('model A: the ?mock graph has one READINGS and one POSITIONS question, each FRAMED with no direct edges', () => {
    const source = new MockSource(10, false);
    source.start(() => undefined, () => undefined);
    const g = source.snapshot();

    const framed = g.questions.filter((q) => q.framing !== undefined);
    expect(framed.map((q) => q.framing?.mode).sort()).toEqual(['POSITIONS', 'READINGS']);

    const byRef = new Map(g.nodes.map((n) => [n.ref, n]));
    for (const q of framed) {
      const root = byRef.get(q.root)!;
      expect(root.status).toBe('FRAMED');
      expect(g.nodes.some((n) => n.kind === 'EDGE' && n.target === q.root)).toBe(false);
      // every position's NodeDto has positionOf equal to this root, and is not itself a question
      for (const p of q.framing!.positions) {
        expect(byRef.get(p.ref)?.positionOf).toBe(q.root);
      }
      expect(g.questions.some((other) => other.root === q.framing!.positions[0]?.ref)).toBe(false);
    }
    source.stop();
  });

  it('model A READINGS: two readings in order, each explored as a root, with a pro/con of its own', () => {
    const source = new MockSource(10, false);
    source.start(() => undefined, () => undefined);
    const g = source.snapshot();
    const readings = g.questions.find((q) => q.framing?.mode === 'READINGS')!;
    expect(readings.framing!.term).toBeDefined();
    expect(readings.framing!.positions).toHaveLength(2);
    expect(readings.framing!.positions.every((p) => p.share === undefined)).toBe(true);
    const [p1, p2] = readings.framing!.positions;
    expect(p1.firstImpression).toBeDefined();
    // the first reading's own argument is under it, not the second reading's
    const argsUnderP1 = g.nodes.filter((n) => n.kind === 'EDGE' && n.target === p1.ref);
    const argsUnderP2 = g.nodes.filter((n) => n.kind === 'EDGE' && n.target === p2.ref);
    expect(argsUnderP1.length).toBeGreaterThan(0);
    expect(argsUnderP2.length).toBeGreaterThan(0);
    source.stop();
  });

  it('model A POSITIONS: shares sum to 1 and are proportional to consensus (softmax odds normalisation)', () => {
    const source = new MockSource(10, false);
    source.start(() => undefined, () => undefined);
    const g = source.snapshot();
    const positions = g.questions.find((q) => q.framing?.mode === 'POSITIONS')!;
    const shares = positions.framing!.positions.map((p) => p.share!);
    expect(shares.every((s) => s !== undefined)).toBe(true);
    expect(shares.reduce((a, b) => a + b, 0)).toBeCloseTo(1, 9);
    // the highest-consensus position gets the highest share
    const byRef = new Map(g.nodes.map((n) => [n.ref, n]));
    const consensuses = positions.framing!.positions.map((p) => byRef.get(p.ref)!.consensus ?? byRef.get(p.ref)!.credence);
    const order = [...shares.keys()].sort((a, b) => consensuses[b] - consensuses[a]);
    expect([...shares.keys()].sort((a, b) => shares[b] - shares[a])).toEqual(order);
    source.stop();
  });
});
