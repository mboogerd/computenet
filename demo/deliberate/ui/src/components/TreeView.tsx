import { createMemo, createSignal, For, onCleanup, onMount, Show } from 'solid-js';
import { DEFAULT_CONSENSUS, type GraphDto } from '../api/types';
import { buildTree } from '../tree/buildTree';
import {
  ACTIVITY_VERB,
  activityOf,
  agreementText,
  clip,
  linkParts,
  questionProgress,
  shown,
  STATUS_HINT,
  STATUS_LABEL,
  stoppedHint,
  stoppedText,
  verdict,
} from '../util/format';
import { createTween } from '../util/tween';
import { ClaimCard, Facts, indexTree, sideCounts, SpreadBand, useChildRefs, type Selection, type TreeIndex } from './ClaimCard';
import { CostBadge } from './CostBadge';
import { OverrideControl } from './OverrideControl';
import { PauseControl } from './PauseControl';

export function TreeView(props: { graph: GraphDto; root: string }) {
  const tree = createMemo(() => buildTree(props.graph, props.root));
  const index = createMemo<TreeIndex>(() => {
    const t = tree();
    return t ? indexTree(t) : new Map();
  });

  const [selected, setSelected] = createSignal<string>();
  const sel: Selection = {
    selected,
    toggle: (ref) => setSelected((cur) => (cur === ref ? undefined : ref)),
    members: () => props.graph.consensusMembers ?? DEFAULT_CONSENSUS,
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
        {(root) => <Question root={root} index={index} graph={() => props.graph} sel={sel} />}
      </Show>
    </Show>
  );
}

/** The question is the hero: large text, one credence gauge, a plain verdict, and progress. */
function Question(props: { root: string; index: () => TreeIndex; graph: () => GraphDto; sel: Selection }) {
  const entry = () => props.index().get(props.root);
  const childRefs = useChildRefs(entry);
  const progress = createMemo(() => questionProgress(props.graph().nodes, props.root));
  const question = () => props.graph().questions.find((q) => q.root === props.root);

  return (
    <Show when={entry()}>
      {(e) => {
        const claim = () => e().node.claim;
        // Label and number come from the same (animated) value, so they never disagree mid-glide.
        // The headline is the consensus of the credence rules; the band behind it is their spread.
        const tweened = createTween(() => shown(claim()));
        const v = () => verdict(tweened(), 'question');
        const counts = () => sideCounts(e().node);
        const open = () => props.sel.selected() === claim().ref;
        const paused = () => question()?.paused === true;
        // A paused question with queued claims is waiting, not working (CTL-05).
        const busy = () => progress().active > 0 && !paused();
        const override = () => claim().override ?? 'AUTO';

        return (
          <>
            <section class="hero" classList={{ 'is-open': open(), 'is-busy': busy() }} aria-label="Question">
              <h2 class="hero__q">{claim().text}</h2>

              <div
                class="gauge"
                title={`Credence: how likely the answer is yes, after weighing every argument — the consensus of the credence rules (${agreementText(claim(), e().node.children.length === 0)})`}
              >
                <span class="gauge__con" aria-hidden="true">no</span>
                <span class="gauge__track" aria-hidden="true">
                  <span class="gauge__fill" style={{ transform: `scaleX(${shown(claim())})` }} />
                  <SpreadBand node={claim()} class="gauge__band" />
                  <span class="gauge__mid" />
                </span>
                <span class="gauge__pro" aria-hidden="true">yes</span>
              </div>

              <div class="hero__row">
                <p class={`verdict verdict--${v().lean}`} aria-live="polite">
                  <span class="verdict__label">{v().label}</span>
                  <span class="verdict__sep" aria-hidden="true">·</span>
                  <span class="verdict__num">
                    {`${v().percent}%`}
                  </span>
                </p>
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
                <Show when={paused()}>
                  <span class="hero__paused" title="Paused: no new round starts until you resume this question">
                    paused
                  </span>
                </Show>
                <Show when={stoppedText(question())}>
                  {(text) => (
                    <span class="hero__stopped" title={stoppedHint(question())}>
                      {text()}
                    </span>
                  )}
                </Show>
                <Show when={claim().status && (claim().status === 'STOPPED' || claim().status === 'FAILED')}>
                  <span class="status status--halted" title={STATUS_HINT[claim().status!]}>
                    question {STATUS_LABEL[claim().status!]}
                  </span>
                </Show>
                <span class="card__spacer" />
                <Show when={question()}>{(q) => <CostBadge question={q()} />}</Show>
                <Show when={question()}>{(q) => <PauseControl root={q().root} paused={q().paused === true} />}</Show>
                <span class="reveal" classList={{ 'is-pinned': override() !== 'AUTO' }}>
                  <OverrideControl id={claim().ref} value={override()} />
                </span>
              </p>

              <NowLine graph={props.graph} root={props.root} />

              <Show when={open()}>
                <Facts
                  id={`facts-${claim().ref}`}
                  claim={claim()}
                  leaf={e().node.children.length === 0}
                  members={props.sel.members?.()}
                />
              </Show>
            </section>

            <Show
              when={childRefs().length > 0}
              fallback={<p class="empty empty--quiet">Proposers are drafting the first arguments…</p>}
            >
              <ul class="tree" aria-label="Deliberation tree">
                <For each={childRefs()}>{(ref) => <ClaimCard claimRef={ref} index={props.index} sel={props.sel} />}</For>
              </ul>
            </Show>
          </>
        );
      }}
    </Show>
  );
}

/** A link's argument, for the "now" line: the link text without "is a reason for …". */
const linkArgumentOf = (text: string) => linkParts(text)?.argument ?? text;

/** How many things the "now" line names before it says "+N more". */
const NOW_SHOWN = 3;

/**
 * "Now: gathering arguments on “…” · weighing link “…”": what the question's
 * deliberation is doing this moment — the claims and links being explored or
 * judged (SPEC §3 "Links as claims", NodeDto.activity). Not a live region: it
 * changes several times a second while busy.
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
          <span class="now__more">+{items().length - NOW_SHOWN} more</span>
        </Show>
      </Show>
    </p>
  );
}
