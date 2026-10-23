#!/bin/sh
# Harvest TV voice button target.
# Use this command in LG Input Hook's "Execute" action.
# It does not alter system partitions.
exec /usr/bin/luna-send-pub -n 1 -f luna://com.webos.applicationManager/launch '{"id":"com.jarvis.harvesttv"}'
