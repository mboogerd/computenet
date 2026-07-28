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
 *  tombstone-wrapper variant the ticket named ("tombstone-style
 *  strikethrough when a row object carries `"tombstoned": true`").
 *
 *  M1-EVAL resolution: verified live against skillmatch's `candSkills`
 *  `SetCell` that the real `ValueEncoder` (`inspect/src/.../ValueEncoder.kt`)
 *  never emits this shape — `orSetMembership` excludes tombstoned elements
 *  from the encoded state entirely ("live membership, tombstones excluded"),
 *  so a removed element just disappears from `rows`, full stop. The
 *  `{cells, tombstoned}` wrapper this type still allows, and the
 *  `isTombstoneRow`/`rowCells` guards below, are kept as harmless forward
 *  compatibility (dead code today, never constructed by the server) rather
 *  than ripped out, in case that encoding decision changes later. */
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

export interface OpaqueMarker {
  type: string;
  text: string;
}

/** Returns the opaque reflective-toString marker when `v` is the M1-BE
 *  "safe reflective-toString last resort" shape. M1-EVAL correction: the real
 *  server (`ValueEncoder.kt`'s `OPAQUE`/`opaque()`) emits the reserved key
 *  `$opaque` (not `opaque`) holding `{type, text}` (not a bare string) —
 *  verified against a live skillmatch read. The original FE guess used the
 *  wrong key and shape; fixed here to match the server, per this ticket's
 *  "reconcile fixtures to the server, not vice versa". */
export function opaqueOf(v: Value): OpaqueMarker | undefined {
  if (!isPlainValueObject(v)) return undefined;
  const o = v['$opaque'];
  if (!isPlainValueObject(o)) return undefined;
  const type = o['type'];
  const text = o['text'];
  return typeof type === 'string' && typeof text === 'string' ? { type, text } : undefined;
}

export function isTombstoneRow(row: TableRow): row is { readonly cells: readonly Value[]; readonly tombstoned: true } {
  return !Array.isArray(row) && (row as { tombstoned?: unknown }).tombstoned === true;
}

export function rowCells(row: TableRow): readonly Value[] {
  return isTombstoneRow(row) ? row.cells : row;
}

// --- M3: flow (20-api-contract.md "flow.rates" SSE event) --------------
//
// Unlike the other M-tickets there is no `GET` snapshot endpoint for flow —
// the contract defines only the 1 Hz SSE batch. The client therefore has no
// "resync" for flow the way topology/errors do; a batch is self-contained
// (every currently-active edge, "edges with rate 0 omitted") so a client
// that missed a batch or two just sees fewer/staler edges until the next one
// arrives — see `sync/flowStore.ts`'s decay-on-silence logic.

/** One edge's reading inside a `flow.rates` batch. `rate`'s unit is not
 *  specified by the contract beyond "per-edge rates over the window"; M3-BE
 *  aggregates over a 1000 ms window (`window` below), so this client treats
 *  `rate` as already normalized to messages/second — see `util/flow.ts`. */
export interface FlowRateEdge {
  id: string;
  rate: number;
  lastWave: Frontier | null;
  hop: number | null;
}

/** `flow.rates` payload — "1 Hz batch; edges with rate 0 omitted" (contract). */
export interface FlowRatesPayload {
  window: number;
  edges: readonly FlowRateEdge[];
}

export interface FlowRatesEvent {
  seq: number;
  kind: 'flow.rates';
  payload: FlowRatesPayload;
}

// --- M4: graphs + search (20-api-contract.md "GraphList (M4)", "SearchResult
// (M4/M5)") ---------------------------------------------------------------

export interface GraphHealth {
  deadLetters: number;
  parked: number;
  restarts: number;
}

/** "cold" is live as of M5-COLD: a component every one of whose cells is
 *  parked — individually suspended, or on a drained host (server `Heat`).
 *  Lowercase here, unlike {@link Lifecycle}'s uppercase `"HOT" | "SUSPENDED"`
 *  on a Node; both are as the contract specifies. */
export type GraphLifecycle = 'hot' | 'cold';

export interface GraphSummary {
  id: string;
  /** From an optional host-side naming annotation; null = unnamed — the UI
   *  renders the id and must never invent a name (10-target-v3.md "Known
   *  kernel gaps": "do NOT invent names"). */
  name: string | null;
  cells: number;
  hosts: number;
  nets: number;
  health: GraphHealth;
  lifecycle: GraphLifecycle;
}

/** `GET /api/inspect/graphs`. */
export interface GraphList {
  graphs: readonly GraphSummary[];
}

export type SearchMode = 'name' | 'problems' | 'data';

export interface SearchHit {
  graph: string;
  ref: Ref | null;
  label: string;
  detail: string;
}

export interface SearchCost {
  cellsQueried: number;
  coldSkipped: number;
}

/** `GET /api/inspect/search?mode={name|problems|data}&q=`. `cost` is
 *  data-mode-only (M5); always null for name/problems (contract). */
export interface SearchResult {
  mode: SearchMode;
  hits: readonly SearchHit[];
  cost: SearchCost | null;
}

export interface GraphsChangedEvent {
  seq: number;
  kind: 'graphs.changed';
  payload: Record<string, never>;
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

// --- M2: errors (20-api-contract.md "ErrorSnapshot (M2)", "error.* events") ----

export interface DeadLetterEntry {
  ref: Ref;
  /** null for a drop (unknown target) — no thrown exception to name (server
   *  `Errors.kt`: `cause?.javaClass?.simpleName`). */
  cause: string | null;
  description: string;
  wave: Frontier | null;
  atMs: number;
}

/** `error.parked` — "send on change; `count: 0` clears" (contract): a
 *  `count: 0` row is a clear signal for the (ref, port) pair, not a
 *  zero-count row to keep around. */
export interface ParkedEntry {
  ref: Ref;
  port: string;
  count: number;
  oldestMs: number;
}

export interface RestartEntry {
  ref: Ref;
  generation: number;
  atMs: number;
}

export interface ErrorCounters {
  deadLetters: number;
  parked: number;
  restarts: number;
  drainedOnTeardown: number;
}

/** `GET /api/inspect/errors`. */
export interface ErrorSnapshot {
  counters: ErrorCounters;
  deadLetters: readonly DeadLetterEntry[];
  parked: readonly ParkedEntry[];
  restarts: readonly RestartEntry[];
}

export interface ErrorDeadLetterEvent {
  seq: number;
  kind: 'error.deadLetter';
  payload: DeadLetterEntry;
}

export interface ErrorParkedEvent {
  seq: number;
  kind: 'error.parked';
  payload: ParkedEntry;
}

export interface ErrorRestartEvent {
  seq: number;
  kind: 'error.restart';
  payload: RestartEntry;
}

/** The kinds this client understands — a clean discriminated union on `kind`. */
export type InspectEvent =
  | TopologyNodeEvent
  | TopologyLinkEvent
  | LifecycleEvent
  | StateSummaryEvent
  | HeartbeatEvent
  | ErrorDeadLetterEvent
  | ErrorParkedEvent
  | ErrorRestartEvent
  | FlowRatesEvent
  | GraphsChangedEvent;

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
  'error.deadLetter',
  'error.parked',
  'error.restart',
  'flow.rates',
  'graphs.changed',
]);
