import { For, Show, createMemo, createSignal } from 'solid-js';
import { activityStore, activityVersion } from '../solid/activity';
import { nodes, selection } from '../solid/state';
import { ACTIVITY_KIND_META, ACTIVITY_ROW_CAP, deriveActivityRows, type ActivityRow } from '../util/activity';
import './ActivityLog.css';

/** V2-FE ticket Implement §5-9: the activity log panel — a collapsible
 *  bottom strip (mounted by `app.tsx`, graph screen only), newest-first,
 *  one row per `ActivityEntry`. The filter ("Only selected cell") is
 *  component-local state (ticket §6: "not in the URL, not in
 *  `solid/toggles.ts`") — a plain `createSignal` here, not threaded through
 *  any shared store. */
export default function ActivityLog() {
  const [collapsed, setCollapsed] = createSignal(true);
  const [onlySelected, setOnlySelected] = createSignal(false);

  const result = createMemo(() => {
    activityVersion();
    return deriveActivityRows(activityStore.entries, {
      onlySelected: onlySelected(),
      selectedRef: selection(),
      nameOf: (ref) => nodes[ref]?.name ?? null,
      cap: ACTIVITY_ROW_CAP,
    });
  });

  return (
    <div class="activity-log" classList={{ 'is-collapsed': collapsed() }}>
      <div class="activity-log__header">
        <button
          type="button"
          class="activity-log__toggle"
          aria-expanded={!collapsed()}
          onClick={() => setCollapsed((c) => !c)}
        >
          <span class="activity-log__chevron">{collapsed() ? '▸' : '▾'}</span>
          Activity
          <Show when={activityStore.entries.length > 0}>
            <span class="activity-log__count">{activityStore.entries.length}</span>
          </Show>
        </button>
        <Show when={!collapsed()}>
          <label
            class="activity-log__filter"
            classList={{ 'is-disabled': !selection() }}
            title={selection() ? 'Restrict rows to the selected cell' : 'Select a cell to filter the log by it'}
          >
            <input
              type="checkbox"
              checked={onlySelected()}
              disabled={!selection()}
              onChange={(e) => setOnlySelected(e.currentTarget.checked)}
            />
            Only selected cell
          </label>
        </Show>
      </div>

      <Show when={!collapsed()}>
        <div class="activity-log__body">
          <Show
            when={result().rows.length > 0}
            fallback={<p class="activity-log__empty">No lifecycle activity yet.</p>}
          >
            <ul class="activity-log__list">
              <For each={result().rows}>{(row) => <ActivityRowView row={row} />}</For>
            </ul>
            <Show when={result().hiddenCount > 0}>
              <p class="activity-log__hidden-note">
                {result().hiddenCount} older {result().hiddenCount === 1 ? 'entry' : 'entries'} not shown (showing the
                newest {ACTIVITY_ROW_CAP}).
              </p>
            </Show>
          </Show>
        </div>
      </Show>
    </div>
  );
}

function ActivityRowView(props: { row: ActivityRow }) {
  const meta = () => ACTIVITY_KIND_META[props.row.kind];
  return (
    <li class="activity-log__row">
      <span class="mono activity-log__time">{props.row.time}</span>
      <span
        class="activity-badge"
        style={{ '--activity-badge-color': `var(${meta().colorVar})` }}
        title={props.row.kind}
      >
        <span class="activity-badge__glyph">{meta().glyph}</span>
        {meta().label}
      </span>
      <span class="activity-log__cell" title={props.row.ref}>
        {props.row.label}
      </span>
      <Show when={props.row.generation !== undefined}>
        <span class="activity-log__gen">gen {props.row.generation}</span>
      </Show>
    </li>
  );
}
