# BDS0 rig — synthetic two-workspace `bd` replication harness

This is the shared harness for the BDS0 spike (epic `computenet-8kj`):
"is `bd import` a sound replication write-seam?" It builds two independent,
throwaway `bd` workspaces and lets you simulate one replication hop by hand
— mutate in A, export the delta, stamp provenance into `metadata`, import
into B, read B's journal — so the four claims under test in the epic
description (§1: echo suppression, ordering authority, close replication,
round-trip fidelity) can each be checked against real `bd` behavior instead
of assumed. §2 ("What done means") names this rig as the first deliverable;
the four claims themselves are separate, sibling features
(`computenet-8kj.1.2`–`.1.5` and friends) — this rig only performs the
mechanics faithfully, it does not judge any claim.

**Isolation.** The rig never reads or writes this repository's own
`.beads` directory. Every `bd` call targets a workspace created under a
throwaway `mktemp -d` root, using `bd -C <workspace> --sandbox`, and the
script never runs `bd` with its cwd inside the repo checkout.

**Requirements.** `bd`, `jq`, and `dolt` must all be on `PATH`. All three
are already expected in this repository's dev environment; `journal`
additionally fails with a clear message (rather than a raw error) if
`dolt` specifically is missing, since it is the one dependency `init` and
`hop` don't need.

## Run it

From a clean checkout, with no setup:

```bash
bash scripts/spike/bds0/rig.sh smoke
```

This is the one command that exercises the whole loop end to end: it
creates a fresh rig root with two seeded workspaces (`init`), replicates one
seeded issue from A into B (`hop`), and prints that issue's journal in B
(`journal`). It asserts the issue landed in B and that its journal has at
least one event, and exits nonzero on any failure.

## Subcommands

### `rig.sh init`

Creates a fresh rig root (a `mktemp -d` directory) containing two
independent `bd` workspaces, `A` (prefix `bdsa`) and `B` (prefix `bdsb`),
each seeded with three issues covering the shapes the claim features need:
a plain task, a task carrying provenance metadata (`metadata.cn_dot`), and
a task with a label plus a comment. It also records the `bd` version the
rig ran against, via a sandboxed workspace invocation (`bd -C <ws>
--sandbox version`), to `<root>/bd-version.txt`.

Prints `export BDS0_RIG_ROOT=<root>` on stdout — source this to point the
other subcommands at the new root:

```bash
eval "$(bash scripts/spike/bds0/rig.sh init)"
```

### `rig.sh hop <FROM> <TO> --dot <value> [--allow-stale] [id...]`

Replicates one delta from workspace `<FROM>` into workspace `<TO>` (both
resolved under `$BDS0_RIG_ROOT`, so it must be set — normally by sourcing
`init`'s output first): exports from `<FROM>`, stamps
`metadata.cn_dot=<value>` on the selected rows (preserving every other
metadata key already present), and imports into `<TO>`. With no `id`
arguments every exported row is selected; with `id` arguments, only rows
whose `.id` matches one of them are selected. `--allow-stale` forwards to
`bd import`.

Prints `bd import`'s own report unmodified and exits nonzero if `bd import`
fails. Also writes the stamped delta JSONL to `$BDS0_RIG_ROOT/last-hop.jsonl`
for post-mortem inspection.

### `rig.sh journal <WS> <issue-id>`

Prints workspace `<WS>`'s journal records for `<issue-id>` as JSON, ordered
by `created_at`.

The installed `bd` (1.1.2) has no `bd events` command, and `bd sql` fails
with `'bd sql' is not yet supported in embedded mode`. The journal surface
this subcommand actually reads is the `events` table inside the workspace's
own embedded Dolt database, at `<ws>/.beads/embeddeddolt/<prefix>/` (the
prefix — `bdsa`, `bdsb`, ... — is whatever `bd init --prefix` used, so this
subcommand locates the directory by globbing
`<ws>/.beads/embeddeddolt/*/` rather than hardcoding it). It reads that
table with the `dolt` CLI directly:

```bash
dolt sql -r json -q "select * from events where issue_id='<id>' order by created_at"
```

Fails with a clear message if `dolt` is not installed, or if no embedded
Dolt database is found under the workspace.

### `rig.sh smoke`

Self-contained end-to-end exercise of the whole rig, described under "Run
it" above. Does not require `BDS0_RIG_ROOT` to be pre-set — it runs `init`
itself and uses the resulting root for the rest of the run.

## Where things land

- `<root>/A/`, `<root>/B/` — the two `bd` workspaces (each a normal `bd
  init` directory, so `bd -C <root>/A --sandbox <cmd>` works against them
  directly).
- `<root>/bd-version.txt` — the `bd` version the rig ran against, written by
  `init`.
- `<root>/last-hop.jsonl` — the stamped delta JSONL from the most recent
  `hop`, for post-mortem inspection.

`<root>` itself is whatever `mktemp -d` produced for that `init` call; it is
never committed or reused across runs.
