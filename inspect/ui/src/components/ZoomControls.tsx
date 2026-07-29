import { fitToScreen, resetZoom, viewport, zoomBy } from '../solid/viewport';
import './ZoomControls.css';

/** Multiplicative step for the +/- buttons — matches `Canvas.tsx`'s own
 *  `ZOOM_STEP` for the keyboard shortcuts; kept as a separate constant since
 *  FE-CANVAS ticket Implement §5 calls out this file as its own component
 *  ("a separate component file, not inline JSX — FE-TESTS renders it
 *  directly"), not sharing module-private constants across files. */
const ZOOM_STEP = 1.2;

/** FE-CANVAS ticket Solution direction §5: zoom out / percentage readout /
 *  zoom in / Fit, rendered inside `.canvas` but outside `.canvas__pan`
 *  (`Canvas.tsx`) so it never scales with the canvas transform. Real
 *  `<button>` elements with `aria-label` + `title`, styled from
 *  `styles/tokens.css` variables only — no raw hex, so it reads correctly in
 *  both themes. */
export default function ZoomControls() {
  const percent = () => Math.round(viewport().scale * 100);

  return (
    <div class="zoom-controls" role="group" aria-label="Canvas zoom controls">
      <button
        type="button"
        class="zoom-controls__btn"
        aria-label="Zoom out"
        title="Zoom out"
        onClick={() => zoomBy(1 / ZOOM_STEP)}
      >
        −
      </button>
      <button
        type="button"
        class="zoom-controls__readout"
        aria-label="Reset zoom to 100%"
        title="Reset zoom to 100%"
        onClick={() => resetZoom()}
      >
        {percent()}%
      </button>
      <button type="button" class="zoom-controls__btn" aria-label="Zoom in" title="Zoom in" onClick={() => zoomBy(ZOOM_STEP)}>
        +
      </button>
      <button
        type="button"
        class="zoom-controls__btn zoom-controls__fit"
        aria-label="Fit graph to screen"
        title="Fit graph to screen"
        onClick={() => fitToScreen()}
      >
        Fit
      </button>
    </div>
  );
}
