# What you write yourself is the one thing nobody reviews

Read this before writing durable text that makes a causal claim — a commit
message or PR body explaining a failure, a dispatch prompt framing a
problem for another agent, a bead describing a mechanism. SKILL.md's
summary of this rule is the operative version; this file is the full case
for it.

Every task and every feature goes to a dispatched reviewer. Your own output
does not: the conflict resolutions and unblocking fixes of 5e, the commit
messages and PR bodies you write, and the framing you put in a dispatch
prompt all land in `main`'s history and in other agents' heads exactly as
you wrote them.

**Claim the observation; do not claim the mechanism unless you tested it.**
A causal sentence — "this fixes the flake", "the two runs raced each other
for the runner's CPU", "the failure is about thread ordering" — is a claim
about the world. 2026-08-09, PR #14: the orchestrator observed a real
defect (every commit on an open PR started two CI runs) and then invented a
mechanism for the flakes it was seeing. A reviewer disproved it three
ways — isolated re-runs with nothing else in flight still failed, a lone
push run on a PR-less branch failed the same port-rebinding test, and every
Actions job gets its own ephemeral VM (zero self-hosted runners), so two
runs cannot share a CPU or a port. By then the mechanism was in the commit
message, the PR body, and a comment in `ci.yml`.

Before a causal claim goes into durable text, it needs one of:

- **a run that distinguishes it from the alternative** — you changed the
  supposed cause and the effect changed — quoted with the run id and the
  verbatim `FAILED` (or now-passing) line; or
- **a mechanism that cannot be otherwise**, cited to the artifact that
  makes it so (the workflow file, the runner documentation), not to your
  reading of it.

Without one of those, write what you actually have. "Halves the number of
CI runs per commit; whether that affects the observed flakes is untested"
is a true sentence and costs nothing to write. Counts fall under the same
rule: in those same texts "six distinct tests" was five and "every required
check both passed and failed on the same sha" was two of five. Count them
from the output before you write them.

The rule is *more* expensive in a **dispatch prompt**, because a subagent
cannot tell your speculation from your evidence — your framing arrives as
established fact. Relay only the artifact: the run id, the job, and the
verbatim `FAILED` line, plus an explicit "the mechanism is unknown, read it
from the log." A brief that guessed a test's package (`cell.wire` for what
was `cell.host`, so `--tests` matched nothing) and guessed its mechanism
cost a reviewer 8 wasted runs before it stopped trusting the framing and
read the CI log, and produced a duplicate bead on top. Search beads for the
failing test before you ask a second agent to look at it.

And code **you** write — a merge conflict resolution, an unblocking fix —
goes to a reviewer on the same terms as task work (5c). It is the one code
path in this flow that no dedicated reader sees, so dispatching that
reviewer is not discretionary.
