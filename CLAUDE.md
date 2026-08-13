# ComputeNet — agent context

@AGENTS.md

Architecture reference: `doc/ARCHITECTURE.md`. User-facing intro: `README.md`.


## Choosing work: bv (beads_viewer)

Work **selection and prioritization** starts with `bv`
(https://github.com/Dicklesworthstone/beads_viewer), a graph-aware triage
engine over the beads workspace. `bd` remains the single blessed CLI for
mutations — create, update, claim, close, dep. Where the managed Beads blocks
below say `bd ready` finds available work, read that as the *listing* command;
the *decision* of what to pick up comes from `bv`. Do not use `br`
(beads_rust); it is not installed here — `bd` is the mutation tool.

Rules:

- Use **only `--robot-*` flags**. Bare `bv` opens an interactive TUI that
  blocks an agent session.
- Run `bv` from the main repository checkout. It reads the passive export
  `.beads/issues.jsonl`, which `bd` keeps fresh on every mutation; worktrees
  have no export, so `bv` fails there by design.
- `bv` output embeds `br ...` claim/show commands. Translate them to `bd`
  (`bd update <id> --claim`, `bd show <id>`).
- Recommendations can include blocked or already-claimed work ranked by graph
  importance. Only `quick_ref.top_picks` and entries marked actionable are
  claimable; verify with `bd show <id>` before claiming.

Commands (verified in this workspace 2026-08-12, bv v0.18.0):

```bash
bv --robot-triage     # THE entry point: ranked picks, quick wins, blockers, health
bv --robot-next       # single top pick only
bv --robot-plan       # parallel execution tracks (multi-agent scheduling)
bv --robot-alerts     # stale issues, blocking cascades
bv --robot-suggest    # hygiene: duplicates, missing deps
bv --robot-insights   # full graph metrics (PageRank, betweenness, cycles)
bv --robot-triage --graph-root <epic-id>   # scope triage to one epic's subgraph
```

(`--format toon` is documented upstream but unavailable here — it needs the
`tru` binary, which is not installed; bv falls back to JSON.)

Workflow: `bv --robot-triage` → verify with `bd show <id>` → `bd update <id>
--claim` → work → `bd close <id>`.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:6cd5cc61 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses a native Dolt remote on DoltHub (`sync.remote` in .beads/config.yaml); `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push          # publication push at end of session (acquisition brackets may have synced earlier)
   git push
   git status
   ```
   That end-of-session `bd dolt push` is the session's *publication* sync.
   The governing principle (decided 2026-08-13, computenet-wpvy.3): **sync
   brackets acquisition, not writes; ownership makes writes free.** Writes
   inside owned territory — items under an epic the session claimed, items
   it claimed — stay local until the publication push; do not sync per
   close, per comment, or per commit there. Acquisitions and shared-surface
   writes — claiming an epic, claiming an item in another epic, filing or
   upvoting under the SDLC epic (`computenet-wpvy`) — are each bracketed
   pull → verify → write → push at the moment they happen. A round-trip is
   ~30s, so a handful per session is noise; the former exactly-two-syncs
   rule is retired as an invariant and survives only as the publication
   cadence. Any other round-trip — catch-up after a failed push, refreshing
   an idle machine — is `scripts/beads-nightly-sync.sh`, which **no
   scheduler currently runs**: a human invokes it or installs a schedule.
   See `doc/ops/beads-sync-runbook.md` (§0 for the sync policy and its
   history, §5 for the caller inventory, §8 for installing a schedule).
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
