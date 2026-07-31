# V4-COMPONENT — two disconnected components that share a logical uuid are two graphs, not one

**Status**: Specified — not-started
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** unscheduled — queued by checkpoint C-replan-2 for the next planning
session · **Branches:** ticket/v4-component

## Context

ComputeNet's inspector has no `Graph` entity to report: a "graph" is the
emergent connected component of the cells one `LocationRegistry` publishes,
joined by the links its `TopologyIndex` records. `ComponentIndex`
(`inspect/src/main/kotlin/civictech/inspect/Graphs.kt:60`) computes that
partition and names each component `g-` + the lexicographically-minimum member
**uuid**.

Using the uuid rather than the encoded `CellRef` is deliberate and documented
(`Graphs.kt:34-36`): two instances of one logical cell share a uuid, so the id
does not flip when a minimum member is *replaced* by a later instance of
itself.

`V4-PILOT` (wave 10, merged `9dd03a8`) drove the repository's first
same-logical-id replicated graph across a real socket — two JVMs, one inspector
each, `civictech.cell.replication.Replication` gossiping `CellRef(SHARED_ID, 0)`
against `CellRef(SHARED_ID, 1)`. Its findings are
`doc/demo-shopping-replica-pilot.md` §"What we observed". That run established
what the replacement-case reasoning never considered: **under peering, two
instances of one logical id coexist**, and each side mirrors the other's refs,
so both instances are vertices in the same `ComponentIndex`.

Checkpoint C-replan-2 sized the four defects the pilot recorded. This ticket is
**D1** (`doc/demo-shopping-replica-pilot.md:457-479`), the one with a complete
failure-mode analysis in hand. D3 (graph-id instability under peer churn) is
deliberately **not** in scope — see below.

## Problem

`sweep()` is correct: over the pilot's captured 30-node/21-edge topology it
produces **11** flood-fill components. `components()` returns **10** map
entries. Two genuinely disconnected components are merged, and the merge
happens strictly after the sweep.

Concretely, from the pilot's own captured payloads
(`doc/demo-shopping-replica-pilot.md:289-319`): the delivered-watermark
companions `Replication.trackDeliveries` mints per replicated cell —
`98ebe0fa-…:0` on peer A and `98ebe0fa-…:1` on peer B — are published one per
JVM and mirrored to the other. Their own gossip is a `streamTo`
(`kernel/src/main/kotlin/civictech/cell/replication/Replication.kt:442`), which
records no `TopologyLink`, and the demo does not `declareLink` it. So `sweep()`
yields **two disconnected singleton components with no edge incident to
either** — and `GET /api/inspect/graphs` bills them as one card:

```json
{"id":"g-98ebe0fa-aff5-3add-9e66-afd614f2e2c4","name":null,"cells":2,
 "hosts":1,"nets":2,"health":{…},"lifecycle":"hot"}
```

A two-cell graph spanning two network hosts, for two cells connected by
nothing. The navigator thumbnail shows two isolated dots and no line; opening
the card draws two boxes at opposite ends of an empty canvas.

Two sites produce this, and **both must be fixed** — repairing either alone
leaves the defect reachable:

1. **`components()` groups by the id string, not by the flood-fill.**
   `Graphs.kt:124-132` reads `partition()` and buckets every cell by the id
   string it was assigned. `sweep()` (`:147-171`) assigns each component
   `idOf(members)` (`:180`) independently, so two disjoint components whose
   minimum members share a uuid receive the *same string*, and the bucketing
   silently unions them.

2. **`addCell`'s singleton fast path can re-create the collision without a
   sweep.** `Graphs.kt:79-90`: a published cell that no recorded link mentions
   is assigned `idOf(listOf(ref))` directly into the memoized partition,
   *without* invalidating it. So publishing `98ebe0fa:1` while `98ebe0fa:0` is
   already assigned `g-98ebe0fa-…` writes the same id for the new singleton and
   never sweeps. This is exactly the observed path — mirrored refs arrive
   through the registry's publish hook — and it would defeat a fix applied only
   to `sweep()`.

The harm is not cosmetic. `Node.graph` is stamped from `componentOf`
(`inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt:336`) and
`GET /api/inspect/topology?graph=<id>` filters on it
(`InspectorServer.kt:555`), so the id is the navigator's *partition key*. Two
unconnected cells sharing one are not two rows in a list — they are one
undividable selection.

## Solution direction

The decided design. `sweep()` already holds the truth; the fix is to stop
losing it, at the smallest possible cost to the ids every existing deployment
and test already sees.

**Rule — collision-conditional instance qualification.**

- A component's *candidate* id stays exactly what it is today: `g-` + the
  lexicographically-minimum member **uuid**.
