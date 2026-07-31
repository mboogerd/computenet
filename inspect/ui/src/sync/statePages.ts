import type { CellState, Ref, Value } from '../api/types';
import { tableOf, type TableShape } from '../api/types';
import type { DetailTransport, PageOutcome } from './detailClient';

/** V1C-FE ticket Solution direction §1 — the paged state view for a big cell.
 *
 * A walk is seeded from whatever `CellState` the normal (unpaged) state fetch
 * already produced — page 1 arrives for free via `DetailTransport.fetchState`
 * / the descriptor-mode read, exactly like every other cell. This module only
 * owns what happens *after* that: accumulating further explicit
 * `fetchStatePage` calls, never fetching one on its own.
 *
 * Framework-free (no Solid import), unit-testable against a mock
 * `DetailTransport` the same way `sync/detailClient.ts` is by
 * `test/detailClient.test.ts`; `solid/detail.ts` is the only caller.
 *
 * `DEFAULT_PAGE_LIMIT` matches the contract's own default
 * (`StatePage.limit`'s server-side default, `20-wave-neutral-read-design.md`
 * / V1C-BE ticket Part 1's `PAGE_LIMIT_DEFAULT`), so a client that never
 * overrides it renders exactly what the server would have answered anyway. */
export const DEFAULT_PAGE_LIMIT = 200;

export interface StateWalkSnapshot {
  ref: Ref | null;
  /** The most recently landed `CellState` for this ref — page 1 or later.
   *  `null` before anything has been seeded. Top-level fields the walk does
   *  not itself accumulate (kind/provenance/unreadable/frontier/staleMs) are
   *  read from here, verbatim from the latest response. */
  latest: CellState | null;
  /** The union of every page fetched so far, or `latest.value` unmerged when
   *  only one page exists. Only meaningful when `latest.kind === 'page'`. */
  value: Value | null;
  /** `false` iff two pages' shapes did not match and were rendered as a
   *  sequence rather than concatenated (see {@link mergePages}). */
  merged: boolean;
  pagesFetched: number;
  /** Σ `page.entries` across every page fetched so far. */
  entriesTotal: number;
  /** Σ `page.exclusivesElided` across every page fetched so far. */
  exclusivesElidedTotal: number;
  /** The latest page's own `walkStable` verdict — already a running
   *  server-verified answer, not something this client re-derives. */
  walkStable: boolean | null;
  /** The union of every page's `page.caveats`, in first-seen order (C10).
   *  The server already accumulates them, but a walk whose first page was
   *  served before a caveat applied would otherwise lose it on a later
   *  page's narrower list — a union can only ever be the honest direction
   *  for a declared weakening. */
  caveats: readonly string[];
  /** The LATEST page's `page.attributes` — cell-level state that rides every
   *  page (C10). Not accumulated: these are per-read values (`SetCell`'s tag
   *  counter, `ShardCell`'s assigned epoch), so the newest reading is the
   *  only honest one to show. `{}` when the server sent none. */
  attributes: { readonly [key: string]: Value };
  /** The cursor to send for the next page; `null` = walk complete (or not
   *  yet started). Gate every "load next page" affordance on this being
   *  non-null AND `latest.kind === 'page'` — never on cell size, never on a
   *  `$truncated` marker inside the rendered value. */
  cursor: string | null;
  loading: boolean;
  /** True for the one render right after an automatic 410 restart — a
   *  neutral inline note, never surfaced as an error (V1C-FE ticket
   *  Solution direction §1: "410 restarts, silently and once"). Cleared at
   *  the start of the next explicit `loadNext()` call. */
  restarted: boolean;
  /** True after a second consecutive 410 — the walk gives up rather than
   *  looping, per the ticket's "a second consecutive 410 stops". */
  stuck: boolean;
}

const EMPTY_SNAPSHOT: StateWalkSnapshot = {
  ref: null,
  latest: null,
  value: null,
  merged: true,
  pagesFetched: 0,
  entriesTotal: 0,
  exclusivesElidedTotal: 0,
  walkStable: null,
  caveats: [],
  attributes: {},
  cursor: null,
  loading: false,
  restarted: false,
  stuck: false,
};

