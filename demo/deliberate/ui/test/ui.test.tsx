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
    pause: vi.fn().mockResolvedValue(undefined),
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
      text: '“It would help.” is a reason for “Should we?”', depth: 1, status: 'PRUNED', override: 'AUTO', rounds: 0,
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
    { ref: 'u', kind: 'CLAIM', credence: 0.6, root: 'q', text: 'Helping is not the point.', depth: 2, status: 'QUEUED', undercuts: 'a-q', onLink: 'a-q' },
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
    // the connector leads with how far the link holds; Jev's first impression is in its label
    expect(html).toContain('holds 80%');
    expect(html).toContain('first impression: decisive link 80%');
    // reach drives visual weight; a non-AUTO override stays visible at rest
    expect(html).toContain('reach--high');
    expect(html).toMatch(/--w:\s*0\.94/);
    expect(html).toContain('is-pinned');
    // the root is still active, so the progress line is shown as busy
    expect(html).toContain('2 of 3 claims settled');
  });

  it('says in the question header when returns diminished, and nothing while the tree grows', () => {
    expect(renderToString(() => <TreeView graph={graph} root="q" />)).not.toContain('stopped:');
    const stopped: GraphDto = {
      ...graph,
      questions: [{ ...graph.questions[0], active: false, stoppedBy: 'diminishing', yieldRecent: 0.05, yieldEarlier: 0.2 }],
      nodes: graph.nodes.map((n) => (n.ref === 'a' ? { ...n, status: 'DIMINISHING' } : n)),
    };
    const html = renderToString(() => <TreeView graph={stopped} root="q" />);
    expect(html).toContain('stopped: returns diminished');
    expect(html).toContain('returns diminished');
    expect(html).toContain('0.05 vs 0.20');
  });

  it('CTL-05 offers pause beside the cost, and shows "paused" with Resume beside the verdict when paused', () => {
    const running = renderToString(() => <TreeView graph={graph} root="q" />);
    expect(running).toMatch(/<button[^>]*class="pause\s*"[^>]*>Pause<\/button>/);
    expect(running).not.toContain('hero__paused');
    expect(running).toContain('deliberating');
    // Next to the cost figure.
    expect(running.indexOf('class="cost"')).toBeLessThan(running.search(/class="pause\s*"/));
    const paused: GraphDto = { ...graph, questions: [{ ...graph.questions[0], paused: true }] };
    const html = renderToString(() => <TreeView graph={paused} root="q" />);
    expect(html).toMatch(/<button[^>]*class="pause\s+is-on\s*"[^>]*>Resume<\/button>/);
    expect(html.match(/class="pause[\s"]/g)).toHaveLength(1);
    expect(html).toContain('class="hero__paused"');
    expect(html).toContain('2 of 3 claims settled');
    // Waiting, not working: no "deliberating" and no busy animation.
    expect(html).not.toContain('deliberating');
    expect(html).not.toMatch(/class="hero[^"]*is-busy/);
    // The paused chip and Resume sit in the verdict row, before the meta line.
    const row = html.slice(html.indexOf('class="hero__row"'), html.indexOf('class="hero__meta"'));
    expect(row).toContain('hero__paused');
    expect(row).toContain('>Resume</button>');
  });

  it('CTL-05 hides Pause on a question that is not deliberating', () => {
    const settled: GraphDto = { ...graph, questions: [{ ...graph.questions[0], active: false }] };
    const html = renderToString(() => <TreeView graph={settled} root="q" />);
    expect(html).not.toMatch(/class="pause/);
  });

  it('says "no arguments yet" instead of a verdict on a question without arguments', () => {
    const bare: GraphDto = { ...graph, nodes: [graph.nodes[0]] };
    const html = renderToString(() => <TreeView graph={bare} root="q" />);
    expect(html).toContain('no arguments yet');
    expect(html).not.toContain('leaning yes');
    expect(html).not.toContain('gauge__band');
    expect(html).not.toContain('gauge__caption');
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
      'Proposed by', 'Also proposed by', 'Merged',
    ]) expect(html).toContain(label);
    // the ref is a debugging aid, shown only with ?debug
    expect(html).not.toContain('Ref');
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
    // the band is drawn above the fill, with a marker at the consensus and a caption under the gauge
    const gauge = html.slice(html.indexOf('class="gauge'), html.indexOf('class="hero__row"'));
    expect(gauge.indexOf('gauge__fill')).toBeLessThan(gauge.indexOf('gauge__band'));
    expect(gauge).toMatch(/class="gauge__mark"[^>]*left:\s*41%/);
    expect(gauge).toContain('rules: 31–72%');
    const facts = renderToString(() => <Facts id="f" claim={layered.nodes[0]} members={layered.consensusMembers} />);
    expect(facts).toContain('weighted log-odds · 41% · in consensus');
    expect(facts).toContain('Euler-based · 72%');
    expect(facts).toContain('rules disagree: 31–72%');
  });

  it('renders an undercutter under the link it attacks, not under the claim', () => {
    const html = renderToString(() => <TreeView graph={layered} root="q" />);
    expect(html).toContain('Undercuts the link');
    expect(html).toContain('branch--undercut');
    const text = html.replace(/<!--[^>]*-->/g, '');
    // the claim keeps its own arguments; the undercutter sits in its link's panel
    expect(text).toContain('0 pro · 1 con');
    expect(text).not.toContain('undercut ·');
    expect(html).toMatch(/<section[^>]*class="linkpanel[^"]*"[^>]*id="link-a-q"/);
    expect(text).toContain('Why it fails');
    const panel = html.slice(html.indexOf('id="link-a-q"'));
    expect(panel.indexOf('Helping is not the point.')).toBeGreaterThan(-1);
    expect(panel.indexOf('Helping is not the point.')).toBeLessThan(panel.indexOf('id="children-a"'));
  });

  it('makes the connector a control that leads with how far the link holds and opens it', () => {
    const html = renderToString(() => <TreeView graph={layered} root="q" />);
    // the chip on a's connector: expandable into the link panel
    expect(html).toMatch(/<button[^>]*class="linkchip[^"]*"[^>]*aria-expanded="false"[^>]*aria-controls="link-a-q"/);
    const chip = html.slice(html.search(/<button[^>]*class="linkchip/), html.indexOf('</button>', html.search(/<button[^>]*class="linkchip/)));
    expect(chip.replace(/<!--[^>]*-->/g, '')).toContain('holds 80%');
    expect(chip).not.toContain('decisive link 80%<');
    // no cryptic +1 −1 counters, and no preview until the chip is hovered or focused
    expect(html).not.toContain('linkchip__count');
    expect(html).not.toContain('role="tooltip"');
    expect(html).not.toContain('aria-describedby="peek-');
  });

  it('opens a link panel with its credence, status, arguments and override', async () => {
    const { LinkPanel, indexTree } = await import('../src/components/ClaimCard');
    const { buildTree } = await import('../src/tree/buildTree');
    const index = indexTree(buildTree(layered, 'q')!);
    const edge = layered.nodes.find((n) => n.ref === 'a-q')!;
    const sel = { selected: () => undefined, toggle: () => undefined };
    const html = renderToString(() => (
      <LinkPanel edge={edge} args={index.get('a')!.linkArgs} open index={() => index} sel={sel} onClose={() => undefined} />
    ));
    const text = html.replace(/<!--[^>]*-->/g, '');
    expect(html).toContain('aria-label="Link exploration override"');
    expect(text).toContain('holds');
    expect(text).toContain('set aside');
    expect(text).toContain('Why it holds');
    expect(text).toContain('No reasons yet that it holds.');
    expect(text).toContain('Helping is not the point.');
    expect(text).toContain('“Should we?”');
  });

  it('says why a claim with no arguments has no spread band', () => {
    const leaf = layered.nodes.find((n) => n.ref === 'b')!;
    const flat = { ...leaf, consensus: 0.3, spreadLow: 0.3, spreadHigh: 0.3, credences: { dfquad: 0.3, wlo: 0.3 } };
    const facts = renderToString(() => <Facts id="f" claim={flat} leaf />);
    expect(facts).toContain('no arguments yet — all rules agree with the first impression');
    const notLeaf = renderToString(() => <Facts id="f" claim={flat} />);
    expect(notLeaf).toContain('rules agree');
    expect(notLeaf).not.toContain('no arguments yet');
    // the card draws a tick for the single value instead of an invisible band
    const tree: GraphDto = { ...graph, nodes: graph.nodes.map((n) => (n.ref === 'b' ? flat : n)) };
    const html = renderToString(() => <TreeView graph={tree} root="q" />);
    expect(html).toContain('bar--leaf');
    expect(html).toContain('bar__tick');
    expect(html).toContain('no arguments yet — all rules agree with the first impression');
  });

  it('shows what the deliberation is doing now, links included', () => {
    const busy: GraphDto = {
      ...layered,
      nodes: layered.nodes.map((n) =>
        n.ref === 'a' ? { ...n, activity: 'exploring' } : n.ref === 'a-q' ? { ...n, activity: 'judging' } : n,
      ),
    };
    const text = renderToString(() => <TreeView graph={busy} root="q" />).replace(/<!--[^>]*-->/g, '');
    expect(text).toContain('aria-label="Now exploring"');
    expect(text).toContain('gathering arguments on');
    // one item on its one line; the rest counted, and named in the count's tooltip
    expect(text.match(/class="now__item/g)).toHaveLength(1);
    expect(text).toContain('+1 more');
    expect(text).toMatch(/title="weighing link “It would help.”"/);
    const idle = renderToString(() => <TreeView graph={graph} root="q" />);
    expect(idle).toContain('nothing in flight');
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
