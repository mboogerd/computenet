import { For, Show, createMemo, type JSX } from 'solid-js';
import type { DeadLetterEntry, Frontier, ParkedEntry, RestartEntry, Value } from '../api/types';
import { capitalize, colorGlyph, manifestBadge, shortType } from '../util/badges';
import { portFlowRows, type PortFlowRow } from '../util/flow';
import { COLD_NOTICE } from '../nav/cold';
import { currentGraphCold } from '../solid/cold';
import {
  cellDetail,
  cellState,
  changeLog,
  changeLogVersion,
  detailError,
  detailLoading,
  isPinned,
  pin,
  stateError,
  stateLoading,
  unpin,
} from '../solid/detail';
import { errorStore, errorVersion } from '../solid/errors';
import { flowStore, flowVersion } from '../solid/flow';
import { edges, selection, setSelection } from '../solid/state';
import type { ChangeLogEntry } from '../sync/changeLog';
import { diffRows, type RowFlash } from '../sync/valueDiff';
import { REMOTE_NOTICE, isRemotePlacement } from '../util/placement';
import ValueView from './ValueView';
import './DetailPanel.css';

/** M5-NET Exclusions: a peer-hosted cell shows descriptor + placement, and
 *  this one sentence everywhere else. Derived from the loaded `CellDetail`
 *  rather than the topology store so it agrees with the placement rows
 *  rendered right above it. */
const remoteSelected = () => isRemotePlacement(cellDetail());

/** M1-FE ticket Implement §1: the detail panel always stacks all four
 *  subsections on selection — no perspective switching (that was v2; see
 *  10-target-v3.md "The v3 model"). Selection persists across toggle
 *  changes because it is an independent signal from the toggle set (nothing
 *  here reacts to `solid/toggles.ts`). */
export default function DetailPanel() {
  return (
    <aside class="detail-panel">
      <Show when={selection()} fallback={<p class="detail-panel__empty">Select a node to inspect it.</p>}>
        {(ref) => (
          <>
            <div class="detail-panel__head">
              <h2 class="detail-panel__name">{cellDetail()?.name ?? ref()}</h2>
              <div class="detail-panel__head-actions">
                {/* V1B-FE ticket Solution direction §4: the same pin toggle
                    as the node card, near the close button. Disabled while
                    cold — mirrors the node-card control's gate; pinning a
                    cell must not be a side effect of merely looking at it
                    while its graph is parked. */}
                <button
                  type="button"
                  class="icon-btn detail-panel__pin"
                  classList={{ 'is-pinned': isPinned(ref()) }}
                  disabled={currentGraphCold()}
                  title={
                    currentGraphCold()
                      ? 'Pinning is disabled while this graph is cold'
                      : isPinned(ref())
                        ? 'Unpin (stop observing when not selected)'
                        : 'Pin (keep observing alongside the selection)'
                  }
                  onClick={() => (isPinned(ref()) ? unpin(ref()) : pin(ref()))}
                >
                  📌
                </button>
                <button
                  class="icon-btn detail-panel__close"
                  title="Close (deselect)"
                  onClick={() => setSelection(null)}
                >
                  ×
                </button>
              </div>
            </div>
            <DescriptorSection />
            <StateSection />
            <FlowSection />
            <ErrorsSection />
          </>
        )}
      </Show>
    </aside>
  );
}

function Section(props: { title: string; children: JSX.Element }) {
  return (
    <section class="detail-section">
      <h3 class="detail-section__title">{props.title}</h3>
      <div class="detail-section__body">{props.children}</div>
    </section>
  );
}

/** 10-target-v3.md "Selecting a node shows all of its properties": class,
 *  color, manifests, ports, process host, network host, generation,
 *  lifecycle, attention band — from `GET /cell/{ref}` (`CellDetail`), not
 *  the topology store, per the ticket's explicit source. */
