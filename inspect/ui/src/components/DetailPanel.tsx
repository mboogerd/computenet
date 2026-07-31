import { For, Show, createMemo, type JSX } from 'solid-js';
import type { CellState, DeadLetterEntry, Frontier, ParkedEntry, RestartEntry, Value, WaveHealthEntry, WaveHealthKind } from '../api/types';
import { capitalize, colorGlyph, manifestBadge, shortType } from '../util/badges';
import { portFlowRows, type PortFlowRow } from '../util/flow';
import { buildSupervisionTimeline, type SupervisionStep } from '../util/supervision';
import {
  caveatNote,
  exclusivesElidedLabel,
  isStaleProvenance,
  pageCounterText,
  provenanceLabel,
  unavailableMessage,
  walkStableNote,
  WALK_RESTARTED_NOTE,
  WALK_STUCK_NOTE,
} from '../util/statePresentation';
import { COLD_FLOW_NOTICE, COLD_STATE_NOTICE } from '../nav/cold';
import { currentGraphCold } from '../solid/cold';
import {
  cellDetail,
  cellState,
  changeLog,
  changeLogVersion,
  detailError,
  detailLoading,
  isPinned,
  loadNextStatePage,
  pin,
  stateError,
  stateLoading,
  stateWalk,
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
  // V1C-FE: `cellState()` is only ever refreshed by the BASE fetch (page 1,
  // or a plain "view"/"snapshot" read) — a subsequent `loadNextStatePage()`
  // (another page, or a 410's silent restart) updates only `stateWalk()`, not
  // `cellState()`. So the section's meta (kind/provenance/frontier/staleMs)
  // must read from whichever is actually the latest response for the current
  // selection, or a restarted walk's new page 1 would render under the OLD
  // page 1's now-stale `kind`/`provenance`. Falls back to `cellState()`
  // itself whenever the walk's own ref has not (yet) caught up to it (a
  // fresh selection, mid-render, before its `seed()` call lands) — see this
  // function's module doc for why that moment is safe rather than an
  // inconsistent flash.
  const displayState = createMemo<CellState | null>(() => {
    const base = cellState();
    if (!base) return null;
    const walk = stateWalk();
    return walk.ref === base.ref && walk.latest ? walk.latest : base;
  });

  const frontierLabel = createMemo(() => formatFrontier(displayState()?.frontier));

  // V1A-FE ticket Implement §2: holds the previously rendered value across
  // renders (plain closure variables — this setup function runs once per
  // component mount, exactly like the `frontierLabel` memo above), reset
  // whenever the selection changes so a freshly selected cell's first paint
  // never flashes against a different cell's last value.
  //
  // V1C-FE ticket Solution direction §1 ("a page append is not a change"):
  // `kind === 'page'` always gets the empty flash. Appending a page to an
  // accumulated walk would otherwise flash the whole appended page as
  // "added" — those entries were always part of the cell's state, this
  // client had simply not fetched them yet.
  let prevRef: string | null = null;
  let prevValue: Value | undefined;
  const flash = createMemo<RowFlash>(() => {
    const ref = selection();
    const st = displayState();
    if (ref !== prevRef) {
      prevRef = ref;
      prevValue = undefined;
    }
    if (st?.kind === 'page') return { added: new Set<string>(), changed: new Set<string>() };
    const value = st?.value;
    const result = value === undefined ? { added: new Set<string>(), changed: new Set<string>() } : diffRows(prevValue, value);
    prevValue = value;
    return result;
  });

  // V1C-FE: for `kind === 'page'` render the WALK's accumulated value (the
  // union of every page fetched so far) rather than the latest response's own
  // `value`, which is only ever the most recently landed page on its own.
  const renderedValue = createMemo<Value | undefined>(() => {
    const s = displayState();
    if (s?.kind !== 'page') return s?.value;
    const walk = stateWalk();
    return walk.ref === s.ref && walk.value !== null ? walk.value : s.value;
  });

  return (
    <Section title="State">
      {/* M5-NET: a remote cell's state is never requested — not locally
          hosted, nothing to read. */}
      <Show when={!remoteSelected()} fallback={<p class="detail-section__status">{REMOTE_NOTICE}</p>}>
      <Show when={!stateLoading()} fallback={<p class="detail-section__status">Loading…</p>}>
        <Show
          when={displayState()}
          fallback={<p class="detail-section__status detail-section__status--error">{describeError(stateError())}</p>}
        >
          {(s) => (
            <>
              {/* V1C-FE ticket Solution direction §3: a cold selection now
                  reads real state (V1C-BE makes a parked cell's read cost
                  nothing causal), so this is an informational line ABOVE the
                  value rather than a replacement for the whole section. */}
              <Show when={currentGraphCold()}>
                <p class="detail-section__status detail-section__status--cold">{COLD_STATE_NOTICE}</p>
              </Show>
              <div class="state-meta">
                <span class="state-meta__frontier mono" title="frontier stamp (source · counter)">
                  {frontierLabel()}
                </span>
                {/* V1C-FE ticket Solution direction §2: the contract pins
                    staleMs to 0 for "page"/"snapshot", so rendering it there
                    would print "0ms stale" over a checkpoint written an hour
                    ago. Only "view" has a real freshness claim to make. */}
                <Show when={s().kind === 'view'}>
                  <span class="state-meta__stale">{s().staleMs}ms stale</span>
                </Show>
                <span class="state-meta__kind">{s().kind}</span>
              </div>
              {/* V1C-FE ticket Solution direction §2: provenance — never
                  rendered for `null` (a "view", or "unavailable"; §0: "never
                  default provenance to 'live'"). `checkpoint` gets the stale
                  register; `live`/`liveSuspended` do not. */}
              <Show when={provenanceLabel(s().provenance)}>
                {(label) => (
                  <p
                    class="state-provenance"
                    classList={{ 'state-provenance--stale': isStaleProvenance(s().provenance) }}
                  >
                    {label()}
                  </p>
                )}
              </Show>
              <Show
                when={s().kind !== 'unavailable'}
                fallback={<p class="detail-section__status">{unavailableMessage(s().unreadable)}</p>}
              >
                <ValueView value={renderedValue() ?? s().value} flash={flash()} />
                <Show when={s().kind === 'page'}>
                  <StatePageControls state={s()} />
                </Show>
              </Show>
            </>
          )}
        </Show>
      </Show>
      {/* V1A-FE ticket Implement §3: the onChange log — a cold or remote
          selection is never observed, so it has no log. NOT gated on
          `stateLoading()`/`cellState()` — the log reflects the observation's
          history, not the latest fetch's own loading state. */}
      <Show when={!currentGraphCold()}>
        <ChangeLogPanel />
      </Show>
      </Show>
      <p class="detail-section__footnote">per-cell consistent — cross-panel alignment not guaranteed</p>
    </Section>
  );
}

