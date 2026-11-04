import { For } from 'solid-js';
import { toasts } from '../solid/graph';
import { AlertIcon } from './Icons';
import './Toasts.css';

/** Transient command-error surface (spec §7). */
export default function Toasts() {
  return (
    <div class="toasts" role="status" aria-live="polite">
      <For each={toasts()}>{(t) => (
          <div class="toast">
            <AlertIcon size={16} />
            <span>{t.msg}</span>
          </div>
        )}</For>
    </div>
  );
}
