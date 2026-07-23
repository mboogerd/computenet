# Relational view DSL — declarative pipelines that compile to cells

**Type:** API change (understandability)
**Origin:** `:demo:skillmatch` `SkillPipeline.build` — and the same
spawn/connect shape in every incremental demo.

## Origin of the idea

The skillmatch pipeline is relational algebra written as manual graph wiring:

```kotlin
val matches = spawn("matches") { JoinSetCell(leftKey = {…}, rightKey = {…}, combine = {…}) }
val matchCounts = spawn("matchCounts") { GroupByCell(keyFn = {…}, aggregator = count()) }
val gap = spawn("gap") { SemiJoinCell(leftKey = {…}, rightKey = {…}, negated = true) }
connect(cand, "outlet", matches, "left")
connect(jobs, "outlet", matches, "right")
connect(matches, "outlet", matchCounts, "inlet")
connect(jobs, "outlet", gap, "left")
connect(cand, "outlet", gap, "right")
// …six spawns, eight connects, string port names…
```

Every reader mentally decompiles this back into: *matches = candidates ⋈ jobs on
skill; qualification = count(matches) per pair vs count(jobs) per job; gap =
jobs ▷ candidates on skill.* The intent is a handful of relational expressions;
the code is a wiring diagram with stringly-typed ports where a mistyped
`"left"`/`"right"` or a crossed `connect` fails at runtime, not compile time.

## What it is

A thin builder that lets pipelines read as the algebra they are, compiling
**down to the exact same cells** (no new runtime, no interpreter):

```kotlin
val g = relational(host) {
    val cand = source<CandidateSkill>("candSkills")
    val jobs = source<JobSkill>("jobSkills")

    val matches = cand.join(jobs, on = CandidateSkill::skill to JobSkill::skill) {
        c, j -> Match(c.candidate, j.job, c.skill)
    }
    val qualified = matches.groupCount { CandidateJob(it.candidate, it.job) }
        .equalsPerKey(jobs.groupCount { it.job }, on = CandidateJob::job)   // backlog 02
    val gap = jobs.antiJoin(cand, on = JobSkill::skill to CandidateSkill::skill)
    val market = cand.groupCount { it.skill }
        .combine(jobs.groupCount { it.skill }) { _, s, d -> Market(s ?: 0, d ?: 0) }  // backlog 01

    expose(matches, qualified, gap, market)
}
```

`join`/`antiJoin`/`groupCount`/`combine`/`equalsPerKey` each `spawn` the
corresponding cell and `connect` the ports for you, returning a typed handle
whose element type flows through the chain — so port names and directions are
generated, not written, and type errors are caught at compile time.

## Why it is a proper fit

- It is **pure sugar over `graph { }`**: it emits the same `spawn`/`connect`
  calls the demos write today, so it inherits every runtime guarantee
  (incremental deltas, waves, replication, evolution) with zero semantic
  surface of its own. Nothing to verify at runtime beyond "it produces the
  expected graph."
- It makes the operator suite **discoverable**: the available combinators are
  the builder's methods, and adding a kernel operator (backlog 01/02) adds one
  method here — the demos then read as SQL-ish views, which is the stated
  purpose of the demo modules (showcase the operator suite).
- It removes the most error-prone hand-work (string port names, connect
  direction) that has no reason to be manual.

## Solution sketch

A `RelationalScope` wrapping `graph { }`. Each handle carries `(ref, portName,
elementType)`. `source<E>(name)` → `SetCell<E>`. `join(other, on, combine)` →
`spawn(JoinSetCell(...))` + two `connect`s, returns a set handle of the combine
type. `groupCount(key)`/`groupBy(key, agg)` → `GroupByCell`. `antiJoin` →
`SemiJoinCell(negated = true)`. `combine`/`equalsPerKey` → the backlog-01/02
cells once they exist. `expose(...)` returns the outlet refs (today's `Refs`).
Selectors are plain lambdas / property references; no macro or codegen needed —
it is ordinary Kotlin builder methods.

## Expected inputs / outputs

- Input: a `relational(host) { }` block of algebra over declared sources.
- Output: the identical cell graph `SkillPipeline.build` produces today
  (assert by structural equality of the resulting graph), plus a typed `Refs`.

## Acceptance criteria

- The DSL form of `SkillPipeline` compiles to a graph structurally equal to the
  hand-wired one (same cells, same links), verified in a test.
- All existing skillmatch tests pass unchanged against the DSL-built pipeline.
- A wrong-direction / mismatched-key wiring that today fails at runtime becomes
  a **compile error** in the DSL (documented with one example).
- Adding one kernel operator surfaces as one builder method; demonstrated by
  wiring `market` via `.combine` (backlog 01).
- Scope discipline: the DSL adds no new runtime cell type and no new wire
  behavior — it is builder sugar only (reviewed as such).
