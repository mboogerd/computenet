import { For, Show, type JSX } from 'solid-js';
import {
  isPlainValueObject,
  isTombstoneRow,
  opaqueOf,
  rowCells,
  tableOf,
  truncatedOf,
  type TableShape,
  type Value,
} from '../api/types';
import { arrayElementKey, tableRowKey, type RowFlash } from '../sync/valueDiff';
import './ValueView.css';

/** Renders the contract's `Value` shape (20-api-contract.md "Value"):
 *  `$table` as a data table (tombstoned rows struck through), objects/lists
 *  as an indented tree, `$truncated` as a "showing N of M" note, `opaque` as
 *  a code block, everything else as a scalar (M1-FE ticket Implement §2).
 *  Not itself reactive — `value` is a plain snapshot from the last
 *  `GET .../state`, replaced wholesale on each fetch; recursion is plain
 *  function calls, not nested components, since there is nothing here to
 *  fine-grain update.
 *
 *  V1A-FE ticket Implement §2: `flash` is an optional, caller-computed
 *  `RowFlash` (from `sync/valueDiff.ts`'s `diffRows`, run by `DetailPanel.tsx`'s
 *  `StateSection` against the previously rendered value) naming which
 *  top-level table rows / array elements should carry `data-flash`. It only
 *  ever applies at the top level: `flash` is not threaded into recursive
 *  `renderValue` calls, so a nested table/array inside a tree never flashes —
 *  `diffRows` only ever computed keys for the top-level value in the first
 *  place. `ValueView` stays a pure render of its props; it holds no state of
 *  its own beyond this call's own recursion. */
export default function ValueView(props: { value: Value; flash?: RowFlash }): JSX.Element {
  return <div class="value-view">{renderValue(props.value, 0, props.flash)}</div>;
}

function flashKindFor(flash: RowFlash | undefined, key: string): 'added' | 'changed' | undefined {
  if (!flash) return undefined;
  if (flash.added.has(key)) return 'added';
  if (flash.changed.has(key)) return 'changed';
  return undefined;
}

function renderValue(value: Value, depth: number, flash?: RowFlash): JSX.Element {
  const table = tableOf(value);
  if (table) {
    const truncated = truncatedOf(value);
    return (
      <>
        <TableView table={table} flash={flash} />
        <Show when={truncated}>
          {(t) => (
            <p class="value-view__truncated">
              showing {t().shown} of {t().total}
            </p>
          )}
        </Show>
      </>
    );
  }

  const opaque = opaqueOf(value);
  if (opaque !== undefined) {
    return (
      <pre class="value-view__opaque">
        <span class="value-view__opaque-type">{opaque.type}</span>
        <code>{opaque.text}</code>
      </pre>
    );
  }

  // A standalone `$truncated` (no sibling `$table`): the whole value was
  // replaced by the marker because of the response-size cap.
  const truncated = truncatedOf(value);
  if (truncated) {
    return (
      <p class="value-view__truncated">
        showing {truncated.shown} of {truncated.total}
      </p>
    );
  }

  if (Array.isArray(value)) {
    return (
      <ul class="value-view__tree">
        <For each={value}>
          {(item) => {
            const key = arrayElementKey(item);
            const flashKind = key !== undefined ? flashKindFor(flash, key) : undefined;
            return <li data-flash={flashKind}>{renderValue(item, depth + 1)}</li>;
          }}
        </For>
      </ul>
    );
  }

  if (isPlainValueObject(value)) {
    const keys = Object.keys(value);
    return (
      <ul class="value-view__tree">
        <For each={keys}>
          {(k) => (
            <li>
              <span class="value-view__key">{k}</span>
              {': '}
              {renderValue(value[k], depth + 1)}
            </li>
          )}
        </For>
      </ul>
    );
  }

  return <span class="value-view__scalar">{formatScalar(value)}</span>;
}

function formatScalar(v: Value): string {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  return String(v);
}

function TableView(props: { table: TableShape; flash?: RowFlash }): JSX.Element {
  const columnCount = props.table.columns.length;
  return (
    <table class="value-view__table">
      <thead>
        <tr>
          <For each={props.table.columns}>{(c) => <th>{c}</th>}</For>
        </tr>
      </thead>
      <tbody>
        <For each={props.table.rows}>
          {(row) => {
            const key = tableRowKey(row, columnCount);
            return (
              <tr classList={{ 'is-tombstoned': isTombstoneRow(row) }} data-flash={flashKindFor(props.flash, key)}>
                <For each={rowCells(row)}>{(cell) => <td>{renderValue(cell, 1)}</td>}</For>
              </tr>
            );
          }}
        </For>
      </tbody>
    </table>
  );
}
