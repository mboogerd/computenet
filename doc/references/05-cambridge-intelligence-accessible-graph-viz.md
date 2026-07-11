# How to Build Accessible Graph Visualization Tools

- **Type**: Vendor best-practices article
- **Source**: https://cambridge-intelligence.com/blog/build-accessible-data-visualization-apps-with-keylines/
- **Accessed**: 2026-07-11

## Keyboard navigation
Full mouse-free operation should be possible: select-all, arrow-key movement of selected items, and app-specific shortcuts for common actions (search, pan a timeline, etc.).

## Screen reader support
- Provide keyboard-navigable structure plus text equivalents of the chart.
- "use ARIA labels to help screen readers understand the chart structure," or mark the canvas `aria-hidden` and expose an equivalent text/table view alongside it.
- Audio/text descriptions for nodes and links, not just visual encoding.

## Color and contrast
- Offer multiple palettes, including colorblind-safe ones; test with a contrast checker.
- Core rule: **"Don't rely on color alone"** — pair color with shape, size, border style, icon, or position so the same information survives a colorblind or grayscale rendering.

## Text and icon clarity
- Text and icons must stay legible at working zoom levels; hide labels rather than render illegible micro-text.
- Icons need accompanying labels unless truly universal (e.g., a magnifying glass for search).

## Motion and animation
- Provide an "off" switch for animation (photosensitivity/vestibular concerns).
- Flashing content must respect W3C seizure-safety thresholds.
- Balance: animation helps low-vision users track change, but the same animation can be a hazard for others — hence the toggle rather than a single fixed choice.

## Relevance

An argumentation-graph frontend is a public-facing deliberation/reasoning tool, which raises the accessibility bar (broad audience, civic/educational use cases per the Deliberatorium and Kialo material in this set). "Don't rely on color alone" is especially binding once color is used to encode support/attack relations — see index: accessibility, edge-encoding.
