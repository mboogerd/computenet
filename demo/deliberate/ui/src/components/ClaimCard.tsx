import { createMemo, createSignal, For, onCleanup, onMount, Show, type Accessor } from 'solid-js';
import type { NodeDto } from '../api/types';
import type { ArgumentNode, TreeNode } from '../tree/buildTree';
import {
  agreementText,
  isDebug,
  layerLines,
  LEAF_AGREEMENT,
  linkParts,
  pct,
  phaseOf,
  shown,
  spreadOf,
  reachTier,
  statusHint,
  triageText,
  reachWeight,
  STATUS_LABEL,
  strengthWord,
  verdict,
} from '../util/format';
import { OverrideControl } from './OverrideControl';
import { TweenPct } from './Tween';

/** Every claim of the rendered tree, by ref, with the edge (= link) that
 *  attaches it to its parent and that link's own arguments. Cards look
 *  themselves up here by ref, so a new snapshot (all new objects) updates
 *  cards in place rather than re-creating them. */
export type TreeIndex = Map<
  string,
  { node: TreeNode; edge?: NodeDto; onLink?: boolean; linkArgs: ArgumentNode[]; parent?: string }
>;

export function indexTree(tree: TreeNode): TreeIndex {
  const idx: TreeIndex = new Map();
  const walk = (t: TreeNode, parent: string | undefined, a?: ArgumentNode) => {
    const entry = { node: t, edge: a?.edge, linkArgs: a?.linkArgs ?? [], parent };
    idx.set(t.claim.ref, a?.onLink ? { ...entry, onLink: true } : entry);
    for (const c of t.children) walk(c.node, t.claim.ref, c);
    for (const c of a?.linkArgs ?? []) walk(c.node, t.claim.ref, c);
  };
  walk(tree, undefined);
  return idx;
}

/** ms a card brought into view from the cruxes or "where the rules disagree"
 *  panel stays visibly marked ({@link focusInTree}); the `.is-focused` CSS
 *  animation (app.css) runs for the same span. */
const FOCUS_MS = 1600;

/**
 * Each mounted ClaimCard's own collapse setter, by claim ref — so
 * {@link focusInTree} can expand a collapsed ancestor before scrolling to a
 * claim outside a panel's own subtree. Solid updates the DOM synchronously
 * when a signal changes, so expanding top-down mounts each next ancestor's
 * children before its setter is looked up. Registered on mount, removed on
 * cleanup (an ancestor collapsing over it again, or the claim leaving the
 * graph) so a stale setter is never called.
 */
const collapseSetters = new Map<string, (collapsed: boolean) => void>();

/**
 * A collapsed/expanded state for the children of [ref] — a claim card, or a
 * framed question's reading (computenet-urmh0) — registered so
 * {@link focusInTree} can expand it before scrolling to something under it.
 */
export function useCollapse(ref: string) {
  const [collapsed, setCollapsed] = createSignal(false);
  onMount(() => collapseSetters.set(ref, setCollapsed));
  onCleanup(() => {
    if (collapseSetters.get(ref) === setCollapsed) collapseSetters.delete(ref);
  });
  return [collapsed, setCollapsed] as const;
}

let focusTimer: ReturnType<typeof setTimeout> | undefined;

/** The rendered element for a claim or link ref: a ClaimCard's `.card`, the
 *  tree's hero `<section>`, or (for a link ref) its `LinkChip` wrap —
 *  whichever carries this `data-claim-ref`. */
function cardElement(ref: string): HTMLElement | undefined {
  for (const el of document.querySelectorAll<HTMLElement>('[data-claim-ref]')) {
    if (el.dataset.claimRef === ref) return el;
  }
  return undefined;
}

