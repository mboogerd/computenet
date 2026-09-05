# `bd` traps

Behaviours of `bd` that produce a WRONG ANSWER rather than an error. Every
one is measured, and every one has silently changed a decision at least once.
SKILL.md and the other references cite this file as "`bd` traps".

- `bd ready` hides `in_progress`/`blocked`/`deferred`; `bd list` hides
  *closed* unless `--all`. Every check below uses the one it means.
- `--parent` scope differs by subcommand, and the two differ from EACH OTHER.
  **`bd list --parent` DOES return descendants** — measured 2026-08-29 on bd
  1.1.2: `bd list --parent=computenet-051 --all` returns the grandchild
  `computenet-051.6.4` alongside the six features (computenet-yb4s; a
  breakdown agent and the orchestrator saw it independently on 08-28).
  `bd ready --parent` reaches **direct children only**, not descendants —
  measured 2026-08-17 and NOT re-measured at 1.1.2, so treat it as the
  cautious assumption rather than a current reading. Record bd's version with
  any new measurement; this is exactly the behaviour that moves under it.
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
- **Project the fields you need; never dump a whole bead.** Descriptions
  here run 1500+ words, so `bd show a && bd show b` returned 112KB and a
  three-bead status loop 177KB, each costing round-trips through the
  persisted-output file (computenet-9r2z). `jq -r '.[0] | "\(.status)
  \(.assignee)"'` is the same call and 40 bytes; read a description with
  `jq -r '.[0].description'` into a file and Read it.
- `bd` prints warnings on stdout **before** the JSON, so `jq` and
  `json.loads` fail on the raw stream; slice from the first line starting
  `[` or `{` (`sed -n '/^[[{]/,/^[]}]/p'`) before parsing. **Every documented
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
- **The acceptance field is spelled differently for writing and for
  reading.** The write flag is `--acceptance`; the JSON read key is
  **`acceptance_criteria`**. They are not the same string, and `jq
  '.[0].acceptance'` answers `null` on a bead that has criteria rather than
  erroring — the `has("<field>")` trap above, on the one field the review
  references treat as the standard to judge against. Measured on
  `computenet-4ru.5.3`, which carries five criteria: `.acceptance` → `null`,
  `.acceptance_criteria` → the text. This has already cost a real review: a
  task reviewer opened its report with "the bead carried NO acceptance
  criteria (`acceptance: null`)", took review-task.md's
  empty-criteria fallback ladder, wrote nine criteria of its own and
  **overwrote the field** with them — about 15 minutes before review could
  begin, and the original text is unrecoverable (computenet-2rix). A
  reviewer that reconstructs criteria writes them to a **comment**, never
  over the field.
