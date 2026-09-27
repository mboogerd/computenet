---
name: retrospective
description: Weekly reflection on how the development process went. Reads the week's retro records (filed by every /work and /remediate-friction session), the week's friction resolution and the daily sync/council outcomes, finds the recurring issues no single session could see, corroborates them against real runs, puts each proposed change through the deliberate council, and acts on what the council decides. Use when the weekly routine fires, or the user says "/retrospective", "weekly retro", "reflect on the week", "how did the week go".
---

# /retrospective

Each session files a retro record: what it did, and every issue it noticed,
however small (/work's `references/retro.md`). No single session can see a
pattern across them. This skill reads a week of records side by side, finds
the patterns, and decides what to change. It uses /deliberate for every
decision, so a person is only asked when the council cannot agree.

It changes process, not product. What it produces are friction items and
decisions for the remediate-friction lane, which owns `.claude/skills/`. It
never edits a skill itself.

## 1. Gather the week

Take the window from the last retrospective's summary bead (see §6), or seven
days if there is none:
`bd list --type=retro --status=closed --limit 0 --json`, keep
`metadata.retro_skill == "retrospective"`, and take the newest `ended`. Run every bd call in the main checkout, and start with
`bd dolt pull`.

```bash
bd list --type=retro --status=open --limit 0 --json                 # this week's retro records
bd list --parent=computenet-wpvy --all --updated-after <since> --limit 0 --json   # friction filed, fixed, queued
bd list --label=human --all --updated-after <since> --limit 0 --json              # parks, and council verdicts on them
GH_PAGER=cat gh pr list --state merged --search "merged:>=<since> head:friction/" --json number,title
```

Read the retro records in full. For council outcomes, read the `COUNCIL`
comments on the human-labelled beads. Count what you read: records per skill
and per machine. A skill or machine with no records at all is itself a
finding, because that session is not reporting.

## 2. Find the patterns

Group the issue lines by tag, then by theme within each tag. A pattern is any
of these:

- the same theme in **two or more sessions**, even if each instance was small;
- one instance that cost a lot, such as a lost slot, a wrong ship or lost data;
- a friction fix that landed this week and whose issue **came back**
  afterwards (see `recurrence-audit.py`);
- the same judgment call made in different ways by different sessions;
- an `other:<word>` tag that recurs, which may deserve a tag of its own;
- council outcomes that went against the sessions' own defaults, or a run of
  NO-CONSENSUS verdicts on the same kind of question.

For each pattern write down its instances (retro ids and evidence), what it
cost, and whether an open friction item already covers it
(`bd search "<word>" --status all`).

## 3. Corroborate

A retro record is a session's own account. For the patterns you will act on,
check the account against the evidence it cites: the bead thread, the PR, the
CI log. When that is not enough, check the session transcript itself. Local
transcripts live under `~/.claude/projects/<project-slug>/*.jsonl`; grep them
for the command or id rather than reading them whole. Transcripts from the
other machine are not reachable, so say so rather than guessing. Drop a
pattern the evidence contradicts, and record that as a `reporting-miss`.

## 4. Decide

For each pattern that calls for a change, write one decision brief and run the
council. Follow /sync-report's `references/council.md` as the model, with
**one background subagent per pattern**, and never batch them. Each brief
states the pattern with its evidence and the options: at least "change
nothing" and "file or upvote a friction item with this acceptance". Add
whatever else fits: queue a revision, promote a tag, retire a rule. Skip the
council for a pattern whose remedy is already an open friction item; upvote it
with the new instances instead.

## 5. Act

- **DECIDED:** carry out the chosen option. Usually that means filing or
  upvoting under the SDLC epic with `file-friction.sh` or `bd comment`,
  quoting the council's choice, votes and deciding factors. Pull before each
  write and push after it, because the SDLC epic is shared.
- **NO-CONSENSUS:** file one item with the `human` label that carries the
  votes and the options, parked per /work's `references/recovery.md`
  "Parks". That item is how the question reaches the user.
- Once §6 has filed the summary bead, close every retro record you read with
  `consumed: <summary bead id>`,
  including those that fed no pattern. An open record is one no
  retrospective has read yet.

## 6. Summarize

File the week's summary as a retro bead that is closed from the start. That
bead is how the next retrospective finds its window:
`file-retro.sh --skill retrospective --file <summary.md>`, then
`bd close <id> --reason "week <since>..<today>"`. Use the retro template's
headings:

- **Summary** holds records read per skill and machine, and the patterns
  found.
- **Outcomes** holds councils run, with DECIDED and NO-CONSENSUS counts, and
  the items filed and upvoted.
- **Issues** lists the patterns dropped for lack of evidence.

Publish with `publish-beads.sh`. Then report to the user: the patterns in
order of cost, what the council decided for each, what now needs them, and
whether the friction rate is falling week over week. If it is not falling,
say so plainly.
