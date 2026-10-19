import { For, Show } from 'solid-js';
import type { Config, NodeState } from './types';

export const choiceLines = (value: string): string[] => value.split('\n').map(line => line.trim()).filter(Boolean);
export function judgeCaption(config: Config, state?: NodeState): string {
  const last = state?.judgment?.last;
  return `${config.mode ?? 'judge'} · ${last?.answer ?? 'No judgment yet'}${last?.probability != null ? ` · p=${last.probability}` : ''}`;
}

export function JudgeCard(props: { config: Config; state?: NodeState }) {
  return <div>
    <p class="small">{judgeCaption(props.config, props.state)}</p>
    <Show when={props.config.mode === 'gate'}><p class="muted small">
      Passed {props.state?.judgment?.passed ?? 0} · Gated {props.state?.judgment?.gated ?? 0}
    </p></Show>
  </div>;
}

export default function JudgeSettings(props: { config: Config; ports: string[]; set: (key: string, value: Config[string]) => void }) {
  const select = (key: string, label: string, values: string[]) => <label class="field"><span>{label}</span>
    <select value={String(props.config[key] ?? values[0])} onChange={event => props.set(key, event.currentTarget.value)}>
      <For each={values}>{value => <option value={value}>{value}</option>}</For>
    </select>
  </label>;
  return <>
    <label class="field"><span>Question</span><textarea rows="5" value={String(props.config.question ?? '')}
      onInput={event => props.set('question', event.currentTarget.value)} /></label>
    <p class="hint">Inputs come from placeholders such as <code>{'{{diff}}'}</code> and <code>{'{{log.new}}'}</code>.</p>
    {select('mode', 'Mode', ['judge', 'gate'])}
    {select('output', 'Answer type', ['boolean', 'probability', 'choice'])}
    <Show when={props.config.output === 'choice'}>
      <label class="field"><span>Choices (one per line)</span><textarea rows="4"
        value={Array.isArray(props.config.choices) ? props.config.choices.join('\n') : ''}
        onChange={event => props.set('choices', choiceLines(event.currentTarget.value))} /></label>
      <Show when={props.config.mode === 'gate'}>
        <label class="field"><span>Passing choices (one per line)</span><textarea rows="3"
          value={Array.isArray(props.config.passChoices) ? props.config.passChoices.join('\n') : ''}
          onChange={event => props.set('passChoices', choiceLines(event.currentTarget.value))} /></label>
      </Show>
    </Show>
    <Show when={props.config.mode === 'gate'}>
      <Show when={props.config.output === 'probability'}>
        <label class="field"><span>Pass threshold (inclusive)</span><input type="number" min="0" max="1" step="0.01"
          value={Number(props.config.threshold ?? 0.5)} onInput={event => props.set('threshold', Number(event.currentTarget.value))} /></label>
      </Show>
      <label class="field"><span>Input to pass through</span><input list="jev-pass-ports" value={String(props.config.passPort ?? '')}
        placeholder="First input" onInput={event => props.set('passPort', event.currentTarget.value)} />
        <datalist id="jev-pass-ports"><For each={props.ports}>{port => <option value={port} />}</For></datalist>
        <small>Leave blank for the first placeholder in the question.</small></label>
    </Show>
    <label class="field"><span>Jev model</span><input value={String(props.config.model ?? '')}
      placeholder="jev-latest" onInput={event => props.set('model', event.currentTarget.value)} /></label>
    <p class="hint">The server reads TYPESAFE_API_KEY from its environment. No key is stored in this workflow.</p>
  </>;
}
