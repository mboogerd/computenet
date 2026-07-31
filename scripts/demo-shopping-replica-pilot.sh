#!/bin/bash
# V4-PILOT — launches two :demo:shopping peers in `--replicate` mode over the
# real :wire transport, each with its own :inspect backend and its own inspector
# UI dev server. Unlike scripts/demo-shopping-two-inspectors.sh (which shows the
# M5 role-distinct union chain: two DIFFERENT logical cells streaming into each
# other), this one puts ONE logical cell in two places — a genuine same-logical-id
# replica pair gossiping across the socket. See doc/demo-shopping-replica-pilot.md.
#
# Ports are deliberately disjoint from scripts/demo-shopping-two-inspectors.sh
# and from inspect/ui/README.md's recipe, so all three can run at once.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HTTP_A=18291
HTTP_B=18292
WS=19301
INSPECT_A=17191
INSPECT_B=17192
UI_A=5291
UI_B=5292

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

echo "==> peer A (listener, replica instance 0): http :$HTTP_A, ws :$WS, inspector :$INSPECT_A"
"$ROOT/demo/shopping/build/install/shopping/bin/shopping" "$HTTP_A" \
  --listen "$WS" --replicate --inspect-port "$INSPECT_A" --net-name jvm-a &
PIDS+=($!)

echo "==> peer B (dialer, replica instance 1): http :$HTTP_B, peers ws://localhost:$WS, inspector :$INSPECT_B"
"$ROOT/demo/shopping/build/install/shopping/bin/shopping" "$HTTP_B" \
  --peer "ws://localhost:$WS" --replicate --inspect-port "$INSPECT_B" --net-name jvm-b &
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

Enter the "shopping" graph in both inspector tabs and find the "shared" cell —
it is one LOGICAL cell with two instances, so each side also shows the peer's
instance as "shared@listener" / "shared@dialer", plus the two delivered-watermark
companions the replica mesh brings with it (four extra nodes per side).

Write onto the replica (not the ordinary shopping list):

    curl -s -X POST http://localhost:$HTTP_A/op -d 'user=alice&action=share&item=flour'
    curl -s http://localhost:$HTTP_B/events | head -1     # "shared":["flour"]

    curl -s -X POST http://localhost:$HTTP_B/op -d 'user=bob&action=share&item=yeast'
    curl -s http://localhost:$HTTP_A/events | head -1     # "shared":["flour","yeast"]

Shared items also appear in each side's ordinary items list — the replica is
linked into the "shopping" component on purpose, so it is visible on the canvas.

Press Ctrl+C to stop everything.
EOF

wait
