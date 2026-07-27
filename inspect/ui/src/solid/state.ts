import { batch, createSignal } from 'solid-js';
import { createStore, produce } from 'solid-js/store';
import type { Edge, EdgeRemoval, InspectEvent, Ref } from '../api/types';
import { TopologyClient, type ConnState } from '../sync/client';
import type { EdgeRec, NodeRec } from '../sync/records';
import { TopologyStore } from '../sync/store';

/** The pure store — source of truth for the layout memo and for adjacency.
 *  Read imperatively (non-reactively), exactly like agora/ui's `graph`
 *  export: reading it inside a Solid memo that depends only on
 *  `structuralVersion()` means a value-only change never invalidates
 *  layout. */
export const store = new TopologyStore();

/** Reactive mirror for component reads — fine-grained per node/edge, kept in
 *  sync with `store` below via `mirror()`. Two plain Maps (not Solid
 *  signals) remember what was mirrored last, so `mirror()` can tell added
 *  from removed from untouched-identity (== unchanged content, thanks to
 *  sync/diff.ts) without the pure store needing to hand back an explicit
 *  delta object — same effect as agora/ui's GraphStore.applySnapshot
 *  returning a Delta, computed here instead since TopologyStore's own
 *  mutators (add/remove/lifecycle) don't each shape one. */
const [nodes, setNodes] = createStore<Record<Ref, NodeRec>>({});
const [edges, setEdges] = createStore<Record<string, EdgeRec>>({});
const [structuralVersion, setStructuralVersion] = createSignal(0);
const [conn, setConn] = createSignal<ConnState>('connecting');
const [ready, setReady] = createSignal(false);
const [selection, setSelection] = createSignal<Ref | null>(null);

export { conn, edges, nodes, ready, selection, setSelection, structuralVersion };

let prevNodes: ReadonlyMap<Ref, NodeRec> = new Map();
let prevEdges: ReadonlyMap<string, EdgeRec> = new Map();

function mirror(): void {
  batch(() => {
    const nextNodes = store.nodes;
    setNodes(
      produce((n) => {
        for (const ref of prevNodes.keys()) if (!nextNodes.has(ref)) delete n[ref];
        for (const [ref, rec] of nextNodes) if (prevNodes.get(ref) !== rec) n[ref] = rec;
      }),
    );
    prevNodes = nextNodes;

    const nextEdges = store.edges;
    setEdges(
      produce((e) => {
        for (const id of prevEdges.keys()) if (!nextEdges.has(id)) delete e[id];
        for (const [id, rec] of nextEdges) if (prevEdges.get(id) !== rec) e[id] = rec;
      }),
    );
    prevEdges = nextEdges;

    if (structuralVersion() !== store.structuralVersion) setStructuralVersion(store.structuralVersion);
    if (selection() && !store.get(selection()!)) setSelection(null); // the selected node vanished
  });
}

function applyEvent(event: InspectEvent): void {
  switch (event.kind) {
    case 'topology.node':
      if (event.payload.op === 'added') store.applyNodeAdded(event.payload.node);
      else store.applyNodeRemoved(event.payload.node.ref);
      break;
    case 'topology.link':
      if (event.payload.op === 'added') store.applyEdgeAdded(event.payload.edge as Edge);
      else store.applyEdgeRemoved(event.payload.edge as EdgeRemoval);
      break;
    case 'lifecycle':
      store.applyLifecycle(event.payload.ref, event.payload.lifecycle, event.payload.generation);
      break;
    default:
      break; // heartbeat, and any later-milestone kind: no local state to update yet
  }
}

const client = new TopologyClient({
  onSnapshot: (snapshot) => {
    store.applySnapshot(snapshot);
    mirror();
    if (!ready()) setReady(true);
  },
  onEvent: (event) => {
    applyEvent(event);
    mirror();
  },
  onState: setConn,
});

/** Start the sync layer. Call once, on app mount. */
export function connect(): void {
  void client.start();
}