- If exactly one component in the partition holds a candidate, it **keeps it
  unchanged**. Every topology in which one logical uuid has at most one live
  instance — which is every non-replicated graph, and every replicated graph
  whose instances are actually connected — produces byte-identical ids to
  today. This is a load-bearing property of the rule, not a nicety: it is what
  keeps the change from churning ids for every existing client.
- If two or more components share a candidate, **each** of them is qualified by
  its own minimum member's instance id: `g-<minUuid>:<minInstanceId>`.

That qualification is unique by construction, and the implementer should record
the argument in the KDoc: every colliding component contains at least one ref
with the shared minimum uuid; the components' vertex sets are disjoint; so if
two of them had the same minimum instance id for that uuid they would share a
vertex, which a flood-fill partition cannot do.

Note the design consequences, and document them rather than hiding them:

- **All colliding components are qualified, symmetrically** — the component
  anchored at instance `0` does not get to keep the bare id. Asymmetry would
  buy the local side one less id change across a peer disconnect, at the price
  of a card whose id silently claims to be "the" graph for that uuid. Honesty
  wins here; the id is already documented as unstable across merge and split
  (`Graphs.kt:37-45`), and a peer arriving or leaving is a genuine split.
- **Separator `:`**, matching `Node.ref`'s own `"<uuid>:<instanceId>"`
  encoding (`inspect/.../Dto.kt:33`), so the two read alike. Do **not** use
  `#` — graph ids travel in the `?graph=` query of `GET /topology`, and `#`
  would be read as a fragment delimiter by a client assembling that URL.

