import { describe, expect, it } from 'vitest';
import { beadsPresetConfig, beadsSummary } from '../src/beadsModel';
import type { NodeState } from '../src/types';

describe('Beads settings and card', () => {
  it.each(['bd-comment', 'bd-create'])('selecting %s disables inherited automatic runs', preset => {
    const config = beadsPresetConfig({ preset: 'custom', auto: true, workspace: '/scratch/beads' }, preset);
    expect(config.auto).toBe(false);
    expect(config.preset).toBe(preset);
    expect(config.workspace).toBe('/scratch/beads');
    expect(preset === 'bd-comment' ? config.text : config.title).toBe('{{in}}');
  });
  it('shows the silent baseline count and the latest added ids', () => {
    const state = { beads: { watch: '', set: '[{"id":"a"},{"id":"b"}]' }, output: null } as NodeState;
    expect(beadsSummary(state)).toEqual({ count: 2, added: [] });
    state.output = { old: '[]', new: state.beads!.set, added: ['a', 'b'], removed: [], changed: [] };
    expect(beadsSummary(state)).toEqual({ count: 2, added: ['a', 'b'] });
  });
  it('distinguishes unobserved or invalid data from an empty tracker', () => {
    expect(beadsSummary()).toEqual({ count: null, added: [] });
    for (const set of ['invalid', '{}', 'null']) expect(beadsSummary({ beads: { watch: '', set } } as NodeState).count).toBeNull();
    expect(beadsSummary({ beads: { watch: '', set: '[]' } } as NodeState).count).toBe(0);
  });
});
