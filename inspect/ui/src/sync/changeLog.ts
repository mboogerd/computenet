import type { Frontier, StateSummaryPayload } from '../api/types';
import { indicatesChange } from './summaryChange';

/** One settled effective change, as shown in the State subsection's "onChange
 *  log" (v1 mock-up's third column). Note: this is one entry per *changed
 *  publish window*, not one per underlying settled change inside the kernel —
 *  V1A-BE's coalescing means those can differ under load (several settled
 *  changes landing inside one 1 Hz window collapse to a single summary, and
 *  therefore a single log entry). */
export interface ChangeLogEntry {
  atMs: number;
  cardinality: string | null;
  frontier: Frontier | null;
}

/** Cap named for the same reason `flowStore.ts`'s `DECAY_AFTER_MISSED_WINDOWS`
 *  is: a client-side bound with no contract-mandated value, chosen to keep a
 *  long-running observation's log from growing unboundedly while still
 *  showing a useful amount of history in a 320px detail panel. */
export const MAX_CHANGE_LOG_ENTRIES = 50;

/** V1A-FE ticket Implement §3: a bounded, per-selected-cell log of settled
 *  effective changes, fed from every `state.summary` for the currently
 *  observed cell. Framework-free (no Solid import) with a `subscribe()`/
 *  version-notification shape, mirroring `sync/flowStore.ts` — `solid/detail.ts`
 *  holds the single instance (there is at most one observed cell in M1, same
 *  as `DetailController`) and wires a Solid signal off it for reactive reads.
 *
 *  Reuses `summaryChange.ts`'s `indicatesChange` — the same "changed vs quiet"
 *  decision `DetailController` uses to gate its refetch, so the log's entries
 *  and the panel's live value never disagree about what counts as a change. */
export class ChangeLog {
  private _entries: ChangeLogEntry[] = [];
  private last: StateSummaryPayload | undefined;
  private subs = new Set<() => void>();

  /** Newest first — the panel's natural reading order for "what just happened". */
  get entries(): readonly ChangeLogEntry[] {
    return this._entries;
  }

  subscribe(fn: () => void): () => void {
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }

  private notify(): void {
    for (const fn of this.subs) fn();
  }

  /** Feed one `state.summary` for the currently observed cell. Appends an
   *  entry only when {@link indicatesChange} says this payload represents a
   *  settled effective change versus the last one seen since the last
   *  {@link clear}; a quiet (publish-even-when-quiet) window is a no-op. */
  onSummary(payload: StateSummaryPayload): void {
    const changed = indicatesChange(this.last, payload);
    this.last = payload;
    if (!changed) return;
    this._entries = [
      { atMs: Date.now(), cardinality: payload.cardinality, frontier: payload.frontier },
      ...this._entries,
    ].slice(0, MAX_CHANGE_LOG_ENTRIES);
    this.notify();
  }

  /** The log is per selected cell and does not survive selecting a different
   *  one (ticket) — called from `solid/detail.ts`'s selection effect,
   *  alongside clearing `stateSummaries`. */
  clear(): void {
    this.last = undefined;
    if (this._entries.length === 0) return;
    this._entries = [];
    this.notify();
  }
}
