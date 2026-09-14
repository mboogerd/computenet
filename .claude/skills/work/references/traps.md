# Command traps

A lookup for every role — orchestrator and dispatched agents — when a `bd`,
`git`, `gh` or shell command gives a result you are about to act on. Each row
is a command that returns a well-formed wrong answer instead of an error.
Scan the table for your tool before you route a decision on its output.

## Contents

- The principle
- bd
- git and worktrees
- gh and CI
- Shell

## The principle

**An empty, null or zero result is evidence about the command before it is
evidence about the world.** In this environment most failures look like clean
negatives: a parse that died prints nothing, a missing JSON key reads `null`,
a search that never ran reports no matches. Before emptiness routes a decision
(defer an epic, "no criteria", "no prior item", "no edges"), confirm it with a
query whose failure would look different — read the raw output, check the key
exists, or search for something you know is present.

Examples: `jq '.[0].acceptance'` prints `null` on an item that has criteria
(the key is `acceptance_criteria`); `bd search` returns nothing for an item
that exists (it searches titles only).

## bd

Throughout, `<main-checkout>` is the main repository checkout and `<scratch>`
is your own scratch directory ([agent.md](agent.md) "Scope").

| Symptom | Cause | Do |
|---|---|---|
| `jq` fails, or prints nothing, on `bd … --json` | `bd` prints warnings before the JSON and can print a pagination trailer after it | Slice first: `sed -n '/^[[{]/,/^[]}]/p'`. Where emptiness routes anything, validate the sliced document with `jq -e .` before indexing into it. |
| Every field of `bd show <id> --json` is `null` | The output is a list of one | Use `bead.sh` (below), which emits one object. Raw: unwrap `.[0]`. |
| A field reads `null` on an item that has it | Wrong key name; `jq` answers a missing key with `null` | Check `has("<field>")` before believing a `null`. Key names are in the table below. |
| A bead read is cut off, or its tail is another item's text | `bd show` inlines the parent's full description once per dependency; the plain view is as large | Read every item with `.claude/skills/work/scripts/bead.sh -C <main-checkout> <id>` (add `-r '<filter>'` for one field). Exit 3: output spilled to the file named on stderr; Read that file. Exit 1: no such id. |
| Any other read that can be large (comments, epic listings) comes back short | Tool-result size limits truncate silently | Redirect to a file in `<scratch>` and Read the file. |
| Fewer comments than exist, or a body cut mid-word | Inline and default views truncate | `bd comments <id> --json > "<scratch>/c-<id>.json"`, then Read it. The JSON is a bare array. `bd comments` reads; `bd comment` writes. |
| Stored comment or description is missing words, yet `bd` exited 0 | Backticks and `$(...)` inside a double-quoted argument ran as commands | Put any free text that quotes code in a file (quoted heredoc below) and pass the file: `bd comment <id> --file <path>`, `--body-file` on `bd create`/`bd update`. Write the file in its own Bash call. Re-read what landed. |
| `bd search` finds nothing | It matches title and id substrings only, and excludes closed items by default | Use `bd show <id>` when you have an id; otherwise `bd list --parent=<epic> --all --json` to a file. Never treat an empty search as absence. |
| Every read succeeds but returns nothing | `bd` opened an empty database (chosen by cwd or `-C`) | Before routing on emptiness, check `bd -C <main-checkout> stats` reports a non-zero `Total Issues`. |
| An epic looks like it has no ready work | `bd ready --parent` does not reliably reach grandchildren; `bd ready` hides in-progress, blocked and deferred items | Run `.claude/skills/work/scripts/ready-in-epic.sh <epic>`. Exit 3 means nothing was checked; a `could not resolve the epic of <id>` line on stderr means that row was not classified. |
| An item parked for a human is missing from `bd blocked` | `bd blocked` lists dependency-blocked items only | Query by status: `bd list --status=blocked --json`. |
| `bd list` misses closed items | `bd list` hides closed without `--all` | Add `--all` when closed items count. |
| `jq '.[]'` on `bd list --json` yields nothing | The shape is an array by default and `{"issues":[...]}` under `--skip-labels` | Select rows shape-agnostically: `(if type=="array" then . else (.issues // []) end)[]`. |
| A `metadata.files` query stops partway, with an error on stderr | The field is a string on most items and an array on a few | Normalise first: `((.metadata.files // "") \| if type=="array" then join(",") else tostring end)`. |
| A multi-flag `bd update` changed nothing | One unknown flag aborts the whole call | Check spellings in the flag table below. Re-read the item after any multi-field write. |
| State half-recorded, or a compound call refused | Several `bd` writes in one Bash call | One `bd` write per Bash call, each with a timeout of at least 300000 ms. Confirm each landed before the next. |
| Two machines mint the same child id | `bd create --parent=<shared parent>` draws ids from a per-database counter | Under a parent you did not claim, use `.claude/skills/work/scripts/create-ticket.sh`. If it is refused, create unparented, then `bd update <new-id> --parent=<parent>`. Under a parent your session claimed, `--parent` is fine. |
| A created item has no `model` or `files` | `bd create` has no `--set-metadata` | Pass `--metadata '<json object>'` on the create. Clear a key with `bd update <id> --unset-metadata <key>`. |
| A child of a parked item is itself hidden as human-owned | `bd create` inherits the parent's labels | Pass `--no-inherit-labels`. |

