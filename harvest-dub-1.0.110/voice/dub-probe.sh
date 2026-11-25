#!/bin/sh
# Harvest TV 1.0.110 «Doblaje» – audio capture finder. READ-ONLY except 5 s test recordings in /tmp/dubprobe/.
# Run as root on the TV WHILE something with sound is playing (best: Netflix or Prime; then repeat with TV en directo):
#   sh /media/developer/apps/usr/palm/applications/com.jarvis.harvesttv/voice/dub-probe.sh
# or from the phone (Termux):  ssh root@192.168.0.150 'sh -s' < dub-probe.sh
# Prints the ALSA/PulseAudio devices and records 5 s from each candidate, with its level. A candidate with
# level > 0.002 while Netflix plays is the capture source to put in Doblaje › Captura. Report saved to /tmp/dubprobe/report.txt.
D=/tmp/dubprobe; mkdir -p $D; rm -f $D/*.wav $D/*.raw
NODE=$(command -v node 2>/dev/null)
lvl(){ # $1 raw file s16le, $2 channels -> "rms peak"
  [ -s "$1" ] || { echo "0 0 (vacío)"; return; }
  [ -n "$NODE" ] || { echo "? ? ($(wc -c < "$1") B, sin node)"; return; }
  $NODE -e 'var b=require("fs").readFileSync(process.argv[1]),n=b.length>>1,s=0,p=0;for(var i=0;i<n;i++){var v=Math.abs(b.readInt16LE(i*2))/32768;s+=v*v;if(v>p)p=v;}console.log((n?Math.sqrt(s/n):0).toFixed(4)+" "+p.toFixed(3)+" ("+b.length+" B)")' "$1"; }
towav(){ # $1 raw $2 rate $3 ch -> $1.wav (for listening)
  [ -n "$NODE" ] && [ -s "$1" ] && $NODE -e 'var fs=require("fs"),b=fs.readFileSync(process.argv[1]),r=+process.argv[2],c=+process.argv[3],h=Buffer.alloc(44);h.write("RIFF",0);h.writeUInt32LE(36+b.length,4);h.write("WAVE",8);h.write("fmt ",12);h.writeUInt32LE(16,16);h.writeUInt16LE(1,20);h.writeUInt16LE(c,22);h.writeUInt32LE(r,24);h.writeUInt32LE(r*2*c,28);h.writeUInt16LE(2*c,32);h.writeUInt16LE(16,34);h.write("data",36);h.writeUInt32LE(b.length,40);fs.writeFileSync(process.argv[1].replace(/\.raw$/,".wav"),Buffer.concat([h,b]));' "$1" "$2" "$3" && rm -f "$1"; }
rec5(){ # name, command...
  name=$1; shift; f=$D/$name.raw
  ( "$@" > $f 2>$D/$name.err & p=$!; sleep 5; kill $p 2>/dev/null; sleep 1; kill -9 $p 2>/dev/null ) 2>/dev/null
  echo "  $name -> nivel/pico: $(lvl $f) $(head -c 140 $D/$name.err 2>/dev/null | tr '\n' ' ')"; }
{
echo "=== dub-probe $(date) ==="
echo "--- tele"; cat /etc/starfish-release 2>/dev/null | head -2; uname -r; cat /proc/device-tree/model 2>/dev/null; echo
echo "--- binarios"; for b in arecord aplay parec pacat paplay pactl node curl; do printf "%s: %s\n" $b "$(command -v $b || echo NO)"; done
echo "--- 1 /proc/asound/cards"; cat /proc/asound/cards 2>&1
echo "--- 2 /proc/asound/pcm (los que tienen 'capture' sirven para grabar)"; cat /proc/asound/pcm 2>&1
echo "--- 3 arecord -l"; arecord -l 2>&1
echo "--- 4 PulseAudio"; for s in /tmp/pulseaudio/native /tmp/pulse/native /var/run/pulse/native /run/pulse/native /tmp/.pulse/native; do [ -S $s ] && echo "socket: $s"; done
pactl info 2>&1 | head -12
echo "  sources:"; pactl list short sources 2>&1
echo "  sinks:"; pactl list short sinks 2>&1
echo "  sink-inputs (apps que suenan ahora por PulseAudio):"; pactl list sink-inputs 2>&1 | grep -E 'Sink Input #|application.name|media.name|Volume: front' | head -30
echo "--- 5 audiod (luna)"; luna-send -n 1 -f luna://com.webos.service.audio/master/getVolume '{}' 2>&1 | head -c 400; echo
luna-send -n 1 -f luna://com.webos.service.audio/media/getStatus '{}' 2>&1 | head -c 400; echo
echo "--- 6 grabaciones de prueba de 5 s (pon Netflix/Prime con diálogo AHORA)"
if command -v parec >/dev/null || command -v pacat >/dev/null; then
  PR=$(command -v parec || echo "$(command -v pacat) -r")
  rec5 pulse_default_monitor $PR --raw --format=s16le --rate=16000 --channels=1 -d @DEFAULT_MONITOR@; towav $D/pulse_default_monitor.raw 16000 1
  for src in $(pactl list short sources 2>/dev/null | awk '{print $2}' | head -8); do n=pulse_$(echo $src | tr -c 'A-Za-z0-9_\n' '_'); rec5 $n $PR --raw --format=s16le --rate=16000 --channels=1 -d "$src"; towav $D/$n.raw 16000 1; done
fi
if command -v arecord >/dev/null; then
  grep -i capture /proc/asound/pcm 2>/dev/null | while IFS=: read cd rest; do c=$(echo $cd | cut -d- -f1 | sed 's/^0*//'); d=$(echo $cd | cut -d- -f2 | sed 's/^0*//'); c=${c:-0}; d=${d:-0}
    nm=$(echo "$rest" | cut -c1-40); case "$nm" in *[Mm][Ii][Cc]*|*FARFIELD*|*[Ff]ar[Ff]ield*) echo "  hw:$c,$d ($nm) = micrófono, se salta"; continue;; esac
    rec5 hw_${c}_${d}_48k_st arecord -q -D hw:$c,$d -f S16_LE -r 48000 -c 2 -t raw; towav $D/hw_${c}_${d}_48k_st.raw 48000 2
    [ -s $D/hw_${c}_${d}_48k_st.wav ] || { rec5 plughw_${c}_${d}_16k arecord -q -D plughw:$c,$d -f S16_LE -r 16000 -c 1 -t raw; towav $D/plughw_${c}_${d}_16k.raw 16000 1; }
    echo "     ($nm)"; done
fi
echo "--- 7 Harvest daemon"; curl -s -m 3 http://127.0.0.1:8768/hv/dub/status 2>&1 | head -c 600; echo
echo "=== fin. Ficheros: $(ls $D/*.wav 2>/dev/null | tr '\n' ' ')"
echo "Una fuente con nivel > 0.002 mientras suena Netflix = válida. Nivel 0.0000 = esa fuente no lleva el audio de la app."
} 2>&1 | tee $D/report.txt
