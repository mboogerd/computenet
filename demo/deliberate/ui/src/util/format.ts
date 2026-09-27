import { ACTIVE_STATUSES, type Activity, type NodeDto, type QuestionDto, type Status } from '../api/types';

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
  DIMINISHING: 'returns diminished',
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
  DIMINISHING: "Not explored: the question's recent rounds were finding much less new than its earlier ones, so it stopped",
  STOPPED: 'You stopped exploring this claim',
  FAILED: 'Every call for this claim failed',
};

/**
 * Why a question stopped growing early, in plain words ("stopped: returns
 * diminished"), or undefined while it has not (SPEC EXP-06, EXP-10).
 */
export function stoppedText(q: QuestionDto | undefined): string | undefined {
  if (q?.stoppedBy === 'diminishing') return 'stopped: returns diminished';
  if (q?.stoppedBy === 'budget') return 'stopped: claim budget spent';
  return undefined;
}

/** One-line explanation of a stop, for a tooltip. */
export function stoppedHint(q: QuestionDto | undefined): string | undefined {
  if (q?.stoppedBy === 'budget') return STATUS_HINT.BUDGET;
  if (q?.stoppedBy !== 'diminishing') return undefined;
  const recent = q.yieldRecent === undefined ? undefined : q.yieldRecent.toFixed(2);
  const earlier = q.yieldEarlier === undefined ? undefined : q.yieldEarlier.toFixed(2);
  const numbers = recent && earlier ? ` (recent non-root rounds ${recent} vs ${earlier} earlier, per argument asked)` : '';
  return `New non-root rounds were adding much less than earlier ones${numbers}, so queued work was halted`;
}

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
  /** The rounded percentage the band was read from, e.g. 68. */
  percent: number;
  /** "leaning yes · 68%". */
  text: string;
}

const QUESTION_WORDS = ['strong no', 'leaning no', 'too close to call', 'leaning yes', 'strong yes'] as const;
const CLAIM_WORDS = ['very unlikely', 'doubtful', 'uncertain', 'plausible', 'very likely'] as const;

/**
 * One-line plain-language reading of a credence, banded on the rounded
 * percentage the user sees: < 20 strong no, 20–39 leaning no, 40–60 too close
 * to call, 61–80 leaning yes, > 80 strong yes (`lean` is non-zero, i.e. tinted
 * pro/con, only outside 40–60). `question` words speak yes/no (the root is a
 * question); claim words speak likelihood. Show the label next to the number
 * it was computed from, or the two can disagree while a number animates.
 */
