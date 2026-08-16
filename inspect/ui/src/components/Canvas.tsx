import { For, Show, createEffect, createMemo, createSignal, onCleanup, onMount } from 'solid-js';
import type { Dir, EdgeRole, Ref } from '../api/types';
import {
  computeHostHulls,
  computeNetHulls,
  hostFingerprint,
  netFingerprint,
  type HostHull,
  type NetHull,
} from '../layout/hulls';
import { cardAnchor, portAnchors } from '../layout/ports';
import { layoutEngine } from '../solid/layout';
import { currentGraphCold } from '../solid/cold';
import { isPinned, observed, pin, snapshotOnly, stateSummaries, unpin } from '../solid/detail';
import { errorStore, errorVersion } from '../solid/errors';
import { flowStore, flowVersion } from '../solid/flow';
import { prefersReducedMotion } from '../solid/motion';
import { currentGraphId } from '../solid/route';
import { edges, nodes, selection, setSelection, store, structuralVersion } from '../solid/state';
import { showErrors, showFlow, showHosts, showNet, showState } from '../solid/toggles';
import {
  activeKey,
  cursorAnchorRect,
  dismissTooltip,
  elementAnchor,
  hideTooltip,
  reportCursorPosition,
  showTooltip,
  TOOLTIP_ID,
} from '../solid/tooltip';
import {
  ensureFirstFit,
  enterGraphViewport,
  fitToScreen,
  panByAmount,
  setSceneSize,
  setViewSize,
  viewport,
  viewSize,
  zoomBy,
} from '../solid/viewport';
import { colorGlyph, manifestBadge, shortType } from '../util/badges';
import { cellErrorBadges, deriveEdgeParkedCounts } from '../util/errors';
import {
  deriveEdgeFlowOverlays,
  edgeFlowRows,
  edgeRouteRoleRows,
  flowLabelText,
  formatRoute,
  pulseDurationMsFor,
  pulsesToRender,
  type EdgeFlowOverlay,
} from '../util/flow';
import { REMOTE_HOST_LABEL } from '../util/placement';
import ZoomControls from './ZoomControls';
import './Canvas.css';

const FUSED_OFFSET = 2.5;

/** Drag-vs-click movement threshold, in client px (FE-CANVAS ticket
 *  Solution direction §4: "prescribe 4 px"). Below it, a pointerdown/up
 *  pair on the background is a click (deselects); at or above it, it was a
 *  pan, and the resulting `click` must not also deselect. */
const DRAG_THRESHOLD_PX = 4;
/** Multiplicative step for one keyboard/button zoom action (`+`/`-`, the
 *  zoom controls' `+`/`-` buttons) — not specified numerically by the
 *  ticket; chosen for a visibly-stepped-but-not-jumpy feel. */
const ZOOM_STEP = 1.2;
/** Wheel-zoom sensitivity: `factor = exp(-deltaY * WHEEL_ZOOM_SENSITIVITY)`,
 *  continuous rather than stepped so a trackpad pinch (which arrives as many
 *  small-`deltaY` wheel events) feels smooth. Tuned so one full mouse-wheel
 *  notch (`deltaY` ~ ±100–120, browser-dependent) is roughly a 12–13% step —
 *  a single higher-sensitivity value (0.01) made one notch a ~3.3x jump,
 *  confirmed live against the mock backend during the manual pass. */
