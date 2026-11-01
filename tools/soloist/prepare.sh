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

# 1. Soloist per le due architetture dei telefoni e dell'emulatore.
for arch in x86_64 arm64; do
  say "== Soloist $arch"
  curl -sSfL -o "$WORK/soloist-$arch.tar.gz" "https://soloist-builds.spotifycdn.com/soloist_release_$arch.tar.gz"
  say "archivio: $(stat -c %s "$WORK/soloist-$arch.tar.gz") byte"
  mkdir -p "$WORK/$arch"
  tar -xzf "$WORK/soloist-$arch.tar.gz" -C "$WORK/$arch"
  (cd "$WORK/$arch" && find . -type f -printf '%s %p\n' | sort -k2) | tee -a "$REPORT"
  bin=$(find "$WORK/$arch" -type f -name soloist | head -n 1)
  [ -n "$bin" ] || { say "nessun programma soloist nell'archivio"; exit 1; }
  file "$bin" | sed "s#$WORK/##" | tee -a "$REPORT"
  readelf -l "$bin" | grep -i 'interpreter' | tee -a "$REPORT" || true
  readelf -d "$bin" | grep -E 'NEEDED|RUNPATH|RPATH' | tee -a "$REPORT" || true
  say "glibc più nuova chiesta: $(objdump -T "$bin" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -n 1)"
  say "libstdc++ più nuova chiesta: $(objdump -T "$bin" | grep -o 'GLIBCXX_[0-9.]*' | sort -uV | tail -n 1)"
done

# 2. Soloist x86_64 sul runner: versione, opzioni e librerie che trova.
BIN=$(find "$WORK/x86_64" -type f -name soloist | head -n 1)
say "== Soloist sul runner"
timeout 20 "$BIN" --version 2>&1 | tee -a "$REPORT" || say "(--version: uscita $?)"
timeout 20 "$BIN" --help 2>&1 | head -n 80 | tee -a "$REPORT" || true
ldd "$BIN" | tee -a "$REPORT"

# 3. Il sistema Linux di prova: Soloist e le librerie che ldd ha trovato, ai loro posti.
ROOT="$WORK/root"
rm -rf "$ROOT"
mkdir -p "$ROOT/opt/soloist" "$ROOT/tmp" "$ROOT/etc"
cp -a "$WORK/x86_64/." "$ROOT/opt/soloist/"
for lib in $(ldd "$BIN" | awk '/=> \// {print $3} /^\t\/.*ld-linux/ {print $1}'); do
  cp --parents -L "$lib" "$ROOT"
done
cp -L /etc/ld.so.cache "$ROOT/etc/" 2>/dev/null || true
cp -L /etc/resolv.conf "$ROOT/etc/" 2>/dev/null || true
REL=$(cd "$WORK/x86_64" && find . -type f -name soloist | head -n 1)
echo "/opt/soloist/${REL#./}" > "$WORK/soloist-path"
say "== sistema di prova: $(du -sh "$ROOT" | cut -f1), Soloist in $(cat "$WORK/soloist-path")"
tar -C "$ROOT" -cf "$WORK/root.tar" .

# 4. proot di Termux per Android (con la sua libreria talloc), nella build di prova come librerie dell'app.
BASE=https://packages.termux.dev/apt/termux-main
curl -sSfL -o "$WORK/Packages" "$BASE/dists/stable/main/binary-$ABI/Packages"
mkdir -p "$WORK/termux"
for pkg in proot libtalloc; do
  file=$(awk -v p="$pkg" '$1 == "Package:" {name = $2} $1 == "Filename:" && name == p {print $2; exit}' "$WORK/Packages")
  version=$(awk -v p="$pkg" '$1 == "Package:" {name = $2} $1 == "Version:" && name == p {print $2; exit}' "$WORK/Packages")
  say "== Termux: $pkg $version"
  curl -sSfL -o "$WORK/$pkg.deb" "$BASE/$file"
  dpkg-deb -x "$WORK/$pkg.deb" "$WORK/termux"
done
T="$WORK/termux/data/data/com.termux/files/usr"
readelf -d "$T/bin/proot" | grep -E 'NEEDED|RUNPATH' | tee -a "$REPORT"
JNI="app/src/debug/jniLibs/$ABI"
mkdir -p "$JNI"
cp "$T/bin/proot" "$JNI/libproot.so"
cp "$T/libexec/proot/loader" "$JNI/libproot-loader.so"
cp -L "$T/lib/libtalloc.so.2" "$JNI/libtalloc.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$JNI/libproot.so"
patchelf --set-soname libtalloc.so "$JNI/libtalloc.so"
ls -l "$JNI" | tee -a "$REPORT"
