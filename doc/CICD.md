# CI/CD

## Phase 1 (built)

Two workflows, both GitHub-native.

### `.github/workflows/ci.yml` — the test gate

Runs on every `push` and `pull_request`. JDK 21 Temurin (matching
`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`'s `jvmToolchain(21)`), Gradle
cached via `gradle/actions/setup-gradle@v4`. Six jobs, each with a
`timeout-minutes` cap so a hung test can't burn a runner indefinitely:

| Job | What it gates | Timeout |
|---|---|---|
| `build-test-fast` | `./gradlew build check -PexcludeMultiJvm=true` (excluding `:kernel:test`), plus the clean-clone untracked-files guard | 45m |
| `kernel-test` | `:kernel:test` alone — split out of the fast lane so the repo's largest suite runs concurrently (computenet-dqy.16) | 30m |
| `build-test-serial` | `./gradlew check -PmultiJvmOnly=true --max-workers=1` — the two-JVM/ProcessBuilder tests | 30m |
| `concord-full` | full concord corpus (`core,dist,dur`) + `CONCORDANCE.md` regeneration must be a no-op | 30m |
| `ui-test` | `inspect/ui` typecheck + tests | 15m |
| `agora-ui-test` | `demo/agora/ui` typecheck + tests | 15m |

Together these are `./gradlew test`'s superset — the split into fast/serial
lanes exists because the multi-JVM tests fork real processes and starve when
contended (see `doc/remediation/AUDIT-2026-07-28.md` §W1). `ubuntu-latest`
gives this repo the 4-vCPU/16-GB standard runner tier, not the 2-vCPU/8-GB
private-repo tier, because the repo is public; measured via
`announcement-probe.yml`'s sampler on run 31673273722, which printed
`runner: 4 cores` and `Mem: 15989` MB.

### `.github/workflows/auto-merge.yml` — auto-merge

On PR `opened`/`synchronize`/`reopened`/`ready_for_review`, runs
`gh pr merge --auto --squash` with the built-in `GITHUB_TOKEN`. This only
*queues* the merge: GitHub performs it once the required status checks pass and
the PR is mergeable, and abandons it if a check fails.

Guarded to skip drafts and fork PRs (fork PRs receive a read-only token, so the
call would fail).

## Required repo settings (cannot be set from a workflow file)

Flip these in GitHub, or the two workflows above do nothing useful:

1. **Settings → General → Pull Requests → Allow auto-merge** — check it.
   Without this, `gh pr merge --auto` errors out.
2. **Settings → Rules → Rulesets** (or Branches → branch protection) on `main`:
   - Require a pull request before merging.
   - **Require status checks to pass**, and add all **six** as required checks:
     `build-test-fast`, `build-test-serial`, `concord-full`, `ui-test`,
     `agora-ui-test`, `kernel-test`. This list is a copy; the ruleset is the
     original, and `AGENTS.md` § "Branches, PRs, and auto-merge" carries the
     `gh api` one-liner that reads it (computenet-4prd).
   - Optionally "Require branches to be up to date before merging" (safer, but
     costs a re-run per intervening merge).
3. **Settings → General → Merge button → Allow squash merging** — enabled
   (default), since the auto-merge workflow squashes.

Without a required status check, auto-merge fires the moment the PR is
mergeable — i.e. immediately, before CI finishes. Step 2 is what makes
auto-merge mean "merge after green".

## Phase 2 (planned, not yet built)

Stubs only — none of this exists.

- **PR completion agent.** Watches for PRs that either fail the test gate or
  are thin (interface-only, stub bodies, no tests) and completes the
  implementation per `AGENTS.md`: read the cited spec, respect the core
  invariants, add the failure/recovery test. Open question: trigger surface
  (workflow_run vs. label), and how to bound its write access.
- **LLM acceptance-criteria evaluator.** Reads the PR body's stated acceptance
  criteria and the diff, and reports whether each criterion is actually met.
  Advisory comment first; only a required check once its false-positive rate is
  measured.
- **Coverage gate on changed lines.** Jacoco over the changed lines only (not
  whole-repo ratcheting), failing below a threshold. Needs a baseline
  measurement before a number is picked.