/** V1C-FE ticket Solution direction §1/§2: everything the page WALK itself
 *  contributes below the rendered value — the elided-exclusives count, the
 *  `walkStable` note, the shape-mismatch note, the 410-restart/stuck notes,
 *  and the page counter + "Load next page" control. Gated on `kind ===
 *  'page'` by the caller; `isCurrent` is a belt-and-suspenders guard against
 *  rendering a walk snapshot left over from a just-superseded selection (the
 *  two signals update together in practice — see `solid/detail.ts`'s
 *  `onState` — but nothing here should silently trust that). */
function StatePageControls(props: { state: CellState }) {
  const walk = createMemo(() => stateWalk());
  const isCurrent = createMemo(() => walk().ref === props.state.ref);

  return (
    <Show when={isCurrent()}>
      <div class="state-page">
        <Show when={walk().exclusivesElidedTotal > 0}>
          <p class="state-page__exclusives">{exclusivesElidedLabel(walk().exclusivesElidedTotal)}</p>
        </Show>
        <Show when={walkStableNote(walk().walkStable)}>
          {(note) => <p class="state-page__smeared">{note()}</p>}
        </Show>
        {/* C10: `page.caveats` — the kernel's own declared weakenings for
            this walk, forwarded rather than inferred. Not in the draft
            contract this ticket was written against; dropping them would
            have let the counter's "N entries — complete" stand unqualified
            over a positional walk that may have skipped an entry. See
            `caveatNote` for why `staleFrontier` deliberately renders
            nothing. */}
        <For each={walk().caveats}>
          {(c) => <Show when={caveatNote(c)}>{(note) => <p class="state-page__caveat">{note()}</p>}</Show>}
        </For>
        <Show when={!walk().merged}>
          <p class="state-page__mismatch">Pages did not share a shape — rendered separately, not merged.</p>
        </Show>
        <Show when={walk().restarted}>
          <p class="state-page__restarted">{WALK_RESTARTED_NOTE}</p>
        </Show>
        <Show when={walk().stuck}>
          <p class="state-page__stuck">{WALK_STUCK_NOTE}</p>
        </Show>
        {/* C10: `page.attributes` — cell-level state that is not a per-entry
            row and rides every page (a set's tag counter, a shard's assigned
            epoch). The server surfaces these deliberately, so that a client
            reading page 4 of a shard walk can tell whether the walk straddled
            a repartition; rendering them is the whole point of that. Latest
            page's values, never accumulated. */}
        <Show when={Object.keys(walk().attributes).length > 0}>
          <dl class="state-page__attributes">
            <For each={Object.keys(walk().attributes)}>
              {(k) => (
                <>
                  <dt class="mono">{k}</dt>
                  <dd>
                    <ValueView value={walk().attributes[k]} />
                  </dd>
                </>
              )}
            </For>
          </dl>
        </Show>
        <div class="state-page__footer">
          <span class="state-page__counter">{pageCounterText(walk().entriesTotal, walk().cursor !== null)}</span>
          <Show when={walk().cursor !== null}>
            <button type="button" class="state-page__more" disabled={walk().loading} onClick={() => loadNextStatePage()}>
              {walk().loading ? 'Loading…' : 'Load next page'}
            </button>
          </Show>
        </div>
      </div>
    </Show>
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
      {/* V1C-FE ticket Solution direction §3: no messages flow in a parked
          cone, so a cold selection says so rather than rendering a port
          table of em-dashes that reads as "no traffic" instead of "cannot be
          measured". State's cold gate came off this wave (V1C-BE makes a
          parked read cheap); flow's did not — nothing to gate off from, it
          is genuinely unavailable. */}
      <Show when={!currentGraphCold()} fallback={<p class="detail-section__status detail-section__status--cold">{COLD_FLOW_NOTICE}</p>}>
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
const WAVE_HEALTH_LABEL: Record<WaveHealthKind, string> = {
  frontierLag: 'frontier lag',
  stalledWave: 'stalled wave',
};

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
  // V3: a heuristic diagnostic class of its own — see `errorStore.ts`'s
  // `waveHealthFor` (the `parked` discipline: open rows only, upserted /
  // deleted by `id`, never an append-only log).
  const waveHealth = createMemo<readonly WaveHealthEntry[]>(() => {
    errorVersion();
    const r = ref();
    return r ? errorStore.waveHealthFor(r) : [];
  });
  // V3: replaces the old flat "Restart history" list (Problem #2) — a pure,
  // separately unit-tested builder (`util/supervision.ts`); this memo only
  // feeds it this cell's own rows and follows the same errorVersion()+ref
  // gating as every other memo here, so it never caches across a selection
  // change.
  const timeline = createMemo<readonly SupervisionStep[]>(() => buildSupervisionTimeline(restarts(), deadLetters()));
  const hasAny = createMemo(
    () => deadLetters().length > 0 || parked().length > 0 || restarts().length > 0 || waveHealth().length > 0,
  );

  return (
    <Section title="Errors">
      <Show when={!remoteSelected()} fallback={<p class="detail-section__status">{REMOTE_NOTICE}</p>}>
      <Show when={hasAny()} fallback={<p class="detail-section__status">No local errors</p>}>
        {/* V3: rendered above the append-only groups below and visually
            distinct (informational/amber, never the dead-letter card's red)
            — a heuristic diagnostic is "worth a look", not a defect claim
            (10-design-notes.md §V3; ticket Solution direction §3b). */}
        <Show when={waveHealth().length}>
          <div class="error-group error-group--wave-health">
            <h4 class="error-group__title">
              Wave health <span class="wave-health-heuristic-tag">(heuristic)</span>
            </h4>
            <For each={waveHealth()}>
              {(w) => (
                <div class="wave-health-row">
                  <div class="wave-health-row__head">
                    <span class="wave-health-row__kind">{WAVE_HEALTH_LABEL[w.kind]}</span>
                    <span
                      class="wave-health-row__badge"
                      title="heuristic diagnostic, computed inspector-side — not kernel-grade detection"
                    >
                      heuristic
                    </span>
                  </div>
                  {/* The server writes this and it already contains the word
                      "heuristic" — rendered verbatim, never paraphrased or
                      stripped (ticket). */}
                  <div class="wave-health-row__desc">{w.description}</div>
                  <div class="wave-health-row__meta">
                    <span class="mono" title="last observed wave on the tapped edge -> this cell's frontier (source · counter)">
                      {formatFrontier(w.wave)} → {formatFrontier(w.frontier)}
                      <Show when={w.lagWaves !== null}> ({w.lagWaves} behind)</Show>
                    </span>
                    <span class="detail-muted">held {w.heldMs}ms</span>
                  </div>
                </div>
              )}
            </For>
          </div>
        </Show>

        <Show when={deadLetters().length}>
          <div class="error-group">
            <h4 class="error-group__title">Dead letters</h4>
            <For each={deadLetters()}>
              {(dl) => (
                <div class="dead-letter-card">
                  <div class="dead-letter-card__cause">{dl.cause ?? 'dropped (unknown target)'}</div>
                  <div class="dead-letter-card__desc">{dl.description}</div>
                  {/* V3: the failing call, when the drop happened during an
                      invocation — absent (older server) or `null` (plain
                      host-level drop) renders nothing extra, so a card
                      without the new fields is byte-for-byte unchanged. */}
                  <Show when={dl.invocation}>
                    {(inv) => (
                      <div
                        class="dead-letter-card__invocation mono"
                        title={`parameterTypes: ${inv().parameterTypes.join(', ') || '—'} · argCount: ${inv().argCount}${
                          inv().hop !== null ? ` · hop ${inv().hop}` : ''
                        }`}
                      >
                        {inv().port} · {inv().method} <span class="detail-muted">({inv().type})</span>
                      </div>
                    )}
                  </Show>
                  {/* V3: one chip per sanitized argument. `frozen`/`redacted`
                      are the exclusive-payload cases (an `Owned` arriving
                      frozen, a `Leased` arriving released-and-redacted) — the
                      whole reason the field exists — so they get a visually
                      stronger treatment than `borrowed`/`owned`/`leased`/`plain`. */}
                  <Show when={dl.disposition?.length}>
                    <div class="dead-letter-card__disposition">
                      <For each={dl.disposition}>
                        {(d) => (
                          <span
                            class="disposition-chip"
                            classList={{
                              'disposition-chip--exclusive': d.ownership === 'frozen' || d.ownership === 'redacted',
                            }}
                            title={d.reason ?? undefined}
                          >
                            #{d.index} {d.ownership}
                          </span>
                        )}
                      </For>
                    </div>
                  </Show>
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

        {/* V3: replaces the old flat "Restart history" list — a causal
            sequence (crash -> restart -> re-baseline) per restart, newest
            restart first (ticket Solution direction §3c). */}
        <Show when={timeline().length}>
          <div class="error-group">
            <h4 class="error-group__title">Supervision timeline</h4>
            <ul class="supervision-timeline">
              <For each={timeline()}>
                {(s) => (
                  <li class="supervision-step" data-kind={s.kind}>
                    <span class="supervision-step__glyph" aria-hidden="true">
                      {s.glyph}
                    </span>
                    <span class="supervision-step__label" title={s.detail ?? undefined}>
                      {s.label}
                    </span>
                    <span class="supervision-step__time">{formatTime(s.atMs)}</span>
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
