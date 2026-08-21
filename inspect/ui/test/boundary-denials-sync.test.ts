import { describe, expect, it } from 'vitest';
import { BOUNDARY_SEAMS, DENIAL_REASONS } from '../src/api/types';

// Renamed from denial-reason-sync.test.ts (computenet-nu49): this file now
// pins TWO enums, not one, so "denial-reason-sync" under-described it. Both
// mirrors live in the same TS file and the same kernel file, so one file
// checking both is more coherent than splitting them.
//
// computenet-ssa.7: `civictech.cell.DenialReason`
// (kernel/src/main/kotlin/civictech/cell/BoundaryDenials.kt) and this
// project's mirror, `DenialReason` / `DENIAL_REASONS` in
// `src/api/types.ts`, drifted silently for three features running:
// AUTH_REQUIRED, ID_MISMATCH, MALFORMED_HELLO (computenet-ssa.2) and
// EXPIRED (computenet-ssa.4) were added to the server enum and never
// reached the UI union. Nothing caught it because no test anywhere
// compared the two (verified 2026-08-20 by `git grep -lniE 'denialreason'`
// over inspect/ui/** and any *ConformanceTest*/*DtoTest* — only types.ts
// itself matched). This test is that comparison, run from the UI side per
// this ticket's scheduling constraint (kernel/inspect-side Gradle work was
// deliberately routed elsewhere to avoid Gradle-daemon contention with a
// concurrent unit on this machine).
//
// computenet-nu49: `civictech.cell.BoundarySeam`, declared in the very same
// kernel file, mirrors the same defect mechanism one type over — measured
// in sync at the time this case was added, but with nothing pinning it
// there. Rather than write a second Kotlin parser, this generalises the
// DenialReason extractor below to take an enum name and reuses it for both
// enums.
//
// PATH DEPENDENCY, stated up front: this test locates its input by a
// relative path from this file to
// kernel/src/main/kotlin/civictech/cell/BoundaryDenials.kt. If that file is
// ever moved or renamed, Vite's `?raw` loader fails the import at collection
// time (`Failed to resolve import` / ENOENT) and this suite fails LOUDLY —
// it does not silently pass with zero constants compared. That failure mode
// is a deliberate design choice: a sync test that can no longer find its
// input must not go quiet, or it becomes worse than no test at all. If the
// kernel file moves, update the path below; this test's own failure message
// on that path already tells the next reader that this is what happened.
import kernelSource from '../../../kernel/src/main/kotlin/civictech/cell/BoundaryDenials.kt?raw';

/** Extracts the constant names of `enum class <enumName> { ... }` from Kotlin
 *  source text.
 *
 *  Deliberately does not try to parse full Kotlin — just enough to name the
 *  same set the sibling `comm` recipe would name. But it is written to
 *  **under-extract loudly rather than quietly**, which the first cut of this
 *  extractor (then specific to `DenialReason`) did not do (review of
 *  computenet-ssa.7, PR #385). That cut matched only lines of the exact form
 *  `    NAME,`, so two entirely ordinary future edits made the whole suite
 *  pass green while the two sides were drifted:
 *
 *    - appending a constant with **no trailing comma** — Kotlin's older and
 *      still-legal style — e.g. `    EXPIRED,` then `    NEW_REASON` with the
 *      brace on the next line; and
 *    - appending a constant that takes **constructor arguments**, e.g.
 *      `    NEW_REASON("x"),`. The kernel already has enums in that shape
 *      (`civictech.cell.control.Suspension`'s
 *      `DEAD_LETTERED(recoverable = false)`), so this is not hypothetical.
 *
 *  Both were measured on that branch: the constant was invisible to the
 *  regex, so `missingFromTs` was empty, the counts agreed, and the test
 *  reported 1 passed. A sync check with a silent-pass path for the single
 *  most likely edit to its input is the failure mode that ticket existed to
 *  remove, so the extraction works the other way round: every declaration
 *  line inside the enum body must be RECOGNISED, and anything it cannot
 *  parse throws instead of being skipped. Parameterising this over
 *  `enumName` (computenet-nu49) keeps that guarantee for every enum it is
 *  applied to — there is no per-enum carve-out in the recognition logic. */
