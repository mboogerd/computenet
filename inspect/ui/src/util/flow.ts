import type { Dir, Frontier, Port, Ref } from '../api/types';
import type { FlowEdgeState } from '../sync/flowStore';

/** Pulse count/speed step by band, not by raw rate (M3-FE ticket Implement
 *  §2: "pulse count/speed stepped by rate bands (define 3–4 bands; do NOT
 *  animate per-message)"). Three bands: a trickle still shows one slow
 *  pulse (so "some traffic" always reads as visibly different from "no
 *  traffic" / fused), rising to three fast pulses for the hottest edges. */
export type FlowBand = 1 | 2 | 3;

const BAND_2_MIN_RATE = 5;
const BAND_3_MIN_RATE = 25;

export function rateBand(rate: number): FlowBand {
  if (rate >= BAND_3_MIN_RATE) return 3;
  if (rate >= BAND_2_MIN_RATE) return 2;
  return 1;
}

const PULSE_COUNT: Record<FlowBand, number> = { 1: 1, 2: 2, 3: 3 };
/** Full-length travel time along the edge, in ms — lower band = slower. */
const PULSE_DURATION_MS: Record<FlowBand, number> = { 1: 2400, 2: 1600, 3: 900 };

export function pulseCountFor(band: FlowBand): number {
  return PULSE_COUNT[band];
}

export function pulseDurationMsFor(band: FlowBand): number {
  return PULSE_DURATION_MS[band];
}

/** The minimal edge shape flow derivation needs — structurally compatible
 *  with the client-side `EdgeRec` (sync/records.ts), same technique as
 *  `util/errors.ts`'s `EdgeTarget`. */
export interface FlowEdgeTarget {
  id: string;
  fused: boolean | null;
}

export type EdgeFlowOverlay =
  | { kind: 'fused' }
  | { kind: 'active'; rate: number; band: FlowBand; lastWave: Frontier | null; hop: number | null };

/** One overlay entry per edge that the Flow toggle has something to draw for
 *  — the fused-gating + "only edges with a current reading" filtering in one
 *  place, so `Canvas.tsx` never has to re-derive it (mirrors
 *  `util/errors.ts`'s `cellErrorBadges`/`deriveEdgeParkedCounts` split).
 *
 *  `fused` always wins over any (hypothetical, defensive) flow-store entry:
 *  M3-BE "never emit[s] rates for [fused edges]" (ticket Context), so a
 *  fused edge is reported as fused regardless of what the flow store might
 *  otherwise hold for its id. An edge that is neither fused nor currently
 *  reporting a positive rate (never seen, or decayed to zero — see
 *  `sync/flowStore.ts`) contributes nothing: there is nothing to draw for
 *  "no observed traffic" beyond the plain unadorned edge line.
 *
 *  `enabled` is the Flow toggle state, gated here (not at the call site) —
 *  "when off, none of it renders" is then a property of this pure function,
 *  directly testable without mounting the canvas. */
export function deriveEdgeFlowOverlays(
  edges: Iterable<FlowEdgeTarget>,
  flowOf: (id: string) => FlowEdgeState | undefined,
  enabled: boolean,
): Map<string, EdgeFlowOverlay> {
  const out = new Map<string, EdgeFlowOverlay>();
  if (!enabled) return out;
  for (const e of edges) {
    if (e.fused === true) {
      out.set(e.id, { kind: 'fused' });
      continue;
    }
    const f = flowOf(e.id);
    if (!f || f.rate <= 0) continue;
    out.set(e.id, { kind: 'active', rate: f.rate, band: rateBand(f.rate), lastWave: f.lastWave, hop: f.hop });
  }
  return out;
}

/** How many pulse dots to render for one edge's overlay. Under
 *  `prefers-reduced-motion: reduce` this is always 0 — the ticket's "static
 *  intensity styling instead of pulses" — regardless of band; the caller
 *  falls back to a static per-band CSS class on the edge line itself
 *  (`Canvas.tsx`'s `data-band` attribute) rather than any moving element. */
export function pulsesToRender(overlay: EdgeFlowOverlay, reducedMotion: boolean): number {
  if (overlay.kind !== 'active') return 0;
  return reducedMotion ? 0 : pulseCountFor(overlay.band);
}

/** The canvas rate/"fused" label text for one edge's overlay (10-target-v3.md
 *  Flow toggle: "rate labels at edge midpoints" / "fused edges thick/static
 *  with a 'fused' label"). */
export function flowLabelText(overlay: EdgeFlowOverlay): string {
  return overlay.kind === 'fused' ? 'fused' : `${overlay.rate.toFixed(1)}/s`;
}

/** One edge endpoint, named for the tooltip's "route" (10-target-v3.md
 *  detail subsection / v2 mockup: "hover tooltip with last wave + hop"; the
 *  M3-FE ticket's own Implement §3 adds "route" to that list). */
export interface RouteEndpoint {
  ref: Ref;
  port: string;
}

