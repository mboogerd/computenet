---
title: "Fable 5 in Claude Code: Routing & Limits"
source_url: https://mcp.directory/blog/fable-5-claude-code-model-routing-guide-2026
publisher: MCP.Directory
date: 2026-06
type: prescribed-practice / experience-report (workflow-focused)
retrieved: 2026-07-11
---

# Summary

The most directly workflow-relevant source for a two-model (Opus + Fable) project: a tiered-routing model
for deciding, per task, whether to invoke Fable at all.

## When to route to Fable 5

- Ambiguous problems that need investigation before execution can even be scoped.
- Multi-session work that would otherwise span several separate conversations.
- Architecture decisions and major migrations.
- Root-cause analysis and complex debugging.
- Any case where the ~2x token cost is repaid by reduced need for self-verification later.

## When to stay on Opus/Sonnet

- Well-specified feature tickets with clear, narrow boundaries.
- Security- or pentest-adjacent repositories (safety classifiers reroute to Opus 4.8 automatically anyway).
- Zero-data-retention requirements (Fable's 30-day retention, per `003`, may be disqualifying).
- Rationed/limited subscription usage windows.

## Concrete workflow patterns

- **Manual plan-then-execute**: switch to Fable for investigation/planning, then switch to Opus to execute
  once the plan is reviewed — accepting a one-time token cost for the mid-conversation model switch.
- **Artifact handoff**: have Fable produce committed files (plans, scripts, test harnesses); Opus then
  executes against those files in separate sessions, avoiding the need to re-read prose context each time.
- **Orchestrator-worker topology**: run Fable as the main conversation doing decomposition, sequencing, and
  judging results (the long-horizon reasoning it's built for), while delegating token-heavy execution to
  subagents pinned to cheaper models (Sonnet for implementation, Haiku for search/lookup).

## Relevance to this project

This is the clearest template for how this project should actually split Opus/Fable responsibilities: Opus
implements well-scoped, already-designed frontend components; Fable is reserved for the harder, more
ambiguous calls — designing the argumentation-graph visualization's information architecture, resolving UX
tradeoffs, and reviewing/judging Opus's output rather than writing every line itself.
