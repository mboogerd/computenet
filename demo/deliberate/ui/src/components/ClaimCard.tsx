import { createMemo, createSignal, For, Show, type Accessor } from 'solid-js';
import type { NodeDto } from '../api/types';
import type { TreeNode } from '../tree/buildTree';
import {
  pct,
  phaseOf,
  reachTier,
  reachWeight,
  STATUS_HINT,
  STATUS_LABEL,
  strengthWord,
  verdict,
} from '../util/format';
import { OverrideControl } from './OverrideControl';
import { TweenPct } from './Tween';

/** Every claim of the rendered tree, by ref, with the edge that attaches it to
 *  its parent. Cards look themselves up here by ref, so a new snapshot (all
 *  new objects) updates cards in place rather than re-creating them. */
export type TreeIndex = Map<string, { node: TreeNode; edge?: NodeDto }>;

export function indexTree(tree: TreeNode): TreeIndex {
  const idx: TreeIndex = new Map();
  const walk = (t: TreeNode, edge?: NodeDto) => {
    idx.set(t.claim.ref, { node: t, edge });
    for (const a of t.children) walk(a.node, a.edge);
  };
  walk(tree);
  return idx;
}

/** Which claim is open (showing its controls and Jev's numbers). One at a time. */
export interface Selection {
  selected: Accessor<string | undefined>;
  toggle: (ref: string) => void;
}

/** Stable child refs of a tree entry, so a new frame with the same children keeps their DOM. */
export function useChildRefs(entry: Accessor<{ node: TreeNode } | undefined>) {
  return createMemo(() => entry()?.node.children.map((a) => a.node.claim.ref) ?? [], undefined, {
    equals: (a, b) => a.length === b.length && a.every((r, i) => r === b[i]),
  });
}

export function sideCounts(node: TreeNode): { pro: number; con: number } {
  const pro = node.children.filter((a) => a.edge.polarity === 'SUPPORT').length;
  return { pro, con: node.children.length - pro };
}

export function ClaimCard(props: { claimRef: string; index: () => TreeIndex; sel: Selection }) {
  const entry = () => props.index().get(props.claimRef);
  const [collapsed, setCollapsed] = createSignal(false);
  const childRefs = useChildRefs(entry);

  return (
    <Show when={entry()}>
      {(e) => {
        const claim = () => e().node.claim;
        const edge = () => e().edge;
        const side = () => (edge()?.polarity === 'SUPPORT' ? 'pro' : 'con');
        const open = () => props.sel.selected() === claim().ref;
        const phase = () => phaseOf(claim().status);
        const counts = () => sideCounts(e().node);
        const override = () => claim().override ?? 'AUTO';
        const panelId = () => `facts-${claim().ref}`;

        return (
          <li
            class={`branch branch--${side()} reach--${reachTier(claim().reach)} phase--${phase()}`}
            style={{ '--w': String(reachWeight(claim().reach)), '--s': String(edge()?.strength ?? 0) }}
          >
            <article class="card" classList={{ 'is-open': open(), 'is-busy': phase() === 'active' }}>
              <button
                type="button"
                class="card__main"
                aria-expanded={open()}
                aria-controls={panelId()}
                onClick={() => props.sel.toggle(claim().ref)}
              >
                <span class="card__text">{claim().text}</span>
                <span class="card__cred" title={`Credence: ${verdict(claim().credence, 'claim').text}`}>
                  <span class="card__num">
                    <TweenPct value={claim().credence} />
                  </span>
                  <span class="bar" aria-hidden="true">
                    <span style={{ transform: `scaleX(${claim().credence})` }} />
                  </span>
                </span>
              </button>

              <div class="card__meta">
                <span class="side" title="Relation strength: if this were true, how strongly it bears on the claim above">
                  <span class="side__word">{side() === 'pro' ? 'Pro' : 'Con'}</span>
                  <span class="side__strength">
                    {' · '}
                    {strengthWord(edge()?.strength)}
                    <Show when={edge()?.strength !== undefined}>
                      {' '}
                      <span class="side__num">{pct(edge()!.strength)}</span>
                    </Show>
                  </span>
                </span>
                <Show when={claim().status}>
                  {(s) => (
                    <span class={`status status--${phase()}`} title={STATUS_HINT[s()]}>
                      {STATUS_LABEL[s()]}
                    </span>
                  )}
                </Show>
                <Show when={claim().error && phase() !== 'failed'}>
                  <span class="status status--failed" title={claim().error}>a call failed</span>
                </Show>
                <Show when={childRefs().length > 0}>
                  <button
                    type="button"
                    class="linkish"
                    aria-expanded={!collapsed()}
                    onClick={() => setCollapsed(!collapsed())}
                  >
                    <span class="chevron" classList={{ 'is-collapsed': collapsed() }} aria-hidden="true" />
                    {counts().pro} pro · {counts().con} con
                  </button>
                </Show>
                <span class="card__spacer" />
                <span class="reveal" classList={{ 'is-pinned': override() !== 'AUTO' }}>
                  <Show when={claim().proposer && claim().proposer !== 'question'}>
                    <span class="proposer" title="Which model proposed this argument">{claim().proposer}</span>
                  </Show>
                  <OverrideControl id={claim().ref} value={override()} />
                </span>
              </div>

              <Show when={open()}>
                <Facts id={panelId()} claim={claim()} edge={edge()} />
              </Show>
            </article>

            <Show when={childRefs().length > 0 && !collapsed()}>
              <ul class="children">
                <For each={childRefs()}>{(ref) => <ClaimCard claimRef={ref} index={props.index} sel={props.sel} />}</For>
              </ul>
            </Show>
          </li>
        );
      }}
    </Show>
  );
}

