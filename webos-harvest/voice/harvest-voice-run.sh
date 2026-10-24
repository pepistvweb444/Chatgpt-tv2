#!/bin/sh
APPDIR=/media/developer/apps/usr/palm/applications/com.jarvis.harvesttv
WATCHER="$APPDIR/voice/harvest_voice_watch.sh"
PID=/tmp/harvest-voice.pid
LOG=/tmp/harvest-voice.log

alive(){
  p=$(cat "$PID" 2>/dev/null)
  [ -n "$p" ] && kill -0 "$p" 2>/dev/null
}

case "$1" in
 start)
   if alive; then echo "running pid=$(cat "$PID")"; exit 0; fi
   rm -f "$PID"
   touch /tmp/voiceinput_disable_set_input_event
   command -v systemctl >/dev/null 2>&1 && systemctl stop voiceconductor.service >/dev/null 2>&1 || true
   chmod 755 "$WATCHER" 2>/dev/null
   if command -v setsid >/dev/null 2>&1; then
     setsid sh "$WATCHER" >>"$LOG" 2>&1 </dev/null &
   elif command -v start-stop-daemon >/dev/null 2>&1; then
     start-stop-daemon -S -b -m -p "$PID" -x /bin/sh -- "$WATCHER" >>"$LOG" 2>&1
     sleep 1
     alive && { echo "started pid=$(cat "$PID")"; exit 0; }
   else
     sh "$WATCHER" >>"$LOG" 2>&1 </dev/null &
   fi
   pid=$!
   [ -n "$pid" ] && echo "$pid" >"$PID"
   sleep 2
   if alive; then
     echo "started pid=$(cat "$PID")"
   else
     echo "start_failed"
     tail -20 "$LOG" 2>/dev/null
   fi
   ;;
 stop)
   p=$(cat "$PID" 2>/dev/null)
   [ -n "$p" ] && kill "$p" 2>/dev/null || true
   rm -f "$PID" /tmp/voiceinput_disable_set_input_event
   command -v systemctl >/dev/null 2>&1 && systemctl start voiceconductor.service >/dev/null 2>&1 || true
   echo stopped
   ;;
 status)
   if alive; then echo "running pid=$(cat "$PID")"; else echo stopped; fi
   ;;
 *) echo "usage: $0 start|stop|status"; exit 2;;
esac