/** Concatenate `pages` (in fetch order) into one rendered `Value`, or — if
 *  their shapes disagree — render them as a plain sequence rather than
 *  fabricate a merge (V1C-FE ticket Solution direction §1: "if two pages'
 *  shapes do not match ... do not fabricate a merge: render the pages in
 *  sequence and say so"). Pure and exported for its own unit test — the
 *  ticket calls this out as "the one place a hard-to-see correctness bug can
 *  hide".
 *
 *  - `$table` pages with identical `columns` concatenate their `rows`.
 *  - plain-array pages concatenate.
 *  - anything else (including a shape mismatch) is wrapped as an array of
 *    the pages in order — `ValueView` already renders an array element that
 *    is itself a `$table`/tree correctly, so this degrades to "one section
 *    per page" rather than losing data or inventing a shape. `merged: false`
 *    tells the caller this happened, so it can say so. */
export function mergePages(pages: readonly Value[]): { value: Value; merged: boolean } {
  if (pages.length === 0) return { value: [], merged: true };
  if (pages.length === 1) return { value: pages[0], merged: true };

  const firstTable = tableOf(pages[0]);
  if (firstTable && pages.every((p) => sameColumns(tableOf(p), firstTable))) {
    const rows = pages.flatMap((p) => tableOf(p)!.rows);
    return { value: { $table: { columns: firstTable.columns, rows } }, merged: true };
  }

  if (pages.every((p) => Array.isArray(p))) {
    return { value: (pages as readonly Value[][]).flat(), merged: true };
  }

  return { value: [...pages], merged: false };
}

/** First-seen-order union of a walk's declared caveats (C10) — see
 *  {@link StateWalkSnapshot.caveats} for why a union rather than the latest
 *  page's list. Pure, and exported for its own unit test. */
export function unionCaveats(sofar: readonly string[], next: readonly string[] | undefined): readonly string[] {
  if (!next || next.length === 0) return sofar;
  const merged = [...sofar];
  for (const c of next) if (!merged.includes(c)) merged.push(c);
  return merged;
}

function sameColumns(a: TableShape | undefined, b: TableShape | undefined): boolean {
  if (!a || !b) return false;
  return a.columns.length === b.columns.length && a.columns.every((c, i) => c === b.columns[i]);
}

/** The walk state machine. One instance per detail panel (mirroring
 *  `DetailController`'s single-selection scope); `solid/detail.ts` seeds it
 *  from every fresh base `CellState` and bridges {@link snapshot} into a
 *  signal. */
export class StateWalk {
  private state: StateWalkSnapshot = EMPTY_SNAPSHOT;
  private pages: Value[] = [];
  private epoch = 0;
  private consecutiveStale = 0;

  constructor(
    private readonly transport: Pick<DetailTransport, 'fetchStatePage'>,
    private readonly onChange: (snapshot: StateWalkSnapshot) => void = () => {},
  ) {}

  snapshot(): StateWalkSnapshot {
    return this.state;
  }

  /** Seed (or reseed) the walk from a fresh base `CellState` — called
   *  whenever `solid/detail.ts`'s own `cellState` signal gets a new value for
   *  the current selection (a fresh selection, a pin's initial fetch, or a
   *  `state.summary`-triggered refetch). Always resets the accumulator: a
   *  refetched page 1 is a NEW walk, not a continuation — the previous walk's
   *  pages describe a fold that has since moved on. Bumps the epoch, so any
   *  `loadNext()` in flight from the previous walk is discarded when it
   *  lands. */
  seed(ref: Ref, state: CellState): void {
    this.epoch += 1;
    this.consecutiveStale = 0;
    if (state.kind === 'page' && state.page) {
      this.pages = [state.value];
      this.state = {
        ref,
        latest: state,
        value: state.value,
        merged: true,
        pagesFetched: 1,
        entriesTotal: state.page.entries,
        exclusivesElidedTotal: state.page.exclusivesElided,
        walkStable: state.page.walkStable,
        caveats: state.page.caveats ?? [],
        attributes: state.page.attributes ?? {},
        cursor: state.page.cursor,
        loading: false,
        restarted: false,
        stuck: false,
      };
    } else {
      this.pages = [];
      this.state = { ...EMPTY_SNAPSHOT, ref, latest: state };
    }
    this.emit();
  }