/**
 * Focuses a claim or link's card in the rendered tree (computenet-lmfg8):
 * for a link ref — which has no entry of its own, since it renders inside
 * the claim that carries it — first finds that owning claim; expands every
 * collapsed ancestor of it, top-down so each expansion mounts the next; then
 * scrolls the ref's own element into view and marks it `is-focused` for
 * {@link FOCUS_MS}. `index` should cover every claim the tree can show
 * (TreeView merges the root tree with each framed position's own subtree);
 * an ancestor beyond what it covers is simply left as it is. Shared by
 * CruxesPanel and DisagreementPanel so both panels focus the tree the same
 * way.
 *
 * The scroll/highlight step is deferred a microtask past the ancestor
 * expansion: this runs inside TreeView's focus effect, and Solid queues the
 * `setCollapsed` writes made during an effect until that effect returns, so
 * a just-expanded ancestor's children are not mounted yet here. They are
 * mounted synchronously once it returns, before the activating handler does,
 * so one microtask is always enough (no async scheduling is involved).
 */
export function focusInTree(ref: string, index: TreeIndex): void {
  let owner = ref;
  if (!index.has(owner)) {
    for (const [claimRef, entry] of index) {
      if (entry.edge?.ref === ref) {
        owner = claimRef;
        break;
      }
    }
  }
  const ancestors: string[] = [];
  let cur = index.get(owner)?.parent;
  while (cur !== undefined) {
    ancestors.unshift(cur);
    cur = index.get(cur)?.parent;
  }
  for (const anc of ancestors) collapseSetters.get(anc)?.(false);

  queueMicrotask(() => {
    const el = cardElement(ref);
    if (!el) return;
    el.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    if (focusTimer !== undefined) clearTimeout(focusTimer);
    for (const prev of document.querySelectorAll('.is-focused')) prev.classList.remove('is-focused');
    el.classList.add('is-focused');
    focusTimer = setTimeout(() => el.classList.remove('is-focused'), FOCUS_MS);
  });
}

/** Which claim is open (showing its controls and Jev's numbers). One at a time. */
export interface Selection {
  selected: Accessor<string | undefined>;
  toggle: (ref: string) => void;
  /** The layers averaged into the consensus, for the facts panel. */
  members?: Accessor<readonly string[]>;
  /**
   * Model D: the research view is on — show the per-rule values (the rules'
   * band and each rule's credence). Off, only the consensus is shown.
   */
  research?: Accessor<boolean>;
}

const sameRefs = (a: string[], b: string[]) => a.length === b.length && a.every((r, i) => r === b[i]);

/** Stable child refs of a tree entry, so a new frame with the same children keeps their DOM. */
export function useChildRefs(entry: Accessor<{ node: TreeNode } | undefined>) {
  return createMemo(() => entry()?.node.children.map((a) => a.node.claim.ref) ?? [], undefined, { equals: sameRefs });
}

