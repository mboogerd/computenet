import type { ActivityEntry, ActivityKind, Ref } from '../api/types';

/** V2-FE ticket Implement §7: "render at most the newest 100 rows regardless
 *  of what the store holds" — independent of the store's own 200-entry cap
 *  (`ActivityStore`/`ACTIVITY_STORE_CAP`), which bounds what is *held*, not
 *  what is *rendered*. */
export const ACTIVITY_ROW_CAP = 100;

export interface ActivityKindMeta {
  glyph: string;
  label: string;
  /** A CSS custom-property name (`tokens.css`) — no new palette entries, per
   *  the ticket's own instruction ("using existing tokens ... do not add a
   *  palette"). */
  colorVar: string;
}

/** One entry per `ActivityKind`, each pairing a distinct glyph with a
 *  distinct *existing* design token, reusing colors that already carry a
 *  related meaning elsewhere in the app rather than inventing new ones:
 *  `activated` borrows the connection-status "live" green, `passivated` the
 *  cell-color "suspending" purple, `restarted` the errors' red, `woken` the
 *  flow pulse's gold, and `drained` the neutral muted-text gray (a drained
 *  host is not itself an error or a live/attention signal). */
export const ACTIVITY_KIND_META: Record<ActivityKind, ActivityKindMeta> = {
  activated: { glyph: '▲', label: 'activated', colorVar: '--status-live' },
  passivated: { glyph: '▮', label: 'passivated', colorVar: '--cell-suspending' },
  drained: { glyph: '▽', label: 'drained', colorVar: '--text-muted' },
  woken: { glyph: '☀', label: 'woken', colorVar: '--flow-pulse' },
  restarted: { glyph: '↻', label: 'restarted', colorVar: '--error' },
};

export interface ActivityRow {
  ref: Ref;
  kind: ActivityKind;
  atMs: number;
  generation?: number;
  /** The topology store's name for `ref` when known, else the short ref —
   *  same 8-char truncation `Canvas.tsx`'s node card falls back to for an
   *  unnamed cell. */
  label: string;
  /** Local time, `HH:MM:SS` (24h clock — deliberately not
   *  `toLocaleTimeString()`, whose AM/PM formatting is locale-dependent). */
  time: string;
}

export interface ActivityRowsResult {
  rows: readonly ActivityRow[];
  /** How many more entries the (possibly filtered) set held beyond the
   *  render cap — 0 when nothing was hidden. */
  hiddenCount: number;
}

export interface DeriveActivityRowsOptions {
  /** The "Only selected cell" control (V2-FE ticket Implement §6). Inert —
   *  has no filtering effect — when `selectedRef` is null: a disabled
   *  control must not silently empty the log just because its own checked
   *  state was left on from an earlier selection. */
  onlySelected: boolean;
  selectedRef: Ref | null;
  nameOf: (ref: Ref) => string | null;
  cap?: number;
}

/** Pure derivation from store entries (assumed newest-first — `ActivityStore
 *  .entries`'s own contract) to what the log panel renders. Unit-tested
 *  without a DOM (ticket acceptance criteria: "Row derivation is a pure,
 *  unit-tested module"). Never returns more than `cap` rows regardless of
 *  how many the (filtered) input holds. */
export function deriveActivityRows(entries: readonly ActivityEntry[], opts: DeriveActivityRowsOptions): ActivityRowsResult {
  const cap = opts.cap ?? ACTIVITY_ROW_CAP;
  const filtered = opts.onlySelected && opts.selectedRef !== null ? entries.filter((e) => e.ref === opts.selectedRef) : entries;

  const rows = filtered.slice(0, cap).map((e) => toRow(e, opts.nameOf));
  const hiddenCount = Math.max(0, filtered.length - cap);
  return { rows, hiddenCount };
}

function toRow(entry: ActivityEntry, nameOf: (ref: Ref) => string | null): ActivityRow {
  return {
    ref: entry.ref,
    kind: entry.kind,
    atMs: entry.atMs,
    generation: entry.generation,
    label: nameOf(entry.ref) ?? entry.ref.slice(0, 8),
    time: formatClock(entry.atMs),
  };
}

function formatClock(atMs: number): string {
  const d = new Date(atMs);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}
