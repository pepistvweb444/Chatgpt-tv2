#!/bin/sh
APPDIR=/media/developer/apps/usr/palm/applications/com.jarvis.harvesttv
RUN="$APPDIR/voice/harvest-voice-run.sh"
INIT=/var/lib/webosbrew/init.d/45-harvest-voice
if ! command -v python3 >/dev/null 2>&1; then echo missing_python; exit 1; fi
mkdir -p /var/lib/webosbrew/init.d
cat >"$INIT" <<EOF
#!/bin/sh
[ -f "$RUN" ] && sh "$RUN" start >/dev/null 2>&1 </dev/null
exit 0
EOF
chmod 755 "$INIT" "$RUN"
sh "$RUN" start
