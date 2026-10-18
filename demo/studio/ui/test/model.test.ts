import { describe, expect, it } from 'vitest';
import { emptyGraph, inputPoint, outputPoint, outputPreview, reduceFrame, wirePath } from '../src/model';
import type { NodeState, StudioNode } from '../src/types';

const node: StudioNode = { id: 'n1', kind: 'text', title: 'Source', x: 120, y: 80, config: {}, ports: [] };
const target: StudioNode = { ...node, id: 'n2', kind: 'exec', ports: ['in', 'change'], x: 600, y: 180 };
const state: NodeState = { id: 'n1', status: 'idle', output: { old: null, new: 'hello' }, inputs: {}, error: null, runCount: 0, lastRun: null, text: 'hello' };

describe('SSE frame reducer', () => {
  it('replaces the entire graph and state on initial and reconnect snapshots', () => {
    const old = { nodes: [target], edges: [], state: { n2: { ...state, id: 'n2' } } };
    expect(reduceFrame(old, { type: 'snapshot', nodes: [node], edges: [], state: { n1: state } })).toEqual({ nodes: [node], edges: [], state: { n1: state } });
    expect(old.nodes).toEqual([target]);
  });
  it('replaces nodes and edges on graph frames, preserving live state and discarding deleted state', () => {
    const old = { nodes: [node, target], edges: [{ id: 'e1', from: 'n1', to: 'n2', port: 'in' }], state: { n1: state, n2: { ...state, id: 'n2' } } };
    const next = reduceFrame(old, { type: 'graph', nodes: [{ ...node, title: 'Renamed' }], edges: [] });
    expect(next.nodes[0].title).toBe('Renamed');
    expect(next.edges).toEqual([]);
    expect(next.state).toEqual({ n1: state });
    expect(old.state.n2).toBeDefined();
  });
  it('replaces only one node state without merging removed optional properties', () => {
    const initial = reduceFrame(emptyGraph(), { type: 'snapshot', nodes: [node, target], edges: [], state: { n1: state, n2: { ...state, id: 'n2', code: 'old code' } } });
    const update: NodeState = { ...state, id: 'n2', status: 'failed', error: 'process failed', runCount: 1 };
    const next = reduceFrame(initial, { type: 'state', node: update });
    expect(next.nodes).toBe(initial.nodes);
    expect(next.state.n1).toBe(state);
    expect(next.state.n2).toEqual(update);
    expect(next.state.n2.code).toBeUndefined();
  });
  it('applies a realistic graph, state and reconnect sequence', () => {
    let graph = reduceFrame(emptyGraph(), { type: 'snapshot', nodes: [node], edges: [], state: { n1: state } });
    graph = reduceFrame(graph, { type: 'graph', nodes: [node, target], edges: [{ id: 'e1', from: 'n1', to: 'n2', port: 'in' }] });
    graph = reduceFrame(graph, { type: 'state', node: { ...state, id: 'n2', status: 'stale', inputs: { in: state.output! } } });
    expect(graph.edges).toHaveLength(1);
    expect(graph.state.n2.status).toBe('stale');
    expect(reduceFrame(graph, { type: 'snapshot', ...emptyGraph() })).toEqual(emptyGraph());
  });
});

describe('wire geometry', () => {
  it('anchors to the card edge and the ordered input row', () => {
    expect(outputPoint(node)).toEqual({ x: 380, y: 162 });
    expect(inputPoint(target, 'in')).toEqual({ x: 600, y: 262 });
    expect(inputPoint(target, 'change')).toEqual({ x: 600, y: 290 });
  });
  it('creates forward cubic handles', () => {
    expect(wirePath({ x: 10, y: 20 }, { x: 210, y: 120 })).toBe('M 10 20 C 110 20, 110 120, 210 120');
  });
  it('keeps short and backward wires curved without NaN', () => {
    expect(wirePath({ x: 0, y: 0 }, { x: 0, y: 0 })).toBe('M 0 0 C 60 0, -60 0, 0 0');
    expect(wirePath({ x: 100, y: 0 }, { x: -100, y: 50 })).toBe('M 100 0 C 200 0, -200 50, -100 50');
  });
});

describe('output preview', () => {
  it('handles absent and empty output', () => {
    expect(outputPreview(null)).toBe(''); expect(outputPreview(undefined)).toBe(''); expect(outputPreview('')).toBe('');
  });
  it('keeps four lines and adds an ellipsis only when content is omitted', () => {
    expect(outputPreview('a\nb\nc\nd')).toBe('a\nb\nc\nd');
    expect(outputPreview('a\nb\nc\nd\ne')).toBe('a\nb\nc\nd…');
  });
  it('also bounds very long individual lines', () => {
    expect(outputPreview('x'.repeat(321))).toBe('x'.repeat(320) + '…');
    expect(outputPreview('x'.repeat(320))).toHaveLength(320);
  });
});
