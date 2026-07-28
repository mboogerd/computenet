import { describe, expect, it } from 'vitest';
import { isSearchModeEnabled, SEARCH_MODES } from '../src/nav/search';

/** M4-FE ticket Tests: "Vitest: ... search-mode gating." */
describe('SEARCH_MODES / isSearchModeEnabled', () => {
  it('lists exactly name, problems, data — in that order', () => {
    expect(SEARCH_MODES.map((m) => m.mode)).toEqual(['name', 'problems', 'data']);
  });

  it('name and problems are enabled', () => {
    expect(isSearchModeEnabled('name')).toBe(true);
    expect(isSearchModeEnabled('problems')).toBe(true);
  });

  it('data is disabled and carries the M5 tooltip reason', () => {
    expect(isSearchModeEnabled('data')).toBe(false);
    const data = SEARCH_MODES.find((m) => m.mode === 'data')!;
    expect(data.disabledReason).toBe('arrives in M5');
  });

  it('enabled modes carry no disabledReason', () => {
    for (const m of SEARCH_MODES.filter((m) => m.enabled)) {
      expect(m.disabledReason).toBeUndefined();
    }
  });
});