Key names in `bd show --json` and `bead.sh`:

| You want | Read | Not |
|---|---|---|
| Parent | `.parent` (absent when unset); effective epic via `.claude/skills/work/scripts/epic-of.sh` | `.parent_id` |
| Acceptance | `.acceptance_criteria` (written with `--acceptance`) | `.acceptance` |
| Comment count / bodies | `.comment_count` / `bd comments <id> --json` | `.comments` |
| Dependencies | `.dependencies` (raw `bd show`), `.dependency_ids` (`bead.sh`), `.dependency_count` (both) | the other path's name |

Flags for free text and acceptance:

| Command | Description from a file | Acceptance | Title |
|---|---|---|---|
| `bd create` | `--body-file <f>` | `--acceptance "<text>"` only | positional or `--title` (`-t` is `--type`) |
| `bd update` | `--body-file <f>` | `--acceptance "<text>"` only | `--title` |
| `create-ticket.sh`, `file-friction.sh` (create only) | `--desc-file <f>` | `--accept-file <f>` | `--title` |

Neither `bd` path reads acceptance from a file. Pass `--acceptance "$(cat <f>)"`
with the file written in an earlier call: the substituted text is not expanded
again. The inert way to write any body file:

```bash
cat > "<scratch>/body.md" <<'EOF'
Text with `backticks` and $(dollar-parens) stays literal.
EOF
```

## git and worktrees

| Symptom | Cause | Do |
|---|---|---|
| `git stash pop` restores someone else's changes | The stash is one stack shared by every worktree of the repository | Get before-and-after without stashing: commit, then compare with `git show <base>:<path> > "<scratch>/before"`. If you must stash: `git stash push -u -m "<unique-tag>"`, note its sha from `git stash list --format='%H %gs'`, and `git stash apply <sha>`, never `pop`. |
| A commit in the main checkout contains files you did not stage | Sessions working in the main checkout share one index | Commit by pathspec, `git commit -m "<msg>" -- <paths>`; never `--amend` there; check `git show --stat HEAD`. |
| `git grep` returns zero, or a revision path resolves wrong | Pathspec, regex and zsh-expansion hazards | See AGENTS.md "Implementation conventions". |

## gh and CI

| Symptom | Cause | Do |
|---|---|---|
| `gh pr checks` exits non-zero while checks look fine | It exits 8 while anything is pending | Classify on output, never `$?`. Wait with `.claude/skills/work/scripts/wait-checks.sh <pr-url>`; its header documents the tokens. |
| `wait-checks.sh` ends `SETTLED` | `SETTLED` means none pending, including failed | Read the rows above it for any non-pass required check before acting. |
| `wait-checks.sh` ends `TIMEOUT-PENDING` or `QUERY-FAILED` | Checks outran one call, or nothing was read | The orchestrator re-runs it (two calls is a normal cold start; only a `STUCK` label is a defect). A reviewer gets one call per head (review.md "Read CI once per head"). |
| A `gh` call returns no output at all | The harness backgrounded it | Not a reading. Run the same command again; if it fails to return twice, report the host as the problem. |
| `gh` fails with 503 or a socket error | Transient GitHub or local exhaustion | Retry a few times with back-off, then re-read the state the call should have changed. |
| `if gh … \| tail -1` reported success but nothing happened | A pipeline's status is its last stage's | Never test a pipeline's status; re-read the write. |
| `gh pr comment` keeps failing | GraphQL is degraded while REST works | `gh api -X POST repos/{owner}/{repo}/issues/<n>/comments -F body=@<file>`. `gh pr ready` has no REST path; retry it. |
| A PR create or edit with an inline `--body` failed or mangled text | Code in the body ran as shell | Use `--body-file <f>`; verify with `gh pr view <n> --json body`. |
| `gh pr checks --watch` returns at once on a fresh push | Only `auto-merge` has reported yet | Not a wait; use `wait-checks.sh`. |

## Shell

Sessions run zsh. The hazards where a correct-looking command silently runs
something else are in AGENTS.md "Implementation conventions". Two more:

- `${PIPESTATUS[n]}` is empty under zsh; the lowercase `pipestatus` array
  holds the stage statuses. Prefer not piping a command whose status matters.
- To avoid retyping `-C` on every `bd` call, define a function, not a
  variable: `bd() { command bd -C "<main-checkout>" "$@"; }`.
