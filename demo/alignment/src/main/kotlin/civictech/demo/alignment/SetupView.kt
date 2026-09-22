package civictech.demo.alignment

/**
 * The facilitator's Setup view (computenet-0dvra-D5/D11/D14/D15, task computenet-0dvra.2), one
 * slice of the served [PAGE]: its CSS, the `<section id="setup">` root and the `<script>`
 * defining `renderSetup()`.
 *
 * Shown only for the topic's creator (the shell's tab logic gates the tab itself), but the shell
 * calls `renderSetup()` on every coalesced frame regardless of who is viewing (AMENDS on
 * computenet-0dvra.2, from the 0dvra.1 reviewer verdict) — so this function is defense in depth:
 * for a non-creator, or before a topic is known, it tears its own content down rather than
 * relying solely on the shell hiding the section. All facilitator-only markup (dimension rows,
 * policy/visibility controls, idea rows, the progress list, copy-link) is built by this script
 * into an otherwise-empty `#setup` root, so "no Setup control in the DOM" for a non-creator is
 * literal, not just `hidden`.
 *
 * API driven here (k1d4g-D6 + k1d4g.2 AMENDS, verified against `AlignmentApp.handleTopics` at
 * this task's base): `POST /topics/{t}/dimensions {creator, name, weight?, direction?, lowLabel?,
 * highLabel?}`; `PUT /topics/{t}/dimensions/{d} {creator, weight?, direction?, lowLabel?,
 * highLabel?}`; `DELETE /topics/{t}/dimensions/{d}?creator=`; `PUT /topics/{t}/policy {creator,
 * ideas?, boardVisibility?, gutCheck?, dotBudget?}` (the last two are the experimental Gut check
 * round's settings, teu97-D2/D10); `POST /topics/{t}/reveal {creator}`; `POST /topics/{t}/ideas
 * {participant, title, description?}`; `PUT /topics/{t}/ideas/{i} {creator, title?,
 * description?}`; `DELETE /topics/{t}/ideas/{i}?creator=`.
 *
 * The progress block (D15) reads `state.ratings` (amends 0dvra-D8): rows are `{topic, idea, dim,
 * participant, value}` and only participant names and counts are used, never a value. It is one
 * of several readers on the page; see the "Data access" entry in the shell contract at the top of
 * [AlignmentPage.kt]'s script for the full list. No `$` anywhere (a plain raw string, no template
 * literals); no literal colour — only `:root` tokens and `dimColour(t, d)`.
 */