/** Pro and con arguments of a claim (arguments about its link are the link's, not the claim's). */
export function sideCounts(node: TreeNode): { pro: number; con: number } {
  const pro = node.children.filter((a) => a.edge.polarity === 'SUPPORT').length;
  return { pro, con: node.children.length - pro };
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

/**
 * A small credence bar: a soft fill to the consensus, the rules' band drawn
 * above it (so all of the band shows) in the research view only, and a marker
 * at the consensus — taller on a leaf, whose rules coincide and so have no band.
 */
function CredenceBar(props: { node: NodeDto; leaf: boolean; research?: boolean }) {
  const flat = () => {
    const s = spreadOf(props.node);
    return props.leaf && Math.round(s.high * 100) === Math.round(s.low * 100);
  };
  return (
    <span class="bar" classList={{ 'bar--leaf': flat() }} aria-hidden="true">
      <span class="bar__fill" style={{ transform: `scaleX(${shown(props.node)})` }} />
      <Show when={props.research}>
        <SpreadBand node={props.node} class="bar__band" />
      </Show>
      <span class="bar__tick" style={{ left: `${shown(props.node) * 100}%` }} />
    </span>
  );
}

export function ClaimCard(props: { claimRef: string; index: () => TreeIndex; sel: Selection }) {
  const entry = () => props.index().get(props.claimRef);
  // computenet-lmfg8: let a cruxes/disagreement entry elsewhere on the page
  // expand this card open before it scrolls to something inside it.
  const [collapsed, setCollapsed] = useCollapse(props.claimRef);
  const [linkOpen, setLinkOpen] = createSignal(false);
  const childRefs = useChildRefs(entry);

  return (
    <Show when={entry()}>
      {(e) => {
        const claim = () => e().node.claim;
        const edge = () => e().edge;
        const onLink = () => e().onLink === true;
        const pro = () => edge()?.polarity === 'SUPPORT';
        // An argument about a link either says it holds or undercuts it.
        const side = () => (onLink() ? (pro() ? 'holds' : 'undercut') : pro() ? 'pro' : 'con');
        const open = () => props.sel.selected() === claim().ref;
        const phase = () => phaseOf(claim().status);
        const counts = () => sideCounts(e().node);
        const leaf = () => e().node.children.length === 0;
        const override = () => claim().override ?? 'AUTO';
        const panelId = () => `facts-${claim().ref}`;
        const childrenId = () => `children-${claim().ref}`;

        return (
          <li
            class={`branch branch--${side()} reach--${reachTier(claim().reach)} phase--${phase()}`}
            classList={{ 'branch--onlink': onLink(), 'is-link-open': linkOpen() }}
            style={{ '--w': String(reachWeight(claim().reach)), '--s': String(edge()?.strength ?? 0) }}
          >
            <article class="card" classList={{ 'is-open': open(), 'is-busy': phase() === 'active' }} data-claim-ref={claim().ref}>
              <button
                type="button"
                class="card__main"
                aria-expanded={open()}
                aria-controls={panelId()}
                onClick={() => props.sel.toggle(claim().ref)}
              >
                <span class="card__text">{claim().text}</span>
                <span
                  class="card__cred"
                  title={`Credence: ${verdict(shown(claim()), 'claim').text}${props.sel.research?.() ? ` (${agreementText(claim(), leaf())})` : ''}`}
                >
                  <span class="card__num">
                    <TweenPct value={shown(claim())} />
                  </span>
                  <CredenceBar node={claim()} leaf={leaf()} research={props.sel.research?.()} />
                </span>
              </button>

              <div class="card__meta">
                <Show when={edge()}>
                  {(ed) => <LinkChip edge={ed()} side={side()} args={e().linkArgs} open={linkOpen()} onToggle={() => setLinkOpen(!linkOpen())} />}
                </Show>
                <Show when={claim().status}>
                  {(s) => (
                    <span class={`status status--${phase()}`} title={statusHint(s())}>
                      {STATUS_LABEL[s()]}
                    </span>
                  )}
                </Show>
                <Show when={callFailed(claim()) && phase() !== 'failed'}>
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
                <Facts
                  id={panelId()}
                  claim={claim()}
                  edge={edge()}
                  leaf={leaf()}
                  members={props.sel.members?.()}
                  research={props.sel.research?.()}
                />
              </Show>
            </article>

            <Show when={edge()}>
              {(ed) => (
                <LinkPanel
                  edge={ed()}
                  args={e().linkArgs}
                  open={linkOpen()}
                  index={props.index}
                  sel={props.sel}
                  onClose={() => setLinkOpen(false)}
                />
              )}
            </Show>

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

const callFailed = (n: NodeDto) => n.error !== undefined;

const SIDE_WORD = { pro: 'Pro', con: 'Con', holds: 'Link holds', undercut: 'Undercuts the link' } as const;

/** Counts of a link's own arguments: "1 holds · 2 fails". */
function linkArgCounts(args: ArgumentNode[]): { holds: number; fails: number } {
  const holds = args.filter((a) => a.edge.polarity === 'SUPPORT').length;
  return { holds, fails: args.length - holds };
}

/** What a link chip leads with: the link's own credence ("holds 65%"), the value that counts. */
export function linkHoldsText(edge: NodeDto): string {
  return edge.strength === undefined ? 'link pending' : `holds ${pct(shown(edge))}`;
}

/** Jev's first impression of a link, for its tooltip: "strong link 72%". */
export function firstImpressionText(edge: NodeDto): string | undefined {
  return edge.strength === undefined ? undefined : `${strengthWord(edge.strength)} ${pct(edge.strength)}`;
}

/**
 * The connector between a claim and its argument, made interactive (SPEC §3
 * "Links as claims"): it reads as the argument's side and how far the link
 * holds; hovering or focusing it previews the link as a claim (with Jev's
 * first impression), and pressing it opens the link — its credence, status,
 * arguments and override. The preview is rendered only while it shows.
 */
function LinkChip(props: {
  edge: NodeDto;
  side: keyof typeof SIDE_WORD;
  args: ArgumentNode[];
  open: boolean;
  onToggle: () => void;
}) {
  const counts = () => linkArgCounts(props.args);
  const peekId = () => `peek-${props.edge.ref}`;
  const active = () => props.edge.activity !== undefined;
  const [peek, setPeek] = createSignal(false);
  const peeking = () => peek() && !props.open;
  const first = () => firstImpressionText(props.edge);
  return (
    <span class="linkchip-wrap" data-claim-ref={props.edge.ref}>
      <button
        type="button"
        class="linkchip"
        classList={{ 'is-open': props.open, 'is-busy': active() }}
        style={{ '--h': String(props.edge.strength === undefined ? 0 : shown(props.edge)) }}
        aria-expanded={props.open}
        aria-controls={`link-${props.edge.ref}`}
        aria-describedby={peeking() ? peekId() : undefined}
        aria-label={`${SIDE_WORD[props.side]}, ${linkHoldsText(props.edge)}${first() ? ` (Jev's first impression: ${first()})` : ''}. ${props.open ? 'Hide' : 'Open'} this link as a claim`}
        onMouseEnter={() => setPeek(true)}
        onMouseLeave={() => setPeek(false)}
        onFocus={() => setPeek(true)}
        onBlur={() => setPeek(false)}
        onKeyDown={(ev) => {
          if (ev.key === 'Escape' && peeking()) {
            ev.stopPropagation();
            setPeek(false);
          }
        }}
        onClick={(ev) => {
          ev.stopPropagation();
          props.onToggle();
        }}
      >
        <span class="linkchip__knot" aria-hidden="true" />
        <span class="side__word">{SIDE_WORD[props.side]}</span>
        <span class="side__strength">
          {'\u00a0· '}
          <span class="side__num">{linkHoldsText(props.edge)}</span>
        </span>
        <Show when={props.args.length > 0}>
          <span class="linkchip__count">
            {' · '}{props.args.length} argument{props.args.length === 1 ? '' : 's'}
          </span>
        </Show>
      </button>
      <Show when={peeking()}>
        <span class="linkchip__peek" role="tooltip" id={peekId()}>
          <span class="linkchip__peek-tag">link</span>
          <LinkSentence text={props.edge.text} />
          <span class="linkchip__peek-meta">
            {linkHoldsText(props.edge)}
            <Show when={props.edge.status}>{(st) => <> · {STATUS_LABEL[st()]}</>}</Show>
            <Show when={props.args.length > 0}>
              {' · '}
              {counts().holds} for · {counts().fails} against
            </Show>
          </span>
          <Show when={first()}>
            {(f) => <span class="linkchip__peek-meta">Jev's first impression: {f()}</span>}
          </Show>
        </span>
      </Show>
    </span>
  );
}

/** “argument” is a reason for “parent”, with the relation in the side's colour. */
function LinkSentence(props: { text: string | undefined }) {
  const parts = () => linkParts(props.text);
  return (
    <Show when={parts()} fallback={<span class="linksentence">{props.text ?? 'this link'}</span>}>
      {(p) => (
        <span class="linksentence">
          <span class="linksentence__end">“{p().argument}”</span>{' '}
          <span class={`linksentence__rel ${p().relation.endsWith('for') ? 'pro-text' : 'con-text'}`}>{p().relation}</span>{' '}
          <span class="linksentence__end">“{p().parent}”</span>
        </span>
      )}
    </Show>
  );
}

/**
 * The link as a claim, drawn on the connector's side of the argument: dashed,
 * tagged "link", with its own credence (the edge's), status, override and
 * arguments — why it holds, and why it fails (undercutters). The arguments are
 * always shown when there are any; the rest opens from the {@link LinkChip}.
 */
export function LinkPanel(props: {
  edge: NodeDto;
  args: ArgumentNode[];
  open: boolean;
  index: () => TreeIndex;
  sel: Selection;
  onClose: () => void;
}) {
  const refsOf = (polarity: string) =>
    createMemo(() => props.args.filter((a) => a.edge.polarity === polarity).map((a) => a.node.claim.ref), undefined, {
      equals: sameRefs,
    });
  const holds = refsOf('SUPPORT');
  const fails = refsOf('ATTACK');
  const [facts, setFacts] = createSignal(false);
  const leaf = () => props.args.length === 0;
  const phase = () => phaseOf(props.edge.status);
  const override = () => props.edge.override ?? 'AUTO';
  const column = (kind: 'holds' | 'fails', refs: () => string[]) => (
    <Show when={props.open || refs().length > 0}>
      <div class={`linkcol linkcol--${kind}`}>
        <h4 class="linkcol__title">{kind === 'holds' ? 'Why it holds' : 'Why it fails'}</h4>
        <Show
          when={refs().length > 0}
          fallback={<p class="linkcol__empty">{kind === 'holds' ? 'No reasons yet that it holds.' : 'No undercutters yet.'}</p>}
        >
          <ul class="children children--link">
            <For each={refs()}>{(ref) => <ClaimCard claimRef={ref} index={props.index} sel={props.sel} />}</For>
          </ul>
        </Show>
      </div>
    </Show>
  );

  return (
    <Show when={props.open || props.args.length > 0}>
      <section
        class="linkpanel"
        classList={{ 'is-open': props.open, 'is-busy': props.edge.activity !== undefined }}
        id={`link-${props.edge.ref}`}
        aria-label="The link as a claim"
        onKeyDown={(ev) => {
          if (ev.key === 'Escape' && props.open) {
            ev.stopPropagation();
            props.onClose();
          }
        }}
      >
        <Show
          when={props.open}
          fallback={
            <p class="linkpanel__label">
              <span class="tag tag--link">link</span> arguments about this connection
            </p>
          }
        >
          <header class="linkpanel__head">
            <span class="tag tag--link">link</span>
            <LinkSentence text={props.edge.text} />
          </header>
          <div class="linkpanel__cred">
            <span class="linkpanel__num" title="The link's credence: how far the argument really bears on the claim, after weighing the link's own arguments">
              holds <TweenPct value={shown(props.edge)} />
            </span>
            <CredenceBar node={props.edge} leaf={leaf()} research={props.sel.research?.()} />
            <Show when={props.sel.research?.()}>
              <span class="linkpanel__agree">{agreementText(props.edge, leaf())}</span>
            </Show>
          </div>
          <div class="card__meta linkpanel__meta">
            <Show when={props.edge.status}>
              {(s) => (
                <span class={`status status--${phase()}`} title={statusHint(s(), true)}>
                  {STATUS_LABEL[s()]}
                </span>
              )}
            </Show>
            <Show when={callFailed(props.edge) && phase() !== 'failed'}>
              <span class="status status--failed" title={props.edge.error}>a call failed</span>
            </Show>
            <button type="button" class="linkish" aria-expanded={facts()} aria-controls={`facts-${props.edge.ref}`} onClick={() => setFacts(!facts())}>
              <span class="chevron" classList={{ 'is-collapsed': !facts() }} aria-hidden="true" />
              numbers
            </button>
            <span class="card__spacer" />
            <OverrideControl id={props.edge.ref} value={override()} what="link" />
          </div>
          <Show when={facts()}>
            <Facts
              id={`facts-${props.edge.ref}`}
              claim={props.edge}
              link
              leaf={leaf()}
              members={props.sel.members?.()}
              research={props.sel.research?.()}
            />
          </Show>
        </Show>
        <div class="linkpanel__cols">
          {column('holds', holds)}
          {column('fails', fails)}
        </div>
      </section>
    </Show>
  );
}

/** Jev's raw judgments, in plain language, for the open claim — or, with `link`, for a link. */
export function Facts(props: {
  id: string;
  claim: NodeDto;
  edge?: NodeDto;
  members?: readonly string[];
  /** The node has no arguments yet, so its rules agree by construction. */
  leaf?: boolean;
  /** `claim` is an EDGE node explored as a link. */
  link?: boolean;
  /** Model D: the research view — add how far the rules agree and each rule's credence. */
  research?: boolean;
}) {
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
      {row(
        'Credence',
        verdict(shown(c()), c().depth === 0 ? 'question' : 'claim').text,
        props.link
          ? 'The consensus of the credence rules: how far the argument really bears on the claim, after weighing the link\'s own arguments'
          : 'The consensus of the credence rules: how likely this is true, after weighing its arguments',
      )}
      {row(
        'Rules',
        props.research && c().credences ? agreementText(c(), props.leaf) : undefined,
        props.leaf ? `How far the credence rules agree — ${LEAF_AGREEMENT}` : 'How far the credence rules (ways of weighing arguments) agree on this claim',
      )}
      <Show when={props.research && c().credences}>
        <dt title="Each rule's credence; the consensus averages the marked ones">By rule</dt>
        <dd>
          <ul class="facts__layers">
            <For each={layerLines(c(), props.members ?? [])}>{(line) => <li>{line}</li>}</For>
          </ul>
        </dd>
      </Show>
      <Show when={props.link}>
        {row('First impression', p(c().strength), "Jev's link strength before any argument about the link: if the argument were true, how strongly it bears on the claim")}
      </Show>
      <Show when={!props.link}>
        {c().depth === 0
          ? row('First impression', p(c().plausibility), "Jev's judgment of the question alone, before any argument: the prior the arguments start from")
          : row('Plausible on its own', p(c().plausibility), "Jev's judgment of the claim alone, before arguments")}
      </Show>
      <Show when={props.edge}>
        {row('Link strength', p(props.edge!.strength), 'If this were true, how strongly it bears on the claim above')}
      </Show>
      <Show when={c().depth !== 0}>
        {row('Reach', p(c().reach), 'How much this can matter to the question: link strengths multiplied from the top')}
        <Show when={!props.link}>
          {row('Relevance', p(c().relevance), 'Would exploring it further change the answer? (Jev)')}
          {row('Quality', p(c().quality), 'Is it a well-made argument: self-contained, coherent, on point? (Jev)')}
        </Show>
        {row(
          'Contribution',
          p(c().contribution),
          props.link
            ? "The argument's contribution × how unsettled the link strength is (highest at 50%). Shown for reference: exploration follows how far settling a link could move the answer"
            : 'Reach × relevance × quality. Shown for reference: exploration follows how far settling a claim could move the answer',
        )}
      </Show>
      {row('Sides covered', sides(), 'How complete Jev judges each side of the argument')}
      {row('Rounds', c().rounds?.toString(), props.link ? 'Proposal rounds run on this link' : 'Proposal rounds run on this claim')}
      {row('Duplicates dropped', c().duplicatesDropped?.toString(), 'Proposed arguments Jev recognised as repeats')}
      {row('Sorted proposals', triageText(c().triage, props.link), 'What Jev did with each argument proposed for this claim')}
      <Show when={c().proposer && c().proposer !== 'question'}>{row('Proposed by', c().proposer!, 'The model that wrote this claim')}</Show>
      {row('Also proposed by', c().alsoProposedBy?.join(', '), 'Other models that made the same point')}
      <Show when={c().merged}>{row('Merged', 'yes', 'Rewritten as one argument together with an overlapping one')}</Show>
      <Show when={c().error}>
        <dt>Error</dt>
        <dd class="facts__error">{c().error}</dd>
      </Show>
      <Show when={isDebug()}>
        <dt>Ref</dt>
        <dd class="mono">{c().ref}</dd>
      </Show>
    </dl>
  );
}
