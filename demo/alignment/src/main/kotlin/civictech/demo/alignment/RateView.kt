package civictech.demo.alignment

/**
 * The Rate view (computenet-v48mn.1, carried into the v2 shell by computenet-0dvra.1), one
 * slice of the served [PAGE]: its CSS, the `<section id="rate">` markup and the `<script>`
 * defining `renderRate()`.
 *
 * The bias-safe personal view: it reads only `state.topics` (via `currentTopic()`) and the
 * caller's own `/topics/{t}/me` view (via the shell's `fetchMe`, which also feeds the board
 * gate's cache) — never `state.ratings` or `state.aggregates`.
 *
 * Carried over from v1 with only the adaptations the shell forces: the topic id comes from the
 * URL (`topicId()`), the "new topic" form moved to the landing, the fetch goes through
 * `fetchMe`, and literal colours became tokens. The restyle, policy-gated add-idea form and
 * anchor labels are computenet-0dvra.3's. Shared helper contract: the comment block at the top
 * of the shell's script in [AlignmentPage.kt]. No `$` anywhere.
 */
internal const val RATE_VIEW = """
<style>
  #rate .row { display: grid; grid-template-columns: 9rem minmax(0,1fr) 2.2rem auto; gap: .6rem; align-items: center; padding: .25rem 0; font-size: var(--fs-2); }
  #rate .row label { color: var(--ink); }
  #rate .row .v { text-align: right; font-variant-numeric: tabular-nums; color: var(--ink); }
  #rate .row.unrated .v { color: var(--unrated); }
  #rate .anchors { display: flex; justify-content: space-between; margin: -.2rem 0 .5rem; padding: 0 0 0 9.6rem; font-size: var(--fs-1); color: var(--muted); }
  #rate .row button { padding: .15rem .5rem; font-size: var(--fs-1); }
</style>
<section id="rate" class="pane view" hidden>
  <form class="inline" id="ideaForm">
    new idea <input id="ideaTitle" placeholder="title">
    <input id="ideaDesc" placeholder="description (optional)">
    <button>add</button>
  </form>
  <p class="muted" id="ideaGate" hidden>only the facilitator adds ideas here</p>
  <div id="ideas"></div>
  <template id="sliderRow"><div class="row"><label></label><input type="range" min="1" max="9" step="1"><span class="v"></span><button type="button">clear</button></div></template>
</section>
<script>
document.getElementById('ideaForm').onsubmit = e => {
  e.preventDefault();
  if (!topicId()) return;
  send('POST', '/topics/' + topicId() + '/ideas', { participant: me(), title: ideaTitle.value, description: ideaDesc.value })
    .then(() => { ideaTitle.value = ''; ideaDesc.value = ''; renderRate(); });
};

// the rating panel reads only state.topics (dimension names, creator) and
// the bias-safe /me view: your own ratings, nothing aggregate, ever
let rateSeq = 0;
let expandedDesc = new Set();
function renderRate() {
  const box = document.getElementById('ideas');
  if (document.getElementById('rate').hidden) return;
  if (!topicId() || !me()) { box.innerHTML = ''; return; }
  if (document.activeElement && document.activeElement.type === 'range') return; // don't yank a slider mid-drag
  const seq = ++rateSeq;
  const tid = topicId();
  fetchMe(tid).then(view => {
    if (seq !== rateSeq) return;
    const t = currentTopic();
    const dims = t ? t.dimensions : [];
    const form = document.getElementById('ideaForm');
    const gateLine = document.getElementById('ideaGate');
    const allowed = !t || mayAddIdea(t);
    form.hidden = !allowed;
    gateLine.hidden = allowed;
    box.innerHTML = '';
    for (const idea of view.ideas) {
      const card = document.createElement('div');
      card.className = 'card';
      const h = document.createElement('h3');
      const title = document.createElement('span');
      title.textContent = idea.title;
      const count = document.createElement('span');
      count.className = 'count';
      count.textContent = idea.rated + ' of ' + idea.total + ' rated';
      h.appendChild(title); h.appendChild(count);
      card.appendChild(h);
      if (idea.description) {
        const open = expandedDesc.has(idea.id);
        const toggle = document.createElement('button');
        toggle.type = 'button'; toggle.className = 'toggle';
        toggle.textContent = open ? 'hide description' : 'show description';
        const pre = document.createElement('pre');
        pre.textContent = idea.description;
        pre.hidden = !open;
        toggle.onclick = () => {
          const nowOpen = !expandedDesc.has(idea.id);
          if (nowOpen) expandedDesc.add(idea.id); else expandedDesc.delete(idea.id);
          pre.hidden = !nowOpen;
          toggle.textContent = nowOpen ? 'hide description' : 'show description';
        };
        card.appendChild(toggle);
        card.appendChild(pre);
      }
      for (const dim of dims) {
        const v = idea.ratings[dim.id];
        const row = document.getElementById('sliderRow').content.firstElementChild.cloneNode(true);
        row.querySelector('label').textContent = dim.name;
        const input = row.querySelector('input');
        const shown = row.querySelector('span');
        const clearBtn = row.querySelector('button');
        const colour = dimColour(t, dim);
        const apply = val => {
          if (val === null || val === undefined) {
            row.className = 'row unrated';
            input.value = '5';
            shown.textContent = '—';
            clearBtn.disabled = true;
            input.style.setProperty('accent-color', 'var(--unrated)');
          } else {
            row.className = 'row';
            input.value = String(val);
            shown.textContent = String(val);
            clearBtn.disabled = false;
            input.style.setProperty('accent-color', colour);
          }
        };
        apply(v);
        input.oninput = () => { row.className = 'row'; shown.textContent = input.value; clearBtn.disabled = false; input.style.setProperty('accent-color', colour); };
        input.onchange = () => send('POST', '/topics/' + tid + '/rate',
          { participant: me(), idea: idea.id, dim: dim.id, value: Number(input.value) }).then(() => { input.blur(); renderRate(); });
        clearBtn.onclick = () => send('POST', '/topics/' + tid + '/rate',
          { participant: me(), idea: idea.id, dim: dim.id, value: null }).then(() => renderRate());
        card.appendChild(row);
        const low = dim.lowLabel || '';
        const high = dim.highLabel || '';
        if (low || high) {
          const anchors = document.createElement('div');
          anchors.className = 'anchors';
          const l = document.createElement('span'); l.textContent = low || '1';
          const h = document.createElement('span'); h.textContent = high || '9';
          anchors.appendChild(l); anchors.appendChild(h);
          card.appendChild(anchors);
        }
      }
      box.appendChild(card);
    }
  }, () => {});
}
</script>
"""
