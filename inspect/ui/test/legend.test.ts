import { describe, expect, it } from 'vitest';
import { legendEntries } from '../src/util/legend';

/** V0-FE ticket acceptance criteria: "unit-tested directly (no DOM) for at
 *  least: all toggles off (only the always-on entries appear), each toggle
 *  on individually (its entry appears), and all toggles on." */
describe('legendEntries', () => {
  const ALWAYS_ON_IDS = ['cell-color', 'manifest-badge', 'edge-role'];

  it('shows only the always-on entries when every toggle is off', () => {
    const ids = legendEntries(false, false, false, false, false).map((e) => e.id);
    expect(ids).toEqual(ALWAYS_ON_IDS);
  });

  it('adds the host-hull entry when showHosts alone is on', () => {
    const ids = legendEntries(true, false, false, false, false).map((e) => e.id);
    expect(ids).toEqual([...ALWAYS_ON_IDS, 'host-hull']);
  });

  it('adds the net-hull entry when showNet alone is on', () => {
    const ids = legendEntries(false, true, false, false, false).map((e) => e.id);
    expect(ids).toEqual([...ALWAYS_ON_IDS, 'net-hull']);
  });

  it('adds the edge-flow entry when showFlow alone is on', () => {
    const ids = legendEntries(false, false, true, false, false).map((e) => e.id);
    expect(ids).toEqual([...ALWAYS_ON_IDS, 'edge-flow']);
  });

  it('adds the error-badge entry when showErrors alone is on', () => {
    const ids = legendEntries(false, false, false, true, false).map((e) => e.id);
    expect(ids).toEqual([...ALWAYS_ON_IDS, 'error-badge']);
  });

  it('adds the state-chip entry when showState alone is on', () => {
    const ids = legendEntries(false, false, false, false, true).map((e) => e.id);
    expect(ids).toEqual([...ALWAYS_ON_IDS, 'state-chip']);
  });

  it('shows every entry when all toggles are on', () => {
    const ids = legendEntries(true, true, true, true, true).map((e) => e.id);
    expect(ids).toEqual([...ALWAYS_ON_IDS, 'host-hull', 'net-hull', 'edge-flow', 'error-badge', 'state-chip']);
  });

  it('every entry carries a non-empty label and detail', () => {
    for (const e of legendEntries(true, true, true, true, true)) {
      expect(e.label.length).toBeGreaterThan(0);
      expect(e.detail.length).toBeGreaterThan(0);
    }
  });
});
