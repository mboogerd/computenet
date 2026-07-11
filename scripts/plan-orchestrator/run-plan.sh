#!/usr/bin/env bash
set -euo pipefail

# Deterministic host-side orchestration for the ticket-shaped Markdown plan.
# Codex containers may edit only their worktree; git lifecycle stays on the host.

ROOT=$(git rev-parse --show-toplevel)
PLAN=${PLAN:-"$ROOT/doc/spec/90-roadmap/94-implementation-plan.md"}
STATE_DIR=${STATE_DIR:-"$ROOT/.codex-orchestrator"}
WORKTREE_ROOT=${WORKTREE_ROOT:-"${TMPDIR:-/tmp}/computenet-plan-worktrees"}
IMAGE=${IMAGE:-computenet-codex-worker:local}
MAX_PARALLEL=${MAX_PARALLEL:-3}
MAX_RECOVERY=${MAX_RECOVERY:-2}
BASE_BRANCH=${BASE_BRANCH:-main}
MODEL=${MODEL:-}
VALIDATE_COMMAND=${VALIDATE_COMMAND:-./gradlew test}
DRY_RUN=0
ONLY_WAVE=

usage() {
  cat <<'EOF'
Usage: scripts/plan-orchestrator/run-plan.sh [options]

  --dry-run          Parse and display scheduling without Docker/git mutations
  --wave N           Run only wave N (earlier waves are assumed complete)
  --plan PATH        Use another plan file with `## Wave` / `### Wn.n` headings
  --help             Show this help

Environment: IMAGE, CODEX_VERSION, MAX_PARALLEL (hard-capped at 3),
MAX_RECOVERY (hard-capped at 2), MODEL, VALIDATE_COMMAND, BASE_BRANCH,
STATE_DIR, WORKTREE_ROOT.
EOF
}

