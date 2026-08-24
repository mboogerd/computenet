#!/bin/sh
# have-tool.sh <tool> — exit 0 iff THIS machine can actually use <tool>.
# command -v proves a binary is on PATH; a daemon/auth-backed tool needs a
# live probe or the needs: label silently passes on a machine that cannot
# run it (computenet-3k1l: docker binary present, daemon absent).
t="$1"
command -v "$t" >/dev/null 2>&1 || exit 1
case "$t" in
  docker)  docker info >/dev/null 2>&1 ;;
  gh)      gh auth status >/dev/null 2>&1 ;;
  *)       exit 0 ;;
esac
