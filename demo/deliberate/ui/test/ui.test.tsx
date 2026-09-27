import { readFileSync } from 'node:fs';
import { renderToString } from 'solid-js/web';
import { describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../src/api/types';
import { QuestionInput } from '../src/components/QuestionInput';
import { TreeView } from '../src/components/TreeView';

vi.mock('../src/sync/store', () => ({
  source: {
    ask: vi.fn(),
    override: vi.fn().mockResolvedValue(undefined),
  },
}));

const graph: GraphDto = {
  questions: [{ root: 'q', text: 'Should we?', claims: 2, active: true }],
  nodes: [
    {
      ref: 'q', kind: 'CLAIM', credence: 0.63, root: 'q', text: 'Should we?', depth: 0,
      status: 'EXPLORING', override: 'AUTO', proposer: 'question',
    },
    {
      ref: 'a', kind: 'CLAIM', credence: 0.75, root: 'q', text: 'It would help.', depth: 1,
      status: 'SATURATED', override: 'EXPAND', proposer: 'claude',
    },
    {
      ref: 'a-q', kind: 'EDGE', credence: 0.8, root: 'q', polarity: 'SUPPORT',
      source: 'a', target: 'q', strength: 0.8,
    },
  ],
};

describe('SPEC UI contract', () => {
  it('UI-01 renders one labelled question input and submit action', () => {
    const html = renderToString(() => <QuestionInput onAsked={() => undefined} />);
    expect(html.match(/<input\b/g)).toHaveLength(1);
    expect(html).toContain('for="ask-input"');
    expect(html).toContain('type="submit"');
    expect(html).toContain('Deliberate');
  });

  it('UI-02/UI-03 renders a rooted tree with all claim and argument facts', () => {
    const html = renderToString(() => <TreeView graph={graph} root="q" />);
    expect(html).toContain('aria-label="Deliberation tree"');
    expect(html).toContain('Should we?');
    expect(html).toContain('It would help.');
    expect(html).toContain('63%');
    expect(html).toContain('Exploring');
    expect(html).toContain('claude');
    expect(html).toContain('aria-label="Exploration override"');
    expect(html).toContain('branch--pro');
    expect(html).toContain('Pro');
    expect(html).toContain('strength 80%');
  });

  it('UI-04 keeps neutral chrome, distinct pro/con colour, motion, and dark mode', () => {
    const tokensCss = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8');
    const appCss = readFileSync(new URL('../src/styles/app.css', import.meta.url), 'utf8');

    expect(tokensCss).toContain('--page: #f6f6f4');
    expect(tokensCss).toMatch(/--pro:\s*#[0-9a-f]+/i);
    expect(tokensCss).toMatch(/--con:\s*#[0-9a-f]+/i);
    expect(tokensCss).toContain('@media (prefers-color-scheme: dark)');
    expect(appCss).toContain('.branch--pro');
    expect(appCss).toContain('.branch--con');
    expect(appCss).toContain('animation: enter');
    expect(appCss).toContain('@keyframes enter');
    expect(appCss).toContain('@media (prefers-reduced-motion: reduce)');
  });
});
