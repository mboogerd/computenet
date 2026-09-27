import { createSignal, For, onCleanup, onMount, Show } from 'solid-js';
import type { BackendCostDto, QuestionDto } from '../api/types';
import { BACKEND_NAMES, costLabel, projectionText, tokens, usd } from '../util/format';

export type CostPopoverAction = 'toggle' | 'outside' | 'escape';

/** Kept explicit so all three close/toggle paths have deterministic unit coverage. */
export function nextCostPopoverState(open: boolean, action: CostPopoverAction): boolean {
  return action === 'toggle' ? !open : false;
}

/**
 * SPEC §12: the question's spend as one quiet dollar figure; the button opens
 * a compact popover with what it is made of. Escape or a click outside closes it.
 */
export function CostBadge(props: { question: QuestionDto; initiallyOpen?: boolean }) {
  const [open, setOpen] = createSignal(props.initiallyOpen ?? false);
  let el!: HTMLSpanElement;
  let button!: HTMLButtonElement;
  const id = () => `cost-${props.question.root}`;
  onMount(() => {
    const onClick = (e: MouseEvent) => {
      if (open() && !el.contains(e.target as Node)) setOpen(nextCostPopoverState(open(), 'outside'));
    };
    const onKey = (e: KeyboardEvent) => {
      if (open() && e.key === 'Escape') {
        setOpen(nextCostPopoverState(open(), 'escape'));
        button.focus();
      }
    };
    document.addEventListener('click', onClick);
    document.addEventListener('keydown', onKey);
    onCleanup(() => {
      document.removeEventListener('click', onClick);
      document.removeEventListener('keydown', onKey);
    });
  });
  return (
    <span class="cost" ref={el}>
      <button
        ref={button}
        type="button"
        class="cost__btn"
        aria-haspopup="dialog"
        aria-expanded={open()}
        aria-controls={id()}
        title="Estimated cost of this question so far — click for details"
        onClick={() => setOpen((o) => nextCostPopoverState(o, 'toggle'))}
      >
        {costLabel(props.question)}
      </button>
      <Show when={open()}>
        <CostPanel id={id()} question={props.question} />
      </Show>
    </span>
  );
}

/**
 * The popover body: the total, the projection, and one line per backend
 * (dollars and calls). Tokens, rates, sources and caveats sit behind a
 * "pricing details" disclosure; anything left out of the total says so up front.
 */
export function CostPanel(props: { id: string; question: QuestionDto }) {
  const backends = () => props.question.cost?.backends ?? [];
  const excluded = () => backends().filter((b) => b.unpricedCalls > 0);
  const name = (b: BackendCostDto) => BACKEND_NAMES[b.backend] ?? b.backend;
  return (
    <div class="cost__panel" id={props.id} role="dialog" aria-label="Estimated cost">
      <p class="cost__total">
        <span>Estimated cost</span>
        <strong>{costLabel(props.question)}</strong>
      </p>
      <p class="cost__projection">{projectionText(props.question)}</p>
      <Show when={backends().length > 0} fallback={<p class="cost__empty">No tracked calls yet.</p>}>
        <ul class="cost__list">
          <For each={backends()}>
            {(b) => (
              <li class="cost__row">
                <span class="cost__name">{name(b)}</span>
                <span class="cost__calls">
                  {b.calls} call{b.calls === 1 ? '' : 's'}
                </span>
                <span class="cost__usd">{b.usd === undefined ? 'rate unknown' : usd(b.usd)}</span>
              </li>
            )}
          </For>
        </ul>
      </Show>
      <Show when={excluded().length > 0}>
        <p class="cost__warn">
          Not in the total: {excluded().map((b) => `${b.unpricedCalls} ${name(b)} call${b.unpricedCalls === 1 ? '' : 's'}`).join(', ')} (no price known).
        </p>
      </Show>
      <Show when={backends().length > 0}>
        <details class="cost__details">
          <summary>pricing details</summary>
          <ul class="cost__list">
            <For each={backends()}>{(b) => <BackendDetails b={b} />}</For>
          </ul>
          <Show when={props.question.cost?.perRoundUsd !== undefined}>
            <p class="cost__foot">
              {usd(props.question.cost!.perRoundUsd)} per round over {props.question.cost!.rounds} rounds.
            </p>
          </Show>
        </details>
      </Show>
    </div>
  );
}

function BackendDetails(props: { b: BackendCostDto }) {
  const b = () => props.b;
  const tokenLine = () => {
    const parts = [`${tokens(b().inputTokens)} in`];
    if (b().cachedInputTokens > 0) parts.push(`${tokens(b().cachedInputTokens)} cached`);
    if (b().cacheWriteTokens > 0) parts.push(`${tokens(b().cacheWriteTokens)} cache writes`);
    parts.push(`${tokens(b().outputTokens)} out`);
    if (b().reasoningTokens > 0) parts.push(`${tokens(b().reasoningTokens)} reasoning`);
    return parts.join(' · ');
  };
  return (
    <li class="cost__detail">
      <p class="cost__head">
        <span class="cost__name">{BACKEND_NAMES[b().backend] ?? b().backend}</span>
        <Show when={b().models.length > 0}>
          <span class="cost__model"> {b().models.join(', ')}</span>
        </Show>
      </p>
      <p class="cost__line">{tokenLine()}</p>
      <p class="cost__line cost__rate">
        <Show when={b().assumed}>
          <span class="cost__tag">assumed</span>{' '}
        </Show>
        {b().rate}
      </p>
      <p class="cost__line cost__source">
        {b().rateSource}
        <Show when={b().rateDate}>{(d) => <> · {d()}</>}</Show>
      </p>
      <Show when={b().note}>{(n) => <p class="cost__line cost__note">{n()}</p>}</Show>
    </li>
  );
}
