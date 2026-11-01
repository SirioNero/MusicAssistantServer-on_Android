#!/bin/bash
# La prova di Soloist dentro Xonos, la parte sul runner (Linux x86_64). Scarica Soloist da Spotify solo per la
# prova (non va nel repository, negli artefatti né nei log: Spotify non lo lascia ridistribuire) e prepara il
# piccolo sistema Linux in cui gira, con glibc e PulseAudio (rootfs.py). proot lo compila la build dell'app
# (app/native/proot.sh). Il riassunto va in $OUT/runner.txt.
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
python3 server/package/rootfs.py --arch amd64 --out "$ROOT" --cache "$WORK/rootfs-cache" 2>&1 | tee -a "$REPORT"
mkdir -p "$ROOT/opt/soloist"
cp -a "$WORK/x86_64/." "$ROOT/opt/soloist/"
cp -L "$(command -v busybox)" "$ROOT/usr/bin/busybox"
ln -sf busybox "$ROOT/usr/bin/sh"
cp tools/soloist/inside.sh "$ROOT/opt/xonos-test.sh"
# Il programma che dice come un programma glibc vede le interfacce di rete (ifaddrs-test.c), compilato con la
# glibc di Ubuntu 24.04 come quella del sistema di prova (quella del runner vuole processori più nuovi).
docker run --rm -v "$PWD/tools/soloist:/src:ro" -v "$ROOT/opt:/out" ubuntu:24.04 sh -c \
  'apt-get -qq update >/dev/null && apt-get -qq install -y gcc libc6-dev >/dev/null && gcc -O2 -o /out/ifaddrs-test /src/ifaddrs-test.c' \
  && say "ifaddrs-test compilato" || say "ifaddrs-test NON compilato"
REL=$(cd "$WORK/x86_64" && find . -type f -name soloist | head -n 1)
echo "/opt/soloist/${REL#./}" > "$WORK/soloist-path"
say "== sistema di prova: $(du -sh "$ROOT" | cut -f1), Soloist in $(cat "$WORK/soloist-path")"
tar -C "$ROOT" -cf "$WORK/root.tar" .
