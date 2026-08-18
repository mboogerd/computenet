package civictech.demo.beadsmirror.ready

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * Beads' per-issue ready predicate (task computenet-98u.1.1; feature
 * computenet-98u.1; epic computenet-98u/BDS3), modelled as a **pure**
 * function of one issue's mirrored field map plus an externally-supplied
 * blocked flag.
 *
 * The predicate this models is `BuildReadyWorkWhere`, cited at a pinned
 * commit:
 * https://github.com/gastownhall/beads/blob/21822ad725d617e31c7d17bba573531100d2df12/internal/storage/sqlbuild/ready.go
 * (`ReadyWorkExcludeTypes`, same file, for the type-exclusion set; the note
 * that `gastownhall/beads` is a fork whose Go import path still reads
 * `github.com/steveyegge/beads` is unrelated to this citation — the commit
 * pinned above is what was fetched and read).
 *
 * Full clause-by-clause coverage — modelled vs. excluded-with-reason — lives
 * in `demo/beadsmirror/READY-COVERAGE.md`; this KDoc covers only what THIS
 * type does.
 *
 * **Scope.** Every clause here reads only the issue's own field map:
 * - the default status set (`status IN ('open', 'in_progress')`, ready.go
 *   lines 104-106 — the caller-supplied `filter.Status`/`filter.Statuses`
 *   override is a caller filter, out of scope per computenet-98u.1's "Default
 *   call semantics only" decision),
 * - `pinned` falsy-or-absent (ready.go line 109: `(pinned = 0 OR pinned IS
 *   NULL)`),
 * - `ephemeral` falsy-or-absent, the `!filter.IncludeEphemeral` default
 *   branch (ready.go lines 112-114),
 * - `issue_type` not in the default exclusion set (ready.go's
 *   `ReadyWorkExcludeTypes` with no caller extras: `merge-request`,
 *   `gate`, `molecule`, `rig`, plus `domain.DefaultInfraTypes()` —
 *   `internal/storage/domain/infra_types.go` at the same pinned commit lists
 *   exactly `agent`, `role`, `message`).
 *
 * **`is_blocked` is never read here.** [isReady]'s `blocked` parameter is
 * beads' `is_blocked = 0` clause (ready.go line 110) already decided
 * elsewhere — computenet-98u.1's derived cell (98u.1.2), NOT this predicate,
 * which must derive it live from the dependency edge set rather than trust
 * the mirrored (denormalized, staleness-prone) column. This type has no
 * access to edges at all, by construction: it cannot read `is_blocked` even
 * by accident.
 *
 * **Blocking semantics, established here from source for 98u.1.2 to
 * implement** (task computenet-98u.1.1's rule 3 — this predicate does not
 * use this, but the derivation the `blocked` parameter must feed from does):
 * `internal/storage/issueops/blocked_state.go` at the same pinned commit,
 * `markBlockedTemplateForIssues`/`unmarkBlockedTemplateForIssues` (the SQL
 * `RecomputeIsBlockedAfterMerge` — cited in the task at `store.go:3705-3714`
 * — ultimately runs, via `RecomputeIsBlockedAfterMergeInTx` ->
 * `RecomputeIsBlockedInTx` -> these templates):
 * - **Directly blocking dependency types**: `blocks` and `conditional-blocks`
 *   — an issue is blocked while it has such an edge whose target
 *   (`t.status <> 'closed' AND t.status <> 'pinned'`) is open. Note the
 *   *target's* openness test is against beads' issue **status** enum value
 *   `'pinned'` (a status a `bd update --status pinned` can set), which is a
 *   different thing from the boolean `pinned` column [isReady] tests on the
 *   READY issue itself — both are real, both are named "pinned", and they do
 *   not interact.
 * - **`waits-for`**: blocks under a metadata-driven gate/spawner mechanism
 *   (`waitsForGateBlockedSQL`: parent-child "spawned children" of the wait
 *   target, `metadata.gate` = `all-children` (default) vs `any-children`,
 *   and a `metadata.also_blocks` collapse-edge carve-out) that this mirror's
 *   edge model (a flat `(issueId, dependsOnIssueId, type)` triple with no
 *   metadata) cannot express without a metadata-carrying edge type kernel
 *   change — excluded from computenet-98u's scope, same as the epic's
 *   parent-descendant sets exclusion this is closely related to.
 * - **`parent-child` propagation**: a `parent-child` edge additionally
 *   propagates: if issue A "depends on" parent P via a `parent-child` edge
 *   and P is itself blocked, A is blocked too
 *   (`d.type = 'parent-child' AND p.is_blocked = 1`). This is a transitive,
 *   graph-shaped rule, not a per-edge test — excluded from computenet-98u's
 *   scope for the same reason the epic mandates excluding parent-descendant
 *   sets.
 * - **Open blocker**: not `status = 'closed'` and not `status = 'pinned'`
 *   (both templates, every EXISTS clause).
 * - **Dangling/foreign target**: the recompute SQL `JOIN`s the target id
 *   against `issues`/`wisps`; a target id that resolves to neither produces
 *   no row, so the `EXISTS` for that edge is false — a dangling or foreign
 *   `dependsOnIssueId` **does not block**. The same conclusion falls out of
 *   `GetBlockedIssuesInTx` (`internal/storage/issueops/blocked.go`, same
 *   pinned commit): a blocking dep whose target status lookup misses
 *   (`!ok`) is `continue`d — never added to the blocker map.
 *
 * **`no bd subprocess`**: this file has no `ProcessBuilder`/`bd`/`dolt`
 * dependency of any kind — the acceptance clause holds structurally, not by
 * a runtime check.
 */
object ReadyPredicate {

    /** ready.go line 105's default `status IN (...)` set (no caller `filter.Status[es]` override). */
    val DEFAULT_READY_STATUSES: Set<String> = setOf("open", "in_progress")

    /**
     * ready.go's `ReadyWorkExcludeTypes(nil)`: the base four
     * (`merge-request`, `gate`, `molecule`, `rig`) plus
     * `domain.DefaultInfraTypes()` (`agent`, `role`, `message`) — no caller
     * `filter.ExcludeTypes` extras, matching [isReady]'s "default call
     * semantics only" scope.
     */
    val EXCLUDED_TYPES: Set<String> = setOf(
        "merge-request",
        "gate",
        "molecule",
        "rig",
        "agent",
        "role",
        "message",
    )

    /**
     * Whether one issue belongs in the default ready set.
     *
     * [fields] is the issue's slice of the mirror's `MirrorProjector.cell`
     * (`MirrorKey.field -> value`), where each value is stored exactly as
     * `MirrorProjector.fieldDelta` puts it: the raw `dolt_diff_issues` JSON
     * cell's `.toString()` rendering (a quoted JSON string for a `varchar`
     * column such as `status`/`issue_type`, a bare JSON number for a
     * `tinyint(1)` column such as `pinned`/`ephemeral` — verified live
     * 2026-08-18 against `bd` 1.1.2 / `dolt` 2.2.3: `to_pinned`/`to_ephemeral`
     * render as the unquoted JSON integers `0`/`1`, never as JSON booleans or
     * quoted strings). [stringField] undoes that rendering back to plain
     * text regardless of which of the two shapes it was.
     *
     * [blocked] is beads' `is_blocked = 0` clause, decided by the caller
     * (never by this function) from the dependency edge set — see the type
     * KDoc for why.
     *
     * A required field ([REQUIRED_FIELDS]) **absent** from [fields] fails
     * the predicate closed (not ready) rather than defaulting — every
     * `dolt_diff_issues` row generated for an `ADDED` diff carries `status`
     * and `issue_type` (both `NOT NULL` columns; verified live in the same
     * probe: a fresh issue's `ADDED` row prints `to_status`/`to_issue_type`
     * unconditionally), so absence here means the caller handed this
     * function an incomplete slice, not a real beads state — the same "fail
     * loudly rather than coerce" posture `DoltCommitFeed`'s own shape checks
     * take, applied as fail-closed here since this is a boolean predicate
     * with no exception channel of its own.
     */
    fun isReady(fields: Map<String, String>, blocked: Boolean): Boolean {
        if (blocked) return false

        val status = stringField(fields, "status") ?: return false
        if (status !in DEFAULT_READY_STATUSES) return false

        if (isTruthyBoolean(fields, "pinned")) return false
        if (isTruthyBoolean(fields, "ephemeral")) return false

        val issueType = stringField(fields, "issue_type") ?: return false
        if (issueType in EXCLUDED_TYPES) return false

        return true
    }

    /** The two columns [isReady] treats as required-present; see its KDoc. */
    val REQUIRED_FIELDS: Set<String> = setOf("status", "issue_type")

    /**
     * Undoes [MirrorProjector][civictech.demo.beadsmirror.projector.MirrorProjector]'s
     * `JsonElement.toString()` storage rendering, back to the field's plain
     * text content — `"\"open\""` -> `"open"`, `"1"` -> `"1"`. `null` when
     * [key] is absent from [fields].
     */
    internal fun stringField(fields: Map<String, String>, key: String): String? =
        fields[key]?.let { raw -> Json.parseToJsonElement(raw).jsonPrimitive.content }

    /**
     * Whether [key] is present and truthy. **Absent means falsy** (ready.go's
     * `pinned = 0 OR pinned IS NULL` / `ephemeral = 0 OR ephemeral IS NULL`
     * clauses both treat SQL NULL the same as the zero value).
     *
     * Truthy content is `"1"` (the verified live rendering of a `tinyint(1)`
     * column's `1`) or `"true"` — the latter is a **defensive, unverified**
     * branch: no observed rendering of `pinned`/`ephemeral` produces a JSON
     * boolean literal or a quoted `"true"`/`"1"` string, but nothing in
     * `dolt`'s `-r json` contract rules either out for a future schema/dolt
     * version, and treating them as falsy by omission would be a silent
     * coverage gap rather than a stated exclusion.
     */
    internal fun isTruthyBoolean(fields: Map<String, String>, key: String): Boolean {
        val value = stringField(fields, key) ?: return false
        return value == "1" || value == "true"
    }
}
