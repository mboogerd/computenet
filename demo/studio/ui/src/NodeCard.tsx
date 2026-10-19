import { createEffect, createMemo, createSignal, For, Show } from 'solid-js';
import type { NodeState, StudioNode } from './types';
import { outputPreview, PORT_GAP, PORT_TOP } from './model';
import { JudgeCard } from './JudgeSettings';
import { nodePath } from './api';
import { portColor, requirementKind } from './portTypes';
import { BeadsCard } from './StudioBeads';

export type Action = (path: string, method?: string, body?: unknown) => Promise<boolean>;
export function Status(props: { state?: NodeState }) {
  return <span class={`status ${props.state?.status ?? 'idle'}`} title={props.state?.error ?? undefined}>
    <i />{props.state?.status ?? 'idle'}
  </span>;
}

export default function NodeCard(props: {
  workflowId: string;
  node: StudioNode;
  state?: NodeState;
  selected: boolean;
  x: number;
  y: number;
  target: string | null;
  validPort: (port: string) => boolean;
  wiring: boolean;
  select: () => void;
  move: (event: PointerEvent) => void;
  wire: (event: PointerEvent) => void;
  action: Action;
}) {
  const [text, setText] = createSignal('');
  const submittedText = createMemo(() => props.state?.text ?? '');
  createEffect(() => setText(submittedText()));
  const submit = () => props.action(`${nodePath(props.workflowId, props.node.id)}/submit`, 'POST', { text: text() });
  const run = () => props.action(`${nodePath(props.workflowId, props.node.id)}/run`, 'POST');
  const unapproved = () => props.node.kind === 'program' &&
    !props.state?.approvedHash;
  return <article class={`node ${props.selected ? 'selected' : ''} ${props.wiring ? 'wiring' : ''}`} style={{ left: `${props.x}px`, top: `${props.y}px` }}
    tabindex="0" onFocus={props.select} onPointerDown={event => {
      props.select();
      if (!(event.target as Element).closest('button, input, textarea, select')) event.currentTarget.focus({ preventScroll: true });
    }} aria-label={`${props.node.title} cell`}>
    <header class="node-header" onPointerDown={props.move}>
      <div class="node-heading"><strong>{props.node.title}</strong><span class="kind">{props.node.kind === 'exec' ? String(props.node.config.preset ?? 'custom') : props.node.kind}</span></div>
      <Status state={props.state} />
    </header>
    <div class="port-area" style={{ height: `${Math.max(1, props.node.ports.length) * PORT_GAP + 8}px` }}>
      <For each={props.node.ports}>{(port, index) => <div class="input-row" style={{ top: `${PORT_TOP + index() * PORT_GAP}px` }}>
        <button class={`port input ${props.wiring && props.validPort(port) ? 'valid' : ''} ${props.target === port ? 'target' : ''}`}
          style={{ background: portColor(requirementKind(props.node.types?.inputs[port])) }}
          data-node={props.node.id} data-port={port} title={`Connect to ${props.node.title}: ${port}`}
          aria-label={`Input ${port}`} onPointerDown={event => event.stopPropagation()} />
        <span>{port}</span>
      </div>}</For>
      <div class="output-row" style={{ top: `${PORT_TOP}px` }}><span>out</span>
        <button class="port output" style={{ background: portColor(props.node.types?.output.kind) }} title={`Output: ${props.node.types?.output.kind ?? 'any'}. Drag to an input to connect`} aria-label={`Wire output of ${props.node.title}`} onPointerDown={props.wire} />
      </div>
    </div>
    <div class="node-body">
      <Show when={props.node.kind === 'text'}>
        <label class="sr-only" for={`text-${props.node.id}`}>Text for {props.node.title}</label>
        <textarea id={`text-${props.node.id}`} rows="3" value={text()} placeholder="Write something to send…"
          onInput={event => setText(event.currentTarget.value)}
          onKeyDown={event => { if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); void submit(); } }} />
        <div class="actions"><button class="primary" onClick={submit}>Submit</button><button onClick={() => props.action(`${nodePath(props.workflowId, props.node.id)}/fire`, 'POST')}>Fire</button></div>
      </Show>
      <Show when={props.node.kind === 'git'}>
        <p class="repo" title={String(props.node.config.repo ?? '')}>{String(props.node.config.repo || 'Select cell to set repository')}</p>
        <div class="actions"><code>{props.state?.ref?.slice(0, 7) || 'No ref yet'}</code><button onClick={() => props.action(`${nodePath(props.workflowId, props.node.id)}/fire`, 'POST')}>Fire</button></div>
      </Show>
      <Show when={props.node.kind === 'beads'}><BeadsCard config={props.node.config} state={props.state}
        fire={() => props.action(`${nodePath(props.workflowId, props.node.id)}/fire`, 'POST')} /></Show>
      <Show when={props.node.kind === 'judge'}><JudgeCard config={props.node.config} state={props.state} /></Show>
      <Show when={props.node.kind === 'exec' || props.node.kind === 'program' || props.node.kind === 'judge'}>
        <div class="actions"><button class={props.state?.status === 'stale' ? 'stale-run' : 'primary'} disabled={unapproved()}
          title={unapproved() ? 'Generate and approve the code in the side panel first' : 'Run with current inputs'} onClick={run}>
          {props.state?.status === 'stale' ? 'Inputs changed — Run' : 'Run'}
        </button>
          <label class="toggle"><input type="checkbox" checked={Boolean(props.node.config.auto)} onChange={event => props.action(nodePath(props.workflowId, props.node.id), 'PATCH', { config: { auto: event.currentTarget.checked } })} />auto</label>
        </div>
        <Show when={unapproved()}><p class="muted small">Code requires review and approval.</p></Show>
      </Show>
      <Show when={props.state?.preview?.status === 'idle'}><span class="preview-badge">preview ready</span></Show>
      <Show when={props.state?.error}><p class="error-box">{props.state?.error}</p></Show>
      <div class="preview-label">LATEST OUTPUT</div>
      <pre class="output-preview">{outputPreview(props.state?.output?.new) || 'No output yet'}</pre>
    </div>
  </article>;
}
