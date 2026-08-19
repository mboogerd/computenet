# Ready-work coverage statement

Epic computenet-98u (BDS3) requires every clause of beads' `BuildReadyWorkWhere`
to be enumerated as modelled or excluded-with-reason, never silently defaulted
or hidden behind a passing test. This file is that enumeration. It is what
computenet-98u.1's differential harness (against `bd ready --json`) and any
later feature must cite instead of re-deriving the predicate from source.

**Pinned commit** (cited per computenet-98u.1.1's breakdown; fetched and read
2026-08-18):

```
https://github.com/gastownhall/beads/blob/21822ad725d617e31c7d17bba573531100d2df12/internal/storage/sqlbuild/ready.go
```

The repository is `gastownhall/beads`; its Go source still imports under the
upstream path `github.com/steveyegge/beads` (a fork whose module path was
never rewritten) — noted so the two names in citations below are not read as
an error.

Files read at the same commit:

- `internal/storage/sqlbuild/ready.go` — `BuildReadyWorkWhere`,
  `ReadyWorkExcludeTypes`, `BuildReadyWorkOrder`.
- `internal/storage/domain/infra_types.go` — `DefaultInfraTypes()`.
- `internal/storage/dolt/store.go:3705-3714` — `RecomputeBlockedAfterMerge`
  (delegates to `recomputeBlockedAfterPull` -> `recomputeBlockedTx` ->
  `issueops.RecomputeIsBlockedAfterMergeInTx`).
- `internal/storage/issueops/blocked_merge.go` — `RecomputeIsBlockedAfterMergeInTx`
  and its scoped/full-pass helpers, which bottom out in `RecomputeIsBlockedInTx`.
- `internal/storage/issueops/blocked_state.go` — `RecomputeIsBlockedInTx`,
  `markBlockedTemplateForIssues`, `unmarkBlockedTemplateForIssues`,
  `waitsForGateBlockedSQL`: the actual SQL that maintains `is_blocked`.
- `internal/storage/issueops/blocked.go` — `GetBlockedIssuesInTx` (the
  `bd blocked` read path; corroborates the dangling-target conclusion below
  from a second, independent piece of source).

## 1. `BuildReadyWorkWhere`, clause by clause

Every `whereClauses = append(...)` in `ready.go`'s `BuildReadyWorkWhere`,
in source order.

