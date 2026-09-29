package civictech.demo.alignment

/**
 * The experimental Gut check view (computenet-teu97, ALN2.6, R14), one slice of the served
 * [PAGE]: its `<style>`, the `<section id="dots">` root and the `<script>` defining
 * `renderDots()`. Registered in the shell by teu97-D7; filled in by teu97-D8.
 *
 * A facilitator-enabled round: each participant distributes a small budget of dots across the
 * topic's ideas as a quick gut check before careful rating. Dots never enter the dataflow or the
 * value/cost score (teu97-D1) — this view reads `currentTopic()` (for the topic's `gutCheck` and
 * `dotBudget`) and the viewer's own `/topics/{t}/me` view ONLY, via the shell's `fetchMe`; it
 * never reads `state.ratings` or `state.aggregates`. The tab itself is hidden by the shell
 * (`renderShell`) unless the topic's round is enabled; `renderDots()` also returns early while
 * `#dots` is hidden, and fetches `/me` on every call so the count shown is always the server's
 * (same pattern as Rate and Compare).
 *
 * `#dotsBudget` shows "you have N of B dots left" (N = the budget minus the viewer's own total
 * across every idea). `#dotsList` holds one `.card` per idea in `/me` order: title, the
 * description collapsed behind a "more" toggle when non-empty, a dot strip (`●` per placed dot,
 * `◦` at zero) and `+`/`−` buttons. A click posts the absolute new count to
 * `POST /topics/{t}/dots`, then re-fetches `/me` and re-renders — on success or on a rejection (the
 * shell's `send` already alerts the error), so the view always ends up showing the server's count.
 *
 * Shared helper contract: the comment block at the top of the shell's script in
 * [AlignmentPage.kt]. No `$` anywhere (a plain raw string, no template literals); no literal
 * colour — only `:root` tokens and `var(--accent)`. Private globals get the `dots` prefix.
 */
internal const val DOTS_VIEW = """
<style>
  #dots .dotsHint { font-size: var(--fs-1); margin: 0 0 .7rem; }
  #dotsBudget { font-weight: 600; margin: 0 0 .8rem; }
  #dotsList .card h3 { margin: 0 0 .3rem; }
  #dotsList .card pre { margin: 0 0 .5rem; }
  .dotsRow { display: flex; align-items: center; gap: .6rem; margin-top: .4rem; }
  .dotsStrip { font-size: var(--fs-3); letter-spacing: .14em; color: var(--accent); }
  .dotsRow button { padding: .2rem .7rem; }
</style>
<section id="dots" class="pane view" hidden>
  <p class="muted dotsHint">A quick gut check: place your dots on the ideas that matter most to you before rating carefully.</p>
  <p id="dotsBudget" class="muted"></p>
  <div id="dotsList"></div>
</section>
<script>
// ── Gut check (experimental, teu97-D7/D8): a lightweight dot-voting pass before careful rating.
// Reads currentTopic() (gutCheck, dotBudget) and the viewer's own /me view ONLY — never
// state.ratings, never state.aggregates. Writes POST /topics/{t}/dots with the absolute new count.
let dotsSeq = 0;
const dotsExpanded = new Set();
const dotsCards = new Map(); // idea id -> card node, reused across renders

function dotsStripText(n) {
  return n > 0 ? '●'.repeat(n) : '○';
}

function dotsPlace(tid, ideaId, count) {
  const resync = () => fetchMe(tid).then(renderDots, () => {});
  send('POST', '/topics/' + encodeURIComponent(tid) + '/dots', { participant: me(), idea: ideaId, count: count })
    .then(resync, resync);
}

function dotsCard(idea, tid, budget, used) {
  let card = dotsCards.get(idea.id);
  if (!card) {
    card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = '<h3><span class="dc-title"></span></h3>' +
      '<button type="button" class="toggle dc-more" hidden></button>' +
      '<pre class="dc-desc" hidden></pre>' +
      '<div class="dotsRow"><span class="dotsStrip" aria-hidden="true"></span>' +
        '<button type="button" class="dc-minus" aria-label="remove a dot">−</button>' +
        '<button type="button" class="dc-plus" aria-label="add a dot">+</button></div>';
    dotsCards.set(idea.id, card);
  }
  card.querySelector('.dc-title').textContent = idea.title;
  const more = card.querySelector('.dc-more');
  const desc = card.querySelector('.dc-desc');
  if (idea.description) {
    more.hidden = false;
    const open = dotsExpanded.has(idea.id);
    more.textContent = open ? 'hide description' : 'show description';
    desc.hidden = !open;
    desc.textContent = idea.description;
    more.onclick = () => {
      const nowOpen = !dotsExpanded.has(idea.id);
      if (nowOpen) dotsExpanded.add(idea.id); else dotsExpanded.delete(idea.id);
      desc.hidden = !nowOpen;
      more.textContent = nowOpen ? 'hide description' : 'show description';
    };
  } else {
    more.hidden = true;
    desc.hidden = true;
  }
  const strip = card.querySelector('.dotsStrip');
  strip.textContent = dotsStripText(idea.dots);
  strip.setAttribute('aria-label', idea.dots + ' dots');
  const minus = card.querySelector('.dc-minus');
  const plus = card.querySelector('.dc-plus');
  minus.disabled = idea.dots <= 0;
  plus.disabled = used >= budget;
  minus.onclick = () => dotsPlace(tid, idea.id, idea.dots - 1);
  plus.onclick = () => dotsPlace(tid, idea.id, idea.dots + 1);
  return card;
}

function renderDots() {
  const box = document.getElementById('dotsList');
  const budgetLine = document.getElementById('dotsBudget');
  if (document.getElementById('dots').hidden) return;
  const tid = topicId();
  const t = currentTopic();
  if (!tid || !t || !me()) { box.innerHTML = ''; budgetLine.textContent = ''; return; }
  const seq = ++dotsSeq;
  fetchMe(tid).then(view => {
    if (seq !== dotsSeq || topicId() !== tid) return; // stale response guard, like Rate's rateSeq
    const budget = t.dotBudget;
    const used = view.ideas.reduce((sum, i) => sum + i.dots, 0);
    budgetLine.textContent = 'you have ' + Math.max(0, budget - used) + ' of ' + budget + ' dots left';
    const seen = new Set();
    for (const idea of view.ideas) {
      seen.add(idea.id);
      box.appendChild(dotsCard(idea, tid, budget, used));
    }
    for (const [id, card] of dotsCards) if (!seen.has(id)) { dotsCards.delete(id); card.remove(); }
  }, () => {});
}
</script>
"""
