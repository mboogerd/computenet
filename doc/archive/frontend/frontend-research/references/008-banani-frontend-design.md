---
title: "Fable 5 for UI & Frontend is the Final Boss of AI for UI"
source_url: https://www.banani.co/blog/fable-5-ui-and-frontend-design
author: Vlad Solomakha (Banani)
date: 2026-07-03
type: experience-report (vendor blog, comparative)
retrieved: 2026-07-11
---

# Summary

A UI-tool vendor's comparative review of Fable 5 against Gemini 3.1 Pro and GPT-5.4 for frontend/UI work.
Enthusiastic overall, but candid about specific weaknesses — useful as a counterweight to purely promotional
coverage.

## Claimed strengths

- Produces both minimalist and visually complex layouts with sophisticated color/typography choices.
- Handles multi-screen interactive prototypes with micro-animations well.
- Generates semantic HTML/CSS and properly componentized React + Tailwind, including accessibility
  attributes and consistent design-token usage.
- Accurately replicates an existing brand's design system when given reference documentation.

## Claimed weaknesses (disagreement / limitation)

- **Cannot actually generate custom images** — when asked for illustrations/photos, it sourced real stock
  photos from Wikimedia Commons rather than generating original imagery. This directly qualifies any claim
  elsewhere that Fable "one-shots" complete visual designs (contrast with the more unqualified praise in
  `003`, `018`).
- Notably slower than competing models (6+ minutes vs. under 2 minutes for some tasks).
- High token/cost consumption relative to output volume.
- Cannot reliably produce custom vector graphics or branded imagery.

## Practical techniques

- Use clarifying questions mid-prompt to steer creative direction rather than over-specifying up front.
- Supply a written design-system reference document for consistent multi-session output.
- Use a "send to Claude Code" style export step to hand generated UI off to a coding agent for integration.
- Request HTML/CSS or React output explicitly and separately depending on the handoff target.

## Relevance to this project

The image-generation limitation matters directly for an argumentation-graph UI: if the frontend needs
icons/illustrations (e.g., node-type icons, avatars), plan to source or hand-draw those separately rather
than expecting Fable to originate them — a concrete disagreement with the more general "one-shots complex
applications" framing in `003`.
