import { createMemo, createSignal, For, Index, Show } from 'solid-js';
import type { FramingDto, PositionDto } from '../api/types';
import type { TreeNode } from '../tree/buildTree';
import {
  bandCaption,
  pct,
  phaseOf,
  priorDecidesText,
  priorText,
  readingsLine,
  shown,
  STATUS_LABEL,
  statusHint,
} from '../util/format';
import { ClaimCard, Facts, indexTree, SpreadBand, useChildRefs, type Selection, type TreeIndex } from './ClaimCard';
import { OverrideControl } from './OverrideControl';

/**
 * Model A, in the hero: the framing line ("depends on what you mean by
 * sleep" / "depends on the reading" / "several possible answers") and, for
 * POSITIONS, the distribution over positions — one row per position, in
 * `framing.positions` order, with its share as a percentage and a
 * proportional bar. Replaces the question's yes/no gauge.
 */
export function FramingSummary(props: { framing: FramingDto }) {
  const readings = () => (props.framing.mode === 'READINGS' ? props.framing.positions : []);
  const credences = () => readings().map((p) => p.credence ?? 0.5);
  const lo = () => Math.min(...credences());
  const hi = () => Math.max(...credences());
  return (
    <div class="framing" aria-label="Framing">
      <p class="framing__line">{readingsLine(props.framing)}</p>
      {/* computenet-3z7w5: every reading on one no–yes track — a neutral band
          over their spread and a numbered marker each; the legend carries the
          full text. <Index> keeps each marker and row in place across frames. */}
      <Show when={readings().length > 0}>
        <div class="gauge spread" aria-hidden="true">
          <span class="gauge__con">no</span>
          <span class="spread__track">
            <span class="spread__band" style={{ left: `${lo() * 100}%`, width: `${(hi() - lo()) * 100}%` }} />
            <span class="gauge__mid" />
            <Index each={readings()}>
              {(p, i) => (
                <span class="spread__mark" style={{ left: `${(p().credence ?? 0.5) * 100}%` }} title={`${p().text} · ${pct(p().credence)}`}>
                  {i + 1}
                </span>
              )}
            </Index>
          </span>
          <span class="gauge__pro">yes</span>
        </div>
        <ol class="spread__legend">
          <Index each={readings()}>
            {(p, i) => (
              <li class="spread__row">
                <span class="spread__n" aria-hidden="true">{i + 1}</span>
                <span class="spread__text">{p().text}</span>
                <span class="spread__pct">{pct(p().credence)}</span>
              </li>
            )}
          </Index>
        </ol>
      </Show>
      <Show when={props.framing.mode === 'POSITIONS'}>
        <ul class="framing__list">
          <For each={props.framing.positions}>
            {(p) => (
              <li class="framing__row">
                <span class="framing__text">{p.text}</span>
                <span class="framing__bar" aria-hidden="true">
                  <span class="framing__bar-fill" style={{ transform: `scaleX(${p.share ?? 0})` }} />
                </span>
                <span class="framing__pct">{pct(p.share)}</span>
              </li>
            )}
          </For>
        </ul>
      </Show>
    </div>
  );
}

/**
 * Model A: one reading or position, explored as a root of its own beneath
 * the framed question — its text as a heading, its own credence gauge (the
 * question's `gauge__*` markup and research-view band, driven by its own
 * NodeDto), its own model D caption built from the `PositionDto`, a
 * status/override control keyed by the position's ref, a Facts toggle, and
 * its argument tree beneath it (SPEC §7 UI-09).
 */
export function Reading(props: { position: PositionDto; tree: TreeNode; sel: Selection }) {
  const index = createMemo<TreeIndex>(() => indexTree(props.tree));
  const entry = () => index().get(props.position.ref);
  const childRefs = useChildRefs(entry);
  const [open, setOpen] = createSignal(false);

  return (
    <Show when={entry()}>
      {(e) => {
        const claim = () => e().node.claim;
        const leaf = () => e().node.children.length === 0;
        const phase = () => phaseOf(claim().status);
        const override = () => claim().override ?? 'AUTO';
        const panelId = () => `facts-${claim().ref}`;

        return (
          <section class="reading" aria-label="Reading">
            <h3 class="reading__q">{props.position.text}</h3>

            <div
              class="gauge"
              title="Credence: how likely this reading's answer is yes, after weighing its own arguments — the consensus of the credence rules."
            >
              <span class="gauge__con" aria-hidden="true">no</span>
              <span class="gauge__track" aria-hidden="true">
                <span class="gauge__fill" style={{ transform: `scaleX(${shown(claim())})` }} />
                <Show when={props.sel.research?.()}>
                  <SpreadBand node={claim()} class="gauge__band" />
                </Show>
                <span class="gauge__mark" style={{ left: `${shown(claim()) * 100}%` }} />
                <span class="gauge__mid" />
              </span>
              <span class="gauge__pro" aria-hidden="true">yes</span>
              <p class="gauge__caption">
                {[priorText(props.position), props.sel.research?.() ? bandCaption(claim()) : undefined]
                  .filter((x) => x !== undefined)
                  .join(' · ')}
              </p>
            </div>

            <Show when={priorDecidesText(props.position)}>
              {(text) => (
                <p
                  class="reading__prior"
                  role="note"
                  title="Jev's first impression of this reading is the prior its arguments start from; from a neutral ½ they point the other way"
                >
                  {text()}
                </p>
              )}
            </Show>

            <div class="reading__meta">
              <Show when={claim().status}>
                {(s) => (
                  <span class={`status status--${phase()}`} title={statusHint(s())}>
                    {STATUS_LABEL[s()]}
                  </span>
                )}
              </Show>
              <button
                type="button"
                class="more"
                aria-expanded={open()}
                aria-controls={panelId()}
                onClick={() => setOpen(!open())}
              >
                {open() ? 'Less' : 'Details'}
              </button>
              <span class="card__spacer" />
              <span class="reveal" classList={{ 'is-pinned': override() !== 'AUTO' }}>
                <OverrideControl id={props.position.ref} value={override()} />
              </span>
            </div>

            <Show when={open()}>
              <Facts
                id={panelId()}
                claim={claim()}
                leaf={leaf()}
                members={props.sel.members?.()}
                research={props.sel.research?.()}
              />
            </Show>

            <Show
              when={childRefs().length > 0}
              fallback={<p class="empty empty--quiet">Proposers are drafting the first arguments…</p>}
            >
              <ul class="tree" aria-label="Reading arguments">
                <For each={childRefs()}>{(ref) => <ClaimCard claimRef={ref} index={index} sel={props.sel} />}</For>
              </ul>
            </Show>
          </section>
        );
      }}
    </Show>
  );
}
