#!/bin/sh
exec /usr/bin/luna-send-pub -n 1 -f luna://com.webos.applicationManager/launch '{"id":"com.jarvis.harvesttv","params":{"jarvisVoice":true}}'
