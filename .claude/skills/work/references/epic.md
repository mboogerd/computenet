# Epic → features

Break one epic into features, then stop. Don't implement anything yourself.

The epic is already claimed and already carries its `owner:` label — the
orchestrator did both before dispatching you. Don't repeat either.

## Reconcile first

```bash
bd list --parent=<id> --all --json
```

A previous breakdown may have died part-way. Create only what's missing.

## Verify the load-bearing premises first

An item can assert its own infrastructure. "Over the existing Headscale
tailnet that is reachability and encryption for free" is a *premise*, not a
requirement, and the breakdown inherits it into every child it writes.
Epic computenet-o97 did exactly that: two of five features were written on top
of that sentence, and there is no tailnet on this machine — no
tailscale/headscale binary, no `100.64.0.0/10` address on any interface,
nothing in the repo mentioning it. One `ifconfig` would have caught it, before
~40 minutes of breakdown across two agents.

So before you decompose: **list the item's load-bearing environmental
premises — a host, a network, a service, a credential, a tool on `PATH`, a
platform behaviour — and verify each with a concrete command.** Put the
command and its output in the item's comment thread, so the next reader does
not re-derive it.

```bash
command -v <tool>            # a tool the item assumes is installed
ifconfig | grep 100.64       # a network: grep an ADDRESS PREFIX, not a CIDR —
                             # interfaces render 100.x.y.z, so 100.64.0.0/10 never matches
ls <path>                    # a host artifact the item assumes exists
curl -sS -o /dev/null -w '%{http_code}' <url>   # a service it assumes is up
```

**If a premise is false, park the item** ([ask-human.md](ask-human.md)) rather
than producing children that inherit it. Deferring the question into a child
task as a "discovery step" is not verification — it is the same unverified
assumption, one level further from anyone who could notice.

## Break it down

Read the full epic (`bd show <id>`) and every spec/doc section it cites —
the cited spec text is the authority (AGENTS.md), not your first instinct.

Propose features that together deliver the epic, each independently
shippable or at least independently reviewable.

**Write them to [issue-quality.md](issue-quality.md)** — the feature section
in particular (example mapping, EARS-phrased rules, concrete examples). It is
the standard the feature reviewer judges against, so an issue that doesn't
meet it fails later rather than never.

```bash
# Bodies quoting code: heredoc-build them first (issue-quality.md
# "Backticks…", computenet-9w9) — inline backticks execute as shell.
bd create --type=feature --parent=<epic-id> --validate \
  --title="<outcome as a change to the system>" \
  --description="<what the system does here today, why this work exists, which spec sections govern it, what's out of scope>" \
  --acceptance="<EARS-phrased rules that define 'this feature is delivered'>" \
  --design="<the examples: Given/When/Then per rule, plus assumptions you decided>"
```

`--acceptance` is not optional. A dedicated reviewer judges the finished
feature against exactly these statements and decides on that basis whether
its PR ships ([review-feature.md](review-feature.md)) — a feature without
them gives that gate nothing to check. Write them at feature level: what
must be true once the whole thing works, not what each task does.

Give each feature enough context to be decomposed later *without this
conversation*: what the system does there today, why the work exists, which
spec sections govern it. The agent that turns it into tasks starts fresh and
knows the codebase only through what you cite.

If the epic's own success criteria don't meet
[issue-quality.md](issue-quality.md) — vague, uncheckable, or absent — repair
them in place (`bd update <epic-id> --acceptance=…`) before splitting. You
cannot trace features to criteria that don't exist, and every later gate
depends on that trace.

Wire `bd dep add` only for real output dependencies — one feature genuinely
cannot start until another lands. Not a preferred order, and not "these
might touch the same files" (file overlap is handled by task-level
scheduling — see [feature.md](feature.md)). Over-wiring starves the queue.

Apply the [ask-human.md](ask-human.md) bar: if the epic's scope is genuinely
ambiguous, or the split has a risky/expensive/hard-to-revert fork, park a
question on the epic rather than guessing a split.

## Finish

```bash
bd lint <feature-ids...>
```

Fix anything `bd lint` reports. The features you created live in the local
beads DB until the orchestrator's Finalize push (SKILL.md step 6) sends them
to the shared tracker — don't sync here; only acquisitions are synced
mid-session, and this is not one (claim-sync.md).

Then check the trace: every
epic success criterion is covered by at least one feature, and every feature
serves at least one criterion. A criterion with no feature means the
breakdown isn't finished; a feature serving none means it's out of scope.

Comment the features created on the epic. Leave the epic `in_progress` — the
orchestrator releases the claim at its Finalize (an epic binds to a session,
never across sessions; the features carry the resume state). Report the
feature ids.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