export function formatRoute(from: RouteEndpoint, to: RouteEndpoint, nameOf: (ref: Ref) => string | null): string {
  const label = (ep: RouteEndpoint) => `${nameOf(ep.ref) ?? ep.ref.slice(0, 8)}.${ep.port}`;
  return `${label(from)} → ${label(to)}`;
}

/** M3-FE ticket Implement §3: "Edge hover tooltip (only when toggle on):
 *  route, last wave (source · counter), hop, rate." The fused case's exact
 *  wording ("fused — no observable messages") is the ticket's own Implement
 *  §2 text, quoted verbatim rather than reworded. `overlay` is `undefined`
 *  for an edge the Flow toggle has nothing active to report for (never
 *  observed, or decayed to zero) — the tooltip still names the route so
 *  hovering any edge while the toggle is on is informative, not just the
 *  ones currently pulsing. */
export function flowTooltip(route: string, overlay: EdgeFlowOverlay | undefined): string {
  if (overlay?.kind === 'fused') return `${route} — fused — no observable messages`;
  if (overlay?.kind === 'active') {
    const wave = overlay.lastWave ? `${overlay.lastWave.source.slice(0, 8)}·${overlay.lastWave.counter}` : '—';
    const hop = overlay.hop ?? '—';
    return `${route} — wave ${wave} · hop ${hop} · ${overlay.rate.toFixed(1)}/s`;
  }
  return `${route} — no observed traffic`;
}

// --- Flow subsection: per-port rate table (10-target-v3.md detail
// subsection 3; M3-FE ticket Implement §4) -------------------------------

export interface PortFlowRow {
  port: string;
  dir: Dir;
  /** This port's own message rate; 0 when `fused` is true or nothing is
   *  currently observed on it.
   *
   *  How the port's edges combine depends on the direction, because the
   *  server's per-edge attribution is directional (M3-BE `FlowCollector`
   *  KDoc, "Attribution"): a tap fires once per *emission*, and a
   *  `FanOutlet` broadcasts, so **every edge leaving one OUT port reports
   *  that same outlet's emission count** — duplicated across the edges,
   *  never divided. Summing an OUT port's edges would therefore multiply
   *  the port's true rate by its fan-out (measured live at M3-EVAL:
   *  `jobSkills.outlet` emitting 6/s across 5 edges summed to 30/s), so an
   *  OUT port reports the emission rate itself — the max over its edges,
   *  which are all readings of the one counter.
   *
   *  An IN port is the opposite: its edges come from *distinct* producing
   *  outlets, so they are independent streams and genuinely add. */
  rate: number;
  /** True only when every edge touching this port is fused — a port with a
   *  mix of fused and active edges still reports the active ones' rate
   *  (there is real, if partial, observable traffic on it). */
  fused: boolean;
  /** The most-advanced (highest counter) wave among this port's edges, or
   *  null when none of them currently report one. */
  lastWave: Frontier | null;
}

/** The minimal edge shape {@link portFlowRows} needs. */
export interface PortFlowEdge {
  id: string;
  from: { ref: Ref; port: string };
  to: { ref: Ref; port: string };
  fused: boolean | null;
}

/** Per-port table for the selected cell: direction, rate, last wave; fused
 *  ports labeled (10-target-v3.md detail subsection 3: "per-port rates, last
 *  wave ... 'fused — no observable messages' where applicable"). See
 *  {@link PortFlowRow.rate} for why OUT and IN combine their edges
 *  differently.
 *  `edges` is the *whole* topology edge set — this filters to the ones
 *  touching `ref` itself, so callers (DetailPanel) do not have to
 *  pre-filter. */
export function portFlowRows(
  ports: readonly Port[],
  ref: Ref,
  edges: Iterable<PortFlowEdge>,
  flowOf: (id: string) => FlowEdgeState | undefined,
): PortFlowRow[] {
  const all = [...edges];
  return ports.map((p) => {
    const touching = all.filter(
      (e) => (p.dir === 'OUT' && e.from.ref === ref && e.from.port === p.name) || (p.dir === 'IN' && e.to.ref === ref && e.to.port === p.name),
    );
    if (touching.length > 0 && touching.every((e) => e.fused === true)) {
      return { port: p.name, dir: p.dir, rate: 0, fused: true, lastWave: null };
    }
    let rate = 0;
    let lastWave: Frontier | null = null;
    for (const e of touching) {
      if (e.fused === true) continue;
      const f = flowOf(e.id);
      if (!f) continue;
      // OUT: one outlet, one counter, reported on each of its edges — take it
      // once. IN: distinct upstream outlets — independent streams, so add.
      rate = p.dir === 'OUT' ? Math.max(rate, f.rate) : rate + f.rate;
      if (f.lastWave && (!lastWave || f.lastWave.counter > lastWave.counter)) lastWave = f.lastWave;
    }
    return { port: p.name, dir: p.dir, rate, fused: false, lastWave };
  });
}
