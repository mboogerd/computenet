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
  net: string | null; // network host / peer id — "local" for a single-JVM component; a peer-announced cell's connection-derived label otherwise (M5-NET)
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

export type StateKind = 'view' | 'snapshot' | 'page' | 'unavailable';

/** V1C-BE: where a `page`/`snapshot` read's bytes came from. Non-null exactly
 *  when `kind` is `'page'` or `'snapshot'`; null for `'view'` (a fold
 *  materialized in the inspector's own heap is neither a live cell read nor a
 *  checkpoint) and for `'unavailable'`. Never default a missing/null value to
 *  `'live'` — that would erase the one distinction this field exists to make
 *  (V1C-BE ticket Part 2, V1C-FE ticket §0). */
export type StateProvenance = 'live' | 'liveSuspended' | 'checkpoint';

/** V1C-BE: present iff `kind === 'unavailable'` — WHICH nothing there is to
 *  report. `terminated`/`readFailed` are not in the V1C-FE ticket's copy of
 *  the contract block; they are in the SHIPPED one (`Dto.kt`'s
 *  `CellState.TERMINATED`/`READ_FAILED`), added at C10 against the merged
 *  backend rather than the draft. */
export type Unreadable =
  | 'migrating'
  | 'remote'
  | 'notStateful'
  | 'unanswered'
  | 'terminated'
  | 'readFailed'
  | 'unknown';

/** V1C-BE: one bounded page of a walk over a cell's state — present iff
 *  `kind === 'page'`. See `CellState.page`'s field-level comments for the
 *  wire meaning of each of these; `sync/statePages.ts` is what accumulates
 *  them across a multi-page walk. */
export interface StatePage {
  /** OPAQUE. Echo verbatim as `?cursor=` for the next page; never parsed,
   *  never constructed, never reused. `null` means the walk is complete. */
  cursor: string | null;
  /** The limit actually applied — the server clamps `?limit=`, so this is how
   *  a client learns its request was reduced. Read it back; never assume the
   *  limit you sent was honoured verbatim. */
  limit: number;
  /** Entries on THIS page, as the cell counted them. Every one is rendered in
   *  `value` — the server never serves a page whose entries a byte budget
   *  cut. `cursor !== null` is the only signal more state exists; a
   *  `$truncated` marker inside `value` means one VALUE was abbreviated, not
   *  that entries went missing. */
  entries: number;
  /** Entries on this page whose value is an Owned/Leased payload — the kernel
   *  pages a presence descriptor for those, never a copy. `> 0` means this
   *  page is deliberately, permanently incomplete for those entries: no
   *  further page will ever fill them in. */
  exclusivesElided: number;
  /** The ONLY consistency claim a paged read makes, verified server-side:
   *  `true` — every page so far carried the same tag frontier (always true on
   *  page 1); `false` — the fold changed mid-walk, so the union is a smeared
   *  read (whole entries, never torn, never duplicated, may miss a mid-walk
   *  removal or include a mid-walk addition); `null` — the cell reports no
   *  tag frontier, so neither claim can be checked — render it as neither. */
  walkStable: boolean | null;
  /** The kernel's own declared weakenings for this page, forwarded rather
   *  than inferred and accumulated across the walk server-side, so a client
   *  joining at page 4 still learns that this walk's cursor is positional.
   *  `'staleFrontier'` (this page carries the walk's OPENING frontier, which
   *  is why its `walkStable` is `null`) and `'positionalCursor'` (no element
   *  identity to key a cursor by, so "every surviving entry appears" and "no
   *  entry twice" both weaken to best-effort) are the two the shipped server
   *  mints; the list is open, so render an unrecognized one rather than
   *  dropping it. Absent on an older server — treat as `[]`, never required.
   *
   *  NOT in the V1C-FE ticket's copy of the contract block: added at C10
   *  against the SHIPPED `StatePageView.caveats`. */
  caveats?: readonly string[];
  /** Cell-level state that is not a per-entry row and rides EVERY page —
   *  `SetCell`'s tag `counter`, `ShardCell`'s `interest`/`assignedEpoch`,
   *  `OperatorPaging`'s `mintCounter`/`lanes`. Each value is an ordinary
   *  contract `Value`. The server surfaces these rather than dropping them
   *  precisely so a client reading page 4 of a shard walk can tell whether
   *  the walk straddled a repartition — so this client renders them.
   *
   *  NOT in the V1C-FE ticket's copy of the contract block: added at C10
   *  against the SHIPPED `StatePageView.attributes`. `{}` when there are
   *  none; absent on an older server. */
  attributes?: { readonly [key: string]: Value };
}

