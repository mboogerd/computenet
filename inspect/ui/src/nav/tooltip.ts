// FE-TOOLTIPS ticket Solution direction §1: the pure tooltip placement
// maths. Framework-free (no DOM, no Solid import) — the same split
// `nav/route.ts` has against `solid/route.ts`, and `nav/viewport.ts` against
// `solid/viewport.ts` — so the part with actual edge cases (flip, shift,
// clamp) is directly unit-testable in this repo's node-environment vitest
// setup (there is no jsdom here; FE-TESTS adds it after this ticket).
//
// Every `Rect`/`Point` here is CLIENT space (post any CSS transform) —
// `solid/tooltip.ts` and `Canvas.tsx` are the only callers, and they always
// hand this the output of `getBoundingClientRect()` (for an element anchor)
// or a raw `clientX`/`clientY` pointer position wrapped as a zero-size rect
// (for the cursor-follow edge-tooltip case) — never a `layout/layered.ts`
// scene coordinate. See FE-CANVAS's `nav/viewport.ts` for why that
// distinction matters post-FE-CANVAS: anything drawn on the canvas sits
// under a `translate(x, y) scale(s)` transform, so its layout position is no
// longer its client position.

export interface Rect {
  readonly x: number;
  readonly y: number;
  readonly w: number;
  readonly h: number;
}

export type Placement = 'top' | 'bottom' | 'left' | 'right';

/** One labelled row in a tooltip's structured content — the direct
 *  replacement for a native `title`'s single flattened string (ticket
 *  Problem #1). Shared by `util/flow.ts` (the edge derivation) and
 *  `solid/tooltip.ts` (the controller's `TooltipContent`) rather than
 *  declared twice — both import the pure shape from here rather than from
 *  each other, so `util/flow.ts` stays Solid-free. */
export interface TooltipRow {
  readonly label: string;
  readonly value: string;
}

/** A tooltip's full content: an optional title line (a node card's name, an
 *  error badge's summary count) plus its labelled rows. `Tooltip.tsx` is the
 *  only renderer — this is data, never JSX, so it can be produced by a pure
 *  function (`util/flow.ts`'s `flowTooltipRows`) or a plain object literal
 *  (`Canvas.tsx`'s per-element builders) alike. */
export interface TooltipContent {
  readonly title?: string;
  readonly rows: readonly TooltipRow[];
}

const OPPOSITE: Record<Placement, Placement> = { top: 'bottom', bottom: 'top', left: 'right', right: 'left' };

/** Gap between the anchor's edge and the tooltip, in client px. */
export const TOOLTIP_GAP = 8;

function positionFor(
  side: Placement,
  anchor: Rect,
  tip: { w: number; h: number },
  gap: number,
): { x: number; y: number } {
  switch (side) {
    case 'top':
      return { x: anchor.x + anchor.w / 2 - tip.w / 2, y: anchor.y - gap - tip.h };
    case 'bottom':
      return { x: anchor.x + anchor.w / 2 - tip.w / 2, y: anchor.y + anchor.h + gap };
    case 'left':
      return { x: anchor.x - gap - tip.w, y: anchor.y + anchor.h / 2 - tip.h / 2 };
    case 'right':
      return { x: anchor.x + anchor.w + gap, y: anchor.y + anchor.h / 2 - tip.h / 2 };
  }
}

/** Does `pos` keep the tooltip inside `view` along `side`'s own (main) axis?
 *  Only the main axis is checked here — the cross axis is never a fit/flip
 *  question, only a shift-and-clamp one, handled uniformly below regardless
 *  of `side`. */
function fitsMainAxis(
  side: Placement,
  pos: { x: number; y: number },
  tip: { w: number; h: number },
  view: { w: number; h: number },
): boolean {
  if (side === 'top') return pos.y >= 0;
  if (side === 'bottom') return pos.y + tip.h <= view.h;
  if (side === 'left') return pos.x >= 0;
  return pos.x + tip.w <= view.w; // 'right'
}

function clamp(v: number, min: number, max: number): number {
  // `max < min` is the oversized-tooltip case (tip.w/tip.h > view.w/view.h)
  // — there is no position that satisfies both bounds, so pin to the
  // window's own origin edge rather than producing a NaN/inverted range.
  return max < min ? min : Math.min(Math.max(v, min), max);
}

/**
 * Where to draw a tooltip sized `tip` for `anchor` (client space; a
 * zero-size rect is a point anchor — the cursor-follow edge case), inside a
 * `view` of that size (typically the window).
 *
 * Rules (ticket Solution direction §1):
 *  - try `opts.prefer` (default `'top'`) first;
 *  - flip to the opposite side when the preferred side does not fit along
 *    its own main axis;
 *  - clamp on BOTH axes, always, so the result never overflows `view` — this
 *    also performs the "shift along the cross axis to stay inside the
 *    viewport" rule for free, since the cross axis is exactly the one this
 *    clamp constrains when the main axis already fit without flipping;
 *  - when NEITHER the preferred nor the flipped side fits (a tooltip larger
 *    than the view, or a view too small on that axis), keep the preferred
 *    side's position and let the clamp pin it in bounds — the returned
 *    `placement` still names the side that was actually used, so a caller
 *    (or a test) can tell a plain fit apart from a flip apart from a
 *    clamped-without-fitting-either-way result.
 */
export function placeTooltip(
  anchor: Rect,
  tip: { w: number; h: number },
  view: { w: number; h: number },
  opts?: { gap?: number; prefer?: Placement },
): { x: number; y: number; placement: Placement } {
  const gap = opts?.gap ?? TOOLTIP_GAP;
  const prefer = opts?.prefer ?? 'top';

  let placement = prefer;
  let pos = positionFor(placement, anchor, tip, gap);

  if (!fitsMainAxis(placement, pos, tip, view)) {
    const flipped = OPPOSITE[placement];
    const flippedPos = positionFor(flipped, anchor, tip, gap);
    if (fitsMainAxis(flipped, flippedPos, tip, view)) {
      placement = flipped;
      pos = flippedPos;
    }
    // else: neither side fits along its main axis — fall through with the
    // preferred side's position; the clamp below still guarantees no
    // overflow.
  }

  return {
    x: clamp(pos.x, 0, view.w - tip.w),
    y: clamp(pos.y, 0, view.h - tip.h),
    placement,
  };
}
