import { For } from 'solid-js';
import { toasts } from '../solid/graph';
import './Toasts.css';

/** Transient command-error surface (spec §7). */
export default function Toasts() {
  return (
    <div class="toasts">
      <For each={toasts()}>{(t) => <div class="toast">{t.msg}</div>}</For>
    </div>
  );
}
