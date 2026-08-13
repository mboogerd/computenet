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
    *)
      echo "usage: $0 {init|hop}" >&2
      exit 1
      ;;
  esac
}

main "$@"
