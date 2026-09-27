import { renderToString } from 'solid-js/web';
import { describe, expect, it } from 'vitest';
import type { NodeDto, QuestionDto } from '../src/api/types';
import { CostBadge, CostPanel, nextCostPopoverState } from '../src/components/CostBadge';
import { mockCost } from '../src/mock/mockSource';
import { costHistoryText, costLabel, projectionText, tokens, usd } from '../src/util/format';

const question: QuestionDto = {
  root: 'q',
  text: 'Should we?',
  claims: 12,
  active: true,
  costUsd: 0.8412,
  projectedUsd: 2.104,
  cost: {
    complete: true,
    rounds: 6,
    queued: 9,
    perRoundUsd: 0.1402,
    backends: [
      {
        backend: 'claude', models: ['claude-sonnet-5'], calls: 12, inputTokens: 135_600, cachedInputTokens: 40_800,
        cacheWriteTokens: 94_800, outputTokens: 720, reasoningTokens: 0, usd: 0.372, unpricedCalls: 0,
        rate: 'as reported by Claude Code (total_cost_usd per call)', rateSource: 'Claude Code CLI', assumed: false,
        note: 'API-equivalent as reported by Claude Code; not your bill if you use a subscription.',
      },
      {
        backend: 'codex', models: ['gpt-9'], calls: 12, inputTokens: 156_000, cachedInputTokens: 100_800,
        cacheWriteTokens: 0, outputTokens: 1_080, reasoningTokens: 360, unpricedCalls: 12,
        rate: 'rate unknown for gpt-9 — tokens only, left out of the total',
        rateSource: 'set --codex-input-rate, --codex-cached-rate and --codex-output-rate', assumed: true,
      },
      {
        backend: 'jev', models: ['jev-1.13.0'], calls: 42, inputTokens: 18_900, cachedInputTokens: 0, cacheWriteTokens: 0,
        outputTokens: 504, reasoningTokens: 0, usd: 0.0008, unpricedCalls: 0, rate: '$0.042/1M input · output free',
        rateSource: 'third-party: OpenRouter typesafe/jev-1.13, MindStudio', rateDate: '2026-09-27', assumed: true,
      },
    ],
  },
};

const text = (html: string) => html.replace(/<!--[^>]*-->/g, '').replace(/<[^>]+>/g, '').replace(/&lt;/g, '<');

describe('dollar and token formatting (SPEC §12)', () => {
  it('shows cents, marks sub-cent amounts and rounds large ones', () => {
    expect(usd(0)).toBe('$0.00');
    expect(usd(0.004)).toBe('<$0.01');
    expect(usd(0.01)).toBe('$0.01');
    expect(usd(0.8412)).toBe('$0.84');
    expect(usd(12.345)).toBe('$12.35');
    expect(usd(1234.5)).toBe('$1,235');
    expect(usd(undefined)).toBe('—');
  });

  it('shortens token counts', () => {
    expect(tokens(950)).toBe('950');
    expect(tokens(1_234)).toBe('1.2K');
    expect(tokens(135_600)).toBe('136K');
    expect(tokens(3_400_000)).toBe('3.4M');
  });

  it('words the projection, or why there is none yet', () => {
    expect(projectionText(question)).toBe('≈$2.10 if the 9 queued claims are explored');
    expect(projectionText({ ...question, projectedUsd: undefined })).toBe('Projection after 3 completed rounds');
    expect(projectionText({ ...question, cost: { ...question.cost!, queued: 0 } })).toBe('Nothing left queued');
  });

  it('distinguishes untracked history from a real zero and keeps later cost as a lower bound', () => {
    const legacy = { ...question, costUsd: 0, projectedUsd: undefined, cost: { ...question.cost!, complete: false, backends: [] } };
    expect(costLabel(legacy)).toBe('—');
    expect(costHistoryText(legacy)).toBe('cost not tracked for this question (created before cost tracking)');
    expect(projectionText(legacy)).toBe('Projection unavailable because earlier rounds were not tracked');

    const partial = { ...legacy, costUsd: 0.8412, cost: { ...legacy.cost!, backends: question.cost!.backends } };
    expect(costLabel(partial)).toBe('at least $0.84');
    expect(costHistoryText(partial)).toBe('at least $0.84 (earlier rounds not tracked)');
  });
});

describe('cost badge and popover (SPEC §12)', () => {
  it('the header shows only the dollar figure, as a closed popover button', () => {
    const html = renderToString(() => <CostBadge question={question} />);
    expect(html).toContain('<button');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('aria-haspopup="dialog"');
    expect(text(html)).toBe('$0.84');
  });

  it('the popover lists calls, tokens, cost, price source and caveats per backend', () => {
    const t = text(renderToString(() => <CostPanel id="c" question={question} />));
    expect(t).toContain('Estimated cost$0.84');
    expect(t).toContain('≈$2.10 if the 9 queued claims are explored');
    expect(t).toContain('Claude claude-sonnet-5$0.37');
    expect(t).toContain('12 calls · 136K in · 41K cached · 95K cache writes · 720 out');
    expect(t).toContain('not your bill if you use a subscription');
    expect(t).toContain('Codex gpt-9rate unknown');
    expect(t).toContain('360 reasoning');
    expect(t).toContain('Not in the total: 12 Codex calls (no price known).');
    expect(t).toContain('Jev jev-1.13.0<$0.01');
    expect(t).toContain('assumed $0.042/1M input · output free');
    expect(t).toContain('OpenRouter typesafe/jev-1.13, MindStudio · 2026-09-27');
    expect(t).toContain('$0.14 per round over 6 rounds.');
  });

  it('an open badge renders its dialog', () => {
    const html = renderToString(() => <CostBadge question={question} initiallyOpen />);
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-expanded="true"');
  });

  it('toggles on its button and closes on Escape or a click outside', () => {
    expect(nextCostPopoverState(false, 'toggle')).toBe(true);
    expect(nextCostPopoverState(true, 'toggle')).toBe(false);
    expect(nextCostPopoverState(true, 'escape')).toBe(false);
    expect(nextCostPopoverState(true, 'outside')).toBe(false);
  });
});

describe('mock cost', () => {
  const claim = (rounds: number, status: NodeDto['status']): NodeDto => ({ ref: 'x', kind: 'CLAIM', credence: 0.5, root: 'q', rounds, status });

  it('is plausible: cents per round, a projection only after 3 rounds', () => {
    const early = mockCost([claim(2, 'EXPLORING'), claim(0, 'QUEUED')]);
    expect(early.projectedUsd).toBeUndefined();
    expect(early.costUsd!).toBeGreaterThan(0.05);
    expect(early.costUsd!).toBeLessThan(0.5);
    const later = mockCost([claim(3, 'SATURATED'), claim(1, 'ROUND_LIMIT'), claim(0, 'QUEUED'), claim(0, 'QUEUED')]);
    expect(later.cost!.queued).toBe(2);
    expect(later.projectedUsd).toBeCloseTo(later.costUsd! + 2 * (later.costUsd! / 4), 10);
    expect(later.cost!.backends.map((b) => b.backend)).toEqual(['claude', 'codex', 'jev']);
  });
});
