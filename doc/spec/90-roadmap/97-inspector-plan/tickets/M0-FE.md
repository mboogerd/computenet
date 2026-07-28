# M0-FE — Inspector UI scaffold: live topology canvas

**Status**: Implemented — merged to main (see `90-progress-log.md`).

Model: `claude-sonnet-5` (effort xhigh) · Track: frontend · Depends: — ·
Parallel with: M0-BE (file-disjoint — you own `inspect/ui/**` only)

## Context

Read `doc/spec/90-roadmap/97-inspector-plan/10-target-v3.md` (product target)
and `20-api-contract.md` (the API you consume — binding; code against the
checked-in fixture until the server exists). Study `demo/agora/ui/` before
writing code: its README documents the architecture you must copy — a
framework-free core (`src/api`, `src/sync`, `src/layout`) with SolidJS
confined to `src/solid`/`src/components`; one sync seam (`applySnapshot`);
diff-once with object-identity reuse for unchanged records; structural changes
(add/remove/relink) bump a `structuralVersion` that keys layout, while value
changes only restyle. That structure-vs-value split is what keeps the canvas
stable under live updates — it is a requirement, not a suggestion.

## Implement

1. **Scaffold `inspect/ui/`**: SolidJS + Vite + TypeScript, Vitest for units —
   same toolchain and versions as `demo/agora/ui/package.json`. npm only; do
   NOT touch Gradle files. Vite dev proxy for `/api/inspect` →
   `INSPECT_BACKEND ?? http://localhost:7071`.
2. **Sync layer** (`src/sync`): fetch `TopologySnapshot`, then consume the SSE
   `events` stream applying `topology.node` / `topology.link` / `lifecycle`
   with seq filtering; on gap, disconnect, or drop marker → refetch snapshot.
   Pure TypeScript, unit-tested against `fixtures/topology.json` (which you
   create from the contract's skillmatch shape — 13 cells, 3 hosts).
3. **Layout** (`src/layout`): deterministic layered (Sugiyama-style) layout —
   sources left, sinks right; stable under insertion (a new node must not
   reshuffle unrelated nodes; re-layout only on structuralVersion change).
   Hand-roll or crib from `demo/agora/ui/src/layout`; no new runtime deps.
4. **Canvas** (`src/components`): SVG node-link rendering per the v2 mockup's
   visual language (see `10-target-v3.md` for the artifact link): node card
   with name + type, color chip P/B/S with letter glyph (never color alone),
   manifest badges (D/GF/R/PT), port dots; solid CONSUME vs dashed OBSERVE
   edges; `fused` edges double-stroked when `fused === true`; SUSPENDED nodes
   ghosted (reduced opacity, dashed border). Click/keyboard selection with a
   selected-node highlight (selection state is wired now; the detail panel
   content arrives in M1 — render an empty right panel with the node's name).
5. **Shell**: header (host name, connection status: live / reconnecting), the
   toggle bar with all five toggles from `10-target-v3.md` rendered but only
   functional ones enabled (none functional in M0 — disabled with tooltips
   naming the milestone); dark + light theme via `prefers-color-scheme`.

## Exclusions

No detail-panel content, hulls, errors, flow, state chips, or navigator (M1+).
No optimistic writes — the inspector is read-only. No CSS framework, router,
or state library (agora precedent).

## Tests / acceptance

- Vitest: sync seam (snapshot apply, delta apply, seq-gap refetch trigger),
  diff identity-reuse, layout stability under single-node insertion.
- `npm run build` and `npm test` green.
- Manual (documented with a screenshot in the report): `npm run dev` against
  the fixture-serving mock (add a tiny `npm run mock` static server for the
  fixture) shows the skillmatch graph; selection highlights; theme switch.

## Report

Tests run, screenshot(s), any contract ambiguity (flag to orchestrator — do
not edit the contract), deviations from the agora architecture and why.
