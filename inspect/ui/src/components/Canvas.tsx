import { For, Show, createMemo } from 'solid-js';
import type { EdgeRole, Ref } from '../api/types';
import { computeHostHulls, hostFingerprint, type HostHull } from '../layout/hulls';
import { portAnchors } from '../layout/ports';
import { layoutEngine } from '../solid/layout';
import { stateSummaries } from '../solid/detail';
import { errorStore, errorVersion } from '../solid/errors';
import { flowStore, flowVersion } from '../solid/flow';
import { prefersReducedMotion } from '../solid/motion';
import { edges, nodes, selection, setSelection, store, structuralVersion } from '../solid/state';
import { showErrors, showFlow, showHosts, showState } from '../solid/toggles';
import { colorGlyph, manifestBadge, shortType } from '../util/badges';
import { cellErrorBadges, deriveEdgeParkedCounts } from '../util/errors';
import {
  deriveEdgeFlowOverlays,
  flowLabelText,
  flowTooltip,
  formatRoute,
  pulseDurationMsFor,
  pulsesToRender,
  type EdgeFlowOverlay,
} from '../util/flow';
import './Canvas.css';

const FUSED_OFFSET = 2.5;

export default function Canvas() {
  // Structure-only dependency: a value-only change (a lifecycle flip, say)
  // never re-runs layout — "value changes only restyle" (10-target-v3.md UI
  // architecture row; M0-FE ticket Context). The layout engine itself
  // (src/layout/layered.ts) is what makes the *result* insertion-stable;
  // this memo is what makes sure it only runs when it has to.
  const layout = createMemo(() => {
    structuralVersion();
    const refs = [...store.nodes.keys()];
    return layoutEngine.compute(refs, store.adjacency());
  });

  const nodeRefs = createMemo(() => [...layout().nodes.keys()]);
  const edgeIds = createMemo(() => Object.keys(edges));

  // M1-FE ticket Implement §3: "recomputed only on structuralVersion change
  // or host change." A host reassignment alone is a pure value change (does
  // not bump structuralVersion — see sync/diff.ts), so this memo adds its
  // own fingerprint dependency rather than piggy-backing on `layout()`'s.
  const hostFp = createMemo(() => hostFingerprint([...store.nodes.keys()], (ref) => nodes[ref]?.host ?? null));
  const hulls = createMemo<HostHull[]>(() => {
    if (!showHosts()) return [];
    hostFp(); // dep: recompute on host reassignment even without a structural change
    return computeHostHulls([...store.nodes.keys()], layout(), (ref) => nodes[ref]?.host ?? null);
  });

  // M2-FE ticket Implement §2: Errors toggle canvas overlay. Both derivations
  // are pure functions of (current refs/edges, the error store, the toggle) —
  // "badges are value-changes (restyle), never structural" holds automatically
  // here since neither memo depends on structuralVersion/layout(), only on
  // nodeRefs()/edgeIds() (already structural-version-gated) plus errorVersion()
  // and showErrors() (both pure value signals).
  const cellBadges = createMemo(() => {
    errorVersion();
    return cellErrorBadges(
      nodeRefs(),
      (ref) => errorStore.deadLettersFor(ref).length + errorStore.restartsFor(ref).length,
      showErrors(),
    );
  });

  const edgeParked = createMemo(() => {
    errorVersion();
    const targets = edgeIds()
      .map((id) => edges[id])
      .filter((e): e is NonNullable<typeof e> => e !== undefined);
    return deriveEdgeParkedCounts(errorStore.allParked(), targets, showErrors());
  });

  // M3-FE ticket Implement §2: Flow toggle canvas overlay. Same shape as the
  // Errors derivations above — a pure function of (current edges, the flow
  // store, the toggle) gated on flowVersion()/showFlow() rather than
  // structuralVersion/layout(), so a rate update never triggers a re-layout.
  const flowOverlays = createMemo(() => {
    flowVersion();
    const targets = edgeIds().map((id) => ({ id, fused: edges[id]?.fused ?? null }));
    return deriveEdgeFlowOverlays(targets, (id) => flowStore.get(id), showFlow());
  });

  function nameOf(ref: Ref): string | null {
    return nodes[ref]?.name ?? null;
  }

  function anchorOf(ref: Ref, port: string) {
    const ln = layout().nodes.get(ref);
    const rec = nodes[ref];
    if (!ln || !rec) return undefined;
    return portAnchors(ln, rec.ports).get(port);
  }

  function onCardKeyDown(e: KeyboardEvent, ref: Ref) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setSelection(ref);
    }
  }

  // Click-through-to-deselect only when the click landed on the scene
  // background itself, not a card bubbling up.
  function onSceneClick(e: MouseEvent) {
    if (e.currentTarget === e.target) setSelection(null);
  }

  return (
    <div class="canvas">
      <Show when={nodeRefs().length > 0} fallback={<p class="canvas__empty">No cells reported yet.</p>}>
        <div
          class="canvas__scene"
          style={{ width: `${layout().width}px`, height: `${layout().height}px` }}
          onClick={onSceneClick}
        >
          <svg class="canvas__svg" width={layout().width} height={layout().height}>
            {/* Hulls first: SVG paints in document order, so "rendered beneath
                edges" (10-target-v3.md toggle table) means listing them
                before the edges/ports below. */}
            <For each={hulls()}>
              {(h) => (
                <g class="host-hull">
                  <rect class="host-hull__rect" x={h.x} y={h.y} width={h.w} height={h.h} rx={12} ry={12} />
                  <text class="host-hull__label" x={h.x + 10} y={h.y + 16}>
                    {h.host}
                  </text>
                </g>
              )}
            </For>

            <For each={edgeIds()}>
              {(id) => {
                const e = () => edges[id];
                const from = () => (e() ? anchorOf(e()!.from.ref, e()!.from.port) : undefined);
                const to = () => (e() ? anchorOf(e()!.to.ref, e()!.to.port) : undefined);
                const overlay = () => flowOverlays().get(id);
                const tooltip = () =>
                  showFlow() && e() ? flowTooltip(formatRoute(e()!.from, e()!.to, nameOf), overlay()) : undefined;
                return (
                  <Show when={e() && from() && to()}>
                    <EdgeLine
                      role={e()!.role}
                      fused={e()!.fused === true}
                      x1={from()!.x}
                      y1={from()!.y}
                      x2={to()!.x}
                      y2={to()!.y}
                      flow={overlay()}
                      reducedMotion={prefersReducedMotion()}
                      tooltip={tooltip()}
                    />
                  </Show>
                );
              }}
            </For>

            <For each={nodeRefs()}>
              {(ref) => {
                const rec = () => nodes[ref];
                const ln = () => layout().nodes.get(ref);
                const anchors = () => (rec() && ln() ? portAnchors(ln()!, rec()!.ports) : undefined);
                return (
                  <For each={rec()?.ports ?? []}>
                    {(p) => {
                      const a = () => anchors()?.get(p.name);
                      return (
                        <Show when={a()}>
                          <circle class="port-dot" data-dir={p.dir} cx={a()!.x} cy={a()!.y} r="3">
                            <title>
                              {p.name} ({p.dir})
                            </title>
                          </circle>
                        </Show>
                      );
                    }}
                  </For>
                );
              }}
            </For>
          </svg>

          <For each={nodeRefs()}>
            {(ref) => {
              const rec = () => nodes[ref];
              const ln = () => layout().nodes.get(ref);
              return (
                <Show when={rec() && ln()}>
                  <div
                    class="node-card"
                    classList={{
                      'is-selected': selection() === ref,
                      'is-suspended': rec()!.lifecycle === 'SUSPENDED',
                      'is-erring': !!cellBadges().get(ref),
                    }}
                    style={{
                      left: `${ln()!.x}px`,
                      top: `${ln()!.y}px`,
                      width: `${ln()!.w}px`,
                      height: `${ln()!.h}px`,
                    }}
                    role="button"
                    tabIndex={0}
                    aria-pressed={selection() === ref}
                    onClick={(e) => {
                      e.stopPropagation();
                      setSelection(ref);
                    }}
                    onKeyDown={(e) => onCardKeyDown(e, ref)}
                  >
                    <div class="node-card__top">
                      <span
                        class="node-card__chip"
                        data-color={rec()!.color ?? 'unknown'}
                        title={rec()!.color ?? 'color unknown'}
                      >
                        {colorGlyph(rec()!.color)}
                      </span>
                      <span class="node-card__name">{rec()!.name ?? ref.slice(0, 8)}</span>
                    </div>
                    <div class="node-card__type" title={rec()!.typeFqn}>
                      {shortType(rec()!.typeFqn)}
                    </div>
                    <Show when={rec()!.manifests.length}>
                      <div class="node-card__badges">
                        <For each={rec()!.manifests}>
                          {(m) => (
                            <span class="node-card__badge" title={m}>
                              {manifestBadge(m)}
                            </span>
                          )}
                        </For>
                      </div>
                    </Show>
                  </div>
                </Show>
              );
            }}
          </For>

          {/* State toggle chips — a separate absolutely-positioned layer
              (not nested inside `.node-card`, which clips overflow for its
              own text-ellipsis rows) anchored just below each card. M1-FE
              ticket "Correction for clarity": driven purely by received
              `state.summary` events; only the observed (== selected) cell
              ever has one, so an unselected cell simply shows no chip. */}
          <For each={nodeRefs()}>
            {(ref) => {
              const ln = () => layout().nodes.get(ref);
              const summary = () => stateSummaries[ref];
              return (
                <Show when={showState() && ln() && summary()}>
                  <div
                    class="node-state-chip"
                    style={{ left: `${ln()!.x}px`, top: `${ln()!.y + ln()!.h + 4}px`, width: `${ln()!.w}px` }}
                    title="cardinality · frontier · staleness"
                  >
                    {summary()!.cardinality ?? '—'} ·{' '}
                    {summary()!.frontier ? `${summary()!.frontier!.source.slice(0, 6)}·${summary()!.frontier!.counter}` : '—'} ·{' '}
                    {summary()!.staleMs}ms
                  </div>
                </Show>
              );
            }}
          </For>

          {/* Errors toggle: red badge with count, one per erring cell
              (10-target-v3.md Errors toggle: "Red badges on erring cells";
              M2-FE ticket Implement §2). A separate layer for the same
              reason the state chip above is: `.node-card` clips via
              `overflow: hidden`, and this badge deliberately pokes past the
              card's top-right corner. */}
          <For each={nodeRefs()}>
            {(ref) => {
              const ln = () => layout().nodes.get(ref);
              const count = () => cellBadges().get(ref);
              return (
                <Show when={ln() && count()}>
                  <div
                    class="node-error-badge"
                    style={{ left: `${ln()!.x + ln()!.w}px`, top: `${ln()!.y}px` }}
                    title={`${count()} error${count() === 1 ? '' : 's'} (dead letters + restarts)`}
                  >
                    {count()}
                  </div>
                </Show>
              );
            }}
          </For>

          {/* Errors toggle: amber "n parked" pill at the midpoint of every
              edge whose target (ref, port) has parked traffic
              (10-target-v3.md Errors toggle: "amber 'n parked' pills on
              edges"; M2-FE ticket Implement §2). */}
          <For each={edgeIds()}>
            {(id) => {
              const e = () => edges[id];
              const from = () => (e() ? anchorOf(e()!.from.ref, e()!.from.port) : undefined);
              const to = () => (e() ? anchorOf(e()!.to.ref, e()!.to.port) : undefined);
              const count = () => edgeParked().get(id);
              return (
                <Show when={e() && from() && to() && count()}>
                  <div
                    class="edge-parked-pill"
                    style={{
                      left: `${(from()!.x + to()!.x) / 2}px`,
                      top: `${(from()!.y + to()!.y) / 2}px`,
                    }}
                    title={`${count()} parked`}
                  >
                    ▮ {count()} parked
                  </div>
                </Show>
              );
            }}
          </For>

          {/* Flow toggle: rate label ("N.n/s") or "fused" label at the edge
              midpoint (10-target-v3.md Flow toggle: "rate labels at edge
              midpoints; fused edges marked, never animated"; M3-FE ticket
              Implement §2). Offset a few px above the parked pill's own
              midpoint position so the two overlays stay legible together
              when both toggles are on. */}
          <For each={edgeIds()}>
            {(id) => {
              const e = () => edges[id];
              const from = () => (e() ? anchorOf(e()!.from.ref, e()!.from.port) : undefined);
              const to = () => (e() ? anchorOf(e()!.to.ref, e()!.to.port) : undefined);
              const overlay = () => flowOverlays().get(id);
              return (
                <Show when={e() && from() && to() && overlay()}>
                  <div
                    class="edge-flow-label"
                    classList={{ 'edge-flow-label--fused': overlay()!.kind === 'fused' }}
                    style={{
                      left: `${(from()!.x + to()!.x) / 2}px`,
                      top: `${(from()!.y + to()!.y) / 2 - 11}px`,
                    }}
                  >
                    {flowLabelText(overlay()!)}
                  </div>
                </Show>
              );
            }}
          </For>
        </div>
      </Show>
    </div>
  );
}

