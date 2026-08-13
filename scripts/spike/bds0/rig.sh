#!/usr/bin/env bash
# rig.sh — synthetic two-workspace bd rig for the BDS0 spike (computenet-8kj.1).
#
# Isolation rules (non-negotiable):
#   - Every bd invocation targets a workspace under a throwaway `mktemp -d`
#     root, using `bd -C <workspace> --sandbox`.
#   - This script never invokes bd with cwd inside the repository checkout,
#     and never reads or writes the repository's own .beads directory.
#
# Subcommands:
#   rig.sh init      Create a fresh rig root with two seeded workspaces, A
#                     and B, and print `export BDS0_RIG_ROOT=<root>`.
#   rig.sh hop <FROM> <TO> --dot <value> [--allow-stale] [id...]
#                     Replicate one delta from workspace <FROM> into
#                     workspace <TO>: export from <FROM>, stamp
#                     metadata.cn_dot=<value> on the selected rows
#                     (preserving other metadata keys), and import into
#                     <TO>. With no ids, every exported row is selected;
#                     with ids, only rows whose .id matches are selected.
#                     Prints bd import's own report unmodified and exits
#                     nonzero if bd import fails. Also writes the stamped
#                     delta JSONL to $BDS0_RIG_ROOT/last-hop.jsonl.
#   rig.sh journal <WS> <issue-id>
#                     Print workspace <WS>'s journal (Dolt `events` table)
#                     rows for <issue-id>, as JSON, ordered by created_at.
#                     bd 1.1.2 has no `bd events` command and `bd sql` is
#                     not supported in embedded mode, so this reads the
#                     workspace's embedded Dolt database directly with the
#                     `dolt` CLI. Fails with a clear message if `dolt` is
#                     not installed or the embedded Dolt directory is
#                     missing.
#   rig.sh smoke      Self-contained end-to-end exercise of the whole rig:
#                     init, one hop A->B of a seeded issue, then journal
#                     that issue in B. Asserts the issue landed in B and
#                     that its journal has at least one event. Exits
#                     nonzero on any failure.
set -euo pipefail

# bd_ws <root> <name> -> path of workspace <name> under rig root <root>.
bd_ws() {
  local root="$1" name="$2"
  printf '%s/%s' "$root" "$name"
}

# bd_ws_run <workspace> [bd args...] -> run bd against one synthetic
# workspace only. Always uses -C and --sandbox so the invocation can never
# touch the ambient cwd or push out of the sandbox.
bd_ws_run() {
  local ws="$1"
  shift
  bd -C "$ws" --sandbox "$@"
}

# init_workspace <workspace> <prefix> -> bd init a fresh workspace, then
# seed it with three issues covering the required shapes: a plain task, a
# task carrying provenance metadata, and a task with a label plus a comment.
init_workspace() {
  local ws="$1" prefix="$2"
  mkdir -p "$ws"
  (
    cd "$ws"
    BD_NON_INTERACTIVE=1 bd init --prefix "$prefix" --skip-agents --skip-hooks >/dev/null
  )

  bd_ws_run "$ws" create "Plain seeded task in $prefix" --type task --silent >/dev/null

  bd_ws_run "$ws" create "Seeded task with provenance metadata in $prefix" \
    --type task --metadata '{"cn_dot":"seed:0"}' --silent >/dev/null

  local labeled_id
  labeled_id=$(bd_ws_run "$ws" create "Seeded task with label and comment in $prefix" \
    --type task --labels seed-demo --silent)
  bd_ws_run "$ws" comment "$labeled_id" "seed comment for $prefix rig bootstrap" >/dev/null
}

cmd_init() {
  local root
  root=$(mktemp -d)

  local ws_a ws_b
  ws_a=$(bd_ws "$root" A)
  ws_b=$(bd_ws "$root" B)

  init_workspace "$ws_a" bdsa
  init_workspace "$ws_b" bdsb

  # Record the bd version via a sandboxed workspace invocation, not a bare
  # `bd version` — per the isolation rules, every bd invocation must use
  # `-C <workspace> --sandbox` and never run with cwd (or an implicit
  # config lookup) inside the repository checkout.
  bd_ws_run "$ws_a" version >"$root/bd-version.txt"

  echo "export BDS0_RIG_ROOT=$root"
}

