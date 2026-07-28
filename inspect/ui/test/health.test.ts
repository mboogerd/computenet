import { describe, expect, it } from 'vitest';
import { deriveHealthPills } from '../src/nav/health';

/** M4-FE ticket Tests: "Vitest: ... card health derivation". */
describe('deriveHealthPills', () => {
  it('omits dead/parked pills when both counters are zero, keeping only lifecycle', () => {
    const pills = deriveHealthPills({ deadLetters: 0, parked: 0, restarts: 0 }, 'hot');
    expect(pills).toEqual([{ kind: 'lifecycle', label: 'hot' }]);
  });

  it('adds a "n dead" pill only when deadLetters > 0', () => {
    const pills = deriveHealthPills({ deadLetters: 2, parked: 0, restarts: 0 }, 'hot');
    expect(pills).toContainEqual({ kind: 'dead', label: '2 dead' });
    expect(pills.some((p) => p.kind === 'parked')).toBe(false);
  });

  it('adds a "n parked" pill only when parked > 0', () => {
    const pills = deriveHealthPills({ deadLetters: 0, parked: 14, restarts: 0 }, 'hot');
    expect(pills).toContainEqual({ kind: 'parked', label: '14 parked' });
    expect(pills.some((p) => p.kind === 'dead')).toBe(false);
  });

  it('orders dead before parked before lifecycle when all are present', () => {
    const pills = deriveHealthPills({ deadLetters: 1, parked: 1, restarts: 1 }, 'hot');
    expect(pills.map((p) => p.kind)).toEqual(['dead', 'parked', 'lifecycle']);
  });

  it('reports the lifecycle pill label verbatim ("hot" or "cold")', () => {
    expect(deriveHealthPills({ deadLetters: 0, parked: 0, restarts: 0 }, 'cold')).toEqual([
      { kind: 'lifecycle', label: 'cold' },
    ]);
  });

  it('restarts alone (no dead/parked) still surfaces only the lifecycle pill — no dedicated restarts pill', () => {
    const pills = deriveHealthPills({ deadLetters: 0, parked: 0, restarts: 5 }, 'hot');
    expect(pills).toEqual([{ kind: 'lifecycle', label: 'hot' }]);
  });
});
