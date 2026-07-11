# Concept Index — Prompting & Workflow Practices for Claude Opus + Fable

One entry per distinct idea/concept found across the reference set, with pointers to every place it's
discussed (agreeing or disagreeing). File numbers refer to `references/NNN-*.md`. Where a source disagrees
or adds a caveat, that's called out explicitly.

---

### 1. Give context/intent, not step-by-step instructions
The strongest, most repeated idea in the whole set: state the larger goal, who it's for, and constraints,
then let the model determine its own sequence — rather than dictating steps.
- Discussed (agree): `001` ("give the reason, not only the request"), `007` (context over planning),
  `013` (this is the article's central thesis — "old Opus habits" of numbered steps are counterproductive
  on Fable), `018` ("context beats instructions on this model, every single time")
- Nuance/partial tension: `005` rule 4 warns against dressing up *simple* requests as hard reasoning
  problems — implying context-first framing shouldn't be applied indiscriminately to trivial asks.

### 2. Calibrate the `effort` parameter instead of rhetorical prompting
Use the model's explicit effort/thinking-budget control (`high` default, `xhigh` for hard work, `low`/
`medium` for routine) rather than phrases like "think harder."
- Discussed (agree): `001`, `002` (adaptive thinking + effort param), `013` (effort as "first-class API
  parameter"), `017` ("high" suits most tasks, "xhigh" is oversized for routine work)

### 3. Stop over-planning; act once there's enough information
Don't re-derive settled facts or narrate unpursued options; act when the model has sufficient context.
- Discussed (agree): `001` (explicit instruction snippet), `013` (removing procedural dictation surfaced
  better solutions than prescribed steps)

### 4. Avoid unsolicited refactors / scope creep
Bug fixes shouldn't come with surrounding cleanup; execute exactly what was asked and flag adjacent issues
rather than fixing them silently.
- Discussed (agree): `001`, `007` ("define boundaries explicitly"), `011` ("strict scope adherence")

### 5. Lead with the outcome in final summaries; keep "working" register separate from "reporting" register
Terse shorthand is fine mid-task; the final summary should read as if for someone who saw none of the
intermediate work.
- Discussed (agree): `001` (explicit "communication-style addendum"), `007` ("prioritize clarity... lead
  with results")

### 6. Ground progress claims in actual tool/test evidence
Require the model to audit claims against real tool results before reporting, and say plainly when
something is unverified, failing, or skipped.
- Discussed (agree): `001` (explicit instruction snippet + "claims must carry evidence"), `010` (claim
  labeling: VERIFIED/REASONED/ASSUMED), `006` ("audit every claim against tool results"), `007`
  (self-verification against original request)

### 7. Build an explicit, curated memory system
One lesson per markdown file, one-line summary at top, update rather than duplicate, delete wrong notes;
keep config files (CLAUDE.md-style) lean rather than exhaustive.
- Discussed (agree): `001`, `011` (session memory management), `018` (trim config files to 3 elements),
  `017` (lean repo prep)

### 8. Delegate to subagents deliberately, prefer async over blocking
Give explicit guidance on when delegation is appropriate; keep long-lived subagents to reuse cached
context; don't block on every subagent return.
- Discussed (agree): `001`, `009` (orchestrator-worker topology), `010`, `014`, `017`, `018`
  (manager-over-worker mindset)

### 9. Route work between Fable and Opus by task difficulty/ambiguity, not uniformly
Fable for ambiguous, architecture-level, long-horizon, or high-stakes work; Opus/Sonnet for well-scoped,
routine, high-volume, or latency-sensitive work.
- Discussed (agree): `003` (product positioning), `009` (concrete routing rules), `016` (benchmark-backed
  version of the same rule), `017` ("Fable sandwich" phase split), `018` (quota-driven version of the same
  rule)

### 10. Phase-based workflow: explore → plan → execute → verify
Split work by phase across models rather than asking one model to do a task end-to-end.
- Discussed (agree): `017` (names this the "Fable sandwich," reports it more reliable than single-model
  end-to-end), `012` (independently previews a before/during/after phase structure), `009` (manual
  plan-then-execute pattern, artifact handoff variant)

### 11. Encode "Fable-like" discipline into cheaper models via explicit, checkable rules
The Opus/Fable capability gap on many tasks is procedural, not purely intelligence — so it can be closed by
writing down failure-pattern rules, decision branches, and verification recipes.
- Discussed (agree): `010` (five-axis technique, the primary source for this idea), `011` (six Skills
  validated via blind grading against plain Opus 4.8, 12-0-2 result)

### 12. Design system before components (UI-specific)
Have the model define tokens/type scale/color roles before generating individual UI components.
- Discussed (agree): `004` (primary source), `008` (same idea, "design-system reference document")

### 13. Specify interaction states explicitly (hover/focus/loading/empty)
Models skip these by default; must be requested explicitly for finished-feeling UI.
- Discussed (agree): `004` (primary/only source, presented as single highest-leverage addition)

### 14. Use realistic data instead of placeholder text in UI prompts
Hardcoding representative data measurably improves perceived UI polish.
- Discussed (agree): `004` (primary source)

### 15. Avoid generic "AI slop"; ask explicitly for distinctive aesthetics
Request unique typography, cohesive color/motion systems explicitly rather than assuming default taste.
- Discussed (agree): `002` (earliest/official statement), `004`, `008` (implicitly, praising
  "sophisticated color choices" as differentiator)

### 16. Supply a reference layer of real, shipped design patterns
Anchoring prompts with real examples (e.g. a design-pattern MCP connector, brand design-system docs)
prevents generic visual drift across multi-screen work.
- Discussed (agree): `004` (Mobbin MCP), `008` (brand design-system documentation)

### 17. Vision-based self-verification (screenshot-driven testing)
The model can test/critique/fix its own UI output using vision, reducing human review burden.
- Discussed (agree): `001` (enhanced vision, bash/crop tool use), `004` (observed automatically in practice)

### 18. Disagreement: Fable cannot originate custom images/illustrations
Contradicts the more general "one-shots complete applications" framing — when asked for images, it
substituted real stock photos rather than generating original art.
- Discussed (disagree with `003`'s framing): `008` (primary source of this specific limitation)

### 19. Disagreement: Fable is not the leader on aesthetic "design taste" specifically
An open-weight model (GLM 5.2) reportedly beats Fable 5 on a dedicated design-taste benchmark, even though
Fable leads on coding/agentic benchmarks.
- Discussed (disagree with `003`/`008`'s implied aesthetic supremacy): `015` (primary source); contrast
  with `016` which shows Fable's lead is much clearer on coding-oriented benchmarks (SWE-Bench Pro) than
  implied for pure design taste.

### 20. Cost/pricing: Fable runs roughly 2x Opus's per-token cost
Consistently cited multiplier; several sources note it's partly offset by fewer turns/faster convergence
on hard tasks.
- Discussed (agree): `003` (implicit, premium positioning), `009`, `016` (concrete $ figures), `018` (quota
  burn framing)

### 21. Safety-classifier fallback to Opus on sensitive topics
Cybersecurity/biotech/model-distillation-adjacent prompts auto-reroute to Opus 4.8; benign coding requests
can occasionally trigger this too.
- Discussed (agree): `003`, `009`, `014`, `017` (recommends "defensive code review" framing to reduce
  false-positive fallback)

### 22. Audit/refactor existing prompts, skills, and instruction files before adopting a new model
Older instructions often over-compensate for a weaker model's limitations; review and trim before layering
new guidance on top.
- Discussed (agree): `001` (explicit migration-checklist item), `014` (primary source, migration-priority
  section)

### 23. Don't ask the model to expose raw reasoning; use structured thinking blocks instead
Requesting hidden/raw reasoning can trigger a refusal; use the adaptive-thinking mechanism instead.
- Discussed (agree): `001` (explicit rule + rationale), `017` (independently reports the same refusal
  behavior as a "gotcha")

### 24. XML-tag structuring and few-shot examples over prose description
Structuring prompts with tags and showing 1–2 concrete examples resolves more ambiguity than describing
the desired output in prose.
- Discussed (agree): `002` (official baseline practice), `005` (rules 5 and 6)

### 25. Role/persona assignment at the start of a prompt
Telling the model what professional perspective to adopt before the task shifts its approach.
- Discussed (agree): `002`, `005` (rule 1)

### 26. Treat the prompt itself as a small system/workflow, not a bare query
Combine context + task + explicit limits + output format + verification step into one structured request.
- Discussed (agree): `007` (primary/clearest source, explicitly named "workflows over prompts"), echoed
  loosely by `006`'s "workflow design as artifact" category

### 27. "Map vs. territory": surface hidden assumptions before delegating
A named mental model for identifying the gap between what was literally asked and what's actually needed,
before handing a task to Fable or Opus.
- Discussed (agree): `012` (primary/only source)

### 28. Long turns by default at higher effort — adjust harness expectations
Hard tasks at high effort can run many minutes; adjust client timeouts, use async status checks, don't
block UI on a single long response.
- Discussed (agree): `001` (primary source)

### 29. Provide a dedicated "send to user" tool for long asynchronous agents
Ensures verbatim deliverables/progress updates reach the user without being compressed into a turn-ending
summary.
- Discussed (agree): `001` (primary/only source, includes a sample tool schema)

### 30. Autonomy framing for unattended/overnight runs
Explicitly tell the model the user isn't watching and can't answer mid-task, so it should proceed on
reversible actions instead of pausing to ask permission; check its own last paragraph for undone promised
work before ending a turn.
- Discussed (agree): `001` (primary source, explicit system-reminder text), `006` (delegation & overnight
  tasks category, "workaround protocol" for when stuck), `018` (goal-oriented autonomous commands with hard
  stopping limits)

---

## Sources index (all files)

| # | File | Type | One-line focus |
|---|------|------|-----------------|
| 001 | `001-anthropic-prompting-fable-5.md` | official | Fable 5 migration/prompting guide |
| 002 | `002-anthropic-prompting-best-practices.md` | official | Model-agnostic prompting baseline |
| 003 | `003-anthropic-claude-fable-product-page.md` | official | Fable's positioning vs. Opus |
| 004 | `004-griffinwooldridge-ui-design-techniques.md` | experience-report | Concrete UI-quality techniques |
| 005 | `005-mindstudio-six-rules.md` | prescribed-practice | Six compact prompting rules |
| 006 | `006-every-prompt-library.md` | prompt-library | Prompt "jobs to be done" by project phase |
| 007 | `007-phillipalcock-workflow.md` | experience-report | "Workflows over prompts" template |
| 008 | `008-banani-frontend-design.md` | experience-report | Frontend/UI strengths & limits (vendor) |
| 009 | `009-mcpdirectory-model-routing.md` | prescribed-practice | Fable/Opus routing rules |
| 010 | `010-devto-teach-opus-sonnet.md` | experience-report | Encoding Fable discipline into Opus |
| 011 | `011-iwoszapar-skills-rigor.md` | experience-report | Skills + blind-graded validation |
| 012 | `012-linas-practical-guide.md` | experience-report | Phase structure, assumption-surfacing |
| 013 | `013-kenhuang-prompting-shift.md` | experience-report | Why old Opus habits hurt on Fable |
| 014 | `014-productcompass-pm-guide.md` | prescribed-practice | Migration hygiene, safeguards, cost |
| 015 | `015-mindstudio-glm-design-taste.md` | disagreement | GLM 5.2 beats Fable on design taste |
| 016 | `016-truefoundry-benchmarks-pricing.md` | benchmark | Fable vs Opus numbers |
| 017 | `017-wavect-coding-workflow.md` | experience-report | "Fable sandwich" phase workflow |
| 018 | `018-aimadesimple-beginner-to-pro.md` | experience-report | Routing + quota economics |
