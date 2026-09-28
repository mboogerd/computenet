import { describe, expect, it } from 'vitest';
import type { NodeDto } from '../src/api/types';
import {
  agreementText,
  layerLines,
  phaseOf,
  STATUS_HINT,
  STATUS_LABEL,
  stoppedHint,
  stoppedText,
  questionProgress,
  reachTier,
  reachWeight,
  REACH_FLOOR,
  shown,
  spreadOf,
  strengthWord,
  triageText,
  verdict,
} from '../src/util/format';
import { nextTheme, resolvedTheme } from '../src/util/theme';
import { easeOut, tweenAt } from '../src/util/tween';

describe('verdict', () => {
  it('reads a question credence as a one-line yes/no lean', () => {
    expect(verdict(0.68).text).toBe('leaning yes · 68%');
    expect(verdict(0.5).text).toBe('too close to call · 50%');
    expect(verdict(0.3).label).toBe('leaning no');
    expect(verdict(0.05).label).toBe('strong no');
    expect(verdict(0.93).label).toBe('strong yes');
    // the live defect: 62% is a lean, not a toss-up
    expect(verdict(0.62).text).toBe('leaning yes · 62%');
    expect(verdict(0.38).text).toBe('leaning no · 38%');
  });

  it('puts band edges on the rounded percentage the user sees', () => {
    expect(verdict(0.6).lean).toBe(0); // 60% is still balanced
    expect(verdict(0.604).lean).toBe(0); // shows 60%
    expect(verdict(0.606).lean).toBe(1); // shows 61%
    expect(verdict(0.4).lean).toBe(0);
    expect(verdict(0.394).lean).toBe(-1);
    expect(verdict(0.8).lean).toBe(1);
    expect(verdict(0.804).label).toBe('leaning yes'); // shows 80%
    expect(verdict(0.806).label).toBe('strong yes'); // shows 81%
    expect(verdict(0.2).lean).toBe(-1);
    expect(verdict(0.19).lean).toBe(-2);
    expect(verdict(0.19).label).toBe('strong no');
  });

  it('speaks likelihood for claims and clamps junk input', () => {
    expect(verdict(0.75, 'claim').text).toBe('plausible · 75%');
    expect(verdict(0.1, 'claim').label).toBe('very unlikely');
    expect(verdict(1.7).text).toBe('strong yes · 100%');
    expect(verdict(Number.NaN).text).toBe('strong no · 0%');
  });
});

describe('reach → visual weight', () => {
  it('keeps the root and full-reach claims at full weight', () => {
    expect(reachWeight(undefined, true)).toBe(1);
    expect(reachWeight(1)).toBe(1);
  });

  it('decreases monotonically and never drops below the floor', () => {
    const ws = [1, 0.8, 0.5, 0.25, 0.1, 0.01, 0].map((r) => reachWeight(r));
    for (let i = 1; i < ws.length; i++) expect(ws[i]).toBeLessThan(ws[i - 1]);
    expect(reachWeight(0)).toBe(REACH_FLOOR);
    expect(reachWeight(-3)).toBe(REACH_FLOOR);
  });

  it('keeps a typical depth-2 claim readable (sqrt softening)', () => {
    // 0.7 × 0.6 = 0.42 reach → still well above the floor
    expect(reachWeight(0.42)).toBeGreaterThanOrEqual(0.75);
  });

  it('places unknown reach mid-way and tiers typography', () => {
    expect(reachWeight(undefined)).toBeGreaterThan(REACH_FLOOR);
    expect(reachWeight(undefined)).toBeLessThan(1);
    expect(reachTier(0.6)).toBe('high');
    expect(reachTier(0.3)).toBe('mid');
    expect(reachTier(0.05)).toBe('low');
    expect(reachTier(undefined)).toBe('mid');
  });
});

describe('strength and status wording', () => {
  it('names relation strengths in plain words', () => {
    expect(strengthWord(undefined)).toBe('link pending');
    expect(strengthWord(0.1)).toBe('weak link');
    expect(strengthWord(0.4)).toBe('modest link');
    expect(strengthWord(0.6)).toBe('strong link');
    expect(strengthWord(0.9)).toBe('decisive link');
  });

  it('groups statuses into phases', () => {
    expect(phaseOf('JUDGING')).toBe('active');
    expect(phaseOf('EXPLORING')).toBe('active');
    expect(phaseOf('QUEUED')).toBe('active');
    expect(phaseOf('SATURATED')).toBe('done');
    expect(phaseOf('ROUND_LIMIT')).toBe('done');
    expect(phaseOf('PRUNED')).toBe('halted');
    expect(phaseOf('STOPPED')).toBe('halted');
    expect(phaseOf('FAILED')).toBe('failed');
    expect(phaseOf('DIMINISHING')).toBe('halted');
  });

  it('words the value-of-information stop for claims and questions (model C)', () => {
    expect(STATUS_LABEL.DIMINISHING).toBe('not worth exploring');
    expect(STATUS_HINT.DIMINISHING).toContain('value of information');
    const q = { root: 'q', text: 'Q?', claims: 60, active: false };
    expect(stoppedText(q)).toBeUndefined();
    expect(stoppedText(undefined)).toBeUndefined();
    expect(stoppedText({ ...q, stoppedBy: 'budget' })).toBe('stopped: claim budget spent');
    expect(stoppedText({ ...q, stoppedBy: 'voi' })).toBe('stopped: nothing left could change the answer');
    expect(stoppedHint({ ...q, stoppedBy: 'voi' })).toContain('value of information');
    expect(stoppedHint(q)).toBeUndefined();
  });
});

