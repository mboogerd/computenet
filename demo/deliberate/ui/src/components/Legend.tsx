import { onCleanup, onMount } from 'solid-js';

/** "How to read this": plain-language definitions, a native disclosure so it
 *  works by keyboard and touch; a click outside or Escape closes it. */
export function Legend() {
  let el!: HTMLDetailsElement;
  onMount(() => {
    const close = (e: Event) => {
      if (!el.open) return;
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !el.contains(e.target as Node)) el.open = false;
    };
    document.addEventListener('click', close);
    document.addEventListener('keydown', close);
    onCleanup(() => {
      document.removeEventListener('click', close);
      document.removeEventListener('keydown', close);
    });
  });
  return (
    <details class="legend" ref={el}>
      <summary class="iconbtn" aria-label="How to read this" title="How to read this">
        ?
      </summary>
      <div class="legend__panel">
        <dl>
          <dt>Credence</dt>
          <dd>How likely a claim is true — for the question, how likely the answer is yes — after weighing all its arguments.</dd>
          <dt>Consensus and band</dt>
          <dd>
            Several rules for weighing arguments run side by side; the number is their consensus (an average in log-odds
            of the rules that pass our sanity checks), and the faint band shows the range from the lowest to the highest
            rule — a wide band means the rules disagree.
          </dd>
          <dt>
            <span class="pro-text">Pro</span> / <span class="con-text">Con</span>
          </dt>
          <dd>An argument for or against the claim directly above it.</dd>
          <dt>Undercuts the link</dt>
          <dd>Does not dispute the claim above; it denies that the argument above actually supports or attacks it.</dd>
          <dt>Link strength</dt>
          <dd>If the argument were true, how much it would move the claim above.</dd>
          <dt>Reach</dt>
          <dd>How much a claim can matter to the question: its link strengths multiplied up to the top. Faint claims barely can.</dd>
          <dt>Quality</dt>
          <dd>Whether an argument is well made: self-contained, coherent and actually about the claim above.</dd>
          <dt>Contribution</dt>
          <dd>Reach × relevance × quality. The strongest claims are explored first; too low and a claim is set aside.</dd>
          <dt>Jev</dt>
          <dd>
            The AI judge. It scores every claim and link, sorts new arguments (added, repeat, reworded, merged, nested as
            evidence, moved sides, undercut a link, dropped), and decides what is worth exploring.
          </dd>
          <dt>Status</dt>
          <dd>
            <em>gathering arguments</em> and <em>weighing</em> are live; <em>fully argued</em> means both sides are
            complete; <em>set aside</em> means unlikely to change the answer; <em>depth limit</em>,
            <em> budget spent</em> and <em>round limit</em> are safety stops.
          </dd>
        </dl>
        <p class="legend__tip">Select a claim to see Jev's numbers and to steer it: Auto, Expand or Stop.</p>
      </div>
    </details>
  );
}