function DescriptorSection() {
  return (
    <Section title="Descriptor & placement">
      <Show when={!detailLoading()} fallback={<p class="detail-section__status">Loading…</p>}>
        <Show
          when={cellDetail()}
          fallback={<p class="detail-section__status detail-section__status--error">{describeError(detailError())}</p>}
        >
          {(d) => (
            <dl class="descriptor-grid">
              <dt>Class</dt>
              <dd class="mono" title={d().typeFqn}>
                {shortType(d().typeFqn)}
              </dd>

              <dt>Color</dt>
              <dd>
                <span class="detail-chip" data-color={d().color ?? 'unknown'} title={d().color ?? 'unknown'}>
                  {colorGlyph(d().color)}
                </span>
              </dd>

              <dt>Manifests</dt>
              <dd>
                <Show when={d().manifests.length} fallback="—">
                  <div class="detail-badges">
                    <For each={d().manifests}>{(m) => <span class="detail-badge" title={m}>{manifestBadge(m)}</span>}</For>
                  </div>
                </Show>
              </dd>

              <dt>Ports</dt>
              <dd>
                <Show when={d().ports.length} fallback="—">
                  <ul class="ports-list">
                    <For each={d().ports}>
                      {(p) => (
                        <li>
                          <span class="mono">{p.name}</span> <span class="detail-muted">({p.dir})</span>
                        </li>
                      )}
                    </For>
                  </ul>
                </Show>
              </dd>

              {/* M5-NET ticket Implement §2: "Placement subsection in the
                  detail panel shows both levels." A peer-announced cell has
                  no process host to report (its location names a bridge, not
                  a ManagedHost) — say so, rather than showing a bare dash
                  that reads as "unknown". */}
              <dt>Process host</dt>
              <dd class="mono">
                <Show when={!isRemotePlacement(d())} fallback={<span class="detail-muted">not reported (remote)</span>}>
                  {d().host}
                </Show>
              </dd>

              <dt>Network host</dt>
              <dd class="mono">
                {d().net ?? '—'}
                <Show when={isRemotePlacement(d())}>
                  {' '}
                  <span class="detail-tag">peer</span>
                </Show>
              </dd>

              <dt>Generation</dt>
              <dd>{d().generation}</dd>

              <dt>Lifecycle</dt>
              <dd>{d().lifecycle}</dd>

              <dt>Attention</dt>
              <dd>
                {/* V2-FE ticket Implement §10: null means the cell's host
                    runs without an attention policy, not "unknown" — say so
                    in the title rather than leaving the dash unexplained.
                    A non-null value is rendered verbatim (capitalized for
                    display only) instead of switched on exhaustively, so an
                    attention band this client has never seen still shows. */}
                <Show
                  when={d().attention !== null}
                  fallback={<span title="no attention policy configured for this cell's host">—</span>}
                >
                  {capitalize(d().attention!)}
                </Show>
              </dd>

              <dt>Links</dt>
              <dd>
                in {d().links.inbound} · out {d().links.outbound} · taps {d().links.taps}
              </dd>
            </dl>
          )}
        </Show>
      </Show>
    </Section>
  );
}

/** M1-FE ticket Implement §2: observe/state fetch is driven from
 *  `solid/detail.ts` (wired to `selection()`); this section only renders
 *  what it is handed. The fixed footnote and staleness/frontier chip are
 *  part of this subsection specifically — F-5 (10-target-v3.md constraint
 *  4): "cross-panel wave alignment is NOT guaranteed". */
/** Shared by the state-meta frontier chip and the change-log entries below —
 *  the ticket's own "reuse the existing frontier-chip formatting" note. */
function formatFrontier(f: Frontier | null | undefined): string {
  return f ? `${f.source.slice(0, 8)} · ${f.counter}` : '—';
}

