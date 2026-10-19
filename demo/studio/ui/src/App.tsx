import { createEffect, createMemo, createSignal, For, on, onCleanup, onMount, Show } from 'solid-js';
import { nodePath, request, workflowPath } from './api';
import { emptyGraph, inputPoint, outputPoint, reduceFrame, wirePath, type Point } from './model';
import type { Config, Frame, Graph, Kind, StudioNode, Workflow } from './types';
import NodeCard, { type Action } from './NodeCard';
import Panel from './Panel';
import ConfirmationDialog, { type Confirmation } from './ConfirmationDialog';
import NewWorkflowForm from './NewWorkflowForm';
import { cellCount, resolveWorkflow, workflowHash } from './workflows';

type Gesture =
  | { type: 'pan'; start: Point; origin: Point }
  | { type: 'move'; id: string; start: Point; origin: Point }
  | { type: 'wire'; id: string };

export default function App() {
  const [workflows, setWorkflows] = createSignal<Workflow[]>([]);
  const [openId, setOpenId] = createSignal<string | null>(null);
  const [loading, setLoading] = createSignal(true);
  const [creatingWorkflow, setCreatingWorkflow] = createSignal(false);
  const [confirmation, setConfirmation] = createSignal<Confirmation>();
  let newWorkflowButton!: HTMLButtonElement;
  const closeWorkflowForm = () => { setCreatingWorkflow(false); newWorkflowButton.focus(); };
  const current = createMemo(() => workflows().find(workflow => workflow.id === openId()));
  const available = () => current()?.status === 'ok';
  // Count refreshes must not tear down and reopen the event stream.
  const sessionKey = createMemo(() => `${openId()}:${current()?.status}`);
  const base = () => workflowPath(openId()!);
  const [graph, setGraph] = createSignal<Graph>(emptyGraph());
  const [selected, setSelected] = createSignal<string | null>(null);
  const [selectedEdge, setSelectedEdge] = createSignal<string | null>(null);
  const [pan, setPan] = createSignal<Point>({ x: 80, y: 80 });
  const [zoom, setZoom] = createSignal(1);
  const [gesture, setGesture] = createSignal<Gesture | null>(null);
  const [moving, setMoving] = createSignal<({ id: string } & Point) | null>(null);
  const [wireEnd, setWireEnd] = createSignal<Point>({ x: 0, y: 0 });
  const [target, setTarget] = createSignal<{ id: string; port: string } | null>(null);
  const [connection, setConnection] = createSignal<'connecting' | 'connected' | 'disconnected'>('disconnected');
  const [toasts, setToasts] = createSignal<{ id: number; message: string }[]>([]);
  let viewport!: HTMLDivElement;
  let nextToast = 0;
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const toast = (message: string) => {
    const id = ++nextToast;
    setToasts(old => [...old, { id, message }]);
    const timer = setTimeout(() => { setToasts(old => old.filter(item => item.id !== id)); timers.delete(timer); }, 12000);
    timers.add(timer);
  };
  const navigate = (id: string) => { window.location.hash = workflowHash(id); setOpenId(id); };
  const fromHash = () => {
    const id = resolveWorkflow(window.location.hash, workflows());
    setOpenId(id);
    const hash = id ? workflowHash(id) : '';
    if (window.location.hash !== hash) history.replaceState(null, '', window.location.pathname + window.location.search + hash);
  };
  const refreshWorkflows = async () => {
    setWorkflows(await request<Workflow[]>('/api/workflows'));
    fromHash();
  };
  const createWorkflow = async (name: string) => {
    const workflow = await request<Workflow>('/api/workflows', 'POST', { name });
    setWorkflows(old => [...old, workflow]); navigate(workflow.id);
    closeWorkflowForm();
  };
  const removeWorkflow = () => {
    const workflow = current();
    if (!workflow) return;
    setConfirmation({
      message: `Remove “${workflow.name}”? This moves it to trash and stops its running cells.`,
      label: 'Move to trash',
      run: async () => {
        try { await request(workflowPath(workflow.id), 'DELETE'); await refreshWorkflows(); }
        catch (error) { toast(String(error instanceof Error ? error.message : error)); }
      },
    });
  };
  const action: Action = async (path, method = 'POST', body) => {
    try { if (!available()) return false; await request(path, method, body); return true; }
    catch (error) { toast(error instanceof Error ? error.message : String(error)); return false; }
  };
  const node = createMemo(() => graph().nodes.find(item => item.id === selected()));
  const nodeIds = createMemo(() => graph().nodes.map(item => item.id));
  const located = (item: StudioNode): StudioNode => {
    const position = moving();
    return position?.id === item.id ? { ...item, x: position.x, y: position.y } : item;
  };
  const byId = (id: string) => graph().nodes.find(item => item.id === id);
  const worldPoint = (event: { clientX: number; clientY: number }): Point => {
    const rect = viewport.getBoundingClientRect();
    return { x: (event.clientX - rect.left - pan().x) / zoom(), y: (event.clientY - rect.top - pan().y) / zoom() };
  };
  const validPort = (id: string, port: string) => {
    const current = gesture();
    return current?.type === 'wire' && current.id !== id && !graph().edges.some(edge => edge.to === id && edge.port === port);
  };
  const removeNode = () => {
    const item = node();
    if (!item) return;
    const path = nodePath(openId()!, item.id);
    setConfirmation({ message: `Delete “${item.title}” and its wires?`, label: 'Delete cell', run: () => action(path, 'DELETE') });
  };
  const removeEdge = async () => {
    const id = selectedEdge();
    if (id && await action(`${base()}/edges/${encodeURIComponent(id)}`, 'DELETE')) setSelectedEdge(null);
  };
  const add = (kind: Kind, preset?: string) => {
    const rect = viewport.getBoundingClientRect();
    const config: Config = preset ? { preset } : {};
    // Start roughly at the visible centre, independent of current pan/zoom.
    void action(`${base()}/nodes`, 'POST', {
      kind, config, x: (rect.width / 2 - pan().x) / zoom() - 130,
      y: (rect.height / 2 - pan().y) / zoom() - 100,
    });
  };
  const beginMove = (event: PointerEvent, item: StudioNode) => {
    if (event.button !== 0) return;
    event.preventDefault(); event.stopPropagation();
    (event.currentTarget as HTMLElement).closest<HTMLElement>('.node')?.focus({ preventScroll: true });
    setSelected(item.id); setSelectedEdge(null);
    setGesture({ type: 'move', id: item.id, start: { x: event.clientX, y: event.clientY }, origin: { x: item.x, y: item.y } });
    setMoving({ id: item.id, x: item.x, y: item.y });
  };
  const beginWire = (event: PointerEvent, id: string) => {
    if (event.button !== 0) return;
    event.preventDefault(); event.stopPropagation();
    setSelectedEdge(null); setWireEnd(worldPoint(event)); setGesture({ type: 'wire', id });
  };
  const beginPan = (event: PointerEvent) => {
    if (event.button !== 0 || (event.target as Element).closest('.node, .wire, .canvas-controls, .edge-controls')) return;
    event.preventDefault(); setSelected(null); setSelectedEdge(null);
    setGesture({ type: 'pan', start: { x: event.clientX, y: event.clientY }, origin: pan() });
  };
  const move = (event: PointerEvent) => {
    const current = gesture();
    if (!current) return;
    if (current.type === 'pan') setPan({ x: current.origin.x + event.clientX - current.start.x, y: current.origin.y + event.clientY - current.start.y });
    if (current.type === 'move') setMoving({ id: current.id, x: current.origin.x + (event.clientX - current.start.x) / zoom(), y: current.origin.y + (event.clientY - current.start.y) / zoom() });
    if (current.type === 'wire') {
      setWireEnd(worldPoint(event));
      const input = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('.port.input');
      const id = input?.dataset.node;
      const port = input?.dataset.port;
      setTarget(id && port && validPort(id, port) ? { id, port } : null);
    }
  };
  const cancel = () => { setGesture(null); setMoving(null); setTarget(null); };
  const drop = (event: PointerEvent) => {
    const current = gesture();
    if (current?.type === 'move') {
      const position = moving();
      if (position && (position.x !== current.origin.x || position.y !== current.origin.y)) {
        void action(nodePath(openId()!, current.id), 'PATCH', { x: position.x, y: position.y });
      }
    }
    if (current?.type === 'wire') {
      const input = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('.port.input');
      const id = input?.dataset.node;
      const port = input?.dataset.port;
      if (id && port && validPort(id, port)) void action(`${base()}/edges`, 'POST', { from: current.id, to: id, port });
    }
    cancel();
  };
  const wheel = (event: WheelEvent) => {
    if ((event.target as Element).closest('textarea, input, select')) return;
    event.preventDefault();
    if (gesture()) return;
    const point = worldPoint(event);
    const rect = viewport.getBoundingClientRect();
    const next = Math.max(0.4, Math.min(2, zoom() * Math.exp(-event.deltaY * 0.001)));
    setPan({ x: event.clientX - rect.left - point.x * next, y: event.clientY - rect.top - point.y * next });
    setZoom(next);
  };
  const key = (event: KeyboardEvent) => {
    if (confirmation() || creatingWorkflow()) return;
    if ((event.target as Element).closest('input, textarea, select, button, [contenteditable="true"]')) return;
    if (event.key === 'Escape') { cancel(); setSelected(null); setSelectedEdge(null); }
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      if (selectedEdge()) void removeEdge(); else void removeNode();
    }
  };
  createEffect(() => {
    const current = graph();
    if (selected() && !current.nodes.some(item => item.id === selected())) setSelected(null);
    if (selectedEdge() && !current.edges.some(item => item.id === selectedEdge())) setSelectedEdge(null);
  });
  createEffect(on(sessionKey, () => {
    const workflow = current();
    setGraph(emptyGraph()); setSelected(null); setSelectedEdge(null); cancel();
    setPan({ x: 80, y: 80 }); setZoom(1);
    setConnection('disconnected');
    if (!workflow || workflow.status === 'failed') return;
    const path = workflowPath(workflow.id);
    setConnection('connecting');
    let receivedFrame = false;
    let disposed = false;
    const events = new EventSource(`${path}/events`);
    events.onopen = () => { if (!disposed) setConnection('connected'); };
    events.onerror = () => { if (!disposed) setConnection('disconnected'); };
    events.onmessage = event => {
      if (disposed) return;
      try {
        const frame = JSON.parse(event.data) as Frame;
        if (!['snapshot', 'graph', 'state'].includes(frame.type)) throw new Error('Unknown studio event');
        receivedFrame = true;
        setGraph(old => reduceFrame(old, frame));
        if (frame.type !== 'state') setWorkflows(old => old.map(item => item.id === workflow.id ? { ...item, nodeCount: frame.nodes.length } : item));
      } catch (error) { toast(`Could not read a studio event: ${error instanceof Error ? error.message : error}`); }
    };
    void request<Graph>(`${path}/graph`).then(initial => { if (!disposed && !receivedFrame) setGraph(initial); }).catch(error => { if (!disposed) toast(error.message); });
    onCleanup(() => { disposed = true; events.close(); });
  }));
  onMount(() => {
    void refreshWorkflows().catch(error => toast(error.message)).finally(() => setLoading(false));
    window.addEventListener('hashchange', fromHash);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', drop);
    window.addEventListener('pointercancel', cancel);
    window.addEventListener('blur', cancel);
    window.addEventListener('keydown', key);
    viewport.addEventListener('wheel', wheel, { passive: false });
    onCleanup(() => {
      window.removeEventListener('hashchange', fromHash);
      window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', drop);
      window.removeEventListener('pointercancel', cancel); window.removeEventListener('blur', cancel);
      window.removeEventListener('keydown', key); viewport.removeEventListener('wheel', wheel);
      timers.forEach(clearTimeout);
    });
  });
  return <div class="app">
    <header class="toolbar"><div class="brand"><span class="brand-mark">⌘</span><div><h1>Studio</h1><small>COMPUTENET</small></div></div>
      <div class="workflow-switcher">
        <select aria-label="Open workflow" title={current()?.error ?? undefined} onFocus={() => { void refreshWorkflows().catch(error => toast(error.message)); }} onChange={event => navigate(event.currentTarget.value)}>
          <Show when={!current()}><option value="" selected={!openId()} disabled>{loading() ? 'Loading workflows…' : 'No workflows'}</option></Show>
          {/* List refreshes replace options even when openId is unchanged. */}
          <For each={workflows()}>{workflow => <option value={workflow.id} selected={workflow.id === openId()} title={workflow.error ?? undefined}>{workflow.name} · {cellCount(workflow.nodeCount)}{workflow.status === 'failed' ? ' · failed' : ''}</option>}</For>
        </select>
        <div class="workflow-create">
          <button ref={newWorkflowButton} aria-expanded={creatingWorkflow()} aria-controls="new-workflow-form" onClick={() => setCreatingWorkflow(value => !value)}>New workflow…</button>
          <Show when={creatingWorkflow()}><NewWorkflowForm create={createWorkflow} cancel={closeWorkflowForm} /></Show>
        </div>
        <button disabled={!current()} onClick={event => { event.currentTarget.focus(); removeWorkflow(); }}>Remove…</button>
      </div>
      <Show when={available()}><div class="add-tools" role="group" aria-label="Add a cell"><span class="muted small">Add cell</span>
        <button onClick={() => add('text')}>+ Text</button><button onClick={() => add('git')}>+ Git repo</button>
        <button onClick={() => add('exec', 'custom')}>+ Command</button><button onClick={() => add('exec', 'claude')}>+ Claude</button>
        <button onClick={() => add('exec', 'codex')}>+ Codex</button><button onClick={() => add('program')}>+ Programmable</button>
        <button onClick={() => add('judge')}>+ Jev</button>
      </div></Show>
      <Show when={available()}><span class={`connection ${connection()}`}><i />{connection()}</span></Show>
    </header>
    <Show when={available() && connection() === 'disconnected'}><div class="disconnect" role="status">Disconnected — reconnecting to the studio…</div></Show>
    <main class="workspace">
      <div class={`viewport ${gesture() ? 'gesturing' : ''}`} ref={viewport} onPointerDown={beginPan} aria-label="Dataflow graph canvas">
        <Show when={!current() && !loading()}><div class="empty"><h2>Your workflows.</h2><p>Create an isolated space for your cells.</p><button class="primary" onPointerDown={event => event.stopPropagation()} onClick={() => setCreatingWorkflow(true)}>Create your first workflow</button></div></Show>
        <Show when={current()?.status === 'failed'}><div class="empty"><h2>Could not load workflow.</h2><p class="error-box">{current()?.error}</p><p>Choose another workflow or remove this one.</p></div></Show>
        <Show when={available() && !graph().nodes.length}><div class="empty"><span class="eyebrow">YOUR DATA, IN MOTION</span><h2>Start with a cell.</h2><p>Add text or a repository, then wire its output<br />into a command, an agent, or a program.</p><button class="primary" onPointerDown={event => event.stopPropagation()} onClick={() => add('text')}>+ Add a text cell</button></div></Show>
        <div class="world" style={{ transform: `translate(${pan().x}px, ${pan().y}px) scale(${zoom()})` }}>
          <svg class="wires" aria-label="Connections">
            <For each={graph().edges}>{edge => {
              const path = () => {
                const from = byId(edge.from); const to = byId(edge.to);
                return from && to ? wirePath(outputPoint(located(from)), inputPoint(located(to), edge.port)) : '';
              };
              return <g class={`wire ${selectedEdge() === edge.id ? 'selected' : ''}`}>
                <path class="wire-line" d={path()} />
                <path class="wire-hit" d={path()} role="button" tabindex="0" aria-label={`Select wire ${edge.from} to ${edge.to}, input ${edge.port}`}
                  onPointerDown={event => { event.stopPropagation(); setSelected(null); setSelectedEdge(edge.id); }}
                  onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelected(null); setSelectedEdge(edge.id); } if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); event.stopPropagation(); setSelectedEdge(edge.id); void removeEdge(); } }} />
              </g>;
            }}</For>
            <Show when={gesture()?.type === 'wire'}><path class="wire-line live" d={(() => { const current = gesture(); const from = current?.type === 'wire' ? byId(current.id) : undefined; return from ? wirePath(outputPoint(located(from)), wireEnd()) : ''; })()} /></Show>
          </svg>
          <For each={nodeIds()}>{id => <NodeCard workflowId={openId()!} node={byId(id)!} state={graph().state[id]} selected={selected() === id}
            x={located(byId(id)!).x} y={located(byId(id)!).y} target={target()?.id === id ? target()!.port : null}
            validPort={port => validPort(id, port)} wiring={gesture()?.type === 'wire'}
            select={() => { setSelected(id); setSelectedEdge(null); }} move={event => beginMove(event, byId(id)!)} wire={event => beginWire(event, id)} action={action} />}</For>
        </div>
        <Show when={selectedEdge()}><div class="edge-controls"><span>Wire selected</span><button onClick={removeEdge} aria-label="Delete selected wire">× Remove</button></div></Show>
        <div class="canvas-controls"><span>{Math.round(zoom() * 100)}%</span><button onClick={() => { setZoom(1); setPan({ x: 80, y: 80 }); }}>Reset view</button></div>
        <div class="canvas-hint">Drag background to pan · Scroll to zoom · Drag output to input</div>
      </div>
      <Show when={node()?.id} keyed>{id => <Panel workflowId={openId()!} node={node()!} state={graph().state[id]} action={action} toast={toast}
        close={() => setSelected(null)} remove={removeNode} />}</Show>
    </main>
    <Show when={confirmation()} keyed>{pending => <ConfirmationDialog message={pending.message} label={pending.label}
      cancel={() => setConfirmation(undefined)} accept={() => { setConfirmation(undefined); void pending.run(); }} />}</Show>
    <div class="toasts" aria-live="polite"><For each={toasts()}>{item => <div class="toast" role="alert"><span>{item.message}</span><button onClick={() => setToasts(old => old.filter(entry => entry.id !== item.id))} aria-label="Dismiss message">×</button></div>}</For></div>
  </div>;
}
