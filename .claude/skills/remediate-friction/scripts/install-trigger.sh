#!/usr/bin/env bash
# Installs the reactive SDLC trigger on THIS machine: a launchd user agent
# that runs gate.sh whenever .beads/issues.jsonl changes (plus hourly as a
# cross-machine fallback). Run it once per machine, from a shell where
# BEADS_ACTOR, claude, bd, and jq are all available — their locations and
# the actor name are baked into the rendered plist. Re-run to update.
#
# Uninstall: launchctl bootout "gui/$(id -u)" ~/Library/LaunchAgents/com.computenet.sdlc-orchestrator.plist
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GATE="$REPO/.claude/skills/remediate-friction/scripts/gate.sh"
LABEL="com.computenet.sdlc-orchestrator"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

: "${BEADS_ACTOR:?BEADS_ACTOR must be set in this shell so it can be baked into the plist}"
for bin in claude bd jq; do command -v "$bin" >/dev/null || { echo "ERROR: $bin not on PATH" >&2; exit 1; }; done
[ -x "$GATE" ] || { echo "ERROR: $GATE not executable" >&2; exit 1; }

PATH_BAKED="$(dirname "$(command -v claude)"):$(dirname "$(command -v bd)"):$(dirname "$(command -v jq)"):/usr/bin:/bin"

mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$GATE</string>
  </array>
  <key>WatchPaths</key>
  <array><string>$REPO/.beads/issues.jsonl</string></array>
  <key>StartInterval</key><integer>3600</integer>
  <key>ThrottleInterval</key><integer>60</integer>
  <key>RunAtLoad</key><false/>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$PATH_BAKED</string>
    <key>BEADS_ACTOR</key><string>$BEADS_ACTOR</string>
  </dict>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/sdlc-orchestrator.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/sdlc-orchestrator.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Installed $LABEL"
echo "  watches:  $REPO/.beads/issues.jsonl (+ hourly fallback, 60s throttle)"
echo "  actor:    $BEADS_ACTOR"
echo "  log:      $HOME/Library/Logs/sdlc-orchestrator.log"
echo "First firing may need a macOS Files-and-Folders approval for bash to read the repo; watch the log."