while (($#)); do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --wave) ONLY_WAVE=$2; shift ;;
    --plan) PLAN=$2; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

(( MAX_PARALLEL >= 1 && MAX_PARALLEL <= 3 )) || { echo "MAX_PARALLEL must be 1..3" >&2; exit 2; }
(( MAX_RECOVERY >= 0 && MAX_RECOVERY <= 2 )) || { echo "MAX_RECOVERY must be 0..2" >&2; exit 2; }

ITEM_DIR="$STATE_DIR/items"
LOG_DIR="$STATE_DIR/logs"
RESULT_DIR="$STATE_DIR/results"
mkdir -p "$ITEM_DIR" "$LOG_DIR" "$RESULT_DIR" "$WORKTREE_ROOT"

declare -a ITEM_IDS=()
declare -a ITEM_WAVES=()
declare -a ITEM_TITLES=()

parse_plan() {
  local line wave= id= title= file=
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^##[[:space:]]Wave[[:space:]]([0-9]+) ]]; then
      wave=${BASH_REMATCH[1]}
      id=
    elif [[ "$line" =~ ^###[[:space:]](W([0-9]+)\.([0-9]+))[[:space:]]—[[:space:]](.*)$ ]]; then
      id=${BASH_REMATCH[1]}
      wave=${BASH_REMATCH[2]}
      title=${BASH_REMATCH[4]}
      file="$ITEM_DIR/$id.md"
      ITEM_IDS+=("$id")
      ITEM_WAVES+=("$wave")
      ITEM_TITLES+=("$title")
      printf '# %s — %s\n' "$id" "$title" >"$file"
    elif [[ -n "$id" ]]; then
      [[ "$line" =~ ^## ]] && id= || printf '%s\n' "$line" >>"$file"
    fi
  done <"$PLAN"
}

parse_plan
((${#ITEM_IDS[@]} > 0)) || { echo "No work items found in $PLAN" >&2; exit 2; }

waves() {
  printf '%s\n' "${ITEM_WAVES[@]}" | awk '!seen[$0]++' | sort -n
  return 0
}

items_for_wave() {
  local wanted=$1 i
  for ((i=0; i<${#ITEM_IDS[@]}; i++)); do
    [[ "${ITEM_WAVES[$i]}" == "$wanted" ]] && printf '%s\t%s\n' "${ITEM_IDS[$i]}" "${ITEM_TITLES[$i]}"
  done
  return 0
}

if ((DRY_RUN)); then
  while IFS= read -r wave; do
    [[ -n "$ONLY_WAVE" && "$wave" != "$ONLY_WAVE" ]] && continue
    echo "Wave $wave (maximum $MAX_PARALLEL concurrent workers)"
    items_for_wave "$wave" | sed 's/^/  /'
  done < <(waves)
  exit 0
fi

for command in docker git jq; do
  command -v "$command" >/dev/null || { echo "Required command missing: $command" >&2; exit 2; }
done
[[ -f "$HOME/.codex/auth.json" ]] || { echo "Missing Codex authentication: $HOME/.codex/auth.json" >&2; exit 2; }
[[ $(git branch --show-current) == "$BASE_BRANCH" ]] || { echo "Checkout $BASE_BRANCH before running" >&2; exit 2; }
[[ -z $(git status --porcelain) ]] || { echo "Main worktree must be clean" >&2; exit 2; }

docker build \
  --build-arg "CODEX_VERSION=${CODEX_VERSION:-0.144.0-alpha.4}" \
  --tag "$IMAGE" "$ROOT/scripts/plan-orchestrator"

agent_prompt() {
  local id=$1 attempt=$2 item_file=$3
  cat <<EOF
You are the implementation worker for exactly one ticket: $id.
This is attempt $attempt. Work only on the ticket below; do not implement other plan items.
Read the cited specifications and repository guidance. Inspect current code, implement the ticket,
add/update focused tests, and run appropriate verification. Do not commit, merge, create worktrees,
or modify the implementation plan. The host owns git lifecycle. Preserve unrelated changes.
Return status=completed only when the implementation and tests are genuinely complete.

$(cat "$item_file")
EOF
}

run_agent() {
  local id=$1 attempt=$2 worktree=$3 prompt_file=$4 log=$5 result=$6
  local -a model_args=()
  [[ -n "$MODEL" ]] && model_args=(--model "$MODEL")
  docker run --rm \
    --network bridge \
    --cpus "${WORKER_CPUS:-4}" \
    --memory "${WORKER_MEMORY:-8g}" \
    --mount "type=bind,src=$worktree,dst=/workspace" \
    --mount "type=bind,src=$ROOT,dst=$ROOT,readonly" \
    --mount "type=volume,src=computenet-gradle-cache,dst=/root/.gradle" \
    --mount "type=bind,src=$HOME/.codex/auth.json,dst=/codex-home/auth.json,readonly" \
    --mount "type=bind,src=$ROOT/scripts/plan-orchestrator/result.schema.json,dst=/result.schema.json,readonly" \
    "$IMAGE" \
    --dangerously-bypass-approvals-and-sandbox \
    --dangerously-bypass-hook-trust \
    --ignore-user-config --ephemeral --json --color never \
    --output-schema /result.schema.json --output-last-message /workspace/.codex-result.json \
    "${model_args[@]}" - <"$prompt_file" >"$log" 2>&1
  cp "$worktree/.codex-result.json" "$result"
  rm -f "$worktree/.codex-result.json"
}

validate_worktree() {
  local worktree=$1 log=$2
  docker run --rm \
    --network none \
    --cpus "${WORKER_CPUS:-4}" \
    --memory "${WORKER_MEMORY:-8g}" \
    --mount "type=bind,src=$worktree,dst=/workspace" \
    --mount "type=volume,src=computenet-gradle-cache,dst=/root/.gradle" \
    --entrypoint /bin/bash "$IMAGE" -lc "$VALIDATE_COMMAND" >"$log" 2>&1
}

worker() {
  local id=$1 title=$2 branch="codex/plan-$1" worktree="$WORKTREE_ROOT/$1"
  local attempt prompt log result status plan_rel=${PLAN#"$ROOT/"}
  # Worktree registration mutates shared Git metadata, so serialize this small
  # host-only section while workers themselves remain parallel.
  while ! mkdir "$STATE_DIR/git-metadata.lock" 2>/dev/null; do sleep 0.1; done
  git worktree remove --force "$worktree" >/dev/null 2>&1 || true
  git branch -D "$branch" >/dev/null 2>&1 || true
  git worktree add -b "$branch" "$worktree" "$BASE_BRANCH" >/dev/null
  rmdir "$STATE_DIR/git-metadata.lock"

  for ((attempt=0; attempt<=MAX_RECOVERY; attempt++)); do
    prompt="$STATE_DIR/prompt-$id-$attempt.txt"
    log="$LOG_DIR/$id-$attempt.jsonl"
    result="$RESULT_DIR/$id-$attempt.json"
    agent_prompt "$id" "$attempt" "$ITEM_DIR/$id.md" >"$prompt"
    if run_agent "$id" "$attempt" "$worktree" "$prompt" "$log" "$result"; then
      status=$(jq -r '.status // "blocked"' "$result" 2>/dev/null || echo blocked)
      if [[ "$status" == completed \
        && -n $(git -C "$worktree" status --porcelain) \
        && -z $(git -C "$worktree" diff --name-only -- "$plan_rel") ]] \
        && validate_worktree "$worktree" "$LOG_DIR/$id-$attempt-validation.log"; then
        git -C "$worktree" add -A
        git -C "$worktree" commit -m "$id: $title" >/dev/null
        printf 'ready\t%s\t%s\t%s\n' "$id" "$branch" "$worktree" >"$STATE_DIR/$id.status"
        return 0
      fi
    fi
  done

  printf 'failed\t%s\t%s\t%s\n' "$id" "$branch" "$worktree" >"$STATE_DIR/$id.status"
  return 1
}

root_cause_report() {
  local id=$1 worktree=$2 report="$STATE_DIR/root-cause-$id.md"
  local prompt="$STATE_DIR/prompt-$id-root-cause.txt"
  {
    echo "Perform a read-only root-cause analysis for failed work item $id."
    echo "Do not edit files. Inspect the current worktree, ticket, attempt summaries, and logs."
    echo "Write concise Markdown with: observed failure, most likely root cause, evidence,"
    echo "what was attempted, and a specific recommended route forward."
    echo
    cat "$ITEM_DIR/$id.md"
    echo
    for result in "$RESULT_DIR/$id-"*.json; do
      [[ -e "$result" ]] || continue
      echo "Result $(basename "$result"):"
      cat "$result"
    done
  } >"$prompt"
  docker run --rm \
    --network bridge --cpus "${WORKER_CPUS:-4}" --memory "${WORKER_MEMORY:-8g}" \
    --mount "type=bind,src=$worktree,dst=/workspace,readonly" \
    --mount "type=bind,src=$ROOT,dst=$ROOT,readonly" \
    --mount "type=bind,src=$STATE_DIR,dst=/orchestrator" \
    --mount "type=bind,src=$HOME/.codex/auth.json,dst=/codex-home/auth.json,readonly" \
    "$IMAGE" --dangerously-bypass-approvals-and-sandbox --ignore-user-config \
    --ephemeral --color never --output-last-message "/orchestrator/root-cause-$id.md" - <"$prompt" \
    >"$LOG_DIR/$id-root-cause.log" 2>&1 || true
  # The container cannot write the host report through a read-only worktree, so
  # fall back to a deterministic evidence summary if no model report is emitted.
  if [[ ! -s "$report" ]]; then
    {
      echo "# Root-cause report: $id"
      echo
      echo "Automated root-cause generation failed; inspect $LOG_DIR/$id-root-cause.log."
      echo "The implementation/recovery attempts were exhausted. Preserve $worktree,"
      echo "review the attempt logs, narrow the ticket if needed, and rerun its wave."
    } >"$report"
  fi
  echo "Root-cause report written to $report" >&2
}

integrate_item() {
  local id=$1 branch=$2 worktree=$3 attempt
  if git -C "$worktree" rebase "$BASE_BRANCH"; then
    git merge --ff-only "$branch"
    git worktree remove "$worktree"
    git branch -d "$branch" >/dev/null
    return 0
  fi

  # A merge conflict is also recoverable by a fresh agent, at most MAX_RECOVERY times.
  for ((attempt=1; attempt<=MAX_RECOVERY; attempt++)); do
    local prompt="$STATE_DIR/prompt-$id-merge-$attempt.txt"
    local log="$LOG_DIR/$id-merge-$attempt.jsonl"
    local result="$RESULT_DIR/$id-merge-$attempt.json"
    cat >"$prompt" <<EOF
Resolve the current rebase conflicts for work item $id. Preserve both the completed ticket and
all changes now present on $BASE_BRANCH. Inspect the conflict markers, edit the files to the
correct integrated result, and run focused tests. Do not run git commands; the host will continue
the rebase. Return status=completed only when every conflict is resolved in the file contents.
EOF
    if run_agent "$id" "merge-$attempt" "$worktree" "$prompt" "$log" "$result" \
      && [[ $(jq -r '.status // "blocked"' "$result") == completed ]] \
      && git -C "$worktree" diff --check \
      && ! git -C "$worktree" grep -nE '^(<<<<<<<|=======|>>>>>>>)' -- . ':!*.md' \
      && validate_worktree "$worktree" "$LOG_DIR/$id-merge-$attempt-validation.log"; then
      git -C "$worktree" add -A
      if GIT_EDITOR=true git -C "$worktree" rebase --continue; then
        git merge --ff-only "$branch"
        git worktree remove "$worktree"
        git branch -d "$branch" >/dev/null
        return 0
      fi
    fi
  done
  git -C "$worktree" rebase --abort >/dev/null 2>&1 || true
  root_cause_report "$id" "$worktree"
  return 1
}

run_wave() {
  local wave=$1 id title pid failed=0
  local -a batch_ids=() batch_titles=() batch_pids=()
  echo "Starting wave $wave"
  while IFS=$'\t' read -r id title; do
    batch_ids+=("$id"); batch_titles+=("$title")
    worker "$id" "$title" &
    batch_pids+=("$!")
    if ((${#batch_pids[@]} == MAX_PARALLEL)); then
      for pid in "${batch_pids[@]}"; do wait "$pid" || failed=1; done
      batch_pids=()
    fi
  done < <(items_for_wave "$wave")
  for pid in "${batch_pids[@]}"; do wait "$pid" || failed=1; done

  if ((failed)); then
    for id in "${batch_ids[@]}"; do
      read -r state _ _ worktree < <(tr '\t' ' ' <"$STATE_DIR/$id.status")
      [[ "$state" == failed ]] && root_cause_report "$id" "$worktree"
    done
    echo "Wave $wave failed; no later wave will start" >&2
    return 1
  fi

  for id in "${batch_ids[@]}"; do
    IFS=$'\t' read -r state _ branch worktree <"$STATE_DIR/$id.status"
    integrate_item "$id" "$branch" "$worktree" || return 1
  done
  echo "Wave $wave complete"
}

while IFS= read -r wave; do
  [[ -n "$ONLY_WAVE" && "$wave" != "$ONLY_WAVE" ]] && continue
  run_wave "$wave"
done < <(waves)

echo "All selected waves completed and merged into $BASE_BRANCH"
