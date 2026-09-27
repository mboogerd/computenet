import { createEffect, createMemo, createSignal, For, on, Show } from 'solid-js';
import { ACTIVE_STATUSES, type NodeDto, type Override } from '../api/types';
import { source } from '../sync/store';
import type { TreeNode } from '../tree/buildTree';
import { num, pct, STATUS_HINT, STATUS_LABEL } from '../util/format';

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

export function ClaimCard(props: { claimRef: string; index: () => TreeIndex; isRoot?: boolean }) {
  const entry = () => props.index().get(props.claimRef);
  const [collapsed, setCollapsed] = createSignal(false);

  return (
    <Show when={entry()}>
      {(e) => {
        const claim = () => e().node.claim;
        const edge = () => e().edge;
        const side = () => (edge() ? (edge()!.polarity === 'SUPPORT' ? 'pro' : 'con') : 'root');
        const childRefs = createMemo(() => e().node.children.map((a) => a.node.claim.ref), undefined, {
          equals: (a, b) => a.length === b.length && a.every((r, i) => r === b[i]),
        });
        const counts = () => {
          const ch = e().node.children;
          const pro = ch.filter((a) => a.edge.polarity === 'SUPPORT').length;
          return { pro, con: ch.length - pro };
        };
        const busy = () => claim().status !== undefined && ACTIVE_STATUSES.has(claim().status!);

        return (
          <li class={`branch branch--${side()}`}>
            <Show when={edge()}>
              {(ed) => (
                <div class="connector" title="Relation strength: how strongly this bears on its parent">
                  <span class="connector__pol">{side() === 'pro' ? 'Pro' : 'Con'}</span>
                  <span class="connector__meter" aria-hidden="true">
                    <span style={{ width: `${(ed().strength ?? 0) * 100}%` }} />
                  </span>
                  <span class="connector__strength">
                    {ed().strength === undefined ? 'strength pending' : `strength ${pct(ed().strength)}`}
                  </span>
                </div>
              )}
            </Show>
            <article class="card" classList={{ 'card--root': props.isRoot, 'is-busy': busy() }}>
              <header class="card__meta">
                <Show when={claim().proposer && claim().proposer !== 'question'}>
                  <span class={`badge badge--${claim().proposer}`}>{claim().proposer}</span>
                </Show>
                <Show when={claim().status}>
                  {(s) => (
                    <span class={`pill pill--${s().toLowerCase()}`} classList={{ 'is-busy': busy() }} title={STATUS_HINT[s()]}>
                      <span class="pill__dot" aria-hidden="true" />
                      {STATUS_LABEL[s()]}
                    </span>
                  )}
                </Show>
                <span class="card__spacer" />
                <OverrideControl id={claim().ref} value={claim().override ?? 'AUTO'} />
              </header>

              <p class="card__text">{claim().text}</p>

              <footer class="card__foot">
                <div class="credence" title="Propagated credence">
                  <span class="credence__bar" aria-hidden="true">
                    <span style={{ width: `${claim().credence * 100}%` }} />
                  </span>
                  <span class="credence__value">{pct(claim().credence)}</span>
                </div>
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
                <Show when={claim().error}>
                  <span class="card__error" title={claim().error}>error</span>
                </Show>
                <details class="details">
                  <summary>details</summary>
                  <dl>
                    <dt>plausibility</dt><dd>{num(claim().plausibility)}</dd>
                    <dt>relevance</dt><dd>{num(claim().relevance)}</dd>
                    <dt>pro saturation</dt><dd>{num(claim().proSaturation)}</dd>
                    <dt>con saturation</dt><dd>{num(claim().conSaturation)}</dd>
                    <dt>rounds</dt><dd>{claim().rounds ?? '—'}</dd>
                    <dt>duplicates dropped</dt><dd>{claim().duplicatesDropped ?? '—'}</dd>
                    <Show when={edge()?.strength !== undefined}>
                      <dt>relation strength</dt><dd>{num(edge()!.strength)}</dd>
                    </Show>
                    <Show when={claim().error}>
                      <dt>error</dt><dd class="details__error">{claim().error}</dd>
                    </Show>
                    <dt>ref</dt><dd class="mono">{claim().ref}</dd>
                  </dl>
                </details>
              </footer>
            </article>
            <Show when={childRefs().length > 0 && !collapsed()}>
              <ul class="children">
                <For each={childRefs()}>{(ref) => <ClaimCard claimRef={ref} index={props.index} />}</For>
              </ul>
            </Show>
          </li>
        );
      }}
    </Show>
  );
}

const MODES: Override[] = ['AUTO', 'EXPAND', 'STOP'];
const MODE_LABEL: Record<Override, string> = { AUTO: 'Auto', EXPAND: 'Expand', STOP: 'Stop' };
const MODE_HINT: Record<Override, string> = {
  AUTO: 'Let Jev decide whether to expand',
  EXPAND: 'Force expansion of this claim',
  STOP: 'Stop exploring this claim',
};

function OverrideControl(props: { id: string; value: Override }) {
  // Optimistic until the next frame reports a different server value.
  const [pending, setPending] = createSignal<Override>();
  // Memo so a new frame carrying the same value does not clear `pending`.
  const server = createMemo(() => props.value);
  createEffect(on(server, () => setPending(undefined), { defer: true }));
  const shown = () => pending() ?? server();

  const choose = async (mode: Override) => {
    if (mode === shown()) return;
    setPending(mode);
    try {
      await source.override(props.id, mode);
    } catch (err) {
      console.warn('deliberate: override failed', err);
      setPending(undefined);
    }
  };

  return (
    <div class="seg" role="group" aria-label="Exploration override">
      <For each={MODES}>
        {(m) => (
          <button
            type="button"
            class="seg__btn"
            classList={{ 'is-on': shown() === m, [`seg__btn--${m.toLowerCase()}`]: true }}
            aria-pressed={shown() === m}
            title={MODE_HINT[m]}
            onClick={() => void choose(m)}
          >
            {MODE_LABEL[m]}
          </button>
        )}
      </For>
    </div>
  );
}
