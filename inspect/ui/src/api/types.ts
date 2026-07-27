// The wire contract — doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md.
// Neither side edits that document unilaterally; if these types need to
// diverge from it, that is a contract-change request to the orchestrator,
// not a unilateral fix here.

export type Ref = string; // "<uuid>:<instanceId>"
export type Dir = 'IN' | 'OUT';
export type Color = 'PURE' | 'BLOCKING' | 'SUSPENDING' | null;
export type Lifecycle = 'HOT' | 'SUSPENDED';
export type EdgeRole = 'CONSUME' | 'OBSERVE';

export interface Port {
  name: string;
  dir: Dir;
  contractFqn: string;
}

export interface Node {
  ref: Ref;
  name: string | null;
  typeFqn: string;
  color: Color;
  manifests: readonly string[];
  ports: readonly Port[];
  host: string | null; // process host (ManagedHost) name
  net: string | null; // network host / peer id — "local" until M5
  lifecycle: Lifecycle;
  generation: number;
  graph: string | null; // component id — null until M4
}

export interface EdgeEndpoint {
  ref: Ref;
  port: string;
}

export interface Edge {
  id: string;
  from: EdgeEndpoint;
  to: EdgeEndpoint;
  role: EdgeRole;
  fused: boolean | null; // best-effort; null when unknown (M0 may emit null)
}

/** The wire shape for a removed-edge delta: only `id` is guaranteed present
 *  (contract: "removed: only id required"). */
export type EdgeRemoval = Pick<Edge, 'id'> & Partial<Omit<Edge, 'id'>>;

export interface TopologySnapshot {
  seq: number;
  nodes: readonly Node[];
  edges: readonly Edge[];
}

// --- SSE events -------------------------------------------------------

export interface TopologyNodeEvent {
  seq: number;
  kind: 'topology.node';
  payload: { op: 'added' | 'removed'; node: Node };
}

export interface TopologyLinkEvent {
  seq: number;
  kind: 'topology.link';
  payload: { op: 'added' | 'removed'; edge: Edge | EdgeRemoval };
}

export interface LifecycleEvent {
  seq: number;
  kind: 'lifecycle';
  payload: { ref: Ref; lifecycle: Lifecycle; generation: number };
}

export interface HeartbeatEvent {
  seq: number;
  kind: 'heartbeat';
  payload: Record<string, never>;
}

/** The kinds M0 understands — a clean discriminated union on `kind`. */
export type InspectEvent = TopologyNodeEvent | TopologyLinkEvent | LifecycleEvent | HeartbeatEvent;

/** The wire-level shape before we know whether `kind` is one M0 understands.
 *  Deliberately NOT a member of the `InspectEvent` union: adding a
 *  `{ kind: string; payload: unknown }` catch-all there would defeat
 *  discriminated-union narrowing on every other member too (TS can no
 *  longer exclude it from a `case 'topology.node':` branch, since a bare
 *  `string` is compatible with any literal). sync/client.ts parses into this
 *  type first, checks `KNOWN_EVENT_KINDS`, and only then casts to
 *  `InspectEvent` for a later-milestone kind (state.summary, error.*,
 *  flow.rates, graphs.changed, ...) — additive evolution: the client ignores
 *  what it doesn't recognize rather than erroring. */
export interface RawEvent {
  seq: number;
  kind: string;
  payload: unknown;
}

export const KNOWN_EVENT_KINDS = new Set<string>([
  'topology.node',
  'topology.link',
  'lifecycle',
  'heartbeat',
]);
