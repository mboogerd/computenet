# Wire corpus nondeterminism ledger

Mirrors `concord/corpus/DISPUTES.md`'s role (epic computenet-ncz, WIR1,
`[WIR1-C11]`): a place to be honest about a check the corpus cannot make,
rather than a place to make the corpus lie.

## What belongs here

- A vector whose bytes cannot be produced deterministically — an unordered
  collection whose JVM iteration order is not itself part of the wire
  contract, a runtime's numeric-literal rendering that another runtime may
  spell differently, or any other source of byte-level variation the codec
  does not pin — together with the reason and the variation a driver is
  expected to tolerate (`[WIR1-C11]`).
- A `[WIR1-C*]`/`[WIR1-I*]` requirement that cannot be checked honestly
  against the current corpus or driver — the epic's "Honesty ledger"
  paragraph — with the missing capability named and the check that would
  restore it.
- A registration whose discriminator cannot yet be pinned to a vector for a
  reason beyond "nobody has authored it yet" (WIR1-F1-D4) — for example, a
  fact about its wire spelling that is a prediction rather than an
  observation.

## What never belongs here

- Silent normalisation in a driver (sorting keys, reformatting numbers,
  ignoring a byte difference) to make a vector pass. Bytes are the unit
  (`[WIR1-C11]`); a driver that cannot reproduce them exactly is a driver
  defect, not a nondeterminism.
- A vector quietly weakened — a looser `decoded`, a dropped assertion, an
  `expect.reject` broadened — to route around a check that fails. That is
  filed here as a ledger entry, with the check still standing, never patched
  into the vector itself.

An entry names: the vector id or discriminator, the requirement it touches,
the reason the bytes/check cannot be pinned, the variation a conforming
driver is expected to tolerate (or "none — see Findings" when there is
nothing to tolerate yet), and the bead or disposition that owns resolving it.

## Nondeterminism

| vector / discriminator | requirement | reason | tolerated variation | bead / disposition |
|---|---|---|---|---|
| _(none yet)_ | | | | |

## Findings

- **The five primitive discriminators are `kotlin.<Type>` builtin descriptor
  names, `unverified:` on the wire.** `String`, `Long`, `Int`, `Boolean` and
  `Double` are registered against kotlinx.serialization's builtin
  serializers, which carry no `@SerialName`; their wire discriminator is the
  builtin descriptor's serial name (`kotlin.String`, `kotlin.Long`,
  `kotlin.Int`, `kotlin.Boolean`, `kotlin.Double` — observed in the constant
  pool of `kotlinx-serialization-core-jvm-1.9.0.jar`, per
  `wire/corpus/SCHEMA.md`). No test in the repo pins a primitive arg's bytes
  today (`WireCodecTest."wire bytes carry no reflection artifacts"` asserts
  only the absence of `civictech` and `propagate`, not the presence of a
  `kotlin.` discriminator), so the `kotlin.` prefix is a prediction, not an
  observation, until a vector pins it.

  Whether the `kotlin.` prefix itself counts as a reflection artifact under
  `[WIR1-I18]` is explicitly open, and is owned by the first feature to pin a
  primitive discriminator to a vector: `computenet-ncz.2` for `kotlin.Double`
  (the determinism-guard vector), `computenet-ncz.3` for the other four. No
  bead is filed for this from this task (WIR1-F1-D4); this entry records the
  fact and its ownership, and proposes no `@SerialName` change.
