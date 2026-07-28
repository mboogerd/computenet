import { describe, expect, it } from 'vitest';
import {
  isPlainValueObject,
  isTombstoneRow,
  opaqueOf,
  rowCells,
  tableOf,
  truncatedOf,
  type CellState,
} from '../src/api/types';
import cellStateTable from '../fixtures/cell-state-table.json';
import cellStateTree from '../fixtures/cell-state-tree.json';
import cellStateScalar from '../fixtures/cell-state-scalar.json';
import cellStateTruncated from '../fixtures/cell-state-truncated.json';
import cellStateOpaque from '../fixtures/cell-state-opaque.json';
import cellStateUnavailable from '../fixtures/cell-state-unavailable.json';

/** `ValueView` (src/components/ValueView.tsx) dispatches purely on these
 *  guards (`tableOf`/`truncatedOf`/`opaqueOf`/`isPlainValueObject`/
 *  `Array.isArray`) — no Solid-specific behavior sits between the guard and
 *  what gets rendered, so exercising the guards against each checked-in
 *  `cell-state-*.json` fixture (M1-FE ticket Implement §4: "one per Value
 *  shape") is a faithful proxy for "the renderer picks the right branch for
 *  this shape", without needing a DOM-rendering test harness (none of this
 *  repo's `inspect/ui` tests mount components — agora/ui precedent: no new
 *  runtime deps). */

function state(fixture: unknown): CellState {
  return fixture as CellState;
}

describe('Value shape dispatch, one fixture per contract shape', () => {
  it('$table: a set/map-like collection, with a tombstoned row struck through', () => {
    const { value } = state(cellStateTable);
    const table = tableOf(value);
    expect(table).toBeDefined();
    expect(table!.columns).toEqual(['skill']);
    expect(table!.rows).toHaveLength(3);
    expect(table!.rows.filter((r) => isTombstoneRow(r))).toHaveLength(1);
    const tombstoned = table!.rows.find((r) => isTombstoneRow(r))!;
    expect(rowCells(tombstoned)).toEqual(['COBOL']);
    expect(rowCells(table!.rows[0])).toEqual(['Kotlin']);
    expect(truncatedOf(value)).toBeUndefined();
    expect(opaqueOf(value)).toBeUndefined();
  });

  it('object/list: renders as an indented tree (plain nested map, no special keys)', () => {
    const { value } = state(cellStateTree);
    expect(tableOf(value)).toBeUndefined();
    expect(truncatedOf(value)).toBeUndefined();
    expect(opaqueOf(value)).toBeUndefined();
    expect(isPlainValueObject(value)).toBe(true);
    const obj = value as Record<string, unknown>;
    expect(Object.keys(obj)).toEqual(['alice', 'bob']);
    expect(Array.isArray((obj.alice as Record<string, unknown>).skills)).toBe(true);
  });

  it('scalar: a bare number renders as text, not dispatched to any container branch', () => {
    const { value } = state(cellStateScalar);
    expect(typeof value).toBe('number');
    expect(tableOf(value)).toBeUndefined();
    expect(isPlainValueObject(value)).toBe(false);
    expect(Array.isArray(value)).toBe(false);
  });

  it('$truncated alongside $table: "showing N of M" note plus the (partial) table', () => {
    const { value } = state(cellStateTruncated);
    const table = tableOf(value);
    const truncated = truncatedOf(value);
    expect(table).toBeDefined();
    expect(truncated).toEqual({ total: 1800, shown: 200 });
  });

  it('opaque: the reflective-toString last resort renders as a code block, not a tree', () => {
    const { value } = state(cellStateOpaque);
    const opaque = opaqueOf(value);
    expect(opaque).toBe('AdvertisedLedger@3f2c1a{entries=12, tombstones=2}');
    expect(tableOf(value)).toBeUndefined();
  });

  it('kind: "unavailable" — no Value shape to dispatch on at all', () => {
    const cs = state(cellStateUnavailable);
    expect(cs.kind).toBe('unavailable');
    expect(cs.frontier).toBeNull();
  });
});
