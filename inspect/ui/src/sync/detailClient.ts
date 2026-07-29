import type { CellDetail, CellState, Ref, StateSummaryPayload } from '../api/types';
import { indicatesChange } from './summaryChange';

/** The M1 REST surface (20-api-contract.md "Endpoints"): everything the
 *  detail panel needs beyond the topology/SSE seam. A separate interface
 *  (not folded into TopologyClient) because it is request/response, not a
 *  stream, and because P6 ties its lifecycle to selection rather than to
 *  the app's connection lifecycle. */
export interface DetailTransport {
  fetchDetail(ref: Ref): Promise<CellDetail>;
  fetchState(ref: Ref): Promise<CellState>;
  /** `POST /cell/{ref}/observe` — starts `state.summary` events for `ref`. */
  observeStart(ref: Ref): Promise<void>;
  /** `DELETE /cell/{ref}/observe` — stops them. */
  observeStop(ref: Ref): Promise<void>;
}

function baseUrl(): string {
  return '/api/inspect';
}

async function expectOk(res: Response): Promise<Response> {
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
  return res;
}

async function expectJson<T>(res: Response): Promise<T> {
  return (await expectOk(res)).json() as Promise<T>;
}

/** `fetch`-based transport against the real `:inspect` server. The ref is
 *  placed in the path verbatim (not `encodeURIComponent`-escaped): the
 *  contract's endpoint pattern is literally `/cell/{ref}` with a
 *  colon-bearing ref, and `:` is a valid raw path character (RFC 3986
 *  `pchar`); M1-BE's own path parsing is the source of truth here if this
 *  needs reconciling at M1-EVAL. */
export const defaultDetailTransport: DetailTransport = {
  fetchDetail: (ref) => fetch(`${baseUrl()}/cell/${ref}`).then((r) => expectJson<CellDetail>(r)),
  fetchState: (ref) => fetch(`${baseUrl()}/cell/${ref}/state`).then((r) => expectJson<CellState>(r)),
  observeStart: (ref) => fetch(`${baseUrl()}/cell/${ref}/observe`, { method: 'POST' }).then(() => undefined),
  observeStop: (ref) => fetch(`${baseUrl()}/cell/${ref}/observe`, { method: 'DELETE' }).then(() => undefined),
};

export interface DetailHandlers {
  onDetail: (ref: Ref, detail: CellDetail | undefined, error?: unknown) => void;
  onState: (ref: Ref, state: CellState | undefined, error?: unknown) => void;
}

/**
 * Owns the M1 observer-effect discipline (10-target-v3.md constraint P6 /
 * M1-BE ticket §2): selecting a node issues exactly one `observe` POST;
 * deselecting (or selecting something else) issues exactly one `observe`
 * DELETE for whatever was previously selected. Browsing without selecting
 * never calls this class at all.
 *
 * Framework-free (no Solid import) so it is directly unit-testable against a
 * mock `DetailTransport`, the same pattern as `sync/client.ts` /
 * `test/client.test.ts`. `solid/detail.ts` is the only caller.
 *
 * An `epoch` counter guards against a stale async response landing after a
 * rapid re-selection (select A, then B before A's fetch resolves): A's
 * eventual detail/state response must never overwrite B's.
 */
export class DetailController {
  private current: Ref | null = null;
  private epoch = 0;
  /** True while the current selection is descriptor-only (a cold graph, see
   *  {@link select}) — so a `state.summary` for some other, still-observed cell
   *  cannot pull this one's state in through the back door. */
  private descriptorOnly = false;
  /** V1A-FE ticket Implement §1: the last `state.summary` seen for the
   *  current selection, so {@link onSummary} can gate its refetch on
   *  `indicatesChange` rather than refetching unconditionally on every
   *  summary (which, once V1A-BE's coalesced feed publishes even-when-quiet
   *  windows, would turn into a 1 Hz polling loop against an unchanged
   *  value). Reset on every {@link select}/{@link deselect} so a
   *  re-selection always refetches once, exactly like the epoch counter
   *  resets the async-staleness guard. */
  private lastSummary: StateSummaryPayload | undefined;

