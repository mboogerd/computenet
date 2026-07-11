# Argdown: Design Philosophy (Creating Argument Maps)

- **Type**: Tool documentation
- **Source**: https://argdown.org/ and https://argdown.org/guide/creating-argument-maps.html
- **Accessed**: 2026-07-11

## Text-first authoring

Argdown's core design bet is a plain-text, Markdown-like notation for authoring argument structure ("Writing lists of pros & cons in Argdown is as simple as writing a twitter message"), with the visual map generated *from* the text rather than built by direct graph manipulation.

## Explicit separation of data from view

> "Argdown is also designed to be used as a pure data source that is completely 'decoupled' from how the data is visualized."

This means the same underlying argument data can drive multiple different visual outputs: "You can produce very different 'views' from the same data within an Argdown document by creating different selections of elements and regroup or recolorize them." The map is a *projection* of the model, not the model itself.

## Usability-first stance on authoring

> "Argdown is designed so that you can start writing without having to spend too much thought about the appearance of your argument map."

Styling/appearance is auto-derived from structure by default, with manual overrides available but optional — the design goal is to keep the cost of *creating* a well-formed argument low, deferring visual polish to be automatic rather than author-driven.

## Relevance

This is a genuinely different design axis from every graph-visualization-vendor source in this set (which all assume direct-manipulation graph editing as the authoring model). It's directly relevant to a ComputeNet frontend because the backend is a **dataflow graph** — the same "data model decoupled from its visual projection" idea is already how ComputeNet's Cells/Ports/Links work architecturally (see doc/spec/00-foundations/01-vision.md), so a UI that treats the rendered graph as one of *several possible views* over the same underlying data (rather than the single source of truth) is consistent with both this reference and the backend's own design philosophy. See index: text-vs-direct-manipulation-authoring, data-view-separation, multiple-view-modes.
