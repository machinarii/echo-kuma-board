#!/bin/sh
# Installs the Kuma Board usage collector on this Mac. Safe to re-run.
#
#  1. Copies kuma_usage.py to ~/.local/bin/kuma-usage.
#  2. Installs two LaunchAgents: dev.kumaboard.usage runs `kuma-usage collect` every 2 minutes;
#     dev.kumaboard.usage-serve answers GET /usage.json on 127.0.0.1:31995, and nothing else.
#  3. Proxies that one file onto the tailnet with `tailscale serve` (tailnet only, never Funnel; no sudo):
#       https://<this machine>.ts.net/kuma-usage/usage.json
#
# Uses no credentials. The Claude Code status line change is a separate, manual step (see README.md).
# Undo: tools/ai-usage/install.sh --uninstall
set -eu

LABEL=dev.kumaboard.usage
SERVE_LABEL=dev.kumaboard.usage-serve
PORT=31995
BIN="$HOME/.local/bin/kuma-usage"
PUBLIC="$HOME/.local/share/kuma-board-usage/public"
AGENTS="$HOME/Library/LaunchAgents"
SERVE_PATH=/kuma-usage
DOMAIN="gui/$(id -u)"
LOG="$HOME/Library/Logs/kuma-usage.log"

if [ "${1:-}" = "--uninstall" ]; then
  for l in "$LABEL" "$SERVE_LABEL"; do
    launchctl bootout "$DOMAIN/$l" 2>/dev/null || true
    rm -f "$AGENTS/$l.plist"
  done
  rm -f "$BIN"
  tailscale serve --set-path="$SERVE_PATH" off 2>/dev/null || true
  echo "removed (saved numbers in ~/.local/state and ~/.local/share/kuma-board-usage are left)"
  exit 0
fi

here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$(dirname "$BIN")" "$PUBLIC" "$AGENTS"
install -m 0755 "$here/kuma_usage.py" "$BIN"

# agent LABEL COMMAND EXTRA_KEYS
agent() {
  cat > "$AGENTS/$1.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$1</string>
  <key>ProgramArguments</key>
  <array><string>/usr/bin/python3</string><string>-I</string><string>$BIN</string><string>$2</string></array>
  <key>EnvironmentVariables</key><dict><key>KUMA_USAGE_PORT</key><string>$PORT</string></dict>
  <key>RunAtLoad</key><true/>
  $3
  <key>Nice</key><integer>10</integer>
  <key>StandardOutPath</key><string>/dev/null</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
PLIST
  launchctl bootout "$DOMAIN/$1" 2>/dev/null || true
  launchctl bootstrap "$DOMAIN" "$AGENTS/$1.plist"
}

agent "$LABEL" collect "<key>StartInterval</key><integer>120</integer>"
agent "$SERVE_LABEL" serve "<key>KeepAlive</key><true/>"
/usr/bin/python3 -I "$BIN" collect

tailscale serve --bg --set-path="$SERVE_PATH" "http://127.0.0.1:$PORT"
host=$(tailscale status --self --json | /usr/bin/python3 -I -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
echo "usage file: https://$host$SERVE_PATH/usage.json (tailnet only)"
