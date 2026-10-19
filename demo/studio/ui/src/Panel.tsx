import { createEffect, createMemo, createResource, createSignal, For, Show } from 'solid-js';
import { nodePath, request } from './api';
import type { Config, NodeState, RunDetail, RunSummary, StudioNode } from './types';
import { Status, type Action } from './NodeCard';
import JudgeSettings from './JudgeSettings';
import { promotionAction } from './model';
import ConfirmationDialog, { type Confirmation } from './ConfirmationDialog';
import { BeadsFields } from './StudioBeads';
import { beadsPresetConfig, isBeadsPreset } from './beadsModel';

export default function Panel(props: { workflowId: string; node: StudioNode; state?: NodeState; action: Action; close: () => void; remove: () => void; toast: (message: string) => void }) {
  const [title, setTitle] = createSignal('');
  const [config, setConfig] = createSignal<Config>({});
  const [saving, setSaving] = createSignal(false);
  const [confirmation, setConfirmation] = createSignal<Confirmation>();
  const [runId, setRunId] = createSignal<string>();
  // Runtime frames do not overwrite unsaved form edits.
  const signature = createMemo(() => JSON.stringify([props.node.id, props.node.title, props.node.config]));
  createEffect(() => {
    const [id, name, settings] = JSON.parse(signature()) as [string, string, Config];
    void id;
    setTitle(name); setConfig(settings);
  });
  const set = (key: string, value: Config[string]) => setConfig(old => ({ ...old, [key]: value }));
  const base = () => nodePath(props.workflowId, props.node.id);
  const [runs] = createResource(() => `${props.node.id}:${props.state?.runCount ?? 0}:${props.state?.lastRun?.runId ?? ''}`, async () => {
    try { return await request<RunSummary[]>(`${base()}/runs`); }
    catch (error) { props.toast(String(error instanceof Error ? error.message : error)); return []; }
  });
  const [detail] = createResource(() => runId() ? [props.node.id, runId()!] as const : false, async ([id, run]) => {
    try { return await request<RunDetail>(`${nodePath(props.workflowId, id)}/runs/${encodeURIComponent(run)}`); }
    catch (error) { props.toast(String(error instanceof Error ? error.message : error)); return undefined; }
  });
  const save = async () => {
    setSaving(true);
    try { return await props.action(base(), 'PATCH', { title: title(), config: config() }); }
    finally { setSaving(false); }
  };
  const field = (key: string, label: string, type = 'text', hint?: string) => <label class="field">
    <span>{label}</span>
    <input type={type} value={String(config()[key] ?? '')} step={key === 'maxBudgetUsd' ? '0.01' : '1'}
      min={type === 'number' ? (key === 'maxBudgetUsd' ? 0 : 1) : undefined}
      onInput={event => set(key, type === 'number' ? Number(event.currentTarget.value) : event.currentTarget.value)} />
    <Show when={hint}><small>{hint}</small></Show>
  </label>;
  const select = (key: string, label: string, choices: string[]) => <label class="field"><span>{label}</span>
    <select value={String(config()[key] ?? choices[0])} onChange={event => {
      const value = event.currentTarget.value;
      if (key === 'preset' && isBeadsPreset(value)) setConfig(beadsPresetConfig(config(), value)); else set(key, value);
    }}>
      <For each={choices}>{choice => <option value={choice}>{choice}</option>}</For>
    </select>
  </label>;
  const textarea = (key: string, label: string) => <label class="field"><span>{label}</span>
    <textarea rows="5" value={String(config()[key] ?? '')} onInput={event => set(key, event.currentTarget.value)} />
  </label>;
  const preset = () => config().preset ?? 'custom';
  const unapproved = () => !!props.state?.codeHash && props.state.codeHash !== props.state.approvedHash;
  return <aside class="panel" aria-label="Selected cell settings">
    <div class="panel-heading"><div><span class="eyebrow">CELL SETTINGS</span><h2>{props.node.title}</h2></div><button onClick={props.close} aria-label="Close settings">×</button></div>
    <Status state={props.state} />
    <Show when={props.state?.error}><p class="error-box">{props.state?.error}</p></Show>
    <form onSubmit={event => { event.preventDefault(); void save(); }}>
      <label class="field"><span>Title</span><input value={title()} onInput={event => setTitle(event.currentTarget.value)} required /></label>
      <Show when={props.node.kind === 'git'}>
        {field('repo', 'Repository path', 'text', 'Absolute path to a local clone')}
        {field('branch', 'Branch', 'text', 'Leave empty to detect the default branch')}
        {field('intervalSec', 'Poll interval (seconds)', 'number')}
        <label class="toggle"><input type="checkbox" checked={Boolean(config().fetch)} onChange={event => set('fetch', event.currentTarget.checked)} />Fetch before polling</label>
      </Show>
      <Show when={props.node.kind === 'beads'}><BeadsFields source config={config()} set={set} /></Show>
      <Show when={props.node.kind === 'exec'}>
        {select('preset', 'Preset', ['custom', 'claude', 'codex', 'bd-comment', 'bd-create'])}
        <Show when={preset() === 'custom'}>{textarea('command', 'Command')}</Show>
        <Show when={isBeadsPreset(preset())}><BeadsFields config={config()} set={set} /></Show>
        <Show when={preset() === 'claude' || preset() === 'codex'}>
          {textarea('prompt', 'Prompt')}
          {field('model', 'Model', 'text', 'Leave empty for the CLI default')}
        </Show>
        <Show when={preset() === 'claude'}>
          {field('maxTurns', 'Maximum turns', 'number')}
          {field('maxBudgetUsd', 'Maximum budget (USD)', 'number')}
          {select('tools', 'Tools', ['readonly', 'none'])}
        </Show>
        <Show when={preset() === 'codex'}>{select('effort', 'Effort', ['low', 'medium', 'high'])}<p class="muted small">Sandbox: read-only</p></Show>
        <p class="hint">Templates accept <code>{'{{port}}'}</code>, <code>{'{{port.old}}'}</code>, <code>{'{{port.new}}'}</code>, <code>{'{{port.repo}}'}</code>. Replace “port” with an input name. Missing values become empty strings.</p>
        <Show when={!isBeadsPreset(preset())}>{field('cwd', 'Working directory', 'text', 'Leave empty for the server default')}</Show>
      </Show>
      <Show when={props.node.kind === 'program'}>
        {textarea('prompt', 'Program prompt')}
        <label class="field"><span>Inputs</span><input value={Array.isArray(config().inputs) ? (config().inputs as string[]).join(', ') : ''}
          onChange={event => set('inputs', event.currentTarget.value.split(',').map(port => port.trim()).filter(Boolean))} />
          <small>Comma-separated names, for example: in, change</small></label>
        {select('generator', 'Generator', ['claude', 'codex'])}
      </Show>
      <Show when={props.node.kind === 'judge'}><JudgeSettings config={config()} ports={props.node.ports} set={set} /></Show>
      <Show when={props.node.kind === 'program' || props.node.kind === 'exec' || props.node.kind === 'judge'}>
        {field('timeoutSec', 'Timeout (seconds)', 'number')}
        <label class="toggle"><input type="checkbox" checked={Boolean(config().auto)} onChange={event => set('auto', event.currentTarget.checked)} />Run automatically when inputs change</label>
      </Show>
      <button class="primary save" type="submit" disabled={saving()}>{saving() ? 'Saving…' : 'Save settings'}</button>
    </form>
    <Show when={props.node.kind === 'program'}>
      <section><h3>Generated program</h3><p class="muted small">New code runs in a sandboxed preview. The live cell keeps its promoted version until you promote a replacement.</p>
        <button disabled={saving() || props.state?.status === 'generating'} onClick={async () => { if (await save()) await props.action(`${base()}/generate`, 'POST'); }}>Save &amp; Generate</button>
        <label class="field"><span>Python source · read-only</span><textarea class="code" rows="12" readonly value={props.state?.code ?? ''} placeholder="Generate a program to review its source here." /></label>
        <Show when={props.state?.preview}>{preview => <div class="output-comparison">
          <div><h4>Live output</h4><pre>{props.state?.output?.new ?? 'No output yet'}</pre></div>
          <div><h4>Preview (sandboxed, not sent downstream)</h4><p class={`preview-status ${preview().status}`}>{preview().status}</p>
            <pre>{preview().output?.new ?? 'No preview output yet'}</pre>
            <Show when={preview().error}><p class="error-box">{preview().error}</p></Show>
          </div>
        </div>}</Show>
        <Show when={unapproved()}><button class="approve" disabled={promotionAction(props.state) === 'disabled'} onClick={event => {
          event.currentTarget.focus();
          const path = `${base()}/approve`;
          const hash = props.state?.codeHash;
          const promote = () => props.action(path, 'POST', { hash });
          if (promotionAction(props.state) === 'confirm') {
            setConfirmation({ message: 'This sandboxed preview failed. Promote this version anyway?', label: 'Promote anyway', run: promote });
          } else void promote();
        }}>Promote this version</button></Show>
        <Show when={props.state?.codeHash && !unapproved()}><p class="approved">Current code approved</p></Show>
      </section>
    </Show>
    <section><h3>Runs <span class="muted">({props.state?.runCount ?? 0})</span></h3>
      <Show when={!runs.loading} fallback={<p class="muted small">Loading runs…</p>}>
        <Show when={runs()?.length} fallback={<p class="muted small">No runs yet.</p>}>
          <div class="run-list"><For each={runs()}>{run => <button class={runId() === run.runId ? 'active' : ''} onClick={() => setRunId(run.runId)}>
            <span>{new Date(run.startedAt).toLocaleString()}</span><small>{run.durationMs} ms · {run.outcome ?? `exit ${run.exitCode}`}{run.costUsd != null ? ` · $${run.costUsd.toFixed(4)}` : ''}</small>
          </button>}</For></div>
        </Show>
      </Show>
      <Show when={detail.loading}><p class="muted small">Loading run…</p></Show>
      <Show when={detail()}>{run => <div class="run-detail">
        <h4>Run detail</h4><dl><dt>Run</dt><dd>{run().runId}</dd><dt>Argv</dt><dd><pre>{JSON.stringify(run().argv, null, 2)}</pre></dd>
          <dt>Working directory</dt><dd>{run().cwd || 'Default'}</dd><dt>Exit code</dt><dd>{run().exitCode}</dd>
          <dt>Outcome</dt><dd>{run().outcome ?? (run().exitCode === 0 ? 'completed' : 'failed')}</dd>
          <dt>Duration</dt><dd>{run().durationMs} ms</dd><dt>Cost</dt><dd>{run().costUsd == null ? 'Not reported' : `$${run().costUsd}`}</dd></dl>
        <Show when={run().error}><p class="error-box">{run().error}</p></Show>
        <Show when={run().judgment}><h4>Judgment</h4><pre>{JSON.stringify(run().judgment, null, 2)}</pre></Show>
        <h4>Inputs</h4><pre>{JSON.stringify(run().inputs, null, 2)}</pre>
        <h4>Stdout</h4><pre>{run().stdout || '(empty)'}</pre><h4>Stderr</h4><pre>{run().stderr || '(empty)'}</pre>
      </div>}</Show>
    </section>
    <Show when={confirmation()} keyed>{pending => <ConfirmationDialog message={pending.message} label={pending.label}
      cancel={() => setConfirmation(undefined)} accept={() => { setConfirmation(undefined); void pending.run(); }} />}</Show>
    <button class="danger" onClick={event => { event.currentTarget.focus(); props.remove(); }}>Delete cell…</button>
  </aside>;
}
