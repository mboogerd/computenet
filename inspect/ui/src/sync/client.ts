import { KNOWN_EVENT_KINDS, type InspectEvent, type RawEvent, type TopologySnapshot } from '../api/types';

export type ConnState = 'connecting' | 'live' | 'reconnecting';

export interface TopologyClientHandlers {
  onSnapshot: (snapshot: TopologySnapshot) => void;
  onEvent: (event: InspectEvent) => void;
  onState?: (s: ConnState) => void;
}

/**
 * Client protocol (20-api-contract.md "SSE events"): fetch the
 * TopologySnapshot, then apply events with seq > snapshot.seq; on a seq gap
 * or a reconnect, refetch the snapshot — events are never replayed.
 *
 * A "gap" is any event whose seq isn't exactly lastSeq+1. That single check
 * also covers the server's slow-client drop behavior (M0-BE: bounded
 * per-client queue, drop-oldest, "force-refetch marker on drop") even though
 * this contract does not name a distinct marker event kind: a dropped event
 * necessarily skips this client's expected next seq, so it is already a
 * detected gap. See the M0-FE report for this as a flagged contract
 * ambiguity — if the eventual server sends an explicit marker kind instead
 * of (or in addition to) a seq skip, this client still resyncs correctly,
 * it just does so via the seq check rather than the marker's `kind`.
 *
 * Framework-free: no Solid imports. This is the seam a real subscription
 * push model would still enter through (fetch + a stream of deltas) — no
 * client-visible change if the transport details evolve.
 */
export class TopologyClient {
  private es?: EventSource;
  private lastSeq = -1;
  private hadError = false;
  private resyncing = false;
  private stopped = false;

  constructor(
    private readonly handlers: TopologyClientHandlers,
    private readonly baseUrl = '/api/inspect',
  ) {}

  async start(): Promise<void> {
    this.stopped = false;
    this.setState('connecting');
    await this.refetch();
    if (this.stopped) return;
    this.openStream();
  }

  stop(): void {
    this.stopped = true;
    this.es?.close();
    this.es = undefined;
  }

  private async refetch(): Promise<void> {
    this.resyncing = true;
    try {
      const res = await fetch(`${this.baseUrl}/topology`);
      if (!res.ok) throw new Error(`GET ${this.baseUrl}/topology -> ${res.status}`);
      const snapshot = (await res.json()) as TopologySnapshot;
      if (this.stopped) return;
      this.lastSeq = snapshot.seq;
      this.handlers.onSnapshot(snapshot);
    } catch (err) {
      // Self-healing: lastSeq is left stale, so the very next event we do
      // receive (once connectivity returns) will look like a gap again and
      // retrigger refetch() — no busy loop, paced by the server's own event
      // cadence rather than a timer here.
      console.error('inspect: topology snapshot fetch failed', err);
    } finally {
      this.resyncing = false;
    }
  }

  private openStream(): void {
    this.es?.close();
    const es = new EventSource(`${this.baseUrl}/events`);
    this.es = es;
    es.onopen = () => {
      this.setState('live');
      if (this.hadError) {
        this.hadError = false;
        void this.refetch(); // we don't know what happened while disconnected
      }
    };
    es.onmessage = (e: MessageEvent) => this.onMessage(e.data as string);
    es.onerror = () => {
      this.hadError = true;
      this.setState('reconnecting'); // EventSource retries on its own
    };
  }

  private onMessage(raw: string): void {
    if (this.resyncing) return; // drop until the fresh snapshot has landed
    let event: RawEvent;
    try {
      event = JSON.parse(raw) as RawEvent;
    } catch (err) {
      console.error('inspect: malformed SSE frame', err);
      return;
    }
    if (event.seq <= this.lastSeq) return; // stale/duplicate

    // A heartbeat is a liveness probe, not a delta: it RE-STATES the server's
    // current seq without consuming one. So it must never advance lastSeq —
    // doing so would let a client that lost exactly one delta swallow the loss
    // (heartbeat.seq == lastSeq+1 would read as "the next delta, understood"),
    // leaving its topology permanently wrong with no gap ever detected. Any
    // heartbeat ahead of us is itself the proof that a delta went missing.
    if (event.kind === 'heartbeat') {
      void this.refetch();
      return;
    }

    if (event.seq > this.lastSeq + 1) {
      void this.refetch(); // gap — the fresh snapshot supersedes this event
      return;
    }
    this.lastSeq = event.seq;
    if (KNOWN_EVENT_KINDS.has(event.kind)) {
      // Safe: KNOWN_EVENT_KINDS is exactly the InspectEvent union's kinds.
      this.handlers.onEvent(event as InspectEvent);
    }
    // else: a later-milestone kind (state.summary, error.*, ...) — ignored,
    // additive evolution (20-api-contract.md preamble).
  }

  private setState(s: ConnState): void {
    this.handlers.onState?.(s);
  }
}
