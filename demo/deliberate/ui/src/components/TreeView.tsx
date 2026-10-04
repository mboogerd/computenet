import { createEffect, createMemo, createSignal, For, onCleanup, onMount, Show, untrack, type Accessor } from 'solid-js';
import { DEFAULT_CONSENSUS, type GraphDto } from '../api/types';
import { buildForest } from '../tree/buildTree';
import type { TreeNode } from '../tree/buildTree';
import { FramingSummary, Reading } from './Framing';
import { useByRef } from '../util/byRef';
import {
  ACTIVITY_VERB,
  activityOf,
  agreementText,
  bandCaption,
  clip,
  isResearch,
  LEAF_AGREEMENT,
  linkParts,
  pct,
  priorDecidesText,
  priorText,
  questionProgress,
  shown,
  statusHint,
  statusLabel,
  stoppedHint,
  stoppedText,
  verdict,
} from '../util/format';
import { createTween } from '../util/tween';
import { ClaimCard, Facts, focusInTree, indexTree, sideCounts, SpreadBand, useChildRefs, type Selection, type TreeIndex } from './ClaimCard';
import { CostBadge } from './CostBadge';
import { OverrideControl } from './OverrideControl';
import { PauseControl } from './PauseControl';

/**
 * [research]: model D's research view — per-rule values shown (default: the
 * `?research` URL flag). `focus`: computenet-lmfg8 — set by App when a
 * cruxes or "where the rules disagree" entry is activated, `{ ref, n }` with
 * `n` bumped on every activation so re-selecting the same entry re-scrolls.
 */
export function TreeView(props: { graph: GraphDto; root: string; research?: boolean; focus?: Accessor<{ ref: string; n: number } | undefined> }) {
  const tree = createMemo(() => buildForest(props.graph, props.root));
  // Indexes the root tree and, for a framed question, every reading/position
  // subtree too (Framing.tsx's Reading builds its own index for rendering;
  // this one exists only so focusInTree can find a claim's ancestor chain
  // wherever in the page it renders).
  const index = createMemo<TreeIndex>(() => {
    const t = tree();
    if (!t) return new Map();
    const idx = indexTree(t);
    for (const p of t.positions ?? []) {
      for (const [ref, entry] of indexTree(p)) idx.set(ref, entry);
    }
    return idx;
  });

  // Only a new focus request re-runs this: the index is read untracked, or
  // every live snapshot (a new index) would re-scroll to the last-picked
  // claim and re-expand any ancestor the user has since collapsed.
  createEffect(() => {
    const f = props.focus?.();
    if (f !== undefined) untrack(() => focusInTree(f.ref, index()));
  });

  const [selected, setSelected] = createSignal<string>();
  const sel: Selection = {
    selected,
    toggle: (ref) => setSelected((cur) => (cur === ref ? undefined : ref)),
    members: () => props.graph.consensusMembers ?? DEFAULT_CONSENSUS,
    research: () => props.research ?? isResearch(),
  };
  onMount(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setSelected(undefined);
    };
    document.addEventListener('keydown', onKey);
    onCleanup(() => document.removeEventListener('keydown', onKey));
  });

  return (
    <Show when={tree()} fallback={<p class="empty">Waiting for the question to appear…</p>}>
      <Show when={props.root} keyed>
        {(root) => <Question root={root} index={index} tree={tree} graph={() => props.graph} sel={sel} />}
      </Show>
    </Show>
  );
}

/**
 * UI-09: a framed question's hero pro/con — each reading/position's own direct
 * arguments ({@link sideCounts}), summed. That is the level an unframed hero
 * counts (the root's direct arguments); deeper claims reply to an argument, not
 * to the question, so their polarity is not counted for it. A framed root has
 * no arguments of its own to count (they live under its readings/positions).
 */
function framedSideCounts(positions: TreeNode[]): { pro: number; con: number } {
  const acc = { pro: 0, con: 0 };
  for (const p of positions) {
    const c = sideCounts(p);
    acc.pro += c.pro;
    acc.con += c.con;
  }
  return acc;
}

