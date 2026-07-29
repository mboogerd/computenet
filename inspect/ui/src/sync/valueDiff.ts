import { isTombstoneRow, rowCells, tableOf, truncatedOf, type TableRow, type TableShape, type Value } from '../api/types';

/** V1A-FE ticket Implement §2: which rows/elements of a newly-rendered
 *  `Value` should flash, computed by diffing it against the previously
 *  rendered `Value` for the same selection. Pure and framework-free so it is
 *  directly unit-testable, mirroring `summaryChange.ts`; `DetailPanel.tsx`'s
 *  `StateSection` holds the "previous value" across renders and calls
 *  {@link diffRows} on each new one, handing the result to `ValueView` as a
 *  prop. */
export interface RowFlash {
  added: ReadonlySet<string>;
  changed: ReadonlySet<string>;
}

const EMPTY_FLASH: RowFlash = { added: new Set(), changed: new Set() };

/** Only `$table` values and plain arrays produce keyed rows — everything
 *  else (scalars, plain trees, `$opaque`) returns two empty sets, and so does
 *  a first render (`prev === undefined`: nothing to compare against, so
 *  nothing "changed"). */
export function diffRows(prev: Value | undefined, next: Value): RowFlash {
  if (prev === undefined) return EMPTY_FLASH;

  const nextTable = tableOf(next);
  if (nextTable) return diffTableRows(tableOf(prev), nextTable);

  if (Array.isArray(next)) return diffArrayElements(Array.isArray(prev) ? prev : undefined, next);

  return EMPTY_FLASH;
}

function diffTableRows(prevTable: TableShape | undefined, nextTable: TableShape): RowFlash {
  const columnCount = nextTable.columns.length;
  const prevByKey = keyedTableRows(prevTable?.rows ?? [], columnCount);

  const added = new Set<string>();
  const changed = new Set<string>();
  const seen = new Set<string>();
  for (const row of nextTable.rows) {
    const key = tableRowKey(row, columnCount);
    if (seen.has(key)) continue; // duplicate key within `next` — keep the first occurrence
    seen.add(key);

    const prevCells = prevByKey.get(key);
    if (prevCells === undefined) {
      added.add(key);
      continue;
    }
    // Single-column tables key on the whole row, so an identical key already
    // implies identical content — "changed" only makes sense once there is a
    // column of "remaining" content beyond the key.
    if (columnCount > 1 && restCellsDiffer(prevCells, rowCells(row))) changed.add(key);
  }
  return { added, changed };
}

function restCellsDiffer(prevCells: readonly Value[], nextCells: readonly Value[]): boolean {
  return stringifyCells(prevCells.slice(1)) !== stringifyCells(nextCells.slice(1));
}

function keyedTableRows(rows: readonly TableRow[], columnCount: number): Map<string, readonly Value[]> {
  const map = new Map<string, readonly Value[]>();
  for (const row of rows) {
    const key = tableRowKey(row, columnCount);
    if (!map.has(key)) map.set(key, rowCells(row)); // duplicate keys: keep the first occurrence
  }
  return map;
}

/** `$table` row key (ticket Implement §2): the first cell's scalar rendering
 *  when the table has more than one column, otherwise the whole row's
 *  stringified cells. Exported so `ValueView.tsx` can compute the same key
 *  per rendered row without re-deriving this rule. */
export function tableRowKey(row: TableRow, columnCount: number): string {
  const cells = rowCells(row);
  return columnCount > 1 ? stringifyCell(cells[0]) : stringifyCells(cells);
}

function diffArrayElements(prevArray: readonly Value[] | undefined, nextArray: readonly Value[]): RowFlash {
  const prevKeys = keyedArrayElements(prevArray ?? []);

  const added = new Set<string>();
  const seen = new Set<string>();
  for (const item of nextArray) {
    const key = arrayElementKey(item);
    if (key === undefined) continue; // a $truncated marker element is not a row
    if (seen.has(key)) continue; // duplicate key within `next` — keep the first occurrence
    seen.add(key);
    if (!prevKeys.has(key)) added.add(key);
  }
  // A plain-array key is the element's whole stringified content, so an
  // identical key already implies identical content — arrays never report
  // "changed", only added (mirroring the single-column table case above).
  return { added, changed: new Set() };
}

function keyedArrayElements(items: readonly Value[]): ReadonlySet<string> {
  const set = new Set<string>();
  for (const item of items) {
    const key = arrayElementKey(item);
    if (key !== undefined) set.add(key);
  }
  return set;
}

/** Plain-array element key (ticket Implement §2): the element's stringified
 *  content, or `undefined` to signal "skip" for a `$truncated` marker element
 *  (the contract: "$truncated ... appended as the last element on a plain
 *  array"). Exported for `ValueView.tsx`, mirroring {@link tableRowKey}. */
export function arrayElementKey(item: Value): string | undefined {
  if (truncatedOf(item) !== undefined) return undefined;
  return stringifyCell(item);
}

// isTombstoneRow is not needed directly here — rowCells() already strips the
// tombstone wrapper down to `cells` for keying/diffing purposes — but is
// re-exported from api/types for callers that also need to render the
// strikethrough, per the ticket's "reuse rowCells()/isTombstoneRow() rather
// than re-deriving row shape" note.
export { isTombstoneRow };

function stringifyCell(v: Value | undefined): string {
  return v === undefined ? 'undefined' : (JSON.stringify(v) ?? 'undefined');
}

function stringifyCells(cells: readonly Value[]): string {
  return JSON.stringify(cells) ?? '[]';
}
