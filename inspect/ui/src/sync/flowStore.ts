import type { FlowRatesPayload, Frontier } from '../api/types';

/** One edge's current reading, as exposed to the rest of the app —
 *  `sync/flowStore.ts`'s own bookkeeping (`missed`) is not part of this
 *  public shape. */
export interface FlowEdgeState {
  id: string;
  rate: number;
  lastWave: Frontier | null;
  hop: number | null;
}

interface Tracked extends FlowEdgeState {
  missed: number;
}

/** M3-FE ticket Implement §1: "an edge absent from a batch decays to zero
 *  after 2 missed windows." The contract's `flow.rates` batch omits
 *  zero-rate edges rather than sending an explicit `rate: 0` row, so the
 *  client has to infer "traffic stopped" from silence. A single missed 1 Hz
 *  batch is ordinary jitter (scheduler tick lag, a dropped SSE frame under
 *  backpressure, etc.) and keeps the edge's last known reading rather than
 *  flickering it away; a second *consecutive* miss is treated as "traffic
 *  actually stopped" and the edge is dropped from the store entirely (band
 *  mapping in `util/flow.ts` then has no rate to render — the edge just
 *  goes quiet). */
export const DECAY_AFTER_MISSED_WINDOWS = 2;

/** The M3 sync seam (20-api-contract.md "flow.rates" SSE event), mirroring
 *  `sync/errorStore.ts`'s shape: no snapshot endpoint exists for flow (see
 *  api/types.ts's M3 section comment) — every "fact" this store holds comes
 *  from a `flow.rates` batch, applied through {@link applyBatch}.
 *  Framework-free — no Solid imports — so it is directly unit-testable. */
export class FlowStore {
  private tracked = new Map<string, Tracked>();
  private subs = new Set<() => void>();

  /** The current reading for one edge id, or `undefined` when the edge has
   *  never reported traffic or has decayed to zero. Strips the internal
   *  `missed` bookkeeping — callers only ever see the public
   *  {@link FlowEdgeState} shape. */
  get(edgeId: string): FlowEdgeState | undefined {
    const t = this.tracked.get(edgeId);
    return t ? { id: t.id, rate: t.rate, lastWave: t.lastWave, hop: t.hop } : undefined;
  }

  /** Every edge with a current (non-decayed) reading — the canvas overlay's
   *  input for iterating active edges without scanning the full edge list. */
  all(): readonly FlowEdgeState[] {
    return [...this.tracked.values()].map(({ id, rate, lastWave, hop }) => ({ id, rate, lastWave, hop }));
  }

  subscribe(fn: () => void): () => void {
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }

  private notify(): void {
    for (const fn of this.subs) fn();
  }

  /** Apply one `flow.rates` SSE batch. Edges named in the batch reset their
   *  miss counter and take the fresh reading; edges tracked from an earlier
   *  batch but absent here age by one window, decaying to zero (removed)
   *  once {@link DECAY_AFTER_MISSED_WINDOWS} consecutive misses accrue. */
  applyBatch(payload: FlowRatesPayload): void {
    const seen = new Set<string>();
    for (const e of payload.edges) {
      seen.add(e.id);
      this.tracked.set(e.id, { id: e.id, rate: e.rate, lastWave: e.lastWave, hop: e.hop, missed: 0 });
    }
    for (const [id, t] of [...this.tracked]) {
      if (seen.has(id)) continue;
      const missed = t.missed + 1;
      if (missed >= DECAY_AFTER_MISSED_WINDOWS) this.tracked.delete(id);
      else this.tracked.set(id, { ...t, missed });
    }
    this.notify();
  }
}
