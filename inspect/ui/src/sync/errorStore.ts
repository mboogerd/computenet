import type { DeadLetterEntry, ErrorCounters, ErrorSnapshot, ParkedEntry, Ref, RestartEntry } from '../api/types';

const EMPTY_COUNTERS: ErrorCounters = { deadLetters: 0, parked: 0, restarts: 0, drainedOnTeardown: 0 };

/** The M2 sync seam (20-api-contract.md "ErrorSnapshot (M2)", "error.*
 *  events"), mirroring `sync/store.ts`'s shape: fetch the snapshot once,
 *  then apply each SSE delta as it arrives. Framework-free — no Solid
 *  imports — so it is directly unit-testable, exactly like `TopologyStore`.
 *
 *  Two different update disciplines coexist here, both taken straight from
 *  the contract:
 *   - `deadLetters`/`restarts` are append-only occurrence logs — each event
 *     is one more thing that happened, so `counters.deadLetters`/`restarts`
 *     increment by exactly one per event, mirroring the server's own
 *     `supervisionAccounting()` counter it was copied from.
 *   - `parked` is current-state, not a log — "send on change; `count: 0`
 *     clears" (contract). `counters.parked` is therefore recomputed as the
 *     live sum over every tracked (ref, port) row after each update, so it
 *     can never drift from what `parkedFor()` actually reports.
 *
 *  Indexed by ref throughout (M2-FE ticket Implement §1: "index by ref") so
 *  the detail panel's Errors subsection (per-cell) and the canvas overlay
 *  (per-cell badge, per-edge parked pill) can both read in O(1) per node
 *  without scanning the whole snapshot on every render. */
export class ErrorStore {
  private _counters: ErrorCounters = EMPTY_COUNTERS;
  private _deadLetters = new Map<Ref, DeadLetterEntry[]>();
  /** ref -> port -> current parked row (count > 0 only; a clear deletes the entry). */
  private _parked = new Map<Ref, Map<string, ParkedEntry>>();
  private _restarts = new Map<Ref, RestartEntry[]>();
  private subs = new Set<() => void>();

  get counters(): ErrorCounters {
    return this._counters;
  }

  deadLettersFor(ref: Ref): readonly DeadLetterEntry[] {
    return this._deadLetters.get(ref) ?? [];
  }

  parkedFor(ref: Ref): readonly ParkedEntry[] {
    const m = this._parked.get(ref);
    return m ? [...m.values()] : [];
  }

  restartsFor(ref: Ref): readonly RestartEntry[] {
    return this._restarts.get(ref) ?? [];
  }

  /** Every currently-parked row across every cell — the canvas overlay's
   *  input for mapping parked ports onto edges (see `util/errors.ts`). */
  allParked(): readonly ParkedEntry[] {
    const out: ParkedEntry[] = [];
    for (const m of this._parked.values()) out.push(...m.values());
    return out;
  }

  subscribe(fn: () => void): () => void {
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }

  private notify(): void {
    for (const fn of this.subs) fn();
  }

  /** Replace the whole known world (initial `GET /errors`, or a later
   *  refetch — see `solid/errors.ts`). */
  applySnapshot(snapshot: ErrorSnapshot): void {
    this._counters = snapshot.counters;

    const deadLetters = new Map<Ref, DeadLetterEntry[]>();
    for (const dl of snapshot.deadLetters) {
      const list = deadLetters.get(dl.ref) ?? [];
      list.push(dl);
      deadLetters.set(dl.ref, list);
    }
    this._deadLetters = deadLetters;

    const parked = new Map<Ref, Map<string, ParkedEntry>>();
    for (const p of snapshot.parked) {
      if (p.count <= 0) continue; // defensive: a snapshot row should never be a cleared one
      const m = parked.get(p.ref) ?? new Map<string, ParkedEntry>();
      m.set(p.port, p);
      parked.set(p.ref, m);
    }
    this._parked = parked;

    const restarts = new Map<Ref, RestartEntry[]>();
    for (const r of snapshot.restarts) {
      const list = restarts.get(r.ref) ?? [];
      list.push(r);
      restarts.set(r.ref, list);
    }
    this._restarts = restarts;

    this.notify();
  }

  applyDeadLetter(entry: DeadLetterEntry): void {
    const next = new Map(this._deadLetters);
    next.set(entry.ref, [...(next.get(entry.ref) ?? []), entry]);
    this._deadLetters = next;
    this._counters = { ...this._counters, deadLetters: this._counters.deadLetters + 1 };
    this.notify();
  }

  applyParked(entry: ParkedEntry): void {
    const next = new Map(this._parked);
    const forRef = new Map(next.get(entry.ref) ?? []);
    if (entry.count <= 0) forRef.delete(entry.port);
    else forRef.set(entry.port, entry);
    if (forRef.size === 0) next.delete(entry.ref);
    else next.set(entry.ref, forRef);
    this._parked = next;

    let total = 0;
    for (const m of next.values()) for (const p of m.values()) total += p.count;
    this._counters = { ...this._counters, parked: total };
    this.notify();
  }

  applyRestart(entry: RestartEntry): void {
    const next = new Map(this._restarts);
    next.set(entry.ref, [...(next.get(entry.ref) ?? []), entry]);
    this._restarts = next;
    this._counters = { ...this._counters, restarts: this._counters.restarts + 1 };
    this.notify();
  }
}
