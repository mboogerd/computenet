import { describe, expect, it } from 'vitest';
import type { ErrorSnapshot, GraphList, SearchResult, TopologySnapshot } from '../src/api/types';
import { deriveHealthPills } from '../src/nav/health';
import errorsFixture from '../fixtures/errors.json';
import graphsFixture from '../fixtures/graphs.json';
import searchNameFixture from '../fixtures/search-name.json';
import searchProblemsFixture from '../fixtures/search-problems.json';
import topology from '../fixtures/topology.json';

const graphs = graphsFixture as GraphList;
const errorsSnapshot = errorsFixture as ErrorSnapshot;
const topologySnapshot = topology as TopologySnapshot;
const searchName = searchNameFixture as SearchResult;
const searchProblems = searchProblemsFixture as SearchResult;

/** M4-FE ticket Implement §5: "Fixtures: fixtures/graphs.json,
 *  fixtures/search-*.json per contract" — exercised the same way
 *  `test/errors-fixture.test.ts` (M2-FE) checks its own fixtures: shape
 *  conformance plus cross-references against the other checked-in
 *  fixtures they describe. */
describe('fixtures/graphs.json', () => {
  it('has at least two graphs, one named and one unnamed (manual acceptance: "one unnamed")', () => {
    expect(graphs.graphs.length).toBeGreaterThanOrEqual(2);
    expect(graphs.graphs.some((g) => g.name !== null)).toBe(true);
    expect(graphs.graphs.some((g) => g.name === null)).toBe(true);
  });

  it('every graph id is unique and starts with "g-" (contract: "g-<stable-id>")', () => {
    const ids = graphs.graphs.map((g) => g.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id.startsWith('g-')).toBe(true);
  });

  it('every graph reports non-negative counts and a "hot" lifecycle (contract: cold is M5)', () => {
    for (const g of graphs.graphs) {
      expect(g.cells).toBeGreaterThan(0);
      expect(g.hosts).toBeGreaterThan(0);
      expect(g.nets).toBeGreaterThan(0);
      expect(g.health.deadLetters).toBeGreaterThanOrEqual(0);
      expect(g.health.parked).toBeGreaterThanOrEqual(0);
      expect(g.health.restarts).toBeGreaterThanOrEqual(0);
      expect(g.lifecycle).toBe('hot');
    }
  });

  it('the named "skillmatch" graph carries the same 16-cell count as fixtures/topology.json', () => {
    const named = graphs.graphs.find((g) => g.name === 'skillmatch')!;
    expect(named).toBeDefined();
    expect(named.cells).toBe(topologySnapshot.nodes.length);
  });

  it("the named graph's health matches fixtures/errors.json's counters (same pilot)", () => {
    const named = graphs.graphs.find((g) => g.name === 'skillmatch')!;
    expect(named.health.deadLetters).toBe(errorsSnapshot.counters.deadLetters);
    expect(named.health.parked).toBe(errorsSnapshot.counters.parked);
    expect(named.health.restarts).toBe(errorsSnapshot.counters.restarts);
  });

  it('every graph derives at least the lifecycle health pill without throwing', () => {
    for (const g of graphs.graphs) expect(deriveHealthPills(g.health, g.lifecycle).length).toBeGreaterThan(0);
  });
});

describe('fixtures/search-name.json', () => {
  it('is mode "name" with hits referencing a real graph id from fixtures/graphs.json', () => {
    expect(searchName.mode).toBe('name');
    expect(searchName.cost).toBeNull();
    const ids = new Set(graphs.graphs.map((g) => g.id));
    for (const hit of searchName.hits) expect(ids.has(hit.graph)).toBe(true);
  });

  it("every hit's ref (when present) is a real node in fixtures/topology.json", () => {
    const refs = new Set(topologySnapshot.nodes.map((n) => n.ref));
    for (const hit of searchName.hits) {
      if (hit.ref !== null) expect(refs.has(hit.ref)).toBe(true);
    }
  });
});

describe('fixtures/search-problems.json', () => {
  it('is mode "problems" with hits only for graphs that actually have nonzero health', () => {
    expect(searchProblems.mode).toBe('problems');
    expect(searchProblems.cost).toBeNull();
    const byId = new Map(graphs.graphs.map((g) => [g.id, g] as const));
    for (const hit of searchProblems.hits) {
      const g = byId.get(hit.graph);
      expect(g).toBeDefined();
      expect(g!.health.deadLetters > 0 || g!.health.parked > 0 || g!.health.restarts > 0).toBe(true);
    }
  });
});
