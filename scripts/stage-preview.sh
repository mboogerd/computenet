#!/bin/sh
# Stage demo-app distributions outside ~/Documents so the IDE preview runner
# (TCC-sandboxed: no Documents access) can launch them with plain `java`.
# Re-run after code changes; .claude/launch.json points at the staged copies.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="${HOME}/.cache/computenet-preview"

"$ROOT/gradlew" -p "$ROOT" :demo:agora:installDist :demo:shopping:installDist \
  :demo:slotfinder:installDist :demo:skillmatch:installDist :demo:tiering:installDist --console=plain -q

mkdir -p "$STAGE"
for app in agora shopping slotfinder skillmatch tiering; do
  rm -rf "$STAGE/$app"
  cp -R "$ROOT/demo/$app/build/install/$app" "$STAGE/$app"
done
echo "staged: $STAGE/{agora,shopping,slotfinder,skillmatch,tiering}"