internal const val SETUP_VIEW = """
<style>
  #setup .card > h3 { margin: 0 0 .6rem; font-size: var(--fs-2); text-transform: uppercase; letter-spacing: .04em; color: var(--muted); }
  #setup .setup-dim-row, #setup .setup-idea-row, #setup .setup-progress-row { padding: .4rem 0; border-bottom: 1px solid var(--line); }
  #setup .setup-dim-row:last-child, #setup .setup-idea-row:last-child, #setup .setup-progress-row:last-child { border-bottom: none; }
  #setup .setup-dim-row { display: grid; align-items: center; gap: .5rem;
    grid-template-columns: .8rem minmax(5rem,10rem) auto 8rem 2.2rem minmax(0,1fr) minmax(0,1fr) auto; }
  #setup .setup-dim-row .sw { width: .8rem; height: .8rem; border-radius: 4px; flex: none; }
  #setup .setup-dim-row .dim-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  #setup .setup-dim-row .dim-weight { text-align: right; }
  #setup .seg { display: inline-flex; border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
  #setup .seg button { border: none; border-radius: 0; padding: .25rem .55rem; font-size: var(--fs-1);
    background: var(--surface); color: var(--muted); }
  #setup .seg button.active { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
  #setup .setup-idea-row { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1fr) auto; gap: .5rem; align-items: center; }
  #setup .setup-progress-row { display: flex; justify-content: space-between; gap: .6rem; font-size: var(--fs-2); }
  #setup [role=radiogroup] { display: flex; flex-direction: column; gap: .3rem; font-size: var(--fs-2); margin-bottom: .7rem; }
  #setup [role=radiogroup] label { display: flex; align-items: center; gap: .4rem; }
  #setupGutCheckRoot { display: flex; flex-direction: column; gap: .4rem; font-size: var(--fs-2); }
  #setupGutCheckRoot label { display: flex; align-items: center; gap: .4rem; }
  #setupDotBudget { width: 4.5rem; }
  #setupLinkFallback { width: 100%; margin-top: .5rem; }
  #setupCopied { margin-left: .5rem; }
</style>
<section id="setup" class="pane view" hidden>
</section>
<script>
// Setup view (computenet-0dvra.2): all facilitator controls are built here into the otherwise-
// empty #setup root, so a non-creator frame (or a not-yet-known topic) leaves it with nothing in
// it, not merely hidden. Called by the shell on every coalesced frame while a known topic route
// is showing, whether or not the Setup tab is active.
let setupBuilt = false;
let setupTopicId = null;
const setupDimRows = new Map();   // dim id -> row node
const setupIdeaRows = new Map();  // idea id -> row node
let setupCopiedTimer = null;

function renderSetup() {
  const t = currentTopic();
  if (!t || !isCreator(t)) { teardownSetup(); return; }
  if (t.id !== setupTopicId) { setupDimRows.forEach(r => r.remove()); setupDimRows.clear();
    setupIdeaRows.forEach(r => r.remove()); setupIdeaRows.clear(); setupTopicId = t.id; }
  ensureSetupSkeleton();
  paintDimensions(t);
  paintPolicyAndVisibility(t);
  paintIdeas(t);
  setupPaintProgress(t);
}

function teardownSetup() {
  if (!setupBuilt) return;
  el('setup').innerHTML = '';
  setupBuilt = false;
  setupTopicId = null;
  setupDimRows.clear();
  setupIdeaRows.clear();
  if (setupCopiedTimer) { clearTimeout(setupCopiedTimer); setupCopiedTimer = null; }
}

function ensureSetupSkeleton() {
  if (setupBuilt) return;
  const root = el('setup');
  root.innerHTML =
    '<h2>Setup</h2>' +
    '<div class="card"><h3>Dimensions</h3><div id="setupDims"></div>' +
      '<div class="ntrow" id="setupAddDim">' +
        '<span class="sw" aria-hidden="true"></span>' +
        '<input id="setupDimName" maxlength="80" placeholder="dimension name" aria-label="new dimension name">' +
        '<select id="setupDimDir" aria-label="new dimension direction"><option value="value">value</option><option value="cost">cost</option></select>' +
        '<button type="button" id="setupDimAdd">add</button>' +
      '</div>' +
    '</div>' +
    '<div class="card"><h3>Idea policy</h3><div id="setupPolicy" role="radiogroup" aria-label="who may add ideas">' +
      '<label><input type="radio" name="setupPolicy" value="everyone">everyone may add ideas</label>' +
      '<label><input type="radio" name="setupPolicy" value="facilitator">only the facilitator</label>' +
    '</div></div>' +
    '<div class="card"><h3>Board visibility</h3><div id="setupVisibility" role="radiogroup" aria-label="when the board appears">' +
      '<label><input type="radio" name="setupVisibility" value="after-rating">after rating</label>' +
      '<label><input type="radio" name="setupVisibility" value="after-reveal">after facilitator reveal</label>' +
    '</div><button type="button" id="setupReveal">Reveal</button></div>' +
    '<div class="card"><h3>Gut check</h3><div id="setupGutCheckRoot">' +
      '<label><input type="checkbox" id="setupGutCheck"> run a dot-voting gut check before rating</label>' +
      '<label>dots per participant <input type="number" id="setupDotBudget" min="1" max="20" step="1"></label>' +
    '</div></div>' +
    '<div class="card"><h3>Ideas</h3><div id="setupIdeas"></div>' +
      '<form class="inline" id="setupIdeaAdd">' +
        '<input id="setupIdeaTitle" maxlength="200" placeholder="title" aria-label="new idea title">' +
        '<input id="setupIdeaDesc" placeholder="description (optional)" aria-label="new idea description">' +
        '<button>add</button>' +
      '</form>' +
    '</div>' +
    '<div class="card"><h3>Who has rated</h3><div id="setupProgress"></div></div>' +
    '<div class="card">' +
      '<button type="button" id="setupCopyLink">copy link</button><span id="setupCopied" class="muted" hidden>copied</span>' +
      '<input id="setupLinkFallback" class="muted" readonly hidden aria-label="topic link">' +
    '</div>';

  el('setupDimAdd').onclick = () => {
    const t = currentTopic();
    if (!t) return;
    const name = el('setupDimName').value.trim();
    if (!name) { alert('give the dimension a name'); return; }
    send('POST', '/topics/' + t.id + '/dimensions', { creator: me(), name: name, direction: el('setupDimDir').value })
      .then(() => { el('setupDimName').value = ''; el('setupDimDir').value = 'value'; renderSetup(); }, () => {});
  };

  el('setupPolicy').querySelectorAll('input').forEach(r => r.onchange = () => {
    const t = currentTopic();
    if (!t) return;
    send('PUT', '/topics/' + t.id + '/policy', { creator: me(), ideas: r.value }).then(renderSetup, () => {});
  });
  el('setupVisibility').querySelectorAll('input').forEach(r => r.onchange = () => {
    const t = currentTopic();
    if (!t) return;
    send('PUT', '/topics/' + t.id + '/policy', { creator: me(), boardVisibility: r.value }).then(renderSetup, () => {});
  });
  el('setupReveal').onclick = () => {
    const t = currentTopic();
    if (!t) return;
    send('POST', '/topics/' + t.id + '/reveal', { creator: me() }).then(renderSetup, () => {});
  };

  el('setupGutCheck').onchange = () => {
    const t = currentTopic();
    if (!t) return;
    send('PUT', '/topics/' + t.id + '/policy', { creator: me(), gutCheck: el('setupGutCheck').checked }).then(renderSetup, () => {});
  };
  el('setupDotBudget').onchange = () => {
    const t = currentTopic();
    if (!t) return;
    send('PUT', '/topics/' + t.id + '/policy', { creator: me(), dotBudget: Number(el('setupDotBudget').value) }).then(renderSetup, () => {});
  };

  el('setupIdeaAdd').onsubmit = e => {
    e.preventDefault();
    const t = currentTopic();
    if (!t) return;
    const title = el('setupIdeaTitle').value.trim();
    if (!title) { alert('give the idea a title'); return; }
    send('POST', '/topics/' + t.id + '/ideas', { participant: me(), title: title, description: el('setupIdeaDesc').value })
      .then(() => { el('setupIdeaTitle').value = ''; el('setupIdeaDesc').value = ''; renderSetup(); }, () => {});
  };

  el('setupCopyLink').onclick = () => {
    const t = currentTopic();
    if (!t) return;
    const url = location.origin + '/t/' + t.id;
    const copied = () => {
      el('setupLinkFallback').hidden = true;
      el('setupCopied').hidden = false;
      if (setupCopiedTimer) clearTimeout(setupCopiedTimer);
      setupCopiedTimer = setTimeout(() => { el('setupCopied').hidden = true; }, 1500);
    };
    const fallback = () => {
      const input = el('setupLinkFallback');
      input.value = url;
      input.hidden = false;
      input.focus();
      input.select();
    };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(url).then(copied, fallback);
    else fallback();
  };

  setupBuilt = true;
}

function paintDimensions(t) {
  const box = el('setupDims');
  const seen = new Set();
  for (const d of t.dimensions) {
    seen.add(d.id);
    let row = setupDimRows.get(d.id);
    if (!row) {
      row = document.createElement('div');
      row.className = 'setup-dim-row';
      row.innerHTML =
        '<span class="sw" aria-hidden="true"></span>' +
        '<span class="dim-name"></span>' +
        '<div class="seg" role="group" aria-label="value or cost"><button type="button" data-dir="value">value</button><button type="button" data-dir="cost">cost</button></div>' +
        '<input type="range" min="0.5" max="5" step="0.5" aria-label="weight">' +
        '<span class="dim-weight num"></span>' +
        '<input class="dim-low" maxlength="80" placeholder="1 means…" aria-label="low anchor label">' +
        '<input class="dim-high" maxlength="80" placeholder="9 means…" aria-label="high anchor label">' +
        '<button type="button" class="link" aria-label="remove dimension">remove</button>';
      box.appendChild(row);
      setupDimRows.set(d.id, row);
      const dimId = d.id;
      row.querySelectorAll('.seg button').forEach(b => b.onclick = () => {
        send('PUT', '/topics/' + t.id + '/dimensions/' + dimId, { creator: me(), direction: b.dataset.dir }).then(renderSetup, () => {});
      });
      const slider = row.querySelector('input[type=range]');
      slider.oninput = () => { row.querySelector('.dim-weight').textContent = slider.value; };
      slider.onchange = () => {
        send('PUT', '/topics/' + t.id + '/dimensions/' + dimId, { creator: me(), weight: Number(slider.value) }).then(renderSetup, () => {});
      };
      const low = row.querySelector('.dim-low');
      low.onchange = () => send('PUT', '/topics/' + t.id + '/dimensions/' + dimId, { creator: me(), lowLabel: low.value }).then(renderSetup, () => {});
      const high = row.querySelector('.dim-high');
      high.onchange = () => send('PUT', '/topics/' + t.id + '/dimensions/' + dimId, { creator: me(), highLabel: high.value }).then(renderSetup, () => {});
      row.querySelector('button.link').onclick = () => {
        if (!confirm('removes every rating on this dimension')) return;
        send('DELETE', '/topics/' + t.id + '/dimensions/' + dimId + '?creator=' + encodeURIComponent(me())).then(renderSetup, () => {});
      };
    }
    if (editing(row)) continue; // never yank an in-progress edit
    row.querySelector('.sw').style.background = dimColour(t, d);
    row.querySelector('.dim-name').textContent = d.name;
    row.querySelectorAll('.seg button').forEach(b => b.classList.toggle('active', b.dataset.dir === (d.direction || 'value')));
    row.querySelector('input[type=range]').value = String(d.weight || 1);
    row.querySelector('.dim-weight').textContent = String(d.weight || 1);
    row.querySelector('.dim-low').value = d.lowLabel || '';
    row.querySelector('.dim-high').value = d.highLabel || '';
  }
  for (const [id, row] of setupDimRows) if (!seen.has(id)) { setupDimRows.delete(id); row.remove(); }
}

function paintPolicyAndVisibility(t) {
  const polRoot = el('setupPolicy');
  if (!editing(polRoot)) polRoot.querySelectorAll('input').forEach(r => { r.checked = r.value === (t.ideas || 'everyone'); });
  const visRoot = el('setupVisibility');
  if (!editing(visRoot)) visRoot.querySelectorAll('input').forEach(r => { r.checked = r.value === (t.boardVisibility || 'after-rating'); });
  const revealBtn = el('setupReveal');
  revealBtn.disabled = !(t.boardVisibility === 'after-reveal' && t.revealed !== true);
  revealBtn.textContent = t.revealed === true ? 'revealed' : 'Reveal';
  // teu97-D10 repair: guard each Gut check control by its OWN focus, not by a shared root.
  // A single shared-root guard left #setupDotBudget stuck disabled after checking
  // #setupGutCheck: the checkbox keeps DOM focus through and past its own PUT's resolution,
  // so editing(sharedRoot) stayed true and no repaint ever cleared the stale `disabled`
  // attribute — reproduced headless (Playwright): check the box, then no further click or
  // wait ever re-enables the budget input without an unrelated SSE frame arriving first.
  const gcCheckbox = el('setupGutCheck');
  if (!editing(gcCheckbox)) gcCheckbox.checked = t.gutCheck === true;
  const gcBudget = el('setupDotBudget');
  if (!editing(gcBudget)) {
    gcBudget.value = String(t.dotBudget);
    gcBudget.disabled = t.gutCheck !== true;
  }
}

function paintIdeas(t) {
  const box = el('setupIdeas');
  const seen = new Set();
  const ideas = state.ideas.filter(i => i.topic === t.id);
  for (const idea of ideas) {
    seen.add(idea.id);
    let row = setupIdeaRows.get(idea.id);
    if (!row) {
      row = document.createElement('div');
      row.className = 'setup-idea-row';
      row.innerHTML =
        '<input class="idea-title" maxlength="200" aria-label="idea title">' +
        '<input class="idea-desc" placeholder="description (optional)" aria-label="idea description">' +
        '<button type="button" class="link" aria-label="remove idea">remove</button>';
      box.appendChild(row);
      setupIdeaRows.set(idea.id, row);
      const ideaId = idea.id;
      const title = row.querySelector('.idea-title');
      title.onchange = () => send('PUT', '/topics/' + t.id + '/ideas/' + ideaId, { creator: me(), title: title.value }).then(renderSetup, () => {});
      const desc = row.querySelector('.idea-desc');
      desc.onchange = () => send('PUT', '/topics/' + t.id + '/ideas/' + ideaId, { creator: me(), description: desc.value }).then(renderSetup, () => {});
      row.querySelector('button.link').onclick = () => {
        if (!confirm('removes every rating on this idea')) return;
        send('DELETE', '/topics/' + t.id + '/ideas/' + ideaId + '?creator=' + encodeURIComponent(me())).then(renderSetup, () => {});
      };
    }
    if (editing(row)) continue;
    row.querySelector('.idea-title').value = idea.title;
    row.querySelector('.idea-desc').value = idea.description || '';
  }
  for (const [id, row] of setupIdeaRows) if (!seen.has(id)) { setupIdeaRows.delete(id); row.remove(); }
}

// R4 (0dvra-D15): reads state.ratings for participant names and counts only, never a value.
// See the "Data access" entry in AlignmentPage.kt's shell contract for the page's full list
// of readers.
function setupPaintProgress(t) {
  const box = el('setupProgress');
  const total = state.ideas.filter(i => i.topic === t.id).length * t.dimensions.length;
  const counts = new Map();
  for (const r of state.ratings) if (r.topic === t.id) counts.set(r.participant, (counts.get(r.participant) || 0) + 1);
  box.innerHTML = '';
  const names = [...counts.keys()].sort();
  if (!names.length) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = 'nobody has rated yet';
    box.appendChild(p);
    return;
  }
  for (const name of names) {
    const n = counts.get(name);
    const line = document.createElement('div');
    line.className = 'setup-progress-row';
    line.innerHTML = '<span class="p-name"></span><span class="p-status muted num"></span>';
    line.querySelector('.p-name').textContent = name;
    line.querySelector('.p-status').textContent = n === total ? 'done' : 'not yet (' + n + ' of ' + total + ')';
    box.appendChild(line);
  }
}
</script>
"""
