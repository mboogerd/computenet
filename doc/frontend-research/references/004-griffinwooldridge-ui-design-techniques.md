---
title: "Claude Fable 5 for UI Design: How to Get Beautiful Output Every Time"
source_url: https://www.griffinwooldridge.com/blog/claude-fable-5-for-ui-design-how-to-get-beautiful-output-every-time
author: Griffin Wooldridge
date: 2026-06-12
type: experience-report
retrieved: 2026-07-11
---

# Summary

Practitioner write-up focused specifically on getting consistently good-looking UI output, aimed at
designers/engineers using Fable 5 as a frontend generator rather than a general coding agent.

## Concrete techniques

- **Design system first**: ask the model to define a design system (tokens, type scale, spacing, color
  roles) *before* building any individual component — component-by-component requests without this drift
  toward inconsistent output.
- **Use realistic data, not placeholders**: hardcoding actual representative data (not "Lorem ipsum" or
  `Item 1`) measurably improves how "finished" the resulting UI reads.
- **Specify interaction states explicitly**: hover, focus, loading, and empty states are skipped by default
  unless explicitly requested — this is treated as the single highest-leverage prompt addition.
- **Set concrete, checkable scope**: concrete requirements give the model something to verify itself
  against, rather than an open-ended aesthetic judgment call.
- **Write prompts as design briefs**, structured the way you'd brief a senior design engineer, rather than
  a casual one-line ask.
- **Supply a reference layer of real shipped patterns** (the author uses a Mobbin MCP connector) to anchor
  styling and prevent generic-looking drift across a multi-screen flow.
- **Tune the effort dial to task complexity** (medium through max/ultra) rather than defaulting to one
  setting for everything.
- **Lean on vision-based self-verification**: the model's own screenshot-based testing/bug-fixing loop
  substantially reduces the review burden on the human.

## Relevance to this project

Directly actionable for the argumentation-graph frontend: before asking Fable to build the graph
visualization or node-editing UI, have it produce a design system (node/edge color roles, typography,
spacing) first, then generate components against that. Explicit-states and realistic-data advice apply
directly to argument nodes/edges (e.g., "claim," "rebuttal," "supported"/"disputed" states).
