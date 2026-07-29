import { describe, expect, it } from 'vitest';
import { placeTooltip, TOOLTIP_GAP, type Rect } from '../src/nav/tooltip';

// FE-TOOLTIPS ticket Solution direction §1: `placeTooltip` is framework-free
// pure maths (no DOM, no Solid) — this file exercises every rule the ticket
// names: each of the four `prefer` sides in the ordinary (fits) case, a flip
// at every one of the four viewport edges, cross-axis shift at every corner,
// an oversized tooltip, and a zero-size (cursor-mode) anchor.

const VIEW = { w: 400, h: 300 };
const TIP = { w: 100, h: 40 };

/** A generously-centred anchor — nowhere near any edge — so the "ordinary,
 *  fits without flipping" cases below are not incidentally also exercising
 *  the flip/shift logic. */
function centerAnchor(): Rect {
  return { x: 150, y: 130, w: 20, h: 20 };
}

describe('placeTooltip — each of the four preferences, fitting normally', () => {
  it('top: centred above the anchor, gap above it', () => {
    const anchor = centerAnchor();
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'top' });
    expect(r.placement).toBe('top');
    expect(r.x).toBe(anchor.x + anchor.w / 2 - TIP.w / 2);
    expect(r.y).toBe(anchor.y - TOOLTIP_GAP - TIP.h);
  });

  it('bottom: centred below the anchor, gap below it', () => {
    const anchor = centerAnchor();
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'bottom' });
    expect(r.placement).toBe('bottom');
    expect(r.x).toBe(anchor.x + anchor.w / 2 - TIP.w / 2);
    expect(r.y).toBe(anchor.y + anchor.h + TOOLTIP_GAP);
  });

  it('left: centred left of the anchor, gap to its left', () => {
    const anchor = centerAnchor();
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'left' });
    expect(r.placement).toBe('left');
    expect(r.x).toBe(anchor.x - TOOLTIP_GAP - TIP.w);
    expect(r.y).toBe(anchor.y + anchor.h / 2 - TIP.h / 2);
  });

  it('right: centred right of the anchor, gap to its right', () => {
    const anchor = centerAnchor();
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'right' });
    expect(r.placement).toBe('right');
    expect(r.x).toBe(anchor.x + anchor.w + TOOLTIP_GAP);
    expect(r.y).toBe(anchor.y + anchor.h / 2 - TIP.h / 2);
  });

  it('defaults to "top" when no preference is given', () => {
    const anchor = centerAnchor();
    expect(placeTooltip(anchor, TIP, VIEW).placement).toBe('top');
  });
});

describe('placeTooltip — flip at every edge', () => {
  it('prefer top, anchor near the top edge: flips to bottom', () => {
    const anchor: Rect = { x: 150, y: 2, w: 20, h: 10 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'top' });
    expect(r.placement).toBe('bottom');
    expect(r.y).toBe(anchor.y + anchor.h + TOOLTIP_GAP);
  });

  it('prefer bottom, anchor near the bottom edge: flips to top', () => {
    const anchor: Rect = { x: 150, y: VIEW.h - 12, w: 20, h: 10 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'bottom' });
    expect(r.placement).toBe('top');
    expect(r.y).toBe(anchor.y - TOOLTIP_GAP - TIP.h);
  });

  it('prefer left, anchor near the left edge: flips to right', () => {
    const anchor: Rect = { x: 5, y: 130, w: 10, h: 20 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'left' });
    expect(r.placement).toBe('right');
    expect(r.x).toBe(anchor.x + anchor.w + TOOLTIP_GAP);
  });

  it('prefer right, anchor near the right edge: flips to left', () => {
    const anchor: Rect = { x: VIEW.w - 15, y: 130, w: 10, h: 20 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'right' });
    expect(r.placement).toBe('left');
    expect(r.x).toBe(anchor.x - TOOLTIP_GAP - TIP.w);
  });
});

describe('placeTooltip — cross-axis shift at every corner', () => {
  // Main axis (top/bottom) fits without flipping in every case below — the
  // point is the *cross* axis (x, for a vertical placement) getting clamped
  // into view rather than centring the tooltip off-screen.

  it('top-left corner, prefer bottom: x shifts right into view', () => {
    const anchor: Rect = { x: 5, y: 5, w: 10, h: 10 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'bottom' });
    expect(r.placement).toBe('bottom');
    // Centred x would be negative (5 + 5 - 50); clamped to the left edge.
    expect(r.x).toBe(0);
    expect(r.x).toBeGreaterThanOrEqual(0);
  });

  it('top-right corner, prefer bottom: x shifts left into view', () => {
    const anchor: Rect = { x: VIEW.w - 15, y: 5, w: 10, h: 10 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'bottom' });
    expect(r.placement).toBe('bottom');
    expect(r.x).toBe(VIEW.w - TIP.w);
    expect(r.x + TIP.w).toBeLessThanOrEqual(VIEW.w);
  });

  it('bottom-left corner, prefer top: x shifts right into view', () => {
    const anchor: Rect = { x: 5, y: VIEW.h - 15, w: 10, h: 10 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'top' });
    expect(r.placement).toBe('top');
    expect(r.x).toBe(0);
  });

  it('bottom-right corner, prefer top: x shifts left into view', () => {
    const anchor: Rect = { x: VIEW.w - 15, y: VIEW.h - 15, w: 10, h: 10 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'top' });
    expect(r.placement).toBe('top');
    expect(r.x).toBe(VIEW.w - TIP.w);
  });
});

describe('placeTooltip — oversized tooltip', () => {
  it('a tooltip larger than the view never overflows; clamps to the origin', () => {
    const anchor: Rect = { x: 190, y: 140, w: 20, h: 20 };
    const bigTip = { w: 500, h: 500 };
    const r = placeTooltip(anchor, bigTip, VIEW, { prefer: 'top' });
    // Neither top nor its flip (bottom) fits along the main axis — falls
    // back to the preferred side's position, then clamps both axes.
    expect(r.placement).toBe('top');
    expect(r.x).toBe(0);
    expect(r.y).toBe(0);
  });

  it('a tooltip wider than the view but shorter than it still clamps only the overflowing axis', () => {
    const anchor: Rect = { x: 190, y: 140, w: 20, h: 20 };
    const wideTip = { w: 500, h: 30 };
    const r = placeTooltip(anchor, wideTip, VIEW, { prefer: 'top' });
    expect(r.x).toBe(0);
    // Height (30) fits comfortably above the anchor — top should still work
    // for the vertical axis.
    expect(r.placement).toBe('top');
    expect(r.y).toBe(anchor.y - TOOLTIP_GAP - wideTip.h);
  });
});

describe('placeTooltip — zero-size anchor (cursor-follow edge case)', () => {
  it('treats a zero-size rect as a point anchor, with normal placement maths', () => {
    const anchor: Rect = { x: 150, y: 150, w: 0, h: 0 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'right' });
    expect(r.placement).toBe('right');
    expect(r.x).toBe(150 + TOOLTIP_GAP);
    expect(r.y).toBe(150 - TIP.h / 2);
  });

  it('a zero-size anchor near an edge still flips correctly', () => {
    const anchor: Rect = { x: 2, y: 150, w: 0, h: 0 };
    const r = placeTooltip(anchor, TIP, VIEW, { prefer: 'left' });
    expect(r.placement).toBe('right');
    expect(r.x).toBe(2 + TOOLTIP_GAP);
  });
});
