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

main() {
  local sub="${1:-}"
  case "$sub" in
    init)
      cmd_init
      ;;
    *)
      echo "usage: $0 init" >&2
      exit 1
      ;;
  esac
}

main "$@"
