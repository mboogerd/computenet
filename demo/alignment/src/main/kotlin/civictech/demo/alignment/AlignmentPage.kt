package civictech.demo.alignment

/**
 * The two-section alignment page served at `/` (computenet-v48mn). Vanilla
 * HTML/JS, no framework, no build step — styling precedent is
 * `demo/backlog-triage/.../TriageApp.kt`'s `PAGE`.
 *
 * `#rate` (this task, computenet-v48mn.1) is the bias-safe personal view: it
 * reads only `state.topics` (dimension names, creator) and `/topics/{t}/me`
 * (the caller's own ratings) — never `state.ratings` or `state.aggregates`.
 * `#board` (sibling task computenet-v48mn.2) is left as its empty roots,
 * `#weights` and `#ranking`, for the aggregate view: weights row, stacked
 * contribution bars, split badges, animated re-rank.
 *
 * The script avoids `$` entirely so this stays a plain `const` raw string —
 * string concatenation throughout, no template literals.
 */
const val PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>alignment</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --blue: #2563eb; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 900px; margin: 1.5rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; margin-bottom: .8rem; }
  header { display: flex; gap: 1rem; align-items: center; flex-wrap: wrap; margin-bottom: .8rem; }
  header label { color: var(--dim); font-size: .85rem; }
  input, select, textarea { padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; font: inherit; }
  button { padding: .3rem .7rem; border: 1px solid var(--line); border-radius: 6px; background: #fff; cursor: pointer; font: inherit; }
  .tabs { display: flex; gap: .4rem; margin-bottom: .8rem; border-bottom: 1px solid var(--line); }
  .tabs button { border: none; border-radius: 0; padding: .5rem .9rem; background: none; font-size: .9rem; color: var(--dim); border-bottom: 2px solid transparent; margin-bottom: -1px; }
  .tabs button.active { color: var(--ink); border-bottom-color: var(--blue); font-weight: 600; }
  section[hidden] { display: none; }
  form.inline { display: flex; gap: .4rem; align-items: center; flex-wrap: wrap; margin-bottom: .8rem; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; margin-bottom: .8rem; }
  .card h3 { font-size: 1rem; margin: 0 0 .3rem; display: flex; justify-content: space-between; align-items: baseline; gap: .6rem; }
  .card h3 .count { font-size: .72rem; font-weight: normal; color: var(--dim); white-space: nowrap; }
  .toggle { border: none; background: none; color: var(--blue); cursor: pointer; padding: 0; font-size: .78rem; margin-bottom: .3rem; }
  .card pre { margin: 0 0 .5rem; padding: .5rem .7rem; background: #fafafa; border: 1px solid var(--line); border-radius: 6px;
              font-size: .78rem; line-height: 1.4; white-space: pre-wrap; word-break: break-word; }
  .row { display: grid; grid-template-columns: 9rem minmax(0,1fr) 2.2rem auto; gap: .6rem; align-items: center; padding: .25rem 0; font-size: .85rem; }
  .row label { color: var(--ink); }
  .row .v { text-align: right; font-variant-numeric: tabular-nums; color: var(--ink); }
  .row.unrated .v { color: var(--dim); }
  .row.unrated input[type=range] { opacity: .4; accent-color: var(--dim); }
  .row button { padding: .15rem .5rem; font-size: .75rem; }
  #board { color: var(--dim); font-size: .85rem; }
</style>
</head>
<body>
<h1>Alignment</h1>
<header>
  <label>as <input id="participant" maxlength="40"></label>
  <label>topic <select id="topicSel"></select></label>
</header>
<div class="tabs">
  <button type="button" id="tabRate">Rate</button>
  <button type="button" id="tabBoard">Board</button>
</div>
<section id="rate">
  <form class="inline" id="topicForm">
    new topic <input id="topicTitle" placeholder="title">
    <input id="topicDims" placeholder="dimensions, e.g. Impact:2, Effort">
    <button>create</button>
  </form>
  <form class="inline" id="ideaForm">
    new idea <input id="ideaTitle" placeholder="title">
    <input id="ideaDesc" placeholder="description (optional)">
    <button>add</button>
  </form>
  <div id="ideas"></div>
  <template id="sliderRow"><div class="row"><label></label><input type="range" min="1" max="9" step="1"><span class="v"></span><button type="button">clear</button></div></template>
</section>
<section id="board" hidden>
  <!-- Board (computenet-v48mn.2): weights row and animated ranking fill these roots. -->
  <div id="weights"></div>
  <div id="ranking"></div>
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

// ── tabs: #rate / #board, remembered in sessionStorage.tab ────────────────
const tabRate = document.getElementById('tabRate');
const tabBoard = document.getElementById('tabBoard');
const sectionRate = document.getElementById('rate');
const sectionBoard = document.getElementById('board');
function showTab(name) {
  sessionStorage.tab = name;
  sectionRate.hidden = name !== 'rate';
  sectionBoard.hidden = name !== 'board';
  tabRate.classList.toggle('active', name === 'rate');
  tabBoard.classList.toggle('active', name === 'board');
  if (name === 'rate') renderRate();
}
tabRate.onclick = () => showTab('rate');
tabBoard.onclick = () => showTab('board');

// ── topic selection, remembered in sessionStorage.topic ───────────────────
const sel = document.getElementById('topicSel');
sel.dataset.want = sessionStorage.topic || '';
const topic = () => sel.value;
sel.onchange = () => { sessionStorage.topic = sel.value; renderRate(); };

document.getElementById('topicForm').onsubmit = e => {
  e.preventDefault();
  const dims = parseDims(topicDims.value);
  if (dims === null) { alert('each dimension weight must be a positive number'); return; }
  send('POST', '/topics', { creator: me(), title: topicTitle.value, dimensions: dims })
    .then(j => {
      topicTitle.value = ''; topicDims.value = ''; sel.dataset.want = j.id;
      // the topic's frame may already have arrived: select it now if it is known, else on the next frame
      renderTopics(); renderRate();
    });
};
document.getElementById('ideaForm').onsubmit = e => {
  e.preventDefault();
  if (!topic()) return;
  send('POST', '/topics/' + topic() + '/ideas', { participant: me(), title: ideaTitle.value, description: ideaDesc.value })
    .then(() => { ideaTitle.value = ''; ideaDesc.value = ''; renderRate(); });
};

/** "Impact:2, Effort" -> [{name:"Impact",weight:2},{name:"Effort"}]; null on a bad weight. */
function parseDims(text) {
  const parts = text.split(',').map(s => s.trim()).filter(s => s);
  const dims = [];
  for (const part of parts) {
    const colon = part.indexOf(':');
    if (colon === -1) { dims.push({ name: part }); continue; }
    const name = part.slice(0, colon).trim();
    const raw = part.slice(colon + 1).trim();
    const weight = Number(raw);
    if (!name || !raw || !isFinite(weight) || weight <= 0) return null;
    dims.push({ name: name, weight: weight });
  }
  return dims;
}

function renderTopics() {
  const want = sel.dataset.want || sessionStorage.topic || sel.value;
  sel.innerHTML = '';
  for (const t of state.topics) {
    const o = document.createElement('option');
    o.value = t.id; o.textContent = t.title;
    sel.appendChild(o);
  }
  if (state.topics.some(t => t.id === want)) { sel.value = want; delete sel.dataset.want; }
  if (sel.value) sessionStorage.topic = sel.value;
}

// the rating panel reads only state.topics (dimension names, creator) and
// the bias-safe /me view: your own ratings, nothing aggregate, ever
let rateSeq = 0;
let expandedDesc = new Set();
function renderRate() {
  const box = document.getElementById('ideas');
  if (sectionRate.hidden) return;
  if (!topic() || !me()) { box.innerHTML = ''; return; }
  if (document.activeElement && document.activeElement.type === 'range') return; // don't yank a slider mid-drag
  const seq = ++rateSeq;
  fetch('/topics/' + topic() + '/me?participant=' + encodeURIComponent(me())).then(r => r.json()).then(view => {
    if (seq !== rateSeq) return;
    const t = state.topics.find(x => x.id === topic());
    const dims = t ? t.dimensions : [];
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
        const apply = val => {
          if (val === null) {
            row.className = 'row unrated';
            input.value = '5';
            shown.textContent = '—';
            clearBtn.disabled = true;
          } else {
            row.className = 'row';
            input.value = String(val);
            shown.textContent = String(val);
            clearBtn.disabled = false;
          }
        };
        apply(v);
        input.oninput = () => { row.className = 'row'; shown.textContent = input.value; clearBtn.disabled = false; };
        input.onchange = () => send('POST', '/topics/' + topic() + '/rate',
          { participant: me(), idea: idea.id, dim: dim.id, value: Number(input.value) }).then(() => { input.blur(); renderRate(); });
        clearBtn.onclick = () => send('POST', '/topics/' + topic() + '/rate',
          { participant: me(), idea: idea.id, dim: dim.id, value: null }).then(() => renderRate());
        card.appendChild(row);
      }
      box.appendChild(card);
    }
  });
}

// SSE frames arrive in bursts (several hub broadcasts milliseconds apart);
// coalesce on a 60 ms tick as in backlog-triage so a burst re-renders once.
// The Board task (computenet-v48mn.2) adds its own render call inside this
// same tick.
let renderPending = false;
function scheduleRender() {
  if (renderPending) return;
  renderPending = true;
  setTimeout(() => { renderPending = false; renderTopics(); renderRate(); }, 60);
}
new EventSource('/events').onmessage = e => {
  state = JSON.parse(e.data);
  scheduleRender();
};

showTab(sessionStorage.tab === 'board' ? 'board' : 'rate');
</script>
</body>
</html>
"""
