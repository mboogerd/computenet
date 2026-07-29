#!/bin/bash
# Launches two :demo:shopping peers over the real :wire transport, each with
# its own :inspect backend and its own inspector UI dev server, so both
# graphs can be watched side by side while an item added on one peer
# converges onto the other. See doc/demo-shopping-inspector.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HTTP_A=18191
HTTP_B=18192
WS=19201
INSPECT_A=17091
INSPECT_B=17092
UI_A=5191
UI_B=5192

echo "==> building demo/shopping"
./gradlew :demo:shopping:installDist --console=plain -q

echo "==> inspect/ui dependencies"
if [ ! -d "$ROOT/inspect/ui/node_modules" ]; then
  (cd "$ROOT/inspect/ui" && npm ci)
fi

PIDS=()
cleanup() {
  echo "==> shutting down"
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> peer A (listener): http :$HTTP_A, ws :$WS, inspector :$INSPECT_A"
"$ROOT/demo/shopping/build/install/shopping/bin/shopping" "$HTTP_A" \
  --listen "$WS" --inspect-port "$INSPECT_A" --net-name jvm-a &
PIDS+=($!)

echo "==> peer B (dialer): http :$HTTP_B, peers ws://localhost:$WS, inspector :$INSPECT_B"
"$ROOT/demo/shopping/build/install/shopping/bin/shopping" "$HTTP_B" \
  --peer "ws://localhost:$WS" --inspect-port "$INSPECT_B" --net-name jvm-b &
PIDS+=($!)

wait_for() {
  local url="$1" name="$2"
  for _ in $(seq 1 60); do
    curl -sf "$url" >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  echo "!! $name never responded at $url" >&2
  exit 1
}

echo "==> waiting for both inspectors"
wait_for "http://localhost:$INSPECT_A/api/inspect/topology" "inspector A"
wait_for "http://localhost:$INSPECT_B/api/inspect/topology" "inspector B"

echo "==> starting inspector UI dev servers"
VITE_BIN="$ROOT/inspect/ui/node_modules/.bin/vite"
(cd "$ROOT/inspect/ui" && INSPECT_BACKEND="http://localhost:$INSPECT_A" exec "$VITE_BIN" --port "$UI_A" --strictPort) &
PIDS+=($!)
(cd "$ROOT/inspect/ui" && INSPECT_BACKEND="http://localhost:$INSPECT_B" exec "$VITE_BIN" --port "$UI_B" --strictPort) &
PIDS+=($!)

wait_for "http://localhost:$UI_A/" "inspector UI A"
wait_for "http://localhost:$UI_B/" "inspector UI B"

cat <<EOF

==> ready — open these two inspector UIs side by side:
    peer A inspector : http://localhost:$UI_A
    peer B inspector : http://localhost:$UI_B

    shopping app (peer A) : http://localhost:$HTTP_A
    shopping app (peer B) : http://localhost:$HTTP_B

Enter the "shopping" graph in both inspector tabs, select the "items" cell in
each, then add an item at http://localhost:$HTTP_A and watch both update.

Press Ctrl+C to stop everything.
EOF

wait
