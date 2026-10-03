import { readFileSync } from 'node:fs';
import { renderToString } from 'solid-js/web';
import { describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../src/api/types';
import { CruxesPanel, cruxesOf } from '../src/components/CruxesPanel';
import { EmptyState, EXAMPLES } from '../src/components/EmptyState';
import { Legend } from '../src/components/Legend';
import { QuestionInput } from '../src/components/QuestionInput';
import { ThemeToggle } from '../src/components/ThemeToggle';
import { TreeView } from '../src/components/TreeView';
import { Facts } from '../src/components/ClaimCard';
import { MockSource } from '../src/mock/mockSource';

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

  it('renders any node error as a failed call, with no sentinel exempt (EXP-06, computenet-dq2fy.24.1)', () => {
    expect(renderToString(() => <TreeView graph={graph} root="q" />)).not.toContain('a call failed');
    const errored: GraphDto = {
      ...graph,
      nodes: graph.nodes.map((n) => (n.ref === 'a' ? { ...n, status: 'BUDGET', error: 'budget exhausted' } : n)),
    };
    expect(renderToString(() => <TreeView graph={errored} root="q" />)).toContain('a call failed');
  });

  it('says in the question header when nothing left could change the answer, and nothing while the tree grows', () => {
    expect(renderToString(() => <TreeView graph={graph} root="q" />)).not.toContain('stopped:');
    const stopped: GraphDto = {
      ...graph,
      questions: [{ ...graph.questions[0], active: false, stoppedBy: 'voi' }],
      nodes: graph.nodes.map((n) => (n.ref === 'a' ? { ...n, status: 'DIMINISHING' } : n)),
    };
    const html = renderToString(() => <TreeView graph={stopped} root="q" />);
    expect(html).toContain('stopped: nothing left could change the answer');
    expect(html).toContain('not worth exploring');
  });

  it('model C lists the question\'s cruxes under "what would change the answer", best first', () => {
    expect(renderToString(() => <CruxesPanel graph={graph} root="q" />)).not.toContain('What would change the answer');
    const withCruxes: GraphDto = {
      ...graph,
      questions: [{ ...graph.questions[0], cruxes: ['a', 'missing'] }],
      nodes: graph.nodes.map((n) => (n.ref === 'a' ? { ...n, sensitivity: -0.25, plausibility: 0.6 } : n)),
    };
    // Solid's SSR marks each dynamic text part with a comment; read the text without them.
    const html = renderToString(() => <CruxesPanel graph={withCruxes} root="q" />).replace(/<!--[^>]*-->/g, '');
    expect(html).toContain('What would change the answer');
    expect(html).toContain('sway 0.25');
    expect(html).toContain('60% plausible');
    expect(html).toContain('if it holds, the answer falls');
    // A ref the graph does not hold is left out, not shown blank.
    expect(cruxesOf(withCruxes, 'q').map((c) => c.ref)).toEqual(['a']);
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
    expect(html).toContain('settled · 3 claims');
    expect(html).not.toContain('deliberating');
    expect(html).not.toMatch(/class="hero[^"]*is-busy/);
  });

  it('treats active link work as deliberating even when every claim is settled', () => {
    const linkBusy: GraphDto = {
      ...graph,
      nodes: graph.nodes.map((n) =>
        n.kind === 'CLAIM'
          ? { ...n, status: 'SATURATED' }
          : n.ref === 'a-q'
            ? { ...n, status: 'EXPLORING', activity: 'exploring' }
            : n,
      ),
    };
    const html = renderToString(() => <TreeView graph={linkBusy} root="q" />);
    expect(html).toMatch(/class="hero[^"].*is-busy/);
    expect(html).toContain('deliberating · 3 of 3 claims settled');
    expect(html).toMatch(/<button[^>]*class="pause/);
  });

  it('shows the single first-impression value, not a verdict, on a question without arguments', () => {
    const bare: GraphDto = { ...graph, nodes: [graph.nodes[0]] };
    const html = renderToString(() => <TreeView graph={bare} root="q" />);
    expect(html).toContain('no arguments yet — all rules agree with the first impression');
    expect(html).not.toContain('leaning yes');
    // model D: only the consensus by default — the rules' band is in the research view
    expect(html).not.toContain('gauge__band');
    expect(html).toMatch(/class="gauge__mark"[^>]*left:\s*63%/);
    expect(html).toContain('first impression: 63%');
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

  it('shows only the consensus by default: no band, no per-rule values', () => {
    const html = renderToString(() => <TreeView graph={layered} root="q" />);
    expect(html).toContain('too close to call');
    expect(html).toContain('41%');
    expect(html).not.toContain('gauge__band');
    expect(html).not.toContain('bar__band');
    expect(html).not.toContain('rules disagree');
    expect(html).not.toContain('rules: 31–72%');
    const facts = renderToString(() => <Facts id="f" claim={layered.nodes[0]} members={layered.consensusMembers} />);
    expect(facts).not.toContain('By rule');
    expect(facts).not.toContain('weighted log-odds');
    expect(facts).not.toContain('rules disagree');
  });

  it('headlines the consensus with the rules\' spread as a band, and lists every rule, in the research view', () => {
    const html = renderToString(() => <TreeView graph={layered} root="q" research />);
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
    const facts = renderToString(() => <Facts id="f" claim={layered.nodes[0]} members={layered.consensusMembers} research />);
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
    // a readable total replaces cryptic +1/−1 counters; no preview until hover/focus
    expect(chip.replace(/<!--[^>]*-->/g, '')).toContain('1 argument');
    expect(html).toContain('linkchip__count');
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
    const facts = renderToString(() => <Facts id="f" claim={flat} leaf research />);
    expect(facts).toContain('no arguments yet — all rules agree with the first impression');
    const notLeaf = renderToString(() => <Facts id="f" claim={flat} research />);
    expect(notLeaf).toContain('rules agree');
    expect(notLeaf).not.toContain('no arguments yet');
    // the card draws a tick for the single value instead of an invisible band
    const tree: GraphDto = { ...graph, nodes: graph.nodes.map((n) => (n.ref === 'b' ? flat : n)) };
    const html = renderToString(() => <TreeView graph={tree} root="q" research />);
    expect(html).toContain('bar--leaf');
    expect(html).toContain('bar__tick');
    expect(html).toContain('no arguments yet — all rules agree with the first impression');
  });

  it('model D: shows the first impression and what the arguments alone say, and flags a disagreement', () => {
    const q = { ...graph.questions[0], firstImpression: 0.9, neutralCredence: 0.3, verdictsDisagree: true };
    const html = renderToString(() => <TreeView graph={{ ...graph, questions: [q] }} root="q" />);
    const text = html.replace(/<!--[^>]*-->/g, '');
    expect(text).toContain('first impression 90% · arguments alone 30%');
    expect(text).toMatch(/class="hero__prior"[^>]*role="note"/);
    expect(text).toContain('The first impression decides the side: weighed from a neutral start, the arguments lean no (30%).');
    // the research view adds the rules' range to the same caption
    const research = renderToString(() => <TreeView graph={{ ...graph, questions: [q] }} root="q" research />).replace(/<!--[^>]*-->/g, '');
    expect(research).toContain('first impression 90% · arguments alone 30% · rules agree: 63%');
    // agreeing verdicts are not flagged
    const agree = { ...q, neutralCredence: 0.7, verdictsDisagree: false };
    const calm = renderToString(() => <TreeView graph={{ ...graph, questions: [agree] }} root="q" />);
    expect(calm).toContain('arguments alone 70%');
    expect(calm).not.toContain('hero__prior');
    // the root's facts name its plausibility as the first impression
    const root = { ...graph.nodes[0], plausibility: 0.9 };
    const facts = renderToString(() => <Facts id="f" claim={root} />);
    expect(facts).toContain('First impression');
    expect(facts).not.toContain('Plausible on its own');
  });

  it('model D: the research facts expose first impression versus arguments first for claims and links', () => {
    const claim = {
      ...graph.nodes[1],
      plausibility: 0.8,
      argumentsFirstCredences: { dfquad: 0.61, wlo: 0.72, jnb: 0.69, woe: 0.7, glo: 0.67 },
      argumentsFirstConsensus: 0.7,
    };
    const claimFacts = renderToString(() => <Facts id="claim-facts" claim={claim} research />).replace(/<!--[^>]*-->/g, '');
    expect(claimFacts).toContain('First impression vs arguments');
    expect(claimFacts).toContain('first impression 80% · arguments first 70%');
    expect(claimFacts).toContain('Arguments first by rule');
    expect(claimFacts).toContain('gated log-odds · 67%');

    const link = {
      ...layered.nodes.find((n) => n.kind === 'EDGE')!,
      strength: 0.85,
      argumentsFirstCredences: { dfquad: 0.58, wlo: 0.62, jnb: 0.6, woe: 0.61, glo: 0.57 },
      argumentsFirstConsensus: 0.61,
    };
    const linkFacts = renderToString(() => <Facts id="link-facts" claim={link} link research />).replace(/<!--[^>]*-->/g, '');
    expect(linkFacts).toContain('first impression 85% · arguments first 61%');
    expect(linkFacts).toContain('Arguments first by rule');
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
    expect(legend).toContain('Cost');
    expect(legend).toContain('estimated model spend so far');
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

describe('UI-09 model A: framing', () => {
  const readingsGraph: GraphDto = {
    questions: [
      {
        root: 'q',
        text: 'Do fish sleep?',
        claims: 4,
        active: false,
        framing: {
          mode: 'READINGS',
          term: 'sleep',
          positions: [
            {
              ref: 'p1',
              text: 'Do fish enter a rest state with lowered responsiveness?',
              credence: 0.55,
              firstImpression: 0.9,
              neutralCredence: 0.55,
              verdictsDisagree: false,
            },
            {
              ref: 'p2',
              text: 'Do fish show REM-like brain activity?',
              credence: 0.4,
              firstImpression: 0.1,
              neutralCredence: 0.4,
              verdictsDisagree: true,
            },
          ],
        },
      },
    ],
    nodes: [
      { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q', text: 'Do fish sleep?', depth: 0, status: 'FRAMED', proposer: 'question' },
      {
        ref: 'p1', kind: 'CLAIM', credence: 0.55, root: 'q', text: 'Do fish enter a rest state with lowered responsiveness?',
        depth: 0, positionOf: 'q', status: 'SATURATED', override: 'AUTO', proposer: 'reading', plausibility: 0.9,
      },
      {
        ref: 'p2', kind: 'CLAIM', credence: 0.4, root: 'q', text: 'Do fish show REM-like brain activity?',
        depth: 0, positionOf: 'q', status: 'QUEUED', override: 'AUTO', proposer: 'reading', plausibility: 0.1,
      },
      {
        ref: 'a', kind: 'CLAIM', credence: 0.6, root: 'q', text: 'They stop responding to stimuli at night.',
        depth: 1, status: 'SATURATED', proposer: 'claude',
      },
      { ref: 'a-p1', kind: 'EDGE', credence: 0.6, root: 'q', polarity: 'SUPPORT', source: 'a', target: 'p1', strength: 0.6 },
    ],
  };

  const positionsGraph: GraphDto = {
    questions: [
      {
        root: 'q',
        text: 'How many will attend?',
        claims: 4,
        active: false,
        framing: {
          mode: 'POSITIONS',
          positions: [
            { ref: 'p1', text: 'Under 100.', credence: 0.7, share: 0.7 },
            { ref: 'p2', text: 'Between 100 and 300.', credence: 0.4, share: 0.26 },
            { ref: 'p3', text: 'Over 300.', credence: 0.1, share: 0.04 },
          ],
        },
      },
    ],
    nodes: [
      { ref: 'q', kind: 'CLAIM', credence: 0.5, root: 'q', text: 'How many will attend?', depth: 0, status: 'FRAMED', proposer: 'question' },
      { ref: 'p1', kind: 'CLAIM', credence: 0.7, root: 'q', text: 'Under 100.', depth: 0, positionOf: 'q', status: 'SATURATED', proposer: 'reading' },
      { ref: 'p2', kind: 'CLAIM', credence: 0.4, root: 'q', text: 'Between 100 and 300.', depth: 0, positionOf: 'q', status: 'SATURATED', proposer: 'reading' },
      { ref: 'p3', kind: 'CLAIM', credence: 0.1, root: 'q', text: 'Over 300.', depth: 0, positionOf: 'q', status: 'QUEUED', proposer: 'reading' },
    ],
  };

  it('A-8 READINGS: hides the question gauge, shows the framing line, and one reading section per position', () => {
    const html = renderToString(() => <TreeView graph={readingsGraph} root="q" />);
    const text = html.replace(/<!--[^>]*-->/g, '');
    const readingsAt = text.indexOf('class="readings"');
    const hero = text.slice(text.indexOf('aria-label="Question"'), readingsAt);

    expect(text).toContain('Do fish sleep?');
    expect(hero).toContain('between 40% and 55%, depending on what you mean by sleep');
    expect(hero).not.toContain('gauge__track');
    expect(hero).not.toContain('gauge__fill');

    const sections = [...text.slice(readingsAt).matchAll(/<section[^]*?class="reading"[^]*?<\/section>/g)].map((m) => m[0]);
    expect(sections).toHaveLength(2);
    const [p1Section, p2Section] = sections;
    expect(p1Section).toContain('Do fish enter a rest state with lowered responsiveness?');
    expect(p1Section).toContain('first impression 90% · arguments alone 55%');
    expect(p2Section).toContain('Do fish show REM-like brain activity?');
    expect(p2Section).toContain('first impression 10% · arguments alone 40%');
    // the disagreement note appears only for the second reading
    expect(p1Section).not.toContain('role="note"');
    expect(p2Section).toContain('role="note"');
    expect(p2Section).toContain('The first impression decides the side');
    // a pro under p1 renders inside p1's section, not p2's
    expect(p1Section).toContain('They stop responding to stimuli at night.');
    expect(p2Section).not.toContain('They stop responding to stimuli at night.');
    expect(p1Section).toContain('branch--pro');
    // each reading has its own override control (p1's section also carries its argument's)
    expect((p1Section.match(/aria-label="Exploration override"/g) ?? []).length).toBe(2);
    expect((p2Section.match(/aria-label="Exploration override"/g) ?? []).length).toBe(1);
  });

  it('A-8 READINGS without a term: the heading reads "…, depending on the reading"', () => {
    const noTerm: GraphDto = {
      ...readingsGraph,
      questions: [{ ...readingsGraph.questions[0], framing: { mode: 'READINGS', positions: readingsGraph.questions[0].framing!.positions } }],
    };
    const html = renderToString(() => <TreeView graph={noTerm} root="q" />);
    expect(html.replace(/<!--[^>]*-->/g, '')).toContain('depending on the reading');
  });

  it('A-8 POSITIONS: lists a distribution in order with proportional bars and percentages', () => {
    const html = renderToString(() => <TreeView graph={positionsGraph} root="q" />);
    const text = html.replace(/<!--[^>]*-->/g, '');
    const framing = text.match(/<div[^]*?class="framing"[^]*?<\/div>/)?.[0] ?? '';

    expect(framing).toContain('several possible answers');
    const pcts = [...framing.matchAll(/class="framing__pct"[^>]*>([^<]+)</g)].map((m) => m[1]);
    expect(pcts).toEqual(['70%', '26%', '4%']);
    const rowTexts = [...framing.matchAll(/class="framing__text"[^>]*>([^<]+)</g)].map((m) => m[1]);
    expect(rowTexts).toEqual(['Under 100.', 'Between 100 and 300.', 'Over 300.']);
    const widths = [...framing.matchAll(/scaleX\(([\d.]+)\)/g)].map((m) => Number(m[1]));
    expect(widths).toEqual([0.7, 0.26, 0.04]);
  });

  it('computenet-qkngi: framed hero sums pro/con over every position\'s subtree, not the rootless root', () => {
    const html = renderToString(() => <TreeView graph={readingsGraph} root="q" />);
    const text = html.replace(/<!--[^>]*-->/g, '');
    const readingsAt = text.indexOf('class="readings"');
    const hero = text.slice(text.indexOf('aria-label="Question"'), readingsAt);

    // p1's subtree carries one SUPPORT argument (a-p1); p2's subtree carries none;
    // the root itself has no direct children at all (FRA-02) — the old
    // sideCounts(root) read "0 pro · 0 con" here regardless.
    const pro = hero.match(/class="pro-text"[^>]*>([^<]+)</)?.[1];
    const con = hero.match(/class="con-text"[^>]*>([^<]+)</)?.[1];
    expect(pro).toBe('1 pro');
    expect(con).toBe('0 con');
  });

  it('computenet-qkngi: the ?mock framed question counts each position\'s direct arguments, the level the unframed hero counts', () => {
    // ?mock's framed READINGS question w0 (seedFinished): reading w0p1 has direct args
    // w1 SUPPORT and w2 ATTACK, reading w0p2 has w6 ATTACK. Deeper claims — w3 attacking
    // w1, and w4/w5 on the w1→w0p1 link — are replies to arguments, not to a position,
    // so they are not counted: an unframed hero counts only the root's direct arguments.
    const mock = new MockSource(10);
    mock.start(() => undefined, () => undefined);
    const g = mock.snapshot();
    mock.stop();
    const html = renderToString(() => <TreeView graph={g} root="w0" />);
    const text = html.replace(/<!--[^>]*-->/g, '');
    const hero = text.slice(text.indexOf('aria-label="Question"'), text.indexOf('class="readings"'));
    expect(hero.match(/class="pro-text"[^>]*>([^<]+)</)?.[1]).toBe('1 pro');
    expect(hero.match(/class="con-text"[^>]*>([^<]+)</)?.[1]).toBe('2 con');
    expect(hero).not.toContain('aria-label="Exploration override"');
  });

  it('computenet-qkngi: framed hero has no question-level override control (EXPAND on a framed root runs no round)', () => {
    const html = renderToString(() => <TreeView graph={readingsGraph} root="q" />);
    const text = html.replace(/<!--[^>]*-->/g, '');
    const readingsAt = text.indexOf('class="readings"');
    const hero = text.slice(text.indexOf('aria-label="Question"'), readingsAt);

    expect(hero).not.toContain('aria-label="Exploration override"');
    // each reading still keeps its own control (unaffected by this change).
    expect((text.slice(readingsAt).match(/aria-label="Exploration override"/g) ?? []).length).toBeGreaterThan(0);
  });

  it('an unframed question renders exactly as before (no framing line, no readings block)', () => {
    const unframed: GraphDto = { ...readingsGraph, questions: [{ ...readingsGraph.questions[0], framing: undefined }] };
    const html = renderToString(() => <TreeView graph={unframed} root="q" />);
    expect(html).not.toContain('class="framing"');
    expect(html).not.toContain('class="readings"');
    expect(html).toContain('gauge__track');
  });
});
