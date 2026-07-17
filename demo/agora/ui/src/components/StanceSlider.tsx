import { Show } from 'solid-js';
import type { Ref } from '../api/types';
import { setStance } from '../api/commands';
import { localStance, setLocalStance } from '../solid/stance';
import { notify } from '../solid/graph';
import './StanceSlider.css';

/** Continuous stance input (spec §6). Bound to the DEVICE-LOCAL "your stance"
 *  value — never to aggregate credence — so it never fights a live update. */
export default function StanceSlider(props: { nodeRef: Ref }) {
  const value = () => localStance(props.nodeRef);
  const display = () => value() ?? 0.5;

  const commit = (v: number | null) => {
    setLocalStance(props.nodeRef, v);
    void setStance(props.nodeRef, v).catch((e) => notify(`Stance failed: ${(e as Error).message}`));
  };

  return (
    <div class="stance">
      <input
        class="stance__range"
        classList={{ 'stance__range--unset': value() === undefined }}
        type="range"
        min="0"
        max="1"
        step="0.05"
        value={display()}
        onInput={(e) => commit(+e.currentTarget.value)}
      />
      <div class="stance__row">
        <span class="stance__label">
          {value() === undefined ? 'No stance set — drag to stake one' : `Your stance: ${value()!.toFixed(2)}`}
        </span>
        <Show when={value() !== undefined}>
          <button class="stance__clear" onClick={() => commit(null)}>
            Clear
          </button>
        </Show>
      </div>
    </div>
  );
}
