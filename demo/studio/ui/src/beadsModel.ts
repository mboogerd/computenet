import type { Config, NodeState } from './types';

export const isBeadsPreset = (preset: unknown): boolean => preset === 'bd-comment' || preset === 'bd-create';
export function beadsPresetConfig(config: Config, preset: string): Config {
  const defaults: Config = preset === 'bd-comment'
    ? { issue: '{{in.issue}}', text: '{{in}}' }
    : { title: '{{in}}', description: '', type: 'task', priority: '2', labels: '' };
  return { workspace: '', ...defaults, ...config, preset, auto: false };
}

export function beadsSummary(state?: NodeState): { count: number | null; added: string[] } {
  let count: number | null = null;
  try {
    const set: unknown = JSON.parse(state?.beads?.set ?? 'null');
    if (Array.isArray(set)) count = set.length;
  } catch { /* A missing/invalid observation must not look like an empty tracker. */ }
  const added = state?.output?.added;
  return { count, added: Array.isArray(added) ? added.filter((id): id is string => typeof id === 'string') : [] };
}
