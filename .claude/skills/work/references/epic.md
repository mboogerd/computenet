# Epic → features

Break one epic into features, then stop. Don't implement anything yourself.

The epic is already claimed and already carries its `owner:` label — the
orchestrator did both before dispatching you. Don't repeat either.

## Reconcile first

```bash
bd list --parent=<id> --all --json
```

A previous breakdown may have died part-way. Create only what's missing.

**Re-read this listing immediately before your first `bd create`, and abort
with a report if the child set changed.** One check at the start is a
check-then-act with a multi-minute window: two breakdown agents dispatched
minutes apart both read "not decomposed" and both proceeded, producing 13
features where 6 belong (computenet-f2p4, and [feature.md](feature.md) carries
the full story). If your own dotted ids **skip numbers** while you are
creating, another writer is creating under this parent right now — stop and
report rather than finishing the set.

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

**Semantic premises need the same treatment, and get less of it.** The
environmental case above is the one people remember (computenet-r2x, fixed in
PR #113). The uncovered case is a claim about code that is present and
readable: *"test X proves property P"*, *"mutation M is expressible"*,
*"this suite already covers that branch"*. Both cost a session in one epic —
a bead asserted a reproducibility test pinned an extraction's byte-stability
when that test compares two JVMs of the SAME build and is blind to exactly the
refactor at issue, and it reached both the implementer and the reviewer as
established fact (computenet-v005). Either state what the cited test actually
compares, or mark the claim `unverified:` so the implementer probes before
building on it. The `unverified:` convention is already in use here and
already works.

**Mark every cost or duration figure MEASURED (naming the run it came from) or
ESTIMATED (in that word), and run any command you prescribe verbatim** — or
label it unverified. An estimate two orders of magnitude wrong, and a
prescribed invocation missing a mandatory flag, both reached implementers in
one epic (computenet-bm7j). Same rules as
[feature.md](feature.md#what-you-may-assert-and-what-a-verification-section-must-reach),
because an epic breakdown writes the same kind of sentence.

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

**Except when the premise is false only HERE. A missing toolchain is not a
park.** If the premise that failed is a tool, daemon, credential or platform
that varies by machine — `cargo`, a running Docker daemon, a cloud
credential — the epic is fine and this machine is simply not the one to run
it. Record it in the form step 3's selection can test, and stand down without
blocking anyone:

```bash
bd update <epic> --add-label "needs:cargo"     # the tool, as `command -v` spells it
bd comment <epic> "Skipped on <machine>: needs cargo, absent here (command -v cargo empty, no ~/.cargo). Labelled needs:cargo; selectable on a machine that has it."
```

An ask-human park is `blocked` + `assignee=human` + the `human` label, which
removes the epic from **every** machine's queue until a person answers — so
parking for a machine-capability reason converts a local fact into a repo-wide
block, by the one machine that could not run it. `computenet-egl` was parked
exactly that way by a `cargo`-less machine while its sibling had `cargo` all
along (computenet-yv63). A comment alone is **not** enough either: selection
reads descriptions and labels, never comments, so the next session on the same
machine re-derives the whole probe from scratch — which is how that park
happened after an earlier session had already recorded the same finding.

**A false premise the epic itself already retired is re-scoped in place, not
parked.** An epic that cites its own upstream spike and says "re-scope against
its finding before writing code" is not blocked — it is instructing you. If
that finding is *decided, verified and prescriptive* (it names the replacement,
and something ran to establish it), rewrite the epic's title, description and
acceptance to match, comment the trace — the finding's id, the artifact, what
changed — and break down the rewritten epic. Park only when the finding is
absent, disputed, or its application to this epic is genuinely ambiguous; that
is ask-human.md's bar, and a decided finding does not clear it. Producing
children against text the repo has already disproved is the expensive failure:
a whole feature set inherits the dead premise, and no downstream reviewer is
positioned to catch it (computenet-taug).

**Report the re-scope to the orchestrator in as many words** — the epic it
claimed is no longer the epic it read, and it has no other way to find that
out. Name the finding, quote the old acceptance and the new one, and say you
rewrote it in place. Without that, the orchestrator is left deciding whether
to trust a breakdown that unilaterally rewrote its own epic's acceptance, on
no evidence.

**If you were told this is a SUB-EPIC under an epic the session already
holds**, you are working inside that claim: break it down exactly as below,
but **do not claim it** — no `--claim`, no assignee, no `owner:` label, and no
comment: leave it `open` exactly as you found it, and report the feature ids
as usual. A second epic claim is what the orchestrator is forbidden to spend.
The provenance comment — what stops a concurrent machine reading the open
sub-epic as free — is the orchestrator's to write and push, not yours
(computenet-k9uh).

## Break it down

Read the full epic (`bd show <id>`) and every spec/doc section it cites —
the cited spec text is the authority (AGENTS.md), not your first instinct.

Propose features that together deliver the epic, each independently
shippable or at least independently reviewable.

**Evidence that can only be produced on another platform or inside a CI job
is named as such**, with the command that reads its answer. The same rule
`feature.md` applies to task clauses applies to the epic's own acceptance: a
criterion whose proof lives in the serial lane, or on Linux, is unsatisfiable
where an implementer stands, and saying so up front costs a sentence while
discovering it costs slot time (computenet-wpvy.31).

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

**A required dependency whose target is NOT an epic has one answer, so two
runs produce the same graph.** `bd` refuses an edge between an epic and a
non-epic (below), so "this epic needs feature `X.3` of another epic" is
unexpressible as written. **Push the edge down to the feature that actually
needs it** — the feature-to-feature edge is same-class and legal — and
**comment on the epic saying you did**, naming both ids. Do not invent an
epic-to-epic edge as a stand-in: it blocks work that has no dependency, and
the epic it names may never be scheduled. Without this written down, two
breakdowns facing the same shape produce two different graphs
(computenet-mxa).

**A blocking edge cannot cross the epic boundary** — `bd` refuses one between
an epic and a non-epic. So "these items wait on this epic's deliverable" is
unexpressible as written, and the shape that *is* expressible is to give the
epic's own deliverable a **feature** child and block the dependents on that
feature: a same-class edge `bd` accepts. Filing the dependents as children of
the epic instead and leaving the deliverable unrepresented is the failure this
prevents — the epic then reads as already broken down, its own work is never
scheduled, and the children wait forever on something nobody is building
(computenet-45rf).

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

Comment the features created on the epic.
The invocation, since it is the one command this file asks you to run and
nothing else shows it — the body is **positional**; `--text`, `--body` and
`bd comment add` are all wrong and have each been guessed by a different
agent (computenet-danb, computenet-63pn):

```bash
bd comment <id> "<text>"
bd comment <id> --file "$SCRATCH/note.md"   # any body that quotes code
```

Use the `--file` form whenever the text contains backticks: inside a
double-quoted argument they execute as shell and the word vanishes from the
stored comment while `bd` reports success ([bd-traps.md](bd-traps.md)).
 Leave the epic `in_progress` — the
orchestrator releases the claim at its Finalize (an epic binds to a session,
never across sessions; the features carry the resume state). Report the
feature ids.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
