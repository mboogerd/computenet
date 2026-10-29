import { createMemo, createSignal, createEffect, For, Show, onMount } from 'solid-js';
import { graph, nodes, structuralVersion, focal, selection, setSelection } from '../solid/graph';
import { pulsing } from '../solid/hot';
import { layoutMap, type Vertex, type MapLayout } from '../layout/map';
import { bandFor, bandVar } from '../styles/bands';
import { labelOf } from '../util/label';
import { FitIcon, ZoomInIcon, ZoomOutIcon } from './Icons';
import './GraphCanvas.css';

const cx = (v: Vertex) => v.x + v.w / 2;
const cy = (v: Vertex) => v.y + v.h / 2;

/** Boundary point of vertex `v` in the direction of (tx,ty). Analytic — never
 *  reads live DOM geometry (fixed dims from the layout). */
function anchor(v: Vertex, tx: number, ty: number): { x: number; y: number } {
  const ox = cx(v);
  const oy = cy(v);
  const dx = tx - ox;
  const dy = ty - oy;
  if (v.kind === 'EDGE') {
    const len = Math.hypot(dx, dy) || 1;
    const r = v.w / 2;
    return { x: ox + (dx / len) * r, y: oy + (dy / len) * r };
  }
  const hw = v.w / 2;
  const hh = v.h / 2;
  const s = Math.min(dx === 0 ? Infinity : hw / Math.abs(dx), dy === 0 ? Infinity : hh / Math.abs(dy));
  return { x: ox + dx * s, y: oy + dy * s };
}

