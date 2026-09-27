import { ACTIVE_STATUSES, type NodeDto, type Status } from '../api/types';

export const pct = (x: number | undefined): string => (x === undefined ? '—' : `${Math.round(x * 100)}%`);

export const num = (x: number | undefined): string => (x === undefined ? '—' : x.toFixed(2));

const clamp01 = (x: number): number => (Number.isFinite(x) ? Math.min(1, Math.max(0, x)) : 0);

/** Plain-language status words: what the explorer is doing with the claim. */
export const STATUS_LABEL: Record<Status, string> = {
  QUEUED: 'waiting',
  JUDGING: 'weighing',
  EXPLORING: 'gathering arguments',
  SATURATED: 'fully argued',
  ROUND_LIMIT: 'round limit',
  PRUNED: 'set aside',
  DEPTH_LIMIT: 'depth limit',
  BUDGET: 'budget spent',
  STOPPED: 'stopped by you',
  FAILED: 'failed',
};

export const STATUS_HINT: Record<Status, string> = {
  QUEUED: 'Waiting for its turn to be explored',
  JUDGING: 'Jev is judging whether this claim is worth exploring',
  EXPLORING: 'Claude and Codex are proposing arguments for and against it',
  SATURATED: 'Jev judged both sides complete — nothing important missing',
  ROUND_LIMIT: 'Stopped after the maximum number of rounds',
  PRUNED: 'Judged unlikely to change the answer to the question, so not explored further',
  DEPTH_LIMIT: 'Too far from the question to explore further',
  BUDGET: 'The question reached its claim budget',
  STOPPED: 'You stopped exploring this claim',
  FAILED: 'Every call for this claim failed',
};

/** How a status reads at a glance: still moving, finished, halted early, or broken. */
export type Phase = 'active' | 'done' | 'halted' | 'failed';

export function phaseOf(s: Status | undefined): Phase {
  if (s === undefined || ACTIVE_STATUSES.has(s)) return 'active';
  if (s === 'SATURATED' || s === 'ROUND_LIMIT') return 'done';
  if (s === 'FAILED') return 'failed';
  return 'halted';
}

export interface Verdict {
  /** e.g. "leaning yes". */
  label: string;
  /** -2 … 2: strongly against … strongly for; 0 is balanced. */
  lean: -2 | -1 | 0 | 1 | 2;
  /** "leaning yes · 68%". */
  text: string;
}

const QUESTION_WORDS = ['very likely no', 'leaning no', 'too close to call', 'leaning yes', 'very likely yes'] as const;
const CLAIM_WORDS = ['very unlikely', 'doubtful', 'uncertain', 'plausible', 'very likely'] as const;

/**
 * One-line plain-language reading of a credence. Bands: < 20 %, < 40 %,
 * 40–60 % (inclusive), ≤ 80 %, above. `question` words speak yes/no (the root
 * is a question); claim words speak likelihood.
 */
export function verdict(credence: number, kind: 'question' | 'claim' = 'question'): Verdict {
  const c = clamp01(credence);
  const r = Math.round(c * 100);
  const band = r < 20 ? 0 : r < 40 ? 1 : r <= 60 ? 2 : r <= 80 ? 3 : 4;
  const label = (kind === 'question' ? QUESTION_WORDS : CLAIM_WORDS)[band];
  return { label, lean: (band - 2) as Verdict['lean'], text: `${label} · ${r}%` };
}

/** Plain word for a relation strength (CRED-02). */
export function strengthWord(s: number | undefined): string {
  if (s === undefined) return 'link pending';
  const r = Math.round(clamp01(s) * 100);
  if (r < 25) return 'weak link';
  if (r < 50) return 'modest link';
  if (r < 75) return 'strong link';
  return 'decisive link';
}

/**
 * Visual weight for a claim's reach (EXP-05: product of relation strengths
 * from the root). Reach multiplies down the tree, so it falls fast; the square
 * root keeps depth-2 claims readable while still letting weak branches recede.
 * Returns an opacity-like factor in [0.42, 1]; the root (reach absent at
 * depth 0, or 1) is 1, an argument whose reach is not yet known sits mid-way.
 */
export const REACH_FLOOR = 0.42;

export function reachWeight(reach: number | undefined, isRoot = false): number {
  if (reach === undefined) return isRoot ? 1 : 0.72;
  const w = REACH_FLOOR + (1 - REACH_FLOOR) * Math.sqrt(clamp01(reach));
  return Math.round(w * 100) / 100;
}

/** Coarse tier used for typography: only claims that can move the answer get emphasis. */
export function reachTier(reach: number | undefined): 'high' | 'mid' | 'low' {
  if (reach === undefined) return 'mid';
  return reach >= 0.5 ? 'high' : reach >= 0.2 ? 'mid' : 'low';
}

export interface Progress {
  total: number;
  settled: number;
  active: number;
  /** settled / total, 0 when empty. */
  fraction: number;
}

/** How far a question's exploration has come: claims no longer being worked on. */
export function questionProgress(nodes: readonly NodeDto[], root: string): Progress {
  let total = 0;
  let active = 0;
  for (const n of nodes) {
    if (n.kind !== 'CLAIM' || n.root !== root) continue;
    total++;
    if (phaseOf(n.status) === 'active') active++;
  }
  const settled = total - active;
  return { total, settled, active, fraction: total === 0 ? 0 : settled / total };
}