function EdgeLine(props: {
  role: EdgeRole;
  fused: boolean;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  /** Flow toggle overlay for this edge — `undefined` when the toggle is off
   *  or this edge has nothing to show (10-target-v3.md Flow toggle). */
  flow?: EdgeFlowOverlay;
  reducedMotion: boolean;
  /** Hover tooltip text (M3-FE ticket Implement §3) — `undefined` renders no
   *  hit-line at all, so the base `pointer-events: none` click-through
   *  behavior (`.canvas__svg`) is unaffected while the toggle is off. */
  tooltip?: string;
}) {
  const dx = () => props.x2 - props.x1;
  const dy = () => props.y2 - props.y1;
  const len = () => Math.hypot(dx(), dy()) || 1;
  const nx = () => (-dy() / len()) * FUSED_OFFSET;
  const ny = () => (dx() / len()) * FUSED_OFFSET;
  const dash = () => (props.role === 'OBSERVE' ? '5 3' : undefined);
  const cls = () => `edge edge--${props.role.toLowerCase()}`;

  // M3-FE ticket Implement §2: "when the [Flow] toggle is on, make the
  // fused state visibly explicit" (thick stroke, on top of the M0/M1 base
  // double-offset-line rendering below) — and, for an active (non-fused)
  // edge under `prefers-reduced-motion`, a static per-band intensity class
  // ("static intensity styling instead of pulses") in place of the moving
  // dots this same overlay would otherwise render.
  const activeBand = () => (props.flow?.kind === 'active' ? props.flow.band : undefined);
  const flowLineCls = () =>
    props.flow?.kind === 'fused' ? ' flow-fused' : activeBand() !== undefined ? ' flow-active' : '';

  const pathD = () => `M ${props.x1} ${props.y1} L ${props.x2} ${props.y2}`;
  const pulseCount = () => (props.flow ? pulsesToRender(props.flow, props.reducedMotion) : 0);
  const pulseDurationMs = () => (activeBand() !== undefined ? pulseDurationMsFor(activeBand()!) : 0);

  return (
    <>
      <Show
        when={props.fused}
        fallback={
          <line
            class={cls() + flowLineCls()}
            data-band={activeBand()}
            x1={props.x1}
            y1={props.y1}
            x2={props.x2}
            y2={props.y2}
            stroke-dasharray={dash()}
          />
        }
      >
        <g class={`${cls()} is-fused${flowLineCls()}`}>
          <line
            x1={props.x1 + nx()}
            y1={props.y1 + ny()}
            x2={props.x2 + nx()}
            y2={props.y2 + ny()}
            stroke-dasharray={dash()}
          />
          <line
            x1={props.x1 - nx()}
            y1={props.y1 - ny()}
            x2={props.x2 - nx()}
            y2={props.y2 - ny()}
            stroke-dasharray={dash()}
          />
        </g>
      </Show>

      {/* Flow toggle: a wide, invisible hit-line carrying the hover tooltip
          (M3-FE ticket Implement §3). The visible line(s) above stay under
          `.canvas__svg`'s blanket `pointer-events: none`, preserving normal
          click-through-to-deselect; `.edge-hit` (Canvas.css) is the one
          element that opts back into pointer events, and only exists while
          `tooltip` is set (i.e. the Flow toggle is on). */}
      <Show when={props.tooltip}>
        <line class="edge-hit" x1={props.x1} y1={props.y1} x2={props.x2} y2={props.y2}>
          <title>{props.tooltip}</title>
        </line>
      </Show>

      {/* Flow toggle: pulses travelling source -> target, count/speed
          stepped by rate band, never per-message (10-target-v3.md Flow
          toggle: "amber pulses travelling along edges"; M3-FE ticket
          Implement §2). SMIL `animateMotion` moves each circle along the
          exact same (x, y) pair the line itself uses — no separate
          coordinate-space translation to keep in sync with the layout.
          `pulseCount() === 0` whenever `reducedMotion` is true (see
          `pulsesToRender`), so this whole block renders nothing then — the
          static `.flow-active[data-band]` styling above carries the signal
          instead. */}
      <Show when={pulseCount() > 0}>
        <For each={Array.from({ length: pulseCount() }, (_, i) => i)}>
          {(i) => (
            <circle class="flow-pulse" r="3">
              <animateMotion
                path={pathD()}
                dur={`${pulseDurationMs()}ms`}
                begin={`${(i * pulseDurationMs()) / pulseCount()}ms`}
                repeatCount="indefinite"
              />
            </circle>
          )}
        </For>
      </Show>
    </>
  );
}
