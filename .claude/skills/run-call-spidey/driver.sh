#!/usr/bin/env bash
# Driver for the Call Spidey Android app (dev.nate.spideycall).
# Talks to whatever device/emulator `adb` currently sees. Run
# `adb devices -l` first if unsure which one that is.
#
# Usage: driver.sh <command> [args...]
set -euo pipefail

PKG="dev.nate.spideycall"
ACTIVITY=".CallActivity"
DUMP_REMOTE="/sdcard/run-call-spidey-dump.xml"
DUMP_LOCAL="$(mktemp -t call-spidey-dump-XXXXXX.xml)"
trap 'rm -f "$DUMP_LOCAL"' EXIT

usage() {
  cat <<'EOF'
Commands:
  build                  ./gradlew installDebug (build + install on the adb device)
  grant-perms            grant RECORD_AUDIO + POST_NOTIFICATIONS so no runtime
                          permission dialog blocks a headless run
  launch                 am start the app's one activity (CallActivity)
  stop                   am force-stop the app
  screenshot <path>      save a PNG of the current screen to <path>
  dump                   print the raw uiautomator XML hierarchy (debugging)
  screen-text            print every visible text label on screen, one per line
  tap-text "<text>"      tap the element whose text matches (last match wins -
                          use this for ambiguous labels that also appear as a
                          non-interactive header, see Gotchas in SKILL.md)
  wait-text "<text>" [s] poll (default 30s) until <text> appears on screen
  call                   tap "Call Spidey" to start a call
  hangup                 tap "Hang up" to end the call
  logs [n]               last n (default 200) logcat lines for this app's pid
EOF
}

dump_ui() {
  adb shell uiautomator dump "$DUMP_REMOTE" >/dev/null
  adb shell cat "$DUMP_REMOTE" > "$DUMP_LOCAL"
}

# Prints "x y" center for the LAST node whose text= exactly matches $1.
# Last-match-wins because this app repeats "Call Spidey" as a static
# header above the actual button - the button is always the later node
# in document order. See SKILL.md Gotchas before changing this.
find_center() {
  local text="$1"
  dump_ui
  grep -o "text=\"${text}\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" "$DUMP_LOCAL" \
    | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' \
    | tail -n1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk '{printf "%d %d\n", ($1+$3)/2, ($2+$4)/2}'
}

cmd_tap_text() {
  local text="$1"
  local center
  center="$(find_center "$text")"
  if [ -z "$center" ]; then
    echo "tap-text: no element with text \"$text\" found on screen" >&2
    echo "--- current screen-text ---" >&2
    cmd_screen_text >&2
    exit 1
  fi
  adb shell input tap $center
}

cmd_screen_text() {
  dump_ui
  grep -o 'text="[^"]*"' "$DUMP_LOCAL" | sed -E 's/^text="(.*)"$/\1/' | grep -v '^$'
}

cmd_wait_text() {
  local text="$1"
  local timeout="${2:-30}"
  local waited=0
  while ! cmd_screen_text | grep -qF "$text"; do
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge "$timeout" ]; then
      echo "wait-text: \"$text\" did not appear within ${timeout}s" >&2
      echo "--- current screen-text ---" >&2
      cmd_screen_text >&2
      exit 1
    fi
  done
}

case "${1:-}" in
  build)
    ./gradlew installDebug
    ;;
  grant-perms)
    adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
    adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
    ;;
  launch)
    adb shell am start -n "$PKG/$ACTIVITY"
    ;;
  stop)
    adb shell am force-stop "$PKG"
    ;;
  screenshot)
    [ -n "${2:-}" ] || { echo "usage: driver.sh screenshot <path>" >&2; exit 1; }
    adb exec-out screencap -p > "$2"
    ;;
  dump)
    dump_ui
    cat "$DUMP_LOCAL"
    ;;
  screen-text)
    cmd_screen_text
    ;;
  tap-text)
    [ -n "${2:-}" ] || { echo "usage: driver.sh tap-text \"<text>\"" >&2; exit 1; }
    cmd_tap_text "$2"
    ;;
  wait-text)
    [ -n "${2:-}" ] || { echo "usage: driver.sh wait-text \"<text>\" [timeout-s]" >&2; exit 1; }
    cmd_wait_text "$2" "${3:-30}"
    ;;
  call)
    cmd_tap_text "Call Spidey"
    ;;
  hangup)
    cmd_tap_text "Hang up"
    ;;
  logs)
    pid="$(adb shell pidof "$PKG" || true)"
    n="${2:-200}"
    if [ -n "$pid" ]; then
      adb logcat -d -t "$n" --pid "$pid"
    else
      adb logcat -d -t "$n" | grep -F "$PKG"
    fi
    ;;
  *)
    usage
    exit 1
    ;;
esac
