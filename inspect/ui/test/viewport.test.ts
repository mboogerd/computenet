import { describe, expect, it } from 'vitest';
import {
  clampScale,
  clampViewport,
  fitBounds,
  IDENTITY_VIEWPORT,
  MAX_FIT_SCALE,
  MAX_SCALE,
  MIN_SCALE,
  MIN_VISIBLE,
  panBy,
  resetViewport,
  zoomAt,
  type Size,
  type Viewport,
} from '../src/nav/viewport';

/** The scene point under a client-space point, given the
 *  `client = scene * scale + (x, y)` mapping every function here implements
 *  — the exact invariant `zoomAt` must preserve at `anchor`. */
function sceneAt(vp: Viewport, client: { x: number; y: number }) {
  return { x: (client.x - vp.x) / vp.scale, y: (client.y - vp.y) / vp.scale };
}

describe('clampScale', () => {
  it('passes values inside [MIN_SCALE, MAX_SCALE] through unchanged', () => {
    expect(clampScale(1)).toBe(1);
    expect(clampScale(0.5)).toBe(0.5);
  });

  it('clamps below MIN_SCALE and above MAX_SCALE', () => {
    expect(clampScale(0.001)).toBe(MIN_SCALE);
    expect(clampScale(1000)).toBe(MAX_SCALE);
  });
});

describe('fitBounds', () => {
  it('returns the identity viewport for an empty scene (w === 0 or h === 0), never dividing by zero', () => {
    expect(fitBounds({ w: 0, h: 500 }, { w: 800, h: 600 })).toEqual(IDENTITY_VIEWPORT);
    expect(fitBounds({ w: 500, h: 0 }, { w: 800, h: 600 })).toEqual(IDENTITY_VIEWPORT);
    expect(fitBounds({ w: 0, h: 0 }, { w: 800, h: 600 })).toEqual(IDENTITY_VIEWPORT);
  });

  it('scales a wide scene down to fit the view width, and centres it', () => {
    const scene: Size = { w: 4000, h: 200 };
    const view: Size = { w: 1000, h: 800 };
    const vp = fitBounds(scene, view);
    expect(vp.scale).toBeCloseTo((1000 - 24 * 2) / 4000, 6);
    // Centred: the scaled scene's midpoint lands on the view's midpoint.
    expect(vp.x + (scene.w * vp.scale) / 2).toBeCloseTo(view.w / 2, 6);
    expect(vp.y + (scene.h * vp.scale) / 2).toBeCloseTo(view.h / 2, 6);
  });

  it('scales a tall scene down to fit the view height, and centres it', () => {
    const scene: Size = { w: 200, h: 3000 };
    const view: Size = { w: 800, h: 600 };
    const vp = fitBounds(scene, view);
    expect(vp.scale).toBeCloseTo((600 - 24 * 2) / 3000, 6);
    expect(vp.y + (scene.h * vp.scale) / 2).toBeCloseTo(view.h / 2, 6);
  });

  it('clamps a tiny scene to MAX_FIT_SCALE (100%) rather than blowing it up', () => {
    const scene: Size = { w: 50, h: 40 };
    const view: Size = { w: 1200, h: 900 };
    const vp = fitBounds(scene, view);
    expect(vp.scale).toBe(MAX_FIT_SCALE);
    expect(vp.x + (scene.w * vp.scale) / 2).toBeCloseTo(view.w / 2, 6);
    expect(vp.y + (scene.h * vp.scale) / 2).toBeCloseTo(view.h / 2, 6);
  });

  it('honors a custom margin', () => {
    const scene: Size = { w: 1000, h: 1000 };
    const view: Size = { w: 1000, h: 1000 };
    const vp = fitBounds(scene, view, { margin: 100 });
    expect(vp.scale).toBeCloseTo((1000 - 200) / 1000, 6);
  });
});

describe('resetViewport', () => {
  it('is always scale 1, centred over the view', () => {
    const scene: Size = { w: 1200, h: 400 };
    const view: Size = { w: 800, h: 600 };
    const vp = resetViewport(scene, view);
    expect(vp.scale).toBe(1);
    expect(vp.x).toBeCloseTo((view.w - scene.w) / 2, 6);
    expect(vp.y).toBeCloseTo((view.h - scene.h) / 2, 6);
  });
});

