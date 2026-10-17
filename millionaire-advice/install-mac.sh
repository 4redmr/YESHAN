#!/bin/bash
# 매일 오전 7시 + 로그인할 때마다 억만장자의 아침 서신을 엽니다.
# 실행: 터미널에서  bash install-mac.sh   (제거: bash install-mac.sh --uninstall)
set -euo pipefail

LABEL="com.yeshan.millionaire-advice"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DIR="$(cd "$(dirname "$0")" && pwd)"
HTML="$DIR/index.html"

if [[ "${1:-}" == "--uninstall" ]]; then
  launchctl unload "$PLIST" 2>/dev/null || true
  rm -f "$PLIST"
  echo "제거했습니다."
  exit 0
fi

[[ -f "$HTML" ]] || { echo "index.html을 찾을 수 없습니다: $HTML"; exit 1; }
mkdir -p "$HOME/Library/LaunchAgents"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/open</string>
    <string>$HTML</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Hour</key><integer>7</integer>
    <key>Minute</key><integer>0</integer>
  </dict>
</dict>
</plist>
EOF

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "설치 완료: 매일 07:00 + 로그인 시 자동으로 열립니다."
