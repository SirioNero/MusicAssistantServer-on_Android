#!/bin/sh
# Gira dentro proot, nel sistema Linux di prova, da dentro Xonos (ExecCheckActivity). Fa quello che farà Music
# Assistant sul telefono: avvia PulseAudio come lo avvia lui (helpers/pulse_capture.py), crea il "pipe sink" il cui
# tubo Music Assistant legge, ci fa passare un suono di prova, poi accende Soloist con PulseAudio per 20 s, con una
# chiave finta, per vedere dove arriva.
B=/bin/busybox
P=/tmp/pulse
export HOME=$P
$B rm -rf $P /tmp/soloist-data /tmp/soloist-cache
$B mkdir -p $P
$B chmod 700 $P
echo "load-module module-native-protocol-unix socket=$P/native auth-anonymous=1" > $P/xonos.pa
XDG_RUNTIME_DIR=$P PULSE_RUNTIME_PATH=$P PULSE_STATE_PATH=$P /usr/bin/pulseaudio --daemonize=no -n \
  --exit-idle-time=-1 --file=$P/xonos.pa --disable-shm=yes > $P/pulse.log 2>&1 &
PA=$!
i=0
while [ ! -S $P/native ] && [ $i -lt 100 ]; do
  $B sleep 0.1
  i=$((i + 1))
done
echo "PulseAudio: socket dopo $i decimi di secondo"
export PULSE_SERVER=unix:$P/native
/usr/bin/pactl info 2>&1 | $B grep -E 'Server (String|Name|Version)'
IDX=$(/usr/bin/pactl load-module module-pipe-sink sink_name=xonos file=$P/xonos.pcm format=s32le rate=44100 channels=2)
echo "pipe sink: modulo $IDX"
/usr/bin/pactl set-sink-volume xonos 65536 && echo "volume: ok"
($B timeout 4 $B cat $P/xonos.pcm | $B wc -c > $P/bytes) &
READER=$!
$B sleep 0.3
$B head -c 176400 /dev/urandom | PULSE_SINK=xonos /usr/bin/pacat --raw --format=s16le --rate=44100 --channels=2
echo "pacat (1 s di audio): uscita $?"
wait $READER
echo "byte letti dal tubo in 4 s: $($B cat $P/bytes)"

echo "== Soloist con PulseAudio, 20 s, chiave finta"
PULSE_SINK=xonos $B timeout 20 /opt/soloist/soloist -n "Xonos prova" -k prova -D /tmp/soloist-data -C /tmp/soloist-cache 2>&1 \
  | $B head -n 120
echo "Soloist: finito"
$B ls -la /tmp/soloist-data 2>&1 | $B head -n 20
/usr/bin/pactl unload-module "$IDX" && echo "unload: ok"
$B kill $PA
$B sleep 0.5
echo "== registro di PulseAudio"
$B tail -n 30 $P/pulse.log