# cmd_hop <FROM> <TO> --dot <value> [--allow-stale] [id...] -> replicate one
# delta from workspace <FROM> into workspace <TO>. Resolves both workspaces
# under $BDS0_RIG_ROOT, so every bd invocation here stays sandboxed to a
# synthetic workspace per the isolation rules above.
cmd_hop() {
  local from="${1:-}" to="${2:-}"
  if [[ -z "$from" || -z "$to" ]]; then
    echo "usage: $0 hop <FROM> <TO> --dot <value> [--allow-stale] [id...]" >&2
    exit 1
  fi
  shift 2

  if [[ -z "${BDS0_RIG_ROOT:-}" ]]; then
    echo "error: BDS0_RIG_ROOT is not set (run 'rig.sh init' and source its output)" >&2
    exit 1
  fi

  local dot=""
  local allow_stale=0
  local -a ids=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dot)
        dot="${2:-}"
        shift 2
        ;;
      --allow-stale)
        allow_stale=1
        shift
        ;;
      *)
        ids+=("$1")
        shift
        ;;
    esac
  done

  if [[ -z "$dot" ]]; then
    echo "usage: $0 hop <FROM> <TO> --dot <value> [--allow-stale] [id...]" >&2
    exit 1
  fi

  local ws_from ws_to
  ws_from=$(bd_ws "$BDS0_RIG_ROOT" "$from")
  ws_to=$(bd_ws "$BDS0_RIG_ROOT" "$to")

  # jq filter: select rows by id when ids were given (pass all otherwise),
  # then stamp metadata.cn_dot on each selected row, preserving every other
  # metadata key already present on it.
  local jq_filter
  if [[ ${#ids[@]} -gt 0 ]]; then
    local ids_json
    ids_json=$(printf '%s\n' "${ids[@]}" | jq -R . | jq -s .)
    jq_filter='select(.id as $i | $ids | index($i)) | .metadata = ((.metadata // {}) + {cn_dot: $dot})'
  else
    jq_filter='.metadata = ((.metadata // {}) + {cn_dot: $dot})'
    ids_json='[]'
  fi

  local delta_file="$BDS0_RIG_ROOT/last-hop.jsonl"
  bd_ws_run "$ws_from" export \
    | jq -c --argjson ids "$ids_json" --arg dot "$dot" "$jq_filter" \
    > "$delta_file"

  local -a import_args=(import - --json)
  if [[ $allow_stale -eq 1 ]]; then
    import_args+=(--allow-stale)
  fi

  bd_ws_run "$ws_to" "${import_args[@]}" < "$delta_file"
}

# cmd_journal <WS> <issue-id> -> print the journal (Dolt `events` table)
# rows for <issue-id> in workspace <WS>, as JSON, ordered by created_at.
# Locates the embedded Dolt directory by globbing
# <ws>/.beads/embeddeddolt/*/ rather than hardcoding the workspace's issue
# prefix, since that prefix varies per workspace (bdsa, bdsb, ...).
cmd_journal() {
  local ws="${1:-}" issue_id="${2:-}"
  if [[ -z "$ws" || -z "$issue_id" ]]; then
    echo "usage: $0 journal <WS> <issue-id>" >&2
    exit 1
  fi

  if ! command -v dolt >/dev/null 2>&1; then
    echo "error: 'dolt' is not installed; the journal is read directly from" \
      "the workspace's embedded Dolt database (bd 1.1.2 has no 'bd events'" \
      "command and 'bd sql' fails in embedded mode)" >&2
    exit 1
  fi

  local -a db_dirs=("$ws"/.beads/embeddeddolt/*/)
  if [[ ! -d "${db_dirs[0]}" ]]; then
    echo "error: no embedded Dolt database found under $ws/.beads/embeddeddolt" >&2
    exit 1
  fi
  if [[ ${#db_dirs[@]} -gt 1 ]]; then
    echo "error: expected exactly one embedded Dolt database under" \
      "$ws/.beads/embeddeddolt, found ${#db_dirs[@]}" >&2
    exit 1
  fi

  (
    cd "${db_dirs[0]}"
    dolt sql -r json -q \
      "select * from events where issue_id='$issue_id' order by created_at"
  )
}

# cmd_smoke -> self-contained end-to-end exercise of the whole rig: init,
# one hop A->B of a seeded issue, then journal that issue in B. Asserts the
# issue landed in B and that its journal has at least one event row.
cmd_smoke() {
  echo "== rig.sh smoke: init ==" >&2
  local init_output
  init_output=$(cmd_init)
  echo "$init_output" >&2
  eval "$init_output"
  export BDS0_RIG_ROOT

  local ws_a ws_b
  ws_a=$(bd_ws "$BDS0_RIG_ROOT" A)
  ws_b=$(bd_ws "$BDS0_RIG_ROOT" B)

  # Pick the plain seeded task in A as the smoke's hop subject: it is the
  # simplest seed shape (no pre-existing provenance metadata to confuse
  # the assertion that hop stamped metadata.cn_dot).
  local seed_id
  seed_id=$(bd_ws_run "$ws_a" list --json \
    | jq -r '[.[] | select(.title == "Plain seeded task in bdsa")][0].id')
  if [[ -z "$seed_id" || "$seed_id" == "null" ]]; then
    echo "smoke FAILED: could not find seeded issue in workspace A" >&2
    exit 1
  fi
  echo "== rig.sh smoke: hop A B --dot 'A:1' $seed_id ==" >&2
  cmd_hop A B --dot 'A:1' "$seed_id" >&2

  echo "== rig.sh smoke: verify $seed_id exists in B ==" >&2
  if ! bd_ws_run "$ws_b" show "$seed_id" --json >/dev/null; then
    echo "smoke FAILED: $seed_id does not exist in workspace B after hop" >&2
    exit 1
  fi

  echo "== rig.sh smoke: journal B $seed_id ==" >&2
  local journal_output
  journal_output=$(cmd_journal "$ws_b" "$seed_id")
  echo "$journal_output"

  local event_count
  event_count=$(echo "$journal_output" | jq '(.rows // []) | length')
  if [[ "$event_count" -lt 1 ]]; then
    echo "smoke FAILED: expected at least one journal event for $seed_id in B, got $event_count" >&2
    exit 1
  fi

  echo "== rig.sh smoke: OK ($event_count journal event(s) for $seed_id in B) ==" >&2
}

main() {
  local sub="${1:-}"
  case "$sub" in
    init)
      cmd_init
      ;;
    hop)
      shift
      cmd_hop "$@"
      ;;
    journal)
      shift
      cmd_journal "$@"
      ;;
    smoke)
      cmd_smoke
      ;;
    *)
      echo "usage: $0 {init|hop|journal|smoke}" >&2
      exit 1
      ;;
  esac
}

main "$@"
