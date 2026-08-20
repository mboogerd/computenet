import { describe, expect, it } from 'vitest';
import { DENIAL_REASONS } from '../src/api/types';

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

/** Extracts the `enum class DenialReason { ... }` constant names, in the
 *  same style as the description's own mechanical reproduction: each
 *  constant is a standalone line of the form `    NAME,` (4-space indent,
 *  a comment or blank line elsewhere is not matched). Deliberately does not
 *  try to parse full Kotlin — just enough to name the same set the
 *  description's `comm` recipe names. */
function kotlinDenialReasonConstants(source: string): string[] {
  const enumStart = source.indexOf('enum class DenialReason');
  if (enumStart === -1) {
    throw new Error(
      "kernel source no longer contains 'enum class DenialReason' — " +
        'BoundaryDenials.kt has changed shape; update this test\'s extraction logic.',
    );
  }
  const braceOpen = source.indexOf('{', enumStart);
  const braceClose = source.indexOf('\n}', braceOpen);
  if (braceOpen === -1 || braceClose === -1) {
    throw new Error('could not locate the enum body braces for DenialReason in the kernel source.');
  }
  const body = source.slice(braceOpen + 1, braceClose);
  const names = [...body.matchAll(/^\s{4}([A-Z_]+),$/gm)].map((m) => m[1]);
  if (names.length === 0) {
    throw new Error('extracted zero DenialReason constants from the kernel source — extraction regex is stale.');
  }
  return names;
}

describe('DenialReason sync: inspect/ui vs civictech.cell.DenialReason (computenet-ssa.7)', () => {
  it('the TS union admits every kernel constant and no more (bidirectional diff, empty both ways)', () => {
    const kotlinNames = kotlinDenialReasonConstants(kernelSource);
    const tsNames: string[] = [...DENIAL_REASONS];

    const kotlinSet = new Set<string>(kotlinNames);
    const tsSet = new Set<string>(tsNames);

    // comm -23: in kernel, not in TS
    const missingFromTs = kotlinNames.filter((n) => !tsSet.has(n)).sort();
    // comm -13: in TS, not in kernel
    const extraInTs = tsNames.filter((n) => !kotlinSet.has(n)).sort();

    expect(missingFromTs, 'kernel constants absent from the TS union').toEqual([]);
    expect(extraInTs, 'TS union members absent from the kernel enum').toEqual([]);

    // Also pin the count so a constant renamed to a name already present on
    // the other side (which would pass the two `comm` checks vacuously by
    // colliding with an existing entry) still shows up as a mismatch.
    expect(tsNames.length, 'TS union member count').toBe(kotlinNames.length);
  });
});
