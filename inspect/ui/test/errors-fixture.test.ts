import { describe, expect, it } from 'vitest';
import type { ErrorSnapshot, InspectEvent, TopologySnapshot } from '../src/api/types';
import { ErrorStore } from '../src/sync/errorStore';
import { deriveEdgeParkedCounts } from '../src/util/errors';
import errorsFixture from '../fixtures/errors.json';
import deadLetterEvent from '../fixtures/error-event-dead-letter.json';
import parkedEvent from '../fixtures/error-event-parked.json';
import restartEvent from '../fixtures/error-event-restart.json';
import waveHealthEvent from '../fixtures/error-event-wave-health.json';
import waveHealthClearedEvent from '../fixtures/error-event-wave-health-cleared.json';
import topology from '../fixtures/topology.json';

const snapshot = errorsFixture as ErrorSnapshot;
const topologySnapshot = topology as TopologySnapshot;

/** M2-FE ticket Implement §1: "Pure TS + unit tests against new fixtures
 *  (`fixtures/errors.json` + one event sample per kind, conforming to the
 *  contract)" — exercised end to end through the real `ErrorStore`, the same
 *  approach `test/fixture.test.ts` (M0/M1) takes for the topology fixture. */
describe('fixtures/errors.json', () => {
  it('counters equal the sum of what the row arrays themselves carry', () => {
    expect(snapshot.counters.deadLetters).toBe(snapshot.deadLetters.length);
    expect(snapshot.counters.restarts).toBe(snapshot.restarts.length);
    expect(snapshot.counters.parked).toBe(snapshot.parked.reduce((sum, p) => sum + p.count, 0));
    expect(snapshot.counters.waveHealth).toBe(snapshot.waveHealth.length);
  });

  it('every row references a ref that is a real node in fixtures/topology.json', () => {
    const refs = new Set(topologySnapshot.nodes.map((n) => n.ref));
    for (const dl of snapshot.deadLetters) expect(refs.has(dl.ref), dl.ref).toBe(true);
    for (const p of snapshot.parked) expect(refs.has(p.ref), p.ref).toBe(true);
    for (const r of snapshot.restarts) expect(refs.has(r.ref), r.ref).toBe(true);
    for (const w of snapshot.waveHealth) expect(refs.has(w.ref), w.ref).toBe(true);
  });

  it("every wave-health row's edge is a real edge in fixtures/topology.json", () => {
    const edgeIds = new Set(topologySnapshot.edges.map((e) => e.id));
    for (const w of snapshot.waveHealth) expect(edgeIds.has(w.edge), w.edge).toBe(true);
  });

  it('loads into ErrorStore, indexed by ref, matching store totals to the snapshot counters', () => {
    const store = new ErrorStore();
    store.applySnapshot(snapshot);
    expect(store.counters).toEqual(snapshot.counters);
    for (const dl of snapshot.deadLetters) {
      expect(store.deadLettersFor(dl.ref)).toContainEqual(dl);
    }
    for (const r of snapshot.restarts) {
      expect(store.restartsFor(r.ref)).toContainEqual(r);
    }
  });

  it("the parked row's (ref, port) resolves to a real inbound edge in the topology fixture", () => {
    const p = snapshot.parked[0];
    const edges = topologySnapshot.edges.filter((e) => e.to.ref === p.ref && e.to.port === p.port);
    expect(edges.length).toBeGreaterThan(0);

    const pills = deriveEdgeParkedCounts(snapshot.parked, topologySnapshot.edges, true);
    for (const e of edges) expect(pills.get(e.id)).toBe(p.count);
  });
});

describe('event fixtures — one per error.* kind', () => {
  it('error-event-dead-letter.json is a well-formed SSE envelope for a node not already erring in the snapshot', () => {
    const event = deadLetterEvent as InspectEvent;
    expect(event.kind).toBe('error.deadLetter');
    if (event.kind !== 'error.deadLetter') throw new Error('unreachable');
    const refs = new Set(topologySnapshot.nodes.map((n) => n.ref));
    expect(refs.has(event.payload.ref)).toBe(true);

    const store = new ErrorStore();
    store.applySnapshot(snapshot);
    const before = store.counters.deadLetters;
    store.applyDeadLetter(event.payload);
    expect(store.counters.deadLetters).toBe(before + 1);
    expect(store.deadLettersFor(event.payload.ref)).toContainEqual(event.payload);
  });

  it('error-event-parked.json updates the existing snapshot row for the same (ref, port)', () => {
    const event = parkedEvent as InspectEvent;
    expect(event.kind).toBe('error.parked');
    if (event.kind !== 'error.parked') throw new Error('unreachable');
    expect(event.payload.ref).toBe(snapshot.parked[0].ref);
    expect(event.payload.port).toBe(snapshot.parked[0].port);

    const store = new ErrorStore();
    store.applySnapshot(snapshot);
    store.applyParked(event.payload);
    expect(store.parkedFor(event.payload.ref)).toEqual([event.payload]);
    expect(store.counters.parked).toBe(event.payload.count);
  });

  it('error-event-restart.json bumps the generation for the same cell the snapshot already has a restart for', () => {
    const event = restartEvent as InspectEvent;
    expect(event.kind).toBe('error.restart');
    if (event.kind !== 'error.restart') throw new Error('unreachable');
    expect(event.payload.ref).toBe(snapshot.restarts[0].ref);
    expect(event.payload.generation).toBeGreaterThan(snapshot.restarts[0].generation);

    const store = new ErrorStore();
    store.applySnapshot(snapshot);
    const before = store.counters.restarts;
    store.applyRestart(event.payload);
    expect(store.counters.restarts).toBe(before + 1);
    expect(store.restartsFor(event.payload.ref)).toHaveLength(2);
  });

  it('error-event-wave-health.json updates the existing open snapshot row for the same id', () => {
    const event = waveHealthEvent as InspectEvent;
    expect(event.kind).toBe('error.waveHealth');
    if (event.kind !== 'error.waveHealth') throw new Error('unreachable');
    expect(event.payload.id).toBe(snapshot.waveHealth[0].id);
    expect(event.payload.state).toBe('open');

    const store = new ErrorStore();
    store.applySnapshot(snapshot);
    store.applyWaveHealth(event.payload);
    expect(store.waveHealthFor(event.payload.ref)).toEqual([event.payload]);
    expect(store.counters.waveHealth).toBe(1);
  });

  it('error-event-wave-health-cleared.json clears the same id the open event named', () => {
    const openEvent = waveHealthEvent as InspectEvent;
    const clearedEvent = waveHealthClearedEvent as InspectEvent;
    expect(clearedEvent.kind).toBe('error.waveHealth');
    if (clearedEvent.kind !== 'error.waveHealth' || openEvent.kind !== 'error.waveHealth') throw new Error('unreachable');
    expect(clearedEvent.payload.id).toBe(openEvent.payload.id);
    expect(clearedEvent.payload.state).toBe('cleared');

    const store = new ErrorStore();
    store.applySnapshot(snapshot);
    store.applyWaveHealth(clearedEvent.payload);
    expect(store.waveHealthFor(clearedEvent.payload.ref)).toEqual([]);
    expect(store.counters.waveHealth).toBe(0);
    expect(store.allWaveHealth()).toEqual([]);
  });
});
