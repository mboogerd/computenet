# `bd` traps

Behaviours of `bd` that produce a WRONG ANSWER rather than an error. Every
one is measured, and every one has silently changed a decision at least once.
SKILL.md and the other references cite this file as "`bd` traps".

- `bd ready` hides `in_progress`/`blocked`/`deferred`; `bd list` hides
  *closed* unless `--all`. Every check below uses the one it means.
- `--parent` scope differs by subcommand — but **not the way this file used to
  say**. `bd list --parent` is one level deep, and so is
  `bd ready --parent`: it reaches **direct children only**, not descendants.
  Measured 2026-08-17 on live data — `bd ready --parent=computenet-dqy.37`
  finds `computenet-dqy.37.2`, while `bd ready --parent=computenet-dqy` does
  **not**, though `epic-of.sh` resolves that item to `computenet-dqy`. An epic
  with ready work two levels down therefore reports EMPTY, which is how a live
  epic gets deferred and hidden on both machines (computenet-28vn). Use
  `.claude/skills/work/scripts/ready-in-epic.sh <epic>` for any epic-scoped ready question.
  `bd blocked --parent`'s depth is **unverified** — treat it the same way.
  And `bd blocked` lists only items blocked by an open dependency edge — a
  hand-set `--status=blocked` (an ask-human park) is invisible to it.
- `bd show <id> --json` returns a **list** — unwrap `.[0]` or every field
  reads `null`. It never includes comment bodies, only `comment_count`.
- **There is no `parent_id` field at all** — the parent lives under
  **`.parent`**, and `jq` answers a *missing* key with `null` exactly as it
  answers an empty one. So the natural `.[0].parent_id` reads every bead as
  orphaned, correctly-parented ones included (computenet-uixt; measured
  2026-08-17 on `computenet-uixt`: `has("parent_id")` → `false`, `.parent` →
  `"computenet-wpvy"`). That is the general trap, not a one-off: a
  misremembered field name is indistinguishable from an unset one, so before
  believing any `null` from `bd --json`, check `has("<field>")`. Read
  **`.parent`**, or `bd dep list <id>` (parentage is the `via parent-child`
  row — the other rows are `blocks` edges, and an unparented bead still
  prints those). And `.parent` is absent-not-null when genuinely unset
  (computenet-wpvy.32), so `.claude/skills/work/scripts/epic-of.sh` remains the way to resolve an
  *effective* epic rather than one hop.
- Epic- and feature-sized output overflows the inline tool-result limit
  (`bd show` on one epic: ~83KB; `bd ready --type=epic --json`: ~43KB) and
  gets truncated or persisted. Redirect any `--json` call that *can* be big
  to `"$SCRATCH/<name>.json"` and read the file, same as comments below
  (computenet-csm).
- `bd` prints warnings on stdout **before** the JSON, so `jq` and
  `json.loads` fail on the raw stream; slice from the first line starting
  `[` or `{` (`sed -n '/^[[{]/,$p'`) before parsing. **Every documented
  snippet in this skill carries that slice** — it is not decoration, and
  removing it to shorten a line reintroduces the bug (computenet-efhi).
  Setting `beads.role` silences the *role* warning, and this clone has it
  (`beads.role=maintainer` in `.git/config`) — but that is **per-clone local
  git config, not tracked and not synced**, so the other machine still emits
  the warning until somebody sets it there too, and so does any fresh clone.
  It is also one warning of several: the id-collision backstop and Dolt's own
  notices print the same way. The slice stays regardless; never drop it
  because "the warning is fixed here".

  **An empty `jq` result must never be read as an empty query result.** That
  is the whole harm: the pipe fails, `jq` prints nothing, exit status is the
  last stage's, and "no rows" is indistinguishable from "the parse died". If a
  query you expect to return something returns nothing, re-run it without the
  `jq` and look at the raw stream before believing it.
- Comments are read one way only: `bd comments <id> --json >
  "$SCRATCH/c-<id>.json"`, then read the file. Inline reads truncate on
  long-lived beads (~34KB observed) and present as *fewer comments than
  exist* — the "has a human answered this?" misread that wrongly deferred an
  epic.
- `bd create` takes the title **positionally** or via `--title` (`-t` is
  `--type`); `bd comment` takes the body positionally or via `--file` (not
  `--body-file`); clearing a metadata key is `--unset-metadata <key>`
  (`--set-metadata key=` merges, it does not clear).
- **`bd create` has no `--set-metadata`** — that flag exists only on `bd
  update`. On create the spelling is `--metadata '<json object>'`, so
  `model` and `files` go in as
  `--metadata '{"model":"sonnet","files":"kernel/src/..."}'`. Getting this
  wrong is silent in the way that matters: the bead is created, the routing
  fields are not, and `next-batch.py` dispatches it with no model and no file
  claim (computenet-kd9s, computenet-w8jt). Don't reach for a second `bd
  update` instead — it is a second write that can fail on its own and leave
  the bead half-configured; one `--metadata` on the create is atomic.
  `.claude/skills/work/scripts/create-ticket.sh` takes `--metadata` and passes it through.
- **`bd list --json` changes shape under `--skip-labels`**: a bare array by
  default, `{"issues":[...]}` with the flag. A `jq '.[]'` written against one
  yields nothing against the other and exits 0, so the caller reads an empty
  result as "no rows" (computenet-kr18). Use the shape-agnostic row selector
  everywhere, and never `|| echo '[]'` a `jq` failure into a clean answer:

  ```bash
  ROWS='(if type=="array" then . else (.issues // []) end)[]'
  bd list … --json | sed -n '/^[[{]/,$p' | jq -r "$ROWS | .id"
  ```

- **Re-parenting a reviewer-filed residual takes two commands.** A
  `discovered-from` edge occupies the same slot as parent-child, so
  `bd update <child> --parent=<parent>` errors when review-feature.md §7's
  prescribed edge already exists (computenet-ofzz). Remove it first:

  ```bash
  bd dep remove <child> <parent>
  bd update <child> --parent=<parent>
  ```
- **`bd create --parent=<shared epic>` is banned.** It allocates the child id
  from `child_counters`, a per-database table reconciled only at sync, so two
  machines filing between syncs mint the SAME id for different beads — a
  primary-key collision whose resolution destroys one of them (computenet-azt,
  computenet-wpvy.45). Use `.claude/skills/work/scripts/create-ticket.sh`, which creates
  unparented (hash id, counter untouched) and then re-parents. Breakdown
  children under an epic or feature YOU claimed are exclusive by that claim
  and keep their dotted ids — `--parent` is correct there.
- `bd` calls are slow and `bd dolt pull`/`push` can run past 120s — give
  sync commands a ≥300s timeout, and never chain `bd` *writes* in one Bash
  block: one write per call, each with the long timeout, or the chain dies
  mid-sequence and leaves half-recorded state (computenet-9oq,
  computenet-9r8).