| # | Clause (source) | Status | Notes |
|---|---|---|---|
| 1 | `filter.Status != ""` -> `status = ?` | **Excluded** | Caller filter (a single explicit status), not the default set. Out of scope per computenet-98u.1's "default call semantics only" decision. |
| 2 | `len(filter.Statuses) > 0` -> `status IN (?)` | **Excluded** | Caller filter, same reason as #1. |
| 3 | default -> `status IN ('open', 'in_progress')` | **Modelled** | `ReadyPredicate.DEFAULT_READY_STATUSES`. |
| 4 | `(pinned = 0 OR pinned IS NULL)` | **Modelled** | `ReadyPredicate.isTruthyBoolean(fields, "pinned")`, negated. Absent key treated as falsy, matching `... IS NULL`. |
| 5 | `is_blocked = 0` | **Modelled, but not by this file** | `ReadyPredicate.isReady`'s `blocked: Boolean` parameter is this clause. The value must come from a live derivation over the dependency edge set (computenet-98u.1.2), never from the mirror's own `is_blocked` field — see §2 below for the source-derived semantics that derivation must implement. This predicate has no `edges` access at all, so it structurally cannot read `is_blocked` by accident. |
| 6 | `!filter.IncludeEphemeral` -> `(ephemeral = 0 OR ephemeral IS NULL)` | **Modelled** | Same shape as #4, for `ephemeral`. The `filter.IncludeEphemeral = true` variant (this clause omitted entirely) is a caller filter — excluded, default call semantics only. |
| 7 | `filter.Priority != nil` -> `priority = ?` | **Excluded** | Caller filter. |
| 8a | `filter.Type != ""` -> `issue_type = ?` | **Excluded** | Caller filter (a single explicit positive type), not the default exclusion set. |
| 8b | else -> `issue_type NOT IN (ReadyWorkExcludeTypes(filter.ExcludeTypes))` | **Modelled** | `ReadyPredicate.EXCLUDED_TYPES`, with no caller `ExcludeTypes` extras (default call semantics). See §1.1 for the exact set. |
| 9a | `filter.Unassigned` -> `(assignee IS NULL OR assignee = '')` | **Excluded** | Caller filter. |
| 9b | `filter.Assignee != nil` -> `assignee = ?` | **Excluded** | Caller filter. |
| 10a | `!filter.IncludeDeferred` -> `(defer_until IS NULL OR defer_until <= UTC_TIMESTAMP())` | **Excluded** | Epic-mandated exclusion: `defer_until` vs `UTC_TIMESTAMP()` is a wall-clock comparison this predicate (evaluated at arbitrary times, not query time) cannot express as a stable membership test. |
| 10b | ...`id NOT IN (DeferredChildIDs)` | **Excluded** | Epic-mandated exclusion: deferred-parent children. Requires a precomputed transitive id set (`ReadyWorkWhereInputs.DeferredChildIDs`) built by a separate query the mirror does not run. |
| 11 | `filter.Labels` (AND, one `id IN (...)` per label) | **Excluded** | Caller filter (label scoping). Labels are not part of this module's field/edge model at all. |
| 12 | `filter.LabelsAny` (OR-set) | **Excluded** | Caller filter, same reason as #11. |
| 13 | `filter.ExcludeLabels` | **Excluded** | Caller filter, same reason as #11. |
| 14 | `filter.LabelPattern` (glob) | **Excluded** | Caller filter, same reason as #11. |
| 15 | `filter.LabelRegex` | **Excluded** | Caller filter, same reason as #11. |
| 16 | `filter.ParentID != nil` -> transitive descendant scoping | **Excluded** | Epic-mandated exclusion: parent-descendant sets. Requires `ReadyWorkWhereInputs.ParentDescendantIDs`, a precomputed recursive-CTE result (`GetDescendantIDsInTx`) this predicate has no access to. |
| 17 | `filter.MoleculeID != ""` -> molecule scoping | **Excluded** | Caller filter (molecule-id scoping). `molecule` is already one of the excluded issue types (#8b), so the molecule/wisp fan-out mechanism this clause scopes into is out of this demo's model entirely (epic §3: never in this lane). |
| 18 | `AppendMetadataClauses(filter.HasMetadataKey, filter.MetadataFields)` | **Excluded** | Caller filter over arbitrary `metadata` JSON keys/values — a query-surface feature (epic §3: `SearchIssues`/paging/counts/tree walks never in this lane), not a fixed predicate clause. |
| 19 | `BuildReadyWorkOrder` (`ORDER BY`: oldest / priority / hybrid-with-48h-recency) | **Excluded** | Epic-mandated exclusion: the ready value is a *set*; ordering is out of scope entirely, including the 48h wall-clock recency policy. |

### 1.1 `ReadyWorkExcludeTypes` (clause #8b), enumerated

`ReadyWorkExcludeTypes(nil)` (no caller `ExcludeTypes`) at the pinned commit:

- Base four, hardcoded in `ready.go`: `merge-request`, `gate`, `molecule`, `rig`.
- Plus `domain.DefaultInfraTypes()` (`internal/storage/domain/infra_types.go`,
  same pinned commit): `agent`, `role`, `message`.

`ReadyPredicate.EXCLUDED_TYPES` is exactly these seven —
`{merge-request, gate, molecule, rig, agent, role, message}` — pinned by the
test `EXCLUDED_TYPES is exactly the base four plus DefaultInfraTypes' three`.

### Modelled floor and mandatory-excluded floor (epic acceptance)

- Modelled >= {default status set, derived not-blocked, pinned, ephemeral,
  type exclusions}: satisfied — rows 3, 5, 4, 6, 8b above.
- Excluded >= {48h recency ordering, `defer_until` vs `UTC_TIMESTAMP()`,
  parent-descendant sets}: satisfied — rows 19, 10a, 16 above.

## 2. Blocking semantics (established from source; implemented by computenet-98u.1.2)

`ReadyPredicate` never reads `is_blocked` and never derives it — it takes a
`blocked: Boolean` from its caller. This section is what that caller (the
derived cell, computenet-98u.1.2) must implement to match beads, established
here from the SQL that actually maintains `is_blocked`
(`markBlockedTemplateForIssues`/`unmarkBlockedTemplateForIssues`/
`waitsForGateBlockedSQL`, `internal/storage/issueops/blocked_state.go`, run by
`RecomputeIsBlockedAfterMergeInTx` per the task's pinned `store.go:3705-3714`
citation), not assumed.

### 2.1 Which `dep_type` values block

- **`blocks`, `conditional-blocks`** — directly blocking. An issue is blocked
  while it has such an edge to a target that is open (§2.3). **Modelled** —
  this is the floor computenet-98u.1's acceptance criteria require
  (`SetCell<MirrorEdge>` with `type` naming the relation, computenet-98u.1's
  design assumes `"blocks"` is a real type and states blocking types are
  "established from beads source/empirics, not assumed to be only 'blocks'";
  this establishes `conditional-blocks` as the second one).
- **`waits-for`** — blocks under a metadata-driven "spawner/gate" mechanism
  (`waitsForGateBlockedSQL`): the wait target's `parent-child` "spawned
  children" must all be closed (default `metadata.gate = 'all-children'`) or
  at least one closed (`metadata.gate = 'any-children'`), **unless**
  `metadata.also_blocks = 'true'` (a collapsed redundant `blocks`/`depends_on`
  edge), in which case it blocks for as long as the spawner target itself is
  open, overriding the any-children carve-out. **Excluded** — this requires
  metadata (`gate`, `also_blocks`) attached to the *edge*, and
  `MirrorEdge` is a bare `(issueId, dependsOnIssueId, type)` triple with no
  metadata slot; modelling it would need a kernel/mirror change to carry
  per-edge metadata, out of computenet-98u's scope (closely related to the
  epic's parent-descendant-sets exclusion — both are about *spawned/child*
  graph shapes, not a flat edge set).
