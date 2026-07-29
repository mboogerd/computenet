import { createEffect, createSignal, on } from 'solid-js';
import type { Placement, Rect, TooltipContent } from '../nav/tooltip';
import { viewport } from './viewport';

// FE-TOOLTIPS ticket Solution direction §3: the single global "current
// tooltip" controller. Every hover/focus site in `Canvas.tsx` calls
// `showTooltip`/`hideTooltip` here rather than owning any state itself —
// "exactly one tooltip element exists in the DOM at a time" (ticket
// acceptance criteria) is a direct consequence of there being exactly one
// signal here that `components/Tooltip.tsx` renders.

/** The DOM id `Tooltip.tsx`'s single element carries — stable, so
 *  `aria-describedby` can reference it before the tooltip itself has ever
 *  been shown. */
export const TOOLTIP_ID = 'inspect-tooltip';

/** Hover-intent delay (ticket Solution direction §3: "~120ms delay before
 *  showing"). Skipped entirely when a tooltip is already shown (retargeting
 *  between two anchors) or when the caller explicitly asks for it (keyboard
 *  focus — Tab moves deliberately, one element at a time, unlike a mouse
 *  sweeping across many). */
const HOVER_INTENT_MS = 120;

/** "Near-immediate" hide grace window (ticket: "near-immediate hide"). Not
 *  zero: a fast re-target — the pointer leaving one anchor and entering a
 *  neighbouring one within the same short window — must read as continuous,
 *  not as a flicker of the tooltip disappearing and reappearing. Long enough
 *  to cover that one-frame-ish gap, short enough that actually leaving for
 *  good still reads as an immediate dismissal. Anything that must dismiss
 *  unconditionally and instantly (Escape, blur, selection change, viewport
 *  change) calls {@link dismissTooltip} instead, which has no grace window
 *  at all. */
const HIDE_GRACE_MS = 40;

/** `Element.getBoundingClientRect()` as a `nav/tooltip.ts` `Rect` — the
 *  shape every element-anchored call site (`Canvas.tsx`'s port dots, node
 *  cards, state chips, error badges, parked pills) hands `showTooltip`.
 *  `DOMRect` already reports client-space post-transform coordinates
 *  (FE-CANVAS's own finding — see this ticket's completion report), so this
 *  is a field rename, never a coordinate conversion. */
export function elementAnchor(el: Element): Rect {
  const r = el.getBoundingClientRect();
  return { x: r.x, y: r.y, w: r.width, h: r.height };
}

export interface ShownTooltip {
  readonly content: TooltipContent;
  /** Client-space rect, read live — an accessor rather than a snapshot so a
   *  cursor-follow edge tooltip (`cursorAnchorRect` below) keeps tracking
   *  the pointer every coalesced update without a new `showTooltip` call,
   *  and an element anchor is re-measured (`getBoundingClientRect()`) on
   *  every render rather than trusting a rect captured at show-time. */
  readonly anchor: () => Rect;
  readonly prefer: Placement;
}

const [tooltip, setTooltipSignal] = createSignal<ShownTooltip | null>(null);
export { tooltip };

/** Identifies whose hover/focus is driving the currently shown tooltip.
 *  `Canvas.tsx` compares this against a node card's own key
 *  (`` `card:${ref}` ``) to decide whether to set `aria-describedby` on it
 *  while its tooltip is shown (ticket Solution direction §3) — the only
 *  focusable anchor among the eight sites this ticket wires up. Every other
 *  anchor still passes a key for uniformity, even though nothing reads it
 *  for them. */
const [activeKey, setActiveKeySignal] = createSignal<string | null>(null);
export { activeKey };

let showTimer: ReturnType<typeof setTimeout> | undefined;
let hideTimer: ReturnType<typeof setTimeout> | undefined;

function clearShowTimer(): void {
  if (showTimer !== undefined) {
    clearTimeout(showTimer);
    showTimer = undefined;
  }
}

function clearHideTimer(): void {
  if (hideTimer !== undefined) {
    clearTimeout(hideTimer);
    hideTimer = undefined;
  }
}

export interface ShowTooltipParams {
  /** See {@link activeKey}'s doc comment. */
  key: string;
  content: TooltipContent;
  anchor: () => Rect;
  /** Default `'top'` — `Tooltip.tsx` and `nav/tooltip.ts`'s `placeTooltip`
   *  flip away from this when it does not fit. */
  prefer?: Placement;
  /** Skip {@link HOVER_INTENT_MS} entirely — keyboard focus (ticket: "show
   *  on focus/focusin"), where a delay would read as a broken tab stop
   *  rather than an intentional affordance. */
  immediate?: boolean;
}

/** Show a tooltip for `params.key`'s anchor.
 *
 *  Delayed by {@link HOVER_INTENT_MS} unless a tooltip is already shown (a
 *  retarget — moving from one already-shown anchor straight to another) or
 *  `params.immediate` is set — both skip the delay outright (ticket
 *  Solution direction §3: "re-targeting between two anchors while one is
 *  already shown skips the delay"). */
