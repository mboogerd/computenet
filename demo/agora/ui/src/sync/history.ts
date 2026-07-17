import type { Delta, Ref } from '../api/types';

export const HISTORY_CAP = 50;
export const WINDOW_MS = 2500;
export const PULSE_DRIFT = 0.15; // mirrors backend MAGNITUDE_BANDS HIGH boundary
export const TICKER_DRIFT = 0.05; // mirrors NORMAL boundary

export interface Sample {
  t: number;
  credence: number;
}
export interface HotEvents {
  /** refs whose windowed drift crossed PULSE_DRIFT this delta (may re-emit
   *  across a burst — the visual consumer just re-arms the fade). */
  pulses: Ref[];
  /** ticker entries, deduped to one per ref per window. */
  ticker: { ref: Ref; t: number; drift: number }[];
}

/** Per-ref rolling credence history, kept beside the store (never on the
 *  immutable NodeRec). Feeds the detail-panel sparkline and the WINDOWED
 *  hot-change detection: one stance produces a burst of per-hop snapshots
 *  whose individual deltas are tiny, so significance is drift vs ~2.5s ago,
 *  not per-message delta. */
export class History {
  private series_ = new Map<Ref, Sample[]>();
  private lastTicker = new Map<Ref, number>();

  record(delta: Delta): HotEvents {
    const pulses: Ref[] = [];
    const ticker: HotEvents['ticker'] = [];
    const touched = [...delta.added, ...delta.changed.map((c) => c.next)];

    for (const rec of touched) {
      const buf = this.series_.get(rec.ref);
      // Sample the window edge BEFORE appending the new value.
      const past = buf && buf.length ? sampleAt(buf, delta.t - WINDOW_MS) : rec.credence;
      this.append(rec.ref, delta.t, rec.credence);

      if (delta.resync) continue; // reconnect catch-up must not strobe the graph
      const drift = Math.abs(rec.credence - past);
      if (drift >= PULSE_DRIFT) pulses.push(rec.ref);
      if (drift >= TICKER_DRIFT) {
        const last = this.lastTicker.get(rec.ref) ?? -Infinity;
        if (delta.t - last >= WINDOW_MS) {
          ticker.push({ ref: rec.ref, t: delta.t, drift });
          this.lastTicker.set(rec.ref, delta.t);
        }
      }
    }
    return { pulses, ticker };
  }

  /** Credence samples for the sparkline ("since you opened this page"). */
  series(ref: Ref): readonly Sample[] {
    return this.series_.get(ref) ?? [];
  }

  private append(ref: Ref, t: number, credence: number): void {
    let buf = this.series_.get(ref);
    if (!buf) {
      buf = [];
      this.series_.set(ref, buf);
    }
    buf.push({ t, credence });
    if (buf.length > HISTORY_CAP) buf.shift();
  }
}

/** Latest sample at or before `target`; if none that old, the earliest kept
 *  sample (the pre-burst value). buf is chronological. */
function sampleAt(buf: readonly Sample[], target: number): number {
  let val = buf[0].credence;
  for (const s of buf) {
    if (s.t <= target) val = s.credence;
    else break;
  }
  return val;
}