- **`parent-child` propagation** — a `parent-child` edge additionally
  propagates blocked state: if issue A "depends on" parent P via a
  `parent-child` edge and `p.is_blocked = 1`, then A is blocked too, whether
  or not A has any `blocks`/`conditional-blocks` edge of its own. **Excluded**
  — this is a transitive, whole-graph-shaped rule (it recurses through
  however many `parent-child` hops separate A from a blocked ancestor), the
  same class of computation the epic mandates excluding for parent-descendant
  sets (row 16 above), not a per-edge test.
- Every other `type` value (e.g. `parent-child` used non-propagatively,
  anything else `bd dep add` accepts) is **non-blocking** by omission — the
  recompute SQL only ever tests `type IN ('blocks', 'conditional-blocks')` or
  `type = 'waits-for'` or `type = 'parent-child'` (propagation); nothing else
  appears in any `WHERE`/`JOIN` predicate that sets `is_blocked`.

### 2.2 "Open blocker"

A blocking-type edge's target counts as an open blocker while its `status` is
**neither `'closed'` nor `'pinned'`** (`t.status <> 'closed' AND t.status <>
'pinned'`, every `EXISTS` clause in both templates).

This `'pinned'` is beads' issue **status enum value** (a status a `bd update
--status pinned` sets), **not** the same thing as the boolean `pinned` column
`ReadyPredicate.isReady` tests on the *ready* issue itself (`dep.4` in §1).
Both are real, both are spelled "pinned", and they never interact: one is a
status string compared with `=`/`<>`, the other is a `tinyint(1)` compared
with `= 0`. A future differential harness or implementer must not conflate
them.

### 2.3 Dangling / foreign `dependsOnIssueId`

The recompute SQL resolves a blocking edge's target via `JOIN issues t ON
t.id = d.depends_on_issue_id` (or `wisps t ON t.id = d.depends_on_wisp_id`).
An `INNER JOIN` against an id that names neither an issue nor a wisp in this
workspace — the dangling/foreign case `MirrorEdge`'s own KDoc documents (the
far side "need not name an issue this mirror holds") — produces no row, so
the `EXISTS` for that edge is `false`.

**A dangling or foreign blocking-type target therefore does not block.** The
same conclusion falls out independently from `GetBlockedIssuesInTx`
(`internal/storage/issueops/blocked.go`, same pinned commit): a blocking dep
whose target status lookup misses (`!ok`) is `continue`d, never added to that
issue's blocker list.

This was established by reading `blocked_state.go`/`blocked.go` at the pinned
commit; it was not re-derived empirically against a live scratch workspace,
since the SQL's `JOIN` semantics settle it unambiguously and beads exposes no
CLI surface in this environment (`bd` 1.1.2) to create a dangling `blocks`
edge without also creating its target (`bd dep add` validates the target
exists).

## 3. Field-rendering facts this predicate depends on (verified live)

Verified 2026-08-18 against `bd` 1.1.2 / `dolt` 2.2.3, scratch workspace, by
querying `dolt_diff_issues` directly with `dolt sql -r json` (the same query
`DoltCommitFeed.ISSUE_QUERY` runs) after `UPDATE issues SET pinned=1,
ephemeral=1 ...; CALL DOLT_COMMIT(...)`:

```json
{"diff_type":"modified","from_ephemeral":0,"from_pinned":0,"from_status":"open",
  "to_ephemeral":1,"to_pinned":1,"to_status":"open"}
