import { createMemo, For, Show } from 'solid-js';
import type { GraphDto, NodeDto } from '../api/types';
import { num, pct } from '../util/format';

/** One crux as the panel shows it. */
export interface Crux {
  ref: string;
  text: string;
  /** d answer / d node: its sign says which way it pulls. */
  sensitivity?: number;
  /** Its plausibility; for a link, its strength. */
  p?: number;
  link: boolean;
}

/** The question's cruxes (QuestionDto.cruxes, best first), resolved to their nodes. */
export function cruxesOf(graph: GraphDto, root: string): Crux[] {
  const q = graph.questions.find((x) => x.root === root);
  const byRef = new Map<string, NodeDto>(graph.nodes.map((n) => [n.ref, n]));
  return (q?.cruxes ?? []).flatMap((ref) => {
    const n = byRef.get(ref);
    if (n === undefined) return [];
    const link = n.kind === 'EDGE';
    return [{ ref, text: n.text ?? '', sensitivity: n.sensitivity, p: link ? n.strength : n.plausibility, link }];
  });
}

/** Which way settling it would move the answer, in words. */
export function pullOf(sensitivity: number | undefined): string {
  if (sensitivity === undefined || sensitivity === 0) return '';
  return sensitivity > 0 ? 'if it holds, the answer rises' : 'if it holds, the answer falls';
}

/**
 * Model C, "what would change the answer": the question's top cruxes — the
 * claims and links whose settling could move the answer most, |sensitivity| ×
 * 4·p·(1 − p). Nothing is shown until the backend names one.
 */
export function CruxesPanel(props: { graph: GraphDto; root: string }) {
  const cruxes = createMemo(() => cruxesOf(props.graph, props.root));
  return (
    <Show when={cruxes().length > 0}>
      <section class="cruxes" aria-label="What would change the answer">
        <h2 class="cruxes__title">What would change the answer</h2>
        <ol class="cruxes__list">
          <For each={cruxes()}>
            {(c) => (
              <li class="crux" data-ref={c.ref}>
                <span class="crux__text">
                  <Show when={c.link}>
                    <span class="crux__kind">link</span>
                  </Show>
                  {c.text}
                </span>
                <span
                  class="crux__meta"
                  title="Sway: how far the answer moves per unit change in this claim (d answer / d claim); certainty: how settled it is now"
                >
                  sway {num(c.sensitivity === undefined ? undefined : Math.abs(c.sensitivity))} · {pct(c.p)} {c.link ? 'strong' : 'plausible'}
                  <Show when={pullOf(c.sensitivity)}>{(w) => <> · {w()}</>}</Show>
                </span>
              </li>
            )}
          </For>
        </ol>
      </section>
    </Show>
  );
}
