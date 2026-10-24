#!/bin/sh
APPDIR=/media/developer/apps/usr/palm/applications/com.jarvis.harvesttv
WATCHER="$APPDIR/voice/harvest_voice_watch.sh"
PID=/tmp/harvest-voice.pid
LOG=/tmp/harvest-voice.log

case "$1" in
 start)
   p=$(cat "$PID" 2>/dev/null)
   if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then echo "running pid=$p"; exit 0; fi
   touch /tmp/voiceinput_disable_set_input_event
   systemctl stop voiceconductor.service >/dev/null 2>&1 || true
   nohup sh "$WATCHER" >>"$LOG" 2>&1 </dev/null &
   echo $! >"$PID"
   sleep 1
   p=$(cat "$PID" 2>/dev/null)
   if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then echo "started pid=$p"; else echo start_failed; fi
   ;;
 stop)
   p=$(cat "$PID" 2>/dev/null)
   [ -n "$p" ] && kill "$p" 2>/dev/null || true
   rm -f "$PID" /tmp/voiceinput_disable_set_input_event
   systemctl start voiceconductor.service >/dev/null 2>&1 || true
   echo stopped
   ;;
 status)
   p=$(cat "$PID" 2>/dev/null)
   if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then echo "running pid=$p"; else echo stopped; fi
   ;;
 *) echo "usage: $0 start|stop|status"; exit 2;;
esac