describe('questionProgress', () => {
  const claim = (ref: string, root: string, status: NodeDto['status']): NodeDto => ({ ref, root, kind: 'CLAIM', credence: 0.5, status });
  it('counts settled claims of one question only, ignoring edges', () => {
    const nodes: NodeDto[] = [
      claim('q', 'q', 'SATURATED'),
      claim('a', 'q', 'EXPLORING'),
      claim('b', 'q', 'PRUNED'),
      claim('c', 'q', 'QUEUED'),
      claim('x', 'other', 'EXPLORING'),
      { ref: 'e', root: 'q', kind: 'EDGE', credence: 0.5 },
    ];
    expect(questionProgress(nodes, 'q')).toEqual({ total: 4, settled: 2, active: 2, fraction: 0.5 });
    expect(questionProgress([], 'q').fraction).toBe(0);
  });
});

describe('theme toggle', () => {
  it('flips the visible scheme on every click and returns to following the OS', () => {
    for (const systemDark of [false, true]) {
      let pref = 'system' as ReturnType<typeof nextTheme>;
      const seen: string[] = [resolvedTheme(pref, systemDark)];
      for (let i = 0; i < 4; i++) {
        pref = nextTheme(pref, systemDark);
        seen.push(resolvedTheme(pref, systemDark));
      }
      for (let i = 1; i < seen.length; i++) expect(seen[i]).not.toBe(seen[i - 1]);
      expect(nextTheme(nextTheme('system', systemDark), systemDark)).toBe('system');
    }
  });
});

describe('tween easing', () => {
  it('starts and lands exactly, and is clamped', () => {
    expect(tweenAt(0.2, 0.8, 0)).toBeCloseTo(0.2);
    expect(tweenAt(0.2, 0.8, 1)).toBeCloseTo(0.8);
    expect(tweenAt(0.2, 0.8, 2)).toBeCloseTo(0.8);
    expect(easeOut(0.5)).toBeGreaterThan(0.5); // ease-out: most of the move happens early
  });
});

describe('triage wording', () => {
  it('lists the non-zero triage counts in plain words, in a fixed order', () => {
    expect(triageText(undefined)).toBeUndefined();
    expect(triageText({})).toBeUndefined();
    expect(triageText({ DROP: 1, ADD: 3, MERGE: 1, DUPLICATE: 0 })).toBe('3 added · 1 merged · 1 dropped');
  });
});

describe('credence layers', () => {
  const node = (patch: Partial<NodeDto>): NodeDto => ({ ref: 'n', kind: 'CLAIM', credence: 0.4, root: 'n', ...patch });

  it('shows the consensus, falling back to the primary credence', () => {
    expect(shown(node({ consensus: 0.62 }))).toBe(0.62);
    expect(shown(node({}))).toBe(0.4);
    expect(spreadOf(node({ consensus: 0.62, spreadLow: 0.31, spreadHigh: 0.72 }))).toEqual({ low: 0.31, high: 0.72 });
    // the band always contains the number it is drawn behind
    expect(spreadOf(node({}))).toEqual({ low: 0.4, high: 0.4 });
  });

  it('says in plain words how far the rules agree', () => {
    expect(agreementText(node({ consensus: 0.5, spreadLow: 0.47, spreadHigh: 0.53 }))).toBe('rules agree within 6 points');
    expect(agreementText(node({ consensus: 0.5, spreadLow: 0.5, spreadHigh: 0.51 }))).toBe('rules agree within 1 point');
    expect(agreementText(node({ consensus: 0.5, spreadLow: 0.5, spreadHigh: 0.5 }))).toBe('rules agree');
    expect(agreementText(node({ consensus: 0.5, spreadLow: 0.45, spreadHigh: 0.55 }))).toBe('rules agree within 10 points');
    expect(agreementText(node({ consensus: 0.55, spreadLow: 0.31, spreadHigh: 0.72 }))).toBe('rules disagree: 31–72%');
  });

  it('lists every rule and marks the consensus members', () => {
    const n = node({ credences: { dfquad: 0.41, wlo: 0.62, mlp: 0.7 } });
    expect(layerLines(n, ['wlo', 'jnb', 'woe'])).toEqual([
      'DF-QuAD · 41%',
      'weighted log-odds · 62% · in consensus',
      'MLP-based · 70%',
    ]);
  });

  it('names the UNDERCUT triage action', () => {
    expect(triageText({ ADD: 1, UNDERCUT: 2 })).toBe('1 added · 2 undercut a link');
    expect(triageText({ OTHER_SIDE: 1 }, true)).toBe('1 moved to parent');
  });
});
