# Interactive Dynamics for Visual Analysis

- **Type**: Academic paper / practitioner survey (Jeffrey Heer & Ben Shneiderman, CACM 2012)
- **Source**: https://homes.cs.washington.edu/~jheer/files/idfva-draft.pdf
- **Accessed**: 2026-07-11

## Core principle

> **"Visual analytics tools must support the fluent and flexible use of visualizations at rates resonant with the pace of human thought."**

Interaction latency itself is treated as a design variable, not just a nice-to-have: sluggish interaction breaks the analytical "flow" the same way a laggy text editor breaks writing flow.

## Twelve-technique taxonomy in three groups

**Data & view specification** (what data appears, how it's rendered)
- *Visualize* — choose the visualization/encoding (chart typologies, drag-and-drop).
- *Filter* — dynamic-query widgets (sliders, checkboxes, search).
- *Sort* — order/seriate records to expose pattern.
- *Derive* — compute new attributes from the raw data.

**View manipulation** (interacting with an already-rendered visualization)
- *Select* — click/lasso, item-based or query-based.
- *Navigate* — pan/zoom/focus+context, per the "overview first, zoom+filter, details-on-demand" pattern (directly citing Shneiderman's mantra).
- *Coordinate* — linked/brushed selection across multiple simultaneous views.
- *Organize* — arrange multiple views/dashboards.

**Analysis process & provenance**
- *Record* — undo/redo, visual history of the analysis path.
- *Annotate* — attach text/graphical notes, including notes tied to a specific selection.
- *Share* — export, bookmark app state, publish an interactive view.
- *Guide* — structure the workflow for common tasks; narrative/guided visualization.

## Relevance

The "process & provenance" group (Record/Annotate/Share) is under-represented in most graph-viz vendor material but is central to an *argumentation* tool specifically — being able to annotate a claim, bookmark/share a specific state of the graph ("look at this sub-debate"), and see history of how the graph evolved (who added what) is closer to the actual value proposition than raw visual exploration alone. See index: annotation, sharing-state, provenance, interaction-primitives.
