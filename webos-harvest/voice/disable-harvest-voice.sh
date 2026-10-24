#!/bin/sh
APPDIR=/media/developer/apps/usr/palm/applications/com.jarvis.harvesttv
RUN="$APPDIR/voice/harvest-voice-run.sh"
rm -f /var/lib/webosbrew/init.d/45-harvest-voice
[ -f "$RUN" ] && sh "$RUN" stop
rm -f /tmp/voiceinput_disable_set_input_event
systemctl start voiceconductor.service >/dev/null 2>&1 || true
echo disabled
