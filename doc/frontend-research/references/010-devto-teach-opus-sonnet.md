---
title: "Want to keep using Fable 5? Teach Opus and Sonnet to \"behave\" like it."
source_url: https://dev.to/toffy/want-to-keep-using-fable-5-teach-opus-and-sonnet-to-behave-like-it-4kl0
author: Yuiko Koyanagi
date: 2026-07-06
type: experience-report / prescribed-practice
retrieved: 2026-07-11
---

# Summary

Argues the Fable/Opus capability gap on many tasks is procedural discipline, not raw intelligence — and
that discipline can be encoded as explicit, checkable skills/instructions that Opus and Sonnet then follow,
closing much of the gap for a fraction of the cost.

## Five-axis technique for encoding "Fable-like" discipline into cheaper models

1. **Pre-writing procedures**: force repository/context reading before any implementation (check
   manifests/lockfiles, grep neighboring files) rather than assuming context.
2. **Failure-pattern mapping**: encode common mistakes as symptom → wrong instinct → correct move triples,
   e.g. "one button needs an onClick" → wrong instinct is marking the whole page client-side → correct move
   is extracting a small leaf component.
3. **Decision branches for high-stakes choices**: explicit if/then gates, e.g. for concurrency: "who cancels
   this, who waits on it, where do errors go — if any answer is 'nobody,' don't launch it."
4. **Verification recipes**: ordered, specific commands with meaning attached to each failure mode, and a
   labeling discipline for claims (VERIFIED / REASONED / ASSUMED) so confidence isn't overstated.
5. **Learning loops**: capture unanticipated failures as project-specific rules, but only after human
   approval, to avoid the rule set drifting on its own.

## Key constraint

Rules must be executable/checkable — vague guidance like "be careful" or "follow best practices" doesn't
transfer; only concrete, verifiable instructions do.

## Relevance to this project

Directly usable: encode the argumentation-graph project's own recurring UI mistakes (e.g., "if a node type
change affects layout, check downstream edge rendering") as this kind of failure-pattern rule, so Opus
executes with Fable-like discipline on the routine 80% of frontend work, reserving actual Fable calls for
the harder 20% per `009`.