/** The question is the hero: large text, one credence gauge, a plain verdict, and progress. */
function Question(props: { root: string; index: () => TreeIndex; tree: () => TreeNode | undefined; graph: () => GraphDto; sel: Selection }) {
  const entry = () => props.index().get(props.root);
  const childRefs = useChildRefs(entry);
  const progress = createMemo(() => questionProgress(props.graph().nodes, props.root));
  const question = () => props.graph().questions.find((q) => q.root === props.root);
  // Model A: set only when the question was framed; the positions built by buildForest, by ref.
  const framing = () => question()?.framing;
  const positions = useByRef(() => framing()?.positions ?? []);
  const positionTrees = createMemo(() => {
    const map = new Map<string, TreeNode>();
    for (const t of props.tree()?.positions ?? []) map.set(t.claim.ref, t);
    return map;
  });
  // UI-09: a framed root has no arguments of its own (they live under its
  // readings/positions), so its hero meta line sums each position's own
  // direct pro/con instead of sideCounts(root).
  const frameCounts = createMemo(() => (framing() ? framedSideCounts(props.tree()?.positions ?? []) : undefined));

  return (
    <Show when={entry()}>
      {(e) => {
        const claim = () => e().node.claim;
        // Label and number come from the same (animated) value, so they never disagree mid-glide.
        // The headline is the consensus of the credence rules; the band behind it is their spread.
        const tweened = createTween(() => shown(claim()));
        const v = () => verdict(tweened(), 'question');
        const counts = () => frameCounts() ?? sideCounts(e().node);
        const open = () => props.sel.selected() === claim().ref;
        const paused = () => question()?.paused === true;
        // A paused question with queued claims is waiting, not working (CTL-05).
        // QuestionDto.active includes active links (LINK-06), not only claims.
        const busy = () => question()?.active === true && !paused();
        const override = () => claim().override ?? 'AUTO';
        // No arguments yet: no verdict to give, only the first impression.
        const unargued = () => e().node.children.length === 0;

        return (
          <>
            <section class="hero" classList={{ 'is-open': open(), 'is-busy': busy() }} aria-label="Question" data-claim-ref={claim().ref}>
              <h2 class="hero__q">{claim().text}</h2>

              <Show
                when={!framing()}
                fallback={<FramingSummary framing={framing()!} />}
              >
                <div
                  class="gauge"
                  classList={{ 'is-empty': unargued() }}
                  title={
                    unargued()
                      ? LEAF_AGREEMENT
                      : props.sel.research?.()
                        ? `Credence: how likely the answer is yes, after weighing every argument — the consensus of the credence rules (${agreementText(claim())}). The band is the range from the lowest to the highest rule.`
                        : 'Credence: how likely the answer is yes, after weighing every argument from Jev\'s first impression — the consensus of the credence rules.'
                  }
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
                    {unargued()
                      ? `first impression: ${pct(shown(claim()))}`
                      : [priorText(question()), props.sel.research?.() ? bandCaption(claim()) : undefined]
                          .filter((x) => x !== undefined)
                          .join(' · ')}
                  </p>
                </div>
              </Show>

              <Show when={!framing()}>
              <div class="hero__row">
                <Show
                  when={!unargued()}
                  fallback={
                    <p class="verdict verdict--none" aria-live="polite">
                      <span class="verdict__label">{LEAF_AGREEMENT}</span>
                    </p>
                  }
                >
                  <p class={`verdict verdict--${v().lean}`} aria-live="polite">
                    <span class="verdict__label">{v().label}</span>
                    <span class="verdict__sep" aria-hidden="true">·</span>
                    <span class="verdict__num">{`${v().percent}%`}</span>
                  </p>
                </Show>
                <Show when={paused() && question()}>
                  {(q) => (
                    <span class="hero__pausedbox">
                      <span class="hero__paused" title="Paused: no new round starts until you resume this question">
                        paused
                      </span>
                      <PauseControl root={q().root} paused />
                    </span>
                  )}
                </Show>
                <button
                  type="button"
                  class="more"
                  aria-expanded={open()}
                  aria-controls={`facts-${claim().ref}`}
                  onClick={() => props.sel.toggle(claim().ref)}
                >
                  {open() ? 'Less' : 'Details'}
                </button>
              </div>
              </Show>

              <div
                class="activity"
                classList={{ 'is-busy': busy() }}
                role="progressbar"
                aria-label="Claims settled"
                aria-valuemin={0}
                aria-valuemax={progress().total}
                aria-valuenow={progress().settled}
              >
                <span style={{ transform: `scaleX(${progress().fraction})` }} />
              </div>
              <p class="hero__meta">
                <span>
                  <span class="pro-text">{counts().pro} pro</span> · <span class="con-text">{counts().con} con</span>
                </span>
                <span>
                  {paused()
                    ? `${progress().settled} of ${progress().total} claims settled`
                    : busy()
                      ? `deliberating · ${progress().settled} of ${progress().total} claims settled`
                      : `settled · ${progress().total} claims`}
                </span>
                <Show when={stoppedText(question())}>
                  {(text) => (
                    <span class="hero__stopped" title={stoppedHint(question())}>
                      {text()}
                    </span>
                  )}
                </Show>
                {/* CTL-03: a question the human stopped already reads "stopped by you" above. */}
                <Show when={claim().status && ((claim().status === 'STOPPED' && question()?.stoppedBy !== 'human') || claim().status === 'FAILED')}>
                  <span class="status status--halted" title={statusHint(claim())}>
                    question {statusLabel(claim())}
                  </span>
                </Show>
                <span class="card__spacer" />
                <Show when={question()}>{(q) => <CostBadge question={q()} />}</Show>
                <Show when={question()?.active && !paused() && question()}>{(q) => <PauseControl root={q().root} paused={false} />}</Show>
                {/* UI-09: a framed root's EXPAND/STOP would run no round (FRA-02) — no question-level override for it. */}
                <Show when={!framing()}>
                  <span class="reveal" classList={{ 'is-pinned': override() !== 'AUTO' }}>
                    <OverrideControl id={claim().ref} value={override()} what="question" />
                  </span>
                </Show>
              </p>

              <Show when={!unargued() && priorDecidesText(question())}>
                {(text) => (
                  <p class="hero__prior" role="note" title="Jev's first impression of the question is the prior the arguments start from; from a neutral ½ they point the other way">
                    {text()}
                  </p>
                )}
              </Show>

              <NowLine graph={props.graph} root={props.root} />

              <Show when={open()}>
                <Facts
                  id={`facts-${claim().ref}`}
                  claim={claim()}
                  leaf={e().node.children.length === 0}
                  members={props.sel.members?.()}
                  research={props.sel.research?.()}
                />
              </Show>
            </section>

            <Show
              when={!framing()}
              fallback={
                <div class="readings">
                  <For each={positions.refs()}>
                    {(ref) => (
                      <Show when={positions.byRef(ref)}>
                        {(p) => (
                          <Show when={positionTrees().get(ref)}>
                            {(t) => <Reading position={p()} tree={t()} sel={props.sel} />}
                          </Show>
                        )}
                      </Show>
                    )}
                  </For>
                </div>
              }
            >
              <Show
                when={childRefs().length > 0}
                fallback={<p class="empty empty--quiet">Proposers are drafting the first arguments…</p>}
              >
                <ul class="tree" aria-label="Deliberation tree">
                  <For each={childRefs()}>{(ref) => <ClaimCard claimRef={ref} index={props.index} sel={props.sel} />}</For>
                </ul>
              </Show>
            </Show>
          </>
        );
      }}
    </Show>
  );
}

