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
  /** `POST /cell/{ref}/observe` — starts `state.summary` events for `ref`,
   *  unless the target has no built-in fold to observe, in which case the
   *  server answers 409 (20-api-contract.md:25) and this resolves
   *  `'refused'` rather than throwing — V1B-FE ticket Solution direction §3:
   *  a refused observe is a normal, handled outcome (the ref stays pinned,
   *  "snapshot only"), not a transport error. */
  observeStart(ref: Ref): Promise<ObserveOutcome>;
  /** `DELETE /cell/{ref}/observe` — stops them. */
  observeStop(ref: Ref): Promise<void>;
}

/** V1B-FE ticket Solution direction §3. `'refused'` is the 409 case — the
 *  target cell has no built-in fold to observe ("no delta outlet, or an
 *  outlet kind with no `View`", `Observations.kt`'s documented refusal
 *  cases) — carried as a resolved value rather than a rejection so callers
 *  can distinguish "the server declined this subscription" from "the
 *  request itself failed" (network error, 5xx, etc.), which still throws. */
export type ObserveOutcome = 'started' | 'refused';

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
  observeStart: (ref) =>
    fetch(`${baseUrl()}/cell/${ref}/observe`, { method: 'POST' }).then((res): ObserveOutcome => {
      if (res.status === 409) return 'refused';
      if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
      return 'started';
    }),
  observeStop: (ref) => fetch(`${baseUrl()}/cell/${ref}/observe`, { method: 'DELETE' }).then(() => undefined),
};

export interface DetailHandlers {
  onDetail: (ref: Ref, detail: CellDetail | undefined, error?: unknown) => void;
  onState: (ref: Ref, state: CellState | undefined, error?: unknown) => void;
  /** V1B-FE ticket Solution direction §2: fired whenever the explicit pinned
   *  set or the snapshot-only annotations change (pin/unpin/unpinAll, or a
   *  409 arriving asynchronously for a ref opened via {@link
   *  DetailController.pin} or {@link DetailController.select}) — the bridge
   *  `solid/detail.ts` turns into `pinned()`/`snapshotOnly()` signals. Both
   *  sets are snapshots (safe to retain by reference). */
  onPinsChanged: (pinned: ReadonlySet<Ref>, snapshotOnly: ReadonlySet<Ref>) => void;
}

/**
 * Owns the M1 observer-effect discipline (10-target-v3.md constraint P6 /
 * M1-BE ticket §2), generalized by V1B-FE from "exactly one observed ref"
 * (the selected one) to a **pinned set** plus the current **selection**,
 * which behaves as an implicit pin that follows the cursor. The full
 * observed set is `pinned ∪ {selection}` (selection only counts while it is
 * not descriptor-only — see {@link select}).
 *
 * Framework-free (no Solid import) so it is directly unit-testable against a
 * mock `DetailTransport`, the same pattern as `sync/client.ts` /
 * `test/client.test.ts`. `solid/detail.ts` is the only caller.
 *
 * Every tracked ref gets its own epoch counter (`Map<Ref, number>`), bumped
 * whenever a *fresh* observation is opened for it (a `select`/`pin` call
 * that was not already observing it) or whenever it is (re)selected as the
 * descriptor target — so a stale async response for a ref that has since
 * been unpinned/deselected/re-opened is discarded, the same property the
 * pre-V1b single-ref `epoch` counter enforced, now scoped per ref instead of
 * globally (so pinning/unpinning one ref never invalidates another ref's
 * in-flight fetch).
 */
export class DetailController {
  /** Explicit, user-controlled pins (`pin`/`unpin`/`unpinAll`). Does not
   *  include the current selection unless it was also explicitly pinned. */
  private pinned = new Set<Ref>();
  /** Refs whose `observeStart` most recently resolved `'refused'` (409): kept
   *  in the observed/pinned set (a no-fold cell can still be "watched", it
   *  just never streams) but never re-read from a `state.summary` — none
   *  ever arrive for it — and excluded from the "live observed" count. */
  private snapshotOnly = new Set<Ref>();
  /** The selected ref, or null. */
  private current: Ref | null = null;
  /** True while the current selection is descriptor-only (a cold graph, see
   *  {@link select}) — so a `state.summary` for some other, still-observed cell
   *  cannot pull this one's state in through the back door, and so selection
   *  contributes nothing to the observed set while cold. */
  private descriptorOnly = false;
  private epochs = new Map<Ref, number>();
  /** Per-ref last `state.summary` seen since the ref's observation was last
   *  (re)opened — V1A-FE ticket Implement §1, generalized per ref: gates
   *  {@link onSummary}'s refetch on `indicatesChange` rather than
   *  refetching unconditionally on every summary (which, once V1A-BE's
   *  coalesced feed publishes even-when-quiet windows, would turn into a
   *  1 Hz polling loop against an unchanged value). Cleared whenever a
   *  ref's observation is freshly (re)opened, so a re-pin/re-selection
   *  always refetches once, exactly like the epoch map resets the
   *  async-staleness guard for that ref. */
  private lastSummary = new Map<Ref, StateSummaryPayload>();