  constructor(
    private readonly transport: DetailTransport,
    private readonly handlers: DetailHandlers,
  ) {}

  /** Currently selected/observed ref, or null. */
  get selected(): Ref | null {
    return this.current;
  }

  /**
   * Select `ref`.
   *
   * `mode: 'descriptor'` is M5-COLD's gate: inside a cold graph, selecting a
   * node fetches its descriptor and **nothing else** — no `POST observe`, no
   * `GET state`. That is not an optimization, it is the whole point of the cold
   * screen: subscribing raises attention and can un-park a cone
   * (10-target-v3.md §Constraints 2), so a graph the UI has just told the user
   * is parked must not be woken by the act of looking at it. Waking is the
   * explicit button, never a side effect of selection.
   */
  select(ref: Ref, mode: 'live' | 'descriptor' = 'live'): void {
    if (this.current === ref && this.descriptorOnly === (mode === 'descriptor')) return;
    const prev = this.current;
    const wasObserved = prev !== null && !this.descriptorOnly;
    this.current = ref;
    this.descriptorOnly = mode === 'descriptor';
    this.lastSummary = undefined;
    const epoch = ++this.epoch;

    // only release what was actually acquired — a descriptor-only selection
    // never opened an observation to release
    if (prev && wasObserved) void this.transport.observeStop(prev);

    void this.loadDetail(ref, epoch);
    if (this.descriptorOnly) return;

    void this.transport
      .observeStart(ref)
      .then(() => this.loadState(ref, epoch))
      .catch((err) => {
        if (epoch === this.epoch && this.current === ref) this.handlers.onState(ref, undefined, err);
      });
  }

  deselect(): void {
    if (!this.current) return;
    const prev = this.current;
    const wasObserved = !this.descriptorOnly;
    this.current = null;
    this.descriptorOnly = false;
    this.lastSummary = undefined;
    this.epoch++;
    if (wasObserved) void this.transport.observeStop(prev);
  }

  /** Called for every `state.summary` SSE event, regardless of ref — a no-op
   *  unless it names the currently-observed cell. Descriptor-only selections
   *  are not observed, so they never re-read state from one either.
   *
   *  V1A-FE ticket Implement §1: refetches only when {@link indicatesChange}
   *  says this summary represents an effective change versus the last one
   *  seen since selection — not on every summary. The epoch guard on
   *  `loadState` is unchanged and remains the sole mechanism for discarding a
   *  response that lands after a re-selection; this adds no second staleness
   *  mechanism, only a *trigger* gate. */
  onSummary(payload: StateSummaryPayload): void {
    if (payload.ref !== this.current || this.descriptorOnly) return;
    const changed = indicatesChange(this.lastSummary, payload);
    this.lastSummary = payload;
    if (changed) void this.loadState(payload.ref, this.epoch);
  }

  private async loadDetail(ref: Ref, epoch: number): Promise<void> {
    try {
      const detail = await this.transport.fetchDetail(ref);
      if (this.isCurrent(ref, epoch)) this.handlers.onDetail(ref, detail);
    } catch (err) {
      if (this.isCurrent(ref, epoch)) this.handlers.onDetail(ref, undefined, err);
    }
  }

  private async loadState(ref: Ref, epoch: number): Promise<void> {
    try {
      const state = await this.transport.fetchState(ref);
      if (this.isCurrent(ref, epoch)) this.handlers.onState(ref, state);
    } catch (err) {
      if (this.isCurrent(ref, epoch)) this.handlers.onState(ref, undefined, err);
    }
  }

  private isCurrent(ref: Ref, epoch: number): boolean {
    return epoch === this.epoch && this.current === ref;
  }
}