/** A link's argument, for the "now" line: the link text without "is a reason for …". */
const linkArgumentOf = (text: string) => linkParts(text)?.argument ?? text;

/** How many things the "now" line names before it says "+N more": one, so the line never wraps. */
const NOW_SHOWN = 1;

/**
 * "Now: gathering arguments on “…” · weighing link “…”": what the question's
 * deliberation is doing this moment — the claims and links being explored or
 * judged (SPEC §3 "Links as claims", NodeDto.activity). One line of fixed
 * height, so the tree below never shifts; the rest is counted ("+2 more") and
 * listed in its tooltip. Not a live region: it changes several times a second.
 */
function NowLine(props: { graph: () => GraphDto; root: string }) {
  const items = createMemo(() => activityOf(props.graph().nodes, props.root));
  // Keyed by ref (a new frame is all new objects), so an item stays put while it is in flight.
  const shownRefs = createMemo(() => items().slice(0, NOW_SHOWN).map((it) => it.ref), undefined, {
    equals: (a, b) => a.length === b.length && a.every((r, i) => r === b[i]),
  });
  const byRef = createMemo(() => new Map(items().map((it) => [it.ref, it])));
  return (
    <p class="now" classList={{ 'is-idle': items().length === 0 }} aria-label="Now exploring">
      <span class="now__label">now</span>
      <Show when={items().length > 0} fallback={<span class="now__idle">nothing in flight</span>}>
        <For each={shownRefs()}>
          {(ref) => (
            <Show when={byRef().get(ref)}>
              {(it) => (
                <span class={`now__item now__item--${it().activity}`} title={it().text}>
                  <span class="now__dot" aria-hidden="true" />
                  {ACTIVITY_VERB[it().activity]}{' '}
                  <Show when={it().kind === 'link'}>
                    <span class="tag tag--link">link</span>{' '}
                  </Show>
                  <span class="now__text">{clip(it().kind === 'link' ? linkArgumentOf(it().text) : it().text)}</span>
                </span>
              )}
            </Show>
          )}
        </For>
        <Show when={items().length > NOW_SHOWN}>
          <span
            class="now__more"
            title={items()
              .slice(NOW_SHOWN)
              .map((it) => `${ACTIVITY_VERB[it.activity]} ${it.kind === 'link' ? 'link ' : ''}“${it.kind === 'link' ? linkArgumentOf(it.text) : it.text}”`)
              .join('\n')}
          >
            +{items().length - NOW_SHOWN} more
          </span>
        </Show>
      </Show>
    </p>
  );
}
