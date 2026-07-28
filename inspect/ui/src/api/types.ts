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

// --- M1: cell detail + state (20-api-contract.md "CellDetail (M1)", "CellState (M1)") ----

export type Attention = 'focus' | 'idle' | null;

export interface LinkCounts {
  inbound: number;
  outbound: number;
  taps: number;
}

/** `GET /cell/{ref}` — Node plus attention + link counts. */
export interface CellDetail extends Node {
  attention: Attention;
  links: LinkCounts;
}

export interface Frontier {
  source: string;
  counter: number;
}

export type StateKind = 'view' | 'snapshot' | 'unavailable';

/** `GET /cell/{ref}/state`. */
export interface CellState {
  ref: Ref;
  frontier: Frontier | null;
  kind: StateKind;
  value: Value;
  staleMs: number;
}

// --- Value (contract "Value" section) ----------------------------------
//
// "generic JSON-ish encoding of cell state ... scalar | [Value] | {"k":
// Value} | {"$table": {"columns": [...], "rows": [[...]]}}" plus a
// truncation marker "appended when it does" and (per M1-BE's Implement §4)
// an "opaque" last-resort string. Kept as a loose structural type — the
// shape is inherently dynamic — with runtime guards below rather than an
// exhaustive discriminated union, matching the contract's own "JSON-ish"
// framing.
export type ValueScalar = string | number | boolean | null;

/** A `$table` row: either a plain array of per-column Values, or the
 *  tombstone-wrapper variant the ticket names ("tombstone-style
 *  strikethrough when a row object carries `"tombstoned": true`"). Neither
 *  the contract nor the M1-BE ticket spells out the wire shape for a
 *  tombstoned row precisely, so this is an FE-side assumption flagged for
 *  M1-EVAL's "reconcile fixtures to the server, not vice versa" step. */
export type TableRow = readonly Value[] | { readonly cells: readonly Value[]; readonly tombstoned: true };

export interface TableShape {
  columns: readonly string[];
  rows: readonly TableRow[];
}

export interface TruncatedMarker {
  total: number;
  shown: number;
}

export type Value =
  | ValueScalar
  | readonly Value[]
  | { readonly [key: string]: Value };

export function isPlainValueObject(v: Value): v is { readonly [key: string]: Value } {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Returns the `$table` payload when `v` carries one (it may also carry a
 *  sibling `$truncated` key — see {@link truncatedOf}). */
export function tableOf(v: Value): TableShape | undefined {
  if (!isPlainValueObject(v)) return undefined;
  const t = v['$table'];
  if (t === undefined) return undefined;
  return t as unknown as TableShape;
}

/** Returns the `$truncated` marker when `v` carries one — either alongside
 *  `$table` (a truncated table) or standalone (the whole value was replaced
 *  by the marker because of the response-size cap). */
export function truncatedOf(v: Value): TruncatedMarker | undefined {
  if (!isPlainValueObject(v)) return undefined;
  const t = v['$truncated'];
  if (t === undefined) return undefined;
  return t as unknown as TruncatedMarker;
}

/** Returns the opaque reflective-toString string when `v` is the M1-BE
 *  "safe reflective-toString last resort" shape. */
export function opaqueOf(v: Value): string | undefined {
  if (!isPlainValueObject(v)) return undefined;
  const o = v['opaque'];
  return typeof o === 'string' ? o : undefined;
}

export function isTombstoneRow(row: TableRow): row is { readonly cells: readonly Value[]; readonly tombstoned: true } {
  return !Array.isArray(row) && (row as { tombstoned?: unknown }).tombstoned === true;
}

export function rowCells(row: TableRow): readonly Value[] {
  return isTombstoneRow(row) ? row.cells : row;
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

/** `state.summary` — "only for cells with an active observe subscription"
 *  (contract). `cardinality` is a free-form label (e.g. "4 rows") or null. */
export interface StateSummaryPayload {
  ref: Ref;
  cardinality: string | null;
  frontier: Frontier | null;
  staleMs: number;
}

export interface StateSummaryEvent {
  seq: number;
  kind: 'state.summary';
  payload: StateSummaryPayload;
}

export interface HeartbeatEvent {
  seq: number;
  kind: 'heartbeat';
  payload: Record<string, never>;
}

/** The kinds this client understands — a clean discriminated union on `kind`. */
export type InspectEvent =
  | TopologyNodeEvent
  | TopologyLinkEvent
  | LifecycleEvent
  | StateSummaryEvent
  | HeartbeatEvent;

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
  'state.summary',
  'heartbeat',
]);
