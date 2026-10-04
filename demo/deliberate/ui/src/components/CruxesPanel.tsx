import { createMemo, For, Show } from 'solid-js';
import type { GraphDto, NodeDto } from '../api/types';
import { num, pct } from '../util/format';
import { useByRef } from '../util/byRef';

/** One crux as the panel shows it. */
export interface Crux {
  ref: string;
  text: string;
  /** Signed exact secant R(node=1) − R(node=0), called the node's sway. */
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
 * Enter or Space activates an entry the same way a click does. The entries
 * are real `<button>`s, so a browser already does this on its own; the
 * explicit handler (shared with DisagreementPanel.tsx) makes the behavior
 * the acceptance criteria ask for — not just a `<button>`'s default —
 * something a test can drive directly, and keeps the two panels identical.
 */
export function onActivateKey(fn: () => void): (ev: KeyboardEvent) => void {
  return (ev) => {
    if (ev.key === 'Enter' || ev.key === ' ') {
      ev.preventDefault();
      fn();
    }
  };
}

/**
 * Model C, "what would change the answer": the question's top cruxes — the
 * claims and links whose settling has the highest exact q-weighted expected
 * root movement. Their signed exact secant is shown as sway. Nothing is shown
 * until the backend names one. Activating an entry (click, or Enter/Space when
 * focused) focuses that claim or link in the tree — computenet-lmfg8,
 * ClaimCard.focusInTree, shared with DisagreementPanel.
 */
export function CruxesPanel(props: { graph: GraphDto; root: string; onFocus?: (ref: string) => void }) {
  const cruxes = createMemo(() => cruxesOf(props.graph, props.root));
  const { refs, byRef } = useByRef(cruxes);
  return (
    <Show when={cruxes().length > 0}>
      <section class="cruxes" aria-label="What would change the answer">
        <h2 class="cruxes__title">What would change the answer</h2>
        <ol class="cruxes__list">
          <For each={refs()}>
            {(ref) => (
              <Show when={byRef(ref)}>
                {(c) => (
                  <li class="crux">
                    <button
                      type="button"
                      class="crux__btn"
                      data-ref={c().ref}
                      onClick={() => props.onFocus?.(c().ref)}
                      onKeyDown={onActivateKey(() => props.onFocus?.(c().ref))}
                    >
                      <span class="crux__text">
                        <Show when={c().link}>
                          <span class="crux__kind">link</span>
                        </Show>
                        {c().text}
                      </span>
                      <span
                        class="crux__meta"
                        title="Sway: the signed exact answer change between this node resolving false and true; certainty: how settled it is now"
                      >
                        sway {num(c().sensitivity === undefined ? undefined : Math.abs(c().sensitivity as number))} · {pct(c().p)} {c().link ? 'strong' : 'plausible'}
                        <Show when={pullOf(c().sensitivity)}>{(w) => <> · {w()}</>}</Show>
                      </span>
                    </button>
                  </li>
                )}
              </Show>
            )}
          </For>
        </ol>
      </section>
    </Show>
  );
}