const WHEEL_ZOOM_SENSITIVITY = 0.0012;

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

  // M5-NET ticket Implement §2: the Network hosts toggle. Same shape as the
  // process-host memo above — its own fingerprint dependency, because a net
  // reassignment (a peer reconnecting under a new label) is a pure value
  // change and does not bump structuralVersion.
  const netFp = createMemo(() => netFingerprint([...store.nodes.keys()], (ref) => nodes[ref]?.net ?? null));
  const netHulls = createMemo<NetHull[]>(() => {
    if (!showNet()) return [];
    netFp();
    hostFp();
    return computeNetHulls(
      [...store.nodes.keys()],
      layout(),
      (ref) => nodes[ref]?.net ?? null,
      (ref) => nodes[ref]?.host ?? null,
    );
  });

  // M2-FE ticket Implement §2: Errors toggle canvas overlay. Both derivations
  // are pure functions of (current refs/edges, the error store, the toggle) —
  // "badges are value-changes (restyle), never structural" holds automatically
  // here since neither memo depends on structuralVersion/layout(), only on
  // nodeRefs()/edgeIds() (already structural-version-gated) plus errorVersion()
  // and showErrors() (both pure value signals).
  // computenet-0994: only FAULT dead letters (denial == null) count toward
  // the red "erring" badge — a BoundaryPolicy refusal is never a cell fault
  // (SEC1-29), so a pure-refusal graph must not light up the canvas as if
  // cells were failing. `cellDenialBadges` below is the separate,
  // not-a-fault (`--wave-health` register) count for the same cells.
  const cellBadges = createMemo(() => {
    errorVersion();
    return cellErrorBadges(
      nodeRefs(),
      (ref) =>
        errorStore.deadLettersFor(ref).filter((dl) => dl.denial == null).length + errorStore.restartsFor(ref).length,
      showErrors(),
    );
  });

  // computenet-0994: BoundaryPolicy refusals for this cell, reported
  // separately from the fault badge above — same `cellErrorBadges` helper,
  // filtered the other way (`denial != null`).
  const cellDenialBadges = createMemo(() => {
    errorVersion();
    return cellErrorBadges(nodeRefs(), (ref) => errorStore.deadLettersFor(ref).filter((dl) => dl.denial != null).length, showErrors());
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

  // FE-CANVAS ticket Solution direction §2 + §5: one canvas viewport per
  // graph. Canvas only mounts while the Graph screen shows a *hot* graph
  // (`app.tsx`'s `Show when={!currentGraphCold()} fallback={<ColdScreen />}>`
  // around `<Canvas />`, itself inside `Show when={screen() === 'graph'}`),
  // so a Canvas mount/unmount already coincides with "entering"/"leaving" a
  // graph in this app's navigation model — there is no way to swap
  // `currentGraphId` while a single Canvas instance stays mounted. Reading
  // it once here (not inside an effect) captures the graph id for this
  // mount, exactly once, before the first render — restoring a stored
  // viewport verbatim, or resetting to identity and leaving the first-entry
  // fit to the layout-size effect below.
  const graphId = currentGraphId() ?? '';
  enterGraphViewport(graphId);

  let canvasEl: HTMLDivElement | undefined;
  const [isPanning, setIsPanning] = createSignal(false);

  // Guards the race the ticket's "the first render is an empty scene"
  // assumption doesn't cover: switching *from one graph to another*
  // (Home -> graph A -> Home -> graph B) leaves the shared, module-level
  // `store` (`solid/state.ts`) holding graph A's — or Home's unfiltered —
  // nodes for one or more renders after Canvas has already mounted for B,
  // because `TopologyClient.setGraphFilter`'s refetch is async and nothing
  // clears the store while it's in flight. Without this check,
  // `ensureFirstFit` would fit against the *stale* (wrong, and generally
  // larger) scene the instant it first sees a non-zero size, then never
  // correct itself once the right snapshot lands (`resolvedGraphIds` in
  // `solid/viewport.ts` treats that graph id as done). Every node the sync
  // layer hands back is stamped with the component id it belongs to
  // (`NodeRec.graph`, non-null since M4) — trusting the scene only once
  // every currently-known node actually carries *this* graph's id is a
  // direct, local test for "the filtered snapshot has actually landed",
  // with no change to the sync layer itself.
  const sceneReadyForFit = createMemo(() => nodeRefs().every((ref) => nodes[ref]?.graph === graphId));

  // First-entry fit (Solution direction §2): "entering a graph with no
  // stored viewport fits it to screen once the layout first reports a
  // non-zero size" — the topology fetch is async, so the first render or
  // two is an empty scene. `resolvedGraphIds` inside `solid/viewport.ts`
  // makes every call after the first successful one a no-op, so a later
  // structural change (a node arriving after the user has since panned/
  // zoomed) never re-fits and discards it.
  //
  // Reading `viewSize()` here — unconditionally, every run, not only
  // indirectly via `ensureFirstFit`'s own internal read — matters more than
  // it looks: a `ResizeObserver` typically notifies exactly once for a
  // `.canvas` box whose size never changes again afterward (this ticket's
  // own manual pass confirmed it), so if that one notification lands before
  // `sceneReadyForFit()` first turns true, an effect that only reaches
  // `viewSize()` from inside that gated branch is not yet subscribed to it
  // when the one-and-only update fires — it never sees a later value, and
  // the fit deadlocks forever. Reading it up front every run means this
  // effect is subscribed to `viewSize` from its very first execution, so
  // whichever of "the topology settles" and "the canvas is measured"
  // happens last is guaranteed to trigger the re-run that completes the fit.
  createEffect(() => {
    setSceneSize({ w: layout().width, h: layout().height });
    viewSize();
    if (sceneReadyForFit()) ensureFirstFit(graphId);
  });

  // The live client size of `.canvas` (Solution direction §5): a
  // `ResizeObserver` rather than a one-time read, so a window resize or a
  // detail-panel width change never leaves a stale fit basis — the *next*
  // Fit/`0`/first-entry action always measures the canvas as it is now, not
  // as it was at mount. `ResizeObserver` fires once synchronously on
  // `observe()`, so this doubles as the initial read too.
  onMount(() => {
    if (!canvasEl) return;
    const el = canvasEl;
    const ro = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      setViewSize({ w: entry.contentRect.width, h: entry.contentRect.height });
      if (sceneReadyForFit()) ensureFirstFit(graphId);
    });
    ro.observe(el);
    onCleanup(() => ro.disconnect());
  });

  // Wheel (Solution direction §4): registered explicitly with
  // `{ passive: false }` so `preventDefault()` actually stops the browser's
  // own page-zoom/scroll — a passive listener cannot. Ctrl/meta-modified
  // wheel zooms, cursor-anchored (macOS trackpad pinch arrives as a wheel
  // event with `ctrlKey: true`, so pinch is covered by the same path); plain
  // wheel pans. Coalesced to at most one update per animation frame: rapid
  // trackpad events accumulate into `pendingPan`/`pendingZoomFactor` and a
  // single `requestAnimationFrame` flushes them, rather than committing a
  // viewport update per DOM event.
  let pendingPanDx = 0;
  let pendingPanDy = 0;
  let pendingZoomFactor = 1;
  let pendingZoomAnchor: { x: number; y: number } | null = null;
  let flushScheduled = false;

  function scheduleFlush(): void {
    if (flushScheduled) return;
    flushScheduled = true;
    requestAnimationFrame(() => {
      flushScheduled = false;
      if (pendingPanDx !== 0 || pendingPanDy !== 0) {
        panByAmount(pendingPanDx, pendingPanDy);
        pendingPanDx = 0;
        pendingPanDy = 0;
      }
      if (pendingZoomAnchor && pendingZoomFactor !== 1) {
        zoomBy(pendingZoomFactor, pendingZoomAnchor);
        pendingZoomFactor = 1;
        pendingZoomAnchor = null;
      }
    });
  }

  function onWheel(e: WheelEvent): void {
    e.preventDefault();
    if (!canvasEl) return;
    if (e.ctrlKey || e.metaKey) {
      const rect = canvasEl.getBoundingClientRect();
      pendingZoomAnchor = { x: e.clientX - rect.left, y: e.clientY - rect.top };
      pendingZoomFactor *= Math.exp(-e.deltaY * WHEEL_ZOOM_SENSITIVITY);
    } else {
      pendingPanDx += e.deltaX;
      pendingPanDy += e.deltaY;
    }
    scheduleFlush();
  }

  onMount(() => {
    if (!canvasEl) return;
    const el = canvasEl;
    el.addEventListener('wheel', onWheel, { passive: false });
    onCleanup(() => el.removeEventListener('wheel', onWheel));
  });

  // Keyboard (Solution direction §5): bound on the Graph screen only — this
  // listener lives for exactly as long as Canvas is mounted, which (see the
  // viewport-mount comment above) is exactly the hot-graph lifetime of the
  // Graph screen. Ignored while typing in a field, and while a modifier
  // that means something else (Ctrl/Cmd browser zoom, in particular) is
  // held. Enter/Space on a focused card is handled by `onCardKeyDown` below
  // and is untouched by this listener (neither key is in its set).
  function onWindowKeyDown(e: KeyboardEvent): void {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const target = e.target;
    if (target instanceof HTMLElement) {
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return;
    }
    if (e.key === '+' || e.key === '=') {
      e.preventDefault();
      zoomBy(ZOOM_STEP);
    } else if (e.key === '-' || e.key === '_') {
      e.preventDefault();
      zoomBy(1 / ZOOM_STEP);
    } else if (e.key === '0') {
      e.preventDefault();
      fitToScreen();
    }
  }

  onMount(() => {
    window.addEventListener('keydown', onWindowKeyDown);
    onCleanup(() => window.removeEventListener('keydown', onWindowKeyDown));
  });

  // Drag-to-pan (Solution direction §4): `pointerdown` on the scene
  // background, or on `.edge-hit` — the exact same `e.currentTarget ===
  // e.target` test `onSceneClick` already used pre-ticket, plus an explicit
  // allowance for the hit-line, so a pointerdown that bubbled up from a
  // *card* still never starts a pan. `setPointerCapture` means a drag that
  // leaves the browser window still ends cleanly on `pointerup`/
  // `pointercancel`. `dragMoved` (not reset until the following `click`) is
  // how `onSceneClick` tells a real drag apart from a below-threshold click.
  //
  // FE-TOOLTIPS ticket Solution direction §4: `.edge-hit` is now always
  // rendered (not just while the Flow toggle is on), so a pointerdown that
  // lands on one is no longer rare. The ticket asks for a pan that neither
  // starts nor is suppressed by that pointerdown — before this ticket, an
  // edge-hit pointerdown fell into the same bucket as a card's (bubbled,
  // `e.target !== e.currentTarget`, so `dragStart` was never set), which
  // would have *suppressed* every drag gesture that happened to begin on
  // one of these visually-invisible 12px-wide stroke targets. The explicit
  // `isEdgeHit` check below is the fix: a pointerdown on the hit-line still
  // starts a pan if the pointer then moves past the threshold, and still
  // does nothing (no select, no deselect — `onSceneClick` below) if it
  // doesn't. `setPointerCapture` on the scene element means the hit-line's
  // own `pointermove` (the edge tooltip's cursor-follow — `EdgeLine` below)
  // stops receiving events for the rest of that gesture once a real drag
  // starts, which is fine: any actual pan moves the viewport, and
  // `solid/tooltip.ts`'s `initTooltipDismissal` already dismisses the
  // tooltip on every viewport change.
  let dragStart: { x: number; y: number } | null = null;
  let dragMoved = false;

  function onScenePointerDown(e: PointerEvent): void {
    const isBackground = e.currentTarget === e.target;
    const isEdgeHit = e.target instanceof Element && e.target.classList.contains('edge-hit');
    if (!isBackground && !isEdgeHit) return;
    if (e.button !== 0) return;
    dragStart = { x: e.clientX, y: e.clientY };
    dragMoved = false;
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
  }

  function onScenePointerMove(e: PointerEvent): void {
    if (!dragStart) return;
    const dx = e.clientX - dragStart.x;
    const dy = e.clientY - dragStart.y;
    if (!dragMoved) {
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD_PX) return;
      dragMoved = true;
      setIsPanning(true);
    }
    // Grab-drag semantics: content follows the pointer 1:1 — `panByAmount`
    // uses wheel "scroll" sign convention, so the pointer's client delta is
    // negated here (`nav/viewport.ts`'s `panBy` doc comment).
    panByAmount(-dx, -dy);
    dragStart = { x: e.clientX, y: e.clientY };
  }

  function onScenePointerEnd(e: PointerEvent): void {
    if (dragStart && (e.currentTarget as Element).hasPointerCapture(e.pointerId)) {
      (e.currentTarget as Element).releasePointerCapture(e.pointerId);
    }
    dragStart = null;
    setIsPanning(false);
  }

  function nameOf(ref: Ref): string | null {
    return nodes[ref]?.name ?? null;
  }

  /** Where an edge endpoint attaches. `dir` is the endpoint's side of the
   *  edge — 'OUT' for its producer, 'IN' for its consumer — and is only used
   *  when the node declares no port by that name, which is the normal case
   *  for a peer-announced cell (no descriptor reached this JVM, so no port
   *  list): the edge anchors on the card instead of being dropped. */
  function anchorOf(ref: Ref, port: string, dir: Dir) {
    const ln = layout().nodes.get(ref);
    const rec = nodes[ref];
    if (!ln || !rec) return undefined;
    return portAnchors(ln, rec.ports).get(port) ?? cardAnchor(ln, dir);
  }

  function onCardKeyDown(e: KeyboardEvent, ref: Ref) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setSelection(ref);
    }
  }

  // Click-through-to-deselect only when the click landed on the scene
  // background itself, not a card bubbling up — and only below the drag
  // threshold: a real drag (`dragMoved`) must never also deselect (Solution
  // direction §4). The flag is consumed (reset) here rather than on
  // `pointerup`, since the browser's own `click` fires after `pointerup`.
  function onSceneClick(e: MouseEvent) {
    if (dragMoved) {
      dragMoved = false;
      return;
    }
    if (e.currentTarget === e.target) setSelection(null);
  }

  return (
    <div class="canvas" ref={canvasEl}>
      <Show when={nodeRefs().length > 0} fallback={<p class="canvas__empty">No cells reported yet.</p>}>
        <div
          class="canvas__pan"
          style={{ transform: `translate(${viewport().x}px, ${viewport().y}px) scale(${viewport().scale})` }}
        >
          <div
            class="canvas__scene"
            classList={{ 'is-panning': isPanning() }}
            style={{ width: `${layout().width}px`, height: `${layout().height}px` }}
            onClick={onSceneClick}
            onPointerDown={onScenePointerDown}
            onPointerMove={onScenePointerMove}
            onPointerUp={onScenePointerEnd}
            onPointerCancel={onScenePointerEnd}
          >
            <svg class="canvas__svg" width={layout().width} height={layout().height}>
              {/* Hulls first: SVG paints in document order, so "rendered beneath
                  edges" (10-target-v3.md toggle table) means listing them
                  before the edges/ports below. Network hulls come before
                  process hulls for the same reason — "net outside, proc inside"
                  (M5-NET ticket Implement §2) is both a geometric nesting
                  (`computeNetHulls`' wider padding) and a paint order. */}
              <For each={netHulls()}>
                {(h) => (
                  <g class="net-hull" classList={{ 'net-hull--peer': h.peer }}>
                    <rect class="net-hull__rect" x={h.x} y={h.y} width={h.w} height={h.h} rx={16} ry={16} />
                    <text class="net-hull__label" x={h.x + 12} y={h.y + 18}>
                      {h.net}
                      <Show when={h.peer}>
                        <tspan class="net-hull__tag"> peer</tspan>
                      </Show>
                    </text>
                  </g>
                )}
              </For>
  
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
                  const from = () => (e() ? anchorOf(e()!.from.ref, e()!.from.port, 'OUT') : undefined);
                  const to = () => (e() ? anchorOf(e()!.to.ref, e()!.to.port, 'IN') : undefined);
                  const overlay = () => flowOverlays().get(id);
                  // V2-FE ticket Implement §12(b): the same ghosting as a
                  // suspended node card, applied to any edge with a suspended
                  // endpoint — either side, not just the one the edge points at.
                  const dimmed = () => {
                    const edge = e();
                    if (!edge) return false;
                    return nodes[edge.from.ref]?.lifecycle === 'SUSPENDED' || nodes[edge.to.ref]?.lifecycle === 'SUSPENDED';
                  };
                  return (
                    <Show when={e() && from() && to()}>
                      <EdgeLine
                        edgeId={id}
                        role={e()!.role}
                        fused={e()!.fused === true}
                        x1={from()!.x}
                        y1={from()!.y}
                        x2={to()!.x}
                        y2={to()!.y}
                        flow={overlay()}
                        flowEnabled={showFlow()}
                        route={formatRoute(e()!.from, e()!.to, nameOf)}
                        reducedMotion={prefersReducedMotion()}
                        dimmed={dimmed()}
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
                        // FE-TOOLTIPS ticket Context table site "port dot":
                        // native `<title>` replaced by the shared tooltip
                        // layer. A port dot is never focusable (an SVG
                        // `<circle>` cannot take a `tabindex`), so — like the
                        // edge hit-line below — its tooltip is hover-only.
                        let dotEl: SVGCircleElement | undefined;
                        return (
                          <Show when={a()}>
                            <circle
                              class="port-dot"
                              data-dir={p.dir}
                              cx={a()!.x}
                              cy={a()!.y}
                              r="3"
                              ref={dotEl}
                              onPointerEnter={() =>
                                showTooltip({
                                  key: `port:${ref}:${p.name}`,
                                  content: { rows: [{ label: 'port', value: `${p.name} (${p.dir})` }] },
                                  anchor: () => elementAnchor(dotEl!),
                                })
                              }
                              onPointerLeave={() => hideTooltip()}
                            />
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
                // FE-TOOLTIPS ticket Context table sites "color chip",
                // "type row" and "manifest badge" — plus the placement/
                // lifecycle facts no single one of those titles ever
                // carried — consolidated into ONE tooltip for the whole
                // card (Solution direction §4 "Node card"), shown on
                // hover/focus of the card itself rather than three
                // separately-hoverable sub-elements. `host: null` (a
                // peer-announced cell no local `LocationRegistry` ever
                // located) reads as `REMOTE_HOST_LABEL`, never a bare dash
                // (`util/placement.ts`).
                const cardKey = `card:${ref}`;
                const cardTooltip = () => {
                  const r = rec()!;
                  return {
                    title: r.name ?? ref.slice(0, 8),
                    rows: [
                      { label: 'type', value: r.typeFqn },
                      { label: 'color', value: r.color ?? 'color unknown' },
                      { label: 'manifests', value: r.manifests.length ? r.manifests.join(', ') : 'none' },
                      { label: 'lifecycle', value: r.lifecycle },
                      { label: 'generation', value: String(r.generation) },
                      { label: 'host', value: r.host ?? REMOTE_HOST_LABEL },
                      { label: 'net', value: r.net ?? '—' },
                    ],
                  };
                };
                let cardEl: HTMLDivElement | undefined;
                return (
                  <Show when={rec() && ln()}>
                    <div
                      class="node-card"
                      ref={cardEl}
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
                      // FE-TOOLTIPS ticket Solution direction §3: the node
                      // card is the one focusable anchor among the eight
                      // tooltip sites this ticket wires up, so it is the
                      // only one that sets `aria-describedby` — the SVG
                      // edge hit-line below is explicitly NOT focusable
                      // (an SVG `<line>` cannot take a `tabindex` without
                      // `focusable="true"` plus a `role`, which would also
                      // require its own keyboard activation semantics this
                      // ticket does not add); its tooltip stays hover-only.
                      aria-describedby={activeKey() === cardKey ? TOOLTIP_ID : undefined}
                      onClick={(e) => {
                        e.stopPropagation();
                        setSelection(ref);
                      }}
                      onKeyDown={(e) => onCardKeyDown(e, ref)}
                      onPointerEnter={() =>
                        showTooltip({ key: cardKey, content: cardTooltip(), anchor: () => elementAnchor(cardEl!) })
                      }
                      onPointerLeave={() => hideTooltip()}
                      onFocusIn={() =>
                        showTooltip({
                          key: cardKey,
                          content: cardTooltip(),
                          anchor: () => elementAnchor(cardEl!),
                          immediate: true,
                        })
                      }
                      onBlur={() => dismissTooltip()}
                    >
                      <div class="node-card__top">
                        <span class="node-card__chip" data-color={rec()!.color ?? 'unknown'}>
                          {colorGlyph(rec()!.color)}
                        </span>
                        <span class="node-card__name">{rec()!.name ?? ref.slice(0, 8)}</span>
                        {/* V2-FE ticket Implement §12(a): the delta on top of
                            the pre-existing ghosted-card treatment
                            (`.is-suspended` below, already there before this
                            ticket) — a small explicit tag, since a dashed
                            border + reduced opacity alone is easy to miss at a
                            glance across a busy graph. */}
                        <Show when={rec()!.lifecycle === 'SUSPENDED'}>
                          <span class="node-card__tag" title="suspended">
                            suspended
                          </span>
                        </Show>
                        {/* V1B-FE ticket Solution direction §4: a pin toggle on
                            every node card, next to the color chip.
                            `stopPropagation` so pinning does not also select
                            the card. Disabled while the graph is cold — same
                            gate as selection's own `mode: 'descriptor'`
                            (P6: subscribing raises attention and can un-park a
                            cone; a graph the UI has called parked must not be
                            woken by pinning it either). */}
                        <button
                          type="button"
                          class="node-card__pin"
                          classList={{ 'is-pinned': isPinned(ref) }}
                          disabled={currentGraphCold()}
                          title={
                            currentGraphCold()
                              ? 'Pinning is disabled while this graph is cold'
                              : isPinned(ref)
                                ? 'Unpin (stop observing when not selected)'
                                : 'Pin (keep observing alongside the selection)'
                          }
                          onClick={(e) => {
                            e.stopPropagation();
                            if (isPinned(ref)) unpin(ref);
                            else pin(ref);
                          }}
                        >
                          📌
                        </button>
                      </div>
                      <div class="node-card__type">{shortType(rec()!.typeFqn)}</div>
                      <Show when={rec()!.manifests.length}>
                        <div class="node-card__badges">
                          <For each={rec()!.manifests}>{(m) => <span class="node-card__badge">{manifestBadge(m)}</span>}</For>
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
                `state.summary` events. V1B-FE ticket Solution direction §4:
                generalized from "only the selected cell ever has one" to one
                chip per entry in the observed set (pinned ∪ selection) — a
                cell outside that set still shows no chip. A `snapshotOnly`
                ref (409'd observe) never receives a `state.summary` (no
                server-side sink exists for it), so it gets a visually
                distinct "pinned · snapshot only" variant instead of the
                cardinality/frontier/staleness line, which is summary-only
                data it will never have. */}
            <For each={nodeRefs()}>
              {(ref) => {
                const ln = () => layout().nodes.get(ref);
                const summary = () => stateSummaries[ref];
                const isSnapshotOnly = () => observed().has(ref) && snapshotOnly().has(ref);
                // FE-TOOLTIPS ticket Context table site "state chip": the
                // cryptic `cardinality · frontier · staleness` legend
                // becomes labelled rows over the real values (Solution
                // direction §4 "Overlay chips").
                let chipEl: HTMLDivElement | undefined;
                const chipTooltip = () =>
                  isSnapshotOnly()
                    ? { rows: [{ label: 'mode', value: 'pinned · snapshot only' }, { label: 'note', value: 'no live fold to observe' }] }
                    : {
                        rows: [
                          { label: 'cardinality', value: summary()!.cardinality ?? '—' },
                          {
                            label: 'frontier',
                            value: summary()!.frontier
                              ? `${summary()!.frontier!.source.slice(0, 6)}·${summary()!.frontier!.counter}`
                              : '—',
                          },
                          { label: 'staleness', value: `${summary()!.staleMs}ms` },
                        ],
                      };
                return (
                  <Show when={showState() && ln() && (summary() || isSnapshotOnly())}>
                    <div
                      class="node-state-chip"
                      classList={{ 'node-state-chip--snapshot': isSnapshotOnly() }}
                      data-mode={isSnapshotOnly() ? 'snapshot' : 'live'}
                      style={{ left: `${ln()!.x}px`, top: `${ln()!.y + ln()!.h + 4}px`, width: `${ln()!.w}px` }}
                      ref={chipEl}
                      onPointerEnter={() =>
                        showTooltip({ key: `state:${ref}`, content: chipTooltip(), anchor: () => elementAnchor(chipEl!) })
                      }
                      onPointerLeave={() => hideTooltip()}
                    >
                      <Show when={!isSnapshotOnly()} fallback="pinned · snapshot only">
                        {summary()!.cardinality ?? '—'} ·{' '}
                        {summary()!.frontier ? `${summary()!.frontier!.source.slice(0, 6)}·${summary()!.frontier!.counter}` : '—'} ·{' '}
                        {summary()!.staleMs}ms
                      </Show>
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
                card's top-right corner.
                computenet-0994: this badge, and its `cellBadges()` count, are
                FAULTS only — a BoundaryPolicy refusal never contributes to
                it (see `cellBadges` above); the sibling denial badge below
                covers refusals in the not-a-fault register. */}
            <For each={nodeRefs()}>
              {(ref) => {
                const ln = () => layout().nodes.get(ref);
                const count = () => cellBadges().get(ref);
                // FE-TOOLTIPS ticket Context table site "error badge": the
                // combined count becomes its dead-letter/restart split
                // (Solution direction §4 "Overlay chips"), read live from
                // `errorStore` at hover time rather than carried in the
                // (combined-only) `cellBadges()` memo.
                let badgeEl: HTMLDivElement | undefined;
                const badgeTooltip = () => ({
                  title: `${count()} error${count() === 1 ? '' : 's'}`,
                  rows: [
                    // computenet-0994: fault dead letters only — a denial row
                    // is reported by the sibling badge below, not folded in here.
                    { label: 'dead letters', value: String(errorStore.deadLettersFor(ref).filter((dl) => dl.denial == null).length) },
                    { label: 'restarts', value: String(errorStore.restartsFor(ref).length) },
                  ],
                });
                return (
                  <Show when={ln() && count()}>
                    <div
                      class="node-error-badge"
                      data-kind="fault"
                      style={{ left: `${ln()!.x + ln()!.w}px`, top: `${ln()!.y}px` }}
                      ref={badgeEl}
                      onPointerEnter={() =>
                        showTooltip({ key: `error:${ref}`, content: badgeTooltip(), anchor: () => elementAnchor(badgeEl!) })
                      }
                      onPointerLeave={() => hideTooltip()}
                    >
                      {count()}
                    </div>
                  </Show>
                );
              }}
            </For>

            {/* computenet-0994: a distinct badge for BoundaryPolicy refusals
                — poking past the top-LEFT corner (the fault badge above owns
                top-right), reusing `.node-error-badge`'s shape but recolored
                inline to `--wave-health`, the same not-a-fault register
                `DetailPanel.tsx`'s denial card border already uses. A cell
                with denials only (no faults) therefore shows no red badge at
                all — exactly the "pure-refusal graph must not look like
                cells are failing" requirement. */}
            <For each={nodeRefs()}>
              {(ref) => {
                const ln = () => layout().nodes.get(ref);
                const count = () => cellDenialBadges().get(ref);
                let badgeEl: HTMLDivElement | undefined;
                const badgeTooltip = () => ({
                  title: `${count()} boundary denial${count() === 1 ? '' : 's'} — refused, not a cell fault`,
                  rows: [
                    { label: 'denied', value: String(errorStore.deadLettersFor(ref).filter((dl) => dl.denial != null).length) },
                  ],
                });
                return (
                  <Show when={ln() && count()}>
                    <div
                      class="node-error-badge"
                      data-kind="denial"
                      style={{
                        left: `${ln()!.x}px`,
                        top: `${ln()!.y}px`,
                        background: 'var(--wave-health)',
                        color: '#1a1300',
                      }}
                      ref={badgeEl}
                      onPointerEnter={() =>
                        showTooltip({ key: `denial:${ref}`, content: badgeTooltip(), anchor: () => elementAnchor(badgeEl!) })
                      }
                      onPointerLeave={() => hideTooltip()}
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
                const from = () => (e() ? anchorOf(e()!.from.ref, e()!.from.port, 'OUT') : undefined);
                const to = () => (e() ? anchorOf(e()!.to.ref, e()!.to.port, 'IN') : undefined);
                const count = () => edgeParked().get(id);
                // FE-TOOLTIPS ticket Context table site "parked pill": names
                // the port and count (Solution direction §4 "Overlay
                // chips") rather than the flattened "N parked" string.
                let pillEl: HTMLDivElement | undefined;
                const pillTooltip = () => ({
                  rows: [
                    { label: 'port', value: e()!.to.port },
                    { label: 'count', value: String(count()) },
                  ],
                });
                return (
                  <Show when={e() && from() && to() && count()}>
                    <div
                      class="edge-parked-pill"
                      style={{
                        left: `${(from()!.x + to()!.x) / 2}px`,
                        top: `${(from()!.y + to()!.y) / 2}px`,
                      }}
                      ref={pillEl}
                      onPointerEnter={() =>
                        showTooltip({ key: `parked:${id}`, content: pillTooltip(), anchor: () => elementAnchor(pillEl!) })
                      }
                      onPointerLeave={() => hideTooltip()}
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
                const from = () => (e() ? anchorOf(e()!.from.ref, e()!.from.port, 'OUT') : undefined);
                const to = () => (e() ? anchorOf(e()!.to.ref, e()!.to.port, 'IN') : undefined);
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
        </div>
        {/* FE-CANVAS ticket Solution direction §5: rendered inside `.canvas`
            but a sibling of `.canvas__pan`, not a descendant of it, so it
            never scales with the zoom transform. */}
        <ZoomControls />
      </Show>
    </div>
  );
}

function EdgeLine(props: {
  edgeId: string;
  role: EdgeRole;
  fused: boolean;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  /** Flow toggle overlay for this edge — `undefined` when the toggle is off
   *  or this edge has nothing to show (10-target-v3.md Flow toggle). */
  flow?: EdgeFlowOverlay;
  /** Whether the Flow toggle is on — gates the tooltip's flow-derived rows
   *  (FE-TOOLTIPS ticket Solution direction §4: "when the toggle is off ...
   *  the flow rows are simply absent"), independently of `.edge-hit` itself,
   *  which now renders unconditionally (see below). */
  flowEnabled: boolean;
  /** `producer.port → consumer.port` (`util/flow.ts`'s `formatRoute`) — a
   *  structural fact, true regardless of the Flow toggle (ticket Problem
   *  #2), so it is always part of the tooltip. */
  route: string;
  reducedMotion: boolean;
  /** V2-FE ticket Implement §12(b): true when either endpoint is a
   *  suspended cell — reuses `.node-card.is-suspended`'s own
   *  `--ghost-opacity` token so an edge and the suspended card(s) it touches
   *  read as one consistent ghosting signal. */
  dimmed?: boolean;
}) {
  const dx = () => props.x2 - props.x1;
  const dy = () => props.y2 - props.y1;
  const len = () => Math.hypot(dx(), dy()) || 1;
  const nx = () => (-dy() / len()) * FUSED_OFFSET;
  const ny = () => (dx() / len()) * FUSED_OFFSET;
  const dash = () => (props.role === 'OBSERVE' ? '5 3' : undefined);
  const cls = () => `edge edge--${props.role.toLowerCase()}${props.dimmed ? ' edge--dimmed' : ''}`;

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

      {/* FE-TOOLTIPS ticket Solution direction §4: a wide, invisible hit-line
          carrying the hover tooltip, now rendered UNCONDITIONALLY — route
          and role are structural facts "true whether or not anyone is
          watching rates" (ticket Problem #2), so an edge is hoverable with
          the Flow toggle off too; the flow-derived rows are simply omitted
          then (`props.flowEnabled` below), not the whole hit-line. The
          visible line(s) above stay under `.canvas__svg`'s blanket
          `pointer-events: none`, preserving normal click-through-to-
          deselect; `.edge-hit` (Canvas.css) is the one element that opts
          back into pointer events.

          Anchored to the CURSOR, not this line's own bounding box (Solution
          direction §3): a long diagonal edge has no useful bounding-box
          anchor. `reportCursorPosition` coalesces to one update per
          animation frame; `onPointerEnter` seeds the position immediately
          so the first frame is not anchored at `(0, 0)`.

          Accessibility limitation, stated plainly (ticket Solution
          direction §3): an SVG `<line>` cannot become a keyboard focus
          stop without also inventing bespoke `tabindex`/`role`/keydown
          semantics this ticket does not add, so this tooltip is
          **hover-only** — there is no way to reach it, or the route/role
          facts it carries, from the keyboard. */}
      <line
        class="edge-hit"
        x1={props.x1}
        y1={props.y1}
        x2={props.x2}
        y2={props.y2}
        onPointerEnter={(ev) => {
          reportCursorPosition(ev.clientX, ev.clientY);
          const rows = props.flowEnabled
            ? [...edgeRouteRoleRows(props.route, props.role), ...edgeFlowRows(props.flow)]
            : edgeRouteRoleRows(props.route, props.role);
          showTooltip({ key: `edge:${props.edgeId}`, content: { rows }, anchor: cursorAnchorRect, prefer: 'right' });
        }}
        onPointerMove={(ev) => reportCursorPosition(ev.clientX, ev.clientY)}
        onPointerLeave={() => hideTooltip()}
      />

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
