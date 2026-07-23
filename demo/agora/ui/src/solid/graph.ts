import { createSignal, batch } from 'solid-js';
import { createStore, produce } from 'solid-js/store';
import { GraphStore } from '../sync/store';
import { History } from '../sync/history';
import { SseClient } from '../sync/sse';
import type { ConnState } from '../sync/sse';
import type { Delta, NodeRec, Ref } from '../api/types';
import type { DebateSort } from '../layout/debate';

export type Mode = 'debate' | 'map';

/** The pure store + history — source of truth for indexes, layout, sparkline.
 *  Read imperatively (non-reactively) from memos so credence ticks don't
 *  invalidate structure-keyed work. */
export const graph = new GraphStore();
export const hist = new History();
const sse = new SseClient();

/** Reactive mirror for component reads — fine-grained per node. */
const [nodes, setNodes] = createStore<Record<Ref, NodeRec>>({});
const [structuralVersion, setStructuralVersion] = createSignal(0);
const [conn, setConn] = createSignal<ConnState>('connecting');
const [ready, setReady] = createSignal(false);
const [selection, setSelection] = createSignal<Ref | null>(null);
const [mode, setMode] = createSignal<Mode>('debate');
const [focal, setFocal] = createSignal<Ref | null>(null);
const [debateSort, _setDebateSort] = createSignal<DebateSort>(
  (localStorage.getItem('agora.debateSort') as DebateSort) ?? 'link',
);
const [ticker, setTicker] = createSignal<{ ref: Ref; t: number; drift: number }[]>([]);
const [pulses, setPulses] = createStore<Record<Ref, number>>({});
const [toasts, setToasts] = createSignal<{ id: number; msg: string }[]>([]);
let toastId = 0;

export {
  nodes,
  structuralVersion,
  conn,
  ready,
  selection,
  setSelection,
  mode,
  setMode,
  focal,
  setFocal,
  debateSort,
  ticker,
  pulses,
  toasts,
};

/** Persisted so the chosen ranking survives a reload, like theme/motion. */
export function setDebateSort(s: DebateSort): void {
  localStorage.setItem('agora.debateSort', s);
  _setDebateSort(s);
}

/** A transient error/info toast (spec §7 command errors). */
export function notify(msg: string): void {
  const id = ++toastId;
  setToasts((t) => [...t, { id, msg }]);
  setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4000);
}

export function nodeOf(ref: Ref | null | undefined): NodeRec | undefined {
  return ref ? nodes[ref] : undefined;
}

sse.onState(setConn);

function applyDelta(delta: Delta): void {
  batch(() => {
    setNodes(
      produce((n) => {
        for (const r of delta.removed) delete n[r.ref];
        for (const r of delta.added) n[r.ref] = r;
        for (const c of delta.changed) n[c.next.ref] = c.next;
      }),
    );
    if (delta.structural) setStructuralVersion(graph.structuralVersion);
    const hot = hist.record(delta);
    if (hot.pulses.length) {
      setPulses(
        produce((p) => {
          for (const ref of hot.pulses) p[ref] = delta.t;
        }),
      );
    }
    if (hot.ticker.length) setTicker((prev) => [...hot.ticker, ...prev].slice(0, 50));
  });
}

/** Open the SSE stream. The first snapshot is the initial load. */
export function connect(): void {
  sse.start((dtos, resync) => {
    const delta = graph.applySnapshot(dtos, { resync, now: Date.now() });
    applyDelta(delta);
    if (!ready()) setReady(true);
  });
}