export function showTooltip(params: ShowTooltipParams): void {
  const prefer = params.prefer ?? 'top';
  const skipDelay = tooltip() !== null || params.immediate === true;

  clearShowTimer();
  clearHideTimer();

  const commit = () => {
    setTooltipSignal({ content: params.content, anchor: params.anchor, prefer });
    setActiveKeySignal(params.key);
  };

  if (skipDelay) {
    commit();
    return;
  }
  showTimer = setTimeout(() => {
    showTimer = undefined;
    commit();
  }, HOVER_INTENT_MS);
}

/** Soft dismiss — the pointer left the anchor (or a focusable anchor
 *  blurred without anything else claiming the tooltip). Delayed by
 *  {@link HIDE_GRACE_MS} so a fast re-target lands inside the grace window
 *  and cancels it outright via {@link showTooltip}'s own `clearHideTimer()`
 *  call, rather than flickering the tooltip off and back on. */
export function hideTooltip(): void {
  clearShowTimer();
  if (tooltip() === null) return;
  clearHideTimer();
  hideTimer = setTimeout(() => {
    hideTimer = undefined;
    setTooltipSignal(null);
    setActiveKeySignal(null);
  }, HIDE_GRACE_MS);
}

/** Hard, immediate dismiss — no grace window, no pending show either. Used
 *  for every "worse than no tooltip" case (ticket Solution direction §3): a
 *  viewport change (the anchor rect has since moved), Escape, window blur,
 *  and browser selection change. */
export function dismissTooltip(): void {
  clearShowTimer();
  clearHideTimer();
  setTooltipSignal(null);
  setActiveKeySignal(null);
}

// --- cursor-follow anchor (edges) ---------------------------------------
//
// An edge's bounding box is a useless anchor for a long diagonal line
// (ticket Solution direction §3) — its tooltip follows the cursor instead.
// One shared `{x, y}` signal (not per-edge) is enough: only one edge can be
// hovered at a time, and `Canvas.tsx` reports the live pointer position into
// it from whichever `.edge-hit` currently has the pointer.

const [cursorPoint, setCursorPointSignal] = createSignal<{ x: number; y: number }>({ x: 0, y: 0 });
let pendingCursor: { x: number; y: number } | null = null;
let cursorRafScheduled = false;

/** Coalesced to at most one update per animation frame (ticket Solution
 *  direction §3) — `pointermove` over an `.edge-hit` line can fire far more
 *  often than a frame, and committing every one of those to a Solid signal
 *  would re-run the tooltip's placement memo that often too. */
export function reportCursorPosition(x: number, y: number): void {
  pendingCursor = { x, y };
  if (cursorRafScheduled) return;
  cursorRafScheduled = true;
  requestAnimationFrame(() => {
    cursorRafScheduled = false;
    if (pendingCursor) setCursorPointSignal(pendingCursor);
  });
}

/** A zero-size rect at the live cursor position — `nav/tooltip.ts`'s
 *  `placeTooltip` treats a zero-size rect as a point anchor. Hand this
 *  function itself (not its return value) to `showTooltip`'s `anchor`
 *  field, so the tooltip keeps tracking the cursor on every coalesced
 *  update without a new `showTooltip` call. */
export function cursorAnchorRect(): Rect {
  const p = cursorPoint();
  return { x: p.x, y: p.y, w: 0, h: 0 };
}

// --- dismissal wiring (ticket Solution direction §3) --------------------

let dismissalInitialized = false;

/** Call once from `app.tsx`'s `onMount` (mirrors `solid/detail.ts`'s
 *  `initDetail`, `solid/route.ts`'s `initRoute`). Wires every dismissal path
 *  that is not a per-element pointer/focus handler in `Canvas.tsx`:
 *
 *  - viewport change — off FE-CANVAS's own `viewport()` signal, never by
 *    re-measuring (ticket: "a tooltip anchored to a rect that has since
 *    moved is worse than no tooltip"). `on(viewport, ..., { defer: true })`
 *    tracks *only* `viewport` (not `tooltip()`, which `dismissTooltip` also
 *    touches) — reading `tooltip()` inside a plain `createEffect` here would
 *    make the effect re-run (and immediately dismiss) the instant a tooltip
 *    is first shown, since showing one changes `tooltip()` too.
 *  - `Escape` and window `blur` — global, since these can fire while focus
 *    is anywhere (or nowhere).
 *  - `selectionchange` — a browser text-selection drag (node card names and
 *    type rows are selectable text), not this app's own cell `selection()`
 *    store, which has no tooltip relationship of its own. */
export function initTooltipDismissal(): void {
  if (dismissalInitialized) return; // module-level singleton; app.tsx mounts once, but guard anyway
  dismissalInitialized = true;

  createEffect(on(viewport, () => dismissTooltip(), { defer: true }));

  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') dismissTooltip();
  });
  window.addEventListener('blur', () => dismissTooltip());
  document.addEventListener('selectionchange', () => dismissTooltip());
}
