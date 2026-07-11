# Progressive Disclosure (Nielsen Norman Group)

- **Type**: Practitioner UX guideline (Nielsen Norman Group)
- **Source**: https://www.nngroup.com/articles/progressive-disclosure/
- **Accessed**: 2026-07-11

## Definition

> "Initially, show users **only a few** of the most important options. Offer a **larger set** of specialized options upon request."

Classic example: a print dialog showing basic options up front, with "Advanced" revealing the rest.

## When it helps

Applications and complex sites where there's real tension between power (many features/settings) and simplicity (most users need only a few of them, most of the time). It's explicitly a technique for reconciling those two audiences in one UI rather than shipping two separate UIs.

## Why it works

Improves three usability dimensions simultaneously:
- **Learnability** — novices aren't shown things they don't yet need.
- **Efficiency** — experts aren't slowed down by clutter from features they rarely touch.
- **Error rate** — fewer visible controls means fewer chances to hit the wrong one.

## Risks / pitfalls

1. **Getting the split wrong** — if a feature that belongs "primary" gets buried, or vice versa, the technique backfires; requires task analysis / usability testing, not guesswork, to decide the split.
2. **Unclear navigation between levels** — "It must be **obvious how users progress** from the primary to the secondary disclosure levels." A hidden or ambiguously-labeled path to "more" defeats the purpose.

## Best practices

- Base the primary/secondary split on task analysis and testing, not intuition.
- Use clear, descriptive (not clever) labels for what "more" reveals.
- **Limit to two disclosure levels** — more than that creates its own navigation problem.
- Group advanced features into logical chunks rather than a flat long list.
- Avoid scattering multiple separate "show more" affordances on the same primary view.

## Relevance

This is the direct UX-practice counterpart to Shneiderman's "overview first, zoom and filter, details-on-demand" (07) applied outside the visualization-research literature — i.e., the same idea has been independently validated by two different disciplines (academic InfoVis and applied UX practice). The "two levels max" and "obvious path to more" rules are concrete, checkable constraints for an argumentation graph that has to stay legible from a handful of claims up to hundreds. See index: progressive-disclosure, overview-first, collapse-expand, scale-to-large-graphs.
