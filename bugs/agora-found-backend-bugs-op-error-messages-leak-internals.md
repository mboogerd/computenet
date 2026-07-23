# agora backend bug — /op 400 bodies leak internal representations

**Severity**: Low (cosmetic / DX; these strings are shown verbatim to end users)
**Component**: `demo/agora` — `civictech.agora.AgoraService` require-messages +
`AgoraApp.handleOp` error passthrough.
**Found**: 2026-07-23, via `POST /op` probing.

## Observation

`POST /op` returns the raw exception message as the 400 body, and the UI surfaces
that body verbatim in an error toast (`api/commands.ts` throws `res.text()`;
`solid/graph.ts` `notify()` renders it). Several messages expose internals:

| input | 400 body returned |
|---|---|
| `action=edge` with a bad source UUID | `unknown source CellRef(id=00000000-0000-0000-0000-000000000000, instanceId=0)` |
| `action=stance … value=abc`          | `For input string: "abc"` (raw Java `NumberFormatException`) |

The first leaks `CellRef.toString()` (including `instanceId`); the second leaks a
JDK exception string. Both reach the user as a toast like
*"Stance failed: For input string: 'abc'"*.

## Expectation

User-facing command errors should be clean, stable, human-readable sentences that
name the problem in domain terms (e.g. *"No such node."*, *"Stance must be a number
between 0 and 1."*). Internal identifiers (`CellRef(... instanceId=0)`) and
provider exception text should not be part of the contract the UI displays.

## Root-cause analysis

- `require(source in nodes) { "unknown source $source" }` interpolates a `CellRef`,
  whose `toString()` is the debug form.
- `value?.toDouble()` in `AgoraApp.handleOp` throws `NumberFormatException` (a
  subclass of `IllegalArgumentException`), so it is caught by the generic
  `catch (e: IllegalArgumentException) { respond(400, e.message …) }` and its raw
  message is passed straight through.

## Solution direction

- Interpolate `source.id` (the UUID) rather than the whole `CellRef`, or better a
  fixed phrase: `require(source in nodes) { "unknown source node" }`.
- Parse the stance value explicitly and translate the failure:
  `params["value"]?.takeIf { it.isNotBlank() }?.toDoubleOrNull()` and, on null,
  `return exchange.respond(400, "stance must be a number in [0,1]")`.

Purely a message-hygiene change; no behavior/wire-format impact. Nice to pair with
the two `createEdge` validation fixes since it touches the same error surface.
