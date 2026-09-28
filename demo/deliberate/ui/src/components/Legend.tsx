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
          <dt>
            <span class="glyph glyph--bar" aria-hidden="true">
              <span class="glyph__fill" />
              <span class="glyph__mark" />
            </span>
            Credence
          </dt>
          <dd>How likely a claim is true after weighing its arguments; for the question, how likely the answer is yes.</dd>
          <dt>
            <span class="glyph glyph--bar" aria-hidden="true">
              <span class="glyph__fill" />
              <span class="glyph__band" />
              <span class="glyph__mark" />
            </span>
            Band
          </dt>
          <dd>
            Several rules weigh the arguments; the number is their consensus. The <em>rules</em> button shows each rule's
            value and the band of their range. Wide means they disagree.
          </dd>
          <dt>First impression</dt>
          <dd>
            Jev's judgment of the question before any argument: the starting point the arguments move. <em>Arguments
            alone</em> weighs the same arguments from an even start; when the two land on different sides, the question
            says so.
          </dd>
          <dt>
            <span class="glyph glyph--stripe glyph--pro" aria-hidden="true" />
            <span class="pro-text">Pro</span> / <span class="glyph glyph--stripe glyph--con" aria-hidden="true" />
            <span class="con-text">Con</span>
          </dt>
          <dd>An argument for or against the claim directly above it.</dd>
          <dt>
            <span class="glyph glyph--link" aria-hidden="true" />
            Link
          </dt>
          <dd>“A is a reason for B” is a claim too. Its chip says how far it holds; press it to open the link and steer it.</dd>
          <dt>
            <span class="glyph glyph--stripe glyph--dashed" aria-hidden="true" />
            Holds / Undercuts
          </dt>
          <dd>Arguments about a link: why it really bears on the claim, or why it does not.</dd>
          <dt>Faint text</dt>
          <dd>Low reach: the claim can barely change the answer to the question.</dd>
          <dt>Jev</dt>
          <dd>The AI judge: it scores claims and links, sorts new arguments and decides what is worth exploring.</dd>
          <dt>Cost</dt>
          <dd>The dollar figure is estimated model spend so far; press it for the backend breakdown and projection.</dd>
          <dt>Status</dt>
          <dd>
            <em>gathering arguments</em> and <em>weighing</em> are live; <em>fully argued</em> is done; <em>set aside</em>{' '}
            won't change the answer; the rest are safety stops.
          </dd>
        </dl>
        <p class="legend__tip">Select a claim for Jev's numbers and to steer it: Auto, Expand or Stop.</p>
      </div>
    </details>
  );
}