function kotlinEnumConstants(source: string, enumName: string): string[] {
  // `\b` on both sides: a plain `indexOf` also matched a *renamed* enum whose
  // new name merely extends the old one (`enum class DenialReasonRenamed`),
  // so the "changed shape" guard below did not fire on it.
  const enumStart = source.search(new RegExp(`\\benum class ${enumName}\\b`));
  if (enumStart === -1) {
    throw new Error(
      `kernel source no longer contains 'enum class ${enumName}' — ` +
        'BoundaryDenials.kt has changed shape; update this test\'s extraction logic.',
    );
  }
  const braceOpen = source.indexOf('{', enumStart);
  const braceClose = source.indexOf('\n}', braceOpen);
  if (braceOpen === -1 || braceClose === -1) {
    throw new Error(`could not locate the enum body braces for ${enumName} in the kernel source.`);
  }
  let body = source.slice(braceOpen + 1, braceClose);

  // A Kotlin enum may terminate its constant list with `;` and carry member
  // declarations after it. Cut there so members are never read as constants —
  // and so they do not trip the unrecognised-line guard below.
  const semicolon = body.search(/^[ \t]*;[ \t]*$/m);
  if (semicolon !== -1) {
    body = body.slice(0, semicolon);
  }

  // Strip KDoc/block comments and line comments, leaving only declarations.
  const lines = body
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  const names: string[] = [];
  for (const line of lines) {
    // `NAME`, `NAME,`, `NAME("x")`, `NAME(a = 1),` — the shapes a constant
    // declaration can legally take on one line.
    const match = /^([A-Z][A-Z0-9_]*)\s*(\([^()]*\))?\s*,?$/.exec(line);
    if (match === null) {
      throw new Error(
        `unrecognised declaration inside 'enum class ${enumName}': ${JSON.stringify(line)} — ` +
          'this extraction refuses to skip what it cannot parse, because skipping is how a ' +
          "sync test goes green on a drifted enum. Widen the pattern (and re-check this test's " +
          'own mutation cases) rather than deleting this guard.',
      );
    }
    names.push(match[1]);
  }
  if (names.length === 0) {
    throw new Error(`extracted zero ${enumName} constants from the kernel source — extraction is stale.`);
  }
  return names;
}

/** Asserts a TS union derived from a `const` array (`(typeof ARR)[number]`)
 *  admits exactly the constant names extracted from the kernel enum of the
 *  same name — no fewer, no more, and not merely the same count (a rename
 *  that collides with an existing entry on the other side would otherwise
 *  pass the two `comm`-style checks vacuously). */
function expectUnionMatchesKernelEnum(kernelSource: string, enumName: string, tsNames: readonly string[]): void {
  const kotlinNames = kotlinEnumConstants(kernelSource, enumName);

  const kotlinSet = new Set<string>(kotlinNames);
  const tsSet = new Set<string>(tsNames);

  // comm -23: in kernel, not in TS
  const missingFromTs = kotlinNames.filter((n) => !tsSet.has(n)).sort();
  // comm -13: in TS, not in kernel
  const extraInTs = tsNames.filter((n) => !kotlinSet.has(n)).sort();

  expect(missingFromTs, `kernel ${enumName} constants absent from the TS union`).toEqual([]);
  expect(extraInTs, `TS union members absent from the kernel ${enumName} enum`).toEqual([]);

  // Also pin the count so a constant renamed to a name already present on
  // the other side (which would pass the two `comm` checks vacuously by
  // colliding with an existing entry) still shows up as a mismatch.
  expect(tsNames.length, `TS union member count (${enumName})`).toBe(kotlinNames.length);
}

describe('DenialReason sync: inspect/ui vs civictech.cell.DenialReason (computenet-ssa.7)', () => {
  it('the TS union admits every kernel constant and no more (bidirectional diff, empty both ways)', () => {
    expectUnionMatchesKernelEnum(kernelSource, 'DenialReason', DENIAL_REASONS);
  });
});

describe('BoundarySeam sync: inspect/ui vs civictech.cell.BoundarySeam (computenet-nu49)', () => {
  it('the TS union admits every kernel constant and no more (bidirectional diff, empty both ways)', () => {
    expectUnionMatchesKernelEnum(kernelSource, 'BoundarySeam', BOUNDARY_SEAMS);
  });
});