**Both sites, per the Problem section.** `sweep()` gains the collision pass;
`addCell`'s fast path must fall back to `assignment = null` when the singleton
candidate id is already present as a value in the memoized assignment (or when
the ref's logical uuid is already assigned). Prefer the cheapest correct test
that keeps `addCell` O(1) amortized for the common case — a maintained
`uuid -> component-count` or `uuid -> assigned` side index is acceptable and is
in your latitude; a linear scan of the assignment on every publish is not,
because `Graphs.kt:44-52` exists precisely to keep building an N-cell graph
O(N) rather than O(N²).

**Latitude** (yours): the internal representation of the collision pass (a
two-pass sweep, a candidate→components multimap, or a post-pass rewrite); the
side-index shape backing `addCell`'s new check; helper naming; whether the
qualified form is produced by an overload of `idOf` or by a separate function.

**NOT in scope, and not to be solved on the way past:**

- **D3 — the named graph's id changing on every peer connect/disconnect.**
  The pilot found the deciding minimum uuid twice belonged to a randomly minted
  cell in the *other* JVM (`doc/demo-shopping-replica-pilot.md:498-511`). That
  is the min-uuid heuristic being member-derived at all, and C-replan-2 ruled
  it out of a `Graphs.kt` patch: a correct answer needs a **declared** boundary
  identity that is not derived from its own membership (the "membranes as
  naming boundary" question, Linear MRB-156). Do not attempt it, and do not
  change the id's derivation for the non-colliding case.
- **A logical-id / instance-multiplicity grouping affordance** ("instance N of
  M", pairing `shared` with `shared@dialer` in the UI). Deferred at C-replan-2
  with its own trigger. This ticket does not add a contract field.
- **D2** (the error lane's `parked` rows never firing for a partitioned replica
  mesh) and **D4** (the stale breadcrumb) — different files, different owners.
- **`../97-inspector-plan/20-api-contract.md` is orchestrator-owned.** The
  contract's graph-id line (`:326`, *"heuristic: lexicographically-min cell
  uuid in the component"*) becomes incomplete with this change. **Propose exact
  replacement wording in your report; do not edit the file.**
- No `kernel/**`, no `wire/**`, no `demo/**`, no `concord/**`, no
  `inspect/ui/**`.

### Test requirement

Extend `inspect/src/test/kotlin/civictech/inspect/InspectorGraphsTest.kt`,
which already fixes cell uuids rather than randomizing them *"so
'lexicographically-min member' is a fact the assertions can state, not a coin
flip they have to recompute"* (`:30-32`) — keep that discipline and fix the
instance ids too.

- **The defect, from the sweep side.** Two published cells sharing one uuid,
  differing only in `instanceId`, with **no link between them**:
  `GET /api/inspect/graphs` returns **two** cards of one cell each, with
  distinct ids, and `GET /api/inspect/topology?graph=<each id>` returns exactly
  that one cell. Assert the two ids' exact strings, not merely that they
  differ.
- **The defect, from the `addCell` fast path.** The same two cells, but
  published such that the partition is already memoized when the second
  arrives — read `GET /graphs` (or any endpoint that forces `partition()`)
  between the two publishes. Same assertions. Without the fast-path repair this
  case fails while the previous one passes; state that in the test's comment so
  a future reader does not delete it as a duplicate.
- **Case (a) control — coexistence that is genuinely connected must NOT be
  qualified.** Two instances of one uuid joined by a link produce **one** card
  whose id is the unqualified `g-<uuid>`. This is the pilot's data-replica pair
  (`doc/demo-shopping-replica-pilot.md:291-294`) and it is the case the fix
  must not touch.
- **No-collision regression.** Every existing assertion in
  `InspectorGraphsTest` that names a `g-…` id must still pass **unchanged**. Do
  not relax an existing expected id to accommodate the new rule; if one moves,
  the rule is wrong.
- **Split/merge behaviour of the qualified pair.** Removing one of the two
  colliding singletons (a `removeCell`, the peer-retraction shape) leaves the
  survivor with the **unqualified** id — the collision is gone, so the
  qualification is too — and `graphs.changed` fires. Pin this explicitly: it is
  the ticket's one deliberate new source of id churn and a reader must find it
  asserted rather than discover it in production.

Bounded waits only (`civictech.testkit.awaitUntil`, the register the file
already uses). Ports: the file binds `port = 0`; keep it that way.

## Files expected to touch

- **Modified**: `inspect/src/main/kotlin/civictech/inspect/Graphs.kt` —
  `sweep()`, `idOf`, `addCell`, and the class KDoc's §Identity paragraph
  (`:24-36`), which must state the coexistence case alongside the replacement
  case it already states.
- **Modified**: `inspect/src/test/kotlin/civictech/inspect/InspectorGraphsTest.kt`
- **Possibly modified**: `inspect/src/main/kotlin/civictech/inspect/Dto.kt:789`
  — the one-line `GraphSummary.id` KDoc restating the heuristic. KDoc only.
- This ticket's `**Status**:` line.

Nothing else. No generated/build output in the diff.

## Read first

- `doc/demo-shopping-replica-pilot.md` — §"What we observed" findings 1 and 3
  (`:213-243`, `:281-331`) and defect **D1** (`:457-479`) in full. Finding 3 is
  the derivation you are fixing; it distinguishes the real-edge case from the
  false-positive case in one payload and you must preserve that distinction.
- `inspect/src/main/kotlin/civictech/inspect/Graphs.kt` — in full. The class
  KDoc's §Identity (`:24-36`) and §Update discipline (`:38-57`) are the two
  constraints the fix sits between: id stability under replacement, and
  `addCell` staying O(1).
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt:218-270` —
  `components()`, `nameGraph`/`nameOf`'s min-uuid tie-break (which must keep
  working per component), and `publishGraphChanges`' full-membership compare.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:449`, `:555` —
  the two consumers: `GET /graphs` and the `?graph=` topology filter.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:70-80`, `:320-335`
  — `Node.graph` and `GraphSummary` as the contract states them today. You
  propose wording; you do not edit.
- `doc/spec/90-roadmap/98-inspector-v4-plan/00-orchestration.md` — the
  C-replan-2 section: this ticket's verdict, and the three things it explicitly
  deferred instead.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `kernel/**`, `wire/**`, `demo/**`, `concord/**`,
`inspect/ui/**`, `doc/spec/90-roadmap/97-inspector-plan/**`, any plan document
other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] Two published cells sharing a logical uuid with no path between them
      appear as two `GraphSummary` cards with distinct ids, and each id
      selects exactly its own cell through `GET /topology?graph=`.
- [ ] The same holds when the second cell is published against an already
      memoized partition (the `addCell` fast-path case).
- [ ] Two instances of one uuid that *are* connected still produce one card
      with the unqualified `g-<uuid>` id.
- [ ] For every topology in which no two components share a minimum uuid,
      the ids produced are byte-identical to before this change; no existing
      `InspectorGraphsTest` id expectation is edited.
- [ ] `addCell` remains O(1) amortised — no scan of the assignment per
      publish; the mechanism is stated in its KDoc.
- [ ] Retracting one of a colliding pair returns the survivor to the
      unqualified id, and the test asserts it.
- [ ] `Graphs.kt`'s §Identity KDoc states the coexistence case and the
      uniqueness argument for the qualifier.
- [ ] `./gradlew :inspect:test` green; `./gradlew test` green.
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :inspect:test --tests 'civictech.inspect.InspectorGraphsTest'
./gradlew :inspect:test
./gradlew test
git status --porcelain     # only the claimed files
```

## Report on completion

- The collision pass's shape and the `addCell` side-index you chose, in three
  sentences, with the uniqueness argument stated once.
- **Proposed replacement wording for `20-api-contract.md:326`** (the
  `GraphSummary.id` heuristic comment) and for `Node.graph`'s note if it needs
  one — verbatim, ready to paste. The contract file is orchestrator-owned.
- Whether any existing test's expected id moved. If one did, say which and
  why — that is a signal the rule is wrong, not a routine adjustment.
- Whether the `?graph=` filter needed any change, or fell out of the id fix.
- Anything specified here you could not do, and why.