/** Jev's raw judgments, in plain language, for the open claim. */
export function Facts(props: { id: string; claim: NodeDto; edge?: NodeDto }) {
  const c = () => props.claim;
  // Rows Jev has not judged yet are left out rather than shown as dashes.
  const row = (label: string, value: string | undefined, hint: string) =>
    value === undefined ? null : (
      <>
        <dt title={hint}>{label}</dt>
        <dd>{value}</dd>
      </>
    );
  const p = (x: number | undefined) => (x === undefined ? undefined : pct(x));
  const sides = () =>
    c().proSaturation === undefined && c().conSaturation === undefined
      ? undefined
      : `pro ${pct(c().proSaturation)} · con ${pct(c().conSaturation)}`;
  return (
    <dl class="facts" id={props.id}>
      {row('Credence', verdict(c().credence, c().depth === 0 ? 'question' : 'claim').text, 'How likely this is true, after weighing its arguments')}
      {row('Plausible on its own', p(c().plausibility), "Jev's judgment of the claim alone, before arguments")}
      <Show when={props.edge}>
        {row('Link strength', p(props.edge!.strength), 'If this were true, how strongly it bears on the claim above')}
      </Show>
      <Show when={c().depth !== 0}>
        {row('Reach', p(c().reach), 'How much this can matter to the question: link strengths multiplied from the top')}
        {row('Relevance', p(c().relevance), 'Would exploring it further change the answer? (Jev)')}
      </Show>
      {row('Sides covered', sides(), 'How complete Jev judges each side of the argument')}
      {row('Rounds', c().rounds?.toString(), 'Proposal rounds run on this claim')}
      {row('Duplicates dropped', c().duplicatesDropped?.toString(), 'Proposed arguments Jev recognised as repeats')}
      <Show when={c().proposer}>{row('Proposed by', c().proposer!, 'The model that wrote this claim')}</Show>
      <Show when={c().error}>
        <dt>Error</dt>
        <dd class="facts__error">{c().error}</dd>
      </Show>
      <dt>Ref</dt>
      <dd class="mono">{c().ref}</dd>
    </dl>
  );
}
