import { readFileSync } from 'node:fs';
import { renderToString } from 'solid-js/web';
import { describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../src/api/types';
import { EmptyState, EXAMPLES } from '../src/components/EmptyState';
import { Legend } from '../src/components/Legend';
import { QuestionInput } from '../src/components/QuestionInput';
import { ThemeToggle } from '../src/components/ThemeToggle';
import { TreeView } from '../src/components/TreeView';
import { Facts } from '../src/components/ClaimCard';

vi.mock('../src/sync/store', () => ({
  source: {
    ask: vi.fn(),
    override: vi.fn().mockResolvedValue(undefined),
  },
}));

const graph: GraphDto = {
  questions: [{ root: 'q', text: 'Should we?', claims: 3, active: true }],
  nodes: [
    {
      ref: 'q', kind: 'CLAIM', credence: 0.63, root: 'q', text: 'Should we?', depth: 0,
      status: 'EXPLORING', override: 'AUTO', proposer: 'question',
    },
    {
      ref: 'a', kind: 'CLAIM', credence: 0.75, root: 'q', text: 'It would help.', depth: 1,
      status: 'SATURATED', override: 'EXPAND', proposer: 'claude', alsoProposedBy: ['codex'], merged: true,
      plausibility: 0.7, reach: 0.8, relevance: 0.8, quality: 0.9, contribution: 0.576,
      proSaturation: 0.8, conSaturation: 0.6, rounds: 2, duplicatesDropped: 1,
      triage: { ADD: 2, MERGE: 1 },
    },
    {
      ref: 'a-q', kind: 'EDGE', credence: 0.8, root: 'q', polarity: 'SUPPORT',
      source: 'a', target: 'q', strength: 0.8,
    },
    {
      ref: 'b', kind: 'CLAIM', credence: 0.3, root: 'q', text: 'It has a cost.', depth: 2,
      status: 'PRUNED', override: 'AUTO', proposer: 'codex', reach: 0.32,
    },
    {
      ref: 'b-a', kind: 'EDGE', credence: 0.4, root: 'q', polarity: 'ATTACK',
      source: 'b', target: 'a', strength: 0.4,
    },
  ],
};

const layered: GraphDto = {
  ...graph,
  consensusMembers: ['wlo', 'jnb', 'woe'],
  nodes: graph.nodes.map((n) =>
    n.ref === 'q'
      ? { ...n, consensus: 0.41, spreadLow: 0.31, spreadHigh: 0.72, credences: { dfquad: 0.63, wlo: 0.41, mlp: 0.31, euler: 0.72 } }
      : n,
  ).concat([
    { ref: 'u', kind: 'CLAIM', credence: 0.6, root: 'q', text: 'Helping is not the point.', depth: 1, status: 'QUEUED', undercuts: 'a-q' },
    { ref: 'u-aq', kind: 'EDGE', credence: 0.5, root: 'q', polarity: 'ATTACK', source: 'u', target: 'a-q', strength: 0.5 },
  ]),
};

describe('SPEC UI contract', () => {
  it('UI-01 renders one labelled question input and submit action', () => {
    const html = renderToString(() => <QuestionInput ask={async () => true} />);
    expect(html.match(/<input\b/g)).toHaveLength(1);
    expect(html).toContain('for="ask-input"');
    expect(html).toContain('type="submit"');
    expect(html).toContain('Deliberate');
  });

  it('UI-01 exposes busy and failed submission outcomes', () => {
    const html = renderToString(() => <QuestionInput ask={async () => false} busy error="backend unavailable" />);
    expect(html).toContain('disabled');
    expect(html).toContain('Asking…');
    expect(html).toContain('role="alert"');
    expect(html).toContain('backend unavailable');
  });

  it('UI-02/UI-03 renders a rooted tree with all claim and argument facts', () => {
    const html = renderToString(() => <TreeView graph={graph} root="q" />);
    expect(html).toContain('aria-label="Deliberation tree"');
    expect(html).toContain('Should we?');
    expect(html).toContain('It would help.');
    // the question is the hero, with a plain-language verdict
    expect(html).toContain('aria-label="Question"');
    expect(html).toContain('leaning yes');
    expect(html).toContain('63%');
    // claim: credence, status, proposer, override, polarity and relation strength
    expect(html).toContain('75%');
    expect(html).toContain('fully argued');
    expect(html).toContain('claude');
    expect(html).toContain('aria-label="Exploration override"');
    expect(html).toContain('branch--pro');
    expect(html).toContain('branch--con');
    expect(html).toContain('Pro');
    expect(html).toContain('Con');
    expect(html).toContain('decisive link');
    expect(html).toContain('80%');
    // reach drives visual weight; a non-AUTO override stays visible at rest
    expect(html).toContain('reach--high');
    expect(html).toMatch(/--w:\s*0\.94/);
    expect(html).toContain('is-pinned');
    // the root is still active, so the progress line is shown as busy
    expect(html).toContain('2 of 3 claims settled');
  });

  it('makes every progressive disclosure keyboard/touch reachable and relates it to its panel', () => {
    const html = renderToString(() => <TreeView graph={graph} root="q" />);
    expect(html).toContain('aria-controls="facts-q"');
    expect(html).toContain('aria-controls="facts-a"');
    expect(html).toContain('aria-controls="children-a"');
    expect(html).toContain('id="children-a"');
    expect(html).toContain('aria-label="Hide arguments for It would help."');
    expect(html.match(/aria-label="Exploration override"/g)).toHaveLength(3);
    expect(html).toContain('aria-pressed="true"');
  });

  it('shows the Jev, contribution, provenance and triage facts for an open argument', () => {
    const claim = graph.nodes.find((n) => n.ref === 'a')!;
    const edge = graph.nodes.find((n) => n.ref === 'a-q')!;
    const html = renderToString(() => <Facts id="facts-a" claim={claim} edge={edge} />);
    for (const label of [
      'Credence', 'Plausible on its own', 'Link strength', 'Reach', 'Relevance', 'Quality',
      'Contribution', 'Sides covered', 'Rounds', 'Duplicates dropped', 'Sorted proposals',
      'Proposed by', 'Also proposed by', 'Merged', 'Ref',
    ]) expect(html).toContain(label);
    expect(html).toContain('2 added · 1 merged');
    expect(html).toContain('codex');
    expect(html).toContain('58%');
  });

  it('headlines the consensus with the rules\' spread as a band, and lists every rule', () => {
    const html = renderToString(() => <TreeView graph={layered} root="q" />);
    // consensus 41% drives the verdict, not the primary layer's 63%
    expect(html).toContain('too close to call');
    expect(html).not.toContain('leaning yes');
    expect(html).toContain('41%');
    expect(html).toContain('gauge__band');
    expect(html).toMatch(/left:\s*31%/);
    expect(html).toContain('rules disagree: 31–72%');
    const facts = renderToString(() => <Facts id="f" claim={layered.nodes[0]} members={layered.consensusMembers} />);
    expect(facts).toContain('weighted log-odds · 41% · in consensus');
    expect(facts).toContain('Euler-based · 72%');
    expect(facts).toContain('rules disagree: 31–72%');
  });

  it('renders an undercutter under the argument whose link it attacks', () => {
    const html = renderToString(() => <TreeView graph={layered} root="q" />);
    expect(html).toContain('Undercuts the link');
    expect(html).toContain('branch--undercut');
    expect(html.replace(/<!--[^>]*-->/g, '')).toContain('0 pro · 1 con · 1 undercut');
  });

  it('uses native or labelled controls for help and theme disclosure', () => {
    const legend = renderToString(() => <Legend />);
    const theme = renderToString(() => <ThemeToggle />);
    expect(legend).toContain('<details');
    expect(legend).toContain('<summary');
    expect(legend).toContain('aria-label="How to read this"');
    expect(theme).toContain('type="button"');
    expect(theme).toContain('aria-label="Switch to dark theme"');
  });

  it('shows a helpful empty state with clickable examples', () => {
    const picked: string[] = [];
    const html = renderToString(() => <EmptyState onPick={(q) => picked.push(q)} />);
    expect(html).toContain('Ask a question with two sides');
    expect(html.match(/class="example"/g)).toHaveLength(3);
    for (const q of EXAMPLES) expect(html).toContain(q);
  });

  it('UI-04 keeps neutral chrome, distinct pro/con colour, motion, and dark mode', () => {
    const tokensCss = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8');
    const appCss = readFileSync(new URL('../src/styles/app.css', import.meta.url), 'utf8');

    expect(tokensCss).toContain('--page: #f6f6f4');
    expect(tokensCss).toMatch(/--pro:\s*#[0-9a-f]+/i);
    expect(tokensCss).toMatch(/--con:\s*#[0-9a-f]+/i);
    expect(tokensCss).toContain('@media (prefers-color-scheme: dark)');
    expect(tokensCss).toContain(':root[data-theme="dark"]');
    expect(tokensCss).toContain(':root:not([data-theme="light"])');
    expect(appCss).toContain('.branch--pro');
    expect(appCss).toContain('.branch--con');
    expect(appCss).toContain('animation: enter');
    expect(appCss).toContain('@keyframes enter');
    expect(appCss).toContain('@media (prefers-reduced-motion: reduce)');
  });
});
