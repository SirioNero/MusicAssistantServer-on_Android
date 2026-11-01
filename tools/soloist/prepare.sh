#!/bin/bash
# La prova di Soloist dentro Xonos, la parte sul runner (Linux x86_64). Scarica Soloist da Spotify solo per la
# prova (non va nel repository, negli artefatti né nei log: Spotify non lo lascia ridistribuire), guarda di cosa
# ha bisogno, prepara un piccolo sistema Linux con le sue librerie e prende proot di Termux per Android, che va
# nella build di prova come libproot.so (vedi app/build.gradle.kts). Il riassunto va in $OUT/runner.txt.
set -euo pipefail
OUT=${OUT:-$PWD/soloist-out}
WORK=${WORK:-/tmp/soloist}
ABI=${ABI:-x86_64}
mkdir -p "$OUT" "$WORK"
REPORT="$OUT/runner.txt"
: > "$REPORT"
say() { echo "$@" | tee -a "$REPORT"; }

# 1. Soloist x86_64 (l'emulatore): versione e librerie. Le parole dentro il programma e la versione arm64 le ha
# già guardate la prova di prima: apre da sé libpulse.so.0 (o PipeWire, che qui non c'è) e fa l'annuncio mDNS da
# solo, senza Avahi.
say "== Soloist x86_64"
curl -sSfL -o "$WORK/soloist-x86_64.tar.gz" "https://soloist-builds.spotifycdn.com/soloist_release_x86_64.tar.gz"
mkdir -p "$WORK/x86_64"
tar -xzf "$WORK/soloist-x86_64.tar.gz" -C "$WORK/x86_64"
BIN=$(find "$WORK/x86_64" -type f -name soloist | head -n 1)
[ -n "$BIN" ] || { say "nessun programma soloist nell'archivio"; exit 1; }
timeout 20 "$BIN" --version 2>&1 | tee -a "$REPORT" || say "(--version: uscita $?)"
readelf -d "$BIN" | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/' | tr '\n' ' ' | tee -a "$REPORT"; echo | tee -a "$REPORT"

# 2. Il sistema Linux di prova: glibc e PulseAudio di Ubuntu 24.04 (rootfs.py, come sul telefono), Soloist in
# /opt/soloist, busybox (statico) per lo script che gira dentro (inside.sh). Il DNS è quello dell'emulatore.
ROOT="$WORK/root"
python3 tools/soloist/rootfs.py --arch amd64 --out "$ROOT" --cache "$WORK/rootfs-cache" 2>&1 | tee -a "$REPORT"
mkdir -p "$ROOT/opt/soloist"
cp -a "$WORK/x86_64/." "$ROOT/opt/soloist/"
cp -L "$(command -v busybox)" "$ROOT/usr/bin/busybox"
ln -sf busybox "$ROOT/usr/bin/sh"
cp tools/soloist/inside.sh "$ROOT/opt/xonos-test.sh"
REL=$(cd "$WORK/x86_64" && find . -type f -name soloist | head -n 1)
echo "/opt/soloist/${REL#./}" > "$WORK/soloist-path"
say "== sistema di prova: $(du -sh "$ROOT" | cut -f1), Soloist in $(cat "$WORK/soloist-path")"
tar -C "$ROOT" -cf "$WORK/root.tar" .

# 4. proot di Termux per Android, con le sue librerie, nella build di prova come librerie dell'app. Il RUNPATH
# diventa $ORIGIN: proot trova le sue librerie accanto a sé senza LD_LIBRARY_PATH, che passerebbe anche ai
# programmi glibc dentro proot.
BASE=https://packages.termux.dev/apt/termux-main
curl -sSfL -o "$WORK/Packages" "$BASE/dists/stable/main/binary-$ABI/Packages"
rm -rf "$WORK/termux"
mkdir -p "$WORK/termux"
for pkg in proot libtalloc libandroid-shmem; do
  file=$(awk -v p="$pkg" '$1 == "Package:" {name = $2} $1 == "Filename:" && name == p {print $2; exit}' "$WORK/Packages")
  version=$(awk -v p="$pkg" '$1 == "Package:" {name = $2} $1 == "Version:" && name == p {print $2; exit}' "$WORK/Packages")
  say "== Termux: $pkg $version"
  curl -sSfL -o "$WORK/$pkg.deb" "$BASE/$file"
  dpkg-deb -x "$WORK/$pkg.deb" "$WORK/termux"
done
T="$WORK/termux/data/data/com.termux/files/usr"
JNI="app/src/debug/jniLibs/$ABI"
rm -rf "$JNI"
mkdir -p "$JNI"
cp "$T/bin/proot" "$JNI/libproot.so"
cp "$T/libexec/proot/loader" "$JNI/libproot-loader.so"
cp -L "$T/lib/libtalloc.so.2" "$JNI/libtalloc.so"
cp -L "$(find "$T/lib" -maxdepth 1 -name 'libandroid-shmem.so*' | sort | head -n 1)" "$JNI/libandroid-shmem.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$JNI/libproot.so"
patchelf --set-soname libtalloc.so "$JNI/libtalloc.so"
patchelf --set-rpath '$ORIGIN' "$JNI/libproot.so"
for f in "$JNI"/*.so; do
  say "$(basename "$f"): $(readelf -d "$f" | grep -E 'NEEDED|RUNPATH|SONAME' | sed -E 's/.*\((NEEDED|RUNPATH|SONAME)\).*\[(.*)\]/\1=\2/' | tr '\n' ' ')"
done
ls -l "$JNI" | tee -a "$REPORT"
