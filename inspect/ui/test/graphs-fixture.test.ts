import { describe, expect, it } from 'vitest';
import type { ErrorSnapshot, GraphList, SearchResult, TopologySnapshot } from '../src/api/types';
import { coldGraphCount, formatColdSkipHint, isGraphCold } from '../src/nav/cold';
import { deriveHealthPills } from '../src/nav/health';
import { formatSearchCost, isNoticeHit } from '../src/nav/search';
import errorsFixture from '../fixtures/errors.json';
import graphsFixture from '../fixtures/graphs.json';
import graphsColdFixture from '../fixtures/graphs-cold.json';
import searchDataColdFixture from '../fixtures/search-data-cold.json';
import searchDataFixture from '../fixtures/search-data.json';
import searchNameFixture from '../fixtures/search-name.json';
import searchProblemsFixture from '../fixtures/search-problems.json';
import topology from '../fixtures/topology.json';
import topologyMultihost from '../fixtures/topology-multihost.json';

const graphs = graphsFixture as GraphList;
const errorsSnapshot = errorsFixture as ErrorSnapshot;
const topologySnapshot = topology as TopologySnapshot;
const searchName = searchNameFixture as SearchResult;
const searchProblems = searchProblemsFixture as SearchResult;
const searchData = searchDataFixture as SearchResult;
const graphsCold = graphsColdFixture as GraphList;
const searchDataCold = searchDataColdFixture as SearchResult;
const multihost = topologyMultihost as TopologySnapshot;

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

/** M5-SEARCH ticket Implement §1-2. Captured from a live `?mode=data&q=alice`
 *  against the skillmatch pilot (18 cells queried across the two components the
 *  pilot runs) and re-keyed onto this fixture set's own ids, the same way
 *  `search-name.json` was. */
describe('fixtures/search-data.json', () => {
  it('is mode "data" and — unlike name/problems — always carries a cost', () => {
    expect(searchData.mode).toBe('data');
    expect(searchData.cost).not.toBeNull();
    expect(formatSearchCost(searchData.cost)).toBe('queried 18 cells · 0 cold skipped');
  });

  it('every navigable hit names a real graph and a real node of fixtures/topology.json', () => {
    const ids = new Set(graphs.graphs.map((g) => g.id));
    const refs = new Set(topologySnapshot.nodes.map((n) => n.ref));
    const navigable = searchData.hits.filter((h) => !isNoticeHit(h));
    expect(navigable.length).toBeGreaterThan(0);
    for (const hit of navigable) {
      expect(ids.has(hit.graph)).toBe(true);
      // a data hit always points at the cell holding the record
      expect(hit.ref).not.toBeNull();
      expect(refs.has(hit.ref!)).toBe(true);
    }
  });

  it("every hit's detail follows the server's 'graph / cell · type — n record(s)' shape", () => {
    for (const hit of searchData.hits.filter((h) => !isNoticeHit(h))) {
      expect(hit.detail).toMatch(/^.+ \/ .+ · .+ — \d+ records?$/u);
    }
  });

  it('carries exactly one closing notice, last, with no ref to navigate to', () => {
    const notices = searchData.hits.filter(isNoticeHit);
    expect(notices).toHaveLength(1);
    expect(searchData.hits[searchData.hits.length - 1]).toBe(notices[0]);
    expect(notices[0].ref).toBeNull();
  });
});

/** M5-COLD. Captured from a live `--cold-graph` run of the skillmatch pilot
 *  (its unnamed side component started suspended) and re-keyed onto this
 *  fixture set's own ids, the same way `search-data.json` was. Kept as a
 *  *second* graph list rather than by editing `graphs.json`: the golden one is
 *  the all-hot pilot every other fixture cross-references, and both states
 *  need to exist offline. */
describe('fixtures/graphs-cold.json', () => {
  it('is the same two components as graphs.json, with the unnamed one parked', () => {
    expect(graphsCold.graphs.map((g) => g.id)).toEqual(graphs.graphs.map((g) => g.id));
    expect(graphsCold.graphs.filter((g) => g.lifecycle === 'cold').map((g) => g.name)).toEqual([null]);
  });

  it('drives the cold predicates the screen and the card dimming read', () => {
    const cold = graphsCold.graphs.find((g) => g.lifecycle === 'cold')!;
    expect(coldGraphCount(graphsCold.graphs)).toBe(1);
    expect(isGraphCold(cold.id, graphsCold.graphs)).toBe(true);
    expect(isGraphCold(cold.id, graphs.graphs)).toBe(false); // same id, hot list
  });

  it('reports "cold" as its lifecycle health pill', () => {
    const cold = graphsCold.graphs.find((g) => g.lifecycle === 'cold')!;
    expect(deriveHealthPills(cold.health, cold.lifecycle)).toContainEqual({ kind: 'lifecycle', label: 'cold' });
  });
});

/** The data search from that same cold run: the cold component's cells are
 *  counted, not read, and the closing notice names the remedy. */
describe('fixtures/search-data-cold.json', () => {
  it('reports a nonzero coldSkipped and the hint that offers to include it', () => {
    expect(searchDataCold.cost!.coldSkipped).toBe(2);
    expect(formatColdSkipHint(searchDataCold.cost)).toBe('2 cold cells skipped — parked or held cells are not searched');
  });

  it('queried fewer cells than the all-hot capture, by exactly the skipped ones', () => {
    expect(searchDataCold.cost!.cellsQueried + searchDataCold.cost!.coldSkipped).toBe(searchData.cost!.cellsQueried);
  });

  it('closes with a scope notice naming the cold graph, not a failure', () => {
    const notice = searchDataCold.hits.filter(isNoticeHit);
    expect(notice).toHaveLength(1);
    expect(notice[0].label).toBe('Search scope');
    expect(notice[0].detail).toContain('cold graph skipped — wake to include');
  });
});

// M4-EVAL fix: both topology fixtures still carried `"graph": null` on every
// node, which the M4 server can no longer emit — `Node.graph` is non-null for
// every published cell, since an unlinked cell is a component of one. Left
// alone, the fixtures would have taught the offline dev loop (and every test
// reading them) a shape the real backend never produces again.
describe('topology fixtures carry a component id on every node (M4)', () => {
  for (const [label, snapshot] of [
    ['topology.json', topologySnapshot],
    ['topology-multihost.json', multihost],
  ] as const) {
    it(`${label}: every node has a non-null "g-" graph id`, () => {
      for (const n of snapshot.nodes) {
        expect(n.graph, `${label} node ${n.ref}`).not.toBeNull();
        expect(n.graph!.startsWith('g-')).toBe(true);
      }
    });

    it(`${label}: it is one connected component, named by its lexicographically-min member uuid`, () => {
      const ids = new Set(snapshot.nodes.map((n) => n.graph));
      expect(ids.size).toBe(1);
      const min = snapshot.nodes.map((n) => n.ref.split(':')[0]).sort()[0];
      expect([...ids][0]).toBe(`g-${min}`);
    });
  }

  it("topology.json's component id is the one graphs.json calls \"skillmatch\"", () => {
    const named = graphs.graphs.find((g) => g.name === 'skillmatch')!;
    expect(topologySnapshot.nodes[0].graph).toBe(named.id);
    expect(named.cells).toBe(topologySnapshot.nodes.length);
  });
});