  constructor(
    private readonly transport: DetailTransport,
    private readonly handlers: DetailHandlers,
  ) {}

  /** Currently selected ref, or null. */
  get selected(): Ref | null {
    return this.current;
  }

  isPinned(ref: Ref): boolean {
    return this.pinned.has(ref);
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
   *
   * V1B-FE ticket Solution direction §1: releasing the previous selection's
   * observation is conditional — skipped if the previous ref is still
   * pinned. Opening the new selection's observation is likewise conditional
   * — skipped if the new ref is already pinned (it is already observed for
   * another reason). Note: this method deliberately has no notion of "cold"
   * beyond the `mode` parameter passed in by the caller — the same is true
   * of `pin` (see its doc comment) — cold-gating a pin control is a UI-layer
   * responsibility (`solid/cold.ts`'s `currentGraphCold()`), not this
   * class's.
   */
  select(ref: Ref, mode: 'live' | 'descriptor' = 'live'): void {
    if (this.current === ref && this.descriptorOnly === (mode === 'descriptor')) return;
    const prev = this.current;
    const prevWasLive = prev !== null && !this.descriptorOnly;
    const prevPinned = prev !== null && this.pinned.has(prev);

    this.current = ref;
    this.descriptorOnly = mode === 'descriptor';

    // only release what was actually acquired, and only if nothing else
    // (a pin) still wants it kept open
    if (prev && prevWasLive && !prevPinned) this.releaseObservation(prev);

    const epoch = this.bumpEpoch(ref);
    void this.loadDetail(ref, epoch);
    if (this.descriptorOnly) {
      this.notifyPinsChanged();
      return;
    }

    if (this.pinned.has(ref)) {
      // already observed via an existing pin — no new POST, just read the
      // current value for the panel
      void this.loadState(ref, epoch);
    } else {
      this.lastSummary.delete(ref);
      this.openLiveObservation(ref, epoch);
    }
    this.notifyPinsChanged();
  }

  deselect(): void {
    if (this.current === null) return;
    const prev = this.current;
    const prevWasLive = !this.descriptorOnly;
    const prevPinned = this.pinned.has(prev);
    this.current = null;
    this.descriptorOnly = false;
    if (prevWasLive && !prevPinned) this.releaseObservation(prev);
    this.notifyPinsChanged();
  }

  /**
   * Pin `ref`: add it to the explicit pinned set. If it was not already
   * observed (not already pinned, not already the live-observed selection),
   * opens exactly one observation for it (`POST .../observe` + one initial
   * `GET .../state`); a no-op transport-wise if it was already observed.
   *
   * Like {@link select}'s `mode: 'descriptor'` gate, this method has no
   * cold-graph awareness of its own — the caller (`solid/detail.ts`'s
   * bridge, driven by the pin control's `disabled`/hidden state) must not
   * invoke it at all while the target's graph is cold, mirroring how
   * `initDetail` decides `mode` before calling `select`.
   */
  pin(ref: Ref): void {
    if (this.pinned.has(ref)) return;
    const alreadyLive = this.current === ref && !this.descriptorOnly;
    this.pinned.add(ref);
    if (!alreadyLive) {
      const epoch = this.bumpEpoch(ref);
      this.lastSummary.delete(ref);
      this.openLiveObservation(ref, epoch);
    }
    this.notifyPinsChanged();
  }

  /**
   * Unpin `ref`. If it is not the current selection and had an open
   * (non-refused) observation, releases it (`DELETE .../observe`) and drops
   * its cached summary/snapshot-only flag. If it **is** the current
   * selection, the observation and cached state are left untouched — the
   * implicit selection-pin keeps it alive. A no-op if `ref` was not pinned.
   */
  unpin(ref: Ref): void {
    if (!this.pinned.has(ref)) return;
    this.pinned.delete(ref);
    if (ref !== this.current) this.releaseObservation(ref);
    this.notifyPinsChanged();
  }

  /** Release every pinned ref that is not the current selection (same rule
   *  as {@link unpin}, applied to each) in one pass, then clear the pinned
   *  set, notifying listeners exactly once regardless of how many refs were
   *  released. */
  unpinAll(): void {
    if (this.pinned.size === 0) return;
    for (const ref of this.pinned) {
      if (ref !== this.current) this.releaseObservation(ref);
    }
    this.pinned.clear();
    this.notifyPinsChanged();
  }

  /** Called for every `state.summary` SSE event, regardless of ref — a no-op
   *  unless it names a ref that is currently live-observed (pinned or the
   *  live-mode selection) and not snapshot-only (no summary ever arrives for
   *  a refused ref in practice, but this stays defensive rather than
   *  assuming that of the server).
   *
   *  V1A-FE ticket Implement §1: refetches only when {@link indicatesChange}
   *  says this summary represents an effective change versus the last one
   *  seen since the ref's observation was (re)opened — not on every summary.
   *  The per-ref epoch guard on `loadState` is unchanged and remains the
   *  sole mechanism for discarding a response that lands after the ref
   *  stops being tracked; this adds no second staleness mechanism, only a
   *  *trigger* gate. */
  onSummary(payload: StateSummaryPayload): void {
    const ref = payload.ref;
    if (!this.isObservedLive(ref) || this.snapshotOnly.has(ref)) return;
    const prev = this.lastSummary.get(ref);
    const changed = indicatesChange(prev, payload);
    this.lastSummary.set(ref, payload);
    if (changed) void this.loadState(ref, this.epochOf(ref));
  }

  private isObservedLive(ref: Ref): boolean {
    return this.pinned.has(ref) || (this.current === ref && !this.descriptorOnly);
  }

  private epochOf(ref: Ref): number {
    return this.epochs.get(ref) ?? 0;
  }

  private bumpEpoch(ref: Ref): number {
    const epoch = this.epochOf(ref) + 1;
    this.epochs.set(ref, epoch);
    return epoch;
  }

  /** `ref` is still the thing an in-flight state fetch was issued for: it
   *  remains tracked (pinned or the current selection) and no fresher
   *  observation has since been opened for it. */
  private isTracked(ref: Ref, epoch: number): boolean {
    return epoch === this.epochOf(ref) && (this.pinned.has(ref) || this.current === ref);
  }

  private isSelected(ref: Ref, epoch: number): boolean {
    return epoch === this.epochOf(ref) && this.current === ref;
  }

  /** `POST .../observe` then one initial `GET .../state`, shared by {@link
   *  select} and {@link pin} for the "not already observed" case. Handles
   *  the 409 ("refused") outcome by flagging `ref` snapshot-only rather than
   *  treating it as a failure — the observe response itself is the signal
   *  (V1B-FE ticket Solution direction §3), never `CellState.kind`. */
  private openLiveObservation(ref: Ref, epoch: number): void {
    void this.transport
      .observeStart(ref)
      .then((outcome) => {
        if (outcome === 'refused' && this.isTracked(ref, epoch)) {
          this.snapshotOnly.add(ref);
          this.notifyPinsChanged();
        }
        return this.loadState(ref, epoch);
      })
      .catch((err) => {
        if (this.isTracked(ref, epoch)) this.handlers.onState(ref, undefined, err);
      });
  }

  /** Fully releases `ref`'s observation: drops its per-ref caches and, unless
   *  it was snapshot-only (in which case no real server-side subscription
   *  was ever open), issues the `DELETE .../observe`. Callers are
   *  responsible for calling {@link notifyPinsChanged} themselves — exactly
   *  once per public method invocation, even when this is called in a loop
   *  (see {@link unpinAll}).
   *
   *  Deliberately does NOT reset `ref`'s epoch counter — a re-open (a later
   *  `pin`/`select` for the same ref) always bumps *forward* from whatever
   *  the counter already holds, so a stale response from this now-closed
   *  observation can never coincidentally match the epoch a later reopen
   *  produces (which a reset-to-zero-then-rebump would risk on the very
   *  first reopen). */
  private releaseObservation(ref: Ref): void {
    this.lastSummary.delete(ref);
    const wasSnapshotOnly = this.snapshotOnly.delete(ref);
    if (!wasSnapshotOnly) void this.transport.observeStop(ref);
  }

  private notifyPinsChanged(): void {
    this.handlers.onPinsChanged(new Set(this.pinned), new Set(this.snapshotOnly));
  }

  private async loadDetail(ref: Ref, epoch: number): Promise<void> {
    try {
      const detail = await this.transport.fetchDetail(ref);
      if (this.isSelected(ref, epoch)) this.handlers.onDetail(ref, detail);
    } catch (err) {
      if (this.isSelected(ref, epoch)) this.handlers.onDetail(ref, undefined, err);
    }
  }

  private async loadState(ref: Ref, epoch: number): Promise<void> {
    try {
      const state = await this.transport.fetchState(ref);
      if (this.isTracked(ref, epoch)) this.handlers.onState(ref, state);
    } catch (err) {
      if (this.isTracked(ref, epoch)) this.handlers.onState(ref, undefined, err);
    }
  }
}
