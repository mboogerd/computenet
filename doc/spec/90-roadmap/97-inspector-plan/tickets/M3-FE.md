# M3-FE — Flow toggle: pulses, rates, flow subsection

Model: `claude-sonnet-5` (effort xhigh) · Track: frontend · Depends: M2-EVAL
merged · Parallel with: M3-BE (code against contract + a `flow.rates` fixture
you create)

Files owned: `inspect/ui/**` only.

## Context

`20-api-contract.md` §flow.rates; `10-target-v3.md` (Flow toggle row, detail
subsection 3). The v2 mockup's Flow perspective shows the visual language:
amber pulses travelling along edges, rate labels at edge midpoints, fused
edges thick/static with a "fused" label, hover tooltip with last wave + hop.

## Implement (prescriptive — stay within this scope)

1. **Flow store**: apply `flow.rates` batches; per-edge rate, lastWave, hop;
   an edge absent from a batch decays to zero after 2 missed windows. Unit
   tests against `fixtures/flow-rates.json` (create per contract).
2. **Flow toggle** (canvas overlay): rate label per active edge; pulse
   animation along the edge path with pulse count/speed stepped by rate
   bands (define 3–4 bands; do NOT animate per-message). Fused edges: no
   pulses, "fused" label, thick stroke — when the toggle is on, make the
   fused state visibly explicit (tooltip: "fused — no observable messages").
   Respect `prefers-reduced-motion`: static intensity styling instead of
   pulses. All flow rendering is value-change (restyle) — never re-layout.
3. **Edge hover tooltip** (only when toggle on): route, last wave
   `(source · counter)`, hop, rate.
4. **Flow subsection** (detail panel, replaces placeholder): per-port table
   for the selected cell — direction, rate (sum of that port's edges),
   last wave; fused ports labeled.
5. **State chips follow-through**: if M1 left canvas state chips
   unimplemented, implement them now behind the State toggle (driven by
   `state.summary` events only). If already done, skip — say so in the report.

## Exclusions

No per-message animation or ticker (deferred; not in v3 v1 scope). No
backend changes. No new dependencies.

## Tests / acceptance

- Vitest: store decay logic, band mapping, fused gating, reduced-motion path.
- `npm test` / `npm run build` green.
- Manual with screenshots (mock or real backend): toggle on shows pulses and
  rates; hover tooltip correct; fused edge distinct; reduced-motion mode.
