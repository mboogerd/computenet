# Concord provenance — EARS ids and the concordance

The reference for L0 requirement ids (`covers:`) and the L4 concordance, distilled
from CONCORD-PLAN §1.1 and §1.5. W1-C (spec-editing) and W1-D (concordance
generator) build against this.

## 1. EARS id scheme (L0)

Requirement ids live **in the spec chapters** under `doc/spec/`, not in a new
document. A chapter already carries RFC-2119 language; W1-C adds a stable id and,
where a statement is vague, tightens it into one of the five EARS templates.

**Id form:** `«chapter»-«slug»-«nn»` — e.g. `21-PROP-01`, `22-GF-01`,
`13-LINK-05`, `42-REPL-04`.

- `«chapter»` is the two-digit spec chapter (`21`, `22`, `24`, `13`, …).
- `«slug»` is a short uppercase topic slug (`PROP`, `GF`, `LINK`, `CATCHUP`).
- `«nn»` is a zero-padded ordinal within the slug.
- Ids are **immutable once assigned** and **deprecated-never-reused**.

### The five EARS templates

| Template | Shape | Example |
|---|---|---|
| Ubiquitous | *The X SHALL …* | `[21-PROP-01]` The graph SHALL deliver every accepted delta to every transitively linked consumer, such that at quiescence each consumer's fold equals the source's accepted-op fold. |
| Event-driven | *WHEN «trigger», the X SHALL …* | `[21-CATCHUP-02]` WHEN a subscriber links to an outlet after deltas have flowed, the outlet SHALL bring it current such that its fold is indistinguishable from an early subscriber's. |
| State-driven | *WHILE «state», the X SHALL …* | `[22-GF-01]` WHILE a wave from a single source is partially delivered across a fork-join, a glitch-free cell SHALL NOT expose derived state that mixes pre- and post-wave inputs. |
| Unwanted behavior | *IF «condition», THEN the X SHALL …* | `[13-LINK-05]` IF a connect violates the inlet's admission policy, THEN the link SHALL be rejected with a stated reason and the existing topology SHALL be unaffected. |
| Optional feature | *WHERE «capability», the X SHALL …* | `[42-REPL-04]` WHERE replication is supported, replicas of one logical cell SHALL converge to equal folds regardless of which replica accepted each write. |

### Declaration vs citation (the scanner's rule)

An id is textually indistinguishable from itself wherever it appears — the
scanner (`ConcordanceScanner.scanRequirements`) has to tell a chapter's own
**declaration** of an id apart from another chapter's **citation** of it, so
that the concordance attributes the id to the chapter that owns it rather
than to whichever chapter happens to sort first by path.

