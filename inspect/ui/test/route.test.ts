import { describe, expect, it } from 'vitest';
import { formatHash, graphIsGone, HOME_ROUTE, parseHash, TOGGLE_KEYS, type GraphRoute, type Route } from '../src/nav/route';

/** M4-FE ticket Tests: "Vitest: hash round-trip (graph/ref/toggles)". */
describe('parseHash', () => {
  it('reads an empty/bare hash as Home', () => {
    expect(parseHash('')).toEqual(HOME_ROUTE);
    expect(parseHash('#')).toEqual(HOME_ROUTE);
    expect(parseHash('#/')).toEqual(HOME_ROUTE);
  });

  it('reads a malformed/stale hash as Home rather than throwing', () => {
    expect(parseHash('#/g/')).toEqual(HOME_ROUTE); // missing graphId
    expect(parseHash('#/nonsense')).toEqual(HOME_ROUTE);
    expect(parseHash('#/x/y/z')).toEqual(HOME_ROUTE); // not the 'g' segment
  });

  it('reads a bare graph hash with no ref/toggles', () => {
    expect(parseHash('#/g/g-abc')).toEqual({ screen: 'graph', graphId: 'g-abc', ref: null, toggles: [] });
  });

  it('reads graphId, ref, and toggles', () => {
    expect(parseHash('#/g/g-abc/uuid%3A0/hosts,flow')).toEqual({
      screen: 'graph',
      graphId: 'g-abc',
      ref: 'uuid:0',
      toggles: ['hosts', 'flow'],
    });
  });

  it('canonicalizes toggle order to TOGGLE_KEYS order regardless of hash order', () => {
    const route = parseHash('#/g/g-abc//errors,hosts') as GraphRoute;
    expect(route.toggles).toEqual(['hosts', 'errors']); // TOGGLE_KEYS order, not hash order
  });

  it('dedupes and drops unrecognized toggle tokens', () => {
    const route = parseHash('#/g/g-abc//hosts,hosts,bogus') as GraphRoute;
    expect(route.toggles).toEqual(['hosts']);
  });

  // V0-FE ticket Problem (a): the net toggle was silently dropped on every
  // hash write/read. TOGGLE_KEYS must include it, and it must round-trip
  // like every other toggle.
  it('reads the net toggle out of the hash', () => {
    const route = parseHash('#/g/g-abc//hosts,net') as GraphRoute;
    expect(route.toggles).toEqual(['hosts', 'net']);
  });

  // Backward compatibility (V0-FE ticket Solution direction (a)): a hash
  // written before this fix — four toggle tokens, never `net` — must still
  // parse without error, with `net` simply absent from `toggles`.
  it('parses an old (pre-net) hash with net simply absent', () => {
    const route = parseHash('#/g/g-abc//hosts,flow,errors,state') as GraphRoute;
    expect(route.toggles).toEqual(['hosts', 'flow', 'errors', 'state']);
    expect(route.toggles).not.toContain('net');
  });
});

describe('formatHash', () => {
  it('formats Home as #/', () => {
    expect(formatHash(HOME_ROUTE)).toBe('#/');
  });

  it('formats a bare graph route with trailing empty segments trimmed', () => {
    expect(formatHash({ screen: 'graph', graphId: 'g-abc', ref: null, toggles: [] })).toBe('#/g/g-abc');
  });

  it('formats graphId and ref percent-encoded', () => {
    const h = formatHash({ screen: 'graph', graphId: 'g-abc', ref: 'uuid:0', toggles: [] });
    expect(h).toBe('#/g/g-abc/uuid%3A0');
  });

  it('formats toggles in canonical TOGGLE_KEYS order, comma-joined', () => {
    const h = formatHash({ screen: 'graph', graphId: 'g-abc', ref: null, toggles: ['errors', 'hosts'] });
    expect(h).toBe('#/g/g-abc//hosts,errors');
  });

  it('formats the net toggle in its TOGGLE_KEYS position, between hosts and flow', () => {
    const h = formatHash({ screen: 'graph', graphId: 'g-abc', ref: null, toggles: ['net', 'hosts'] });
    expect(h).toBe('#/g/g-abc//hosts,net');
  });
});

describe('parseHash(formatHash(route)) round-trips', () => {
  const cases: Route[] = [
    HOME_ROUTE,
    { screen: 'graph', graphId: 'g-abc', ref: null, toggles: [] },
    { screen: 'graph', graphId: 'g-abc', ref: 'uuid:0', toggles: [] },
    { screen: 'graph', graphId: 'g-abc', ref: null, toggles: ['flow'] },
    // V0-FE ticket acceptance criteria: a hash with showNet on round-trips
    // with 'net' present in toggles.
    { screen: 'graph', graphId: 'g-abc', ref: 'uuid:0', toggles: ['net'] },
    { screen: 'graph', graphId: 'g-abc', ref: 'uuid:0', toggles: [...TOGGLE_KEYS] },
    // a ref containing characters that need percent-encoding beyond ':'
    { screen: 'graph', graphId: 'g-weird id/slash', ref: 'a b/c', toggles: ['state'] },
  ];

  for (const route of cases) {
    it(`round-trips ${JSON.stringify(route)}`, () => {
      expect(parseHash(formatHash(route))).toEqual(route);
    });
  }
});

// M4-EVAL fix: a component merging or splitting away while the user is inside
// it is normal operation (ids are `g-<min member uuid>`), and the UI must say
// so rather than render an empty canvas that reads as a bug.
describe('graphIsGone', () => {
  const known = [{ id: 'g-a' }, { id: 'g-b' }];

  it('is false on Home (no graph selected)', () => {
    expect(graphIsGone(null, known, true)).toBe(false);
  });

  it('is false before the graph list has ever loaded, even with an unknown id', () => {
    expect(graphIsGone('g-zzz', [], false)).toBe(false);
  });

  it('is false for an id the loaded list contains', () => {
    expect(graphIsGone('g-b', known, true)).toBe(false);
  });

  it('is true for an id the loaded list no longer contains (merged/split away)', () => {
    expect(graphIsGone('g-zzz', known, true)).toBe(true);
  });

  it('is true when every component merged into one with a different id', () => {
    expect(graphIsGone('g-b', [{ id: 'g-a' }], true)).toBe(true);
  });
});