function StateSection() {
  const frontierLabel = createMemo(() => formatFrontier(cellState()?.frontier));

  // V1A-FE ticket Implement §2: holds the previously rendered value across
  // renders (plain closure variables — this setup function runs once per
  // component mount, exactly like the `frontierLabel` memo above), reset
  // whenever the selection changes so a freshly selected cell's first paint
  // never flashes against a different cell's last value.
  let prevRef: string | null = null;
  let prevValue: Value | undefined;
  const flash = createMemo<RowFlash>(() => {
    const ref = selection();
    const value = cellState()?.value;
    if (ref !== prevRef) {
      prevRef = ref;
      prevValue = undefined;
    }
    const result = value === undefined ? { added: new Set<string>(), changed: new Set<string>() } : diffRows(prevValue, value);
    prevValue = value;
    return result;
  });

  return (
    <Section title="State">
      {/* M5-COLD: inside a cold graph nothing was fetched — no observe, no
          `GET state` — so this says why, rather than rendering a failure for
          a request that was deliberately never made (ticket Implement §2:
          "selection shows descriptor only"). M5-NET: a remote cell's state is
          likewise never requested — not locally hosted, nothing to read. */}
      <Show
        when={!currentGraphCold()}
        fallback={<p class="detail-section__status detail-section__status--cold">{COLD_NOTICE}</p>}
      >
      <Show when={!remoteSelected()} fallback={<p class="detail-section__status">{REMOTE_NOTICE}</p>}>
      <Show when={!stateLoading()} fallback={<p class="detail-section__status">Loading…</p>}>
        <Show
          when={cellState()}
          fallback={<p class="detail-section__status detail-section__status--error">{describeError(stateError())}</p>}
        >
          {(s) => (
            <>
              <div class="state-meta">
                <span class="state-meta__frontier mono" title="frontier stamp (source · counter)">
                  {frontierLabel()}
                </span>
                <span class="state-meta__stale">{s().staleMs}ms stale</span>
                <span class="state-meta__kind">{s().kind}</span>
              </div>
              <Show
                when={s().kind !== 'unavailable'}
                fallback={<p class="detail-section__status">State unavailable for this cell.</p>}
              >
                <ValueView value={s().value} flash={flash()} />
              </Show>
            </>
          )}
        </Show>
      </Show>
      {/* V1A-FE ticket Implement §3: the onChange log — gated on the same
          cold/remote guards as the value above (a cold or remote selection is
          not observed, so it has no log), but NOT on `stateLoading()`/
          `cellState()` — the log reflects the observation's history, not the
          latest fetch's own loading state. */}
      <ChangeLogPanel />
      </Show>
      </Show>
      <p class="detail-section__footnote">per-cell consistent — cross-panel alignment not guaranteed</p>
    </Section>
  );
}

function ChangeLogPanel() {
  const entries = createMemo<readonly ChangeLogEntry[]>(() => {
    changeLogVersion();
    return changeLog.entries;
  });

  return (
    <div class="state-changelog">
      <h4 class="state-changelog__title">Change log</h4>
      <Show when={entries().length} fallback={<p class="detail-section__status">no changes observed yet</p>}>
        <ul class="state-changelog__list">
          <For each={entries()}>
            {(e) => (
              <li>
                <span class="mono">{formatTime(e.atMs)}</span>
                <span class="detail-muted">{e.cardinality ?? '—'}</span>
                <span class="mono" title="frontier stamp (source · counter)">
                  {formatFrontier(e.frontier)}
                </span>
              </li>
            )}
          </For>
        </ul>
      </Show>
    </div>
  );
}

/** M3-FE ticket Implement §4: "Flow subsection (detail panel, replaces
 *  placeholder): per-port table for the selected cell — direction, rate
 *  (sum of that port's edges), last wave; fused ports labeled." The ticket's
 *  parenthetical holds for IN ports; an OUT port's edges are all readings of
 *  one outlet's counter, so summing them would multiply by the fan-out — see
 *  `util/flow.ts`'s `PortFlowRow.rate` (M3-EVAL defect fix). Reads
 *  `flowStore` directly (imperatively), gated on `flowVersion()` +
 *  `selection()`, same pattern as `ErrorsSection` below — not gated on the
 *  canvas Flow *toggle* (10-target-v3.md: "the detail panel is not
 *  perspective-dependent"; only the canvas overlay is toggle-gated, per the
 *  M1/M2 precedent this section follows). */
