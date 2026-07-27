# M2-FE — Errors toggle + errors subsection

Model: `claude-sonnet-5` (effort xhigh) · Track: frontend · Depends: M1-EVAL
merged · Parallel with: M2-BE (code against contract + fixtures)

Files owned: `inspect/ui/**` only.

## Context

`20-api-contract.md` §ErrorSnapshot + §error.* events; `10-target-v3.md`
(Errors toggle row, detail subsection 4). The v2 mockup's Errors perspective
shows the visual language: red count badge on erring nodes, amber
"▮ n parked" pill at edge midpoints, red-tinted node border.

## Implement (prescriptive — stay within this scope)

1. **Error store** (`src/sync`): fetch `ErrorSnapshot` on connect; apply
   `error.deadLetter` / `error.parked` / `error.restart` events; index by ref.
   Pure TS + unit tests against new fixtures (`fixtures/errors.json` + one
   event sample per kind, conforming to the contract).
2. **Errors toggle** (canvas overlay): when on — red badge with count on
   cells having dead letters or restarts; amber parked pill on the inbound
   edge(s) of cells with parked counts (aggregate per edge target port when
   the contract's parked rows name a port that maps to an edge); erring cells
   get the red border treatment. When off, none of it renders. Badges are
   value-changes (restyle), never structural (no re-layout).
3. **Errors subsection** (detail panel, replaces the M1 placeholder): for the
   selected cell — dead-letter cards (cause bold in red, description,
   wave stamp, timestamp), parked rows, restart history with generation; the
   "No local errors" placeholder otherwise.
4. **Header counters**: small counter strip (dead / parked / restarts) in the
   shell header, always visible, from the store totals — doubles as the
   affordance to switch the toggle on.

## Exclusions

No flow. No new endpoints. No filtering/search of errors (navigator handles
"problems" in M4). Do not restructure the store architecture.

## Tests / acceptance

- Vitest: store apply logic per event kind (incl. parked `count: 0` clear),
  badge/pill derivation per fixture, toggle on/off render gating.
- `npm test` / `npm run build` green.
- Manual with screenshots: toggle on shows badges/pills on a demo run with
  induced errors (coordinate with M2-BE's test recipe via the orchestrator if
  needed, or use the mock server + fixtures for the screenshot and say so).