The rule the scanner applies: an occurrence **declares** the id when the text
immediately following the closing `]` — skipping over any wrapping backtick
or markdown bold marker (`` ` `` / `**`) — starts with an **uppercase
letter**, opening a new EARS sentence per the five templates above (`The …`,
`WHEN …`, `WHILE …`, `IF …`, `WHERE …`) or an equivalent normative opener
(`A …`, `Migration and RESTART preserve …`). An occurrence **cites** the id
when it continues an existing sentence in lowercase, or is followed by
punctuation:

- Declaration: `[42-WM-01] The delivered-watermark state SHALL carry …`
- Declaration (house style wraps the id itself): `` `[24-WL-01]` A lateness
  declaration SHALL be … ``
- Citation: `a `[42-WM-01]` delivered-prefix row`, `(`[24-WL-04]`)`, ``
  `[21-REBASE-01]` is covered by … ``

When more than one chapter's occurrence of an id reads as a declaration by
this rule (for example a `90-roadmap/` ticket quoting a chapter's EARS
sentence verbatim), the first one by path sort order wins, same as the
general first-sighting rule — which is why a normative chapter (`00`–`50`)
still wins over a roadmap ticket (`90-roadmap/`) discussing it, without
needing a separate normative-first exception. When **no** occurrence of an id
reads as a declaration (a prose shape this rule cannot read), the scanner
falls back to the plain first sighting by path order, exactly as before this
rule existed. Both shapes are silent resolutions, not verified ones — §3's
"Unestablished ownership" and "Contested ownership" lints report them instead
of leaving them implicit.

A spec author who wants an id attributed to the chapter that owns it should
therefore let the id open the requirement sentence there, and cite it
elsewhere only mid-sentence (in backticks, by house style, but backticks are
not themselves the signal — sentence position is).

**Known blind spot:** the rule only inspects the *case* of the first
non-whitespace/backtick/bold character after the closing `]` — it does not
check whether what follows actually opens a normative sentence. A citation
immediately followed by an uppercase token — an acronym (`EARS-GAP`) or a
capitalised code identifier (`MapDelta`) — reads as a declaration for this
reason alone: `` the `[24-TAG-02]` EARS-GAP self-doubt `` classifies as a
declaration even though it continues a citation, not a new sentence. This is
why an id can carry more than one declaration-classified occurrence across
chapters (§3, "Contested ownership") without any chapter having actually
mis-declared it. It is also how an id ends up owned by a `90-roadmap/`
ticket instead of any normative chapter (§3, "Roadmap-owned declaration"):
the chapter only cites the id mid-sentence, so it never opens a genuine
declaration there, while the roadmap ticket's own citation happens to be
followed by an uppercase acronym and reads as one on case alone.

### The "checkable through the SPI" rule (the L0 gate)

A statement enters L0 (gets an id) **only when it is checkable through the driver
SPI** (`civictech.concord.driver`) — i.e. boundary-observable (P1). Statements
about internals — scheduling order, protocol frames, memory, progress acks — stay
normative prose **without ids**; they are implementation guidance, not conformance
surface. When W1-C hits a statement that resists a template, it **flags, does not
decide** (CONCORD-PLAN §4 W1-C, §5 dispute rule).

## 2. Concordance format (L4)

A generator (`:concord` Gradle task, W1-D) scans L0 ids and L2 `covers:` tags and
emits `doc/spec/CONCORDANCE.md`:

```
| Requirement | Scenarios              | Last run            |
| 21-CATCHUP-02 | 21-CATCHUP-01, 24-GEN-01 | ✅ 39e9636         |
| 22-GF-01      | 22-GF-DIAMOND-01         | ✅ 39e9636         |
| 42-REPL-04    | 42-REPL-01               | — (dist, not in gate) |
```

- **Requirement** — an L0 id.
- **Scenarios** — every scenario whose `covers:` names it.
- **Last run** — pass/fail marker plus the commit at which it was recorded; dist/dur
  rows outside the core gate are marked `—` with the reason.

## 3. Lint rules

Failing the build (fatal):

- **Dangling `covers:`** — a `covers:` id that matches no L0 requirement.
- **Orphan scenario** — a scenario with an empty `covers:` (P6: every scenario
  covers ≥1 id).

Reported but non-fatal:

- **Coverage gap** — a `Specified`-status requirement with no covering scenario.
  This is the testing agent's standing worklist (P6/P10), not a failure.
- **Unestablished ownership** (computenet-7ei34) — a requirement id with no
  declaration-classified occurrence anywhere in `doc/spec/**` (§1, "Declaration
  vs citation"); the concordance falls back to first-sighting-by-path for it,
  and this lint says so instead of leaving that fallback silent.
- **Contested ownership** (computenet-7ei34) — a requirement id with
  declaration-classified occurrences in more than one normative chapter
  (`00`–`50`). First-sighting-by-path still picks a winner, but more than one
  chapter genuinely reads as declaring the id, which is an ownership question
  a spec author should resolve, not a scanner bug.
- **Roadmap-owned declaration** (computenet-x3hqg, residual of
  computenet-7ei34's clause 1) — a requirement id with at least one
  declaration-classified occurrence, but none of them in a normative chapter
  file (`00`–`50`): every declaration-classified occurrence is in
  `90-roadmap/**` or another non-normative file (e.g. a spec-root
  `README.md`). This is a distinct condition from "Unestablished ownership",
  not a rewording of it — there the id has no declaration-classified
  occurrence anywhere and the concordance falls back to plain
  first-sighting-by-path; here [`scanRequirements`]'s declaration-attribution
  rule resolves cleanly to a real declaration, it is just one that lives
  outside the normative spec, most often via the "Known blind spot" above (a
  roadmap ticket's citation immediately followed by an uppercase acronym like
  `EARS-GAP`) rather than a spec author deliberately declaring the
  requirement in a ticket. Reported separately from "Unestablished ownership"
  so the two silent-resolution shapes stay distinguishable in the
  concordance: "never declared" vs. "declared, but owned by the wrong kind of
  file".

## 4. Exclusions are recorded (P10)

When a requirement is deliberately **not** covered (on P1 boundary-observability
or P4 cross-implementation grounds — concurrency colors, scheduling internals,
attention/stride, security membranes), the concordance records it as
excluded-with-reason. Silence is indistinguishable from oversight; an explicit
exclusion is the audit trail.

## Note on the pilot `covers:` ids

The four W0 pilots carry provisional `covers:` ids (`24-OP-03`, `21-PROP-01`,
`22-GF-01`) so they are not orphans. These are **placeholders** until W1-C lands
real EARS ids in chapters 21/22/24; W2 reconciles the pilots' `covers:` against
the assigned ids. A pilot id that W1-C does not mint becomes a dangling-lint hit
that W2 must fix — that is the intended forcing function.