  /** Abandon the walk outright — no base state to fall back to (deselection,
   *  panel close, a fetch error). Clears the accumulator, drops the cursor,
   *  and (via the epoch bump) discards any in-flight `loadNext()` response
   *  when it lands rather than applying it. */
  abandon(): void {
    this.epoch += 1;
    this.consecutiveStale = 0;
    this.pages = [];
    this.state = EMPTY_SNAPSHOT;
    this.emit();
  }

  /** Fetch exactly one more page — the "Load next page" action. A no-op if
   *  there is nothing to fetch (`cursor === null`) or a fetch is already in
   *  flight; both are also how the UI gates the control itself, so this is a
   *  belt-and-suspenders guard, not the only one. Never called automatically:
   *  every invocation is one explicit user action
   *  (`20-wave-neutral-read-design.md` §4.2 — "the inspector becomes a
   *  participant" is the failure mode a background/auto-advancing walk would
   *  be). */
  async loadNext(limit: number = DEFAULT_PAGE_LIMIT): Promise<void> {
    const cursor = this.state.cursor;
    if (cursor === null || this.state.loading) return;
    const ref = this.state.ref;
    if (ref === null) return;
    const epoch = this.epoch;

    this.state = { ...this.state, loading: true, restarted: false, stuck: false };
    this.emit();

    const outcome = await this.transport.fetchStatePage(ref, { cursor, limit });
    if (epoch !== this.epoch) return; // superseded by a seed()/abandon() while this was in flight

    if (outcome.status === 'staleCursor') {
      await this.handleStaleCursor(ref, epoch, limit);
      return;
    }

    this.consecutiveStale = 0;
    this.applyPage(ref, outcome.state);
  }

  private async handleStaleCursor(ref: Ref, epoch: number, limit: number): Promise<void> {
    if (this.consecutiveStale >= 1) {
      // a second consecutive 410 — stop rather than loop (ticket Solution
      // direction §1: "a second consecutive 410 stops rather than looping")
      this.consecutiveStale = 0;
      this.state = { ...this.state, loading: false, stuck: true };
      this.emit();
      return;
    }
    this.consecutiveStale += 1;
    // drop the cursor, clear the accumulator, restart from page 1 — silently:
    // no error surfaced, at most the neutral `restarted` note.
    this.pages = [];
    const restart = await this.transport.fetchStatePage(ref, { limit });
    if (epoch !== this.epoch) return;

    if (restart.status === 'staleCursor') {
      this.consecutiveStale = 0;
      this.state = { ...EMPTY_SNAPSHOT, ref, loading: false, stuck: true };
      this.emit();
      return;
    }
    this.consecutiveStale = 0;
    this.applyPage(ref, restart.state, /* restarted */ true);
  }

  private applyPage(ref: Ref, state: CellState, restarted = false): void {
    if (state.kind === 'page' && state.page) {
      this.pages.push(state.value);
      const { value, merged } = mergePages(this.pages);
      this.state = {
        ref,
        latest: state,
        value,
        merged,
        pagesFetched: this.state.ref === ref && !restarted ? this.state.pagesFetched + 1 : 1,
        entriesTotal: (restarted ? 0 : this.state.entriesTotal) + state.page.entries,
        exclusivesElidedTotal: (restarted ? 0 : this.state.exclusivesElidedTotal) + state.page.exclusivesElided,
        walkStable: state.page.walkStable,
        caveats: unionCaveats(restarted ? [] : this.state.caveats, state.page.caveats),
        attributes: state.page.attributes ?? {},
        cursor: state.page.cursor,
        loading: false,
        restarted,
        stuck: false,
      };
    } else {
      // The re-fetched page no longer answers `kind: 'page'` at all (e.g. the
      // cell became a `view`/`unavailable` between requests) — nothing left
      // to walk; say what landed and stop.
      this.pages = [];
      this.state = { ...EMPTY_SNAPSHOT, ref, latest: state, restarted };
    }
    this.emit();
  }

  private emit(): void {
    this.onChange(this.state);
  }
}
