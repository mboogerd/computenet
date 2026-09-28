import { createMemo, For, Show } from 'solid-js';
import type { GraphDto } from '../api/types';
import { pct, spreadOf } from '../util/format';

/** One claim as the "where the rules disagree" view shows it. */
export interface Disagreement {
  ref: string;
  text: string;
  low: number;
  high: number;
  /** high - low, read through spreadOf(); what the list is sorted by. */
  width: number;
}

/**
 * The question's claims ordered by spread width, widest first, top 10. A
 * claim with no spread fields at all (spreadLow and spreadHigh both absent —
 * a single credence layer, nothing to disagree about) is excluded, not
 * scored as a zero-width tie.
 */
export function disagreementsOf(graph: GraphDto, root: string): Disagreement[] {
  return graph.nodes
    .filter((n) => n.root === root && n.kind === 'CLAIM' && n.spreadLow !== undefined && n.spreadHigh !== undefined)
    .map((n) => {
      const { low, high } = spreadOf(n);
      return { ref: n.ref, text: n.text ?? '', low, high, width: high - low };
    })
    .sort((a, b) => b.width - a.width)
    .slice(0, 10);
}

/**
 * "Where the rules disagree": the question's claims with the widest spread
 * between the credence rules, widest first. Selecting an entry focuses that
 * claim in the tree the same way the cruxes panel does (CruxesPanel.tsx):
 * a `data-ref` marker, nothing more.
 */
export function DisagreementPanel(props: { graph: GraphDto; root: string }) {
  const rows = createMemo(() => disagreementsOf(props.graph, props.root));
  return (
    <Show when={rows().length > 0}>
      <section class="disagreement" aria-label="Where the rules disagree">
        <h2 class="disagreement__title">Where the rules disagree</h2>
        <ol class="disagreement__list">
          <For each={rows()}>
            {(r) => (
              <li class="disagree" data-ref={r.ref}>
                <span class="disagree__text">{r.text}</span>
                <span class="disagree__meta" title="Lowest and highest credence over every rule">
                  {pct(r.low)}–{pct(r.high)}
                </span>
              </li>
            )}
          </For>
        </ol>
      </section>
    </Show>
  );
}
