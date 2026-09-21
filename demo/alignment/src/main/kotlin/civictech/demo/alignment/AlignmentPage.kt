package civictech.demo.alignment

/**
 * The deliberately minimal slider page served at `/` (computenet-sigl0.2).
 * Feature computenet-v48mn replaces this body with the real two-view UI; it is
 * plain HTML/JS on purpose — no framework, no build step, no styling effort.
 *
 * The rating panel reads the bias-safe `/topics/{t}/me` view; the ranked list
 * (`id="ranking"`) re-renders from every `/events` frame. The script avoids
 * `$` entirely so this stays a `const` raw string.
 */
const val PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>alignment</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 900px; margin: 1.5rem auto; padding: 0 1rem; }
  section { border-top: 1px solid #ddd; padding: .6rem 0; }
  .unrated { opacity: .45; }
  .split { color: #b45309; }
  label { display: inline-block; min-width: 7rem; }
</style>
</head>
<body>
<h1>Alignment</h1>
<section>
  as <input id="participant" maxlength="40">
</section>
<section>
  <form id="topicForm">
    new topic <input id="topicTitle" placeholder="title">
    <input id="topicDims" placeholder="dimensions, comma-separated">
    <button>create</button>
  </form>
  topic <select id="topicSel"></select>
</section>
<section>
  <form id="ideaForm">
    new idea <input id="ideaTitle" placeholder="title">
    <input id="ideaDesc" placeholder="description (optional)">
    <button>add</button>
  </form>
</section>
<section>
  <h2>Rate</h2>
  <div id="rate"></div>
  <template id="sliderRow"><div><label></label><input type="range" min="1" max="9"><span></span><button>clear</button></div></template>
</section>
<section>
  <h2>Ranking</h2>
  <ul id="ranking"></ul>
</section>
<script>
let state = { topics: [], ideas: [], ratings: [], aggregates: {} };
const who = document.getElementById('participant');
who.value = sessionStorage.participant || ('p-' + Math.random().toString(36).slice(2, 6));
sessionStorage.participant = who.value;
who.onchange = () => { sessionStorage.participant = who.value.trim(); renderRate(); };
const me = () => who.value.trim();
const send = (method, url, body) => fetch(url, {
  method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
}).then(r => r.ok ? r.json() : r.json().then(j => { alert(j.error || r.status); throw j; }));
const sel = document.getElementById('topicSel');
const topic = () => sel.value;
sel.onchange = () => { renderRanking(); renderRate(); };

document.getElementById('topicForm').onsubmit = e => {
  e.preventDefault();
  const dims = topicDims.value.split(',').map(s => s.trim()).filter(s => s).map(n => ({ name: n }));
  send('POST', '/topics', { creator: me(), title: topicTitle.value, dimensions: dims })
    .then(j => { topicTitle.value = ''; topicDims.value = ''; sel.dataset.want = j.id; });
};
document.getElementById('ideaForm').onsubmit = e => {
  e.preventDefault();
  if (!topic()) return;
  send('POST', '/topics/' + topic() + '/ideas', { participant: me(), title: ideaTitle.value, description: ideaDesc.value })
    .then(() => { ideaTitle.value = ''; ideaDesc.value = ''; });
};

function renderTopics() {
  const want = sel.dataset.want || sel.value;
  sel.innerHTML = '';
  for (const t of state.topics) {
    const o = document.createElement('option');
    o.value = t.id; o.textContent = t.title;
    sel.appendChild(o);
  }
  if (state.topics.some(t => t.id === want)) { sel.value = want; delete sel.dataset.want; }
}

function renderRanking() {
  const box = document.getElementById('ranking');
  box.innerHTML = '';
  const agg = state.aggregates[topic()];
  if (!agg) return;
  for (const i of agg.ideas) {
    const li = document.createElement('li');
    li.textContent = (i.rank === null ? '-' : '#' + i.rank) + ' ' + i.title + '  ' +
      (i.score === null ? 'unrated' : i.score.toFixed(2)) + (i.split ? '  (split)' : '');
    if (i.split) li.className = 'split';
    box.appendChild(li);
  }
}

// the rating panel reads the bias-safe /me view: your own ratings, nothing aggregate
let rateSeq = 0;
function renderRate() {
  const box = document.getElementById('rate');
  if (!topic() || !me()) { box.innerHTML = ''; return; }
  if (document.activeElement && document.activeElement.type === 'range') return; // don't yank a slider mid-drag
  const seq = ++rateSeq;
  fetch('/topics/' + topic() + '/me?participant=' + encodeURIComponent(me())).then(r => r.json()).then(view => {
    if (seq !== rateSeq) return;
    box.innerHTML = '';
    for (const idea of view.ideas) {
      const div = document.createElement('div');
      const h = document.createElement('h3');
      h.textContent = idea.title + ' (' + idea.rated + '/' + idea.total + ' rated)';
      div.appendChild(h);
      for (const dim of Object.keys(idea.ratings)) {
        const v = idea.ratings[dim];
        const row = document.getElementById('sliderRow').content.firstElementChild.cloneNode(true);
        row.querySelector('label').textContent = dim;
        const input = row.querySelector('input');
        input.value = v === null ? '5' : String(v);
        if (v === null) row.className = 'unrated'; // explicit "unrated" until touched
        const shown = row.querySelector('span');
        shown.textContent = v === null ? ' unrated ' : ' ' + v + ' ';
        input.oninput = () => { shown.textContent = ' ' + input.value + ' '; row.className = ''; };
        input.onchange = () => send('POST', '/topics/' + topic() + '/rate',
          { participant: me(), idea: idea.id, dim: dim, value: Number(input.value) }).then(() => { input.blur(); renderRate(); });
        row.querySelector('button').onclick = () => send('POST', '/topics/' + topic() + '/rate',
          { participant: me(), idea: idea.id, dim: dim, value: null });
        div.appendChild(row);
      }
      box.appendChild(div);
    }
  });
}

new EventSource('/events').onmessage = e => {
  state = JSON.parse(e.data);
  renderTopics(); renderRanking(); renderRate();
};
</script>
</body>
</html>
"""