export default function GraphCanvas() {
  let mapEl: HTMLDivElement | undefined;

  const layout = createMemo<MapLayout | null>(() => {
    structuralVersion(); // structure-only dependency — credence never re-lays-out
    const f = focal();
    if (!f || !graph.get(f)) return null;
    return layoutMap(graph, f);
  });

  const [view, setView] = createSignal({ x: 0, y: 0, k: 1 });

  // pan (background only) + click-through select for cards/junctions
  let panning = false;
  let lastX = 0;
  let lastY = 0;
  const onPointerDown = (e: PointerEvent) => {
    if (e.button !== 0) return;
    if ((e.target as Element).closest('.map-card, .map-junction')) return; // let selection through
    panning = true;
    lastX = e.clientX;
    lastY = e.clientY;
  };
  const onPointerMove = (e: PointerEvent) => {
    if (!panning) return;
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
    lastX = e.clientX;
    lastY = e.clientY;
    setView((v) => ({ ...v, x: v.x + dx, y: v.y + dy }));
  };
  const endPan = () => {
    panning = false;
  };
  const onWheel = (e: WheelEvent) => {
    e.preventDefault();
    const rect = mapEl!.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    setView((v) => {
      const k = Math.min(3, Math.max(0.2, v.k * Math.exp(-e.deltaY * 0.0015)));
      const scale = k / v.k;
      return { k, x: mx - (mx - v.x) * scale, y: my - (my - v.y) * scale };
    });
  };

  /** Zoom about the viewport center (toolbar buttons). */
  const zoomBy = (factor: number) => {
    if (!mapEl) return;
    const rect = mapEl.getBoundingClientRect();
    const mx = rect.width / 2;
    const my = rect.height / 2;
    setView((v) => {
      const k = Math.min(3, Math.max(0.2, v.k * factor));
      const scale = k / v.k;
      return { k, x: mx - (mx - v.x) * scale, y: my - (my - v.y) * scale };
    });
  };

  /** Edges touching the selection — highlighted, everything else recedes.
   *  A selected EDGE highlights itself; a selected CLAIM highlights every
   *  edge it sources or receives (both halves through the junction). */
  const lit = createMemo<Set<string> | null>(() => {
    const sel = selection();
    const l = layout();
    if (!sel || !l || !l.vertices.has(sel)) return null;
    const s = new Set<string>([sel]);
    for (const seg of l.segments) if (seg.from === sel || seg.to === sel) s.add(seg.edgeRef);
    return s;
  });

  const isLit = (ref: string): boolean => {
    const s = lit();
    if (!s) return false;
    if (s.has(ref)) return true;
    const l = layout();
    return !!l && l.segments.some((seg) => s.has(seg.edgeRef) && (seg.from === ref || seg.to === ref));
  };

  const fit = () => {
    const l = layout();
    if (!l || !mapEl || !l.width) return;
    const rect = mapEl.getBoundingClientRect();
    // On first paint the flex chain may not have sized the map yet (rect ~0),
    // which would fit to a near-zero scale — leave the view untouched; the
    // rAF-scheduled callers below re-run fit once layout has settled.
    if (rect.width < 1 || rect.height < 1) return;
    const k = Math.min(1, Math.min(rect.width / l.width, rect.height / l.height));
    setView({ k, x: (rect.width - l.width * k) / 2, y: 24 });
  };

  // rAF, not queueMicrotask: a microtask runs before the browser's layout pass,
  // so getBoundingClientRect() would read a stale/zero size on first mount.
  onMount(() => requestAnimationFrame(fit));
  // re-fit when the focal (hence the whole layout) changes
  let lastFocal: string | null = null;
  createEffect(() => {
    const f = focal();
    structuralVersion();
    if (f !== lastFocal) {
      lastFocal = f;
      requestAnimationFrame(fit);
    }
  });

  return (
    <div
      class="map"
      classList={{ 'map--has-focus': lit() !== null }}
      ref={mapEl}
      style={{
        // the dot grid pans and zooms with the scene
        'background-size': `${22 * view().k}px ${22 * view().k}px`,
        'background-position': `${view().x}px ${view().y}px`,
      }}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endPan}
      onPointerLeave={endPan}
      onWheel={onWheel}
      onDblClick={fit}
    >
      <Show
        when={layout()}
        fallback={<p class="map__empty">Pick or add a focal claim to see the map.</p>}
      >
        {(l) => (
          <>
            <div
              class="map__scene"
              style={{
                transform: `translate(${view().x}px, ${view().y}px) scale(${view().k})`,
                'transform-origin': '0 0',
              }}
            >
              <svg class="map__svg" width={l().width} height={l().height}>
                <defs>
                  <marker
                    id="m-support"
                    markerWidth="12"
                    markerHeight="12"
                    refX="9"
                    refY="5"
                    orient="auto"
                    markerUnits="userSpaceOnUse"
                  >
                    <path d="M1,1 L9,5 L1,9 Z" fill="var(--edge-support)" />
                  </marker>
                  <marker
                    id="m-attack"
                    markerWidth="12"
                    markerHeight="12"
                    refX="6"
                    refY="6"
                    orient="auto"
                    markerUnits="userSpaceOnUse"
                  >
                    <path d="M6,1 L6,11" stroke="var(--edge-attack)" stroke-width="2.5" stroke-linecap="round" />
                  </marker>
                </defs>

                <For
                  each={l().segments.filter((s) => {
                    // Drop segments touching an unreachable node — those vertices
                    // never leave the origin, so their lines/markers would pile
                    // up at the scene's top-left corner.
                    const f = l().vertices.get(s.from);
                    const t = l().vertices.get(s.to);
                    return f && t && f.depth !== -1 && t.depth !== -1;
                  })}
                >
                  {(seg) => {
                    const f = l().vertices.get(seg.from)!;
                    const t = l().vertices.get(seg.to)!;
                    const a = anchor(f, cx(t), cy(t));
                    const b = anchor(t, cx(f), cy(f));
                    const sel = () => selection() === seg.edgeRef;
                    const on = () => lit()?.has(seg.edgeRef) ?? false;
                    return (
                      <line
                        class="map-seg"
                        classList={{ 'is-lit': on() }}
                        x1={a.x}
                        y1={a.y}
                        x2={b.x}
                        y2={b.y}
                        stroke={
                          sel()
                            ? seg.polarity === 'ATTACK'
                              ? 'var(--reject-strong)'
                              : 'var(--accept-strong)'
                            : seg.polarity === 'ATTACK'
                              ? 'var(--edge-attack)'
                              : 'var(--edge-support)'
                        }
                        stroke-width={sel() ? 2.5 : on() ? 2 : 1.5}
                        stroke-linecap="round"
                        stroke-dasharray={seg.polarity === 'ATTACK' ? '5 3' : undefined}
                        marker-end={
                          seg.part === 'out'
                            ? seg.polarity === 'ATTACK'
                              ? 'url(#m-attack)'
                              : 'url(#m-support)'
                            : undefined
                        }
                      />
                    );
                  }}
                </For>

                <For
                  each={[...l().vertices.values()].filter((v) => v.kind === 'EDGE' && v.depth !== -1)}
                >
                  {(v) => {
                    const rec = () => nodes[v.ref];
                    const band = () => (rec() ? bandFor(rec()!.credence) : 'contested');
                    const pulse = pulsing(v.ref);
                    const r = v.w / 2;
                    return (
                      <g
                        class="map-junction"
                        classList={{ 'is-lit': lit()?.has(v.ref) ?? false }}
                        transform={`translate(${cx(v)}, ${cy(v)})`}
                        onClick={() => setSelection(v.ref)}
                      >
                        <Show when={pulse()}>
                          <circle
                            class="junction-pulse"
                            r={r + 5}
                            fill="none"
                            stroke="var(--status-amber)"
                            stroke-width="2"
                          />
                        </Show>
                        <rect
                          x={-r}
                          y={-r}
                          width={2 * r}
                          height={2 * r}
                          rx="2"
                          transform="rotate(45)"
                          fill={bandVar(band())}
                          stroke={selection() === v.ref ? 'var(--text)' : 'var(--hairline)'}
                          stroke-width={selection() === v.ref ? 2 : 1}
                        />
                        <Show when={rec()?.head}>
                          <text class="map-junction__head" x="0" y="-11" text-anchor="middle">
                            ⟳
                          </text>
                        </Show>
                      </g>
                    );
                  }}
                </For>
              </svg>

              <For
                each={[...l().vertices.values()].filter((v) => v.kind === 'CLAIM' && v.depth !== -1)}
              >
                {(v) => {
                  const rec = () => nodes[v.ref];
                  const band = () => (rec() ? bandFor(rec()!.credence) : 'contested');
                  const pulse = pulsing(v.ref);
                  return (
                    <div
                      class="map-card"
                      classList={{
                        'is-selected': selection() === v.ref,
                        'is-pulsing': pulse(),
                        'is-focal': v.depth === 0,
                        'is-lit': isLit(v.ref),
                      }}
                      style={{
                        left: `${v.x}px`,
                        top: `${v.y}px`,
                        width: `${v.w}px`,
                        height: `${v.h}px`,
                        '--card-band': bandVar(band()),
                      }}
                      onClick={() => setSelection(v.ref)}
                    >
                      <span class="map-card__text">{rec() ? labelOf(rec()!) : v.ref.slice(0, 8)}</span>
                      <span class="map-card__cred">
                        <span
                          class="map-card__swatch"
                          classList={{ 'map-card__swatch--contested': band() === 'contested' }}
                        />
                        {rec()?.credence.toFixed(2)}
                      </span>
                    </div>
                  );
                }}
              </For>
            </div>

            <div class="map__tools" onPointerDown={(e) => e.stopPropagation()}>
              <button class="map__tool pressable" onClick={() => zoomBy(1.25)} title="Zoom in" aria-label="Zoom in">
                <ZoomInIcon size={16} />
              </button>
              <span class="map__zoom">{Math.round(view().k * 100)}%</span>
              <button class="map__tool pressable" onClick={() => zoomBy(0.8)} title="Zoom out" aria-label="Zoom out">
                <ZoomOutIcon size={16} />
              </button>
              <span class="map__tools-sep" />
              <button class="map__tool pressable" onClick={fit} title="Fit to view (or double-click)" aria-label="Fit to view">
                <FitIcon size={16} />
              </button>
            </div>
            <Show when={l().unreachable.filter((r) => graph.get(r)?.kind === 'CLAIM').length}>
              <div class="map__hint">
                {l().unreachable.filter((r) => graph.get(r)?.kind === 'CLAIM').length} claim(s) not
                connected to this focal — pick another above.
              </div>
            </Show>
          </>
        )}
      </Show>
    </div>
  );
}
