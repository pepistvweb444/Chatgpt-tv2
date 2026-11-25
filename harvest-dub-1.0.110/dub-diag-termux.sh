#!/data/data/com.termux/files/usr/bin/sh
# Harvest TV 1.0.110 «Doblaje» – diagnóstico de captura de audio de la tele, lanzado desde Termux en el móvil.
# Uso (con Netflix o Prime reproduciendo algo con diálogo en la tele):
#   sh /sdcard/Download/dub-diag-termux.sh            (tele en 192.168.0.150)
#   sh /sdcard/Download/dub-diag-termux.sh 192.168.0.150
# Solo lee: lista dispositivos de audio y graba 5 s de prueba de cada fuente en /tmp/dubprobe de la tele.
# Copia el informe y las grabaciones a /sdcard/Download/dubprobe/ para escucharlas en el móvil.
TV=${1:-192.168.0.150}
HERE=$(cd "$(dirname "$0")" && pwd)
PROBE="$HERE/dub-probe.sh"
[ -f "$PROBE" ] || { echo "Falta $PROBE (cópialo junto a este script)"; exit 1; }
echo "Conectando a root@$TV … (pon Netflix/Prime con diálogo ahora)"
ssh -o ConnectTimeout=8 root@$TV 'sh -s' < "$PROBE" || { echo "No he podido entrar por SSH en $TV"; exit 1; }
OUT=/sdcard/Download/dubprobe; mkdir -p "$OUT"
scp -q "root@$TV:/tmp/dubprobe/report.txt" "root@$TV:/tmp/dubprobe/*.wav" "$OUT/" 2>/dev/null
echo "Informe y grabaciones en $OUT (escucha los .wav: el que suene a la película es la fuente buena)."
echo "Repite con la TV en directo puesta para comprobarla también."
