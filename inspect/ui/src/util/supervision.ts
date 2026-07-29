import type { DeadLetterEntry, RestartEntry } from '../api/types';

/** V3-FE ticket Problem #2 / Solution direction §3c: the per-cell "supervision
 *  timeline" that replaces the flat restart list (`DetailPanel.tsx`'s old
 *  "Restart history" `<ul>`). Each restart contributes an ordered causal
 *  group — crash, then restart, then re-baseline — newest restart first. */
export type SupervisionStepKind = 'crash' | 'restart' | 'reBaseline';

/** One row of the timeline. Every rendering decision (which steps exist,
 *  their order, their glyph/label text) is made in {@link buildSupervisionTimeline}
 *  — the component "renders that list and nothing more" (ticket). */
export interface SupervisionStep {
  /** Stable, unique across the whole returned list — usable directly as a
   *  Solid `<For>` key. */
  key: string;
  kind: SupervisionStepKind;
  glyph: string;
  label: string;
  atMs: number;
  /** The matching dead letter's `description`, when one was found at the
   *  same (ref, atMs, cause) as this crash step — extra context for a title
   *  tooltip. `null` for a restart/re-baseline step, or when no match was
   *  found; never used to add or omit a step on its own. */
  detail: string | null;
}

/** Pure builder — no DOM, no Solid. Takes one cell's `RestartEntry[]` and
 *  `DeadLetterEntry[]` (both already available from `ErrorStore`, indexed by
 *  ref — no new endpoint, no new fetch) and returns the ordered timeline.
 *
 *  Rules (V3-FE ticket acceptance criteria):
 *  - crash -> restart -> re-baseline, in that order, per restart.
 *  - Restarts are ordered newest-first (by `atMs`, ties broken by
 *    `generation` descending — a restart's own identity when two land in the
 *    same millisecond).
 *  - The crash step is omitted when `cause` is null (nothing observed to
 *    report); the re-baseline step is omitted when `reBaselineAtMs` is null.
 *    Neither omission is ever replaced by a step asserting the negative
 *    ("no crash", "no re-baseline") — `null` means *not observed*, not "did
 *    not happen".
 *  - The restart step itself is never omitted — a generation bump without a
 *    known cause or a known re-baseline is still a restart that happened. */
export function buildSupervisionTimeline(
  restarts: readonly RestartEntry[],
  deadLetters: readonly DeadLetterEntry[],
): readonly SupervisionStep[] {
  const sorted = [...restarts].sort((a, b) => b.atMs - a.atMs || b.generation - a.generation);
  const steps: SupervisionStep[] = [];

  for (const r of sorted) {
    const id = `${r.ref}:${r.generation}:${r.atMs}`;

    if (r.cause !== null && r.causeAtMs !== null) {
      const match = deadLetters.find((dl) => dl.ref === r.ref && dl.atMs === r.causeAtMs && dl.cause === r.cause);
      steps.push({
        key: `${id}-crash`,
        kind: 'crash',
        glyph: '✕',
        label: `crash — ${r.cause}`,
        atMs: r.causeAtMs,
        detail: match?.description ?? null,
      });
    }

    steps.push({
      key: `${id}-restart`,
      kind: 'restart',
      glyph: '↻',
      label: `restart — generation ${r.generation}`,
      atMs: r.atMs,
      detail: null,
    });

    if (r.reBaselineAtMs !== null) {
      steps.push({
        key: `${id}-reBaseline`,
        kind: 'reBaseline',
        glyph: '⟳',
        label: 're-baseline',
        atMs: r.reBaselineAtMs,
        detail: null,
      });
    }
  }

  return steps;
}
