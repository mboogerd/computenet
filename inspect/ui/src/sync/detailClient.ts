import type { CellDetail, CellState, Ref } from '../api/types';

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

  constructor(
    private readonly transport: DetailTransport,
    private readonly handlers: DetailHandlers,
  ) {}

  /** Currently selected/observed ref, or null. */
  get selected(): Ref | null {
    return this.current;
  }

  select(ref: Ref): void {
    if (this.current === ref) return;
    const prev = this.current;
    this.current = ref;
    const epoch = ++this.epoch;

    if (prev) void this.transport.observeStop(prev);

    void this.loadDetail(ref, epoch);
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
    this.current = null;
    this.epoch++;
    void this.transport.observeStop(prev);
  }

  /** Called for every `state.summary` SSE event, regardless of ref — a no-op
   *  unless it names the currently-observed cell. */
  onSummary(ref: Ref): void {
    if (ref !== this.current) return;
    void this.loadState(ref, this.epoch);
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
