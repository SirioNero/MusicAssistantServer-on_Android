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
mkdir -p "$OUT"
: > "$REPORT"
say() { echo "$@" | tee -a "$REPORT"; }

adb wait-for-device
say "== emulatore: Android $(adb shell getprop ro.build.version.release | tr -d '\r') (SDK $(adb shell getprop ro.build.version.sdk | tr -d '\r')), $(adb shell uname -r | tr -d '\r')"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk | tee -a "$REPORT"
LIB=$(adb shell pm dump $PKG | grep -m 1 'legacyNativeLibraryDir\|nativeLibraryDir' | sed 's/.*=//' | tr -d '\r')
say "nativeLibraryDir: $LIB"
adb shell ls -l "$LIB/x86_64" "$LIB" 2>&1 | tr -d '\r' | tee -a "$REPORT"

# Il sistema di prova nei file dell'app (run-as legge dall'ingresso: nessun permesso su /data/local/tmp).
adb shell run-as $PKG mkdir -p files
adb exec-in "run-as $PKG sh -c 'cat > files/root.tar'" < "$WORK/root.tar"
adb shell run-as $PKG sh -c "'mkdir -p files/root && tar -xf files/root.tar -C files/root && rm files/root.tar && du -s files/root'" | tee -a "$REPORT"

# Un comando dentro Xonos: gli argomenti uno per riga, l'ambiente da $ENV_LINES.
run() {
  local title=$1
  shift
  say "== $title"
  printf '%s\n' "$@" | adb exec-in "run-as $PKG sh -c 'cat > files/exec-args'"
  printf '%s\n' "${ENV_LINES[@]}" | adb exec-in "run-as $PKG sh -c 'cat > files/exec-env'"
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -W -n $PKG/.ExecCheckActivity > /dev/null
  for _ in $(seq 1 75); do
    adb logcat -d -s XonosExec:I | grep -q 'fine:' && break
    sleep 1
  done
  adb logcat -d -s XonosExec:I | sed 's/^.*XonosExec: //' | tee -a "$REPORT"
  # Quello che il sistema ha rifiutato in quel momento (SELinux, seccomp).
  adb logcat -d | grep -E 'avc: *denied|SECCOMP|seccomp|SIGSYS' | tail -n 15 | tee -a "$REPORT"
}

ENV_LINES=(
  "LD_LIBRARY_PATH=@LIB@"
  "PROOT_LOADER=@LIB@/libproot-loader.so"
  "PROOT_TMP_DIR=@CACHE@"
  "HOME=@FILES@"
)
SOLOIST=$(cat "$WORK/soloist-path")
run "proot da solo" @LIB@/libproot.so --version
run "un programma del sistema di prova dentro proot" @LIB@/libproot.so -r @FILES@/root -w / /lib64/ld-linux-x86-64.so.2 --version
run "le librerie di Soloist dentro proot" @LIB@/libproot.so -r @FILES@/root -w / /lib64/ld-linux-x86-64.so.2 --list "$SOLOIST"
run "Soloist --version dentro proot" @LIB@/libproot.so --kill-on-exit -r @FILES@/root -b /dev -b /proc -b /sys -w / "$SOLOIST" --version
run "Soloist --help dentro proot" @LIB@/libproot.so --kill-on-exit -r @FILES@/root -b /dev -b /proc -b /sys -w / "$SOLOIST" --help
exit 0