/** `GET /cell/{ref}/state`. `provenance`/`page`/`unreadable` are V1C-BE
 *  additions, all optional-tolerant on read: an older server that omits them
 *  entirely must decode the same as it always has (10-design-notes.md
 *  binding constraint 8 — additive evolution only, unknown/absent fields
 *  never required). */
export interface CellState {
  ref: Ref;
  frontier: Frontier | null;
  kind: StateKind;
  value: Value;
  staleMs: number;
  /** Non-null exactly for `'page'` | `'snapshot'`. */
  provenance?: StateProvenance | null;
  /** Non-null exactly for `'page'`. */
  page?: StatePage | null;
  /** Non-null exactly for `'unavailable'`. */
  unreadable?: Unreadable | null;
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

/** `civictech.cell.BoundarySeam.name` — which `BoundaryPolicy` seam refused
 *  a crossing (spec 40/43 "three seams, one per dispatch class"). */
export type BoundarySeam = 'ADMISSION' | 'LINK_AUTHORITY' | 'PROTOCOL_AUTHORITY' | 'DISCLOSURE' | 'INTEGRITY';

/** `civictech.cell.DenialReason.name` — why a crossing was refused. Deliberately
 *  closed and named per seam, not free text: a denial record is meant to be
 *  machine-readable. There is no reason constant for an attention clamp — a
 *  clamp is not a denial and produces no record (30/34 decision 6).
 *
 *  Mirrors `civictech.cell.DenialReason`
 *  (`kernel/src/main/kotlin/civictech/cell/BoundaryDenials.kt`) constant for
 *  constant — see `DENIAL_REASONS` below, which
 *  `test/denial-reason-sync.test.ts` (computenet-ssa.7) checks against that
 *  file in both directions so the two cannot drift apart unnoticed again. */
export const DENIAL_REASONS = [
  'NOT_ADMITTED',
  'LINK_REFUSED',
  'MIN_AUTH',
  'RATE',
  'DISCLOSURE_DENIED',
  'DISCLOSURE_PROJECTED_AWAY',
  'UNSIGNED',
  'BAD_SIGNATURE',
  'REPLAY',
  /** Seam 1 hello: the side's policy is `RequireAuthenticated` and the hello
   *  did not carry the key material or signature that policy demands — a
   *  legacy name-only `HELLO`, or a `HELLO2` never followed by a `PROOF`
   *  (DSC1 `[DSC1-HELLO-08]`/`[DSC1-HELLO-09]`). Machine-distinguishable
   *  from `NOT_ADMITTED`: that means "I know who you claim to be and you
   *  are not welcome"; this means "you never proved who you claim to be". */
  'AUTH_REQUIRED',
  /** Seam 1 hello: the peer id a `HELLO2` claimed is not the id derived from
   *  the public key that same `HELLO2` presented (DSC1 `[DSC1-HELLO-06]`) —
   *  an impersonation attempt. **Not a synonym for `BAD_SIGNATURE`**: the
   *  proof may verify perfectly under the presented key. What fails here is
   *  the *binding* between key and claimed name, not the signature itself —
   *  conflating the two loses the fact that this was an impersonation
   *  attempt rather than a forged or corrupt signature. */
  'ID_MISMATCH',
  /** Seam 1 hello: a structurally invalid authenticated hello — wrong token
   *  count on a `HELLO2` line, an undecodable base64url field, a claimed id
   *  outside the fixed key-derived form, or a protocol message out of order
   *  (a `PROOF` before any `HELLO2`). Exists so a malformed hello is never
   *  silently absorbed into a peer name. */
  'MALFORMED_HELLO',
  /** Seam 1 announcement: the announcement's `notAfter` is in the past
   *  relative to the receiver's clock, less the receiver's configured skew
   *  allowance (DSC1 `[DSC1-ANN-07]`). Distinct from `REPLAY`: `REPLAY` means
   *  this receiver has already accepted an announcement at least as new from
   *  this identity (a statement about this receiver's history); `EXPIRED`
   *  means the signer itself declared the announcement stale, and holds even
   *  for a counter this receiver has never seen. */
  'EXPIRED',
] as const;

export type DenialReason = (typeof DENIAL_REASONS)[number];

/** computenet-usd.7 / computenet-4ixu — `DeadLetterRow.denial`: the
 *  structural discriminator that lets a client tell a `BoundaryPolicy`
 *  refusal apart from a plain host-level drop, both of which are
 *  `cause: null` and, before this field, shared one free-text
 *  `description` a client had to parse to tell apart. Never a fault: a
 *  denial is not classified as a cell failure and mints no wave. */
export interface BoundaryDenialSummary {
  seam: BoundarySeam;
  reason: DenialReason;
  /** The membrane `Exposure.externalName` the refused crossing was addressed to. */
  exposure: string;
  /** The `PeerId` this refusal is attributed to — the convention differs by
   *  seam (see server `BoundaryDenial.principal` KDoc); null does not always
   *  mean the same thing. */
  principal: string | null;
  /** What was refused, named per seam (protocol id, or contract/method); null
   *  where the seam has no such subject (e.g. admission). */
  subject: string | null;
  /** Free-text specifics for the audit trail (observed counter, offending
   *  auth level, refusing policy name). */
  detail: string | null;
}

export interface DeadLetterEntry {
  ref: Ref;
  /** null for a drop (unknown target) — no thrown exception to name (server
   *  `Errors.kt`: `cause?.javaClass?.simpleName`). */
  cause: string | null;
  description: string;
  wave: Frontier | null;
  atMs: number;
  /** V3: the failing call, when the drop happened during an invocation.
   *  `null` for a plain host-level drop (no invocation to describe) — an
   *  older server that omits this field entirely reads the same way (see
   *  `sync/errorStore.ts` and `components/DetailPanel.tsx`, both defensive
   *  on read). */
  invocation: {
    port: string;
    type: 'PORT_API' | 'PORT_MANAGEMENT' | 'PORT_PROTOCOL';
    method: string;
    parameterTypes: readonly string[];
    argCount: number;
    hop: number | null;
  } | null;
  /** V3: what the kernel's dead-letter sanitization did to each argument —
   *  `[]` when there was no invocation or no args. An `Owned` arrives
   *  `frozen`, a `Leased` arrives released-and-redacted (`redacted`) — the
   *  exclusive-payload cases this field exists to surface; `borrowed` /
   *  `owned` / `leased` / `plain` describe the argument's ownership kind
   *  without a sanitization action having been needed. */
  disposition: readonly {
    index: number;
    ownership: 'frozen' | 'redacted' | 'borrowed' | 'owned' | 'leased' | 'plain';
    reason: string | null;
  }[];
  /** computenet-4ixu: non-null exactly when this row reports a
   *  `BoundaryPolicy` refusal (server `DeadLetter.denial`), never a fault —
   *  never both this and `cause`. Optional-tolerant on read like
   *  `provenance`/`page`/`unreadable` on `CellState`: an older server that
   *  omits the key entirely decodes the same as it always has (additive
   *  evolution, `10-design-notes.md` binding constraint 8). */
  denial?: BoundaryDenialSummary | null;
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
  /** V3: a **time-window correlation** with the dead letter that preceded
   *  this generation bump, not a kernel-reported restart cause. `null` when
   *  no such correlated dead letter was found. */
  cause: string | null;
  /** V3: when the correlated cause (above) was observed; `null` iff `cause`
   *  is `null`. */
  causeAtMs: number | null;
  /** V3: when this generation's re-baseline was observed. `null` means
   *  **not observed** — never "did not happen" — a supervision timeline
   *  reading this field must omit the step rather than render a negative
   *  claim. */
  reBaselineAtMs: number | null;
}

export type WaveHealthKind = 'frontierLag' | 'stalledWave';

/** A heuristic wave-health diagnostic — NOT kernel-grade detection. A lagging
 *  frontier is often legitimate (an absorbed delta, a filtering operator, a
 *  fresh emission epoch after a restart), so `heuristic` is always true and the
 *  UI must present these as "worth a look", never as a defect claim. */
export interface WaveHealthEntry {
  /** Stable per (kind, edge, ref): the open row, its updates and its clear all
   *  carry this id. The store keys on it. */
  id: string;
  kind: WaveHealthKind;
  /** `'cleared'` removes the row with this `id` — the same discipline
   *  `ParkedEntry`'s `count: 0` already established. */
  state: 'open' | 'cleared';
  ref: Ref;
  /** The `Edge.id` whose last observed wave the comparison used. */
  edge: string;
  wave: Frontier | null;
  frontier: Frontier | null;
  /** Counter delta; null when the two stamps do not share a source. */
  lagWaves: number | null;
  heldMs: number;
  atMs: number;
  heuristic: boolean;
  description: string;
}

export interface ErrorCounters {
  deadLetters: number;
  parked: number;
  restarts: number;
  drainedOnTeardown: number;
  /** V3: the count of currently **open** wave-health rows — a gauge that
   *  falls as conditions resolve, like `parked`, unlike the monotonic
   *  `deadLetters`/`restarts`. */
  waveHealth: number;
}

/** `GET /api/inspect/errors`. */
export interface ErrorSnapshot {
  counters: ErrorCounters;
  deadLetters: readonly DeadLetterEntry[];
  parked: readonly ParkedEntry[];
  restarts: readonly RestartEntry[];
  /** V3: open rows only, never a history log. */
  waveHealth: readonly WaveHealthEntry[];
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

export interface ErrorWaveHealthEvent {
  seq: number;
  kind: 'error.waveHealth';
  payload: WaveHealthEntry;
}

// --- V2: activity (98-inspector-v4-plan/10-design-notes.md "V2 — activity";
// V2-FE ticket "The contract shapes you code against", frozen by V2-BE in
// this same wave) ---------------------------------------------------------

/** Kind meanings (V2-FE ticket): `passivated` = the cell was suspended
 *  (explicitly or by supervision); `activated` = it resumed; `drained` = its
 *  host finished draining; `woken` = a user pressed the wake button and this
 *  cell was in the blast radius; `restarted` = supervision restarted it
 *  (`generation` names the new generation). A wake legitimately produces
 *  both a `woken` and an `activated` entry for the same ref — both are
 *  rendered, never de-duplicated. */
export type ActivityKind = 'activated' | 'passivated' | 'drained' | 'woken' | 'restarted';

/** One activity-log row. `generation` is present only on a `restarted`
 *  entry — absent otherwise (contract). */
export interface ActivityEntry {
  ref: Ref;
  kind: ActivityKind;
  atMs: number;
  generation?: number;
}

/** `GET /api/inspect/activity` — "catch-up, oldest first, at most 200
 *  entries" (contract). */
export interface ActivitySnapshot {
  entries: readonly ActivityEntry[];
}

export interface ActivityEvent {
  seq: number;
  kind: 'activity';
  payload: ActivityEntry;
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
  | ErrorWaveHealthEvent
  | FlowRatesEvent
  | GraphsChangedEvent
  | ActivityEvent;

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
  'error.waveHealth',
  'flow.rates',
  'graphs.changed',
  'activity',
]);