describe('zoomAt — cursor-anchored zoom invariance', () => {
  const scene: Size = { w: 4000, h: 3000 };
  const view: Size = { w: 1000, h: 800 };

  it('keeps the scene point under the anchor fixed when zooming in', () => {
    const vp: Viewport = { x: -200, y: -100, scale: 1 };
    const anchor = { x: 400, y: 300 };
    const before = sceneAt(vp, anchor);
    const after = zoomAt(vp, anchor, 1.5, scene, view);
    const afterScene = sceneAt(after, anchor);
    expect(afterScene.x).toBeCloseTo(before.x, 6);
    expect(afterScene.y).toBeCloseTo(before.y, 6);
    expect(after.scale).toBeCloseTo(1.5, 6);
  });

  it('keeps the scene point under the anchor fixed when zooming out', () => {
    const vp: Viewport = { x: -500, y: -300, scale: 2 };
    const anchor = { x: 150, y: 650 };
    const before = sceneAt(vp, anchor);
    const after = zoomAt(vp, anchor, 0.5, scene, view);
    const afterScene = sceneAt(after, anchor);
    expect(afterScene.x).toBeCloseTo(before.x, 6);
    expect(afterScene.y).toBeCloseTo(before.y, 6);
    expect(after.scale).toBeCloseTo(1, 6);
  });

  it('holds the invariant for an off-centre anchor, not just the view centre', () => {
    const vp: Viewport = { x: 0, y: 0, scale: 1 };
    const anchor = { x: 950, y: 30 }; // near a corner
    const before = sceneAt(vp, anchor);
    const after = zoomAt(vp, anchor, 1.2, scene, view);
    const afterScene = sceneAt(after, anchor);
    expect(afterScene.x).toBeCloseTo(before.x, 6);
    expect(afterScene.y).toBeCloseTo(before.y, 6);
  });

  it('is a true no-op at the MAX_SCALE boundary (anchor trivially holds — nothing moved)', () => {
    const vp: Viewport = { x: -1000, y: -800, scale: MAX_SCALE };
    const anchor = { x: 500, y: 400 };
    const after = zoomAt(vp, anchor, 1.5, scene, view);
    expect(after).toBe(vp); // same reference: a genuine no-op, not a recomputed identical value
  });

  it('is a true no-op at the MIN_SCALE boundary (anchor trivially holds — nothing moved)', () => {
    const vp: Viewport = { x: 100, y: 50, scale: MIN_SCALE };
    const anchor = { x: 500, y: 400 };
    const after = zoomAt(vp, anchor, 0.5, scene, view);
    expect(after).toBe(vp);
  });

  it('clamps the resulting scale to MAX_SCALE without exceeding it', () => {
    const vp: Viewport = { x: 0, y: 0, scale: 2.9 };
    const after = zoomAt(vp, { x: 500, y: 400 }, 2, scene, view);
    expect(after.scale).toBe(MAX_SCALE);
  });

  it('clamps the resulting scale to MIN_SCALE without going below it', () => {
    const vp: Viewport = { x: 0, y: 0, scale: 0.15 };
    const after = zoomAt(vp, { x: 500, y: 400 }, 0.2, scene, view);
    expect(after.scale).toBe(MIN_SCALE);
  });
});

describe('panBy', () => {
  const scene: Size = { w: 4000, h: 3000 };
  const view: Size = { w: 1000, h: 800 };

  it('translates by -(dx, dy) — wheel "scroll" sign convention', () => {
    const vp: Viewport = { x: -100, y: -100, scale: 1 };
    const after = panBy(vp, 30, -20, scene, view);
    expect(after.x).toBeCloseTo(-130, 6);
    expect(after.y).toBeCloseTo(-80, 6);
    expect(after.scale).toBe(1);
  });

  it('is a no-op when dx and dy are both 0 and the result is already in range', () => {
    const vp: Viewport = { x: -100, y: -100, scale: 1 };
    expect(panBy(vp, 0, 0, scene, view)).toEqual(vp);
  });
});

describe('clampViewport — pan/zoom loss prevention', () => {
  const scene: Size = { w: 2000, h: 1500 };
  const view: Size = { w: 1000, h: 800 };

  it('leaves an in-range viewport untouched', () => {
    const vp: Viewport = { x: 0, y: 0, scale: 1 };
    expect(clampViewport(vp, scene, view)).toEqual(vp);
  });

  it('pulls a viewport panned far to the right back so MIN_VISIBLE px stays on screen', () => {
    const vp: Viewport = { x: 100_000, y: 0, scale: 1 };
    const after = clampViewport(vp, scene, view);
    expect(after.x).toBeLessThanOrEqual(view.w - MIN_VISIBLE);
  });

  it('pulls a viewport panned far to the left back so MIN_VISIBLE px stays on screen', () => {
    const vp: Viewport = { x: -100_000, y: 0, scale: 1 };
    const after = clampViewport(vp, scene, view);
    const scaledW = scene.w * after.scale;
    expect(after.x).toBeGreaterThanOrEqual(MIN_VISIBLE - scaledW);
  });

  it('pulls a viewport panned far up/down back on the y axis the same way', () => {
    const down = clampViewport({ x: 0, y: -100_000, scale: 1 }, scene, view);
    expect(down.y).toBeGreaterThanOrEqual(MIN_VISIBLE - scene.h * down.scale);
    const up = clampViewport({ x: 0, y: 100_000, scale: 1 }, scene, view);
    expect(up.x).toBeLessThanOrEqual(view.w - MIN_VISIBLE); // sanity: x untouched by a y-only excursion
    expect(up.y).toBeLessThanOrEqual(view.h - MIN_VISIBLE);
  });

  it('never demands more visible px than a fully-zoomed-out scene actually has', () => {
    // At scale 0.1 on a small scene the *entire* scaled scene is smaller than
    // MIN_VISIBLE; the clamp must not then force an unreachable position.
    const tinyScene: Size = { w: 100, h: 80 };
    const vp: Viewport = { x: 0, y: 0, scale: MIN_SCALE };
    const after = clampViewport(vp, tinyScene, view);
    expect(after).toEqual(vp); // already fully on screen — no-op
  });

  it('is a no-op for a zero-area scene or view rather than producing NaN', () => {
    const vp: Viewport = { x: 10, y: 10, scale: 1 };
    expect(clampViewport(vp, { w: 0, h: 0 }, view)).toEqual(vp);
    expect(clampViewport(vp, scene, { w: 0, h: 0 })).toEqual(vp);
  });
});
