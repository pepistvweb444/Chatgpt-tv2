#!/bin/sh
APP_ID="com.jarvis.harvesttv"
LOG="/tmp/harvest-voice.log"
DISABLE_FILE="/tmp/voiceinput_disable_set_input_event"

log(){ echo "$(date '+%H:%M:%S') $*" >>"$LOG"; }

suppress_lg(){
  touch "$DISABLE_FILE" 2>/dev/null
  if command -v systemctl >/dev/null 2>&1; then
    systemctl stop voiceconductor.service >/dev/null 2>&1 || true
  fi
}

launch_jarvis(){
  /usr/bin/luna-send-pub -n 1 -f luna://com.webos.applicationManager/launch     '{"id":"com.jarvis.harvesttv","params":{"jarvisVoice":true}}' >>"$LOG" 2>&1
}

pick_log(){
  if [ -f /tmp/lginput-hook-lginput2.log ]; then
    echo /tmp/lginput-hook-lginput2.log
    return
  fi
  if [ -f /var/log/messages ]; then
    echo /var/log/messages
    return
  fi
  if [ -f /var/log/lginput2.log ]; then
    echo /var/log/lginput2.log
    return
  fi
  echo /tmp/lginput-hook-lginput2.log
}

is_voice_line(){
  echo "$1" | grep -q 'keyid=17.*uinput_code=428' && return 0
  echo "$1" | grep -q 'KEY_VOICE' && return 0
  return 1
}

suppress_lg
SRC="$(pick_log)"
log "watcher source=$SRC"

last_lines=0
while true; do
  if [ -f "$SRC" ]; then
    lines=$(wc -l <"$SRC" 2>/dev/null)
    [ -z "$lines" ] && lines=0

    if [ "$lines" -lt "$last_lines" ]; then
      last_lines=0
    fi

    if [ "$lines" -gt "$last_lines" ]; then
      start=$((last_lines + 1))
      tail -n +"$start" "$SRC" 2>/dev/null | while IFS= read -r line; do
        if is_voice_line "$line"; then
          suppress_lg
          log "KEY_VOICE detected"
          launch_jarvis
          sleep 1
        fi
      done
      last_lines="$lines"
    fi
  fi
  sleep 1
done
