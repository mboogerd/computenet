import { Show } from 'solid-js';
import Canvas from './Canvas';
import { COLD_NOTICE, COLD_TAG, WAKE_CONFIRMATION } from '../nav/cold';
import { askToWake, cancelWake, confirmingWake, wakeError, wakeGraph, waking } from '../solid/cold';
import './ColdScreen.css';

/** M5-COLD ticket Implement §2 — the cold screen: the v2 mockup's "Read
 *  checkpoint (no attention)" vs "Wake to inspect", scaled to what the kernel
 *  can honestly do today.
 *
 *  Three deliberate choices:
 *
 *  1. **The structure is still shown**, ghosted. Topology is registry
 *     metadata, so drawing it costs the parked graph nothing (M5-COLD
 *     Implement §1: "structure of a cold graph remains servable"). Selecting a
 *     node still works and still opens the detail panel — on the descriptor
 *     only, because `solid/detail.ts` withholds the observe subscription while
 *     the graph is cold.
 *  2. **No preview of state.** The mockup's "read checkpoint" half needs cold
 *     reads from a checkpoint or journal, which the kernel does not have
 *     (Linear MRB-157). So the notice says *unavailable*; it does not show a
 *     last-known value dressed up as current, which would be worse than
 *     showing nothing.
 *  3. **Waking is confirmed, never implicit.** The button opens a dialog that
 *     names the consequence before anything is sent. */
export default function ColdScreen() {
  return (
    <div class="cold-screen">
      <div class="cold-banner" role="status">
        <span class="cold-banner__tag" aria-hidden="true">
          {COLD_TAG}
        </span>
        <div class="cold-banner__text">
          <p class="cold-banner__notice">{COLD_NOTICE}</p>
          <p class="cold-banner__sub">
            Structure is registry metadata and costs this graph nothing to draw. Selecting a cell shows its descriptor
            only.
          </p>
          <Show when={wakeError()}>
            <p class="cold-banner__error">Wake failed — the graph is still parked.</p>
          </Show>
        </div>
        <button class="cold-banner__wake" disabled={waking()} onClick={askToWake}>
          {waking() ? 'Waking…' : 'Wake to inspect'}
        </button>
      </div>

      {/* Ghosted, not hidden: `.cold-screen__ghost` dims the real canvas
          rather than substituting a different rendering for it, so what the
          user sees while cold is the same graph they will see once it is
          hot. */}
      <div class="cold-screen__ghost">
        <Canvas />
      </div>

      <Show when={confirmingWake()}>
        <WakeDialog />
      </Show>
    </div>
  );
}

/** The confirmation. Deliberately blunt about the consequence: this is the one
 *  place the inspector stops being an observer (10-target-v3.md §Constraints 2
 *  — "observation is causal"), so the user is told exactly that before it
 *  happens, and Cancel is the default-focused way out. */
function WakeDialog() {
  return (
    <div class="cold-dialog__scrim" onClick={cancelWake}>
      <div
        class="cold-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="Wake this graph"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 class="cold-dialog__title">Wake this graph?</h2>
        <p class="cold-dialog__body">{WAKE_CONFIRMATION}</p>
        <p class="cold-dialog__note">
          Resuming a drained host reactivates every cell it holds, including cells of other graphs.
        </p>
        <div class="cold-dialog__actions">
          <button class="cold-dialog__cancel" onClick={cancelWake} autofocus>
            Cancel
          </button>
          <button class="cold-dialog__confirm" onClick={wakeGraph}>
            Wake
          </button>
        </div>
      </div>
    </div>
  );
}
