import { createMemo, createSignal, For, Show, type Accessor } from 'solid-js';
import type { NodeDto } from '../api/types';
import type { TreeNode } from '../tree/buildTree';
import {
  agreementText,
  layerLines,
  pct,
  phaseOf,
  shown,
  spreadOf,
  reachTier,
  triageText,
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
export type TreeIndex = Map<string, { node: TreeNode; edge?: NodeDto; undercut?: boolean }>;

export function indexTree(tree: TreeNode): TreeIndex {
  const idx: TreeIndex = new Map();
  const walk = (t: TreeNode, edge?: NodeDto, undercut?: boolean) => {
    idx.set(t.claim.ref, undercut ? { node: t, edge, undercut } : { node: t, edge });
    for (const a of t.children) walk(a.node, a.edge, a.undercut);
  };
  walk(tree);
  return idx;
}

/** Which claim is open (showing its controls and Jev's numbers). One at a time. */
export interface Selection {
  selected: Accessor<string | undefined>;
  toggle: (ref: string) => void;
  /** The layers averaged into the consensus, for the facts panel. */
  members?: Accessor<readonly string[]>;
}

/** Stable child refs of a tree entry, so a new frame with the same children keeps their DOM. */
export function useChildRefs(entry: Accessor<{ node: TreeNode } | undefined>) {
  return createMemo(() => entry()?.node.children.map((a) => a.node.claim.ref) ?? [], undefined, {
    equals: (a, b) => a.length === b.length && a.every((r, i) => r === b[i]),
  });
}

/** Pro and con arguments of a claim; undercutters of its own link are counted apart. */
export function sideCounts(node: TreeNode): { pro: number; con: number; undercuts: number } {
  const args = node.children.filter((a) => !a.undercut);
  const pro = args.filter((a) => a.edge.polarity === 'SUPPORT').length;
  return { pro, con: args.length - pro, undercuts: node.children.length - args.length };
}

/** A thin translucent band over a credence track: where the credence rules disagree. */
export function SpreadBand(props: { node: NodeDto; class: string }) {
  const s = () => spreadOf(props.node);
  return (
    <span
      class={props.class}
      aria-hidden="true"
      style={{ left: `${s().low * 100}%`, width: `${Math.max(0, s().high - s().low) * 100}%` }}
    />
  );
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
        const undercut = () => e().undercut === true;
        const side = () => (undercut() ? 'undercut' : edge()?.polarity === 'SUPPORT' ? 'pro' : 'con');
        const open = () => props.sel.selected() === claim().ref;
        const phase = () => phaseOf(claim().status);
        const counts = () => sideCounts(e().node);
        const override = () => claim().override ?? 'AUTO';
        const panelId = () => `facts-${claim().ref}`;
        const childrenId = () => `children-${claim().ref}`;

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
                <span class="card__cred" title={`Credence: ${verdict(shown(claim()), 'claim').text} (${agreementText(claim())})`}>
                  <span class="card__num">
                    <TweenPct value={shown(claim())} />
                  </span>
                  <span class="bar" aria-hidden="true">
                    <SpreadBand node={claim()} class="bar__band" />
                    <span class="bar__fill" style={{ transform: `scaleX(${shown(claim())})` }} />
                  </span>
                </span>
              </button>

              <div class="card__meta">
                <span
                  class="side"
                  title={
                    undercut()
                      ? 'Undercuts the link: it does not dispute the claim above, it denies that the argument above bears on it'
                      : 'Relation strength: if this were true, how strongly it bears on the claim above'
                  }
                >
                  <span class="side__word">{undercut() ? 'Undercuts the link' : side() === 'pro' ? 'Pro' : 'Con'}</span>
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
                    aria-controls={childrenId()}
                    aria-label={`${collapsed() ? 'Show' : 'Hide'} arguments for ${claim().text}`}
                    onClick={() => setCollapsed(!collapsed())}
                  >
                    <span class="chevron" classList={{ 'is-collapsed': collapsed() }} aria-hidden="true" />
                    {counts().pro} pro · {counts().con} con
                    {counts().undercuts > 0 ? ` · ${counts().undercuts} undercut${counts().undercuts === 1 ? '' : 's'}` : ''}
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
                <Facts id={panelId()} claim={claim()} edge={edge()} members={props.sel.members?.()} />
              </Show>
            </article>

            <Show when={childRefs().length > 0 && !collapsed()}>
              <ul class="children" id={childrenId()}>
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
export function Facts(props: { id: string; claim: NodeDto; edge?: NodeDto; members?: readonly string[] }) {
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
      {row('Credence', verdict(shown(c()), c().depth === 0 ? 'question' : 'claim').text, 'The consensus of the credence rules: how likely this is true, after weighing its arguments')}
      {row('Rules', c().credences ? agreementText(c()) : undefined, 'How far the credence rules (ways of weighing arguments) agree on this claim')}
      <Show when={c().credences}>
        <dt title="Each rule's credence; the consensus averages the marked ones">By rule</dt>
        <dd>
          <ul class="facts__layers">
            <For each={layerLines(c(), props.members ?? [])}>{(line) => <li>{line}</li>}</For>
          </ul>
        </dd>
      </Show>
      {row('Plausible on its own', p(c().plausibility), "Jev's judgment of the claim alone, before arguments")}
      <Show when={props.edge}>
        {row('Link strength', p(props.edge!.strength), 'If this were true, how strongly it bears on the claim above')}
      </Show>
      <Show when={c().depth !== 0}>
        {row('Reach', p(c().reach), 'How much this can matter to the question: link strengths multiplied from the top')}
        {row('Relevance', p(c().relevance), 'Would exploring it further change the answer? (Jev)')}
        {row('Quality', p(c().quality), 'Is it a well-made argument: self-contained, coherent, on point? (Jev)')}
        {row('Contribution', p(c().contribution), 'Reach × relevance × quality: stronger claims are explored first; too low and it is set aside')}
      </Show>
      {row('Sides covered', sides(), 'How complete Jev judges each side of the argument')}
      {row('Rounds', c().rounds?.toString(), 'Proposal rounds run on this claim')}
      {row('Duplicates dropped', c().duplicatesDropped?.toString(), 'Proposed arguments Jev recognised as repeats')}
      {row('Sorted proposals', triageText(c().triage), 'What Jev did with each argument proposed for this claim')}
      <Show when={c().proposer}>{row('Proposed by', c().proposer!, 'The model that wrote this claim')}</Show>
      {row('Also proposed by', c().alsoProposedBy?.join(', '), 'Other models that made the same point')}
      <Show when={c().merged}>{row('Merged', 'yes', 'Rewritten as one argument together with an overlapping one')}</Show>
      <Show when={c().error}>
        <dt>Error</dt>
        <dd class="facts__error">{c().error}</dd>
      </Show>
      <dt>Ref</dt>
      <dd class="mono">{c().ref}</dd>
    </dl>
  );
}
