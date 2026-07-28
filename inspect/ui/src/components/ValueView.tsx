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
import './ValueView.css';

/** Renders the contract's `Value` shape (20-api-contract.md "Value"):
 *  `$table` as a data table (tombstoned rows struck through), objects/lists
 *  as an indented tree, `$truncated` as a "showing N of M" note, `opaque` as
 *  a code block, everything else as a scalar (M1-FE ticket Implement §2).
 *  Not itself reactive — `value` is a plain snapshot from the last
 *  `GET .../state`, replaced wholesale on each fetch; recursion is plain
 *  function calls, not nested components, since there is nothing here to
 *  fine-grain update. */
export default function ValueView(props: { value: Value }): JSX.Element {
  return <div class="value-view">{renderValue(props.value, 0)}</div>;
}

function renderValue(value: Value, depth: number): JSX.Element {
  const table = tableOf(value);
  if (table) {
    const truncated = truncatedOf(value);
    return (
      <>
        <TableView table={table} />
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
        <For each={value}>{(item) => <li>{renderValue(item, depth + 1)}</li>}</For>
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

function TableView(props: { table: TableShape }): JSX.Element {
  return (
    <table class="value-view__table">
      <thead>
        <tr>
          <For each={props.table.columns}>{(c) => <th>{c}</th>}</For>
        </tr>
      </thead>
      <tbody>
        <For each={props.table.rows}>
          {(row) => (
            <tr classList={{ 'is-tombstoned': isTombstoneRow(row) }}>
              <For each={rowCells(row)}>{(cell) => <td>{renderValue(cell, 1)}</td>}</For>
            </tr>
          )}
        </For>
      </tbody>
    </table>
  );
}
