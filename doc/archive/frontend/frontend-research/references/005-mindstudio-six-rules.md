---
title: "How to Prompt Claude Fable 5 Like an Anthropic Engineer: 6 Rules That Actually Work"
source_url: https://www.mindstudio.ai/blog/how-to-prompt-claude-fable-5-anthropic-engineer-rules
publisher: MindStudio
date: 2026-07-02
type: prescribed-practice (secondary, framed as insider rules)
retrieved: 2026-07-11
---

# Summary

A "rules" checklist framed as internal-engineer practice for prompting Fable 5. Overlaps heavily with the
official docs (`001`, `002`) but stated as compact, numbered rules.

## The six rules

1. **Assign a role/professional perspective before the task** — this activates the relevant frame the model
   should reason from before it sees the actual ask.
2. **Explicitly suppress default filler** — hedging language, restating the task back, and redundant
   summaries should be turned off explicitly rather than assumed away.
3. **State the expected output scope up front** — brief vs. moderate vs. full analysis vs. comprehensive
   treatment, to avoid an ambiguous "medium" answer nobody asked for.
4. **Don't dress up simple requests as hard reasoning problems** — doing so triggers unnecessarily costly
   extended-thinking behavior better reserved for genuinely hard analytical work.
5. **Use XML tags to separate prompt components** — instructions, content, and examples kept structurally
   distinct reduces parsing ambiguity.
6. **Show, don't just describe, the desired output** — one or two concrete examples of the target
   style/format/tone remove more ambiguity than a paragraph of description.

## Relevance to this project

Rule 4 (don't over-frame simple asks as hard problems) is a useful counterbalance to the "run at higher
effort" guidance elsewhere (`001`) — the two aren't contradictory but sit on either side of the same
effort-calibration question, worth stating together in the prompting guide.
