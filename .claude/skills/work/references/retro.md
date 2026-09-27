# Session retro record

Every /work and /remediate-friction session ends by filing one retro record
with `.claude/skills/work/scripts/file-retro.sh`. That includes sessions that stopped early or did
nothing. The weekly /retrospective reads these records instead of whole
transcripts, so a record has one job: let a reader spot a recurring issue,
even a small one, without the transcript.

Keep it short and factual. Name ids, commands and numbers. "Things went
fine" tells the reader nothing; "3 of 4 reviews needed a second round" does.

## Template

Write to `<scratch>/retro.md` with exactly these headings. Put `- none` under
a heading that has nothing to report, and never drop the heading.

```markdown
## Summary
- <what the session set out to do, and what it actually did; 3-8 bullets>

## Outcomes
- closed: <ids> | parked: <ids> | filed: <ids> | PRs opened/shipped: <numbers>

## Issues
- [<tag>] <what happened, what it cost> (evidence: <id, command, PR or log path>)

## Judgment calls
- <a decision made WITHOUT parking>: <choice>, confidence <0-100>, why

## Time
- <rough split: working / waiting on CI / waiting on agents>; subagents: <n>; slot used: <m of n min>

## Handoff
- <what the next session must know that is not already on a bead>
```

## Issue tags

Pick the closest tag. Write `other:<word>` only when none fits, and the
retrospective will look at promoting that word to a tag.

| Tag | Use when |
|---|---|
| `deviation` | you did something the skill does not say, or skipped a step it does say, and why |
| `refusal` | a permission classifier, policy or tool refused an action |
| `flake` | a test or CI check failed without a change: name it and the seed |
| `misleading-instruction` | skill or AGENTS.md text sent you the wrong way |
| `tooling` | a command, script or tool failed or was missing |
| `unverified` | a claim shipped or reported without its evidence (Linux not run, gate scoped) |
| `surprise` | the repo, a bead or a doc contradicted what you were told |
| `reporting-miss` | something you or an agent reported wrongly or incompletely |
| `slow` | a step cost far more time than it should have |
| `optimization` | nothing went wrong, but you saw a cheaper, faster or simpler way |
| `none` | the only line when there were no issues |

Record small issues too. A one-off hiccup that is not worth a friction item
(work step 7) is exactly what goes here: the retrospective finds the
recurrences no single session can see. An issue that meets the friction bar
goes in both places.
