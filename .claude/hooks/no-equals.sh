#!/usr/bin/env bash
# PreToolUse(Bash): zsh expands an unquoted =-initial word (`echo ===`) to a
# command path, fails, and drops every command batched after it. Prefix
# `setopt noequals` only when such a word is present; merge into tool_input so
# timeout/description/run_in_background survive.
set -uo pipefail
payload=$(cat)
cmd=$(jq -r '.tool_input.command // empty' <<<"$payload" 2>/dev/null) || exit 0
[ -n "$cmd" ] || exit 0
printf '%s' "$cmd" | grep -qE '(^|[[:space:];|&(])=' || exit 0
jq -c '{hookSpecificOutput: {hookEventName: "PreToolUse",
  updatedInput: (.tool_input + {command: ("setopt noequals 2>/dev/null; " + .tool_input.command)})}}' <<<"$payload"