export function verdict(credence: number, kind: 'question' | 'claim' = 'question'): Verdict {
  const c = clamp01(credence);
  const r = Math.round(c * 100);
  const band = r < 20 ? 0 : r < 40 ? 1 : r <= 60 ? 2 : r <= 80 ? 3 : 4;
  const label = (kind === 'question' ? QUESTION_WORDS : CLAIM_WORDS)[band];
  return { label, lean: (band - 2) as Verdict['lean'], percent: r, text: `${label} · ${r}%` };
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

const TRIAGE_WORDS: Record<string, string> = {
  ADD: 'added',
  DUPLICATE: 'repeats',
  REPLACE: 'reworded',
  MERGE: 'merged',
  REFINE: 'nested as evidence',
  OTHER_SIDE: 'moved sides',
  UNDERCUT: 'undercut a link',
  DROP: 'dropped',
};

/** "3 added · 1 repeats · 1 merged" from the EXP-03 triage counts, in a fixed order. */
export function triageText(t: Record<string, number> | undefined): string | undefined {
  if (!t) return undefined;
  const parts = Object.keys(TRIAGE_WORDS)
    .filter((k) => (t[k] ?? 0) > 0)
    .map((k) => `${t[k]} ${TRIAGE_WORDS[k]}`);
  return parts.length ? parts.join(' · ') : undefined;
}

/** The credence the UI shows for a node: the consensus of the credence layers (falls back to the primary layer). */
export const shown = (n: NodeDto): number => n.consensus ?? n.credence;

/** The [low, high] credence over every layer; a single point when only one layer runs. */
export function spreadOf(n: NodeDto): { low: number; high: number } {
  const c = shown(n);
  return { low: Math.min(n.spreadLow ?? c, c), high: Math.max(n.spreadHigh ?? c, c) };
}

/** Spreads up to this many percentage points read as agreement. */
export const AGREE_POINTS = 10;

/**
 * Why a node with no arguments has no spread band: every rule starts from the
 * same first impression (the `jev` stance) and has nothing to weigh, so they
 * agree by construction — not a bug, and not evidence of consensus.
 */
export const LEAF_AGREEMENT = 'no arguments yet — all rules agree with the first impression';

/**
 * Plain words for how far the credence rules agree on a node:
 * "rules agree within 6 points", or "rules disagree: 31–72%". A `leaf` (no
 * arguments yet) whose rules coincide says so ({@link LEAF_AGREEMENT}).
 */
export function agreementText(n: NodeDto, leaf = false): string {
  const { low, high } = spreadOf(n);
  const lo = Math.round(clamp01(low) * 100);
  const hi = Math.round(clamp01(high) * 100);
  const width = hi - lo;
  if (leaf && width === 0) return LEAF_AGREEMENT;
  if (width <= AGREE_POINTS) return width === 0 ? 'rules agree' : `rules agree within ${width} point${width === 1 ? '' : 's'}`;
  return `rules disagree: ${lo}–${hi}%`;
}

// ---------- SPEC §3 "Links as claims" ----------

/** A link's claim split into its ends: “argument” is a reason for|against “parent”. */
export interface LinkParts {
  argument: string;
  relation: 'is a reason for' | 'is a reason against';
  parent: string;
}

export function linkParts(text: string | undefined): LinkParts | undefined {
  const m = text === undefined ? null : /^“([\s\S]*)” (is a reason (?:for|against)) “([\s\S]*)”$/.exec(text);
  return m ? { argument: m[1], relation: m[2] as LinkParts['relation'], parent: m[3] } : undefined;
}

/** Where a status reads differently for a link than for a claim. */
export const LINK_STATUS_HINT: Partial<Record<Status, string>> = {
  QUEUED: 'Waiting for its turn: the proposers will be asked whether this argument really bears on the claim',
  EXPLORING: 'Claude and Codex are proposing reasons this link holds, and reasons it fails',
  PRUNED:
    "Not explored: the argument matters little, or Jev's link strength is already clear-cut (near 0 or 1). Expand to explore it anyway",
  SATURATED: 'Jev judged both sides of this link complete',
};

export const statusHint = (s: Status, link = false): string => (link ? LINK_STATUS_HINT[s] : undefined) ?? STATUS_HINT[s];

/** One line of the "now exploring" ticker. */
export interface ActivityItem {
  ref: string;
  /** A claim, or a link (an EDGE explored as a claim). */
  kind: 'claim' | 'link';
  activity: Activity;
  text: string;
}

const ACTIVITY_ORDER: Record<Activity, number> = { exploring: 0, judging: 1, assessing: 2 };

/** Present-tense verbs for the ticker. */
export const ACTIVITY_VERB: Record<Activity, string> = {
  exploring: 'gathering arguments on',
  judging: 'weighing',
  assessing: 'judging new argument',
};

/** What the deliberation of question `root` is doing right now, exploring first, then in snapshot order. */
export function activityOf(nodes: readonly NodeDto[], root: string): ActivityItem[] {
  return nodes
    .filter((n) => n.root === root && n.activity !== undefined)
    .map((n) => ({ ref: n.ref, kind: n.kind === 'EDGE' ? ('link' as const) : ('claim' as const), activity: n.activity!, text: n.text ?? '' }))
    .sort((a, b) => ACTIVITY_ORDER[a.activity] - ACTIVITY_ORDER[b.activity]);
}

/** A ticker entry's text, cut at a word boundary. */
export function clip(text: string, max = 64): string {
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  const space = cut.lastIndexOf(' ');
  return `${(space > max * 0.6 ? cut.slice(0, space) : cut).trimEnd()}…`;
}

/** Plain names of the credence rules (SPEC §2 "Credence layers and consensus"). */
export const LAYER_NAMES: Record<string, string> = {
  dfquad: 'DF-QuAD',
  wlo: 'weighted log-odds',
  jnb: 'Jeffrey / naive Bayes',
  woe: 'weight of evidence',
  euler: 'Euler-based',
  qe: 'quadratic energy',
  mlp: 'MLP-based',
};

/** One line per rule, e.g. "weighted log-odds · 62% · in consensus", in the backend's layer order. */
export function layerLines(n: NodeDto, members: readonly string[]): string[] {
  return Object.entries(n.credences ?? {}).map(
    ([id, c]) => `${LAYER_NAMES[id] ?? id} · ${pct(c)}${members.includes(id) ? ' · in consensus' : ''}`,
  );
}

// ---------- SPEC §12 cost ----------

/**
 * A dollar figure for the cost UI: "$0.84", "<$0.01" for a non-zero amount
 * under a cent, "$0.00" for nothing, whole dollars from $1,000 up.
 */
export function usd(x: number | undefined): string {
  if (x === undefined || !Number.isFinite(x)) return '—';
  if (x <= 0) return '$0.00';
  if (x < 0.01) return '<$0.01';
  if (x < 1000) return `$${x.toFixed(2)}`;
  return `$${Math.round(x).toLocaleString('en-US')}`;
}

/** A token count in short form: 950, 12.6K, 3.4M. */
export function tokens(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)}K`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

/** Plain names for the backends that cost money. */
export const BACKEND_NAMES: Record<string, string> = { claude: 'Claude', codex: 'Codex', jev: 'Jev' };

/** The header figure, including the lower-bound treatment for questions restored from pre-cost records. */
export function costLabel(q: QuestionDto): string {
  if (q.cost?.complete !== false) return usd(q.costUsd ?? 0);
  const hasPricedCalls = q.cost.backends.some((b) => b.usd !== undefined);
  return hasPricedCalls ? `at least ${usd(q.costUsd ?? 0)}` : '—';
}

/** The explicit history caveat required for questions created before tracking existed. */
export function costHistoryText(q: QuestionDto): string | undefined {
  if (q.cost?.complete !== false) return undefined;
  return q.cost.backends.some((b) => b.usd !== undefined)
    ? `${costLabel(q)} (earlier rounds not tracked)`
    : 'cost not tracked for this question (created before cost tracking)';
}

/** "≈$2.10 if the queued claims are explored", or why there is no projection yet. */
export function projectionText(q: QuestionDto | undefined): string {
  const c = q?.cost;
  if (c?.complete === false) return 'Projection unavailable because earlier rounds were not tracked';
  if (q?.projectedUsd === undefined || !c) return 'Projection after 3 completed rounds';
  if (c.queued === 0) return 'Nothing left queued';
  return `≈${usd(q.projectedUsd)} if the ${c.queued} queued claim${c.queued === 1 ? ' is' : 's are'} explored`;
}
