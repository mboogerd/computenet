# M4-FE — Navigator: cards, constellation, name/problems search

Model: `claude-sonnet-5` (effort xhigh) · Track: frontend · Depends: M3-EVAL
merged · Parallel with: M4-BE (code against contract + fixtures)

Files owned: `inspect/ui/**` only.

## Context

`10-target-v3.md` §Navigator; `20-api-contract.md` §GraphList, §SearchResult,
§graphs.changed, and the new `?graph=` topology filter. The v2 mockup's home
screen (artifact link in `10-target-v3.md`) is the visual reference: left
rail with search + graph cards, main area with constellation thumbnails.

## Implement (prescriptive — stay within this scope)

1. **Routing**: two screens — Home (navigator) and Graph (existing canvas),
   with graph id + selected ref + toggle set encoded in the URL hash (no
   router library; hash-parse like agora does). Entering a graph fetches
   filtered topology; Back returns to Home.
2. **Graph cards**: name (or the `g-…` id styled as "unnamed", dashed border
   per the v2 mockup), cell/host counts, health pills (n dead / n parked /
   hot). Live-refresh on `graphs.changed`.
3. **Constellation**: structure-only SVG thumbnails per component (reuse the
   layout module at thumbnail scale; dots + faint edges, no labels beyond the
   card header), health-tinted. Click-through to the Graph screen.
4. **Search**: input + mode chips (name / problems / data). Name: as-you-type
   against `/search?mode=name`. Problems: list on chip select; clicking a hit
   opens the graph with the Errors toggle forced on. Data: chip disabled with
   tooltip "arrives in M5" (the BE returns 501).
5. **Fixtures**: `fixtures/graphs.json`, `fixtures/search-*.json` per
   contract.

## Exclusions

Cold-graph UX, data search, network hulls (M5). No router/state libraries.

## Tests / acceptance

- Vitest: hash round-trip (graph/ref/toggles), card health derivation,
  graphs.changed refetch, search-mode gating.
- `npm test` / `npm run build` green.
- Manual with screenshots: Home with ≥2 components (one unnamed), thumbnail
  click-through preserves toggles, problems-hit opens with Errors toggle on.
