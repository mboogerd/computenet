import { For, Show } from 'solid-js';
import type { Config, NodeState } from './types';
import { beadsSummary } from './beadsModel';

export function BeadsFields(props: { config: Config; source?: boolean; set: (key: string, value: Config[string]) => void }) {
  const field = (key: string, label: string, fallback = '') => <label class="field"><span>{label}</span>
    <input value={String(props.config[key] ?? fallback)} onInput={event => props.set(key, event.currentTarget.value)} />
  </label>;
  return <>
    {field('workspace', 'Beads workspace (absolute path)')}
    <Show when={props.source}>
      <label class="field"><span>Query</span><select value={String(props.config.query ?? 'ready')} onChange={event => props.set('query', event.currentTarget.value)}>
        <option value="ready">ready</option><option value="list">list</option>
      </select></label>
      <Show when={props.config.query === 'list'}>
        {field('status', 'Status filter (optional)')}{field('label', 'Label filter (optional)')}{field('type', 'Type filter (optional)')}
      </Show>
      <label class="field"><span>Poll interval (seconds)</span><input type="number" min="1" step="1" value={Number(props.config.intervalSec ?? 60)}
        onInput={event => props.set('intervalSec', Number(event.currentTarget.value))} /></label>
      <p class="hint">One change per observed difference. Use <code>{'{{x.added}}'}</code>, <code>{'{{x.removed}}'}</code>, <code>{'{{x.changed}}'}</code> or <code>{'{{x.new}}'}</code> in downstream templates. The first observation is silent.</p>
    </Show>
    <Show when={!props.source}>
      <p class="hint">Writes to the real tracker in this workspace. Manual Run is the default. This repository syncs its tracker to DoltHub on push.</p>
      <Show when={props.config.preset === 'bd-comment'}>
        {field('issue', 'Issue id template', '{{in.issue}}')}
        <label class="field"><span>Comment text template</span><textarea rows="5" value={String(props.config.text ?? '{{in}}')} onInput={event => props.set('text', event.currentTarget.value)} /></label>
      </Show>
      <Show when={props.config.preset === 'bd-create'}>
        {field('title', 'Issue title template', '{{in}}')}
        <label class="field"><span>Description template</span><textarea rows="5" value={String(props.config.description ?? '')} onInput={event => props.set('description', event.currentTarget.value)} /></label>
        {field('type', 'Issue type template', 'task')}{field('priority', 'Priority template (0–4)', '2')}{field('labels', 'Labels template (comma-separated)')}
        <p class="hint">Creates an unparented issue.</p>
      </Show>
    </Show>
  </>;
}

export function BeadsCard(props: { config: Config; state?: NodeState; fire: () => unknown }) {
  const summary = () => beadsSummary(props.state);
  return <>
    <p class="repo" title={String(props.config.workspace ?? '')}>{String(props.config.workspace || 'Select cell to set workspace')}</p>
    <div class="actions"><span class="small">{String(props.config.query ?? 'ready')} · {summary().count ?? '—'} issues</span><button onClick={props.fire}>Fire</button></div>
    <p class="muted small">Last added: <Show when={summary().added.length} fallback="none"><For each={summary().added}>{(id, index) => <>{index() ? ', ' : ''}{id}</>}</For></Show></p>
  </>;
}
