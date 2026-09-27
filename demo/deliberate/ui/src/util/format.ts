import type { Status } from '../api/types';

export const pct = (x: number | undefined): string => (x === undefined ? '—' : `${Math.round(x * 100)}%`);

export const num = (x: number | undefined): string => (x === undefined ? '—' : x.toFixed(2));

export const STATUS_LABEL: Record<Status, string> = {
  QUEUED: 'Queued',
  JUDGING: 'Judging',
  EXPLORING: 'Exploring',
  SATURATED: 'Saturated',
  ROUND_LIMIT: 'Round limit',
  PRUNED: 'Pruned',
  DEPTH_LIMIT: 'Depth limit',
  BUDGET: 'Budget',
  STOPPED: 'Stopped',
  FAILED: 'Failed',
};

export const STATUS_HINT: Record<Status, string> = {
  QUEUED: 'Waiting for an explorer slot',
  JUDGING: 'Jev is judging relevance and plausibility',
  EXPLORING: 'Proposers are generating arguments',
  SATURATED: 'Both sides judged saturated',
  ROUND_LIMIT: 'Stopped after the maximum number of rounds',
  PRUNED: 'Judged not relevant enough to the question to expand',
  DEPTH_LIMIT: 'Beyond the maximum depth',
  BUDGET: 'The tree hit its claim budget',
  STOPPED: 'Stopped by you',
  FAILED: 'Every call for this claim failed',
};
