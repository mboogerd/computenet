import { describe, expect, it } from 'vitest';
import type { FlowRatesEvent, InspectEvent, TopologySnapshot } from '../src/api/types';
import { FlowStore } from '../src/sync/flowStore';
import { deriveEdgeFlowOverlays, rateBand } from '../src/util/flow';
import flowFixture from '../fixtures/flow-rates.json';
import topology from '../fixtures/topology.json';

/** M3-FE ticket Implement §1: "Unit tests against fixtures/flow-rates.json
 *  (create per contract)." A `flow.rates` batch has no snapshot endpoint to
 *  pair with (see api/types.ts's M3 section comment), so — unlike
 *  `fixtures/errors.json` — this fixture is itself a *sequence* of three
 *  SSE envelopes (one per 1 Hz window), exercised end to end through the
 *  real `FlowStore` exactly the way `test/errors-fixture.test.ts` exercises
 *  `ErrorStore`. The sequence is deliberately built to exercise the ticket's
 *  own decay rule: batch 1 reports two edges; batch 2 drops the second
 *  (1st, grace-period miss); batch 3 drops it again (2nd consecutive miss —
 *  decays to zero). */
const events = flowFixture as FlowRatesEvent[];
const topologySnapshot = topology as TopologySnapshot;

const TRACKED_EDGE = '327ab252-b906-4236-9194-9b7e2f7b7b60'; // reports in every batch
const DECAYING_EDGE = '3c1e56b2-8d06-4307-a31f-1bef6ad566f5'; // reports once, then goes silent

describe('fixtures/flow-rates.json', () => {
  it('is three flow.rates envelopes in ascending seq order', () => {
    expect(events).toHaveLength(3);
    expect(events.every((e) => e.kind === 'flow.rates')).toBe(true);
    expect(events.map((e) => e.seq)).toEqual([601, 602, 603]);
  });

  it('every edge id named in the fixture is a real edge in fixtures/topology.json', () => {
    const edgeIds = new Set(topologySnapshot.edges.map((e) => e.id));
    for (const ev of events) {
      for (const e of ev.payload.edges) expect(edgeIds.has(e.id), e.id).toBe(true);
    }
  });

  it('both tracked edges share the same source cell/port — exercises "sum of that port\'s edges"', () => {
    const byId = new Map(topologySnapshot.edges.map((e) => [e.id, e]));
    const a = byId.get(TRACKED_EDGE)!;
    const b = byId.get(DECAYING_EDGE)!;
    expect(a.from.ref).toBe(b.from.ref);
    expect(a.from.port).toBe(b.from.port);
  });

  it('applies sequentially into a real FlowStore, reproducing the decay-to-zero rule end to end', () => {
    const store = new FlowStore();

    store.applyBatch(events[0].payload);
    expect(store.get(TRACKED_EDGE)?.rate).toBe(12.5);
    expect(store.get(DECAYING_EDGE)?.rate).toBe(2);

    store.applyBatch(events[1].payload); // DECAYING_EDGE absent — 1st miss, grace
    expect(store.get(TRACKED_EDGE)?.rate).toBe(30);
    expect(store.get(DECAYING_EDGE)?.rate).toBe(2); // still the last-known reading

    store.applyBatch(events[2].payload); // DECAYING_EDGE absent again — decays
    expect(store.get(TRACKED_EDGE)?.rate).toBe(31);
    expect(store.get(DECAYING_EDGE)).toBeUndefined();
  });

  it('band mapping over the fixture\'s own rates matches the documented thresholds', () => {
    expect(rateBand(events[0].payload.edges[0].rate)).toBe(2); // 12.5 -> band 2
    expect(rateBand(events[0].payload.edges[1].rate)).toBe(1); // 2.0 -> band 1
    expect(rateBand(events[1].payload.edges[0].rate)).toBe(3); // 30.0 -> band 3
  });

  it('after full decay, deriveEdgeFlowOverlays (toggle on) reports only the surviving edge', () => {
    const store = new FlowStore();
    for (const ev of events) store.applyBatch(ev.payload);

    const targets = topologySnapshot.edges.map((e) => ({ id: e.id, fused: e.fused }));
    const overlays = deriveEdgeFlowOverlays(targets, (id) => store.get(id), true);

    expect(overlays.get(TRACKED_EDGE)).toEqual({
      kind: 'active',
      rate: 31,
      band: 3,
      lastWave: { source: '9c41a2f0', counter: 290 },
      hop: 1,
    });
    expect(overlays.has(DECAYING_EDGE)).toBe(false);
  });

  it('every event fixture also parses as a well-formed InspectEvent (client-shape sanity)', () => {
    for (const ev of events) {
      const event = ev as InspectEvent;
      expect(event.kind).toBe('flow.rates');
    }
  });
});