- **`bd show <child> --json` inlines the parent epic's ENTIRE description**
  (and each dependency's), so a child of a large epic is *bigger than the
  epic*. Measured 2026-08-19: `bd show computenet-x9e.3 --json` = 57KB while
  `bd show computenet-x9e --json` = 43KB. The agent is not reading a big
  issue; it is reading a small one that silently carries a big one inside it,
  and the failure looks like a truncated read whose natural recovery — re-run
  the command — fails identically (computenet-rram). The payload is one copy
  of the parent body **per dependency entry**, not one per bead, so several
  dependencies multiply it: 35KB overrun on a task, ~149KB from one call on a
  feature (computenet-zwju). **A truncated read of this does not look
  truncated** — its tail is the PARENT's acceptance and metadata, well-formed,
  so it reads as a complete read of the wrong bead (computenet-o5oz, sighting
  three of rram -> zwju -> o5oz; a fourth belongs on that chain, not a fresh
  file). Read the bead's own fields through the
  projection, which drops `dependencies` before the output can reach a tool
  result (57KB -> 7KB on computenet-x9e.3):

  ```bash
  .claude/skills/work/scripts/bead.sh <id>                  # the whole bead, projected
  .claude/skills/work/scripts/bead.sh <id> -r '.status'     # one field; no .[0] unwrap
  ```

  Redirect-and-slice still works and is right when you need a field the
  projection drops:

  ```bash
  bd show <id> --json > "$SCRATCH/<id>.json"
  jq -r '.[0] | "\(.description)\n---\n\(.acceptance_criteria)"' "$SCRATCH/<id>.json"
  ```

  rram was closed on the redirect rule alone, carried by hand in each dispatch
  prompt — it held for every agent that was warned and failed for the two
  whose prompt did not carry it for the call they made. That is why the
  projection is a script.

  **A truncated bead read is a truncated ACCEPTANCE LIST**, and nothing in
  the output says it was truncated, so the review proceeds against criteria
  it never saw (computenet-h0dj). The general rule, of which `bd comments`
  and `bd ready --type=epic --json` are the already-known cases: **any `bd`
  read whose size is not bounded by construction goes to a file first.**

  **The PLAIN (non-`--json`) `bd show` view is not the safe alternative** —
  it is the same trap wearing the smaller number. `bd show computenet-9sm`
  is 114KB against the epic's own 36KB description, and the harness elides
  the MIDDLE of a tool result that big with a `... [N characters truncated]
  ...` marker sitting inside prose: well-formed text before it, well-formed
  text after it, and a spec read in halves. Measured 2026-09-05: one
  breakdown agent hit it, and the workaround then had to be hand-carried
  into **nine consecutive dispatch prompts** because it lived only in an
  orchestrator's head (computenet-cjfd). `bead.sh` is the standing read for
  every bead, large or small.

  **Above ~25KB even the projection does not fit**, and `bead.sh` handles
  that itself: it writes the projected bead to `$SCRATCH/bead-<id>.json` and
  prints that path instead of the body, so a `Read` call pages it and
  nothing is silently missing. Paging it back through Bash does not work —
  the harness re-persists a large Bash output too. A caller that PIPES
  `bead.sh` into another command raises `BEAD_SPILL_BYTES`: a scalar filter
  (`-r '.status'`) never spills, but `-r '.description'` is
  description-sized and does.
- **`bd comment` executes backticks in its free text and reports success.**
  Backticks inside a double-quoted shell argument are command substitution,
  so the word vanishes from the stored comment while `bd` prints "Comment
  added" and exits 0. Measured 2026-08-18: a comment reading ``dispatch is a
  `when` EXPRESSION over it`` stored as "dispatch is a  EXPRESSION over it",
  with `(eval):1: command not found: when` the only sign. The comment is 95%
  intact, which is what makes it dangerous — it reads as garbled rather than
  corrupted, to a future agent who cannot know a word is missing. **Any bd
  free text that quotes code goes through a quoted heredoc or `bd comment
  <id> --file <path>`** — this applies to every bd subcommand taking free
  text, not only to filing friction (computenet-9w9, computenet-s62u).
- **`bd search` matches a case-insensitive literal substring of the TITLE
  and id only.** Descriptions are invisible, and a multi-word query hits only
  when those words appear verbatim *and adjacent* in a title. **An empty
  result is no evidence at all**, never evidence of absence. This produced a
  false accusation: a reviewer searched for a follow-up bead using the
  residual's *subject* wording, found nothing, and reported that an
  implementer "claimed to file a bead and did not" — while
  `bd show computenet-yhbd` returns it, open and correctly parented
  (computenet-tay3). It bites reviewers specifically because the reviewer
  searches from the residual's subject while the bead was titled by its
  author, so the two rarely share an adjacent word sequence. To check
  whether a bead exists: `bd show <id>` when an id is named, otherwise
  `bd list --parent=<epic> --all --json` or a grep of `.beads/issues.jsonl`
  — never a phrase search alone.
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
  bd list … --json | sed -n '/^[[{]/,/^[]}]/p' | jq -r "$ROWS | .id"
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
- **The `--json` slice must be TERMINATED, not open-ended.** Every idiom in
  this skill now reads `sed -n '/^[[{]/,/^[]}]/p'` — first line opening the
  document through the first line closing it at column 0. The old form
  `,$p` ran to end of file, and bd writes a **pagination trailer** when a
  listing is capped: `Showing 100 of 144 ready issues. Use --limit 0 …`. At
  bd 1.1.2 that trailer goes to **stderr**, so it reaches the slice only under
  `2>&1` — which every reported instance used, and which most idioms here use
  to keep bd's leading warnings visible. jq then reports `parse error: Invalid
  numeric literal at line 3258, column 8` — column 8 is where `100` begins in
  the trailer, so it reads as mid-document corruption and is not: the array
  above it is complete and valid (characterised in computenet-dowo, reproduced
  2026-08-29 on `bd ready --json`). The slice was designed for a PREFIX
  problem; this is a SUFFIX problem. It appears and disappears with the size
  of the result set, which is why it looked intermittent.

  **An empty single-field jq result is never evidence the field is unset.**
  When the document does fail to parse, a whole-document query fails loudly
  while `jq -r '.[0].acceptance_criteria'` may still print — jq emits fields
  it reached before the bad line — and a query for a field AFTER it returns
  nothing, which reads exactly like an empty field. Where emptiness routes
  anything (an acceptance list, `metadata.files`, a `cross_bead`
  authorization), make the read fail loudly first: `jq -e .` the sliced
  document, or read the whole object and index into it.
- **`bd update` aborts the WHOLE call on one unknown flag**, discarding the
  writes a valid flag in the same call would have made. Measured 2026-08-29:
  `bd update <id> --nosuchflag --set-metadata probe2=x` → `Error: unknown
  flag`, and `probe2` was never set. The shape that produces it: **three call
  paths spell these flags three ways**. Step 7 distinguishes the wrappers from
  bare `bd create` (that was g1gf's fix); nothing said anything about
  `bd update`, and guessing there costs the whole call:

  | path | description from a file | acceptance |
  |---|---|---|
  | `bd create` | `--body-file F` | `--acceptance STR` only |
  | `bd update` | `--body-file F` | `--acceptance STR` only |
  | `create-ticket.sh`, `file-friction.sh` (CREATE only) | `--desc-file F` | `--accept-file F` |

  So `--desc-file` and `--accept-file` exist ONLY on the wrappers, which only
  create — an orchestrator *updating* a bead has no wrapper path at all and
  must use `bd update`'s row. Neither `bd` path has any acceptance-from-file
  flag. (`--stdin` is an alias for `--body-file -` on both.) Three sessions have paid
  the same two minutes finding one cell of that table: computenet-9z8t
  (`bd update --acceptance-file`), computenet-g1gf (`bd create --desc-file`),
  computenet-k9th (`bd update --desc-file`, which discarded the `--title`
  write beside it). Set acceptance in its own `bd update` call, and re-read
  the bead rather than trusting a multi-field update's exit code.
- **`create-ticket.sh` can be DENIED by the permission classifier inside a
  dispatched subagent** — not a script error, a refusal of the bash call
  itself, and not universal: the orchestrator in the same session ran the same
  invocation successfully twice (computenet-umw4). The denial lands on the one
  path that exists to prevent the `child_counters` collision, and the obvious
  workaround is exactly the hand-typed `bd create --parent=<shared epic>`
  AGENTS.md forbids. **It is not the workaround.** Do by hand what the script
  does: `bd create` **unparented** (hash id, counter untouched), then
  `bd update <new-id> --parent=<parent>`.

  Two rules that make the denial cheap when it happens: **write the body files
  in a SEPARATE bash call from the create**, because a denial discards the
  heredocs composed in the same invocation — one reviewer's later
  `--desc "$(cat …)"` read an empty file and produced an empty-bodied bead
  that had to be backfilled — and **re-read the bead** after any create you
  did not watch succeed.
- **Repeating `-C <main-checkout>` is what tempts the variable that breaks.**
  `BD="bd -C /path"; $BD show x` fails with `no such file or directory: bd -C
  /path` — zsh does not word-split an unquoted expansion — and it reads as bd
  being missing rather than as a quoting fault. Recurred 2026-08-27 after
  computenet-jobe closed it (computenet-ahg8, and computenet-wahz before
  that). Define a function once instead, and every later call is bare `bd`:

  ```bash
  bd() { command bd -C "$WT" "$@"; }
  ```
- **An EMPTY database answers every read successfully.** The database `bd`
  opens is chosen by cwd (or `-C`) — *not* by "only the main checkout has
  one", which is false in both directions now measured. A worktree **without**
  its own database walks up and reaches the real one: measured 2026-08-19
  from a clean sibling worktree, bare `bd stats` returned 852/129/700,
  identical to `bd -C <main-checkout> bd stats`. And a worktree that has
  somehow **acquired** one answers from *that*: `bd list --limit 3 --json` →
  `[]` (exit 0), `bd stats` → `Total Issues: 0`, `bd show <known-id>` → "no
  issue found", against 702 issues in the main checkout (computenet-8mb3).

  The failure mode is therefore not the missing-database error the old
  wording implied — it is a **well-formed, successful, empty answer**, with no
  warning, no nonzero exit and nothing in the JSON to notice. That matters
  because emptiness ROUTES control flow in this skill: an empty readiness
  answer sends step 3 to `bd defer`, which hides an epic from BOTH machines,
  and an empty liveness listing is a false all-clear on siblings — the exact
  check whose failure produced a four-way collision, reached by a second
  independent route. So **where emptiness decides something, prove the
  database is the right one first**, and treat a failure as "nothing was
  checked" (the `ready-in-epic.sh` exit-3 class), never as "nothing there":

  ```bash
  bd -C <main-checkout> stats | grep -qE 'Total Issues: *[1-9]' \
    || echo "NOT CHECKED: bd opened an empty database — do not route on this"
  ```

  What *creates* a per-worktree database is **not established**. `bd init`
  from inside a worktree is the standing hypothesis and nobody has reproduced
  it; the one instance found was nine days old with mtimes spread across three
  dates, so several sessions wrote to it without noticing. It is recorded here
  as an unexplained observation, not a mechanism — and none of the 15
  worktrees on this machine has one today. Do not write guidance that assumes
  the cause.
- `bd` calls are slow and `bd dolt pull`/`push` can run past 120s — give
  sync commands a ≥300s timeout, and never chain `bd` *writes* in one Bash
  block: one write per call, each with the long timeout, or the chain dies
  mid-sequence and leaves half-recorded state (computenet-9oq,
  computenet-9r8).
- **`bd comments` is PLURAL for reading; `bd comment` is singular for
  writing.** Measured on bd 1.1.2 (Homebrew, 2026-08-20): `bd comments <id>`
  and `bd comments <id> --json` both work; `bd comment <id>` with no body
  fails with `Error: no comment text provided`, and `--list` is not a flag on
  either. A report claiming the reverse was rejected on this measurement
  (computenet-878y) — if a future bd inverts it, record the version here
  rather than swapping the forms on one session's error text.
- **`bd comments`' DEFAULT (non-JSON) view truncates long bodies mid-word.**
  Content past the cut is effectively unwritten for anyone reading it that
  way. An implementer's landing comment held eight numbered interpretations;
  6, 7 and 8 fell in the truncated region, and interpretation 8 was where its
  reviewer found the task's one real error — visible only because that
  reviewer re-read through `--json` (computenet-wq14). So every read goes
  `--json` into a file, the same rule computenet-h0dj established for the
  other five call sites:

  ```bash
  bd comments <id> --json > "$SCRATCH/c-<id>.json"
  ```

  The `--json` shape for comments is a **bare array** of comment objects — not
  an object with a `.comments` key — matching `bd list --json` (computenet-kr18)
  and unlike `bd show --json`, which is a list of one issue.
- **`metadata.files` is a STRING on almost every bead and a JSON ARRAY on a
  few.** Measured 2026-08-19: 2 of ~700 export records array-typed. So the
  natural claim-collision query over the export

  ```bash
  jq -r 'select(.status!="closed") | select(.metadata.files | test("Foo")) | .id' .beads/issues.jsonl
  ```

  dies at the first array record with `array ([...]) cannot be matched, as it
  is not a string`. **The failure mode is a PARTIAL result, not an empty one**
  — jq prints every match it found before the abort and puts the error on
  stderr, so the output reads as a complete answer. A competing claim recorded
  below the abort point is invisible, and missing one is two branches editing
  one file. Normalise both shapes:

  ```bash
  jq -r 'select(.status!="closed")
         | select(((.metadata.files // "") | if type=="array" then join(",") else tostring end) | test("Foo"))
         | .id' .beads/issues.jsonl
  ```

  (`next-batch.py` already handles both; this is for the ad-hoc queries
  orchestrators and breakdown agents write, which are the ones that break —
  computenet-hkgz.)
