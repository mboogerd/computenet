import { For } from 'solid-js';
import './LoadingState.css';

/** Pre-snapshot placeholder: a skeleton of the debate layout, so the first
 *  paint already has the page's shape instead of a bare "Connecting…". */
export default function LoadingState() {
  return (
    <div class="loading" role="status" aria-live="polite">
      <span class="loading__sr">Connecting…</span>
      <div class="skel skel--bar" />
      <div class="skel skel--focal" />
      <div class="loading__cols">
        <For each={[0, 1]}>
          {() => (
            <div class="loading__col">
              <div class="skel skel--title" />
              <div class="skel skel--row" />
              <div class="skel skel--row" />
              <div class="skel skel--row skel--short" />
            </div>
          )}
        </For>
      </div>
    </div>
  );
}