function FlowSection() {
  const rows = createMemo<readonly PortFlowRow[]>(() => {
    flowVersion();
    const ref = selection();
    const detail = cellDetail();
    if (!ref || !detail) return [];
    return portFlowRows(detail.ports, ref, Object.values(edges), (id) => flowStore.get(id));
  });

  return (
    <Section title="Flow">
      <Show when={!remoteSelected()} fallback={<p class="detail-section__status">{REMOTE_NOTICE}</p>}>
      <Show when={!detailLoading()} fallback={<p class="detail-section__status">Loading…</p>}>
        <Show when={rows().length} fallback={<p class="detail-section__status">This cell has no ports.</p>}>
          <table class="flow-table">
            <thead>
              <tr>
                <th>Port</th>
                <th>Dir</th>
                <th>Rate</th>
                <th>Last wave</th>
              </tr>
            </thead>
            <tbody>
              <For each={rows()}>
                {(r) => (
                  <tr>
                    <td class="mono">{r.port}</td>
                    <td class="detail-muted">{r.dir}</td>
                    <td class="mono">
                      <Show when={!r.fused} fallback={<span class="flow-table__fused">fused</span>}>
                        {r.rate > 0 ? `${r.rate.toFixed(1)}/s` : '—'}
                      </Show>
                    </td>
                    <td class="mono">{r.lastWave ? `${r.lastWave.source.slice(0, 8)} · ${r.lastWave.counter}` : '—'}</td>
                  </tr>
                )}
              </For>
            </tbody>
          </table>
        </Show>
      </Show>
      </Show>
      <p class="detail-section__footnote">1 Hz aggregate — not per-message; per-cell consistent, cross-panel alignment not guaranteed</p>
    </Section>
  );
}

/** M2-FE ticket Implement §3: replaces the M1 placeholder. Reads
 *  `errorStore` directly (imperatively, like `Canvas.tsx`'s badge/pill
 *  memos), gated on `errorVersion()` + `selection()` so it re-renders
 *  exactly when either changes — no subscription lifecycle to manage here
 *  (unlike the State subsection): the error feed is not per-cell observed,
 *  it is a standing SSE stream the whole app already receives (M2-BE ticket
 *  Exclusions: "No per-cell subscriptions for error data"). */
function ErrorsSection() {
  const ref = () => selection();

  const deadLetters = createMemo<readonly DeadLetterEntry[]>(() => {
    errorVersion();
    const r = ref();
    return r ? errorStore.deadLettersFor(r) : [];
  });
  const parked = createMemo<readonly ParkedEntry[]>(() => {
    errorVersion();
    const r = ref();
    return r ? errorStore.parkedFor(r) : [];
  });
  const restarts = createMemo<readonly RestartEntry[]>(() => {
    errorVersion();
    const r = ref();
    return r ? errorStore.restartsFor(r) : [];
  });
  const hasAny = createMemo(() => deadLetters().length > 0 || parked().length > 0 || restarts().length > 0);

  return (
    <Section title="Errors">
      <Show when={!remoteSelected()} fallback={<p class="detail-section__status">{REMOTE_NOTICE}</p>}>
      <Show when={hasAny()} fallback={<p class="detail-section__status">No local errors</p>}>
        <Show when={deadLetters().length}>
          <div class="error-group">
            <h4 class="error-group__title">Dead letters</h4>
            <For each={deadLetters()}>
              {(dl) => (
                <div class="dead-letter-card">
                  <div class="dead-letter-card__cause">{dl.cause ?? 'dropped (unknown target)'}</div>
                  <div class="dead-letter-card__desc">{dl.description}</div>
                  <div class="dead-letter-card__meta">
                    <span class="mono" title="wave stamp (source · counter)">
                      {dl.wave ? `${dl.wave.source.slice(0, 8)} · ${dl.wave.counter}` : '—'}
                    </span>
                    <span>{formatTime(dl.atMs)}</span>
                  </div>
                </div>
              )}
            </For>
          </div>
        </Show>

        <Show when={parked().length}>
          <div class="error-group">
            <h4 class="error-group__title">Parked</h4>
            <ul class="parked-rows">
              <For each={parked()}>
                {(p) => (
                  <li>
                    <span class="mono">{p.port}</span>
                    <span class="parked-rows__count">{p.count} parked</span>
                    <span class="detail-muted">oldest {p.oldestMs}ms ago</span>
                  </li>
                )}
              </For>
            </ul>
          </div>
        </Show>

        <Show when={restarts().length}>
          <div class="error-group">
            <h4 class="error-group__title">Restart history</h4>
            <ul class="restart-rows">
              <For each={restarts()}>
                {(r) => (
                  <li>
                    <span>generation {r.generation}</span>
                    <span>{formatTime(r.atMs)}</span>
                  </li>
                )}
              </For>
            </ul>
          </div>
        </Show>
      </Show>
      </Show>
    </Section>
  );
}

function formatTime(atMs: number): string {
  return new Date(atMs).toLocaleTimeString();
}

function describeError(err: unknown): string {
  if (err instanceof Error) return err.message;
  return err ? String(err) : 'failed to load';
}
