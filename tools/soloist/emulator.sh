#!/bin/bash
# La prova di Soloist dentro Xonos, la parte sull'emulatore: installa la build di prova (con proot tra le sue
# librerie), mette nei file dell'app il sistema Linux di prova di prepare.sh e fa eseguire i comandi a
# ExecCheckActivity, cioè da dentro il processo di Xonos (permessi, SELinux e seccomp di un'app vera).
# Il riassunto va in $OUT/emulator.txt.
set -uo pipefail
OUT=${OUT:-$PWD/soloist-out}
WORK=${WORK:-/tmp/soloist}
PKG=it.xonos.ma
REPORT="$OUT/emulator.txt"
LAST="$OUT/last.txt"
mkdir -p "$OUT"
: > "$REPORT"
say() { echo "$@" | tee -a "$REPORT"; }

adb wait-for-device
say "== emulatore: Android $(adb shell getprop ro.build.version.release | tr -d '\r') (SDK $(adb shell getprop ro.build.version.sdk | tr -d '\r')), $(adb shell uname -r | tr -d '\r')"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk | tee -a "$REPORT"
# Il percorso contiene "==": si toglie solo il nome prima del primo "=".
DIR=$(adb shell dumpsys package $PKG | grep -m 1 'legacyNativeLibraryDir=' | sed 's/^[^=]*=//' | tr -d '\r')
say "cartella delle librerie: $DIR"
adb shell run-as $PKG ls -l "$DIR/x86_64" 2>&1 | tr -d '\r' | tee -a "$REPORT"

# Il sistema di prova nei file dell'app: prima in /data/local/tmp, poi run-as lo estrae (la build di prova è
# debuggable). Come i file di musica in tools/server/emulator.sh.
adb push "$WORK/root.tar" /data/local/tmp/root.tar > /dev/null
adb shell run-as $PKG sh -c "'rm -rf files/root && mkdir -p files/root && tar -xf /data/local/tmp/root.tar -C files/root && du -sk files/root'" 2>&1 | tr -d '\r' | tee -a "$REPORT"
adb shell rm -f /data/local/tmp/root.tar

# Un comando dentro Xonos: gli argomenti uno per riga, l'ambiente da $ENV_LINES. L'uscita va anche in $LAST.
run() {
  local title=$1
  shift
  say "== $title"
  printf '%s\n' "$@" | adb exec-in "run-as $PKG sh -c 'cat > files/exec-args'"
  printf '%s\n' "${ENV_LINES[@]}" | adb exec-in "run-as $PKG sh -c 'cat > files/exec-env'"
  adb shell am force-stop $PKG
  adb logcat -b all -c
  adb shell am start -W -n $PKG/.ExecCheckActivity > /dev/null
  for _ in $(seq 1 75); do
    adb logcat -d -s XonosExec:I | grep -q 'fine:' && break
    sleep 1
  done
  adb logcat -d -s XonosExec:I | grep -v '^---------' | sed 's/^.*XonosExec: //' | tee "$LAST" | tee -a "$REPORT"
  # Quello che il sistema ha rifiutato in quel momento (SELinux, seccomp) e gli arresti.
  adb logcat -d -b all | grep -E 'avc: *denied|type=1326|SECCOMP|seccomp|SIGSYS|Fatal signal|CANNOT LINK' \
    | grep -v 'XonosExec' | grep -v 'gmscore_app' | tail -n 15 | tee -a "$REPORT"
}

ENV_LINES=(
  "PROOT_LOADER=@LIB@/libproot-loader.so"
  "PROOT_TMP_DIR=@CACHE@"
  "HOME=/root"
)
P=@LIB@/libproot.so
# Il sistema di prova con /dev, /proc e /sys di Android.
GUEST=(--kill-on-exit -r @FILES@/root -b /dev -b /proc -b /sys -w /)
SOLOIST=$(cat "$WORK/soloist-path")

run "proot da solo" $P --version
run "il caricatore di glibc (Ubuntu 24.04) dentro proot" $P "${GUEST[@]}" /lib64/ld-linux-x86-64.so.2 --version
run "le librerie di Soloist dentro proot" $P "${GUEST[@]}" /lib64/ld-linux-x86-64.so.2 --list "$SOLOIST"
run "Soloist --version dentro proot" $P "${GUEST[@]}" "$SOLOIST" --version
# Come un programma glibc dentro proot vede le interfacce di rete: Soloist le elenca per l'annuncio mDNS.
run "le interfacce di rete viste da glibc dentro proot" $P "${GUEST[@]}" /opt/ifaddrs-test
# PulseAudio come lo avvia Music Assistant, un suono nel tubo, poi Soloist con PulseAudio (inside.sh).
run "PulseAudio e Soloist dentro proot" $P "${GUEST[@]}" /bin/busybox sh /opt/xonos-test.sh
exit 0
