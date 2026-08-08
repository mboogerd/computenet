# Epic → features

You are breaking one epic (a wishlist item) into features. An epic isn't
directly implementable — produce the feature breakdown, then stop. Don't
fall through into implementing anything yourself.

## Ownership

The epic is already claimed (assignee/`in_progress`) by the session that
dispatched you. Make sure it carries this machine's owner label:

```bash
bd update <id> --add-label=owner:$BEADS_ACTOR
```

Every feature and task created under it (`bd create --parent=<id>`) inherits
that label automatically — no tagging needed downstream. A session's claim
excludes other machines' `owner:` labels (see [claim-sync.md](claim-sync.md)),
so nothing under this epic gets picked up elsewhere until it's done. Do
**not** stamp the epic with a session id — that claim must outlive this
session (claim-sync.md explains why).

## Reconcile before creating

The epic may already have features from a breakdown that died part-way:

```bash
bd list --parent=<id> --json
```

Create only what's missing. Don't duplicate a feature that's already there.

## The breakdown

- Read the full epic (`bd show <id>`) and any spec/doc sections it cites.
  The cited spec text is the authority (AGENTS.md), not your first instinct.
- Propose features that together deliver the epic, each independently
  shippable or at least independently reviewable.
- For each: `bd create --type=feature --parent=<epic-id> --title=... --description=...`
- Give each feature enough context to be broken down later without this
  conversation: what the system does there today, why the work exists, and
  which spec sections govern it. The agent that decomposes it into tasks
  starts fresh and knows the codebase only through what you cite.
- Wire `blocks` dependencies (`bd dep add`) only where one feature genuinely
  can't start before another lands — a real output dependency, not a merely
  preferred order, and not "these might touch the same files" (file overlap
  is handled at task level by scheduling, not by dependencies — see
  [feature.md](feature.md)). Over-wiring starves the queue and idles the
  session.
- Apply the [ask-human.md](ask-human.md) bar: if the epic's scope is
  genuinely ambiguous, or the split has a risky/expensive/hard-to-revert
  fork in it, park a question on the epic instead of guessing a split.

## Finishing

- `bd dolt push` (your `bd create`/`bd dep add` calls are local until pushed).
- Comment on the epic summarizing the features created. Leave it
  `in_progress`, assignee and `owner:` label unchanged — it stays claimed by
  this machine across every future session until all its features close, at
  which point close the epic itself. It is *not* stamped with a session id,
  so the `SessionEnd` hook won't touch it — that's intentional, not an
  oversight.
- Report back: the epic id, that it was broken down, and the feature ids
  created. Your dispatch is done — the session dispatches the next item.