```

and on the initial `ADDED` row for a freshly created issue, every non-`NULL`
column prints unconditionally, including the falsy ones:

```json
{"diff_type":"added", ..., "to_ephemeral":0, "to_pinned":0, "to_is_blocked":0,
  "to_issue_type":"task", "to_status":"open", ...}
```

Conclusions `ReadyPredicate` relies on:

- `pinned`/`ephemeral` (`tinyint(1)` columns) render as **bare JSON integers**
  (`0`/`1`), never JSON booleans and never quoted strings.
  `MirrorProjector.fieldDelta` stores `new.toString()` on that raw
  `JsonElement`, so the mirror's stored string is `"0"`/`"1"` (unquoted).
  `ReadyPredicate.stringField`/`isTruthyBoolean` parse that back via
  `Json.parseToJsonElement(raw).jsonPrimitive.content`, which handles both
  this shape and a quoted-string shape uniformly.
- `status`/`issue_type` (`varchar` columns) render as **quoted JSON strings**
  (`"open"`, `"task"`), matching `EchoDropTest`'s existing
  `JsonPrimitive("open").toString()` fixture pattern.
- Because every issue's `ADDED` diff row carries **every** non-`NULL` column
  unconditionally (`dolt_diff_issues`, not `bd export`'s optional-field
  omission), `status`/`pinned`/`ephemeral`/`issue_type` are present in the
  mirror's field map for every issue from the moment it is created, with
  `pinned`/`ephemeral` explicitly keyed to `"0"` rather than absent. The
  "absent means default" fallback `ReadyPredicate.isTruthyBoolean` still
  implements is therefore a defensive completeness property (matching
  `pinned IS NULL`/`ephemeral IS NULL` in ready.go, and covering any future
  path that could leave a field key un-mirrored), not something this
  workspace's ordinary write path is ever observed to exercise. This
  supersedes the task's "unverified" note in its own description — the
  export-side falsy-field omission (`bd export`/`.beads/issues.jsonl`) it
  observed does **not** carry over to the diff-feed side the mirror actually
  reads.
- `bd` 1.1.2's CLI in this environment exposes no direct `--pin`/`bd pin`
  flag to set the boolean `pinned` column (`bd update --help` lists
  `--ephemeral` but no pin equivalent); the probe above set it with a direct
  `dolt sql` `UPDATE` against the scratch workspace's embedded Dolt database
  instead, then `CALL DOLT_COMMIT(...)` to produce a real diff row.
