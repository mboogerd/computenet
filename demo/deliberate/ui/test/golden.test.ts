import { describe, expect, it } from 'vitest';
import type { GraphDto, Override, Status } from '../src/api/types';
import { ACTIVITY_VERB, BACKEND_NAMES, STATUS_HINT, STATUS_LABEL } from '../src/util/format';
import { buildForest, countClaims } from '../src/tree/buildTree';
import golden from './fixtures/golden.json';

// Shared with civictech.deliberate.DtoGoldenTest (computenet-dq2fy.24.2): the
// same file pins the Kotlin encoding and, here, that every value the backend
// can send is one the UI's runtime maps/unions actually know about, and that
// the claim-count relationship buildForest/countClaims relies on holds for a
// framed question (SPEC FRA-02: the server's `claims` counts positions and
// their subtrees, which buildForest keeps in a separate `positions` array).
const graph = golden as GraphDto;

const OVERRIDES: readonly Override[] = ['AUTO', 'EXPAND', 'STOP'];
const POLARITIES = ['SUPPORT', 'ATTACK'];
const STOPPED_BY = ['human', 'budget', 'voi'];
const FRAMING_MODES = ['READINGS', 'POSITIONS'];

describe('golden fixture', () => {
  it('every node status is a key of STATUS_LABEL and STATUS_HINT', () => {
    for (const n of graph.nodes) {
      if (n.status === undefined) continue;
      expect(Object.keys(STATUS_LABEL)).toContain(n.status);
      expect(Object.keys(STATUS_HINT)).toContain(n.status);
    }
  });

  it('every node override is AUTO, EXPAND or STOP', () => {
    for (const n of graph.nodes) {
      if (n.override === undefined) continue;
      expect(OVERRIDES).toContain(n.override);
    }
  });

  it('every node activity is a key of ACTIVITY_VERB', () => {
    for (const n of graph.nodes) {
      if (n.activity === undefined) continue;
      expect(Object.keys(ACTIVITY_VERB)).toContain(n.activity);
    }
  });

  it('every edge polarity is SUPPORT or ATTACK', () => {
    for (const n of graph.nodes) {
      if (n.polarity === undefined) continue;
      expect(POLARITIES).toContain(n.polarity);
    }
  });

  it('every question stoppedBy is human, budget or voi', () => {
    for (const q of graph.questions) {
      if (q.stoppedBy === undefined) continue;
      expect(STOPPED_BY).toContain(q.stoppedBy);
    }
  });

  it('every framing mode is READINGS or POSITIONS', () => {
    for (const q of graph.questions) {
      if (q.framing === undefined) continue;
      expect(FRAMING_MODES).toContain(q.framing.mode);
    }
  });

  it('every cost backend is a key of BACKEND_NAMES', () => {
    for (const q of graph.questions) {
      for (const b of q.cost?.backends ?? []) {
        expect(Object.keys(BACKEND_NAMES)).toContain(b.backend);
      }
    }
  });

  it('for each question, buildForest builds and the tree plus its positions account for every claim', () => {
    expect(graph.questions.length).toBeGreaterThan(0);
    for (const q of graph.questions) {
      const forest = buildForest(graph, q.root);
      expect(forest).toBeDefined();
      const positionsTotal = (forest!.positions ?? []).reduce((sum, p) => sum + countClaims(p), 0);
      expect(countClaims(forest!) + positionsTotal).toBe(q.claims);
    }
  });
});
