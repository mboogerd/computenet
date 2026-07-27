import { For, Show, createMemo } from 'solid-js';
import type { EdgeRole, Ref } from '../api/types';
import { portAnchors } from '../layout/ports';
import { layoutEngine } from '../solid/layout';
import { edges, nodes, selection, setSelection, store, structuralVersion } from '../solid/state';
import { colorGlyph, manifestBadge, shortType } from '../util/badges';
import './Canvas.css';

const FUSED_OFFSET = 2.5;

export default function Canvas() {
  // Structure-only dependency: a value-only change (a lifecycle flip, say)
  // never re-runs layout — "value changes only restyle" (10-target-v3.md UI
  // architecture row; M0-FE ticket Context). The layout engine itself
  // (src/layout/layered.ts) is what makes the *result* insertion-stable;
  // this memo is what makes sure it only runs when it has to.
  const layout = createMemo(() => {
    structuralVersion();
    const refs = [...store.nodes.keys()];
    return layoutEngine.compute(refs, store.adjacency());
  });

  const nodeRefs = createMemo(() => [...layout().nodes.keys()]);
  const edgeIds = createMemo(() => Object.keys(edges));

  function anchorOf(ref: Ref, port: string) {
    const ln = layout().nodes.get(ref);
    const rec = nodes[ref];
    if (!ln || !rec) return undefined;
    return portAnchors(ln, rec.ports).get(port);
  }

  function onCardKeyDown(e: KeyboardEvent, ref: Ref) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setSelection(ref);
    }
  }

  // Click-through-to-deselect only when the click landed on the scene
  // background itself, not a card bubbling up.
  function onSceneClick(e: MouseEvent) {
    if (e.currentTarget === e.target) setSelection(null);
  }

  return (
    <div class="canvas">
      <Show when={nodeRefs().length > 0} fallback={<p class="canvas__empty">No cells reported yet.</p>}>
        <div
          class="canvas__scene"
          style={{ width: `${layout().width}px`, height: `${layout().height}px` }}
          onClick={onSceneClick}
        >
          <svg class="canvas__svg" width={layout().width} height={layout().height}>
            <For each={edgeIds()}>
              {(id) => {
                const e = () => edges[id];
                const from = () => (e() ? anchorOf(e()!.from.ref, e()!.from.port) : undefined);
                const to = () => (e() ? anchorOf(e()!.to.ref, e()!.to.port) : undefined);
                return (
                  <Show when={e() && from() && to()}>
                    <EdgeLine
                      role={e()!.role}
                      fused={e()!.fused === true}
                      x1={from()!.x}
                      y1={from()!.y}
                      x2={to()!.x}
                      y2={to()!.y}
                    />
                  </Show>
                );
              }}
            </For>

            <For each={nodeRefs()}>
              {(ref) => {
                const rec = () => nodes[ref];
                const ln = () => layout().nodes.get(ref);
                const anchors = () => (rec() && ln() ? portAnchors(ln()!, rec()!.ports) : undefined);
                return (
                  <For each={rec()?.ports ?? []}>
                    {(p) => {
                      const a = () => anchors()?.get(p.name);
                      return (
                        <Show when={a()}>
                          <circle class="port-dot" data-dir={p.dir} cx={a()!.x} cy={a()!.y} r="3">
                            <title>
                              {p.name} ({p.dir})
                            </title>
                          </circle>
                        </Show>
                      );
                    }}
                  </For>
                );
              }}
            </For>
          </svg>

          <For each={nodeRefs()}>
            {(ref) => {
              const rec = () => nodes[ref];
              const ln = () => layout().nodes.get(ref);
              return (
                <Show when={rec() && ln()}>
                  <div
                    class="node-card"
                    classList={{
                      'is-selected': selection() === ref,
                      'is-suspended': rec()!.lifecycle === 'SUSPENDED',
                    }}
                    style={{
                      left: `${ln()!.x}px`,
                      top: `${ln()!.y}px`,
                      width: `${ln()!.w}px`,
                      height: `${ln()!.h}px`,
                    }}
                    role="button"
                    tabIndex={0}
                    aria-pressed={selection() === ref}
                    onClick={(e) => {
                      e.stopPropagation();
                      setSelection(ref);
                    }}
                    onKeyDown={(e) => onCardKeyDown(e, ref)}
                  >
                    <div class="node-card__top">
                      <span
                        class="node-card__chip"
                        data-color={rec()!.color ?? 'unknown'}
                        title={rec()!.color ?? 'color unknown'}
                      >
                        {colorGlyph(rec()!.color)}
                      </span>
                      <span class="node-card__name">{rec()!.name ?? ref.slice(0, 8)}</span>
                    </div>
                    <div class="node-card__type" title={rec()!.typeFqn}>
                      {shortType(rec()!.typeFqn)}
                    </div>
                    <Show when={rec()!.manifests.length}>
                      <div class="node-card__badges">
                        <For each={rec()!.manifests}>
                          {(m) => (
                            <span class="node-card__badge" title={m}>
                              {manifestBadge(m)}
                            </span>
                          )}
                        </For>
                      </div>
                    </Show>
                  </div>
                </Show>
              );
            }}
          </For>
        </div>
      </Show>
    </div>
  );
}

function EdgeLine(props: {
  role: EdgeRole;
  fused: boolean;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}) {
  const dx = () => props.x2 - props.x1;
  const dy = () => props.y2 - props.y1;
  const len = () => Math.hypot(dx(), dy()) || 1;
  const nx = () => (-dy() / len()) * FUSED_OFFSET;
  const ny = () => (dx() / len()) * FUSED_OFFSET;
  const dash = () => (props.role === 'OBSERVE' ? '5 3' : undefined);
  const cls = () => `edge edge--${props.role.toLowerCase()}`;

  return (
    <Show
      when={props.fused}
      fallback={
        <line class={cls()} x1={props.x1} y1={props.y1} x2={props.x2} y2={props.y2} stroke-dasharray={dash()} />
      }
    >
      <g class={`${cls()} is-fused`}>
        <line
          x1={props.x1 + nx()}
          y1={props.y1 + ny()}
          x2={props.x2 + nx()}
          y2={props.y2 + ny()}
          stroke-dasharray={dash()}
        />
        <line
          x1={props.x1 - nx()}
          y1={props.y1 - ny()}
          x2={props.x2 - nx()}
          y2={props.y2 - ny()}
          stroke-dasharray={dash()}
        />
      </g>
    </Show>
  );
}
