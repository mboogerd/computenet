import { describe, expect, it } from 'vitest';
import { createComponent } from 'solid-js';
import { renderToString } from 'solid-js/web';
import JudgeSettings, { choiceLines, judgeCaption, JudgeCard } from '../src/JudgeSettings';
import { emptyGraph, reduceFrame } from '../src/model';
import type { Config, NodeState, StudioNode } from '../src/types';

const config: Config = { question: 'Does {{diff}} pass?', mode: 'gate', output: 'probability', threshold: 0.7, auto: true };
const state: NodeState = { id: 'j', status: 'gated', output: null, inputs: { diff: { old: 'one', new: 'two', repo: '/repo' } },
  error: null, runCount: 3, lastRun: { runId: 'r', startedAt: '2026-10-19T00:00:00Z', durationMs: 12, exitCode: 0, costUsd: null, outcome: 'gated' },
  judgment: { last: { answer: '0.4', probability: 0.4, reason: null }, passed: 1, gated: 2 } };

describe('Jev cell controls', () => {
  it('parses the choices editor without splitting labels containing commas', () => {
    expect(choiceLines('  needs review, by owner  \n\npass\n  stop ')).toEqual(['needs review, by owner', 'pass', 'stop']);
    expect(choiceLines('  \n')).toEqual([]);
  });
  it('shows mode, answer and probability with passed and gated counters', () => {
    expect(judgeCaption(config, state)).toBe('gate · 0.4 · p=0.4');
    expect(judgeCaption({})).toBe('judge · No judgment yet');
    const html = renderToString(() => createComponent(JudgeCard, { config, state })).replace(/<!--.*?-->/g, '');
    expect(html).toContain('Passed 1'); expect(html).toContain('Gated 2');
  });
  it('renders type-specific gate editors and keeps keys out of form fields', () => {
    const render = (value: Config) => renderToString(() => createComponent(JudgeSettings, { config: value, ports: ['diff'], set: () => {} }));
    const probability = render(config);
    expect(probability).toContain('Pass threshold'); expect(probability).toContain('step="0.01"');
    expect(probability).toContain('Input to pass through'); expect(probability).not.toContain('Choices (one per line)');
    const choice = render({ ...config, output: 'choice', choices: ['real', 'flake'], passChoices: ['real'] });
    expect(choice).toContain('Choices (one per line)'); expect(choice).toContain('Passing choices (one per line)');
    expect(choice).not.toContain('Pass threshold');
    const judge = render({ ...config, mode: 'judge', output: 'boolean' });
    expect(judge).not.toContain('Input to pass through'); expect(judge).not.toContain('Pass threshold');
    expect(judge).not.toContain('type="password"');
  });
  it('preserves gated outcomes and counters across SSE updates and reconnects', () => {
    const node: StudioNode = { id: 'j', kind: 'judge', title: 'Jev gate', x: 0, y: 0, config, ports: ['diff'] };
    let graph = reduceFrame(emptyGraph(), { type: 'snapshot', nodes: [node], edges: [], state: { j: state } });
    expect(graph.state.j.status).toBe('gated'); expect(graph.state.j.lastRun?.outcome).toBe('gated');
    const passed: NodeState = { ...state, status: 'idle', output: { ...state.inputs.diff, gate: { probability: 0.9, reason: null } },
      judgment: { last: { answer: '0.9', probability: 0.9, reason: null }, passed: 2, gated: 2 } };
    graph = reduceFrame(graph, { type: 'state', node: passed });
    const reconnect = reduceFrame(emptyGraph(), { type: 'snapshot', ...graph });
    expect(reconnect.state.j).toEqual(passed); expect(reconnect.state.j.output?.repo).toBe('/repo');
  });
});
