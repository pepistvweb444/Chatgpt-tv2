#!/bin/sh
APP_ID="com.jarvis.harvesttv"
LOG="/tmp/harvest-voice.log"
DISABLE_FILE="/tmp/voiceinput_disable_set_input_event"

log(){ echo "$(date '+%H:%M:%S') $*" >>"$LOG"; }

suppress_lg(){
  touch "$DISABLE_FILE" 2>/dev/null
  systemctl stop voiceconductor.service >/dev/null 2>&1 || true
}

launch_jarvis(){
  /usr/bin/luna-send-pub -n 1 -f luna://com.webos.applicationManager/launch     '{"id":"com.jarvis.harvesttv","params":{"jarvisVoice":true}}' >>"$LOG" 2>&1
}

pick_log(){
  [ -f /var/log/messages ] && { echo /var/log/messages; return; }
  [ -f /var/log/lginput2.log ] && { echo /var/log/lginput2.log; return; }
  echo /tmp/lginput-hook-lginput2.log
}

suppress_lg
SRC="$(pick_log)"
log "Harvest Voice shell watcher using $SRC"

while true; do
  tail -F -n 0 "$SRC" 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      *'button_type":"KEY_VOICE"'*|*"button_type": "KEY_VOICE"*|*"keyid=17"*'uinput_code=428'*)
        suppress_lg
        log "KEY_VOICE detected"
        launch_jarvis
        sleep 1
        ;;
    esac
  done
  sleep 1
done
